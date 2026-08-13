import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { engineSkip, requireEngineModule } from "./engine-module.js";

test("generated Kotlin/JS engine renders StorageScore JSON", {
  skip: engineSkip,
}, async () => {
  const { MeconWebEngine } = await import(await requireEngineModule());
  const engine = new MeconWebEngine("{}", "{}", "test");
  const bundle = JSON.parse(engine.renderScoreJson(JSON.stringify({ id: "test" })));
  assert.equal(bundle.schemaVersion ?? 1, 1);
  assert.equal(bundle.engineVersion, "test");
  assert.equal(bundle.surfaces.length, 1);
  assert.deepEqual(bundle.surfaces[0].elements ?? [], []);
  const frame = JSON.parse(engine.renderScoreFrameJson(JSON.stringify({ id: "test" })));
  assert.equal(frame.bundle.engineVersion, "test");
  assert.deepEqual(frame.geometry?.slurs ?? {}, {});
});

test("generated Kotlin/JS engine keeps the free-practice time scale independent of viewport width", {
  skip: engineSkip,
}, async () => {
  const { MeconFreePractice, MeconFreePracticePreset, MeconWebEngine } =
    await import(await requireEngineModule());
  const preset = new MeconFreePracticePreset();
  const practice = new MeconFreePractice(preset.documentJson(), preset.scoreJson());
  const update = JSON.parse(practice.initialUpdateJson());
  const engine = new MeconWebEngine("{}", "{}", "width-test");
  const narrow = JSON.parse(engine.renderFreePracticeFrameForWidthJson(
    JSON.stringify(update.score.score),
    JSON.stringify(update.timeline),
    600,
  ));
  const wide = JSON.parse(engine.renderFreePracticeFrameForWidthJson(
    JSON.stringify(update.score.score),
    JSON.stringify(update.timeline),
    1200,
  ));
  assert.equal(narrow.timeAxis.intrinsicContentWidth, narrow.bundle.surfaces[0].width);
  assert.equal(wide.timeAxis.intrinsicContentWidth, narrow.timeAxis.intrinsicContentWidth);
  assert.equal(narrow.timeAxis.surfaceWidth, 600);
  assert.equal(wide.timeAxis.surfaceWidth, 1200);
  assert.equal(narrow.timeAxis.scrollExtent, 0);
  assert.equal(wide.timeAxis.contentEndX, narrow.timeAxis.contentEndX);
  assert.deepEqual(wide.timeAxis.anchors, narrow.timeAxis.anchors);
  assert.ok(narrow.timeAxis.contentEndX > 100);
  assert.ok(narrow.timeAxis.anchors.every((anchor) => anchor.x <= narrow.timeAxis.surfaceWidth));
  assert.ok(narrow.timeAxis.anchors.every((anchor) => Number.isInteger(anchor.scoreTime.measure)));
  assert.ok(narrow.playbackAnchors.length > 0);
  assert.ok(narrow.playbackAnchors.every((anchor) => Number.isFinite(anchor.tick)));
  assert.deepEqual(
    narrow.playbackAnchors.map((anchor) => anchor.tick),
    [...narrow.playbackAnchors.map((anchor) => anchor.tick)].sort((left, right) => left - right),
  );
  practice.close();
});

