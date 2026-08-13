import test from "node:test";
import assert from "node:assert/strict";
import {
  createControlledScrollSync,
  createScoreEditorCommandController,
  createScoreEditorDragController,
} from "../editor/index.js";

test("controlled scroll sync does not pull an active native scrollbar back to a stale prop", () => {
  const sync = createControlledScrollSync();
  const element = { scrollLeft: 0 };

  element.scrollLeft = 80;
  assert.equal(sync.observe(80), true);
  sync.apply(element, 0);
  assert.equal(element.scrollLeft, 80);

  sync.apply(element, 80);
  sync.apply(element, 24);
  assert.equal(element.scrollLeft, 24);
  assert.equal(sync.observe(24), false);
});

test("a no-op boundary scroll cannot block later sibling scroll updates", () => {
  const sync = createControlledScrollSync();
  const element = { scrollLeft: 80 };

  assert.equal(sync.observe(80, 80), false);
  sync.apply(element, 36);
  assert.equal(element.scrollLeft, 36);
  assert.equal(sync.observe(36, 36), false);
});

const input = {
  insertMeasure: "3",
  insertBeat: "1.5",
  insertPitch: "2",
  insertChordPitches: "0, 2, 4, 2",
  insertDuration: "EIGHTH",
  insertDots: "1",
  tupletCount: "3",
  graceMode: true,
  graceDuration: "SIXTEENTH",
  graceTimeSource: "PRINCIPAL",
  graceNoteType: "APPOGGIATURA",
  accidental: "SHARP",
  tieMode: true,
  articulations: ["STACCATO"],
};

function setup(overrides = {}) {
  const intents = [];
  const update = {
    score: { voiceTracks: { voice: { events: [{ id: "event-1" }] } } },
    selection: [{ type: "event", voiceTrackId: "voice", eventId: "event-1" }],
    canPaste: true,
    ...overrides,
  };
  return {
    intents,
    controller: createScoreEditorCommandController({
      update,
      input,
      structure: {
        clefValue: "BASS", keyValue: "7|MAJOR", meterNumerator: "3", meterDenominator: "8",
        boundaryMeasure: "2", barlineValue: "REPEAT_RIGHT", repeatCount: "3",
        targetBoundaryMeasure: "4",
      },
      expression: { expressionEndMeasure: "5", ornamentKind: "TRILL" },
      destinations: [{ value: "staff|1", staffId: "staff", voiceNumber: 1 }],
      dispatch: (intent) => intents.push(intent),
    }),
  };
}

test("editor controller builds note and chord intents for the shared session", () => {
  const { controller, intents } = setup();
  assert.equal(controller.insertEvent(false), true);
  assert.equal(controller.insertChord(), true);
  assert.deepEqual(intents[0], {
    type: "insertNote",
    voiceTrackId: "voice",
    start: { measure: 3, beat: { numerator: 3, denominator: 8 } },
    duration: { base: "EIGHTH", dots: 1 },
    pitch: { diatonicSteps: 2 },
    inputAccidental: "SHARP",
    isRest: false,
    tupletCount: 3,
    grace: {
      totalDuration: { base: "SIXTEENTH" },
      stealFrom: "PRINCIPAL",
      noteType: "APPOGGIATURA",
    },
    trailingTie: true,
    articulations: ["STACCATO"],
  });
  assert.deepEqual(intents[1].pitches, [0, 2, 4].map((diatonicSteps) => ({ diatonicSteps })));
});

test("editor controller commits a renderer-resolved pointer target as an insert intent", () => {
  const { controller, intents } = setup();
  assert.equal(controller.insertEventAtPointer({
    voiceTrackId: "voice",
    staffTrackId: "staff",
    voiceNumber: 1,
    start: { measure: 2, beat: { numerator: 1, denominator: 8 } },
    pitch: { diatonicSteps: 3, chromaticOffset: 0 },
  }, {
    duration: { base: "HALF", dots: 0 },
    isRest: false,
    decoration: { tupletCount: 3 },
  }), true);
  assert.deepEqual(intents[0], {
    type: "insertNote",
    voiceTrackId: "voice",
    staffTrackId: "staff",
    voiceNumber: 1,
    start: { measure: 2, beat: { numerator: 1, denominator: 8 } },
    duration: { base: "HALF", dots: 0 },
    pitch: { diatonicSteps: 3, chromaticOffset: 0 },
    isRest: false,
    tupletCount: 3,
  });
});

