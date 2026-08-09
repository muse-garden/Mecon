export const SCORE_EDITOR_CONTROL_IDS = Object.freeze([
  "history.undo", "history.redo",
  "selection.selectAll", "selection.delete", "selection.copy", "selection.cut",
  "selection.paste", "selection.transposeDown", "selection.transposeUp",
  "selection.moveVoice", "selection.duration", "selection.accidental",
  "selection.tie", "selection.beam", "selection.articulation", "selection.arpeggio",
  "input.position", "input.duration", "input.rest", "input.chord",
  "input.step", "input.midi", "input.grace", "input.tuplet",
  "score.layout", "score.expression", "score.structure", "score.repeat", "score.slur",
  "document.export", "host.status",
  "tool.select", "tool.marquee", "tool.palette-toggle",
  "voice.1", "voice.2", "voice.3", "voice.4",
  "duration.whole", "duration.half", "duration.quarter", "duration.eighth",
  "duration.16th", "duration.32nd", "duration.rest", "duration.dot.1",
  "duration.dot.2", "duration.uncommon-toggle", "duration.breve", "duration.64th",
  "duration.longa", "duration.maxima", "duration.128th",
  "accidental.sharp", "accidental.flat", "accidental.natural",
  "accidental.double-sharp", "accidental.double-flat", "curve.tie", "curve.slur",
  "grace.appoggiatura", "grace.acciaccatura", "grace.small-note",
  "tuplet.suggested.2", "tuplet.suggested.3", "tuplet.suggested.4",
  "tuplet.suggested.5", "tuplet.suggested.6", "tuplet.suggested.7",
  "tuplet.suggested.8", "tuplet.suggested.9", "tuplet.custom", "tuplet.confirm",
  "tuplet.clear", "beam.independent", "beam.both", "beam.right", "beam.left",
  "beam.group", "articulation.toggle", "articulation.staccato",
  "articulation.spiccato", "articulation.staccatissimo", "articulation.tenuto",
  "articulation.accent", "articulation.marcato", "articulation.fermata",
]);

const KNOWN_CONTROLS = new Set(SCORE_EDITOR_CONTROL_IDS);

const group = (id, items) => Object.freeze({ type: "group", id, items: Object.freeze(items) });
const separator = Object.freeze({ type: "separator" });

function profile(layout, overflow = "wrap") {
  return Object.freeze({ layout: Object.freeze(layout), hidden: Object.freeze([]), overflow });
}

export const FULL_SCORE_EDITOR_TOOLBAR = profile([
  { type: "slot", id: "file" },
  group("history", ["history.undo", "history.redo"]),
  separator,
  group("selection", [
    "selection.selectAll", "selection.delete", "selection.copy", "selection.cut",
    "selection.paste", "selection.transposeDown", "selection.transposeUp",
  ]),
  group("voice", ["selection.moveVoice"]),
  group("duration", ["selection.duration"]),
  group("accidental", ["selection.accidental"]),
  group("tie-beam", ["selection.tie", "selection.beam"]),
  group("performance", ["selection.articulation", "selection.arpeggio"]),
  separator,
  group("input", [
    "input.position", "input.duration", "input.rest", "input.chord",
    "input.step", "input.midi", "input.grace", "input.tuplet",
  ]),
  group("score-elements", [
    "score.layout", "score.expression", "score.structure", "score.repeat", "score.slur",
  ]),
  separator,
  group("document", ["document.export"]),
  { type: "slot", id: "status" },
]);

export const FREE_PRACTICE_SCORE_TOOLBAR = profile([
  group("tool", ["tool.select", "tool.marquee", "tool.palette-toggle"]),
  group("voice", ["voice.1", "voice.2", "voice.3", "voice.4"]),
  group("duration", [
    "duration.whole", "duration.half", "duration.quarter", "duration.eighth",
    "duration.16th", "duration.32nd", "duration.rest", "duration.dot.1",
    "duration.dot.2", "duration.uncommon-toggle",
    "duration.breve", "duration.64th", "duration.longa", "duration.maxima", "duration.128th",
  ]),
  group("accidental", [
    "accidental.sharp", "accidental.flat", "accidental.natural",
    "accidental.double-sharp", "accidental.double-flat",
  ]),
  group("curve", ["curve.tie", "curve.slur"]),
  group("grace", ["grace.appoggiatura", "grace.acciaccatura", "grace.small-note"]),
  group("tuplet", [
    "tuplet.suggested.2", "tuplet.suggested.3", "tuplet.suggested.4",
    "tuplet.suggested.5", "tuplet.suggested.6", "tuplet.suggested.7",
    "tuplet.suggested.8", "tuplet.suggested.9", "tuplet.custom", "tuplet.confirm",
  ]),
  group("beam", ["beam.independent", "beam.both", "beam.right", "beam.left", "beam.group"]),
  group("articulation", [
    "articulation.toggle", "articulation.staccato", "articulation.tenuto",
    "articulation.accent", "articulation.marcato", "articulation.staccatissimo",
  ]),
]);

/** Converts a commonMain toolbar layer DTO to the renderer's layout contract. */
export function toolbarProfileFromDescriptor(layer, overflow = "wrap") {
  if (!layer?.groups) return FREE_PRACTICE_SCORE_TOOLBAR;
  return profile(layer.groups.flatMap((item, index) => [
    ...(index ? [separator] : []),
    group(item.id, item.controls),
  ]), overflow);
}

export function resolveToolbarLayout(config = FULL_SCORE_EDITOR_TOOLBAR) {
  if (!config || !Array.isArray(config.layout)) {
    throw new TypeError("ScoreEditor toolbar config must contain a layout array");
  }
  const overflow = config.overflow ?? "wrap";
  if (!["wrap", "scroll", "menu"].includes(overflow)) {
    throw new TypeError(`Unknown ScoreEditor toolbar overflow: ${overflow}`);
  }
  const hidden = new Set(config.hidden ?? []);
  for (const id of hidden) assertKnownControl(id);

  const seen = new Set();
  const resolved = [];
  for (const item of config.layout) {
    if (item?.type === "group") {
      if (!item.id || !Array.isArray(item.items)) {
        throw new TypeError("ScoreEditor toolbar groups require an id and items array");
      }
      const items = item.items.filter((id) => {
        assertKnownControl(id);
        if (seen.has(id)) throw new TypeError(`Duplicate ScoreEditor toolbar control: ${id}`);
        seen.add(id);
        return !hidden.has(id);
      });
      if (items.length) resolved.push(group(item.id, items));
    } else if (item?.type === "separator" || item?.type === "break") {
      resolved.push(Object.freeze({ type: item.type }));
    } else if (item?.type === "slot" && item.id) {
      resolved.push(Object.freeze({ type: "slot", id: item.id }));
    } else {
      throw new TypeError(`Invalid ScoreEditor toolbar layout item: ${JSON.stringify(item)}`);
    }
  }

  const normalized = resolved.filter((item, index) => {
    if (item.type !== "separator") return true;
    const previous = resolved[index - 1];
    const next = resolved[index + 1];
    return previous && next && previous.type !== "separator" && next.type !== "separator";
  });
  return Object.freeze({
    overflow,
    items: Object.freeze(normalized),
    visibleControlIds: Object.freeze([...seen].filter((id) => !hidden.has(id))),
  });
}

function assertKnownControl(id) {
  if (!KNOWN_CONTROLS.has(id)) throw new TypeError(`Unknown ScoreEditor toolbar control: ${id}`);
}
