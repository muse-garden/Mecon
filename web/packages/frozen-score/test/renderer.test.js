import test from "node:test";
import assert from "node:assert/strict";
import {
  hitTest,
  loadMecon,
  loadMeconArchive,
  loadMeconDocument,
  parseFrozenScore,
  renderSvg,
  writeMeconArchive,
  writeMeconDocument,
} from "../index.js";

const px = (value) => value;
const point = (x, y) => ({ x: px(x), y: px(y) });
const box = (x, y, width, height) => ({ origin: point(x, y), width: px(width), height: px(height) });
const black = { alpha: 255, red: 0, green: 0, blue: 0 };

const bundle = {
  schemaVersion: 1,
  bounds: box(0, 0, 200, 100),
  surfaces: [{
    index: 0,
    width: 200,
    height: 100,
    elements: [
      {
        id: "elem_1",
        type: "STAFF_LINE",
        hitBox: box(5, 8, 100, 4),
        commands: [{
          type: "com.mecon.renderer.render.DrawLine",
          start: point(5, 10),
          end: point(105, 10),
          thickness: 1,
          color: black,
          cap: "BUTT",
          bounds: box(5, 9, 100, 2),
        }],
      },
      {
        id: "elem_2",
        type: "NOTEHEAD",
        eventId: "note-1",
        hitBox: box(45, 5, 12, 12),
        commands: [{
          type: "com.mecon.renderer.render.DrawGlyph",
          position: point(48, 15),
          glyph: { name: "noteheadBlack", codepoint: "\uE0A4" },
          fontSize: 16,
          color: black,
          bounds: box(45, 5, 12, 12),
        }],
      },
      {
        id: "elem_3",
        type: "FUTURE_ELEMENT",
        eventId: "future-1",
        hitBox: box(45, 5, 12, 12),
        commands: [{ type: "future.DrawSparkle", bounds: box(45, 5, 12, 12) }],
      },
    ],
  }],
};

test("parses a forward-compatible frozen bundle", () => {
  const parsed = parseFrozenScore(JSON.stringify({ ...bundle, schemaVersion: 2, futureField: true }));
  assert.equal(parsed.schemaVersion, 2);
});

test("SVG replays known commands and reports unknown commands", () => {
  const unknown = [];
  const svg = renderSvg(bundle, {
    selectedIds: ["elem_2"],
    onUnknownCommand: (command) => unknown.push(command.type),
  });
  assert.match(svg, /<line /);
  assert.match(svg, /\uE0A4/);
  assert.match(svg, /stroke-dasharray="4 3"/);
  assert.deepEqual(unknown, ["future.DrawSparkle"]);
});

test("hit testing returns the topmost selectable element", () => {
  assert.equal(hitTest(bundle, 0, 50, 10)?.id, "elem_3");
  assert.equal(hitTest(bundle, 0, 50, 10, { types: ["NOTEHEAD"] })?.id, "elem_2");
  assert.equal(hitTest(bundle, 0, 150, 50), null);
});

test("loads geometry through manifest paths in a .mecon ZIP", async () => {
  const manifest = {
    activeScoreId: "score-a",
    scores: [{
      id: "score-a",
      path: "scores/score-a.json",
      geometryPath: "custom/geometry-a.json",
    }],
  };
  const archive = storedZip(new Map([
    ["manifest.json", JSON.stringify(manifest)],
    ["custom/geometry-a.json", JSON.stringify(bundle)],
  ]));
  const loaded = await loadMecon(archive);
  assert.equal(loaded.scoreRef.id, "score-a");
  assert.equal(loaded.bundle.surfaces[0].elements[1].eventId, "note-1");
});

test("SVG applies per-element preview offsets without moving other elements", () => {
  const svg = renderSvg(bundle, { elementOffsets: { elem_2: { x: 0, y: -5 } } });
  assert.match(svg, /data-mecon-id="elem_2"[^>]*transform="translate\(0 -5\)"/);
  assert.doesNotMatch(svg, /data-mecon-id="elem_1"[^>]*transform=/);
});

test("SVG restores omitted Kotlin command colors and supports per-element tint", () => {
  const omitted = structuredClone(bundle);
  delete omitted.surfaces[0].elements[0].commands[0].color;
  delete omitted.surfaces[0].elements[1].commands[0].color;
  const svg = renderSvg(omitted, { elementTints: { elem_2: "#a6abb3" } });
  assert.match(svg, /data-mecon-id="elem_1".*stroke="#000"/);
  assert.match(svg, /data-mecon-id="elem_2".*fill="#a6abb3"/);
});

test("selection tint outranks semantic tint and center markers remain visible", () => {
  const svg = renderSvg(bundle, {
    selectedIds: ["elem_2"],
    selectionMode: "tint",
    selectionColor: "#2878ff",
    elementTints: { elem_2: "#41aa5f" },
    elementCenterMarkers: { elem_2: { color: "#fff", radius: 1.5 } },
  });
  assert.match(svg, /data-mecon-id="elem_2".*fill="#2878ff"/);
  assert.match(svg, /data-mecon-id="elem_2".*<circle[^>]*r="1.5"[^>]*fill="#fff"/);
  assert.doesNotMatch(svg, /data-mecon-id="elem_2".*fill="#41aa5f"/);
});

test("SVG omits elements listed in hiddenIds", () => {
  const svg = renderSvg(bundle, { hiddenIds: ["elem_2"] });
  assert.match(svg, /data-mecon-id="elem_1"/);
  assert.doesNotMatch(svg, /data-mecon-id="elem_2"/);
});

