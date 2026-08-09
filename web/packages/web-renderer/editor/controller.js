import {
  allEventSelectionTargets,
  isRestTarget,
  quarterBeatFraction,
  resolveEventTargets,
} from "./interaction.js";

/**
 * Framework-neutral command controller for common event operations. Hosts own
 * transport and form state; this controller owns shared-session intent shapes.
 */
export function createScoreEditorCommandController({
  update,
  input,
  structure = {},
  expression = {},
  destinations = [],
  dispatch,
}) {
  const eventTargets = () => resolveEventTargets(update);

  function editSelection(type, fields = {}, mapTarget = (target) => target) {
    const targets = eventTargets().map(mapTarget);
    if (!targets.length) return false;
    dispatch({ type, targets, ...fields });
    return true;
  }

  function selectAllEvents() {
    const targets = allEventSelectionTargets(update);
    if (!targets.length) return false;
    dispatch({ type: "setSelection", targets });
    return true;
  }

  function groupedEventTargets(count) {
    const grouped = new Map();
    for (const target of eventTargets()) {
      const ids = grouped.get(target.voiceTrackId) ?? [];
      ids.push(target.eventId);
      grouped.set(target.voiceTrackId, ids);
    }
    return [...grouped].map(([voiceTrackId, eventIds]) => ({
      voiceTrackId,
      eventIds,
      ...(count == null ? {} : { count }),
    }));
  }

  function selectionIsOnlyRests() {
    const targets = eventTargets();
    return targets.length > 0 && targets.every((target) => isRestTarget(update, target));
  }

  function insertionStart() {
    return {
      measure: Math.max(1, Number(input.insertMeasure) || 1),
      beat: quarterBeatFraction(input.insertBeat),
    };
  }

  function insertionDecoration() {
    return {
      ...(Number(input.tupletCount) > 1 ? { tupletCount: Number(input.tupletCount) } : {}),
      ...(input.graceMode ? {
        grace: {
          totalDuration: { base: input.graceDuration },
          stealFrom: input.graceTimeSource,
          noteType: input.graceNoteType,
        },
      } : {}),
      ...(input.insertionBeaming ? { beaming: input.insertionBeaming } : {}),
    };
  }

  /** pitchFields are intent fields: either { pitch } or { midiNote, preferSharps }. */
  function insertEvent(isRest, pitchFields = null) {
    const voiceTrackId = Object.keys(update?.score.voiceTracks ?? {})[0];
    if (!voiceTrackId) return false;
    dispatch({
      type: "insertNote",
      voiceTrackId,
      start: insertionStart(),
      duration: { base: input.insertDuration, dots: Number(input.insertDots) || 0 },
      ...(isRest ? { pitch: null } : pitchFields ?? {
        pitch: { diatonicSteps: Number(input.insertPitch) || 0 },
      }),
      isRest,
      ...insertionDecoration(),
    });
    return true;
  }

  function insertEventAtPointer(target, insertion) {
    if (!target?.voiceTrackId || !target?.start) return false;
    dispatch({
      type: "insertNote",
      voiceTrackId: target.voiceTrackId,
      staffTrackId: target.staffTrackId,
      voiceNumber: target.voiceNumber ?? 1,
      start: target.start,
      duration: insertion.duration,
      pitch: insertion.isRest ? null : target.pitch,
      isRest: insertion.isRest,
      ...(target.smallNoteAppendStartEventId
        ? { smallNoteAppendStartEventId: target.smallNoteAppendStartEventId } : {}),
      ...insertion.decoration,
    });
    return true;
  }

  function insertChord() {
    const voiceTrackId = Object.keys(update?.score.voiceTracks ?? {})[0];
    const steps = [...new Set(String(input.insertChordPitches).split(",")
      .map((value) => Number(value.trim())).filter(Number.isInteger))];
    if (!voiceTrackId || !steps.length) return false;
    dispatch({
      type: "insertChord",
      voiceTrackId,
      start: insertionStart(),
      duration: { base: input.insertDuration, dots: Number(input.insertDots) || 0 },
      pitches: steps.map((diatonicSteps) => ({ diatonicSteps })),
      ...insertionDecoration(),
    });
    return true;
  }

  function pasteAtInputPosition() {
    const voiceTrackId = Object.keys(update?.score.voiceTracks ?? {})[0];
    if (!voiceTrackId || !update?.canPaste) return false;
    dispatch({ type: "pasteNotes", voiceTrackId, start: insertionStart() });
    return true;
  }

  function moveSelectionToDestination() {
    const destination = destinations.find((item) => item.value === input.moveDestination);
    if (!destination) return false;
    return editSelection("moveVoices", {}, (target) => ({
      ...target,
      targetVoiceNumber: destination.voiceNumber,
      targetStaffId: destination.staffId,
    }));
  }

  function structureOnset() {
    const measure = Math.max(1, Number(input.insertMeasure) || 1);
    return { measure: measure === 1 ? 0 : measure, beat: { numerator: 0, denominator: 1 } };
  }

  function selectedStructure(type) {
    return update?.selection?.find((target) => target.type === type);
  }

  function targetStaffId() {
    return input.moveDestination?.split("|")[0] || destinations[0]?.staffId;
  }

  function applyClef() {
    const staffTrackId = targetStaffId();
    if (!staffTrackId) return false;
    dispatch({ type: "setClef", staffTrackId, onset: structureOnset(), clef: structure.clefValue });
    return true;
  }

  function applyKeySignature() {
    const [root, mode] = String(structure.keyValue).split("|");
    dispatch({
      type: "setKeySignature",
      onset: structureOnset(),
      keySignature: { root: Number(root), mode },
    });
    return true;
  }

  function applyTimeSignature() {
    dispatch({
      type: "setTimeSignature",
      measureNumber: Math.max(1, Number(input.insertMeasure) || 1),
      timeSignature: {
        numerator: Math.max(1, Number(structure.meterNumerator) || 4),
        denominator: Math.max(1, Number(structure.meterDenominator) || 4),
      },
    });
    return true;
  }

  function setBarline() {
    dispatch({
      type: "setBarline",
      boundaryMeasure: Math.max(0, Number(structure.boundaryMeasure) || 0),
      barlineType: structure.barlineValue,
      repeatCount: Math.max(2, Number(structure.repeatCount) || 2),
    });
    return true;
  }

  function moveSelectedNavigation() {
    const selected = selectedStructure("navigationMark");
    if (!selected) return false;
    dispatch({
      type: "moveNavigationMark",
      boundaryMeasure: selected.boundaryMeasure,
      targetBoundaryMeasure: Math.max(0, Number(structure.targetBoundaryMeasure) || 0),
      mark: selected.mark,
      offset: { dx: 0, dy: 0 },
    });
    return true;
  }

  function deleteSelectedNavigation() {
    const selected = selectedStructure("navigationMark");
    if (!selected) return false;
    dispatch({
      type: "deleteNavigationMark",
      boundaryMeasure: selected.boundaryMeasure,
      mark: selected.mark,
    });
    return true;
  }

  function resizeSelectedVolta(edge) {
    const selected = selectedStructure("voltaEnding");
    if (!selected) return false;
    const target = Math.max(1, Number(structure.targetBoundaryMeasure) || 1);
    if (edge === "second" && selected.numbers?.includes(2)) {
      dispatch({ type: "resizeSecondVolta", startMeasure: selected.startMeasure, endMeasure: target });
      return true;
    }
    if (edge === "first" && selected.numbers?.includes(1)) {
      dispatch({
        type: "resizeFirstVoltaStart",
        startMeasure: selected.startMeasure,
        newStartMeasure: target,
      });
      return true;
    }
    return false;
  }

  function deleteSelectedVolta() {
    const selected = selectedStructure("voltaEnding");
    if (!selected) return false;
    dispatch({
      type: "deleteVolta",
      startMeasure: selected.startMeasure,
      endMeasure: selected.endMeasure,
      numbers: selected.numbers,
    });
    return true;
  }

  function addSlurFromSelection() {
    const targets = eventTargets();
    if (targets.length !== 2 || targets[0].voiceTrackId !== targets[1].voiceTrackId) return false;
    dispatch({ type: "addSlurs", targets: [{
      voiceTrackId: targets[0].voiceTrackId,
      startEventId: targets[0].eventId,
      endEventId: targets[1].eventId,
    }] });
    return true;
  }

  function deleteSelectedSlur() {
    const selected = selectedStructure("slur");
    if (!selected) return false;
    dispatch({ type: "deleteSlurs", slurIds: [selected.slurId] });
    return true;
  }

  function updateSlurGeometry(fields = {}) {
    const selected = selectedStructure("slur");
    if (!selected || !structure.slurDraft) return false;
    dispatch({
      type: "setSlurGeometry",
      slurId: selected.slurId,
      geometry: { ...structure.slurDraft, ...fields },
    });
    return true;
  }

  function expressionTime(measure = input.insertMeasure) {
    return {
      measure: Math.max(1, Number(measure) || 1),
      beat: quarterBeatFraction(input.insertBeat),
    };
  }

  function addPointExpression(type, fields = {}) {
    const staffTrackId = targetStaffId();
    if (!staffTrackId) return false;
    dispatch({ type, staffTrackId, onset: expressionTime(), afterTime: expressionTime(), ...fields });
    return true;
  }

  function addSpanExpression(type, fields = {}) {
    const staffTrackId = targetStaffId();
    if (!staffTrackId) return false;
    dispatch({
      type,
      staffTrackId,
      start: expressionTime(),
      end: expressionTime(expression.expressionEndMeasure),
      ...fields,
    });
    return true;
  }

  function deleteSelectedExpression() {
    const selected = selectedStructure("attachment");
    if (!selected) return false;
    dispatch({ type: "deleteExpressions", ids: [selected.attachmentId] });
    return true;
  }

  function addOrnamentToSelection() {
    const target = eventTargets()[0];
    if (!target) return false;
    const staff = Object.values(update?.score.staffTracks ?? {})
      .find((candidate) => (candidate.voiceTrackIds ?? []).includes(target.voiceTrackId));
    if (!staff) return false;
    dispatch({
      type: "addOrnament",
      staffTrackId: staff.id,
      sourceEventId: target.eventId,
      ornamentKind: expression.ornamentKind,
      anchor: "ON_NOTE",
    });
    return true;
  }

  function updateSelectedAttachment(type, fields) {
    const selected = selectedStructure("attachment");
    if (!selected) return false;
    dispatch({ type, ...fields(selected.attachmentId) });
    return true;
  }

  return {
    editSelection,
    eventTargets,
    groupedEventTargets,
    insertChord,
    insertEvent,
    insertEventAtPointer,
    addOrnamentToSelection,
    addPointExpression,
    addSlurFromSelection,
    addSpanExpression,
    applyClef,
    applyKeySignature,
    applyTimeSignature,
    deleteSelectedExpression,
    deleteSelectedNavigation,
    deleteSelectedSlur,
    deleteSelectedVolta,
    expressionTime,
    moveSelectionToDestination,
    moveSelectedNavigation,
    pasteAtInputPosition,
    resizeSelectedVolta,
    selectAllEvents,
    selectedStructure,
    selectionIsOnlyRests,
    setBarline,
    targetStaffId,
    updateSelectedAttachment,
    updateSlurGeometry,
  };
}
