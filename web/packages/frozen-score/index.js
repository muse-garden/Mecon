export const FROZEN_SCHEMA_VERSION = 1;

const value = (input) =>
  typeof input === "number" ? input : Number(input?.value ?? input ?? 0);
const point = (input) => ({ x: value(input?.x), y: value(input?.y) });
const rect = (input) => ({
  x: value(input?.origin?.x),
  y: value(input?.origin?.y),
  width: value(input?.width),
  height: value(input?.height),
});
const commandKind = (command) =>
  String(command?.type ?? command?.kind ?? command?.["@type"] ?? "")
    .split(".")
    .at(-1);
const segmentKind = commandKind;
const color = (input, omittedDefault = "none") => {
  if (input === undefined) return omittedDefault;
  if (input === null) return "none";
  const alpha = Math.max(0, Math.min(255, Number(input.alpha ?? 255))) / 255;
  return `rgba(${input.red ?? 0},${input.green ?? 0},${input.blue ?? 0},${alpha})`;
};
const cap = (input) => String(input ?? "BUTT").toLowerCase();
const escapeXml = (input) =>
  String(input)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

export function parseFrozenScore(input) {
  const bundle = typeof input === "string" ? JSON.parse(input) : input;
  if (!bundle || !Array.isArray(bundle.surfaces)) {
    throw new TypeError("FrozenScoreBundle must contain a surfaces array");
  }
  const schemaVersion = Number(bundle.schemaVersion ?? 1);
  if (!Number.isInteger(schemaVersion) || schemaVersion < 1) {
    throw new Error(`Unsupported frozen geometry schema: ${bundle.schemaVersion}`);
  }
  return {
    ...bundle,
    surfaces: bundle.surfaces.map((surface, index) => ({
      index,
      contentOffsetY: 0,
      elements: [],
      ...surface,
      elements: Array.isArray(surface?.elements) ? surface.elements : [],
    })),
    timePositions: Array.isArray(bundle.timePositions) ? bundle.timePositions : [],
  };
}

export async function loadMecon(source, options = {}) {
  const archive = await loadMeconArchive(source);
  const { entries, manifest } = archive;
  const manifestBytes = entries.get("manifest.json");
  if (!manifestBytes) throw new Error("Invalid .mecon: manifest.json is missing");
  const scoreRef = options.scoreId
    ? manifest.scores?.find((item) => item.id === options.scoreId)
    : manifest.scores?.find((item) => item.id === manifest.activeScoreId) ?? manifest.scores?.[0];
  if (!scoreRef) throw new Error("Invalid .mecon: no score is listed in the manifest");
  if (!scoreRef.geometryPath) {
    throw new Error(`Score ${scoreRef.id} has no frozen geometry`);
  }
  const geometryBytes = entries.get(scoreRef.geometryPath);
  if (!geometryBytes) {
    throw new Error(`Invalid .mecon: ${scoreRef.geometryPath} is missing`);
  }
  return {
    manifest,
    scoreRef,
    bundle: parseFrozenScore(new TextDecoder().decode(geometryBytes)),
  };
}

/**
 * Read every physical entry so an editor can replace only the active score/geometry and preserve
 * unknown modules, inactive scores, and future container extensions byte-for-byte.
 */
export async function loadMeconArchive(source) {
  const bytes = source instanceof Blob
    ? new Uint8Array(await source.arrayBuffer())
    : source instanceof Uint8Array
      ? source
      : new Uint8Array(source);
  const entries = await unzipEntries(bytes);
  const manifestBytes = entries.get("manifest.json");
  if (!manifestBytes) throw new Error("Invalid .mecon: manifest.json is missing");
  return {
    entries,
    manifest: JSON.parse(new TextDecoder().decode(manifestBytes)),
  };
}

