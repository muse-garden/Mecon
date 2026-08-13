import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  createMeconFreePractice,
  createMeconFreePracticePreset,
} from "../index.js";
import { engineSkip, requireEngineModule } from "./engine-module.js";

const fraction = (value) => (value == null ? "null" : `${value.numerator}/${value.denominator}`);

const parseFraction = (value) => {
  if (value == null) return null;
  const [numerator, denominator] = value.split("/");
  return { numerator: Number(numerator), denominator: Number(denominator) };
};

/** Mirrors FreePracticeTraceTest.timelineEdit: slots and layouts are addressed by index. */
function timelineEdit(step, update) {
  const slot = (key = "slotIndex") => update.timeline.slots[step[key]].id;
  switch (step.edit) {
    case "placeChordRange":
      return {
        type: "placeChordRange",
        slotId: slot(),
        onset: parseFraction(step.onset),
        duration: parseFraction(step.duration),
      };
    case "translateChordRange":
      return {
        type: "translateChordRange",
        slotId: slot(),
        delta: parseFraction(step.delta),
        includeFollowing: step.includeFollowing ?? false,
      };
    case "moveSharedBoundary":
      return {
        type: "moveSharedBoundary",
        leftSlotId: slot("leftSlotIndex"),
        boundary: parseFraction(step.boundary),
      };
    case "moveBoundaryWithFollowing":
      return {
        type: "moveBoundaryWithFollowing",
        leftSlotId: slot("leftSlotIndex"),
        boundary: parseFraction(step.boundary),
      };
    case "setTonalLayoutBounds":
      return {
        type: "setTonalLayoutBounds",
        tonalLayoutId: update.timeline.tonalLayouts[step.layoutIndex].id,
        start: parseFraction(step.start),
        end: parseFraction(step.end),
      };
    default:
      throw new Error(`Unknown timeline edit ${step.edit}`);
  }
}

