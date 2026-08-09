/**
 * Platform-only score-editor interaction helpers.
 *
 * These functions translate frozen render geometry and browser pointer coordinates into stable
 * score-editing intent targets. They never mutate StorageScore or implement music rules.
 */
export function resolveEventTargets(update, selection = update?.selection ?? []) {
  if (!update?.score) return [];
  const voices = Object.entries(update.score.voiceTracks ?? {});
  const resolved = new Map();
  for (const target of selection) {
    // Untyped entries are intent targets this module builds itself; only foreign kinds are skipped.
    if (target.type && target.type !== "event") continue;
    if (!target.eventId) continue;
    const voiceTrackId = target.voiceTrackId
      ?? voices.find(([, voice]) => (voice.events ?? []).some((event) => event.id === target.eventId))?.[0];
    if (!voiceTrackId) continue;
    const key = `${voiceTrackId}\u0000${target.eventId}`;
    const previous = resolved.get(key);
    const pitchIndices = target.pitchIndices?.length ? [...new Set(target.pitchIndices)].sort((a, b) => a - b) : null;
    if (previous) {
      if (!previous.pitchIndices || !pitchIndices) delete previous.pitchIndices;
      else previous.pitchIndices = [...new Set([...previous.pitchIndices, ...pitchIndices])].sort((a, b) => a - b);
      continue;
    }
    resolved.set(key, {
      voiceTrackId,
      eventId: target.eventId,
      ...(pitchIndices ? { pitchIndices } : {}),
    });
  }
  return [...resolved.values()];
}

export function allEventSelectionTargets(update) {
  if (!update?.score) return [];
  return Object.entries(update.score.voiceTracks ?? {}).flatMap(([voiceTrackId, voice]) =>
    (voice.events ?? []).map((event) => ({
      type: "event",
      voiceTrackId,
      eventId: event.id,
    })),
  );
}

export function isRestTarget(update, target) {
  const voice = update?.score?.voiceTracks?.[target?.voiceTrackId];
  const event = voice?.events?.find((candidate) => candidate.id === target?.eventId);
  const pitchTrack = voice ? update?.score?.pitchTracks?.[voice.pitchTrackId] : null;
  const pitchEvent = pitchTrack?.events?.find((candidate) => candidate.id === event?.pitchEventId);
  return Array.isArray(pitchEvent?.pitches) && pitchEvent.pitches.length === 0;
}

export function staffDragMetrics(surface, element) {
  const elementCenterY = boxCenterY(element?.hitBox);
  const staffs = (surface?.elements ?? []).filter((candidate) =>
    candidate.type === "STAFF" &&
    (candidate.systemIndex == null || element?.systemIndex == null || candidate.systemIndex === element.systemIndex) &&
    (candidate.staffIndex == null || element?.staffIndex == null || candidate.staffIndex === element.staffIndex),
  ).map((candidate) => ({
    candidate,
    distance: Math.abs(boxCenterY(candidate.hitBox) - elementCenterY),
  })).sort((a, b) => a.distance - b.distance);
  const commandLines = (staffs[0]?.candidate.commands ?? []).filter((command) =>
    String(command?.type ?? command?.kind ?? "").split(".").at(-1) === "DrawLine",
  ).map((command) => Number(command?.start?.y?.value ?? command?.start?.y ?? 0));
  const legacyLines = (surface?.elements ?? []).filter((candidate) =>
    candidate.type === "STAFF_LINE" &&
    candidate.staffIndex === element?.staffIndex &&
    candidate.systemIndex === element?.systemIndex,
  ).map((candidate) => boxCenterY(candidate.hitBox));
  const lines = (commandLines.length >= 2 ? commandLines : legacyLines).sort((a, b) => a - b);
  const gaps = lines.slice(1).map((line, index) => line - lines[index]).filter((gap) => gap > 0);
  if (!gaps.length) return null;
  gaps.sort((a, b) => a - b);
  const staffSpace = gaps[Math.floor(gaps.length / 2)];
  return { halfSpace: staffSpace / 2, centerY: lines.reduce((sum, line) => sum + line, 0) / lines.length };
}

