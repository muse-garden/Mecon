import React, { useEffect, useRef, useState } from "react";

const TIMELINE_KEYS = new Set([
  "Enter", " ", "ArrowLeft", "ArrowRight", "Delete", "Backspace", "Escape",
]);

function timelineOwnsKey(event) {
  if (!TIMELINE_KEYS.has(event.key)) return false;
  // Application shortcuts such as Ctrl/Cmd+Z belong to the workbench. Arrow keys are the only
  // timeline command that intentionally uses Ctrl/Cmd (move the selected slot with its followers).
  return !(event.ctrlKey || event.metaKey) || event.key === "ArrowLeft" || event.key === "ArrowRight";
}

function DrawObject({ object }) {
  const { bounds } = object;
  switch (object.kind) {
    case "RECT":
    case "ROUND_RECT":
      return <rect data-object-id={object.id} x={bounds.x} y={bounds.y} width={bounds.width} height={bounds.height}
        rx={object.kind === "ROUND_RECT" ? object.radius : 0} fill={object.fill ?? "none"}
        stroke={object.stroke ?? "none"} strokeWidth={object.strokeWidth || 0} />;
    case "LINE":
      return <line data-object-id={object.id} x1={bounds.x} y1={bounds.y} x2={bounds.x + bounds.width} y2={bounds.y + bounds.height}
        stroke={object.stroke ?? "none"} strokeWidth={object.strokeWidth || 1}
        strokeDasharray={object.dashPattern?.length ? object.dashPattern.join(" ") : undefined} />;
    case "CIRCLE":
      return <circle data-object-id={object.id} cx={bounds.x + bounds.width / 2} cy={bounds.y + bounds.height / 2}
        r={bounds.width / 2} fill={object.fill ?? "none"} stroke={object.stroke ?? "none"}
        strokeWidth={object.strokeWidth || 0} />;
    case "TEXT":
      return <text data-object-id={object.id} x={object.textAlign === "center" ? bounds.x + bounds.width / 2 : bounds.x + 5}
        y={bounds.y + bounds.height / 2} dominantBaseline="middle"
        textAnchor={object.textAlign === "center" ? "middle" : "start"}
        fill={object.fill ?? "currentColor"} fontFamily={object.fontFamily ?? "system-ui"}
        fontSize={object.fontSize || 12} fontWeight={object.fontWeight || 400}>{object.text ?? ""}</text>;
    case "BRACKET":
      return <g data-object-id={object.id}><path d={`M${bounds.x},${bounds.y + bounds.height} V${bounds.y + 4} H${bounds.x + bounds.width} V${bounds.y + bounds.height}`}
        fill="none" stroke={object.stroke ?? "#94a3b8"} strokeWidth={object.strokeWidth || 1} />
        {object.text && <text x={bounds.x + 5} y={bounds.y + 12} fill={object.stroke ?? "#94a3b8"}
          fontFamily="system-ui" fontSize="10" fontWeight="500">{object.text}</text>}</g>;
    default:
      return null;
  }
}

