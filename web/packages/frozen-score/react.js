import React, { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import { hitTest, parseFrozenScore, renderCanvas, renderSvg } from "./index.js";

export const FrozenScore = forwardRef(function FrozenScore(
  {
    bundle: bundleInput,
    renderer = "canvas",
    surfaceIndex = 0,
    selectedIds = [],
    onSelect,
    className,
    style,
    ...renderOptions
  },
  forwardedRef,
) {
  const hostRef = useRef(null);
  const bundle = parseFrozenScore(bundleInput);
  useImperativeHandle(forwardedRef, () => hostRef.current, []);

  useEffect(() => {
    if (renderer === "canvas" && hostRef.current) {
      renderCanvas(hostRef.current, bundle, { ...renderOptions, surfaceIndex, selectedIds });
    }
  }, [bundle, renderer, surfaceIndex, selectedIds, renderOptions.musicFontFamily, renderOptions.background]);

  const select = (event) => {
    if (!onSelect) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const surface = bundle.surfaces.find((item) => item.index === surfaceIndex) ?? bundle.surfaces[surfaceIndex];
    const x = (event.clientX - bounds.left) * surface.width / bounds.width;
    const y = (event.clientY - bounds.top) * surface.height / bounds.height;
    onSelect(hitTest(bundle, surfaceIndex, x, y, renderOptions.hitTestOptions), event);
  };

  if (renderer === "svg") {
    const markup = renderSvg(bundle, { ...renderOptions, surfaceIndex, selectedIds });
    return React.createElement("div", {
      ref: hostRef,
      className,
      style,
      onClick: select,
      dangerouslySetInnerHTML: { __html: markup },
    });
  }
  return React.createElement("canvas", { ref: hostRef, className, style, onClick: select });
});
