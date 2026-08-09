import React, { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { FrozenScore } from "@mecon/frozen-score/react";
export { CircleOfFifthsPicker } from "./CircleOfFifthsPicker.jsx";

export const MeconScore = forwardRef(function MeconScore(
  { engine, score, onRender, onError, ...viewerProps },
  forwardedRef,
) {
  const localRef = useRef(null);
  const [bundle, setBundle] = useState(null);
  useImperativeHandle(forwardedRef, () => localRef.current, []);

  useEffect(() => {
    let active = true;
    try {
      const next = engine.layout(score);
      if (active) {
        setBundle(next);
        onRender?.(next);
      }
    } catch (error) {
      if (active) onError?.(error);
    }
    return () => { active = false; };
  }, [engine, score]);

  if (!bundle) return null;
  return React.createElement(FrozenScore, { ...viewerProps, bundle, ref: localRef });
});