/** Parse editable JSON while retaining future/non-JSON modules as opaque archive entries. */
export async function loadMeconDocument(source) {
  const archive = await loadMeconArchive(source);
  const decoder = new TextDecoder();
  const scores = new Map();
  const modules = new Map();
  const opaqueModules = new Map();
  for (const ref of archive.manifest.scores ?? []) {
    const bytes = archive.entries.get(ref.path);
    if (!bytes) throw new Error(`Invalid .mecon: ${ref.path} is missing`);
    scores.set(ref.id, JSON.parse(decoder.decode(bytes)));
  }
  for (const ref of archive.manifest.modules ?? []) {
    const bytes = archive.entries.get(ref.path);
    if (!bytes) throw new Error(`Invalid .mecon: ${ref.path} is missing`);
    try {
      modules.set(ref.id, JSON.parse(decoder.decode(bytes)));
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error;
      opaqueModules.set(ref.id, bytes);
    }
  }
  return { ...archive, scores, modules, opaqueModules };
}

/**
 * Replace explicitly changed logical entries and retain every other archive byte. Maps are keyed by
 * manifest score/module ids rather than guessed paths. Geometry values are FrozenScoreBundle JSON.
 */
export function writeMeconDocument(document, changes = {}) {
  const manifest = changes.manifest ?? document.manifest;
  const replacements = new Map();
  if (changes.manifest) replacements.set("manifest.json", JSON.stringify(manifest));
  replaceReferencedJson(replacements, manifest.scores, changes.scores, "score");
  replaceReferencedJson(replacements, manifest.modules, changes.modules, "module");
  if (changes.geometries) {
    for (const [scoreId, value] of changes.geometries) {
      const ref = manifest.scores?.find((item) => item.id === scoreId);
      if (!ref) throw new Error(`Unknown .mecon score id: ${scoreId}`);
      if (!ref.geometryPath) throw new Error(`Score ${scoreId} has no geometryPath`);
      replacements.set(ref.geometryPath, jsonEntry(value));
    }
  }
  return writeMeconArchive(document, replacements);
}

function replaceReferencedJson(replacements, refs = [], changes, kind) {
  if (!changes) return;
  for (const [id, value] of changes) {
    const ref = refs.find((item) => item.id === id);
    if (!ref) throw new Error(`Unknown .mecon ${kind} id: ${id}`);
    replacements.set(ref.path, jsonEntry(value));
  }
}

function jsonEntry(value) {
  return typeof value === "string" ? value : JSON.stringify(value);
}

/**
 * Write a standards-compliant ZIP using stored entries. Stored output is intentionally simple and
 * deterministic; desktop ZipInputStream and browsers both accept it. CRC-32 is emitted so desktop
 * readers can validate data integrity. Values may be UTF-8 strings, ArrayBuffers, or Uint8Arrays.
 */
