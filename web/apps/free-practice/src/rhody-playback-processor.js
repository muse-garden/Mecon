class MeconRhodyKeyboardProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.exports = null;
    this.handle = 0;
    this.events = [];
    this.reportedAudio = false;
    this.port.onmessage = ({ data }) => {
      try {
        if (data.type === "init") {
          const module = new WebAssembly.Module(data.wasmBytes);
          const instance = new WebAssembly.Instance(module, {});
          this.exports = instance.exports;
          this.handle = this.exports.rhody_wasm_create(sampleRate, data.family ?? 2);
          this.port.postMessage({ type: "ready" });
        } else if (data.type === "reset") {
          this.events = [];
          this.exports?.rhody_wasm_all_notes_off(this.handle);
        } else if (data.type === "dispose") {
          this.events = [];
          if (this.exports && this.handle) this.exports.rhody_wasm_destroy(this.handle);
          this.handle = 0;
        } else if (this.exports && data.type === "preset") {
          this.exports.rhody_wasm_set_preset(this.handle, data.value);
        } else if (this.exports) {
          this.events.push(data);
          this.events.sort((left, right) => (left.frame ?? currentFrame) - (right.frame ?? currentFrame));
        }
      } catch (error) {
        this.port.postMessage({ type: "error", message: error?.message ?? String(error) });
      }
    };
  }

  applyEvent(data) {
    if (data.type === "noteOn") {
      this.exports.rhody_wasm_note_on(this.handle, data.frequency, data.velocity);
      this.port.postMessage({ type: "diagnostic", stage: "noteOn", frequency: data.frequency });
    } else if (data.type === "noteOff") {
      this.exports.rhody_wasm_note_off(this.handle, data.frequency);
    }
  }

  renderInto(channel, offset, frames) {
    if (frames <= 0 || !this.handle) return;
    const pointer = this.exports.rhody_wasm_render(this.handle, frames);
    const rendered = new Float32Array(this.exports.memory.buffer, pointer, frames);
    channel.set(rendered, offset);
    if (!this.reportedAudio) {
      let peak = 0;
      for (const sample of rendered) peak = Math.max(peak, Math.abs(sample));
      if (peak > 1e-5) {
        this.reportedAudio = true;
        this.port.postMessage({ type: "diagnostic", stage: "audio", peak });
      }
    }
  }

  process(_inputs, outputs) {
    try {
      const channels = outputs[0];
      const channel = channels?.[0];
      if (!this.exports || !this.handle || !channel) return true;
      const start = currentFrame;
      const end = start + channel.length;
      let cursor = 0;
      while (this.events.length && (this.events[0].frame ?? start) < end) {
        const event = this.events.shift();
        const offset = Math.max(cursor, Math.min(channel.length, (event.frame ?? start) - start));
        this.renderInto(channel, cursor, offset - cursor);
        this.applyEvent(event);
        cursor = offset;
      }
      this.renderInto(channel, cursor, channel.length - cursor);
      for (let index = 1; index < channels.length; index++) channels[index].set(channel);
      return true;
    } catch (error) {
      this.port.postMessage({ type: "error", message: error?.message ?? String(error) });
      return false;
    }
  }
}

registerProcessor("mecon-rhody-keyboard", MeconRhodyKeyboardProcessor);