test("generated Kotlin/JS timeline replays raw pointer input through the common reducer", {
  skip: engineSkip,
}, async () => {
  const { MeconFreePracticeTimeline } = await import(await requireEngineModule());
  const timeline = new MeconFreePracticeTimeline();
  const request = {
    revision: 7,
    axisRevision: 11,
    viewportWidth: 900,
    contentOriginX: 20,
    axisAnchors: [
      { time: { numerator: 0, denominator: 1 }, x: 0 },
      { time: { numerator: 1, denominator: 4 }, x: 144 },
      { time: { numerator: 1, denominator: 2 }, x: 288 },
    ],
    axisContentEndX: 288,
    timeline: {
      end: { numerator: 1, denominator: 2 },
      slots: [
        { id: "slot-a", onset: { numerator: 0, denominator: 1 }, duration: { numerator: 1, denominator: 4 }, symbol: "I" },
        { id: "slot-b", onset: { numerator: 1, denominator: 4 }, duration: { numerator: 1, denominator: 4 }, symbol: "V" },
      ],
      tonalLayouts: [],
      idioms: [],
    },
    selectedSlotId: "slot-a",
  };
  const scene = JSON.parse(timeline.projectJson(JSON.stringify(request)));
  const target = scene.hitObjects.find((item) => item.id === "slot:slot-a");
  const down = JSON.parse(timeline.handleJson(JSON.stringify(scene), JSON.stringify(request), JSON.stringify({
    type: "DOWN", sceneGeneration: scene.generation, pointerId: 3,
    x: target.bounds.x + 30, y: target.bounds.y + 20, ctrl: true,
  })));
  const movedRequest = { ...request, gesture: down.gesture };
  const moved = JSON.parse(timeline.handleJson(JSON.stringify(scene), JSON.stringify(movedRequest), JSON.stringify({
    type: "MOVE", sceneGeneration: scene.generation, pointerId: 3,
    x: down.gesture.startX + 72, y: target.bounds.y + 20,
  })));
  assert.deepEqual(moved.previewEdit.delta, { numerator: 1, denominator: 8 });
  assert.equal(moved.previewEdit.includeFollowing, true);
  const up = JSON.parse(timeline.handleJson(JSON.stringify(scene), JSON.stringify({
    ...request, gesture: moved.gesture,
  }), JSON.stringify({ type: "UP", sceneGeneration: scene.generation, pointerId: 3 })));
  assert.deepEqual(up.commitEdit, moved.previewEdit);
});

test("generated Kotlin/JS engine returns a complete engraved transpose preview", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor, MeconWebEngine } = await import(await requireEngineModule());
  const score = {
    id: "preview-test",
    measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
    instruments: [{ id: "instrument", staffIds: ["staff"] }],
    staffGroups: [{ id: "group", members: [{ type: "staff", staffId: "staff" }] }],
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const inserted = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote",
    expectedRevision: 0,
    voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" },
    pitch: { diatonicSteps: 0, chromaticOffset: 1 },
  })));
  const eventId = inserted.selection[0].eventId;
  const engine = new MeconWebEngine("{}", "{}", "preview-test");
  const renderedFrame = JSON.parse(engine.renderScoreFrameJson(JSON.stringify(inserted.score)));
  assert.deepEqual(
    JSON.parse(engine.applyAccidentalToPitchJson(JSON.stringify({ diatonicSteps: 4 }), "DOUBLE_FLAT")),
    { diatonicSteps: 4, chromaticOffset: -2 },
  );
  const paletteSelection = JSON.parse(engine.paletteSelectionInfoJson(JSON.stringify(inserted.selection)));
  assert.equal(paletteSelection.accidental, "SHARP");
  assert.equal(paletteSelection.durationBase, "QUARTER");
  assert.equal(paletteSelection.voiceNumber, 1);
  const staff = renderedFrame.bundle.surfaces[0].elements.find((element) => element.type === "STAFF");
  const staffLines = staff.commands.filter((command) => command.type.split(".").at(-1) === "DrawLine")
    .map((command) => Number(command.start.y?.value ?? command.start.y));
  const timePosition = renderedFrame.bundle.timePositions.find((position) => position.timeCode?.measure === 1);
  const noteTarget = JSON.parse(engine.noteInputTargetJson(JSON.stringify({
    x: Number(timePosition.x?.value ?? timePosition.x),
    y: staffLines.reduce((sum, y) => sum + y, 0) / staffLines.length,
    duration: { base: "EIGHTH" },
    accidental: "FLAT",
  })));
  assert.equal(noteTarget.voiceTrackId, "voice");
  assert.equal(noteTarget.staffTrackId, "staff");
  assert.equal(noteTarget.start.measure, 1);
  assert.ok(Number.isInteger(noteTarget.pitch.diatonicSteps));
  assert.equal(noteTarget.pitch.chromaticOffset, -1);
  assert.ok(noteTarget.commands.length > 0);
  assert.ok(noteTarget.commands.some((command) => command.type.split(".").at(-1) === "DrawGlyph"));
  assert.ok(noteTarget.commands.some((command) => command.glyph?.codepoint === "\uE260"));
  const preview = JSON.parse(engine.transposePreviewJson(JSON.stringify([{ eventId }]), 2));
  const commandKinds = preview.movedCommands.map((command) => command.type.split(".").at(-1));
  assert.ok(commandKinds.includes("DrawGlyph"));
  assert.ok(commandKinds.includes("DrawLine"), "quarter-note preview should include its stem");
  assert.ok(preview.hiddenElementIds.length >= 2);

  const withRest = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote",
    expectedRevision: 1,
    voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
    duration: { base: "QUARTER" },
    isRest: true,
  })));
  const restId = withRest.selection[0].eventId;
  engine.renderScoreFrameJson(JSON.stringify(withRest.score));
  const restPreview = JSON.parse(engine.restMovePreviewJson(JSON.stringify([
    { eventId: restId, staffPosition: 2 },
  ])));
  assert.ok(restPreview.movedCommands
    .some((command) => command.type.split(".").at(-1) === "DrawGlyph"));
  assert.ok(restPreview.hiddenElementIds.length >= 1);
  editor.close();
});