export function writeMeconArchive(source, replacements) {
  const sourceEntries = source instanceof Map ? source : source?.entries;
  if (!(sourceEntries instanceof Map)) {
    throw new TypeError("writeMeconArchive expects a Map or an archive returned by loadMeconArchive");
  }
  const entries = new Map(sourceEntries);
  if (replacements) {
    const updates = replacements instanceof Map ? replacements : Object.entries(replacements);
    for (const [path, value] of updates) {
      if (value == null) entries.delete(path);
      else entries.set(path, entryBytes(value));
    }
  }
  if (!entries.has("manifest.json")) {
    throw new Error("Invalid .mecon: manifest.json is missing");
  }

  const encoder = new TextEncoder();
  const locals = [];
  const centrals = [];
  let localOffset = 0;
  for (const [path, value] of entries) {
    const name = String(path);
    if (!name || name.endsWith("/") || name.includes("\\")) {
      throw new Error(`Invalid .mecon entry path: ${name}`);
    }
    const nameBytes = encoder.encode(name);
    const data = entryBytes(value);
    const crc = crc32(data);

    const local = new Uint8Array(30 + nameBytes.length + data.length);
    const localView = new DataView(local.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true);
    localView.setUint16(6, 0x0800, true);
    localView.setUint16(8, 0, true);
    localView.setUint32(14, crc, true);
    localView.setUint32(18, data.length, true);
    localView.setUint32(22, data.length, true);
    localView.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    local.set(data, 30 + nameBytes.length);
    locals.push(local);

    const central = new Uint8Array(46 + nameBytes.length);
    const centralView = new DataView(central.buffer);
    centralView.setUint32(0, 0x02014b50, true);
    centralView.setUint16(4, 20, true);
    centralView.setUint16(6, 20, true);
    centralView.setUint16(8, 0x0800, true);
    centralView.setUint16(10, 0, true);
    centralView.setUint32(16, crc, true);
    centralView.setUint32(20, data.length, true);
    centralView.setUint32(24, data.length, true);
    centralView.setUint16(28, nameBytes.length, true);
    centralView.setUint32(42, localOffset, true);
    central.set(nameBytes, 46);
    centrals.push(central);
    localOffset += local.length;
  }
  if (entries.size > 0xffff) throw new Error("Too many .mecon entries for ZIP32");
  const centralSize = centrals.reduce((sum, item) => sum + item.length, 0);
  const end = new Uint8Array(22);
  const endView = new DataView(end.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(8, entries.size, true);
  endView.setUint16(10, entries.size, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, localOffset, true);
  return concatBytes([...locals, ...centrals, end]);
}

function entryBytes(value) {
  if (typeof value === "string") return new TextEncoder().encode(value);
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  throw new TypeError(".mecon entry values must be strings, ArrayBuffers, or Uint8Arrays");
}

function concatBytes(parts) {
  const output = new Uint8Array(parts.reduce((sum, item) => sum + item.length, 0));
  let cursor = 0;
  for (const part of parts) {
    output.set(part, cursor);
    cursor += part.length;
  }
  return output;
}

const CRC32_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let value = 0; value < 256; value++) {
    let crc = value;
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1);
    }
    table[value] = crc >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const value of bytes) crc = CRC32_TABLE[(crc ^ value) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

async function unzipEntries(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let index = bytes.byteLength - 22; index >= Math.max(0, bytes.byteLength - 65557); index--) {
    if (view.getUint32(index, true) === 0x06054b50) {
      eocd = index;
      break;
    }
  }
  if (eocd < 0) throw new Error("Invalid .mecon: ZIP central directory not found");
  const count = view.getUint16(eocd + 10, true);
  let cursor = view.getUint32(eocd + 16, true);
  const result = new Map();
  const decoder = new TextDecoder();
  for (let entryIndex = 0; entryIndex < count; entryIndex++) {
    if (view.getUint32(cursor, true) !== 0x02014b50) throw new Error("Invalid ZIP directory entry");
    const method = view.getUint16(cursor + 10, true);
    const compressedSize = view.getUint32(cursor + 20, true);
    const nameLength = view.getUint16(cursor + 28, true);
    const extraLength = view.getUint16(cursor + 30, true);
    const commentLength = view.getUint16(cursor + 32, true);
    const localOffset = view.getUint32(cursor + 42, true);
    const name = decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
    if (view.getUint32(localOffset, true) !== 0x04034b50) throw new Error("Invalid ZIP local entry");
    const localNameLength = view.getUint16(localOffset + 26, true);
    const localExtraLength = view.getUint16(localOffset + 28, true);
    const start = localOffset + 30 + localNameLength + localExtraLength;
    const compressed = bytes.slice(start, start + compressedSize);
    let data;
    if (method === 0) {
      data = compressed;
    } else if (method === 8) {
      if (typeof DecompressionStream === "undefined") {
        throw new Error("This browser lacks DecompressionStream; provide geometry JSON directly");
      }
      const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
      data = new Uint8Array(await new Response(stream).arrayBuffer());
    } else {
      throw new Error(`Unsupported ZIP compression method: ${method}`);
    }
    result.set(name, data);
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return result;
}

export async function loadMusicFont(url, family = "Bravura") {
  const font = new FontFace(family, `url(${JSON.stringify(url).slice(1, -1)})`);
  await font.load();
  document.fonts.add(font);
  return font;
}

export function hitTest(bundleInput, surfaceIndex, x, y, options = {}) {
  const bundle = parseFrozenScore(bundleInput);
  const surface = bundle.surfaces.find((item) => item.index === surfaceIndex)
    ?? bundle.surfaces[surfaceIndex];
  if (!surface) return null;
  const origin = surfaceOrigin(bundle, surface);
  const surfaceX = x + origin.x;
  const surfaceY = y + origin.y;
  const types = options.types ? new Set(options.types) : null;
  for (let index = surface.elements.length - 1; index >= 0; index--) {
    const element = surface.elements[index];
    if (types && !types.has(element.type)) continue;
    const box = rect(element.hitBox);
    const padding = Number(options.padding ?? 0);
    if (
      surfaceX >= box.x - padding && surfaceX <= box.x + box.width + padding &&
      surfaceY >= box.y - padding && surfaceY <= box.y + box.height + padding
    ) return element;
  }
  return null;
}

export function renderCanvas(canvas, bundleInput, options = {}) {
  const bundle = parseFrozenScore(bundleInput);
  const surfaceIndex = options.surfaceIndex ?? 0;
  const surface = bundle.surfaces.find((item) => item.index === surfaceIndex)
    ?? bundle.surfaces[surfaceIndex];
  if (!surface) throw new RangeError(`Unknown surface ${surfaceIndex}`);
  const ratio = options.pixelRatio ?? globalThis.devicePixelRatio ?? 1;
  canvas.width = Math.max(1, Math.ceil(surface.width * ratio));
  canvas.height = Math.max(1, Math.ceil(surface.height * ratio));
  canvas.style.width = `${surface.width}px`;
  canvas.style.height = `${surface.height}px`;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas2D is unavailable");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, surface.width, surface.height);
  if (options.background) {
    context.fillStyle = options.background;
    context.fillRect(0, 0, surface.width, surface.height);
  }
  const origin = surfaceOrigin(bundle, surface);
  context.translate(-origin.x, -origin.y);
  const selected = new Set(options.selectedIds ?? []);
  const hidden = new Set(options.hiddenIds ?? []);
  for (const element of surface.elements) {
    if (hidden.has(String(element.id))) continue;
    const offset = elementOffset(options.elementOffsets, element.id);
    context.save();
    context.translate(offset.x, offset.y);
    try {
      for (const command of element.commands ?? []) {
        drawCanvasCommand(
          context,
          command,
          options,
          elementTint(options.elementTints, element.id) ?? (
            selected.has(String(element.id)) && options.selectionMode === "tint"
              ? (options.selectionColor ?? "#2878ff")
              : null
          ),
        );
      }
      if (selected.has(String(element.id)) && options.selectionMode !== "tint") {
        drawSelectionCanvas(context, element, options);
      }
    } finally {
      context.restore();
    }
  }
  for (const layer of options.commandLayers ?? []) {
    for (const command of layer.commands ?? []) {
      drawCanvasCommand(context, command, options, layer.color ?? null);
    }
  }
  return surface;
}

function elementOffset(offsets, id) {
  const offset = offsets instanceof Map ? offsets.get(String(id)) ?? offsets.get(id) : offsets?.[String(id)];
  return { x: value(offset?.x), y: value(offset?.y) };
}

function elementTint(tints, id) {
  return tints instanceof Map ? tints.get(String(id)) ?? tints.get(id) : tints?.[String(id)];
}

function drawCanvasCommand(context, command, options, tint = null) {
  context.save();
  try {
    switch (commandKind(command)) {
      case "DrawLine": {
        const start = point(command.start);
        const end = point(command.end);
        context.beginPath();
        context.moveTo(start.x, start.y);
        context.lineTo(end.x, end.y);
        context.strokeStyle = tint ?? color(command.color, "#000");
        context.lineWidth = value(command.thickness);
        context.lineCap = cap(command.cap);
        context.setLineDash(command.dashIntervals ?? []);
        context.stroke();
        break;
      }
      case "DrawRect": {
        const box = rect(command.rect);
        paintCanvasShape(context, command, () => context.rect(box.x, box.y, box.width, box.height), tint);
        break;
      }
      case "DrawEllipse": {
        const center = point(command.center);
        paintCanvasShape(context, command, () =>
          context.ellipse(center.x, center.y, value(command.radiusX), value(command.radiusY), 0, 0, Math.PI * 2), tint);
        break;
      }
      case "DrawPath":
        paintCanvasShape(context, command, () => appendCanvasPath(context, command.path?.segments ?? []), tint);
        break;
      case "DrawBezier": {
        const curve = command.curve;
        const p0 = point(curve.p0), p1 = point(curve.p1), p2 = point(curve.p2), p3 = point(curve.p3);
        context.beginPath();
        context.moveTo(p0.x, p0.y);
        context.bezierCurveTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y);
        context.strokeStyle = tint ?? color(command.color, "#000");
        context.lineWidth = value(command.filled ? command.midpointThickness : command.endpointThickness);
        context.lineCap = "round";
        context.stroke();
        break;
      }
      case "DrawGlyph": {
        const p = point(command.position);
        context.translate(p.x, p.y);
        context.scale(command.scaleX ?? 1, command.scaleY ?? 1);
        context.fillStyle = tint ?? color(command.color, "#000");
        context.font = `${value(command.fontSize)}px ${JSON.stringify(options.musicFontFamily ?? "Bravura")}`;
        context.textAlign = "left";
        context.textBaseline = "alphabetic";
        context.fillText(command.glyph?.codepoint ?? "", 0, 0);
        break;
      }
      case "DrawText":
        drawCanvasText(context, command, tint);
        break;
      case "RenderGroup":
        if (command.clipRect) {
          const box = rect(command.clipRect);
          context.beginPath();
          context.rect(box.x, box.y, box.width, box.height);
          context.clip();
        }
        context.globalAlpha *= command.opacity ?? 1;
        for (const child of command.commands ?? []) drawCanvasCommand(context, child, options, tint);
        break;
      default:
        options.onUnknownCommand?.(command);
    }
  } finally {
    context.restore();
  }
}

