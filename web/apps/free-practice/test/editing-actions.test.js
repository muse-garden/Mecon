import test from "node:test";
import assert from "node:assert/strict";
import { createScoreEditorDragController } from "../../../packages/web-renderer/editor/drag-controller.js";
import {
  allEventSelectionTargets,
  articulationDragGeometry,
  attachmentDragGeometry,
  attachmentDragSource,
  beamDragGeometry,
  compareTimeCodes,
  curveDragGeometry,
  dragEndpoint,
  dragStepDelta,
  isRestTarget,
  marqueeSelection,
  formatQuarterBeat,
  navigationDragOffset,
  nearestBoundary,
  nearestTimePosition,
  quarterBeatFraction,
  restPositionForElement,
  resolveEventTargets,
  selectedElementIds,
  selectionTargetForElement,
  staffAnchor,
  staffCoreAtPointer,
  staffDragMetrics,
} from "../../../packages/web-renderer/editor/interaction.js";

const update = {
  score: {
    voiceTracks: {
      soprano: { events: [{ id: "note-1" }] },
      alto: { events: [{ id: "note-2" }] },
    },
  },
  selection: [
    { eventId: "note-1" },
    { eventId: "note-1" },
    { staffTrackId: "staff", type: "clef" },
    { eventId: "missing" },
  ],
};

test("resolves renderer event ids to stable voice targets", () => {
  assert.deepEqual(resolveEventTargets(update), [{ voiceTrackId: "soprano", eventId: "note-1" }]);
  assert.deepEqual(selectionTargetForElement(update, { eventId: "note-2" }), {
    type: "event",
    voiceTrackId: "alto",
    eventId: "note-2",
  });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "NOTEHEAD", eventId: "note-2", metadata: { pitchIndex: "1" },
  }), {
    type: "event", voiceTrackId: "alto", eventId: "note-2", pitchIndices: [1],
  });
});

test("recognizes rests through the serialized pitch-track relation", () => {
  const restUpdate = { score: {
    voiceTracks: { voice: { pitchTrackId: "pitch", events: [
      { id: "rest", pitchEventId: "rest-pitch" }, { id: "note", pitchEventId: "note-pitch" },
    ] } },
    pitchTracks: { pitch: { events: [
      { id: "rest-pitch", pitches: [] }, { id: "note-pitch", pitches: [{ diatonicSteps: 0 }] },
    ] } },
  } };
  assert.equal(isRestTarget(restUpdate, { voiceTrackId: "voice", eventId: "rest" }), true);
  assert.equal(isRestTarget(restUpdate, { voiceTrackId: "voice", eventId: "note" }), false);
});

test("projects attachment body-drag inputs from score anchors and captured geometry", () => {
  const updateWithAttachment = { score: { staffTracks: { staff: { attachments: [
    { id: "hairpin", onset: { measure: 1 }, endOnset: { measure: 2 } },
  ] } } } };
  const geometry = { attachments: { hairpin: { startDx: 1, startDy: 2, endDx: 3, endDy: 2 } } };
  assert.deepEqual(attachmentDragSource(updateWithAttachment, geometry, {
    type: "HAIRPIN", eventId: "hairpin",
  }), {
    attachmentId: "hairpin",
    start: { measure: 1 },
    end: { measure: 2 },
    geometry: { startDx: 1, startDy: 2, endDx: 3, endDy: 2 },
  });
});

test("distinguishes curve endpoints from apex dragging and moves only the selected endpoint", () => {
  const element = { hitBox: { origin: { x: 100 }, width: 80 } };
  assert.equal(dragEndpoint(element, 110), "start");
  assert.equal(dragEndpoint(element, 140), null);
  assert.equal(dragEndpoint(element, 175), "end");
  const geometry = {
    startDx: 1, startDy: 2, endDx: 3, endDy: 4,
    directionOnly: true, autoEndpoints: true,
  };
  assert.deepEqual(curveDragGeometry(geometry, "start", 10, -20, 10), {
    startDx: 2, startDy: 0, endDx: 3, endDy: 4,
    directionOnly: false, autoEndpoints: false, manuallyAdjusted: true,
  });
  assert.deepEqual(curveDragGeometry(geometry, "end", -10, 10, 10), {
    startDx: 1, startDy: 2, endDx: 2, endDy: 5,
    directionOnly: false, autoEndpoints: false, manuallyAdjusted: true,
  });
});