function boxCenterY(box) {
  return Number(box?.origin?.y?.value ?? box?.origin?.y ?? 0) +
    Number(box?.height?.value ?? box?.height ?? 0) / 2;
}

export function dragStepDelta(startY, currentY, halfSpace) {
  if (!(halfSpace > 0)) return 0;
  return -Math.round((currentY - startY) / halfSpace);
}

export function restPositionForElement(element, metrics) {
  const metadataPosition = Number(element?.metadata?.staffPosition);
  if (Number.isInteger(metadataPosition)) return metadataPosition;
  if (!metrics) return 0;
  const box = element?.hitBox;
  const centerY = Number(box?.origin?.y?.value ?? box?.origin?.y ?? 0) +
    Number(box?.height?.value ?? box?.height ?? 0) / 2;
  return Math.round((metrics.centerY - centerY) / metrics.halfSpace);
}

export function previewOffsets(elementIds, stepDelta, halfSpace) {
  const y = -stepDelta * halfSpace;
  return Object.fromEntries((elementIds ?? []).map((id) => [String(id), { x: 0, y }]));
}

export function beamDragGeometry(geometry, endpoint, deltaY, staffSpace) {
  if (!geometry || !(staffSpace > 0)) return geometry;
  const dy = deltaY / staffSpace;
  return {
    ...geometry,
    startDy: Number(geometry.startDy ?? 0) + (endpoint === "end" ? 0 : dy),
    endDy: Number(geometry.endDy ?? 0) + (endpoint === "start" ? 0 : dy),
    manuallyAdjusted: true,
  };
}

export function curveDragGeometry(geometry, endpoint, deltaX, deltaY, staffSpace) {
  if (!geometry || !(staffSpace > 0) || !["start", "end"].includes(endpoint)) return geometry;
  const dx = deltaX / staffSpace;
  const dy = deltaY / staffSpace;
  return {
    ...geometry,
    ...(endpoint === "start" ? {
      startDx: Number(geometry.startDx ?? 0) + dx,
      startDy: Number(geometry.startDy ?? 0) + dy,
    } : {
      endDx: Number(geometry.endDx ?? 0) + dx,
      endDy: Number(geometry.endDy ?? 0) + dy,
    }),
    directionOnly: false,
    autoEndpoints: false,
    manuallyAdjusted: true,
  };
}

export function attachmentDragGeometry(geometry, endpoint, deltaX, deltaY, staffSpace) {
  if (!geometry || !(staffSpace > 0)) return geometry;
  const dx = deltaX / staffSpace;
  const dy = deltaY / staffSpace;
  if (endpoint === "start") return {
    ...geometry,
    startDx: Number(geometry.startDx ?? 0) + dx,
    startDy: Number(geometry.startDy ?? 0) + dy,
    manuallyAdjustedY: true,
  };
  if (endpoint === "end" && geometry.endDx != null && geometry.endDy != null) return {
    ...geometry,
    endDx: Number(geometry.endDx) + dx,
    endDy: Number(geometry.endDy) + dy,
    manuallyAdjustedY: true,
  };
  return {
    ...geometry,
    startDx: Number(geometry.startDx ?? 0) + dx,
    startDy: Number(geometry.startDy ?? 0) + dy,
    ...(geometry.endDx == null ? {} : { endDx: Number(geometry.endDx) + dx }),
    ...(geometry.endDy == null ? {} : { endDy: Number(geometry.endDy) + dy }),
    manuallyAdjustedY: true,
  };
}

export function dragEndpoint(element, x, hasStart = true, hasEnd = true) {
  const originX = Number(element?.hitBox?.origin?.x?.value ?? element?.hitBox?.origin?.x ?? 0);
  const width = Number(element?.hitBox?.width?.value ?? element?.hitBox?.width ?? 0);
  if (!(width > 0)) return null;
  const relativeX = (x - originX) / width;
  if (hasStart && relativeX <= 0.25) return "start";
  if (hasEnd && relativeX >= 0.75) return "end";
  return null;
}

function fractionValue(value) {
  if (value == null) return 0;
  const numerator = Number(value.numerator ?? value.value?.numerator ?? 0);
  const denominator = Number(value.denominator ?? value.value?.denominator ?? 1);
  return denominator === 0 ? 0 : numerator / denominator;
}