test("generated Kotlin/JS aligned free-practice ghost matches the committed note X", {
  skip: engineSkip,
}, async () => {
  const { MeconFreePractice, MeconFreePracticePreset, MeconWebEngine } =
    await import(await requireEngineModule());
  const value = (item) => Number(item?.value ?? item ?? 0);
  const commandX = (commands) => value(commands.find(
    (command) => command.type.split(".").at(-1) === "DrawGlyph",
  )?.position?.x);
  const engine = new MeconWebEngine("{}", "{}", "ghost-x-diagnostic");
  const preset = new MeconFreePracticePreset();
  const practice = new MeconFreePractice(preset.documentJson(), preset.scoreJson());
  const practiceBefore = JSON.parse(practice.initialUpdateJson());
  const practiceFrameBefore = JSON.parse(engine.renderFreePracticeFrameForWidthJson(
    JSON.stringify(practiceBefore.score.score), JSON.stringify(practiceBefore.timeline), 1200,
  ));
  const practiceStaff = practiceFrameBefore.bundle.surfaces[0].elements
    .find((element) => element.type === "STAFF");
  const practiceStaffLines = practiceStaff.commands
    .filter((command) => command.type.split(".").at(-1) === "DrawLine")
    .map((command) => value(command.start.y));
  const practiceTimeBefore = practiceFrameBefore.bundle.timePositions
    .find((position) => position.timeCode?.measure === 1);
  const practiceTarget = JSON.parse(engine.noteInputTargetJson(JSON.stringify({
    x: value(practiceTimeBefore.x),
    y: practiceStaffLines.reduce((sum, y) => sum + y, 0) / practiceStaffLines.length,
    duration: { base: "QUARTER" },
  })));
  const practiceGhostX = commandX(practiceTarget.commands);
  const practiceAfter = JSON.parse(practice.dispatchJson(JSON.stringify({
    type: "score",
    expectedRevision: practiceBefore.revision,
    inner: {
      type: "insertNote",
      expectedRevision: practiceBefore.score.revision,
      voiceTrackId: practiceTarget.voiceTrackId,
      staffTrackId: practiceTarget.staffTrackId,
      voiceNumber: practiceTarget.voiceNumber,
      start: practiceTarget.start,
      duration: { base: "QUARTER" },
      pitch: practiceTarget.pitch,
    },
  })));
  const practiceEventId = practiceAfter.score.selection[0].eventId;
  const practiceFrameAfter = JSON.parse(engine.renderFreePracticeFrameForWidthJson(
    JSON.stringify(practiceAfter.score.score), JSON.stringify(practiceAfter.timeline), 1200,
  ));
  const practiceNotehead = practiceFrameAfter.bundle.surfaces.flatMap((surface) => surface.elements)
    .find((element) => element.type === "NOTEHEAD" && element.eventId === practiceEventId);
  const practiceActualX = commandX(practiceNotehead.commands);
  const practiceTimeAfter = practiceFrameAfter.bundle.timePositions.find((position) =>
    position.timeCode?.measure === practiceTarget.start.measure &&
    value(position.timeCode?.beat?.numerator) === value(practiceTarget.start.beat?.numerator) &&
    value(position.timeCode?.beat?.denominator) === value(practiceTarget.start.beat?.denominator));
  console.info("[ghost-x-diagnostic:free-practice]", JSON.stringify({
    pointerX: value(practiceTimeBefore.x),
    ghostX: practiceGhostX,
    actualX: practiceActualX,
    delta: practiceGhostX - practiceActualX,
    beforeTimeX: value(practiceTimeBefore.x),
    beforeLeftX: value(practiceTimeBefore.leftX),
    afterTimeX: value(practiceTimeAfter?.x),
    afterLeftX: value(practiceTimeAfter?.leftX),
    targetStart: practiceTarget.start,
  }));
  assert.ok(Number.isFinite(practiceGhostX));
  assert.ok(Number.isFinite(practiceActualX));
  assert.equal(
    practiceGhostX,
    practiceActualX,
    `aligned free-practice ghost X must match its committed note: ${JSON.stringify({
      practiceGhostX, practiceActualX,
    })}`,
  );
  practice.close();
});