function paintCanvasShape(context, command, append, tint = null) {
  context.beginPath();
  append();
  const fillColor = color(command.fillColor, "#000");
  if (fillColor !== "none") {
    context.fillStyle = tint ?? fillColor;
    context.fill();
  }
  if (command.strokeColor) {
    context.strokeStyle = tint ?? color(command.strokeColor);
    context.lineWidth = value(command.strokeThickness);
    context.stroke();
  }
}

function appendCanvasPath(context, segments) {
  for (const segment of segments) {
    switch (segmentKind(segment)) {
      case "MoveTo": {
        const p = point(segment.point); context.moveTo(p.x, p.y); break;
      }
      case "LineTo": {
        const p = point(segment.point); context.lineTo(p.x, p.y); break;
      }
      case "QuadTo": {
        const c = point(segment.control), e = point(segment.end);
        context.quadraticCurveTo(c.x, c.y, e.x, e.y); break;
      }
      case "CubicTo": {
        const c1 = point(segment.control1), c2 = point(segment.control2), e = point(segment.end);
        context.bezierCurveTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y); break;
      }
      case "Close": context.closePath(); break;
    }
  }
}

function drawCanvasText(context, command, tint = null) {
  const p = point(command.position);
  context.fillStyle = tint ?? color(command.color, "#000");
  context.textAlign = String(command.alignment ?? "LEFT").toLowerCase();
  context.textBaseline = "alphabetic";
  const style = String(command.fontStyle ?? "NORMAL") === "ITALIC" ? "italic" : "normal";
  const weight = String(command.fontWeight ?? "NORMAL") === "BOLD" ? "bold" : "normal";
  context.font = `${style} ${weight} ${value(command.fontSize)}px ${JSON.stringify(command.fontFamily ?? "serif")}`;
  if (!command.richText?.runs?.length) {
    context.fillText(command.text ?? "", p.x, p.y);
    return;
  }
  let x = p.x;
  for (const run of command.richText.runs) {
    const runSize = value(command.fontSize) * (run.style?.sizeScale ?? 1);
    const runStyle = run.style?.italic ? "italic" : style;
    const runWeight = run.style?.bold ? "bold" : weight;
    context.font = `${runStyle} ${runWeight} ${runSize}px ${JSON.stringify(command.fontFamily ?? "serif")}`;
    context.fillStyle = tint ?? color(run.style?.color ?? command.color, "#000");
    const baseline = run.style?.baseline;
    const y = p.y + (baseline === "SUPERSCRIPT" ? -runSize * 0.35 : baseline === "SUBSCRIPT" ? runSize * 0.25 : 0);
    context.fillText(run.text, x, y);
    x += context.measureText(run.text).width;
  }
}