test("snaps interval attachment endpoints on the target staff row and preserves the other endpoint", () => {
  const bundle = { timePositions: [
    { timeCode: { measure: 1 }, x: 50, topY: 100, bottomY: 180 },
    { timeCode: { measure: 2 }, x: 150, topY: 100, bottomY: 180 },
    { timeCode: { measure: 3 }, x: 70, topY: 300, bottomY: 380 },
  ] };
  const surface = { contentOffsetY: 0 };
  assert.deepEqual(nearestTimePosition(bundle, surface, 140, 140), {
    timeCode: { measure: 2 }, x: 150,
  });
  assert.deepEqual(nearestTimePosition(bundle, surface, 75, 340), {
    timeCode: { measure: 3 }, x: 70,
  });
  assert.equal(compareTimeCodes({ measure: 1, beat: { numerator: 1, denominator: 2 } }, { measure: 2 }), -1);
  assert.equal(compareTimeCodes({ measure: 2 }, { measure: 2, beat: { numerator: 0, denominator: 1 } }), 0);
  assert.deepEqual(attachmentDragGeometry(
    { startDx: 0, startDy: -3, endDx: 0, endDy: -3 }, "end", 5, 10, 10,
  ), {
    startDx: 0, startDy: -3, endDx: 0.5, endDy: -2, manuallyAdjustedY: true,
  });
});

test("converts the manual beat field to and from exact wire fractions", () => {
  assert.deepEqual(quarterBeatFraction(0.5), { numerator: 1, denominator: 8 });
  assert.deepEqual(quarterBeatFraction(1.5), { numerator: 3, denominator: 8 });
  assert.deepEqual(quarterBeatFraction("3"), { numerator: 3, denominator: 4 });
  // Tuplet onsets have no finite decimal form, so the field accepts an exact ratio.
  assert.deepEqual(quarterBeatFraction("1/3"), { numerator: 1, denominator: 12 });
  assert.deepEqual(quarterBeatFraction("7/3"), { numerator: 7, denominator: 12 });
  assert.deepEqual(quarterBeatFraction(""), { numerator: 0, denominator: 1 });
});

test("formats a session input position back into the beat field without losing precision", () => {
  assert.equal(formatQuarterBeat({ numerator: 1, denominator: 4 }), "1");
  assert.equal(formatQuarterBeat({ numerator: 3, denominator: 8 }), "1.5");
  assert.equal(formatQuarterBeat({ numerator: 0, denominator: 1 }), "0");
  assert.equal(formatQuarterBeat({ numerator: 7, denominator: 12 }), "7/3");
  // Round-tripping the tuplet cursor the session reports must reproduce the same onset.
  assert.deepEqual(
    quarterBeatFraction(formatQuarterBeat({ numerator: 7, denominator: 12 })),
    { numerator: 7, denominator: 12 },
  );
});

test("snaps structural drags to the nearest allowed barline boundary", () => {
  const surface = { elements: [1, 2, 3].map((measureNumber) => ({
    type: "BARLINE", measureNumber,
    hitBox: { origin: { x: measureNumber * 100 - 2 }, width: 4 },
  })) };
  assert.deepEqual(nearestBoundary(surface, 188), { boundaryMeasure: 2, x: 200 });
  assert.deepEqual(nearestBoundary(surface, 188, (measure) => measure >= 3), {
    boundaryMeasure: 3, x: 300,
  });
  surface.elements.push({
    type: "BARLINE", measureNumber: 4,
    hitBox: { origin: { x: 148, y: 200 }, width: 4, height: 40 },
  });
  assert.deepEqual(nearestBoundary(surface, 150, () => true, 220), {
    boundaryMeasure: 4, x: 150,
  });
  assert.deepEqual(nearestBoundary(
    surface, 100, () => true, null, null,
    { paginated: false, bounds: { origin: { x: 100, y: 20 } } },
  ), { boundaryMeasure: 2, x: 100 });
});