test("generated Kotlin/JS score editor applies a real insert intent", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "editable-test",
    measures: [{ number: 1 }],
    pitchTracks: {
      "pitch-1": { id: "pitch-1", events: [] },
    },
    voiceTracks: {
      "voice-1": { id: "voice-1", pitchTrackId: "pitch-1", events: [] },
    },
    staffTracks: {
      "staff-1": { id: "staff-1", voiceTrackIds: ["voice-1"] },
    },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  assert.equal(JSON.parse(editor.initialUpdateJson()).revision, 0);
  const update = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote",
    expectedRevision: 0,
    voiceTrackId: "voice-1",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" },
    pitch: { diatonicSteps: 0 },
  })));
  assert.equal(update.revision, 1);
  assert.equal(update.effect.kind, "APPLIED");
  assert.equal(update.selection.length, 1);
  const selected = update.score.voiceTracks["voice-1"].events
    .find((event) => event.id === update.selection[0].eventId);
  assert.ok(selected);
  const pitch = update.score.pitchTracks["pitch-1"].events
    .find((event) => event.id === selected.pitchEventId);
  assert.equal(pitch.pitches.length, 1);
  assert.equal(pitch.pitches[0].diatonicSteps, 0);
  assert.equal(pitch.pitches[0].chromaticOffset, 0);
  const copied = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "copyNotes",
    expectedRevision: 1,
    targets: [{ voiceTrackId: "voice-1", eventId: selected.id }],
  })));
  assert.equal(copied.revision, 2);
  assert.equal(copied.effect.kind, "COPIED");
  assert.equal(copied.canPaste, true);
  const pasted = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "pasteNotes",
    expectedRevision: 2,
    voiceTrackId: "voice-1",
    start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
  })));
  assert.equal(pasted.revision, 3);
  assert.equal(pasted.effect.kind, "PASTED");
  assert.equal(pasted.selection.length, 1);
  editor.close();
});

test("generated Kotlin/JS score editor inserts a chord atomically", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "chord-insert-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const update = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertChord", expectedRevision: 0, voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" },
    pitches: [{ diatonicSteps: 0 }, { diatonicSteps: 2 }, { diatonicSteps: 4 }],
  })));
  assert.equal(update.revision, 1);
  assert.equal(update.selection.length, 1);
  const event = update.score.voiceTracks.voice.events.find((item) => item.id === update.selection[0].eventId);
  assert.equal(update.score.pitchTracks.pitch.events.find((item) => item.id === event.pitchEventId).pitches.length, 3);
  editor.close();
});

test("generated Kotlin/JS score editor continues a tuplet from the shared input transition", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "tuplet-input-transition-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const first = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote", expectedRevision: 0, voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" }, tupletCount: 3,
    pitch: { diatonicSteps: 0 },
  })));

  assert.deepEqual(first.noteInputTransition, {
    duration: { base: "EIGHTH", dots: 0, tuplet: null },
    tupletCount: null,
  });
  assert.deepEqual(first.nextInputPosition, {
    measure: 1, beat: { numerator: 1, denominator: 12 },
  });

  const second = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote", expectedRevision: first.revision, voiceTrackId: "voice",
    start: first.nextInputPosition,
    duration: first.noteInputTransition.duration,
    pitch: { diatonicSteps: 1 },
  })));
  assert.equal(second.noteInputTransition, undefined);
  const events = second.score.voiceTracks.voice.events;
  const pitchedIds = new Set(second.score.pitchTracks.pitch.events
    .filter((event) => event.pitches.length > 0).map((event) => event.id));
  assert.equal(events.filter((event) => event.tupletSpan?.count === 3).length, 1);
  assert.equal(events.filter((event) => pitchedIds.has(event.pitchEventId) &&
    event.duration?.tuplet?.actual === 3).length, 2);
  editor.close();
});