/**
 * Orders two wire time codes so a drag can grey out candidates that would invert a span's endpoints.
 * This is interaction filtering only — the session revalidates every committed span, so this must
 * never become the sole check.
 */
export function compareTimeCodes(left, right) {
  const leftParts = [Number(left?.measure ?? 0), fractionValue(left?.beat), fractionValue(left?.grace)];
  const rightParts = [Number(right?.measure ?? 0), fractionValue(right?.beat), fractionValue(right?.grace)];
  for (let index = 0; index < leftParts.length; index++) {
    if (leftParts[index] !== rightParts[index]) return leftParts[index] < rightParts[index] ? -1 : 1;
  }
  return 0;
}

function surfaceOrigin(bundle, surface) {
  if (bundle?.paginated) return { x: 0, y: Number(surface?.contentOffsetY ?? 0) };
  return {
    x: Number(bundle?.bounds?.origin?.x?.value ?? bundle?.bounds?.origin?.x ?? 0),
    y: Number(bundle?.bounds?.origin?.y?.value ?? bundle?.bounds?.origin?.y ?? 0),
  };
}

/** Convert a surface-local browser point into the renderer's global coordinate space. */
export function surfacePointToGlobal(bundle, surface, x, y) {
  const origin = surfaceOrigin(bundle, surface);
  return { x: Number(x) + origin.x, y: Number(y) + origin.y };
}

export function nearestTimePosition(bundle, surface, x, staffAnchorY, predicate = () => true) {
  const origin = surfaceOrigin(bundle, surface);
  const globalY = staffAnchorY + origin.y;
  const candidates = (bundle?.timePositions ?? []).filter((position) => {
    const top = Number(position?.topY?.value ?? position?.topY ?? Number.NEGATIVE_INFINITY);
    const bottom = Number(position?.bottomY?.value ?? position?.bottomY ?? Number.POSITIVE_INFINITY);
    return globalY >= Math.min(top, bottom) && globalY <= Math.max(top, bottom) &&
      position.timeCode && predicate(position.timeCode);
  });
  const match = candidates.map((position) => ({
    timeCode: position.timeCode,
    x: Number(position.x?.value ?? position.x ?? 0) - origin.x,
    distance: Math.abs(Number(position.x?.value ?? position.x ?? 0) - origin.x - x),
  })).sort((left, right) => left.distance - right.distance)[0];
  return match ? { timeCode: match.timeCode, x: match.x } : null;
}

/**
 * Parses the manual "quarter beat" field into an exact wire fraction of a whole note.
 *
 * Accepts `"1.5"` and `"7/3"`; the fraction form exists because tuplet onsets such as 1/3 of a beat
 * have no finite decimal form. Note spelling, duration arithmetic and cursor advancement all live in
 * the shared session — this only converts what the user literally typed.
 */
export function quarterBeatFraction(quarterBeat) {
  const text = String(quarterBeat ?? "").trim();
  const ratio = /^(\d+)\s*\/\s*(\d+)$/.exec(text);
  const [quarters, quarterDivisor] = ratio
    ? [Number(ratio[1]), Number(ratio[2])]
    : [Math.max(0, Math.round((Number(text) || 0) * 1000)), 1000];
  if (!(quarterDivisor > 0)) return { numerator: 0, denominator: 1 };
  return reduceFraction(quarters, quarterDivisor * 4);
}

/**
 * Renders a wire beat (fraction of a whole note) back into the manual quarter-beat field without
 * losing precision: tuplet positions such as 7/12 of a whole note become "7/3" rather than a
 * truncated decimal, so re-submitting the field reproduces the exact same onset.
 */
export function formatQuarterBeat(beat) {
  if (!beat) return "0";
  const { numerator, denominator } = reduceFraction(
    Number(beat.numerator ?? 0) * 4,
    Number(beat.denominator ?? 1),
  );
  if (denominator === 1) return String(numerator);
  const decimal = numerator / denominator;
  if (Math.abs(Math.round(decimal * 1000) / 1000 - decimal) < Number.EPSILON) return String(decimal);
  return `${numerator}/${denominator}`;
}