test("generated Kotlin/JS free-practice session replays the JVM golden trace", {
  skip: engineSkip,
}, async () => {
  const engineModule = await import(await requireEngineModule());
  const preset = await createMeconFreePracticePreset({ engineModule });
  assert.deepEqual(preset.module, {
    id: "free-practice",
    type: "exploration.free-practice",
    schemaVersion: 9,
  });
  const session = await createMeconFreePractice({ ...preset, engineModule });
  const preview = session.previewTimelineEdit({
    requestId: 99,
    baseRevision: 0,
    edit: {
      type: "placeChordRange",
      slotId: "slot-0",
      onset: { numerator: 0, denominator: 1 },
      duration: { numerator: 1, denominator: 2 },
    },
  });
  assert.equal(preview.accepted, true);
  assert.deepEqual(preview.timeline.slots[0].duration, { numerator: 1, denominator: 2 });
  const initial = session.initialUpdate();
  assert.equal(initial.revision, 0, "preview must not mutate the session");
  let catalogRequest = initial.catalogRequests[0];
  const trace = JSON.parse(readFileSync(new URL(
    "../../../../features/free-practice/testdata/practice-trace.json",
    import.meta.url,
  ), "utf8"));
  let request;
  let insertedTonalLayoutId;
  let insertedIdiomId;
  let appendedSlotId;
  let findingRequest = initial.findingRequests[0];
  let previewRequestId = 100;
  for (const step of trace.steps) {
    let update;
    if (step.kind === "staleTarget") {
      update = session.dispatch({ type: "selectSlot", expectedRevision: step.expectedRevision, slotId: "missing" });
    } else if (step.kind === "runWriting") {
      update = session.dispatch({
        type: "runWriting", expectedRevision: step.expectedRevision, triggerSlotId: "slot-0",
      });
      request = update.requests[0];
    } else if (step.kind === "setPivotChord") {
      update = session.dispatch({
        type: "setPivotChord", expectedRevision: step.expectedRevision, slotId: "slot-0", selected: true,
      });
    } else if (step.kind === "setTonalLayoutKey") {
      update = session.dispatch({
        type: "setTonalLayoutKey",
        expectedRevision: step.expectedRevision,
        tonalLayoutId: "tonal-layout-0",
        fifths: step.fifths,
        mode: step.mode,
      });
    } else if (step.kind === "timelineEdit") {
      // Same PracticeTimelineEdit for preview and commit — the shape the browser sends on drag.
      const edit = timelineEdit(step, session.initialUpdate());
      const preview = session.previewTimelineEdit({
        requestId: ++previewRequestId,
        baseRevision: session.initialUpdate().revision,
        edit,
      });
      update = session.dispatch({ type: "timelineEdit", expectedRevision: step.expectedRevision, edit });
      if (preview.accepted) assert.deepEqual(preview.timeline, update.timeline, step.edit);
    } else if (step.kind === "insertTonalLayout") {
      update = session.dispatch({
        type: "insertTonalLayout",
        expectedRevision: step.expectedRevision,
        fifths: step.fifths,
        mode: step.mode,
        start: { numerator: 0, denominator: 1 },
        end: { numerator: 1, denominator: 4 },
      });
      insertedTonalLayoutId = update.timeline.tonalLayouts.find((layout) => layout.id !== "tonal-layout-0").id;
    } else if (step.kind === "selectChordTonalLayout") {
      update = session.dispatch({
        type: "selectChordTonalLayout",
        expectedRevision: step.expectedRevision,
        slotId: "slot-0",
        tonalLayoutId: insertedTonalLayoutId,
      });
    } else if (step.kind === "selectTonalLayout") {
      update = session.dispatch({
        type: "selectTonalLayout",
        expectedRevision: step.expectedRevision,
        tonalLayoutId: step.target === "inserted" ? insertedTonalLayoutId : "tonal-layout-0",
      });
    } else if (step.kind === "selectIdiomTonalLayout") {
      update = session.dispatch({
        type: "selectIdiomTonalLayout",
        expectedRevision: step.expectedRevision,
        tonalLayoutId: step.target === "inserted" ? insertedTonalLayoutId : "tonal-layout-0",
      });
    } else if (step.kind === "setInsertedTonalLayoutKey") {
      update = session.dispatch({
        type: "setTonalLayoutKey",
        expectedRevision: step.expectedRevision,
        tonalLayoutId: insertedTonalLayoutId,
        fifths: step.fifths,
        mode: step.mode,
      });
    } else if (step.kind === "removeTonalLayout") {
      update = session.dispatch({
        type: "removeTonalLayout",
        expectedRevision: step.expectedRevision,
        tonalLayoutId: insertedTonalLayoutId,
      });
    } else if (step.kind === "applyCatalogFixture") {
      const variant = (id) => ({
        id,
        title: id,
        durations: [{ numerator: 1, denominator: 4 }],
        chordIdentities: ["I"],
        chordChoices: [session.initialUpdate().catalog.chordChoices[0].choice],
        targetKeyDistance: 0,
        parameters: {},
        anchorStepIndex: 0,
        fixedInversionStepIndices: [],
      });
      update = session.applyTeachingCatalogResult({
        requestId: catalogRequest.requestId,
        baseRevision: catalogRequest.baseRevision,
        fingerprint: catalogRequest.fingerprint,
        definitions: [{
          id: "trace.idiom",
          title: "Trace idiom",
          sourceExerciseId: "trace-exercise",
          sourceChapterId: "trace-chapter",
          availableByDefault: true,
          variants: [variant("variant-a"), variant("variant-b")],
        }],
      });
    } else if (step.kind === "insertIdiom") {
      update = session.dispatch({
        type: "insertIdiom",
        expectedRevision: step.expectedRevision,
        anchorSlotId: "slot-0",
        definitionId: "trace.idiom",
        variantId: "variant-a",
      });
      insertedIdiomId = update.document.workspace.idiomInstances[0].id;
    } else if (step.kind === "replaceIdiom") {
      update = session.dispatch({
        type: "replaceIdiom",
        expectedRevision: step.expectedRevision,
        idiomInstanceId: insertedIdiomId,
        definitionId: "trace.idiom",
        variantId: "variant-b",
      });
    } else if (step.kind === "selectIdiom") {
      update = session.dispatch({
        type: "selectIdiom",
        expectedRevision: step.expectedRevision,
        idiomInstanceId: insertedIdiomId,
      });
    } else if (step.kind === "removeIdiom") {
      update = session.dispatch({
        type: "removeIdiom",
        expectedRevision: step.expectedRevision,
        idiomInstanceId: insertedIdiomId,
      });
    } else if (step.kind === "updateWritingSettings") {
      update = session.dispatch({
        type: "updateWritingSettings",
        expectedRevision: step.expectedRevision,
        settings: {
          ...session.initialUpdate().document.settings.writing,
          autoWritingEnabled: false,
          backtrackChordCount: 2,
          replayChordCount: 3,
          playbackTempoBpm: 96,
        },
      });
    } else if (step.kind === "insertChordRange") {
      const before = new Set(session.initialUpdate().timeline.slots.map((slot) => slot.id));
      update = session.dispatch({
        type: "insertChordRange",
        expectedRevision: step.expectedRevision,
        onset: session.initialUpdate().timeline.end,
        duration: { numerator: 1, denominator: 4 },
      });
      appendedSlotId = update.timeline.slots.find((slot) => !before.has(slot.id)).id;
    } else if (step.kind === "removeChordRange") {
      update = session.dispatch({
        type: "removeChordRange",
        expectedRevision: step.expectedRevision,
        slotId: appendedSlotId,
      });
    } else if (step.kind === "setCatalogFilter") {
      update = session.dispatch({
        type: "setCatalogFilter",
        expectedRevision: step.expectedRevision,
        includeOffKey: true,
      });
    } else if (step.kind === "updateStaffVoices") {
      update = session.dispatch({
        type: "updateStaffVoices",
        expectedRevision: step.expectedRevision,
        staffVoices: {
          upperVoiceCount: step.upperVoiceCount,
          lowerVoiceCount: step.lowerVoiceCount,
        },
      });
    } else if (step.kind === "rebuildPractice") {
      update = session.dispatch({
        type: "rebuildPractice",
        expectedRevision: step.expectedRevision,
        polyphonyLimit: step.polyphonyLimit,
        fifths: step.fifths,
        mode: step.mode,
      });
    } else if (step.kind === "applyFindingFixture") {
      update = session.applyFindingResult({
        requestId: findingRequest.requestId,
        baseRevision: findingRequest.baseRevision,
        fingerprint: findingRequest.fingerprint,
        items: [{
          messageKey: "freePractice.finding.trace",
          arguments: {},
          severity: "INFO",
          anchors: [],
          message: "共享规则提示文字",
        }],
      });
    } else if (step.kind === "applyFixture") {
      const voices = [...request.document.workspace.voices].sort((a, b) => a.order - b.order);
      const pitchesByVoiceId = Object.fromEntries(voices.map((voice) => [voice.id, voice.highest]));
      update = session.applyBackgroundResult({
        requestId: request.requestId,
        baseRevision: request.baseRevision,
        scopeFingerprint: request.scopeFingerprint,
        kind: request.kind,
        candidates: [{
          frames: [{ slotId: request.triggerSlotId, pitchesByVoiceId }],
          diversityGroupKey: "trace-primary",
          score: 0,
          diagnosticKeys: [],
        }],
        outcome: { type: "solved", scope: request.scopeSlotIds, replayRange: null },
      });
    } else if (step.kind === "backgroundFailure") {
      // The crash channel every shell must use when a background worker dies.
      update = session.applyBackgroundFailure({ requestId: request.requestId, reason: step.reason });
    } else if (step.kind === "insertManualNote") {
      const before = session.initialUpdate();
      update = session.dispatch({
        type: "score",
        expectedRevision: step.expectedRevision,
        inner: {
          type: "insertNote",
          expectedRevision: before.score.revision,
          voiceTrackId: before.document.workspace.voices[0].id,
          start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
          duration: { base: "QUARTER" },
          pitch: { diatonicSteps: 7 },
        },
      });
    } else if (step.kind === "rejectPolyphonyChord") {
      const before = session.initialUpdate();
      update = session.dispatch({
        type: "score",
        expectedRevision: step.expectedRevision,
        inner: {
          type: "insertChord",
          expectedRevision: before.score.revision,
          voiceTrackId: before.document.workspace.voices[0].id,
          start: { measure: 1, beat: { numerator: 0, denominator: 1 } },
          duration: { base: "QUARTER" },
          pitches: [7, 9, 11, 13].map((diatonicSteps) => ({ diatonicSteps })),
        },
      });
    } else if (step.kind === "staleScoreRevision") {
      update = session.dispatch({
        type: "score",
        expectedRevision: step.expectedRevision,
        inner: { type: "undo", expectedRevision: 0 },
      });
    } else if (step.kind === "setHarmonicRole") {
      const before = session.initialUpdate();
      const event = Object.values(before.score.score.voiceTracks)
        .flatMap((voice) => voice.events).find((candidate) => !candidate.isRest);
      update = session.dispatch({
        type: "setHarmonicRole",
        expectedRevision: step.expectedRevision,
        noteheads: [{ eventId: event.id, pitchIndex: 0 }],
        role: "CHORD_TONE",
      });
    } else if (step.kind === "setHarmonicRoleFilters") {
      update = session.dispatch({
        type: "setHarmonicRoleFilters",
        expectedRevision: step.expectedRevision,
        chordCatalogEnabled: true,
        idiomCatalogEnabled: true,
      });
    } else if (step.kind === "setVoiceLock") {
      const before = session.initialUpdate();
      const event = Object.values(before.score.score.voiceTracks)
        .flatMap((voice) => voice.events).find((candidate) => !candidate.isRest);
      const voice = Object.values(before.score.score.voiceTracks)
        .find((candidate) => candidate.events.some((item) => item.id === event.id));
      update = session.dispatch({
        type: "setVoiceLock", expectedRevision: step.expectedRevision,
        voiceTrackId: voice.id, locked: true,
      });
    } else if (step.kind === "insertLockedNote") {
      const before = session.initialUpdate();
      update = session.dispatch({
        type: "score",
        expectedRevision: step.expectedRevision,
        inner: {
          type: "insertNote",
          expectedRevision: before.score.revision,
          voiceTrackId: before.document.noteConstraints.lockedVoiceTrackIds[0],
          start: { measure: 1, beat: { numerator: 1, denominator: 4 } },
          duration: { base: "QUARTER" },
          pitch: { diatonicSteps: 8 },
        },
      });
    } else if (step.kind === "setStaffLock") {
      const before = session.initialUpdate();
      const voiceId = before.document.noteConstraints.lockedVoiceTrackIds[0];
      const staff = Object.values(before.score.score.staffTracks)
        .find((candidate) => candidate.voiceTrackIds.includes(voiceId));
      update = session.dispatch({
        type: "setStaffLock", expectedRevision: step.expectedRevision,
        staffTrackId: staff.id, locked: true,
      });
    } else {
      update = session.dispatch({ type: step.kind, expectedRevision: step.expectedRevision });
    }
    if (update.catalogRequests.length === 1) catalogRequest = update.catalogRequests[0];
    if (update.findingRequests.length === 1) findingRequest = update.findingRequests[0];
    assert.equal(update.schemaVersion, 3, `${step.kind} schema`);
    assert.equal(update.revision, step.revision, step.kind);
    assert.equal(update.effect.kind, step.effect, step.kind);
    if (step.editPlayback !== undefined) {
      assert.equal(update.editPlayback?.type, step.editPlayback, step.kind);
    }
    const slotIds = update.document.workspace.slots.map((slot) => slot.id);
    assert.equal(new Set(slotIds).size, slotIds.length, `${step.kind} stable slot ids`);
    assert.ok(update.findings.items.every((finding) => finding.messageKey.startsWith("freePractice.")));
    if (step.outcome) assert.equal(update.writing.outcome?.type, step.outcome);
    if (step.scoreHasNotes !== undefined) {
      const hasNotes = Object.values(update.score.score.voiceTracks)
        .some((voice) => voice.events.some((event) => !event.isRest));
      assert.equal(hasNotes, step.scoreHasNotes, step.kind);
    }
    if (step.assignmentSourceCount !== undefined) {
      assert.equal(
        Object.keys(update.document.workspace.voiceAssignmentSources).length,
        step.assignmentSourceCount,
        step.kind,
      );
    }
    if (step.roleCount !== undefined) {
      assert.equal(update.document.noteConstraints.harmonicRoles.length, step.roleCount, step.kind);
    }
    if (step.conflictCount !== undefined) {
      assert.equal(update.noteConstraints.noteheads.filter((item) => item.conflict).length,
        step.conflictCount, step.kind);
    }
    if (step.chordRoleFilter !== undefined) {
      assert.equal(update.noteConstraints.chordCatalogFilterEnabled, step.chordRoleFilter, step.kind);
    }
    if (step.idiomRoleFilter !== undefined) {
      assert.equal(update.noteConstraints.idiomCatalogFilterEnabled, step.idiomRoleFilter, step.kind);
    }
    if (step.planChordSoundsMatchCatalog !== undefined) {
      const sounds = new Set(update.catalog.chordChoices.map((item) =>
        [...item.choice.pitchClasses].sort((a, b) => a - b).join(",")));
      const matches = update.plan.chordCatalogFilters
        .flatMap((filter) => filter.chordGroups)
        .flatMap((group) => group.choices)
        .every((item) => sounds.has([...item.choice.pitchClasses].sort((a, b) => a - b).join(",")));
      assert.equal(matches, step.planChordSoundsMatchCatalog, step.kind);
    }
    if (step.lockedVoiceCount !== undefined) {
      assert.equal(update.document.noteConstraints.lockedVoiceTrackIds.length,
        step.lockedVoiceCount, step.kind);
    }
    if (step.lockedStaffCount !== undefined) {
      assert.equal(update.document.noteConstraints.lockedStaffTrackIds.length,
        step.lockedStaffCount, step.kind);
    }
    if (step.lockedNoteCount !== undefined) {
      assert.equal(update.noteConstraints.noteheads.filter((item) => item.locked).length,
        step.lockedNoteCount, step.kind);
    }
    if (step.scoreChanged !== undefined) {
      assert.equal(update.score.scoreChanged, step.scoreChanged, step.kind);
    }
    if (step.renderFirstMeasure !== undefined) {
      assert.equal(update.score.renderHint?.firstMeasure, step.renderFirstMeasure, step.kind);
    }
    if (step.renderStructureReflow !== undefined) {
      assert.equal(update.score.renderHint?.structureReflow, step.renderStructureReflow, step.kind);
    }
    if (step.pivot !== undefined) assert.equal(update.plan.pivotEnabled, step.pivot, step.kind);
    if (step.kind === "setTonalLayoutKey") {
      assert.equal(update.plan.currentKey?.fifths, step.fifths, step.kind);
      assert.equal(update.plan.currentKey?.mode, step.mode, step.kind);
    }
    if (step.slots !== undefined) {
      assert.deepEqual(
        update.timeline.slots.map((slot) => `${fraction(slot.onset)}+${fraction(slot.duration)}`),
        step.slots,
        step.kind,
      );
    }
    if (step.layouts !== undefined) {
      assert.deepEqual(
        update.timeline.tonalLayouts.map((layout) => `${fraction(layout.start)}+${fraction(layout.end)}`),
        step.layouts,
        step.kind,
      );
    }
    if (step.layoutCount !== undefined) assert.equal(update.timeline.tonalLayouts.length, step.layoutCount, step.kind);
    if (step.selectedLayout !== undefined) {
      assert.equal(update.document.workspace.slots[0].tonalLayoutId, step.selectedLayout, step.kind);
    }
    if (step.selectionLayout !== undefined) {
      assert.equal(update.selection.tonalLayoutId, step.selectionLayout, step.kind);
    }
    if (step.idiomCatalogLayout !== undefined) {
      assert.equal(update.plan.idiomCatalogFilters.find((filter) => filter.selected)?.tonalLayoutId,
        step.idiomCatalogLayout, step.kind);
    }
    if (step.selectionIdiom !== undefined) {
      assert.equal(update.selection.idiomInstanceId, step.selectionIdiom, step.kind);
    }
    if (step.catalogDefinitionCount !== undefined) {
      assert.equal(update.plan.idiomCatalog.definitions.length, step.catalogDefinitionCount, step.kind);
    }
    if (step.idiomCount !== undefined) {
      assert.equal(update.document.workspace.idiomInstances.length, step.idiomCount, step.kind);
    }
    if (step.idiomVariant !== undefined) {
      assert.equal(update.document.workspace.idiomInstances[0].variantId, step.idiomVariant, step.kind);
    }
    if (step.playbackTempoBpm !== undefined) {
      assert.equal(update.document.settings.writing.playbackTempoBpm, step.playbackTempoBpm, step.kind);
      assert.equal(update.document.settings.writing.autoWritingEnabled, false, step.kind);
    }
    if (step.slotCount !== undefined) assert.equal(update.timeline.slots.length, step.slotCount, step.kind);
    if (step.includeOffKey !== undefined) {
      assert.equal(update.plan.idiomCatalog.includeOffKey, step.includeOffKey, step.kind);
      assert.equal(update.catalogRequests[0].includeOffKey, step.includeOffKey, step.kind);
    }
    if (step.polyphonyLimit !== undefined) {
      assert.equal(update.document.settings.polyphonyLimit, step.polyphonyLimit, step.kind);
      assert.equal(update.document.workspace.voices.length, step.polyphonyLimit, step.kind);
      assert.equal(update.document.settings.initialKey.fifths, step.fifths, step.kind);
      assert.equal(update.document.settings.initialKey.mode, step.mode, step.kind);
    }
    if (step.findingCount !== undefined) {
      assert.equal(update.findings.items.length, step.findingCount, step.kind);
      assert.equal(update.findings.stale, false, step.kind);
    }
    if (step.findingMessage !== undefined) {
      assert.equal(update.findings.items[0]?.message, step.findingMessage, step.kind);
    }
    if (step.upperVoiceCount !== undefined) {
      assert.equal(update.document.settings.staffVoices.upperVoiceCount, step.upperVoiceCount, step.kind);
      assert.equal(update.document.settings.staffVoices.lowerVoiceCount, step.lowerVoiceCount, step.kind);
    }
  }
  session.close();
});

