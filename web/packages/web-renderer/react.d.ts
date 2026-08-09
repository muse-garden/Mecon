import type { ForwardRefExoticComponent, RefAttributes } from "react";
import type { FrozenScoreBundle } from "@mecon/frozen-score";
import type { FrozenScoreProps } from "@mecon/frozen-score/react";
import type { MeconRenderer } from "./index.js";

export interface CircleOfFifthsKey {
  fifths: number;
  mode: string;
}
export interface CircleOfFifthsOption {
  key: CircleOfFifthsKey;
  label: string;
}
export interface CircleOfFifthsPickerProps {
  options?: readonly CircleOfFifthsOption[];
  currentKey?: CircleOfFifthsKey | null;
  selectedKeys?: readonly CircleOfFifthsKey[];
  matchedKeys?: readonly CircleOfFifthsKey[];
  onKeyClick?: (key: CircleOfFifthsKey) => void;
  label?: (option: CircleOfFifthsOption) => string;
  className?: string;
  centerLabel?: string | null;
  centerCaption?: string | null;
}
export const CircleOfFifthsPicker: import("react").FC<CircleOfFifthsPickerProps>;
export interface MeconScoreProps extends Omit<FrozenScoreProps, "bundle"> {
  engine: MeconRenderer;
  score: string | object;
  onRender?: (bundle: FrozenScoreBundle) => void;
  onError?: (error: unknown) => void;
}
export const MeconScore: ForwardRefExoticComponent<MeconScoreProps & RefAttributes<HTMLCanvasElement | HTMLDivElement>>;
