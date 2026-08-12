import test from "node:test";
import assert from "node:assert/strict";
import { writingSlotIdsForScoreSelection } from "../src/writing-selection.js";

const fraction = (numerator, denominator = 1) => ({ numerator, denominator });
const time = (measure, beat = 0) => ({ measure, beat: fraction(beat) });

function fixture(targets) {
  return {
    frame: {
      playbackAnchors: [
        { scoreTime: time(1), time: fraction(0) },
        { scoreTime: time(1, 1), time: fraction(1, 4) },
        { scoreTime: time(1, 2), time: fraction(1, 2) },
      ],
    },
    update: {
      selection: { scoreTargets: targets },
      score: { score: { voiceTracks: {
        upper: { events: [
          { id: "first", onset: time(1) },
          { id: "middle", onset: time(1, 1) },
        ] },
        lower: { events: [{ id: "last", onset: time(1, 2) }] },
      } } },
      timeline: { slots: [
        { id: "slot-1", onset: fraction(0), duration: fraction(1, 4) },
        { id: "slot-2", onset: fraction(1, 4), duration: fraction(1, 4) },
        { id: "slot-3", onset: fraction(1, 2), duration: fraction(1, 4) },
      ] },
    },
  };
}

test("maps a marquee note selection to its complete continuous slot range", () => {
  const { frame, update } = fixture([
    { type: "event", eventId: "first", voiceTrackId: "upper" },
    // Exercise the compatibility path where a selection target has no owning voice id.
    { type: "event", eventId: "last" },
  ]);
  assert.deepEqual(writingSlotIdsForScoreSelection(frame, update), ["slot-1", "slot-2", "slot-3"]);
});

test("a single selected note rewrites only the slot containing its onset", () => {
  const { frame, update } = fixture([
    { type: "event", eventId: "middle", voiceTrackId: "upper" },
  ]);
  assert.deepEqual(writingSlotIdsForScoreSelection(frame, update), ["slot-2"]);
});

test("does not fall back to the focused timeline slot without a score-note selection", () => {
  const { frame, update } = fixture([]);
  update.selection.slotId = "slot-2";
  assert.deepEqual(writingSlotIdsForScoreSelection(frame, update), []);
});
