function fractionValue(value) {
  const denominator = Number(value?.denominator ?? 1);
  return denominator === 0 ? 0 : Number(value?.numerator ?? 0) / denominator;
}

function sameTimeCode(left, right) {
  return Number(left?.measure ?? 0) === Number(right?.measure ?? 0)
    && fractionValue(left?.beat) === fractionValue(right?.beat);
}

function eventForTarget(score, target) {
  const voices = score?.voiceTracks ?? {};
  const owned = target.voiceTrackId == null ? null : voices[target.voiceTrackId];
  return owned?.events?.find((event) => event.id === target.eventId)
    ?? Object.values(voices).flatMap((voice) => voice.events ?? [])
      .find((event) => event.id === target.eventId)
    ?? null;
}

/**
 * Resolve the selected score notes to the continuous harmony-slot window they touch.
 * Event ids are used only to recover stable musical onsets; the worker still receives slot ids.
 */
export function writingSlotIdsForScoreSelection(frame, update) {
  const slots = update?.timeline?.slots ?? [];
  const targets = update?.selection?.scoreTargets ?? [];
  if (!slots.length || !targets.length) return [];

  const score = update?.score?.score;
  const anchors = frame?.playbackAnchors?.length
    ? frame.playbackAnchors
    : frame?.timeAxis?.anchors ?? [];
  const selectedTimes = targets.flatMap((target) => {
    if (target?.type !== "event") return [];
    const event = eventForTarget(score, target);
    const anchor = anchors.find((candidate) => sameTimeCode(candidate.scoreTime, event?.onset));
    return anchor?.time == null ? [] : [fractionValue(anchor.time)];
  });
  if (!selectedTimes.length) return [];

  const start = Math.min(...selectedTimes);
  const end = Math.max(...selectedTimes);
  return slots.filter((slot) => {
    const slotStart = fractionValue(slot.onset);
    const slotEnd = slotStart + fractionValue(slot.duration);
    return slotStart <= end && slotEnd > start;
  }).map((slot) => slot.id);
}