function drawSelectionCanvas(context, element, options) {
  const box = rect(element.hitBox);
  context.save();
  context.strokeStyle = options.selectionColor ?? "#2878ff";
  context.lineWidth = options.selectionWidth ?? 1.5;
  context.setLineDash(options.selectionDash ?? [4, 3]);
  context.strokeRect(box.x, box.y, box.width, box.height);
  context.restore();
}

export function renderSvg(bundleInput, options = {}) {
  const bundle = parseFrozenScore(bundleInput);
  const surfaceIndex = options.surfaceIndex ?? 0;
  const surface = bundle.surfaces.find((item) => item.index === surfaceIndex)
    ?? bundle.surfaces[surfaceIndex];
  if (!surface) throw new RangeError(`Unknown surface ${surfaceIndex}`);
  const origin = surfaceOrigin(bundle, surface);
  const selected = new Set(options.selectedIds ?? []);
  const hidden = new Set(options.hiddenIds ?? []);
  const content = surface.elements.map((element) => {
    if (hidden.has(String(element.id))) return "";
    const tint = elementTint(options.elementTints, element.id);
    const commands = (element.commands ?? []).map((command) => svgCommand(command, options, tint)).join("");
    const selection = selected.has(String(element.id)) ? svgSelection(element, options) : "";
    const offset = elementOffset(options.elementOffsets, element.id);
    const transform = offset.x || offset.y ? ` transform="translate(${offset.x} ${offset.y})"` : "";
    return `<g data-mecon-id="${escapeXml(element.id)}" data-mecon-type="${escapeXml(element.type)}"${transform}>${commands}${selection}</g>`;
  }).join("");
  const background = options.background
    ? `<rect x="${origin.x}" y="${origin.y}" width="${surface.width}" height="${surface.height}" fill="${escapeXml(options.background)}"/>`
    : "";
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${surface.width}" height="${surface.height}" viewBox="${origin.x} ${origin.y} ${surface.width} ${surface.height}" role="img">${background}${content}</svg>`;
}

