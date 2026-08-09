const IDLE_PLAYBACK = Object.freeze({ state: "idle", time: null, tick: null });

function fractionValue(value) {
  const denominator = Number(value?.denominator ?? 1);
  return denominator === 0 ? 0 : Number(value?.numerator ?? 0) / denominator;
}

function sameFraction(left, right) {
  return fractionValue(left) === fractionValue(right);
}

function sameTimeCode(left, right) {
  return Number(left?.measure ?? 0) === Number(right?.measure ?? 0)
    && sameFraction(left?.beat, right?.beat);
}

function addFractions(left, right) {
  return {
    numerator: Number(left?.numerator ?? 0) * Number(right?.denominator ?? 1)
      + Number(right?.numerator ?? 0) * Number(left?.denominator ?? 1),
    denominator: Number(left?.denominator ?? 1) * Number(right?.denominator ?? 1),
  };
}

/** A tiny external store so only the score surface, rather than its whole host, updates per frame. */
export function createPlaybackCursorStore() {
  let snapshot = IDLE_PLAYBACK;
  const listeners = new Set();
  return Object.freeze({
    getSnapshot: () => snapshot,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    set(next) {
      snapshot = (next?.state === "playing" || next?.state === "paused") && next.time
        ? Object.freeze({
          state: next.state,
          time: next.time,
          tick: next.tick != null && Number.isFinite(Number(next.tick)) ? Number(next.tick) : null,
        })
        : IDLE_PLAYBACK;
      for (const listener of listeners) listener();
    },
  });
}

/** Resolve the exact renderer anchor selected by the current audio cursor. */
export function playbackAnchorForCursor(frame, absoluteTime, playbackTick = null) {
  const anchors = frame?.playbackAnchors?.length
    ? frame.playbackAnchors
    : frame?.timeAxis?.anchors ?? [];
  if (!absoluteTime || !anchors.length) return null;
  const useTicks = playbackTick != null && Number.isFinite(Number(playbackTick))
    && anchors.every((anchor) => Number.isFinite(Number(anchor.tick)));
  const target = useTicks ? Number(playbackTick) : fractionValue(absoluteTime);
  let leftIndex = 0;
  while (leftIndex + 1 < anchors.length && (
    useTicks ? Number(anchors[leftIndex + 1].tick) : fractionValue(anchors[leftIndex + 1].time)
  ) <= target) leftIndex++;
  return anchors[leftIndex];
}

/** Resolve an absolute score time using the same preceding-anchor step semantics as Desktop. */
export function scorePlayheadGeometry(frame, absoluteTime, surfaceIndex = 0, playbackTick = null) {
  const positions = frame?.bundle?.timePositions ?? [];
  const surfaces = frame?.bundle?.surfaces ?? [];
  if (!positions.length || !surfaces.length) return null;
  const left = playbackAnchorForCursor(frame, absoluteTime, playbackTick);
  if (!left) return null;
  const position = positions.find((item) => sameTimeCode(item.timeCode, left.scoreTime))
    ?? null;
  if (!position) return null;

  const surface = surfaces.find((item) => item.index === surfaceIndex) ?? surfaces[surfaceIndex];
  if (!surface) return null;
  const paginated = Boolean(frame.bundle.paginated);
  const boundsOriginX = frame.bundle.bounds?.origin?.x ?? frame.bundle.bounds?.x ?? 0;
  const boundsOriginY = frame.bundle.bounds?.origin?.y ?? frame.bundle.bounds?.y ?? 0;
  const originX = paginated ? 0 : Number(boundsOriginX?.value ?? boundsOriginX);
  const originY = paginated
    ? Number(surface.contentOffsetY ?? 0)
    : Number(boundsOriginY?.value ?? boundsOriginY);
  const x = Number(position.x) - originX;
  const top = Number(position.topY) - originY;
  const bottom = Number(position.bottomY) - originY;
  if (top < 0 || bottom > Number(surface.height) || bottom <= top) return null;
  return Object.freeze({ x, top, height: bottom - top });
}

/** Build a seek range from the unified score/timeline selection, preferring selected score events. */
export function playbackRangeForSelection(frame, update, tempoBpm) {
  const slots = update?.timeline?.slots ?? [];
  if (!slots.length) return null;
  const score = update?.score?.score;
  const anchors = frame?.playbackAnchors?.length
    ? frame.playbackAnchors
    : frame?.timeAxis?.anchors ?? [];
  const targets = update?.selection?.scoreTargets ?? [];
  const selectedEventTimes = targets.map((target) => {
    if (target?.type !== "event") return null;
    const voice = score?.voiceTracks?.[target.voiceTrackId];
    const event = voice?.events?.find((item) => item.id === target.eventId);
    return anchors.find((anchor) => sameTimeCode(anchor.scoreTime, event?.onset))?.time ?? null;
  }).filter(Boolean);
  const selectedSlotId = update?.selection?.slotId ?? update?.selectedSlotId;
  const selectedSlot = slots.find((slot) => slot.id === selectedSlotId);
  const start = selectedEventTimes.reduce((earliest, time) => (
    earliest == null || fractionValue(time) < fractionValue(earliest) ? time : earliest
  ), null) ?? selectedSlot?.onset;
  if (!start) return null;
  const startValue = fractionValue(start);
  const firstSlotIndex = slots.findIndex((slot) => {
    const onset = fractionValue(slot.onset);
    return onset <= startValue && fractionValue(addFractions(slot.onset, slot.duration)) > startValue;
  });
  const fallbackIndex = slots.findIndex((slot) => fractionValue(slot.onset) >= startValue);
  const resolvedIndex = firstSlotIndex >= 0 ? firstSlotIndex : fallbackIndex;
  if (resolvedIndex < 0) return null;
  const last = slots.at(-1);
  return {
    firstSlotId: slots[resolvedIndex].id,
    lastSlotId: last.id,
    start,
    end: update.timeline.end ?? addFractions(last.onset, last.duration),
    tempoBpm,
  };
}

export function interpolateScoreTime(start, end, progress) {
  const bounded = Math.max(0, Math.min(1, Number(progress) || 0));
  const value = fractionValue(start) + (fractionValue(end) - fractionValue(start)) * bounded;
  return { numerator: Math.round(value * 1_000_000), denominator: 1_000_000 };
}

/** Convert the AudioContext-relative excerpt offset back to its exact expanded MIDI tick. */
export function playbackTickAtOffset(excerpt, offsetSeconds) {
  const startTick = Number(excerpt?.startTick);
  const endTick = Number(excerpt?.endTick);
  const secondsPerTick = Number(excerpt?.secondsPerTick);
  if (!Number.isFinite(startTick) || !Number.isFinite(endTick)
    || !Number.isFinite(secondsPerTick) || secondsPerTick <= 0) return null;
  return Math.min(endTick, Math.max(startTick, startTick + Number(offsetSeconds) / secondsPerTick));
}

/** Rewind a paused transport to the current note onset so resuming never emits a clipped note. */
export function completeNoteResumeOffset(notes, currentOffsetSeconds) {
  const current = Math.max(0, Number(currentOffsetSeconds) || 0);
  return (notes ?? []).reduce((latest, note) => {
    const start = Number(note?.startSeconds);
    return Number.isFinite(start) && start <= current && start > latest ? start : latest;
  }, 0);
}
