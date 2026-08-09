import test from "node:test";
import assert from "node:assert/strict";
import {
  FREE_PRACTICE_SCORE_TOOLBAR,
  FULL_SCORE_EDITOR_TOOLBAR,
  resolveToolbarLayout,
} from "../editor/index.js";

test("full toolbar exposes every configured score-editor capability once", () => {
  const resolved = resolveToolbarLayout(FULL_SCORE_EDITOR_TOOLBAR);
  assert.equal(new Set(resolved.visibleControlIds).size, resolved.visibleControlIds.length);
  assert.ok(resolved.visibleControlIds.includes("score.structure"));
  assert.ok(resolved.visibleControlIds.includes("score.expression"));
  assert.equal(resolved.overflow, "wrap");
});

test("free-practice toolbar keeps note editing and hides score-element families", () => {
  const resolved = resolveToolbarLayout(FREE_PRACTICE_SCORE_TOOLBAR);
  for (const id of [
    "tool.select", "tool.marquee", "tool.palette-toggle", "voice.1", "voice.4",
    "duration.quarter", "duration.rest", "duration.uncommon-toggle",
    "accidental.sharp", "curve.tie", "curve.slur", "grace.appoggiatura",
    "tuplet.custom", "beam.group", "articulation.toggle",
  ]) assert.ok(resolved.visibleControlIds.includes(id), id);
  assert.ok(!resolved.visibleControlIds.includes("tuplet.clear"));
  assert.ok(!resolved.visibleControlIds.some((id) => id.startsWith("score.")));
  for (const id of [
    "selection.copy", "selection.transposeUp", "selection.arpeggio",
    "input.position", "input.midi", "input.chord",
  ]) assert.ok(!resolved.visibleControlIds.includes(id), id);
});

test("custom layouts support ordering, hidden controls, breaks and host slots", () => {
  const resolved = resolveToolbarLayout({
    overflow: "scroll",
    hidden: ["selection.copy"],
    layout: [
      { type: "group", id: "edit", items: ["selection.copy", "selection.delete"] },
      { type: "break" },
      { type: "slot", id: "custom" },
    ],
  });
  assert.deepEqual(resolved.visibleControlIds, ["selection.delete"]);
  assert.deepEqual(resolved.items.map((item) => item.type), ["group", "break", "slot"]);
  assert.equal(resolved.overflow, "scroll");
});

test("toolbar config rejects unknown and duplicate control ids", () => {
  assert.throws(() => resolveToolbarLayout({ layout: [
    { type: "group", id: "bad", items: ["unknown"] },
  ] }), /Unknown ScoreEditor toolbar control/);
  assert.throws(() => resolveToolbarLayout({ layout: [
    { type: "group", id: "one", items: ["selection.copy"] },
    { type: "group", id: "two", items: ["selection.copy"] },
  ] }), /Duplicate ScoreEditor toolbar control/);
});