test("Kotlin/JS timeline channels stay bounded at 32 and 64 slots", {
  skip: engineSkip,
  timeout: 60_000,
}, async () => {
  const engineModule = await import(await requireEngineModule());
  const percentile = (samples, ratio) => [...samples].sort((a, b) => a - b)[
    Math.min(samples.length - 1, Math.floor((samples.length - 1) * ratio))
  ];
  const measured = (action) => {
    const start = performance.now();
    action();
    return performance.now() - start;
  };
  for (const [voiceCount, slotCount] of [[4, 32], [6, 64]]) {
    const preset = await createMeconFreePracticePreset({ engineModule });
    const session = await createMeconFreePractice({ ...preset, engineModule });
    let update = session.initialUpdate();
    if (voiceCount !== 4) {
      update = session.dispatch({
        type: "rebuildPractice",
        expectedRevision: update.revision,
        polyphonyLimit: voiceCount,
        fifths: 0,
        mode: "MAJOR",
      });
    }
    update = session.dispatch({
      type: "updateWritingSettings",
      expectedRevision: update.revision,
      settings: { ...update.document.settings.writing, autoWritingEnabled: false },
    });
    while (update.timeline.slots.length < slotCount) {
      update = session.dispatch({
        type: "insertChordRange",
        expectedRevision: update.revision,
        onset: update.timeline.end,
        duration: { numerator: 1, denominator: 4 },
      });
    }
    const target = update.timeline.slots.at(-1);
    const writingTarget = update.timeline.slots[0];
    const previews = Array.from({ length: 12 }, (_, index) => measured(() => {
      const result = session.previewTimelineEdit({
        requestId: index,
        baseRevision: update.revision,
        edit: {
          type: "placeChordRange",
          slotId: target.id,
          onset: target.onset,
          duration: index % 2 ? { numerator: 1, denominator: 4 } : { numerator: 1, denominator: 8 },
        },
      });
      assert.equal(result.accepted, true);
    }));
    const commits = Array.from({ length: 12 }, (_, index) => measured(() => {
      update = session.dispatch({
        type: "timelineEdit",
        expectedRevision: update.revision,
        edit: {
          type: "placeChordRange",
          slotId: target.id,
          onset: target.onset,
          duration: index % 2 ? { numerator: 1, denominator: 4 } : { numerator: 1, denominator: 8 },
        },
      });
      assert.ok(["APPLIED", "NO_OP"].includes(update.effect.kind));
    }));
    const cancellations = Array.from({ length: 12 }, () => measured(() => {
      update = session.dispatch({
        type: "runWriting", expectedRevision: update.revision, triggerSlotId: writingTarget.id,
      });
      assert.equal(update.effect.kind, "WRITING_REQUESTED");
      update = session.dispatch({ type: "cancelWriting", expectedRevision: update.revision });
      assert.equal(update.effect.kind, "WRITING_CANCELLED");
    }));
    for (const [phase, samples] of Object.entries({ previews, commits, cancellations })) {
      const p50 = percentile(samples, 0.50);
      const p95 = percentile(samples, 0.95);
      console.log(`free-practice js ${voiceCount} voices/${slotCount} slots ${phase} p50=${p50.toFixed(2)}ms p95=${p95.toFixed(2)}ms`);
      assert.ok(p95 < 5_000, `${phase} p95=${p95}ms`);
    }
    session.close();
  }
});

