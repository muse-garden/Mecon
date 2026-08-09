import type { CSSProperties, ForwardRefExoticComponent, MouseEvent, RefAttributes } from "react";
import type { FrozenElement, FrozenScoreBundle, RenderBackend, RenderOptions } from "./index.js";
export interface FrozenScoreProps extends RenderOptions {
  bundle: string | FrozenScoreBundle;
  renderer?: RenderBackend;
  className?: string;
  style?: CSSProperties;
  hitTestOptions?: { types?: Iterable<string>; padding?: number };
  onSelect?: (element: FrozenElement | null, event: MouseEvent) => void;
}
export const FrozenScore: ForwardRefExoticComponent<FrozenScoreProps & RefAttributes<HTMLCanvasElement | HTMLDivElement>>;
