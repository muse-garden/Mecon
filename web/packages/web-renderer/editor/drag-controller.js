import { hitTest } from "@mecon/frozen-score";
import {
  articulationDragGeometry,
  attachmentDragGeometry,
  attachmentDragSource,
  beamDragGeometry,
  compareTimeCodes,
  curveDragGeometry,
  dragEndpoint,
  dragStepDelta,
  marqueeSelection,
  navigationDragOffset,
  nearestBoundary,
  nearestTimePosition,
  restPositionForElement,
  resolveEventTargets,
  selectedElementIds,
  selectionTargetForElement,
  staffAnchor,
  staffCoreAtPointer,
  staffDragMetrics,
  surfacePointToGlobal,
} from "./interaction.js";

const DRAGGABLE_TYPES = [
  "NOTEHEAD", "REST", "ARTICULATION", "SLUR", "TIE", "BEAM", "NAVIGATION_MARK",
  "VOLTA_ENDING", "DYNAMIC", "HAIRPIN", "OCTAVE_SHIFT", "ORNAMENT",
];

/** Complete pointer-drag adapter. It emits intents but never mutates score state. */
export function createScoreEditorDragController({
  frame, surfaceIndex, canvasPoint, dragRef, suppressClickRef, setDragPreview,
  requestTransposePreview, requestRestMovePreview, dispatch, tool = "select",
}) {
  function onPointerDown(event) {
    if (!frame || event.button !== 0) return;
    if (tool === "note") return;
    const point = canvasPoint(event);
    if (!point) return;
    const { surface, x, y } = point;
    const element = hitTest(frame.bundle, surfaceIndex, x, y, { padding: 4, types: DRAGGABLE_TYPES });
    const target = selectionTargetForElement(frame.update, element);
    const metrics = staffDragMetrics(surface, element);
    if (!element || tool === "marquee") {
      dragRef.current = {
        mode: "MARQUEE", pointerId: event.pointerId, startX: x, startY: y,
        targets: [], elementIds: [], changed: false,
      };
      event.currentTarget.setPointerCapture(event.pointerId);
      return;
    }
    if (!target) return;
    if (!["SLUR", "NAVIGATION_MARK", "VOLTA_ENDING"].includes(element.type) && !metrics) return;
    if (element.type === "BEAM") {
      const geometry = frame.geometry?.beams?.[target.groupId];
      if (!geometry || !metrics) return;
      const originX = Number(element.hitBox?.origin?.x?.value ?? element.hitBox?.origin?.x ?? 0);
      const width = Number(element.hitBox?.width?.value ?? element.hitBox?.width ?? 0);
      const relativeX = width > 0 ? (x - originX) / width : 0.5;
      const endpoint = relativeX <= 0.25 ? "start" : relativeX >= 0.75 ? "end" : null;
      const elementIds = surface.elements.filter((candidate) =>
        candidate.type === "BEAM" && candidate.metadata?.groupId === target.groupId,
      ).map((candidate) => String(candidate.id));
      dragRef.current = {
        mode: "BEAM", endpoint, pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds, metrics, target, geometry, currentGeometry: geometry, changed: false,
      };
    } else if (element.type === "ARTICULATION") {
      const geometry = frame.geometry?.articulations?.[target.eventId];
      if (!geometry || !metrics) return;
      dragRef.current = {
        mode: "ARTICULATION", pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds: [String(element.id)], metrics, target, geometry,
        currentGeometry: geometry, changed: false,
      };
    } else if (element.type === "SLUR" || element.type === "TIE") {
      const geometry = element.type === "SLUR"
        ? frame.geometry?.slurs?.[target.slurId]
        : frame.geometry?.ties?.[target.sourceEventId]?.find(
          (item) => item.sourcePitchIndex === target.sourcePitchIndex,
        );
      if (!geometry || !metrics) return;
      dragRef.current = {
        mode: element.type, endpoint: dragEndpoint(element, x), pointerId: event.pointerId,
        startX: x, startY: y, element, elementIds: [String(element.id)], metrics, target,
        geometry, currentGeometry: geometry, changed: false,
      };
    } else if (["DYNAMIC", "HAIRPIN", "OCTAVE_SHIFT", "ORNAMENT"].includes(element.type)) {
      const source = attachmentDragSource(frame.update, frame.geometry, element);
      if (!source || !metrics) return;
      const endpoint = source.end && source.geometry.endDx != null && source.geometry.endDy != null
        ? dragEndpoint(element, x) : null;
      const anchor = staffAnchor(surface, element.systemIndex, element.staffIndex, frame.bundle);
      if (!anchor) return;
      dragRef.current = {
        mode: "ATTACHMENT", endpoint, pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds: [String(element.id)], metrics, target, source,
        sourceAnchor: { x, y: anchor.y }, targetAnchor: { x, y: anchor.y },
        currentStart: source.start, currentEnd: source.end, currentGeometry: source.geometry,
        deltaX: 0, deltaY: 0, changed: false,
      };
    } else if (element.type === "NAVIGATION_MARK") {
      const boundary = nearestBoundary(
        surface, x, (measure) => measure === target.boundaryMeasure,
        null, element.systemIndex, frame.bundle,
      );
      const anchor = staffAnchor(surface, element.systemIndex, element.staffIndex, frame.bundle);
      if (!boundary || !anchor || !metrics) return;
      const existingOffset = frame.update.score.measures?.[target.boundaryMeasure - 1]
        ?.navigationMarkOffsets?.[target.mark] ?? { dx: 0, dy: 0 };
      dragRef.current = {
        mode: "NAVIGATION", pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds: [String(element.id)], metrics, target,
        sourceAnchor: { x: boundary.x, y: anchor.y }, existingOffset,
        targetAnchor: { x: boundary.x, y: anchor.y }, targetSystemIndex: element.systemIndex,
        targetBoundary: target.boundaryMeasure, deltaX: 0, deltaY: 0, changed: false,
      };
    } else if (element.type === "VOLTA_ENDING") {
      const endpoint = target.numbers?.includes(1) ? "start" : target.numbers?.includes(2) ? "end" : null;
      if (!endpoint) return;
      dragRef.current = {
        mode: "VOLTA", endpoint, pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds: [String(element.id)], target,
        targetBoundary: endpoint === "start" ? target.startMeasure : target.endMeasure,
        deltaX: 0, deltaY: 0, changed: false,
      };
    } else {
      const currentTargets = resolveEventTargets(frame.update);
      const targetKey = `${target.voiceTrackId}\u0000${target.eventId}`;
      const selected = currentTargets.some((item) =>
        `${item.voiceTrackId}\u0000${item.eventId}` === targetKey &&
        (!target.pitchIndices || !item.pitchIndices ||
          target.pitchIndices.every((index) => item.pitchIndices.includes(index))),
      );
      const targets = selected ? currentTargets : [target];
      const elementIds = selectedElementIds({ ...frame.update, selection: targets }, surface.elements);
      const restPositions = Object.fromEntries(targets.map((item) => {
        const rest = surface.elements.find((candidate) =>
          candidate.type === "REST" && candidate.eventId === item.eventId,
        );
        return [item.eventId, restPositionForElement(rest, metrics)];
      }));
      dragRef.current = {
        mode: "NOTE", pointerId: event.pointerId, startX: x, startY: y,
        element, elementIds, metrics, targets, restPositions, stepDelta: 0,
      };
    }
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function onPointerMove(event) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const point = canvasPoint(event);
    if (!point) return;
    const { surface, x, y } = point;
    if (drag.mode === "NOTE") {
      drag.stepDelta = dragStepDelta(drag.startY, y, drag.metrics.halfSpace);
      if (drag.stepDelta === 0) {
        setDragPreview(null);
      } else if (drag.element.type === "REST" && requestRestMovePreview) {
        const requestedDelta = drag.stepDelta;
        const targets = drag.targets.map((target) => ({
          eventId: target.eventId,
          staffPosition: (drag.restPositions[target.eventId] ?? 0) + requestedDelta,
        }));
        requestRestMovePreview(targets, (enginePreview) => {
          if (dragRef.current === drag && drag.stepDelta === requestedDelta) {
            setDragPreview(enginePreview ? { enginePreview } : null);
          }
        });
      } else if (drag.element.type !== "REST" && requestTransposePreview) {
        const requestedDelta = drag.stepDelta;
        requestTransposePreview(drag.targets, requestedDelta, (enginePreview) => {
          if (dragRef.current === drag && drag.stepDelta === requestedDelta) {
            setDragPreview(enginePreview ? { enginePreview } : null);
          }
        });
      } else {
        // A host without a compiled-renderer preview gets no notation ghost. Never synthesize
        // or translate score elements in JavaScript as a platform-specific fallback.
        setDragPreview(null);
      }
      return;
    }
    drag.deltaX = x - drag.startX;
    drag.deltaY = y - drag.startY;
    drag.changed = Math.abs(drag.deltaX) >= 1 || Math.abs(drag.deltaY) >= 1;
    if (drag.mode === "MARQUEE") {
      // Continuous frozen elements stay in the renderer's global coordinate space while the
      // browser pointer and preview rectangle are surface-local. Paginated elements are already
      // page-local, so only continuous surfaces need their bounds origin restored for hit testing.
      const start = frame.bundle.paginated
        ? { x: drag.startX, y: drag.startY }
        : surfacePointToGlobal(frame.bundle, surface, drag.startX, drag.startY);
      const end = frame.bundle.paginated
        ? { x, y }
        : surfacePointToGlobal(frame.bundle, surface, x, y);
      drag.targets = marqueeSelection(frame.update, surface.elements, start.x, start.y, end.x, end.y);
      drag.elementIds = selectedElementIds({ ...frame.update, selection: drag.targets }, surface.elements);
      setDragPreview(!drag.changed ? null : {
        elementIds: drag.elementIds,
        offsets: {},
        marquee: {
          x: Math.min(drag.startX, x),
          y: Math.min(drag.startY, y),
          width: Math.abs(x - drag.startX),
          height: Math.abs(y - drag.startY),
        },
      });
      return;
    }
    if (drag.mode === "SLUR" || drag.mode === "TIE") {
      const space = drag.metrics.halfSpace * 2;
      if (drag.endpoint) {
        drag.currentGeometry = curveDragGeometry(drag.geometry, drag.endpoint, drag.deltaX, drag.deltaY, space);
      } else {
        const outward = (drag.geometry.above ? drag.startY - y : y - drag.startY) / space;
        const apex = drag.geometry.minApex + outward;
        drag.currentGeometry = {
          ...drag.geometry, minApex: apex, maxApex: apex,
          directionOnly: false, manuallyAdjusted: true,
        };
      }
    } else if (drag.mode === "BEAM") {
      drag.currentGeometry = beamDragGeometry(
        drag.geometry, drag.endpoint, drag.deltaY, drag.metrics.halfSpace * 2,
      );
    } else if (drag.mode === "ARTICULATION") {
      drag.currentGeometry = articulationDragGeometry(
        drag.geometry, drag.target.articulationIndex, drag.deltaX, drag.deltaY,
        drag.metrics.halfSpace * 2,
      );
    } else if (drag.mode === "ATTACHMENT" && drag.endpoint) {
      const core = staffCoreAtPointer(surface, y, drag.element.staffIndex, frame.bundle);
      if (core) {
        const anchor = staffAnchor(surface, core.systemIndex, drag.element.staffIndex, frame.bundle);
        const allowed = drag.endpoint === "start"
          ? (time) => compareTimeCodes(time, drag.source.end) < 0
          : (time) => compareTimeCodes(time, drag.source.start) > 0;
        const snapped = anchor ? nearestTimePosition(frame.bundle, surface, x, anchor.y, allowed) : null;
        if (snapped && anchor) {
          drag.targetAnchor = { x: snapped.x, y: anchor.y };
          drag.currentGeometry = attachmentDragGeometry(
            drag.source.geometry, drag.endpoint, x - snapped.x,
            drag.deltaY - (anchor.y - drag.sourceAnchor.y), drag.metrics.halfSpace * 2,
          );
          if (drag.endpoint === "start") drag.currentStart = snapped.timeCode;
          else drag.currentEnd = snapped.timeCode;
        }
      }
    } else if (drag.mode !== "ATTACHMENT") {
      const allowed = drag.mode === "VOLTA"
        ? (measure) => drag.endpoint === "start"
          ? measure <= drag.target.endMeasure : measure >= drag.target.startMeasure
        : () => true;
      const core = drag.mode === "NAVIGATION" || drag.mode === "VOLTA"
        ? staffCoreAtPointer(surface, y, drag.element.staffIndex, frame.bundle) : null;
      const systemIndex = core?.systemIndex ?? drag.targetSystemIndex ?? drag.element.systemIndex;
      const boundary = nearestBoundary(
        surface, x, allowed, drag.mode === "VOLTA" ? (core?.anchorY ?? drag.startY) : null,
        systemIndex, frame.bundle,
      );
      if (boundary) {
        drag.targetBoundary = boundary.boundaryMeasure;
        if (drag.mode === "VOLTA") drag.targetSystemIndex = systemIndex;
        if (drag.mode === "NAVIGATION") {
          drag.targetSystemIndex = systemIndex;
          drag.targetAnchor = { x: boundary.x, y: core?.anchorY ?? drag.targetAnchor.y };
        }
      }
    }
    setDragPreview(!drag.changed ? null : {
      elementIds: drag.elementIds,
      offsets: Object.fromEntries(drag.elementIds.map((id) => [id, {
        x: drag.mode === "SLUR" || drag.mode === "TIE" || drag.mode === "BEAM" ||
          (drag.mode === "ATTACHMENT" && drag.endpoint) ? 0 : drag.deltaX,
        y: drag.mode === "VOLTA" ? 0 : drag.deltaY,
      }])),
    });
  }

  function finish(event, cancelled = false) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    dragRef.current = null;
    setDragPreview(null);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (cancelled || (drag.mode === "NOTE" ? drag.stepDelta === 0 : !drag.changed)) return;
    suppressClickRef.current = true;
    if (drag.mode === "SLUR") {
      dispatch({ type: "setSlurGeometry", slurId: drag.target.slurId, geometry: drag.currentGeometry });
    } else if (drag.mode === "TIE") {
      dispatch({ type: "setTieGeometry", sourceEventId: drag.target.sourceEventId, geometry: drag.currentGeometry });
    } else if (drag.mode === "BEAM") {
      dispatch({ type: "setBeamGeometry", groupId: drag.target.groupId, geometry: drag.currentGeometry });
    } else if (drag.mode === "ARTICULATION") {
      dispatch({
        type: "setArticulationGeometry", eventId: drag.target.eventId,
        geometry: drag.currentGeometry, selectedIndex: drag.target.articulationIndex,
      });
    } else if (drag.mode === "MARQUEE") {
      dispatch({ type: "setSelection", targets: drag.targets });
    } else if (drag.mode === "ATTACHMENT") {
      const scale = drag.metrics.halfSpace * 2;
      dispatch({
        type: "moveAttachment", attachmentId: drag.source.attachmentId,
        start: drag.endpoint ? drag.currentStart : drag.source.start,
        end: drag.endpoint ? drag.currentEnd : drag.source.end,
        geometry: drag.endpoint ? drag.currentGeometry
          : attachmentDragGeometry(drag.source.geometry, null, drag.deltaX, drag.deltaY, scale),
      });
    } else if (drag.mode === "NAVIGATION") {
      dispatch({
        type: "moveNavigationMark", boundaryMeasure: drag.target.boundaryMeasure,
        targetBoundaryMeasure: drag.targetBoundary, mark: drag.target.mark,
        offset: navigationDragOffset(
          drag.existingOffset, drag.deltaX, drag.deltaY, drag.sourceAnchor, drag.targetAnchor,
          drag.metrics.halfSpace * 2,
        ),
      });
    } else if (drag.mode === "VOLTA") {
      if (drag.endpoint === "start" && drag.targetBoundary !== drag.target.startMeasure) {
        dispatch({ type: "resizeFirstVoltaStart", startMeasure: drag.target.startMeasure,
          newStartMeasure: drag.targetBoundary });
      } else if (drag.endpoint === "end" && drag.targetBoundary !== drag.target.endMeasure) {
        dispatch({ type: "resizeSecondVolta", startMeasure: drag.target.startMeasure,
          endMeasure: drag.targetBoundary });
      }
    } else if (drag.element.type === "REST") {
      dispatch({ type: "moveRests", targets: drag.targets.map((target) => ({
        ...target, staffPosition: (drag.restPositions[target.eventId] ?? 0) + drag.stepDelta,
      })) });
    } else {
      dispatch({ type: "transposeNotes", targets: drag.targets, stepDelta: drag.stepDelta });
    }
  }

  function cancel() {
    if (!dragRef.current) return;
    dragRef.current = null;
    suppressClickRef.current = true;
    setDragPreview(null);
  }

  return { onPointerDown, onPointerMove, finish, cancel };
}
