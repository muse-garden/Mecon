export const PlaybackInstrument = Object.freeze({
  default: "default",
  piano: "piano",
  organ: "organ",
});

export const ORGAN_PRESETS = Object.freeze([
  Object.freeze({ value: 0, label: "Principal Chorus" }),
  Object.freeze({ value: 1, label: "Foundations" }),
  Object.freeze({ value: 2, label: "Soft Flutes" }),
  Object.freeze({ value: 3, label: "Solo Trumpet" }),
  Object.freeze({ value: 4, label: "String Celeste" }),
  Object.freeze({ value: 5, label: "Cornet" }),
  Object.freeze({ value: 6, label: "Vox Humana" }),
  Object.freeze({ value: 7, label: "Full Organ" }),
]);

const RHODY_FAMILY = Object.freeze({ piano: 2, organ: 3 });
const ROOM_PRESETS = Object.freeze({
  piano: Object.freeze({ name: "small_studio", rt60Seconds: 0.38, preDelayMs: 4, wet: 0.18 }),
  organ: Object.freeze({ name: "church", rt60Seconds: 3.8, preDelayMs: 24, wet: 0.78 }),
});

export function resolveRhodyWasmUrl(
  baseUrl = import.meta.env?.BASE_URL ?? "/",
) {
  const pageUrl = globalThis.window?.location?.href ?? "http://localhost/";
  const normalizedBase = String(baseUrl || "/").endsWith("/") ? baseUrl : `${baseUrl}/`;
  return new URL("rhody/rhody_wasm.wasm", new URL(normalizedBase, pageUrl)).href;
}

function createRoomImpulse(context, preset) {
  const preDelayFrames = Math.round(context.sampleRate * preset.preDelayMs / 1_000);
  const length = Math.max(1, preDelayFrames + Math.round(context.sampleRate * preset.rt60Seconds));
  const impulse = context.createBuffer(2, length, context.sampleRate);
  // Fixed seeds make the room response stable across transports and tests.
  let seed = preset.name === "church" ? 0x43d2a91f : 0x19f02b65;
  const random = () => {
    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
    return ((seed >>> 0) / 0xffffffff) * 2 - 1;
  };
  for (let channel = 0; channel < 2; channel++) {
    const samples = impulse.getChannelData(channel);
    for (let index = preDelayFrames; index < length; index++) {
      const elapsed = (index - preDelayFrames) / context.sampleRate;
      const envelope = 10 ** (-3 * elapsed / preset.rt60Seconds);
      samples[index] = random() * envelope * (channel ? 0.92 : 1);
    }
  }
  return impulse;
}

function connectOutput(context, source, instrument, reverbEnabled) {
  const room = ROOM_PRESETS[instrument];
  const output = context.createGain();
  output.gain.value = 0.9;
  output.connect(context.destination);
  if (!reverbEnabled) {
    source.connect(output);
    return { output, nodes: [] };
  }
  const dry = context.createGain();
  const wet = context.createGain();
  const convolver = context.createConvolver();
  dry.gain.value = 1;
  wet.gain.value = room.wet;
  convolver.buffer = createRoomImpulse(context, room);
  source.connect(dry).connect(output);
  source.connect(convolver).connect(wet).connect(output);
  return { output, nodes: [dry, wet, convolver] };
}

export async function createRhodyPlayback(context, {
  instrument,
  organPreset = 0,
  reverbEnabled = true,
  wasmUrl = resolveRhodyWasmUrl(),
  onDiagnostic = () => {},
} = {}) {
  if (!wasmUrl || !(instrument in RHODY_FAMILY)) return null;
  const processorUrl = new URL("./rhody-playback-processor.js", import.meta.url);
  const [wasmBytes] = await Promise.all([
    fetch(wasmUrl).then(async (response) => {
      if (!response.ok) throw new Error(`Rhody WASM request failed: ${response.status}`);
      return response.arrayBuffer();
    }),
    context.audioWorklet.addModule(processorUrl),
  ]);
  const node = new AudioWorkletNode(context, "mecon-rhody-keyboard", {
    numberOfInputs: 0,
    numberOfOutputs: 1,
    outputChannelCount: [2],
  });
  const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error("Rhody AudioWorklet initialization timed out"));
    }, 3_000);
    const onMessage = ({ data }) => {
      if (data.type === "ready") { cleanup(); resolve(); }
      else if (data.type === "error") { cleanup(); reject(new Error(data.message)); }
      else if (data.type === "diagnostic") onDiagnostic(data);
    };
    const onProcessorError = () => {
      cleanup();
      reject(new Error("Rhody AudioWorklet processor failed during initialization"));
    };
    const cleanup = () => {
      clearTimeout(timeout);
      node.port.removeEventListener("message", onMessage);
      node.removeEventListener("processorerror", onProcessorError);
    };
    node.port.addEventListener("message", onMessage);
    node.addEventListener("processorerror", onProcessorError);
    node.port.start();
  });
  node.port.postMessage(
    { type: "init", wasmBytes, family: RHODY_FAMILY[instrument] },
    [wasmBytes],
  );
  try {
    await ready;
  } catch (error) {
    node.port.close();
    node.disconnect();
    throw error;
  }
  node.port.addEventListener("message", ({ data }) => {
    if (data.type === "diagnostic" || data.type === "error") onDiagnostic(data);
  });
  node.addEventListener("processorerror", () => onDiagnostic({
    type: "error", message: "Rhody AudioWorklet processor stopped",
  }));
  if (instrument === PlaybackInstrument.organ) {
    node.port.postMessage({ type: "preset", value: organPreset });
  }
  const graph = connectOutput(context, node, instrument, reverbEnabled);
  const frameAt = (when) => Math.round((when ?? context.currentTime) * context.sampleRate);
  return Object.freeze({
    instrument,
    roomPreset: reverbEnabled ? ROOM_PRESETS[instrument].name : null,
    noteOn: (frequency, velocity, when) => node.port.postMessage({
      type: "noteOn", frequency, velocity, frame: frameAt(when),
    }),
    noteOff: (frequency, when) => node.port.postMessage({
      type: "noteOff", frequency, frame: frameAt(when),
    }),
    reset: () => node.port.postMessage({ type: "reset" }),
    dispose: () => {
      node.port.postMessage({ type: "dispose" });
      node.disconnect();
      graph.output.disconnect();
      for (const graphNode of graph.nodes) graphNode.disconnect();
    },
  });
}