test("generated Kotlin/JS score editor moves a note across staves", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "voice-move-test",
    measures: [{ number: 1 }],
    pitchTracks: {
      upperPitch: { id: "upperPitch", events: [] },
      lowerPitch: { id: "lowerPitch", events: [] },
    },
    voiceTracks: {
      upperVoice: { id: "upperVoice", voiceNumber: 1, pitchTrackId: "upperPitch", events: [] },
      lowerVoice: { id: "lowerVoice", voiceNumber: 1, pitchTrackId: "lowerPitch", events: [] },
    },
    staffTracks: {
      upperStaff: { id: "upperStaff", voiceTrackIds: ["upperVoice"] },
      lowerStaff: { id: "lowerStaff", voiceTrackIds: ["lowerVoice"] },
    },
    staffGroups: [{
      id: "grand",
      members: [
        { type: "staff", staffId: "upperStaff" },
        { type: "staff", staffId: "lowerStaff" },
      ],
    }],
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const inserted = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote",
    expectedRevision: 0,
    voiceTrackId: "upperVoice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" },
    pitch: { diatonicSteps: 0 },
  })));
  const eventId = inserted.selection[0].eventId;
  const moved = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "moveVoices",
    expectedRevision: 1,
    targets: [{
      voiceTrackId: "upperVoice",
      eventId,
      targetVoiceNumber: 1,
      targetStaffId: "lowerStaff",
    }],
  })));
  assert.equal(moved.effect.kind, "APPLIED");
  assert.equal(moved.selection[0].voiceTrackId, "lowerVoice");
  assert.ok(moved.score.voiceTracks.lowerVoice.events.some((event) => event.id === moved.selection[0].eventId));
  editor.close();
});

test("generated Kotlin/JS score editor applies structural intents", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "structure-test",
    measures: [{ number: 1 }, { number: 2 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", clef: "TREBLE", voiceTrackIds: ["voice"] } },
    staffGroups: [{ id: "group", members: [{ type: "staff", staffId: "staff" }] }],
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const clef = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "setClef",
    expectedRevision: 0,
    staffTrackId: "staff",
    onset: { measure: 0, beat: { numerator: 0, denominator: 1 } },
    clef: "BASS",
  })));
  assert.equal(clef.effect.kind, "APPLIED");
  assert.equal(clef.score.staffTracks.staff.clef, "BASS");
  assert.equal(clef.selection[0].type, "clef");
  const key = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "setKeySignature",
    expectedRevision: 1,
    onset: { measure: 0, beat: { numerator: 0, denominator: 1 } },
    keySignature: { root: 2, mode: "MAJOR" },
  })));
  assert.equal(key.selection[0].type, "keySignature");
  const meter = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "setTimeSignature",
    expectedRevision: 2,
    measureNumber: 1,
    timeSignature: { numerator: 3, denominator: 4 },
  })));
  assert.equal(meter.selection[0].type, "timeSignature");
  const inserted = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertMeasures",
    expectedRevision: 3,
    afterMeasure: 1,
    count: 1,
  })));
  assert.equal(inserted.score.measures.length, 3);
  const deleted = JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "deleteMeasures",
    expectedRevision: 4,
    measureNumbers: [2],
  })));
  assert.equal(deleted.score.measures.length, 2);
  editor.close();
});

test("generated Kotlin/JS score editor applies repeat structures", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "repeat-test",
    measures: [1, 2, 3, 4, 5].map((number) => ({ number })),
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", clef: "TREBLE", voiceTrackIds: ["voice"] } },
    staffGroups: [{ id: "group", members: [{ type: "staff", staffId: "staff" }] }],
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent,
    expectedRevision,
  })));
  const repeat = dispatch(0, {
    type: "setBarline", boundaryMeasure: 2, barlineType: "REPEAT_RIGHT", repeatCount: 3,
  });
  assert.equal(repeat.score.measures[1].repeatEnd, true);
  assert.equal(repeat.score.measures[1].repeatCount, 3);
  assert.equal(repeat.selection[0].type, "barline");
  const voltas = dispatch(1, { type: "toggleVoltaPair", boundaryMeasure: 0 });
  assert.deepEqual(voltas.score.measures[0].voltaNumbers, [1]);
  assert.deepEqual(voltas.score.measures[2].voltaNumbers, [2]);
  const navigation = dispatch(2, {
    type: "toggleNavigationMark", boundaryMeasure: 4, mark: "CODA",
  });
  assert.equal(navigation.selection[0].type, "navigationMark");
  const moved = dispatch(3, {
    type: "moveNavigationMark",
    boundaryMeasure: 4,
    targetBoundaryMeasure: 3,
    mark: "CODA",
    offset: { dx: 1, dy: -2 },
  });
  assert.deepEqual(moved.score.measures[2].navigationMarkOffsets.CODA, { dx: 1, dy: -2 });
  editor.close();
});