test("locks navigation dragging to staff cores and removes cross-system anchor travel", () => {
  const staff = (systemIndex, centerY) => ({
    type: "STAFF", systemIndex, staffIndex: 0,
    hitBox: { origin: { y: centerY - 16 }, height: 32 },
    commands: [-16, -8, 0, 8, 16].map((offset) => ({ type: "DrawLine", start: { y: centerY + offset } })),
  });
  const surface = { elements: [staff(0, 50), staff(1, 150),
    { type: "BARLINE", systemIndex: 0, measureNumber: 1, hitBox: { origin: { x: 98, y: 34 }, width: 4, height: 32 } },
    { type: "BARLINE", systemIndex: 1, measureNumber: 5, hitBox: { origin: { x: 198, y: 134 }, width: 4, height: 32 } },
  ] };
  assert.deepEqual(staffCoreAtPointer(surface, 145, 0), {
    systemIndex: 1, staffIndex: 0, anchorY: 150, top: 134, bottom: 166,
  });
  assert.equal(staffCoreAtPointer(surface, 100, 0), null);
  assert.deepEqual(staffAnchor(surface, 0, 0), { x: 0, y: 50 });
  assert.deepEqual(nearestBoundary(surface, 190, () => true, null, 1), {
    boundaryMeasure: 5, x: 200,
  });
  assert.deepEqual(navigationDragOffset(
    { dx: 1, dy: -0.5 }, 100, 108, { x: 100, y: 50 }, { x: 200, y: 150 }, 8,
  ), { dx: 1, dy: 0.5 });
});

test("snaps vertical staff dragging and resolves rest positions", () => {
  const staffLines = [20, 30, 40, 50, 60].map((y, index) => ({
    id: `line-${index}`, type: "STAFF_LINE", staffIndex: 0, systemIndex: 1,
    hitBox: { origin: { y: y - 1 }, height: 2 },
  }));
  const rest = {
    id: "rest", type: "REST", staffIndex: 0, systemIndex: 1,
    metadata: { staffPosition: "2" }, hitBox: { origin: { y: 24 }, height: 12 },
  };
  const metrics = staffDragMetrics({ elements: [...staffLines, rest] }, rest);
  assert.deepEqual(metrics, { halfSpace: 5, centerY: 40 });
  assert.equal(dragStepDelta(30, 19, metrics.halfSpace), 2);
  assert.equal(dragStepDelta(30, 41, metrics.halfSpace), -2);
  assert.equal(restPositionForElement(rest, metrics), 2);
});

test("moves a whole beam or one endpoint in staff-space units", () => {
  const geometry = { startDy: 1, endDy: 2, manuallyAdjusted: false };
  assert.deepEqual(beamDragGeometry(geometry, null, 8, 4), {
    startDy: 3, endDy: 4, manuallyAdjusted: true,
  });
  assert.deepEqual(beamDragGeometry(geometry, "start", -4, 4), {
    startDy: 0, endDy: 2, manuallyAdjusted: true,
  });
  assert.deepEqual(beamDragGeometry(geometry, "end", 4, 4), {
    startDy: 1, endDy: 3, manuallyAdjusted: true,
  });
});

test("moves exactly one articulation mark in staff-space units", () => {
  const geometry = { marks: [
    { index: 0, above: true, dx: 1, dy: -2 },
    { index: 1, above: false, dx: 0, dy: 2 },
  ] };
  assert.deepEqual(articulationDragGeometry(geometry, 1, 4, -8, 4), { marks: [
    { index: 0, above: true, dx: 1, dy: -2 },
    { index: 1, above: false, dx: 1, dy: 0 },
  ] });
});