test("continuous surfaces use the content bounds origin as their viewport", () => {
  const offsetBundle = {
    ...bundle,
    paginated: false,
    bounds: box(100, 50, 200, 100),
    surfaces: [{
      index: 0,
      width: 200,
      height: 100,
      elements: [{
        id: "offset-note",
        type: "NOTEHEAD",
        hitBox: box(105, 55, 10, 10),
        commands: [],
      }],
    }],
  };
  assert.equal(hitTest(offsetBundle, 0, 10, 10)?.id, "offset-note");
  assert.match(renderSvg(offsetBundle), /viewBox="100 50 200 100"/);
});

test("restores empty collections omitted by Kotlin serialization", () => {
  const parsed = parseFrozenScore({
    bounds: box(0, 0, 200, 100),
    surfaces: [{ width: 200, height: 100 }],
  });
  assert.deepEqual(parsed.surfaces[0].elements, []);
  assert.deepEqual(parsed.timePositions, []);
});

test("writes a .mecon ZIP while preserving unknown entries byte-for-byte", async () => {
  const manifest = {
    activeScoreId: "score-a",
    scores: [{
      id: "score-a",
      path: "scores/score-a.json",
      geometryPath: "geometry/score-a.json",
    }],
    modules: [{ id: "future", type: "future.module", path: "modules/future.json" }],
  };
  const unknown = new Uint8Array([0, 255, 7, 99, 13]);
  const initial = writeMeconArchive(new Map([
    ["manifest.json", JSON.stringify(manifest)],
    ["scores/score-a.json", "{\"title\":\"before\"}"],
    ["geometry/score-a.json", JSON.stringify(bundle)],
    ["modules/future.json", unknown],
  ]));
  const opened = await loadMeconArchive(initial);
  const saved = writeMeconArchive(opened, {
    "scores/score-a.json": "{\"title\":\"after\"}",
  });
  const reopened = await loadMeconArchive(saved);

  assert.equal(
    new TextDecoder().decode(reopened.entries.get("scores/score-a.json")),
    "{\"title\":\"after\"}",
  );
  assert.deepEqual(reopened.entries.get("modules/future.json"), unknown);
  assert.deepEqual(reopened.manifest, manifest);
  assert.equal((await loadMecon(saved)).scoreRef.id, "score-a");
});

test("opens a document with an opaque future module", async () => {
  const opaque = new Uint8Array([0, 255, 7, 99]);
  const archive = writeMeconArchive(new Map([
    ["manifest.json", JSON.stringify({
      activeScoreId: "score-a",
      scores: [{ id: "score-a", path: "scores/score-a.json" }],
      modules: [{ id: "future", type: "future.module", path: "modules/future.bin" }],
    })],
    ["scores/score-a.json", JSON.stringify({ id: "score-a" })],
    ["modules/future.bin", opaque],
  ]));

  const document = await loadMeconDocument(archive);
  assert.equal(document.modules.has("future"), false);
  assert.deepEqual(document.opaqueModules.get("future"), opaque);
});

test("updates manifest-addressed score and geometry without rewriting modules", async () => {
  const manifest = {
    activeScoreId: "score-a",
    scores: [{
      id: "score-a",
      path: "custom/score.json",
      geometryPath: "custom/geometry.json",
    }],
    modules: [{ id: "future", type: "future.module", path: "custom/future.json" }],
  };
  const moduleBytes = new TextEncoder().encode("{ \"futureFormatting\" : true }");
  const initial = writeMeconArchive(new Map([
    ["manifest.json", JSON.stringify(manifest)],
    ["custom/score.json", "{\"title\":\"before\"}"],
    ["custom/geometry.json", JSON.stringify(bundle)],
    ["custom/future.json", moduleBytes],
  ]));
  const document = await loadMeconDocument(initial);
  assert.equal(document.scores.get("score-a").title, "before");

  const saved = writeMeconDocument(document, {
    scores: new Map([["score-a", { title: "after" }]]),
    geometries: new Map([["score-a", { ...bundle, engineVersion: "edited" }]]),
  });
  const reopened = await loadMeconDocument(saved);
  assert.equal(reopened.scores.get("score-a").title, "after");
  assert.equal((await loadMecon(saved)).bundle.engineVersion, "edited");
  assert.deepEqual(reopened.entries.get("custom/future.json"), moduleBytes);
});

function storedZip(entries) {
  const encoder = new TextEncoder();
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const nameBytes = encoder.encode(name);
    const data = encoder.encode(text);
    const local = new Uint8Array(30 + nameBytes.length + data.length);
    const localView = new DataView(local.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true);
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
    centralView.setUint32(20, data.length, true);
    centralView.setUint32(24, data.length, true);
    centralView.setUint16(28, nameBytes.length, true);
    centralView.setUint32(42, offset, true);
    central.set(nameBytes, 46);
    centrals.push(central);
    offset += local.length;
  }
  const centralSize = centrals.reduce((sum, item) => sum + item.length, 0);
  const end = new Uint8Array(22);
  const endView = new DataView(end.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(8, entries.size, true);
  endView.setUint16(10, entries.size, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, offset, true);
  const output = new Uint8Array(offset + centralSize + end.length);
  let cursor = 0;
  for (const part of [...locals, ...centrals, end]) {
    output.set(part, cursor);
    cursor += part.length;
  }
  return output;
}
