import React, { useRef, useState } from "react";

const DEFAULT_HEIGHT = 560;
const MIN_HEIGHT = 180;
const MAX_HEIGHT = 800;

function clampHeight(value) {
  const viewportLimit = typeof window === "undefined"
    ? MAX_HEIGHT
    : Math.max(MIN_HEIGHT, window.innerHeight - 180);
  return Math.min(MAX_HEIGHT, viewportLimit, Math.max(MIN_HEIGHT, value));
}

/** Platform-only layout state: resizing never enters the practice document or undo history. */
export function ResizableLowerWorkbench({ left, right }) {
  const [height, setHeight] = useState(DEFAULT_HEIGHT);
  const drag = useRef(null);

  function startResize(event) {
    if (event.button !== 0) return;
    event.preventDefault();
    drag.current = { pointerId: event.pointerId, startY: event.clientY, startHeight: height };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function resize(event) {
    const current = drag.current;
    if (!current || current.pointerId !== event.pointerId) return;
    setHeight(clampHeight(current.startHeight + current.startY - event.clientY));
  }

  function stopResize(event) {
    if (drag.current?.pointerId !== event.pointerId) return;
    drag.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  return <section className="workbench-lower-shell" style={{ "--practice-lower-height": `${height}px` }}>
    <div className="workbench-lower-resizer" role="separator" aria-label="调整和声面板高度"
      aria-orientation="horizontal" aria-valuemin={MIN_HEIGHT} aria-valuemax={MAX_HEIGHT}
      aria-valuenow={Math.round(height)} tabIndex={0}
      onPointerDown={startResize} onPointerMove={resize} onPointerUp={stopResize}
      onPointerCancel={stopResize} onDoubleClick={() => setHeight(DEFAULT_HEIGHT)}
      onKeyDown={(event) => {
        if (!["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        if (event.key === "Home") setHeight(MIN_HEIGHT);
        else if (event.key === "End") setHeight(clampHeight(MAX_HEIGHT));
        else setHeight((current) => clampHeight(current + (event.key === "ArrowUp" ? 16 : -16)));
      }} />
    <div className="workbench-lower">
      <div className="workbench-lower-pane harmony-workbench-pane">{left}</div>
      <div className="workbench-lower-pane">{right}</div>
    </div>
  </section>;
}