test("marquee selects intersecting noteheads and rests with chord pitch identity", () => {
  const elements = [
    { type: "NOTEHEAD", eventId: "note-1", metadata: { pitchIndex: "2" },
      hitBox: { origin: { x: 10, y: 10 }, width: 8, height: 8 } },
    { type: "REST", eventId: "note-2", hitBox: { origin: { x: 30, y: 10 }, width: 8, height: 8 } },
    { type: "NOTEHEAD", eventId: "note-1", metadata: { pitchIndex: "0" },
      hitBox: { origin: { x: 70, y: 10 }, width: 8, height: 8 } },
  ];
  assert.deepEqual(marqueeSelection(update, elements, 40, 25, 5, 5), [
    { type: "event", voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [2] },
    { type: "event", voiceTrackId: "alto", eventId: "note-2" },
  ]);
});

function dragMarquee(frame, start, end) {
  const dragRef = { current: null };
  const suppressClickRef = { current: false };
  const dispatched = [];
  const pointerTarget = {
    setPointerCapture() {},
    hasPointerCapture() { return true; },
    releasePointerCapture() {},
  };
  const controller = createScoreEditorDragController({
    frame,
    surfaceIndex: 0,
    canvasPoint: (event) => ({
      surface: frame.bundle.surfaces[0],
      x: event.surfaceX,
      y: event.surfaceY,
    }),
    dragRef,
    suppressClickRef,
    setDragPreview() {},
    dispatch: (intent) => dispatched.push(intent),
    tool: "marquee",
  });
  const event = (point) => ({
    button: 0,
    pointerId: 7,
    surfaceX: point.x,
    surfaceY: point.y,
    currentTarget: pointerTarget,
  });
  controller.onPointerDown(event(start));
  controller.onPointerMove(event(end));
  controller.finish(event(end));
  return dispatched;
}

function continuousMarqueeFrame(origin) {
  return {
    update: {
      score: { voiceTracks: { soprano: { events: [{ id: "note-1" }] } } },
      selection: [],
    },
    bundle: {
      paginated: false,
      bounds: { origin, width: 100, height: 100 },
      surfaces: [{
        index: 0,
        width: 100,
        height: 100,
        elements: [{
          id: "head-1",
          type: "NOTEHEAD",
          eventId: "note-1",
          metadata: { pitchIndex: "0" },
          hitBox: { origin: { x: origin.x + 10, y: origin.y + 10 }, width: 8, height: 8 },
        }],
      }],
    },
  };
}

const marqueeSelectionIntent = [{
  type: "setSelection",
  targets: [{ type: "event", voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [0] }],
}];

test("marquee selects from a continuous surface whose origin is zero", () => {
  assert.deepEqual(
    dragMarquee(continuousMarqueeFrame({ x: 0, y: 0 }), { x: 5, y: 5 }, { x: 20, y: 20 }),
    marqueeSelectionIntent,
  );
});

test("marquee maps a continuous surface-local drag to global element hit boxes", () => {
  assert.deepEqual(
    dragMarquee(continuousMarqueeFrame({ x: 100, y: 50 }), { x: 5, y: 5 }, { x: 20, y: 20 }),
    marqueeSelectionIntent,
  );
});

test("marquee keeps paginated surface drags in page-local element coordinates", () => {
  const frame = continuousMarqueeFrame({ x: 0, y: 0 });
  frame.bundle.paginated = true;
  frame.bundle.surfaces[0].contentOffsetY = 500;

  assert.deepEqual(
    dragMarquee(frame, { x: 5, y: 5 }, { x: 20, y: 20 }),
    marqueeSelectionIntent,
  );
});

test("reads staff spacing from the frozen STAFF draw commands", () => {
  const note = { type: "NOTEHEAD", trackId: "staff-a", hitBox: { origin: { y: 55 }, height: 10 } };
  const staff = {
    type: "STAFF", trackId: "staff-a", hitBox: { origin: { y: 40 }, height: 32 },
    commands: [40, 48, 56, 64, 72].map((y) => ({
      type: "com.mecon.renderer.render.DrawLine", start: { x: 10, y }, end: { x: 100, y },
    })),
  };
  assert.deepEqual(staffDragMetrics({ elements: [staff, note] }, note), {
    halfSpace: 4,
    centerY: 56,
  });
});