/** Thin browser adapter: raw input in, shared draw/a11y objects out. */
export function HarmonyTimeline({ scene, scrollLeft = 0, onScroll, onInput }) {
  const surfaceRef = useRef(null);
  const scrollRef = useRef(null);
  const lastScrollLeftRef = useRef(scrollLeft);
  // Pointer feedback stays on the main thread: hover must not wait for an engine Worker round trip.
  // `hoverTargets` already arrives sorted by the controller's hit priority, so the whole browser-side
  // rule is "first target that contains the pointer" — no priority or highlight logic is copied here.
  const [hoverHitId, setHoverHitId] = useState(null);

  useEffect(() => {
    if (scrollRef.current && Math.abs(scrollRef.current.scrollLeft - scrollLeft) > 1) {
      scrollRef.current.scrollLeft = scrollLeft;
    }
  }, [scrollLeft]);

  if (!scene) return <section className="harmony-timeline loading" aria-label="和声时间轴">正在生成时间轴…</section>;

  function point(event) {
    const bounds = surfaceRef.current.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  }

  function updateHover(event) {
    const { x, y } = point(event);
    const target = (scene.hoverTargets ?? []).find(({ bounds }) =>
      x >= bounds.x && x <= bounds.x + bounds.width && y >= bounds.y && y <= bounds.y + bounds.height);
    setHoverHitId(target?.hitId ?? null);
  }

  function send(type, event, extra = {}) {
    const coordinates = "clientX" in event ? point(event) : { x: 0, y: 0 };
    onInput?.({
      type,
      sceneGeneration: scene.generation,
      pointerId: event.pointerId ?? null,
      ...coordinates,
      button: Math.max(0, event.button ?? 0),
      key: event.key ?? null,
      ctrl: !!event.ctrlKey,
      meta: !!event.metaKey,
      shift: !!event.shiftKey,
      deltaX: event.deltaX ?? 0,
      deltaY: event.deltaY ?? 0,
      ...extra,
    });
  }

  function semanticButton(object, style = {}, className = "") {
    return <button key={object.id} type="button"
      className={`timeline-semantic-object ${className}`.trim()}
      style={{ width: object.bounds.width, height: object.bounds.height, ...style }}
      aria-label={object.label} aria-pressed={object.role === "button" ? object.selected : undefined}
      aria-disabled={object.disabled || undefined}
      onKeyDown={(event) => {
        if (!timelineOwnsKey(event)) return;
        event.stopPropagation();
        send("KEY", event, { actionTargetId: object.id });
      }}
      onClick={(event) => {
        event.stopPropagation();
        send("ACTIVATE", event, { actionTargetId: object.id });
      }} />;
  }

  function semanticObject(object) {
    if (/^slot:.+:(start|end)$/.test(object.id)) return null;
    const button = semanticButton(object);
    if (object.id.startsWith("slot:")) {
      const handles = (scene.accessibility ?? []).filter((candidate) => candidate.id.startsWith(`${object.id}:`));
      return <div key={object.id} className={`timeline-slot-control${handles.length === 0 ? " locked" : ""}`}
      data-slot-id={object.id.slice(5)}
      style={{ left: object.bounds.x, top: object.bounds.y, width: object.bounds.width, height: object.bounds.height }}>
      {button}
      {handles.map((handle) => semanticButton(handle, {
        position: "absolute",
        left: handle.bounds.x - object.bounds.x,
        top: handle.bounds.y - object.bounds.y,
      }, handle.id.endsWith(":start") ? "timeline-start-handle" : "timeline-end-handle"))}
    </div>;
    }
    const compatibilityClass = object.id.startsWith("tonal:") && !/:(start|end)$/.test(object.id)
      ? " timeline-key-labels"
      : object.id.startsWith("idiom:")
        ? " timeline-idiom-range"
        : "";
    return <div key={object.id} className={`timeline-a11y-control${compatibilityClass}`}
      style={{ left: object.bounds.x, top: object.bounds.y, width: object.bounds.width, height: object.bounds.height }}>
      {button}
    </div>;
  }

  const hovered = (scene.hoverTargets ?? []).find((target) => target.hitId === hoverHitId) ?? null;
  const drawObjects = hovered
    ? [...(scene.drawObjects ?? []), ...(hovered.overlay ?? [])].sort((left, right) => left.z - right.z)
    : (scene.drawObjects ?? []);

  return <section className="harmony-timeline-shell" aria-label="和声时间轴" data-axis-source="renderer">
    <div className="harmony-timeline-scroll" ref={scrollRef}
      onScroll={(event) => {
        const next = event.currentTarget.scrollLeft;
        const deltaX = next - lastScrollLeftRef.current;
        lastScrollLeftRef.current = next;
        onScroll?.(next);
        if (Math.abs(deltaX) > 0.5) onInput?.({
          type: "WHEEL", sceneGeneration: scene.generation, deltaX, deltaY: 0,
        });
      }}>
      <div className="harmony-timeline" ref={surfaceRef}
        style={{
          width: `${scene.contentWidth}px`,
          height: `${scene.contentHeight}px`,
          cursor: hovered?.cursor ?? "default",
        }}
        data-scene-generation={scene.generation}
        data-hover-target={hovered?.hitId ?? undefined}
        onPointerDownCapture={(event) => {
          event.currentTarget.setPointerCapture?.(event.pointerId);
          send("DOWN", event);
        }}
        onPointerMove={(event) => {
          if (event.currentTarget.hasPointerCapture?.(event.pointerId)) send("MOVE", event);
          else updateHover(event);
        }}
        onPointerLeave={() => setHoverHitId(null)}
        onPointerUp={(event) => {
          send("UP", event);
          event.currentTarget.releasePointerCapture?.(event.pointerId);
          updateHover(event);
        }}
        onPointerCancel={(event) => {
          send("CANCEL", event);
          event.currentTarget.releasePointerCapture?.(event.pointerId);
          setHoverHitId(null);
        }}
        onKeyDown={(event) => {
          if (timelineOwnsKey(event)) send("KEY", event);
        }}>
        <svg width={scene.contentWidth} height={scene.contentHeight}
          viewBox={`0 0 ${scene.contentWidth} ${scene.contentHeight}`} aria-hidden="true">
          {drawObjects.map((object) => <DrawObject key={object.id} object={object} />)}
        </svg>
        <div className="timeline-semantic-layer">
          {(scene.accessibility ?? []).map(semanticObject)}
        </div>
      </div>
    </div>
  </section>;
}
