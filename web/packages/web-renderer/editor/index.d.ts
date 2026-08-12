export type ScoreEditorControlId =
  | "history.undo" | "history.redo"
  | "selection.selectAll" | "selection.delete" | "selection.copy" | "selection.cut"
  | "selection.paste" | "selection.transposeDown" | "selection.transposeUp"
  | "selection.moveVoice" | "selection.duration" | "selection.accidental"
  | "selection.tie" | "selection.beam" | "selection.articulation" | "selection.arpeggio"
  | "input.position" | "input.duration" | "input.rest" | "input.chord"
  | "input.step" | "input.midi" | "input.grace" | "input.tuplet"
  | "score.layout" | "score.expression" | "score.structure" | "score.repeat"
  | "score.slur" | "document.export" | "host.status"
  | "tool.select" | "tool.marquee" | "tool.palette-toggle"
  | `voice.${1 | 2 | 3 | 4}`
  | "duration.whole" | "duration.half" | "duration.quarter" | "duration.eighth"
  | "duration.16th" | "duration.32nd" | "duration.rest" | `duration.dot.${1 | 2}`
  | "duration.uncommon-toggle" | "duration.breve" | "duration.64th"
  | "duration.longa" | "duration.maxima" | "duration.128th"
  | "accidental.sharp" | "accidental.flat" | "accidental.natural"
  | "accidental.double-sharp" | "accidental.double-flat"
  | "curve.tie" | "curve.slur"
  | "grace.appoggiatura" | "grace.acciaccatura" | "grace.small-note"
  | `tuplet.suggested.${2 | 3 | 4 | 5 | 6 | 7 | 8 | 9}`
  | "tuplet.custom" | "tuplet.confirm" | "tuplet.clear"
  | "beam.independent" | "beam.both" | "beam.right" | "beam.left" | "beam.group"
  | "articulation.toggle" | "articulation.staccato" | "articulation.spiccato"
  | "articulation.staccatissimo" | "articulation.tenuto" | "articulation.accent"
  | "articulation.marcato" | "articulation.fermata";

export interface ToolbarGroup {
  type: "group";
  id: string;
  items: readonly ScoreEditorControlId[];
}

export type ToolbarLayoutItem = ToolbarGroup
  | { type: "separator" }
  | { type: "break" }
  | { type: "slot"; id: string };

export interface ScoreEditorToolbarConfig {
  layout: readonly ToolbarLayoutItem[];
  hidden?: readonly ScoreEditorControlId[];
  overflow?: "wrap" | "scroll" | "menu";
}

export const SCORE_EDITOR_CONTROL_IDS: readonly ScoreEditorControlId[];
export const FULL_SCORE_EDITOR_TOOLBAR: Readonly<ScoreEditorToolbarConfig>;
export const FREE_PRACTICE_SCORE_TOOLBAR: Readonly<ScoreEditorToolbarConfig>;
export const SMUFL_GLYPHS: Readonly<Record<string, string>>;
export const DURATION_GLYPHS: readonly Readonly<{
  value: string; label: string; note: string; rest: string;
}>[];
export interface PlaybackCursorSnapshot {
  state: "idle" | "playing" | "paused";
  time: { numerator: number; denominator: number } | null;
  tick: number | null;
}
export interface PlaybackCursorStore {
  getSnapshot(): PlaybackCursorSnapshot;
  subscribe(listener: () => void): () => void;
  set(snapshot: PlaybackCursorSnapshot): void;
}
export function createPlaybackCursorStore(): PlaybackCursorStore;
export function playbackAnchorForCursor(frame: any, absoluteTime: any, playbackTick?: number | null): any | null;
export function scorePlayheadGeometry(frame: any, absoluteTime: any, surfaceIndex?: number, playbackTick?: number | null):
  Readonly<{ x: number; top: number; height: number }> | null;
export function playbackRangeForSelection(frame: any, update: any, tempoBpm: number): any | null;
export function interpolateScoreTime(start: any, end: any, progress: number):
  { numerator: number; denominator: number };
export function playbackTickAtOffset(excerpt: any, offsetSeconds: number): number | null;
export function completeNoteResumeOffset(notes: readonly any[], currentOffsetSeconds: number): number;
export function resolveToolbarLayout(config?: ScoreEditorToolbarConfig): Readonly<{
  overflow: "wrap" | "scroll" | "menu";
  items: readonly ToolbarLayoutItem[];
  visibleControlIds: readonly ScoreEditorControlId[];
}>;
export function toolbarProfileFromDescriptor(layer?: {
  groups: readonly { id: string; controls: readonly ScoreEditorControlId[] }[];
}, overflow?: "wrap" | "scroll" | "menu"): Readonly<ScoreEditorToolbarConfig>;