function surfaceOrigin(bundle, surface) {
  if (bundle.paginated) return { x: 0, y: 0 };
  const bounds = rect(bundle.bounds);
  return { x: bounds.x, y: bounds.y };
}

export function renderSvgElement(svg, bundle, options = {}) {
  const markup = renderSvg(bundle, options);
  const document = new DOMParser().parseFromString(markup, "image/svg+xml");
  const rendered = document.documentElement;
  for (const attribute of [...rendered.attributes]) svg.setAttribute(attribute.name, attribute.value);
  svg.replaceChildren(...[...rendered.childNodes].map((node) => svg.ownerDocument.importNode(node, true)));
  return svg;
}

function svgCommand(command, options, tint = null) {
  switch (commandKind(command)) {
    case "DrawLine": {
      const a = point(command.start), b = point(command.end);
      const dash = command.dashIntervals?.length ? ` stroke-dasharray="${command.dashIntervals.join(" ")}"` : "";
      return `<line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" stroke="${tint ?? color(command.color, "#000")}" stroke-width="${value(command.thickness)}" stroke-linecap="${cap(command.cap)}"${dash}/>`;
    }
    case "DrawRect": {
      const box = rect(command.rect);
      return `<rect x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}" ${svgPaint(command, tint)}/>`;
    }
    case "DrawEllipse": {
      const center = point(command.center);
      return `<ellipse cx="${center.x}" cy="${center.y}" rx="${value(command.radiusX)}" ry="${value(command.radiusY)}" ${svgPaint(command, tint)}/>`;
    }
    case "DrawPath":
      return `<path d="${svgPath(command.path?.segments ?? [])}" ${svgPaint(command, tint)}/>`;
    case "DrawBezier": {
      const c = command.curve;
      const p0 = point(c.p0), p1 = point(c.p1), p2 = point(c.p2), p3 = point(c.p3);
      const width = value(command.filled ? command.midpointThickness : command.endpointThickness);
      return `<path d="M${p0.x} ${p0.y} C${p1.x} ${p1.y} ${p2.x} ${p2.y} ${p3.x} ${p3.y}" fill="none" stroke="${tint ?? color(command.color, "#000")}" stroke-width="${width}" stroke-linecap="round"/>`;
    }
    case "DrawGlyph": {
      const p = point(command.position);
      const transform = `translate(${p.x} ${p.y}) scale(${command.scaleX ?? 1} ${command.scaleY ?? 1})`;
      return `<text transform="${transform}" font-family="${escapeXml(options.musicFontFamily ?? "Bravura")}" font-size="${value(command.fontSize)}" fill="${tint ?? color(command.color, "#000")}">${escapeXml(command.glyph?.codepoint ?? "")}</text>`;
    }
    case "DrawText": {
      const p = point(command.position);
      const anchor = { LEFT: "start", CENTER: "middle", RIGHT: "end" }[command.alignment] ?? "start";
      const runs = command.richText?.runs?.length
        ? command.richText.runs.map((run) => {
          const baseline = run.style?.baseline;
          const shift = baseline === "SUPERSCRIPT" ? "super" : baseline === "SUBSCRIPT" ? "sub" : "baseline";
          return `<tspan font-size="${(run.style?.sizeScale ?? 1) * 100}%" font-weight="${run.style?.bold ? "bold" : "inherit"}" font-style="${run.style?.italic ? "italic" : "inherit"}" baseline-shift="${shift}" fill="${tint ?? color(run.style?.color ?? command.color, "#000")}">${escapeXml(run.text)}</tspan>`;
        }).join("")
        : escapeXml(command.text ?? "");
      return `<text x="${p.x}" y="${p.y}" font-family="${escapeXml(command.fontFamily ?? "serif")}" font-size="${value(command.fontSize)}" font-weight="${String(command.fontWeight ?? "").toLowerCase()}" font-style="${String(command.fontStyle ?? "").toLowerCase()}" text-anchor="${anchor}" fill="${tint ?? color(command.color, "#000")}">${runs}</text>`;
    }
    case "RenderGroup": {
      const children = (command.commands ?? []).map((child) => svgCommand(child, options, tint)).join("");
      if (command.clipRect) {
        const box = rect(command.clipRect);
        return `<svg x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}" viewBox="${box.x} ${box.y} ${box.width} ${box.height}" overflow="hidden" opacity="${command.opacity ?? 1}">${children}</svg>`;
      }
      return `<g opacity="${command.opacity ?? 1}">${children}</g>`;
    }
    default:
      options.onUnknownCommand?.(command);
      return "";
  }
}