test("editor controller builds structural and expression intents", () => {
  const { controller, intents } = setup({
    score: {
      voiceTracks: { voice: { events: [{ id: "event-1" }] } },
      staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
    },
  });
  assert.equal(controller.applyClef(), true);
  assert.equal(controller.applyKeySignature(), true);
  assert.equal(controller.applyTimeSignature(), true);
  assert.equal(controller.setBarline(), true);
  assert.equal(controller.addPointExpression("addDynamic", { level: "MF" }), true);
  assert.equal(controller.addSpanExpression("addHairpin", { hairpinType: "CRESCENDO" }), true);
  assert.equal(controller.addOrnamentToSelection(), true);
  assert.deepEqual(intents.map((intent) => intent.type), [
    "setClef", "setKeySignature", "setTimeSignature", "setBarline",
    "addDynamic", "addHairpin", "addOrnament",
  ]);
  assert.deepEqual(intents[0].onset, { measure: 3, beat: { numerator: 0, denominator: 1 } });
  assert.deepEqual(intents[5].end, { measure: 5, beat: { numerator: 3, denominator: 8 } });
});

test("editor controller centralizes selection and paste target construction", () => {
  const { controller, intents } = setup();
  assert.equal(controller.editSelection("deleteNotes"), true);
  assert.equal(controller.pasteAtInputPosition(), true);
  assert.equal(controller.selectAllEvents(), true);
  assert.deepEqual(intents.map((intent) => intent.type), ["deleteNotes", "pasteNotes", "setSelection"]);
  assert.deepEqual(controller.groupedEventTargets(3), [{
    voiceTrackId: "voice", eventIds: ["event-1"], count: 3,
  }]);
});

test("editor controller reports no-op when a command has no stable target", () => {
  const { controller, intents } = setup({
    score: { voiceTracks: {} },
    selection: [],
    canPaste: false,
  });
  assert.equal(controller.editSelection("deleteNotes"), false);
  assert.equal(controller.insertEvent(false), false);
  assert.equal(controller.insertChord(), false);
  assert.equal(controller.pasteAtInputPosition(), false);
  assert.deepEqual(intents, []);
});

test("drag controller commits note and navigation drags as shared intents", () => {
  const intents = [];
  const dragRef = { current: {
    mode: "NOTE", pointerId: 7, stepDelta: 2, element: { type: "NOTEHEAD" },
    targets: [{ voiceTrackId: "voice", eventId: "note" }],
  } };
  const common = {
    frame: {}, surfaceIndex: 0, canvasPoint: () => null, dragRef,
    suppressClickRef: { current: false }, setDragPreview: () => {},
    dispatch: (intent) => intents.push(intent),
  };
  const controller = createScoreEditorDragController(common);
  const target = {
    hasPointerCapture: () => false,
    releasePointerCapture: () => {},
  };
  controller.finish({ pointerId: 7, currentTarget: target });
  assert.deepEqual(intents[0], {
    type: "transposeNotes", targets: [{ voiceTrackId: "voice", eventId: "note" }], stepDelta: 2,
  });

  dragRef.current = {
    mode: "NAVIGATION", pointerId: 8, changed: true, deltaX: 20, deltaY: 40,
    existingOffset: { dx: 1, dy: 2 }, sourceAnchor: { x: 0, y: 100 },
    targetAnchor: { x: 20, y: 140 }, metrics: { halfSpace: 5 },
    target: { boundaryMeasure: 1, mark: "SEGNO" }, targetBoundary: 4,
  };
  controller.finish({ pointerId: 8, currentTarget: target });
  assert.equal(intents[1].type, "moveNavigationMark");
  assert.equal(intents[1].targetBoundaryMeasure, 4);
  // Cross-system anchor travel is removed; only offset relative to the new anchor persists.
  assert.deepEqual(intents[1].offset, { dx: 1, dy: 2 });
});