test("generated Kotlin/JS score editor creates and shapes a slur", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "slur-test",
    measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const first = dispatch(0, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 0 },
  });
  const second = dispatch(1, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 1 },
  });
  const slur = dispatch(2, {
    type: "addSlurs",
    targets: [{
      voiceTrackId: "voice",
      startEventId: first.selection[0].eventId,
      endEventId: second.selection[0].eventId,
    }],
  });
  assert.equal(slur.selection[0].type, "slur");
  const slurId = slur.selection[0].slurId;
  const shaped = dispatch(3, {
    type: "setSlurGeometry",
    slurId,
    geometry: {
      startPitchIndex: 0, endPitchIndex: 0,
      startDx: 0.5, startDy: -0.25, endDx: -0.5, endDy: -0.25,
      above: true, minApex: 1.2, maxApex: 3,
      slopeDamping: 1, middleStraightening: 0, directionOnly: true,
    },
  });
  assert.equal(shaped.score.geometry.slurs[slurId].directionLocked, true);
  const removed = dispatch(4, { type: "deleteSlurs", slurIds: [slurId] });
  assert.equal(removed.score.voiceTracks.voice.slurs.length, 0);
  editor.close();
});

test("generated Kotlin/JS score editor shapes one pitch tie", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "tie-test",
    measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const first = dispatch(0, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 0 },
  });
  dispatch(1, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 0 },
  });
  const sourceEventId = first.selection[0].eventId;
  dispatch(2, {
    type: "setTies",
    targets: [{ voiceTrackId: "voice", eventId: sourceEventId, tieOut: true, pitchIndices: [0] }],
  });
  const shaped = dispatch(3, {
    type: "setTieGeometry", sourceEventId,
    geometry: {
      sourcePitchIndex: 0, targetPitchIndex: 0,
      startDx: 0.25, startDy: -0.5, endDx: -0.25, endDy: -0.5,
      above: true, minApex: 1.25, maxApex: 2.75,
      slopeDamping: 1, middleStraightening: 0, directionOnly: false,
    },
  });
  assert.equal(shaped.selection[0].type, "tie");
  assert.equal(shaped.selection[0].sourcePitchIndex, 0);
  assert.equal(shaped.score.geometry.ties[sourceEventId][0].directionLocked, true);
  assert.equal(shaped.score.geometry.ties[sourceEventId][0].manuallyAdjusted, true);
  editor.close();
});

test("generated Kotlin/JS score editor applies expression attachments", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "expression-test",
    measures: [{ number: 1 }, { number: 2 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const start = { measure: 1, beat: { numerator: 0, denominator: 1 } };
  const end = { measure: 2, beat: { numerator: 0, denominator: 1 } };
  const dynamic = dispatch(0, { type: "addDynamic", staffTrackId: "staff", onset: start, level: "MF" });
  assert.equal(dynamic.selection[0].type, "attachment");
  const dynamicId = dynamic.selection[0].attachmentId;
  const hairpin = dispatch(1, {
    type: "addHairpin", staffTrackId: "staff", start, end,
    hairpinType: "CRESCENDO", style: "WEDGE",
  });
  assert.equal(hairpin.score.staffTracks.staff.attachments.length, 2);
  const tempo = dispatch(2, { type: "addTempoMark", onset: end, markType: "METRONOME", bpm: 96 });
  assert.equal(tempo.score.globalTrack.tempoEvents.at(-1).bpm, 96);
  const removed = dispatch(3, { type: "deleteExpressions", ids: [dynamicId] });
  assert.equal(removed.score.staffTracks.staff.attachments.length, 1);
  editor.close();
});

test("generated Kotlin/JS score editor applies layout and staff visibility", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "layout-test",
    measures: [{ number: 1 }, { number: 2 }, { number: 3 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const page = dispatch(0, { type: "setLayoutBreak", beforeMeasure: 2, kind: "PAGE" });
  assert.equal(page.score.globalTrack.events[0].type, "pageBreak");
  const hidden = dispatch(1, {
    type: "setStaffVisibility", staffTrackIds: ["staff"],
    startMeasure: 1, endMeasure: 2, hidden: true,
  });
  assert.deepEqual(hidden.score.staffTracks.staff.hiddenRanges, [{ from: 1, to: 2 }]);
  const shown = dispatch(2, {
    type: "setStaffVisibility", staffTrackIds: ["staff"],
    startMeasure: 1, endMeasure: 2, hidden: false,
  });
  assert.deepEqual(shown.score.staffTracks.staff.hiddenRanges, []);
  editor.close();
});

test("generated Kotlin/JS score editor inserts and edits a grace group", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "grace-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const onset = { measure: 1, beat: { numerator: 0, denominator: 1 } };
  dispatch(0, {
    type: "insertNote", voiceTrackId: "voice", start: onset,
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 0 },
  });
  const grace = dispatch(1, {
    type: "insertNote", voiceTrackId: "voice", start: onset,
    duration: { base: "EIGHTH" }, pitch: { diatonicSteps: 1 },
    grace: {
      totalDuration: { base: "EIGHTH" }, stealFrom: "PRINCIPAL", noteType: "ACCIACCATURA",
    },
  });
  const graceId = grace.selection[0].eventId;
  const edited = dispatch(2, {
    type: "setGraceGroups",
    targets: [{
      voiceTrackId: "voice", eventId: graceId,
      totalDuration: { base: "SIXTEENTH" }, stealFrom: "PREVIOUS",
    }],
  });
  const event = edited.score.voiceTracks.voice.events.find((item) => item.id === graceId);
  assert.equal(event.graceInfo.totalDuration.base, "SIXTEENTH");
  assert.equal(event.graceInfo.stealFrom, "PREVIOUS");
  editor.close();
});