function reduceFraction(numerator, denominator) {
  let left = numerator;
  let right = denominator;
  while (right !== 0) [left, right] = [right, left % right];
  const divisor = Math.max(1, Math.abs(left));
  return { numerator: numerator / divisor, denominator: denominator / divisor };
}

export function articulationDragGeometry(geometry, index, deltaX, deltaY, staffSpace) {
  if (!geometry || !(staffSpace > 0)) return geometry;
  return {
    ...geometry,
    marks: (geometry.marks ?? []).map((mark) => mark.index === index ? {
      ...mark,
      dx: Number(mark.dx ?? 0) + deltaX / staffSpace,
      dy: Number(mark.dy ?? 0) + deltaY / staffSpace,
    } : mark),
  };
}

export function marqueeSelection(update, elements, startX, startY, endX, endY) {
  const left = Math.min(startX, endX);
  const right = Math.max(startX, endX);
  const top = Math.min(startY, endY);
  const bottom = Math.max(startY, endY);
  const selected = [];
  const seen = new Set();
  for (const element of elements ?? []) {
    if (!["NOTEHEAD", "REST"].includes(element.type)) continue;
    const box = element.hitBox ?? {};
    const x = Number(box.origin?.x?.value ?? box.origin?.x ?? 0);
    const y = Number(box.origin?.y?.value ?? box.origin?.y ?? 0);
    const width = Number(box.width?.value ?? box.width ?? 0);
    const height = Number(box.height?.value ?? box.height ?? 0);
    if (x + width < left || x > right || y + height < top || y > bottom) continue;
    const target = selectionTargetForElement(update, element);
    if (!target) continue;
    const key = `${target.voiceTrackId}\u0000${target.eventId}\u0000${(target.pitchIndices ?? []).join(",")}`;
    if (!seen.has(key)) {
      seen.add(key);
      selected.push(target);
    }
  }
  return selected;
}

export function nearestBoundary(
  surface, x, predicate = () => true, rowY = null, systemIndex = null, bundle = null,
) {
  const origin = surfaceOrigin(bundle, surface);
  let candidates = (surface?.elements ?? []).filter((element) =>
    element.type === "BARLINE" && Number.isInteger(element.measureNumber) && predicate(element.measureNumber) &&
      (systemIndex == null || element.systemIndex === systemIndex),
  ).map((element) => ({
    boundaryMeasure: element.measureNumber,
    x: Number(element.hitBox?.origin?.x?.value ?? element.hitBox?.origin?.x ?? 0) +
      Number(element.hitBox?.width?.value ?? element.hitBox?.width ?? 0) / 2 - origin.x,
    y: boxCenterY(element.hitBox) - origin.y,
  }));
  if (Number.isFinite(rowY) && candidates.length) {
    const nearestRowDistance = Math.min(...candidates.map((candidate) => Math.abs(candidate.y - rowY)));
    candidates = candidates.filter((candidate) => Math.abs(candidate.y - rowY) <= nearestRowDistance + 1);
  }
  const match = candidates.sort((left, right) => Math.abs(left.x - x) - Math.abs(right.x - x))[0];
  return match ? { boundaryMeasure: match.boundaryMeasure, x: match.x } : null;
}

export function staffCoreAtPointer(surface, y, staffIndex = null, bundle = null) {
  const origin = surfaceOrigin(bundle, surface);
  const candidates = (surface?.elements ?? []).filter((element) =>
    element.type === "STAFF" && (staffIndex == null || element.staffIndex === staffIndex),
  ).map((element) => {
    const lines = (element.commands ?? []).filter((command) =>
      String(command?.type ?? command?.kind ?? "").split(".").at(-1) === "DrawLine",
    ).map((command) => Number(command?.start?.y?.value ?? command?.start?.y ?? 0)).sort((a, b) => a - b);
    if (lines.length < 2) return null;
    return {
      systemIndex: element.systemIndex,
      staffIndex: element.staffIndex,
      anchorY: lines.reduce((sum, line) => sum + line, 0) / lines.length - origin.y,
      top: lines[0] - origin.y,
      bottom: lines.at(-1) - origin.y,
    };
  }).filter(Boolean);
  return candidates.find((candidate) => y >= candidate.top && y <= candidate.bottom) ?? null;
}