export function resolveEventTargets(update: any, selection?: readonly any[]): any[];
export function selectionTargetForElement(update: any, element: any): any;
export function selectionIdentity(target: any): string;
export function selectedElementIds(update: any, elements?: readonly any[]): string[];
export function allEventSelectionTargets(update: any): any[];
export function isRestTarget(update: any, target: any): boolean;
export function quarterBeatFraction(value: string | number): { numerator: number; denominator: number };
export interface ScoreEditorCommandController {
  addOrnamentToSelection(): boolean;
  addPointExpression(type: string, fields?: any): boolean;
  addSlurFromSelection(): boolean;
  addSpanExpression(type: string, fields?: any): boolean;
  applyClef(): boolean;
  applyKeySignature(): boolean;
  applyTimeSignature(): boolean;
  deleteSelectedExpression(): boolean;
  deleteSelectedNavigation(): boolean;
  deleteSelectedSlur(): boolean;
  deleteSelectedVolta(): boolean;
  editSelection(type: string, fields?: any, mapTarget?: (target: any) => any): boolean;
  eventTargets(): any[];
  expressionTime(measure?: string | number): any;
  groupedEventTargets(count?: number): any[];
  insertChord(): boolean;
  insertEvent(isRest: boolean, pitchFields?: any): boolean;
  insertEventAtPointer(target: any, insertion: any): boolean;
  moveSelectionToDestination(): boolean;
  moveSelectedNavigation(): boolean;
  pasteAtInputPosition(): boolean;
  resizeSelectedVolta(edge: "first" | "second"): boolean;
  selectAllEvents(): boolean;
  selectedStructure(type: string): any;
  selectionIsOnlyRests(): boolean;
  setBarline(): boolean;
  targetStaffId(): string | undefined;
  updateSelectedAttachment(type: string, fields: (id: string) => any): boolean;
  updateSlurGeometry(fields?: any): boolean;
}
export function createScoreEditorCommandController(options: {
  update: any;
  input: Record<string, any>;
  structure?: Record<string, any>;
  expression?: Record<string, any>;
  destinations?: readonly any[];
  dispatch: (intent: any) => void;
}): ScoreEditorCommandController;
export function formatQuarterBeat(value: { numerator: number; denominator: number }): string;
export function compareTimeCodes(left: any, right: any): number;
export function nearestTimePosition(bundle: any, surface: any, x: number, y: number): any;
export function surfacePointToGlobal(bundle: any, surface: any, x: number, y: number): { x: number; y: number };
export function nearestBoundary(surface: any, x: number, allowed?: (measure: number) => boolean,
  pointerY?: number | null, systemIndex?: number | null, sourceSystem?: any): any;
export function marqueeSelection(update: any, elements: readonly any[], x1: number, y1: number,
  x2: number, y2: number): any[];
export function staffDragMetrics(surface: any, element: any): any;
export function dragStepDelta(startY: number, y: number, halfSpace: number): number;
export function restPositionForElement(element: any, metrics: any): number;
export function dragEndpoint(element: any, x: number): "start" | "end" | null;
export function curveDragGeometry(geometry: any, endpoint: any, dx: number, dy: number, staffSpace: number): any;
export function beamDragGeometry(geometry: any, endpoint: any, dy: number, staffSpace: number): any;
export function articulationDragGeometry(geometry: any, index: number, dx: number, dy: number,
  staffSpace: number): any;
export function attachmentDragSource(update: any, geometry: any, element: any): any;
export function attachmentDragGeometry(geometry: any, endpoint: any, dx: number, dy: number,
  staffSpace: number): any;
export function staffCoreAtPointer(surface: any, y: number, contentOffsetY?: number): any;
export function staffAnchor(surface: any, systemIndex: number, staffIndex: number): any;
export function navigationDragOffset(geometry: any, dx: number, dy: number, sourceAnchor: any,
  targetAnchor: any, staffSpace: number): any;
export function createScoreEditorDragController(options: {
  frame: any;
  surfaceIndex: number;
  canvasPoint: (event: any) => { surface: any; x: number; y: number } | null;
  dragRef: { current: any };
  suppressClickRef: { current: boolean };
  setDragPreview: (preview: any) => void;
  dispatch: (intent: any) => void;
  tool?: "select" | "marquee";
}): {
  onPointerDown(event: any): void;
  onPointerMove(event: any): void;
  finish(event: any, cancelled?: boolean): void;
};

export function createControlledScrollSync(tolerance?: number): {
  apply(element: { scrollLeft: number } | null, controlled: number): void;
  observe(next: number, controlled?: number | null): boolean;
};