function svgPaint(command, tint = null) {
  return `fill="${tint ?? color(command.fillColor, "#000")}" stroke="${tint ?? color(command.strokeColor)}" stroke-width="${value(command.strokeThickness)}"`;
}

function svgPath(segments) {
  return segments.map((segment) => {
    switch (segmentKind(segment)) {
      case "MoveTo": { const p = point(segment.point); return `M${p.x} ${p.y}`; }
      case "LineTo": { const p = point(segment.point); return `L${p.x} ${p.y}`; }
      case "QuadTo": { const c = point(segment.control), e = point(segment.end); return `Q${c.x} ${c.y} ${e.x} ${e.y}`; }
      case "CubicTo": { const a = point(segment.control1), b = point(segment.control2), e = point(segment.end); return `C${a.x} ${a.y} ${b.x} ${b.y} ${e.x} ${e.y}`; }
      case "Close": return "Z";
      default: return "";
    }
  }).join(" ");
}

function svgSelection(element, options) {
  const box = rect(element.hitBox);
  return `<rect x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}" fill="none" stroke="${escapeXml(options.selectionColor ?? "#2878ff")}" stroke-width="${options.selectionWidth ?? 1.5}" stroke-dasharray="${(options.selectionDash ?? [4, 3]).join(" ")}" pointer-events="none"/>`;
}
