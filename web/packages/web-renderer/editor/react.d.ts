import type { Dispatch, ForwardedRef, ReactNode, SetStateAction } from "react";
import type { ScoreEditorToolbarConfig } from "./index.js";

export interface ScoreEditorToolbarProps {
  config?: ScoreEditorToolbarConfig;
  controls?: Partial<Record<string, ReactNode>>;
  slots?: Record<string, ReactNode>;
  className?: string;
}

export function ScoreEditorToolbar(props: ScoreEditorToolbarProps): ReactNode;

type EditorSetter<T> = Dispatch<SetStateAction<T>>;

export interface ScoreEditorInputState {
  insertMeasure: number; setInsertMeasure: EditorSetter<number>;
  insertBeat: number; setInsertBeat: EditorSetter<number>;
  insertPitch: number; setInsertPitch: EditorSetter<number>;
  insertChordPitches: string; setInsertChordPitches: EditorSetter<string>;
  insertDuration: string; setInsertDuration: EditorSetter<string>;
  insertDots: number; setInsertDots: EditorSetter<number>;
  editDuration: string; setEditDuration: EditorSetter<string>;
  editDots: number; setEditDots: EditorSetter<number>;
  graceMode: boolean; setGraceMode: EditorSetter<boolean>;
  graceNoteType: string; setGraceNoteType: EditorSetter<string>;
  graceDuration: string; setGraceDuration: EditorSetter<string>;
  graceTimeSource: string; setGraceTimeSource: EditorSetter<string>;
  tupletCount: number; setTupletCount: EditorSetter<number>;
  articulation: string; setArticulation: EditorSetter<string>;
  arpeggioType: string; setArpeggioType: EditorSetter<string>;
  restStaffPosition: number; setRestStaffPosition: EditorSetter<number>;
  moveDestination: string; setMoveDestination: EditorSetter<string>;
  stepInputEnabled: boolean; setStepInputEnabled: EditorSetter<boolean>;
  midiStatus: string; setMidiStatus: EditorSetter<string>;
  editorTool: string; setEditorTool: EditorSetter<string>;
  paletteExpanded: boolean; setPaletteExpanded: EditorSetter<boolean>;
  uncommonDurationsExpanded: boolean; setUncommonDurationsExpanded: EditorSetter<boolean>;
  articulationsExpanded: boolean; setArticulationsExpanded: EditorSetter<boolean>;
  restMode: boolean; setRestMode: EditorSetter<boolean>;
  customTupletText: string; setCustomTupletText: EditorSetter<string>;
  insertionBeaming: any; setInsertionBeaming: EditorSetter<any>;
}

export function useScoreEditorInputState(): ScoreEditorInputState;

export interface ScoreEditorStructureState {
  structureCount: number; setStructureCount: EditorSetter<number>;
  clefValue: string; setClefValue: EditorSetter<string>;
  keyValue: string; setKeyValue: EditorSetter<string>;
  meterNumerator: number; setMeterNumerator: EditorSetter<number>;
  meterDenominator: number; setMeterDenominator: EditorSetter<number>;
  boundaryMeasure: number; setBoundaryMeasure: EditorSetter<number>;
  targetBoundaryMeasure: number; setTargetBoundaryMeasure: EditorSetter<number>;
  barlineValue: string; setBarlineValue: EditorSetter<string>;
  repeatCount: number; setRepeatCount: EditorSetter<number>;
  navigationMark: string; setNavigationMark: EditorSetter<string>;
  slurDraft: any; setSlurDraft: EditorSetter<any>;
}

export function useScoreEditorStructureState(): ScoreEditorStructureState;

export interface ScoreEditorExpressionState {
  expressionEndMeasure: number; setExpressionEndMeasure: EditorSetter<number>;
  dynamicLevel: string; setDynamicLevel: EditorSetter<string>;
  hairpinType: string; setHairpinType: EditorSetter<string>;
  hairpinStyle: string; setHairpinStyle: EditorSetter<string>;
  octaveShiftType: string; setOctaveShiftType: EditorSetter<string>;
  tempoMarkType: string; setTempoMarkType: EditorSetter<string>;
  tempoBpm: number; setTempoBpm: EditorSetter<number>;
  layoutBreakKind: string; setLayoutBreakKind: EditorSetter<string>;
  visibilityEndMeasure: number; setVisibilityEndMeasure: EditorSetter<number>;
  ornamentKind: string; setOrnamentKind: EditorSetter<string>;
  ornamentOscillations: number; setOrnamentOscillations: EditorSetter<number>;
  performanceAmount: number; setPerformanceAmount: EditorSetter<number>;
  tempoDisplayStyle: string; setTempoDisplayStyle: EditorSetter<string>;
}

export function useScoreEditorExpressionState(): ScoreEditorExpressionState;

export interface ScoreEditorInputInspectorProps {
  frame: any;
  state: ScoreEditorInputState;
  commands: import("./index.js").ScoreEditorCommandController;
  dispatch: (intent: any) => void;
  onEnableMidi?: () => void | Promise<void>;
}

export function ScoreEditorInputInspector(props: ScoreEditorInputInspectorProps): ReactNode;