export function staffAnchor(surface, systemIndex, staffIndex, bundle = null) {
  const staff = (surface?.elements ?? []).find((element) =>
    element.type === "STAFF" && element.systemIndex === systemIndex && element.staffIndex === staffIndex,
  );
  if (!staff) return null;
  const metrics = staffDragMetrics(surface, staff);
  const origin = surfaceOrigin(bundle, surface);
  return metrics ? { x: 0, y: metrics.centerY - origin.y } : null;
}

export function navigationDragOffset(existing, deltaX, deltaY, sourceAnchor, targetAnchor, staffSpace) {
  if (!(staffSpace > 0) || !sourceAnchor || !targetAnchor) return existing ?? { dx: 0, dy: 0 };
  return {
    dx: Number(existing?.dx ?? 0) + (deltaX - (targetAnchor.x - sourceAnchor.x)) / staffSpace,
    dy: Number(existing?.dy ?? 0) + (deltaY - (targetAnchor.y - sourceAnchor.y)) / staffSpace,
  };
}

export function attachmentDragSource(update, frameGeometry, element) {
  const id = element?.eventId;
  const geometry = id ? frameGeometry?.attachments?.[id] : null;
  if (!id || !geometry) return null;
  const attachments = Object.values(update?.score?.staffTracks ?? {})
    .flatMap((staff) => staff.attachments ?? []);
  const attachment = attachments.find((candidate) => candidate.id === id);
  if (!attachment?.onset) return null;
  let end = attachment.endOnset ?? null;
  if (!end && attachment.endEventId) {
    end = attachments.find((candidate) => candidate.id === attachment.endEventId)?.onset ?? null;
  }
  return { attachmentId: id, start: attachment.onset, end, geometry };
}

export function selectedElementIds(update, elements) {
  const eventTargets = resolveEventTargets(update);
  const structural = update?.selection ?? [];
  return (elements ?? []).filter((element) => {
    if (structural.some((target) => {
      switch (target.type) {
        case "tie":
          return element.type === "TIE" && target.sourceEventId === element.eventId &&
            target.sourcePitchIndex === Number(element.metadata?.sourcePitchIndex);
        case "beam":
          return element.type === "BEAM" && target.groupId === element.metadata?.groupId;
        case "articulation":
          return element.type === "ARTICULATION" && target.eventId === element.eventId &&
            target.articulationIndex === Number(element.metadata?.articulationIndex);
        case "attachment":
          return target.attachmentId === element.eventId;
        case "slur":
          return element.type === "SLUR" && target.slurId === element.metadata?.slurId;
        default:
          return false;
      }
    })) return true;
    return eventTargets.some((target) => {
      if (target.eventId !== element.eventId) return false;
      if (!target.pitchIndices) return true;
      if (!["NOTEHEAD", "ACCIDENTAL", "DOT"].includes(element.type)) return false;
      const pitchIndex = Number(element.metadata?.pitchIndex);
      return Number.isInteger(pitchIndex) && target.pitchIndices.includes(pitchIndex);
    });
  }).map((element) => String(element.id));
}

/**
 * Identity of a selection target for additive-click comparison, keyed by the fields that actually
 * identify the element. Derived extras the session echoes back (a barline's `onset`, a tie's
 * `targetEventId`) are deliberately excluded so a target built from a rendered element still matches
 * the equivalent target the session returned.
 */
export function selectionIdentity(target) {
  const parts = (() => {
    switch (target?.type) {
      case "event":
        return [target.voiceTrackId, target.eventId,
          [...(target.pitchIndices ?? [])].sort((a, b) => a - b).join(",")];
      case "slur": return [target.slurId];
      case "tie": return [target.sourceEventId, target.sourcePitchIndex];
      case "beam": return [target.groupId];
      case "articulation": return [target.eventId, target.articulationIndex];
      case "attachment": return [target.attachmentId];
      case "barline": return [target.boundaryMeasure];
      case "voltaEnding": return [target.startMeasure, target.endMeasure];
      case "navigationMark": return [target.boundaryMeasure, target.mark];
      case "clef":
      case "keySignature":
      case "timeSignature": return [target.staffTrackId, JSON.stringify(target.onset ?? null)];
      case "layoutBreak": return [target.beforeMeasure];
      case "staffVisibility": return [target.staffTrackId, target.startMeasure, target.endMeasure];
      default: return [target?.eventId];
    }
  })();
  return [target?.type, ...parts].join(" ");
}