test("generated Kotlin/JS executor solves a real free-writing request without forbidden-table data", {
  skip: engineSkip,
}, async () => {
  const engineModule = await import(await requireEngineModule());
  const preset = await createMeconFreePracticePreset({ engineModule });
  const session = await createMeconFreePractice({ ...preset, engineModule });
  const requested = session.dispatch({ type: "runWriting", expectedRevision: 0, triggerSlotId: "slot-0" });
  const result = session.executeBackgroundRequest(requested.requests[0]);
  assert.ok(["solved", "noSolution", "budgetExhausted", "invalid"].includes(result.outcome.type));
  const applied = session.applyBackgroundResult(result);
  assert.equal(applied.revision, 2);
  assert.notEqual(applied.effect.kind, "STALE_BACKGROUND_RESULT");
  session.close();
});

test("generated Kotlin/JS executor computes findings on its independent channel", {
  skip: engineSkip,
}, async () => {
  const engineModule = await import(await requireEngineModule());
  const preset = await createMeconFreePracticePreset({ engineModule });
  const session = await createMeconFreePractice({ ...preset, engineModule });
  const initial = session.initialUpdate();
  assert.equal(initial.findings.stale, true);
  const request = initial.findingRequests[0];
  const result = session.executeFindingRequest(request);
  assert.equal(result.fingerprint, request.fingerprint);
  const applied = session.applyFindingResult(result);
  assert.equal(applied.effect.kind, "FINDINGS_UPDATED");
  assert.equal(applied.findings.stale, false);
  assert.equal(applied.findingRequests.length, 0);
  session.close();
});