test("resolves staff metrics by rendered staff identity when a slur owns a voice track id", () => {
  const surface = { elements: [{
    type: "STAFF", trackId: "staff", staffIndex: 0, systemIndex: 2,
    hitBox: { origin: { y: 100 }, height: 40 },
    commands: [100, 110, 120, 130, 140].map((y) => ({ type: "DrawLine", start: { y } })),
  }] };
  assert.deepEqual(staffDragMetrics(surface, {
    type: "SLUR", trackId: "voice", staffIndex: 0, systemIndex: 2,
    hitBox: { origin: { y: 80 }, height: 20 },
  }), { halfSpace: 5, centerY: 120 });
});

test("builds stable select-all targets for every voice event", () => {
  assert.deepEqual(allEventSelectionTargets(update), [
    { type: "event", voiceTrackId: "soprano", eventId: "note-1" },
    { type: "event", voiceTrackId: "alto", eventId: "note-2" },
  ]);
});

test("merges multiple selected noteheads without widening them to the whole chord", () => {
  assert.deepEqual(resolveEventTargets(update, [
    { type: "event", voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [2] },
    { type: "event", voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [0] },
  ]), [{ voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [0, 2] }]);
  assert.deepEqual(resolveEventTargets(update, [
    { type: "event", voiceTrackId: "soprano", eventId: "note-1", pitchIndices: [2] },
    { type: "event", voiceTrackId: "soprano", eventId: "note-1" },
  ]), [{ voiceTrackId: "soprano", eventId: "note-1" }]);
});

test("highlights only selected noteheads for a partial chord selection", () => {
  const partial = { ...update, selection: [{
    type: "event", voiceTrackId: "alto", eventId: "note-2", pitchIndices: [1],
  }] };
  assert.deepEqual(selectedElementIds(partial, [
    { id: "head-0", type: "NOTEHEAD", eventId: "note-2", metadata: { pitchIndex: "0" } },
    { id: "head-1", type: "NOTEHEAD", eventId: "note-2", metadata: { pitchIndex: "1" } },
    { id: "stem", type: "STEM", eventId: "note-2" },
  ]), ["head-1"]);
});

test("maps frozen structural metadata to shared selection identities", () => {
  assert.deepEqual(selectionTargetForElement(update, {
    type: "SLUR",
    trackId: "soprano",
    metadata: { slurId: "slur-1", startEventId: "note-1", endEventId: "note-2" },
  }), {
    type: "slur",
    slurId: "slur-1",
    voiceTrackId: "soprano",
    startEventId: "note-1",
    endEventId: "note-2",
  });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "BEAM", metadata: { groupId: "beam-1" },
  }), { type: "beam", groupId: "beam-1" });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "ARTICULATION", eventId: "note-1", metadata: { articulationIndex: "2" },
  }), {
    type: "articulation", eventId: "note-1", voiceTrackId: "soprano", articulationIndex: 2,
  });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "ARTICULATION", eventId: "breath-1", trackId: "staff",
  }), { type: "attachment", attachmentId: "breath-1", staffTrackId: "staff" });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "TIE", eventId: "note-1",
    metadata: { sourcePitchIndex: "0", targetEventId: "note-2" },
  }), {
    type: "tie", sourceEventId: "note-1", voiceTrackId: "soprano",
    sourcePitchIndex: 0, targetEventId: "note-2",
  });
  assert.deepEqual(selectionTargetForElement(update, { type: "BARLINE", measureNumber: 2 }), {
    type: "barline",
    boundaryMeasure: 2,
  });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "VOLTA_ENDING",
    eventId: "volta-attachment-1",
    metadata: { voltaStartMeasure: "1", voltaEndMeasure: "3", voltaNumbers: "1,2" },
  }), {
    type: "voltaEnding",
    startMeasure: 1,
    endMeasure: 3,
    numbers: [1, 2],
  });
  assert.deepEqual(selectionTargetForElement(update, {
    type: "NAVIGATION_MARK",
    measureNumber: 4,
    metadata: { navigationMark: "CODA" },
  }), {
    type: "navigationMark",
    boundaryMeasure: 4,
    mark: "CODA",
  });
});