test("generated Kotlin/JS score editor applies tuplets, articulations, and small-note regions", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "note-properties-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const first = dispatch(0, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "EIGHTH" }, pitch: { diatonicSteps: 0 },
  });
  const firstId = first.selection[0].eventId;
  const second = dispatch(1, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 8 } },
    duration: { base: "EIGHTH" }, pitch: { diatonicSteps: 1 },
  });
  const secondId = second.selection[0].eventId;
  const third = dispatch(2, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
    duration: { base: "QUARTER" }, isRest: true,
  });
  const thirdId = third.selection[0].eventId;
  const articulated = dispatch(3, {
    type: "toggleArticulation", articulation: "STACCATO",
    targets: [{ voiceTrackId: "voice", eventId: firstId }],
  });
  const firstVoiceEvent = articulated.score.voiceTracks.voice.events.find((item) => item.id === firstId);
  const firstPitchEvent = articulated.score.pitchTracks.pitch.events
    .find((item) => item.id === firstVoiceEvent.pitchEventId);
  assert.deepEqual(firstPitchEvent.articulations, ["STACCATO"]);

  const tupled = dispatch(4, {
    type: "applyTuplets",
    targets: [{ voiceTrackId: "voice", eventIds: [firstId, secondId], count: 3 }],
  });
  const tupletStart = tupled.score.voiceTracks.voice.events
    .find((item) => item.id === firstId);
  assert.equal(tupletStart.tupletSpan.count, 3);
  assert.deepEqual(tupletStart.duration.tuplet, { actual: 3, normal: 2 });

  const small = dispatch(5, {
    type: "createSmallNoteRegions",
    targets: [{ voiceTrackId: "voice", eventIds: [thirdId] }],
  });
  const smallStart = small.score.voiceTracks.voice.events
    .find((item) => item.id === thirdId);
  assert.equal(smallStart.tupletSpan.smallNotes, true);
  assert.equal(smallStart.rendering.scale, 0.7);
  editor.close();
});