export function selectionTargetForElement(update, element) {
  if (!element) return null;
  if (element.type === "SLUR") {
    const slurId = element.metadata?.slurId;
    const startEventId = element.metadata?.startEventId;
    const endEventId = element.metadata?.endEventId;
    const voiceTrackId = element.trackId;
    if (slurId && startEventId && endEventId && voiceTrackId) {
      return { type: "slur", slurId, voiceTrackId, startEventId, endEventId };
    }
  }
  if (element.type === "TIE" && element.eventId) {
    const sourcePitchIndex = Number(element.metadata?.sourcePitchIndex);
    if (Number.isInteger(sourcePitchIndex)) {
      const voiceTrackId = resolveEventTargets(update, [{ eventId: element.eventId }])[0]?.voiceTrackId;
      if (voiceTrackId) return {
        type: "tie",
        sourceEventId: element.eventId,
        voiceTrackId,
        sourcePitchIndex,
        ...(element.metadata?.targetEventId ? { targetEventId: element.metadata.targetEventId } : {}),
      };
    }
  }
  if (element.type === "BEAM" && element.metadata?.groupId) {
    return { type: "beam", groupId: element.metadata.groupId };
  }
  if (element.type === "ARTICULATION" && element.eventId) {
    const articulationIndex = Number(element.metadata?.articulationIndex);
    const voiceTrackId = resolveEventTargets(update, [{ eventId: element.eventId }])[0]?.voiceTrackId;
    if (voiceTrackId && Number.isInteger(articulationIndex)) return {
      type: "articulation", eventId: element.eventId, voiceTrackId, articulationIndex,
    };
    if (element.trackId) {
      return { type: "attachment", attachmentId: element.eventId, staffTrackId: element.trackId };
    }
  }
  if ([
    "ORNAMENT", "DYNAMIC", "FERMATA", "HAIRPIN", "OCTAVE_SHIFT",
    "TEMPO_MARKING", "TEXT_ANNOTATION", "REHEARSAL_MARK", "PEDAL",
  ].includes(element.type) && element.eventId) {
    return { type: "attachment", attachmentId: element.eventId, staffTrackId: element.trackId };
  }
  if (element.type === "BARLINE" && Number.isInteger(element.measureNumber)) {
    return { type: "barline", boundaryMeasure: element.measureNumber };
  }
  if (element.type === "VOLTA_ENDING") {
    const start = Number(element.metadata?.voltaStartMeasure);
    const end = Number(element.metadata?.voltaEndMeasure);
    const numbers = String(element.metadata?.voltaNumbers ?? "")
      .split(",").map(Number).filter(Number.isInteger);
    if (Number.isInteger(start) && Number.isInteger(end) && numbers.length) {
      return { type: "voltaEnding", startMeasure: start, endMeasure: end, numbers };
    }
  }
  if (element.type === "NAVIGATION_MARK" && Number.isInteger(element.measureNumber)) {
    const navigationMark = element.metadata?.navigationMark;
    if (navigationMark) {
      return { type: "navigationMark", boundaryMeasure: element.measureNumber, mark: navigationMark };
    }
  }
  if (element.eventId) {
    const target = resolveEventTargets(update, [{ eventId: element.eventId }])[0] ?? null;
    if (!target) return null;
    const pitchIndex = Number(element.metadata?.pitchIndex);
    if (["NOTEHEAD", "ACCIDENTAL", "DOT"].includes(element.type) && Number.isInteger(pitchIndex)) {
      return { type: "event", ...target, pitchIndices: [pitchIndex] };
    }
    return { type: "event", ...target };
  }
  return null;
}
