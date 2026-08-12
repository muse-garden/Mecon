export type RenderBackend = "canvas" | "svg";
export interface FrozenRect { origin: { x: number; y: number }; width: number; height: number }
export interface FrozenElement {
  id: string;
  type: string;
  commands: unknown[];
  hitBox: FrozenRect;
  eventId?: string;
  trackId?: string;
  measureNumber?: number;
  systemIndex?: number;
  staffIndex?: number;
  metadata?: Record<string, string>;
}
export interface FrozenSurface {
  index: number;
  width: number;
  height: number;
  contentOffsetY?: number;
  elements: FrozenElement[];
}
export interface FrozenScoreBundle {
  schemaVersion: number;
  engineVersion?: string;
  fontFingerprint?: string;
  paginated?: boolean;
  bounds: FrozenRect;
  surfaces: FrozenSurface[];
  timePositions?: unknown[];
}
export interface RenderOptions {
  surfaceIndex?: number;
  selectedIds?: Iterable<string>;
  hiddenIds?: Iterable<string>;
  elementOffsets?: Map<string, { x?: number; y?: number }> | Record<string, { x?: number; y?: number }>;
  elementTints?: Map<string, string> | Record<string, string>;
  elementCenterMarkers?: Map<string, string | { color: string; radius?: number }> |
    Record<string, string | { color: string; radius?: number }>;
  commandLayers?: Array<{ commands: readonly unknown[]; color?: string }>;
  musicFontFamily?: string;
  background?: string;
  pixelRatio?: number;
  selectionColor?: string;
  selectionWidth?: number;
  selectionDash?: number[];
  selectionMode?: "outline" | "tint";
  onUnknownCommand?: (command: unknown) => void;
}
export const FROZEN_SCHEMA_VERSION: 1;
export function parseFrozenScore(input: string | FrozenScoreBundle): FrozenScoreBundle;
export function loadMecon(source: Blob | ArrayBuffer | Uint8Array, options?: { scoreId?: string }): Promise<{
  manifest: any;
  scoreRef: any;
  bundle: FrozenScoreBundle;
}>;
export interface MeconArchive {
  entries: Map<string, Uint8Array>;
  manifest: any;
}
export type MeconEntryValue = string | ArrayBuffer | ArrayBufferView | Uint8Array;
export function loadMeconArchive(source: Blob | ArrayBuffer | Uint8Array): Promise<MeconArchive>;
export interface MeconDocumentArchive extends MeconArchive {
  scores: Map<string, any>;
  modules: Map<string, any>;
  opaqueModules: Map<string, Uint8Array>;
}
export function loadMeconDocument(source: Blob | ArrayBuffer | Uint8Array): Promise<MeconDocumentArchive>;
export function writeMeconArchive(
  source: MeconArchive | ReadonlyMap<string, MeconEntryValue>,
  replacements?: ReadonlyMap<string, MeconEntryValue | null> | Record<string, MeconEntryValue | null>,
): Uint8Array;
export function writeMeconDocument(
  document: MeconDocumentArchive,
  changes?: {
    manifest?: any;
    scores?: ReadonlyMap<string, any>;
    modules?: ReadonlyMap<string, any>;
    geometries?: ReadonlyMap<string, string | FrozenScoreBundle>;
  },
): Uint8Array;
export function loadMusicFont(url: string, family?: string): Promise<FontFace>;
export function hitTest(bundle: string | FrozenScoreBundle, surfaceIndex: number, x: number, y: number, options?: {
  types?: Iterable<string>;
  padding?: number;
}): FrozenElement | null;
export function renderCanvas(canvas: HTMLCanvasElement, bundle: string | FrozenScoreBundle, options?: RenderOptions): FrozenSurface;
export function renderSvg(bundle: string | FrozenScoreBundle, options?: RenderOptions): string;
export function renderSvgElement(svg: SVGSVGElement, bundle: string | FrozenScoreBundle, options?: RenderOptions): SVGSVGElement;