export function ScoreEditorLayoutInspector(props: {
  frame: any;
  input: ScoreEditorInputState;
  expression: ScoreEditorExpressionState;
  commands: import("./index.js").ScoreEditorCommandController;
  dispatch: (intent: any) => void;
}): ReactNode;

export function ScoreEditorExpressionInspector(props: {
  frame: any;
  input: ScoreEditorInputState;
  expression: ScoreEditorExpressionState;
  commands: import("./index.js").ScoreEditorCommandController;
  dispatch: (intent: any) => void;
}): ReactNode;

export function ScoreEditorStructureInspector(props: {
  frame: any;
  input: ScoreEditorInputState;
  structure: ScoreEditorStructureState;
  commands: import("./index.js").ScoreEditorCommandController;
  dispatch: (intent: any) => void;
  onDeleteMeasure?: () => void;
}): ReactNode;

export function ScoreEditorRepeatInspector(props: {
  frame: any;
  structure: ScoreEditorStructureState;
  commands: import("./index.js").ScoreEditorCommandController;
  dispatch: (intent: any) => void;
}): ReactNode;

export function ScoreEditorSlurInspector(props: {
  frame: any;
  structure: ScoreEditorStructureState;
  commands: import("./index.js").ScoreEditorCommandController;
}): ReactNode;

export function createScoreEditorToolbarControls(options: {
  frame: any;
  input: ScoreEditorInputState;
  commands: import("./index.js").ScoreEditorCommandController;
  destinations?: readonly any[];
  dispatch: (intent: any) => void;
  onEnableMidi?: () => void | Promise<void>;
  onExport?: () => void;
}): Partial<Record<string, ReactNode>>;

export function createScoreEditorSelectionController(options: {
  frame: any;
  surfaceIndex: number;
  canvasRef: { current: HTMLCanvasElement | null };
  suppressClickRef: { current: boolean };
  dispatch: (intent: any) => void;
  tool?: string;
  onNoteInput?: (point: { x: number; y: number }) => void;
  onNoteHover?: (point: { x: number; y: number }) => void;
  onNoteHoverEnd?: () => void;
}): {
  canvasPoint(event: any): { surface: any; x: number; y: number } | null;
  onClick(event: any): void;
  onPointerMove(event: any): void;
  onPointerLeave(): void;
};

export interface ScoreEditorSurfaceProps {
  snapshot: any;
  surfaceIndex?: number;
  onSurfaceIndexChange?: (index: number) => void;
  dragPreview?: any;
  noteInputPreview?: any;
  background?: string;
  emptyContent?: ReactNode;
  onClick?: (event: any) => void;
  onPointerDown?: (event: any) => void;
  onPointerMove?: (event: any) => void;
  onPointerLeave?: (event: any) => void;
  onPointerUp?: (event: any) => void;
  onPointerCancel?: (event: any) => void;
  scrollLeft?: number;
  onScroll?: (scrollLeft: number) => void;
  onViewportWidth?: (width: number) => void;
  /** Shared horizontal extent; extra width becomes trailing margin after the engraved score. */
  scrollContentWidth?: number;
  playbackStore?: import("./index.js").PlaybackCursorStore | null;
  className?: string;
  ariaLabel?: string;
  canvasAriaLabel?: string;
  ref?: ForwardedRef<HTMLCanvasElement>;
}

export const ScoreEditorSurface: (props: ScoreEditorSurfaceProps) => ReactNode;

export interface ScoreEditorController {
  frame: any;
  dispatch: (intent: any) => void;
  canvasRef: { current: HTMLCanvasElement | null };
  dragRef: { current: any };
  input: ScoreEditorInputState;
  structure: ScoreEditorStructureState;
  expression: ScoreEditorExpressionState;
  destinations: readonly any[];
  commands: import("./index.js").ScoreEditorCommandController;
  surfaceIndex: number;
  setSurfaceIndex: EditorSetter<number>;
  dragPreview: any;
  noteInputPreview: any;
  selection: ReturnType<typeof createScoreEditorSelectionController>;
  drag: any;
}

export function useScoreEditorController(options: {
  frame: any;
  dispatch: (intent: any) => void;
  requestTransposePreview?: (targets: any[], stepDelta: number, callback: (preview: any) => void) => void;
  requestRestMovePreview?: (targets: any[], callback: (preview: any) => void) => void;
  requestNoteInputTarget?: (request: any, callback: (target: any) => void) => void;
}): ScoreEditorController;

export interface ScoreEditorRegions {
  toolbar: ReactNode;
  surface: ReactNode;
  inspectors: ReactNode;
}

export function ScoreEditor(props: {
  controller: ScoreEditorController;
  toolbarConfig?: ScoreEditorToolbarConfig;
  toolbarSlots?: Record<string, ReactNode>;
  hiddenControlIds?: readonly string[];
  onEnableMidi?: () => void | Promise<void>;
  onExport?: () => void;
  onDeleteMeasure?: () => void;
  surfaceProps?: Omit<ScoreEditorSurfaceProps, "snapshot" | "surfaceIndex" | "ref">;
  children?: ReactNode | ((regions: ScoreEditorRegions) => ReactNode);
}): ReactNode;
