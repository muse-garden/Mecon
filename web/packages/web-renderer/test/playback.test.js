import test from "node:test";
import assert from "node:assert/strict";
import {
  completeNoteResumeOffset,
  createPlaybackCursorStore,
  interpolateScoreTime,
  playbackTickAtOffset,
  playbackRangeForSelection,
  scorePlayheadGeometry,
} from "../editor/index.js";

const fraction = (numerator, denominator = 1) => ({ numerator, denominator });

test("playback cursor store publishes lightweight transport frames", () => {
  const store = createPlaybackCursorStore();
  let notifications = 0;
  const unsubscribe = store.subscribe(() => notifications++);
  store.set({ state: "playing", time: fraction(1, 2), tick: 480 });
  assert.deepEqual(store.getSnapshot(), { state: "playing", time: fraction(1, 2), tick: 480 });
  store.set({ state: "paused", time: fraction(3, 4) });
  assert.deepEqual(store.getSnapshot(), { state: "paused", time: fraction(3, 4), tick: null });
  store.set({ state: "idle", time: null });
  assert.deepEqual(store.getSnapshot(), { state: "idle", time: null, tick: null });
  assert.equal(notifications, 3);
  unsubscribe();
});

test("playhead projection steps between notes and retains the engine-selected staff band", () => {
  const frame = {
    timeAxis: { anchors: [
      { time: fraction(0), scoreTime: { measure: 1, beat: fraction(0) }, x: 100 },
      { time: fraction(1), scoreTime: { measure: 2, beat: fraction(0) }, x: 300 },
    ] },
    playbackAnchors: [
      { time: fraction(0), scoreTime: { measure: 1, beat: fraction(0) }, tick: 0 },
      { time: fraction(1, 2), scoreTime: { measure: 1, beat: fraction(1, 2) }, tick: 240 },
      { time: fraction(1), scoreTime: { measure: 2, beat: fraction(0) }, tick: 960 },
    ],
    bundle: {
      paginated: false,
      bounds: { origin: { x: 20, y: 20 }, width: 500, height: 300 },
      surfaces: [{ index: 0, width: 500, height: 300, contentOffsetY: 0 }],
      timePositions: [
        { timeCode: { measure: 1, beat: fraction(0) }, x: 100, topY: 80, bottomY: 160 },
        { timeCode: { measure: 1, beat: fraction(1, 2) }, x: 200, topY: 80, bottomY: 160 },
        { timeCode: { measure: 2, beat: fraction(0) }, x: 300, topY: 180, bottomY: 260 },
      ],
    },
  };
  assert.deepEqual(scorePlayheadGeometry(frame, fraction(1, 2)), { x: 180, top: 60, height: 80 });
  assert.deepEqual(scorePlayheadGeometry(frame, fraction(3, 4), 0, 300), { x: 180, top: 60, height: 80 });
  assert.deepEqual(scorePlayheadGeometry(frame, fraction(1)), { x: 280, top: 160, height: 80 });
});

test("selection playback prefers a selected score event and continues to the score end", () => {
  const slots = [
    { id: "slot-a", onset: fraction(0), duration: fraction(1, 4) },
    { id: "slot-b", onset: fraction(1, 4), duration: fraction(1, 4) },
    { id: "slot-c", onset: fraction(1, 2), duration: fraction(1, 4) },
  ];
  const update = {
    score: { score: { voiceTracks: { voice: { events: [
      { id: "note-b", onset: { measure: 1, beat: fraction(1, 4) } },
    ] } } } },
    timeline: { slots, end: fraction(3, 4) },
    selection: {
      slotId: "slot-a",
      scoreTargets: [{ type: "event", voiceTrackId: "voice", eventId: "note-b" }],
    },
  };
  const frame = { timeAxis: { anchors: [
    { time: fraction(0), scoreTime: { measure: 1, beat: fraction(0) }, x: 10 },
    { time: fraction(1, 4), scoreTime: { measure: 1, beat: fraction(1, 4) }, x: 20 },
  ] } };
  assert.deepEqual(playbackRangeForSelection(frame, update, 96), {
    firstSlotId: "slot-b", lastSlotId: "slot-c", start: fraction(1, 4),
    end: fraction(3, 4), tempoBpm: 96,
  });
});

test("playback time interpolation follows the requested score range", () => {
  assert.deepEqual(interpolateScoreTime(fraction(1, 4), fraction(5, 4), 0.5), fraction(750000, 1000000));
  assert.deepEqual(interpolateScoreTime(fraction(1, 4), fraction(5, 4), 2), fraction(1250000, 1000000));
});

test("playback tick uses the excerpt's audio clock scale instead of score-time interpolation", () => {
  const excerpt = { startTick: 480, endTick: 1440, secondsPerTick: 0.001 };
  assert.equal(playbackTickAtOffset(excerpt, 0.24), 720);
  assert.equal(playbackTickAtOffset(excerpt, 2), 1440);
});

test("pause rewinds to the current note onset so resume schedules a complete note", () => {
  const notes = [
    { startSeconds: 0, durationSeconds: 0.5 },
    { startSeconds: 0.5, durationSeconds: 0.5 },
    { startSeconds: 1, durationSeconds: 0.5 },
  ];
  assert.equal(completeNoteResumeOffset(notes, 0.82), 0.5);
  assert.equal(completeNoteResumeOffset(notes, 1), 1);
});