test("generated Kotlin/JS score editor applies inspector property intents", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const score = {
    id: "inspector-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, intent) => JSON.parse(editor.dispatchJson(JSON.stringify({
    ...intent, expectedRevision,
  })));
  const note = dispatch(0, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" }, pitch: { diatonicSteps: 0 },
  });
  const noteId = note.selection[0].eventId;
  const arpeggio = dispatch(1, {
    type: "setArpeggio", arpeggioType: "UP",
    targets: [{ voiceTrackId: "voice", eventId: noteId }],
  });
  assert.equal(arpeggio.score.voiceTracks.voice.events.find((item) => item.id === noteId)
    .rendering.arpeggio, "UP");

  const ornament = dispatch(2, {
    type: "addOrnament", staffTrackId: "staff", sourceEventId: noteId,
    ornamentKind: "TRILL", anchor: "ON_NOTE",
  });
  const ornamentId = ornament.selection[0].attachmentId;
  const ornamentUpdated = dispatch(3, {
    type: "updateOrnament", ornamentId, oscillations: 7,
  });
  assert.equal(ornamentUpdated.score.staffTracks.staff.attachments
    .find((item) => item.id === ornamentId).oscillations, 7);

  const tempo = dispatch(4, {
    type: "addTempoMark",
    onset: { measure: 1, beat: { numerator: 1, denominator: 4 } },
    markType: "METRONOME", bpm: 96,
  });
  const tempoId = tempo.selection[0].attachmentId;
  const tempoUpdated = dispatch(5, {
    type: "updateTempo", tempoId, effectiveBpm: 108, displayStyle: "HIDDEN",
  });
  const storedTempo = tempoUpdated.score.globalTrack.tempoEvents.find((item) => item.id === tempoId);
  assert.equal(storedTempo.bpm, 108);
  assert.equal(storedTempo.displayStyle, "HIDDEN");

  const rest = dispatch(6, {
    type: "insertNote", voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 1, denominator: 2 } },
    duration: { base: "QUARTER" }, isRest: true,
  });
  const restId = rest.selection[0].eventId;
  const restMoved = dispatch(7, {
    type: "moveRests", targets: [{ voiceTrackId: "voice", eventId: restId, staffPosition: 2 }],
  });
  assert.equal(restMoved.score.voiceTracks.voice.events.find((item) => item.id === restId)
    .rendering.restStaffPosition, 2);

  const fermata = dispatch(8, {
    type: "addFermata", afterTime: { measure: 1, beat: { numerator: 3, denominator: 4 } },
  });
  const fermataId = fermata.selection[0].attachmentId;
  const performance = dispatch(9, {
    type: "updatePerformanceMark", markId: fermataId,
    amount: { numerator: 3, denominator: 2 },
  });
  assert.deepEqual(performance.score.globalTrack.events.find((item) => item.id === fermataId).extension,
    { numerator: 3, denominator: 2 });
  editor.close();
});

test("generated frozen chord noteheads preserve pitch indices for partial selection", {
  skip: engineSkip,
}, async () => {
  const { MeconScoreEditor, MeconWebEngine } = await import(await requireEngineModule());
  const score = {
    id: "chord-hit-test", measures: [{ number: 1 }],
    pitchTracks: { pitch: { id: "pitch", events: [] } },
    voiceTracks: { voice: { id: "voice", pitchTrackId: "pitch", events: [] } },
    staffTracks: { staff: { id: "staff", voiceTrackIds: ["voice"] } },
    instruments: [{ id: "instrument", staffIds: ["staff"] }],
    staffGroups: [{ id: "group", members: [{ type: "staff", staffId: "staff" }] }],
  };
  const editor = new MeconScoreEditor(JSON.stringify(score));
  const dispatch = (expectedRevision, pitch) => JSON.parse(editor.dispatchJson(JSON.stringify({
    type: "insertNote", expectedRevision, voiceTrackId: "voice",
    start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
    duration: { base: "QUARTER" }, pitch,
  })));
  dispatch(0, { diatonicSteps: 0 });
  const chord = dispatch(1, { diatonicSteps: 2 });
  const eventId = chord.selection[0].eventId;
  const metadata = readFileSync(new URL(
    "../../../../apps/desktop/src/main/resources/bravura/bravuraMetadata.json",
    import.meta.url,
  ), "utf8");
  const glyphNames = readFileSync(new URL(
    "../../../../apps/desktop/src/main/resources/bravura/glyphnames.json",
    import.meta.url,
  ), "utf8");
  const engine = new MeconWebEngine(metadata, glyphNames, "test");
  const bundle = JSON.parse(engine.renderScoreJson(JSON.stringify(chord.score)));
  const allElements = bundle.surfaces.flatMap((surface) => surface.elements ?? []);
  const pitchIndices = allElements
    .filter((element) => element.type === "NOTEHEAD" && element.eventId === eventId)
    .map((element) => element.metadata?.pitchIndex)
    .sort();
  assert.deepEqual(pitchIndices, ["0", "1"], JSON.stringify(allElements
    .filter((element) => element.type === "NOTEHEAD")
    .map((element) => ({ eventId: element.eventId, metadata: element.metadata }))));
  assert.ok(allElements
    .filter((element) => element.type === "NOTEHEAD" && element.eventId === eventId)
    .every((element) => element.metadata?.noteheadFilled === "true"));
  editor.close();
});
