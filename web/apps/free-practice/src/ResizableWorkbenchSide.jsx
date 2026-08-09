import React, { useRef, useState } from "react";

const DEFAULT_WIDTH = 600;
const MIN_WIDTH = 240;
const MAX_WIDTH = 720;

function clampWidth(value) {
  const viewportLimit = typeof window === "undefined" ? MAX_WIDTH : Math.max(MIN_WIDTH, window.innerWidth - 320);
  return Math.min(MAX_WIDTH, viewportLimit, Math.max(MIN_WIDTH, value));
}

/** Platform-only layout state: resizing never enters the practice document or undo history. */
export function ResizableWorkbenchSide({ children }) {
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  const drag = useRef(null);

  function startResize(event) {
    if (event.button !== 0) return;
    event.preventDefault();
    drag.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: width };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function resize(event) {
    const current = drag.current;
    if (!current || current.pointerId !== event.pointerId) return;
    setWidth(clampWidth(current.startWidth + current.startX - event.clientX));
  }

  function stopResize(event) {
    if (drag.current?.pointerId !== event.pointerId) return;
    drag.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  return <section className="workbench-side" style={{ "--practice-side-width": `${width}px` }}>
    <div className="workbench-side-resizer" role="separator" aria-label="调整右侧面板宽度"
      aria-orientation="vertical" aria-valuemin={MIN_WIDTH} aria-valuemax={MAX_WIDTH}
      aria-valuenow={Math.round(width)} tabIndex={0}
      onPointerDown={startResize} onPointerMove={resize} onPointerUp={stopResize}
      onPointerCancel={stopResize} onDoubleClick={() => setWidth(DEFAULT_WIDTH)}
      onKeyDown={(event) => {
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight" && event.key !== "Home" && event.key !== "End") return;
        event.preventDefault();
        if (event.key === "Home") setWidth(MIN_WIDTH);
        else if (event.key === "End") setWidth(clampWidth(MAX_WIDTH));
        else setWidth((current) => clampWidth(current + (event.key === "ArrowLeft" ? 16 : -16)));
      }} />
    {children}
  </section>;
}
