import type { FrozenScoreBundle, RenderOptions } from "@mecon/frozen-score";
export interface EngineModule {
  com?: { mecon?: { web?: { MeconWebEngine?: new (metadata: string, glyphNames: string, version?: string) => {
    renderScoreJson(scoreJson: string): string;
    renderChordDetailConstructionJson?(constructionJson: string): string;
    renderFreePracticeFrameJson?(scoreJson: string, timelineJson: string): string;
    renderFreePracticeFrameForWidthJson?(scoreJson: string, timelineJson: string, width: number): string;
    renderFreePracticeFrameScaledJson?(scoreJson: string, timelineJson: string, pixelsPerWhole: number): string;
    renderFreePracticeFrameForWidthScaledJson?(scoreJson: string, timelineJson: string, width: number, pixelsPerWhole: number): string;
    transposePreviewJson?(targetsJson: string, stepDelta: number): string;
    restMovePreviewJson?(targetsJson: string): string;
    noteInputTargetJson?(requestJson: string): string;
    paletteSelectionInfoJson?(selectionJson: string): string;
    applyAccidentalToPitchJson?(pitchJson: string, accidentalName: string): string;
  } } } };
  MeconWebEngine?: new (metadata: string, glyphNames: string, version?: string) => {
    renderScoreJson(scoreJson: string): string;
    renderChordDetailConstructionJson?(constructionJson: string): string;
    renderFreePracticeFrameJson?(scoreJson: string, timelineJson: string): string;
    renderFreePracticeFrameForWidthJson?(scoreJson: string, timelineJson: string, width: number): string;
    renderFreePracticeFrameScaledJson?(scoreJson: string, timelineJson: string, pixelsPerWhole: number): string;
    renderFreePracticeFrameForWidthScaledJson?(scoreJson: string, timelineJson: string, width: number, pixelsPerWhole: number): string;
    transposePreviewJson?(targetsJson: string, stepDelta: number): string;
    restMovePreviewJson?(targetsJson: string): string;
    noteInputTargetJson?(requestJson: string): string;
    paletteSelectionInfoJson?(selectionJson: string): string;
    applyAccidentalToPitchJson?(pitchJson: string, accidentalName: string): string;
  };
  MeconScoreEditor?: new (scoreJson: string) => KotlinScoreEditor;
  MeconFreePractice?: new (documentJson: string, scoreJson: string) => KotlinFreePractice;
  MeconFreePracticeTimeline?: new () => KotlinFreePracticeTimeline;
  MeconFreePracticeExecutor?: new () => KotlinFreePracticeExecutor;
  MeconFreePracticePreset?: new () => { documentJson(): string; scoreJson(): string };
}
export interface KotlinFreePractice {
  initialUpdateJson(): string;
  dispatchJson(intentJson: string): string;
  applyBackgroundResultJson(resultJson: string): string;
  applyTeachingCatalogResultJson(resultJson: string): string;
  applyFindingResultJson(resultJson: string): string;
  applyBackgroundFailureJson(failureJson: string): string;
  applyTeachingCatalogFailureJson(failureJson: string): string;
  applyFindingFailureJson(failureJson: string): string;
  previewTimelineEditJson(requestJson: string): string;
  buildPlaybackExcerptJson(rangeJson: string): string;
  close(): void;
}
export interface KotlinFreePracticeExecutor {
  executeJson(requestJson: string): string;
  executeTeachingCatalogJson(requestJson: string): string;
  executeFindingJson(requestJson: string): string;
}
export interface KotlinFreePracticeTimeline {
  toolbarDescriptorJson(): string;
  pixelsPerWholeJson(defaultChordDurationJson: string, defaultChordWidth: number): number;
  projectJson(requestJson: string): string;
  handleJson(sceneJson: string, requestJson: string, inputJson: string): string;
}
export interface KotlinScoreEditor {
  initialUpdateJson(): string;
  interactionCatalogJson(): string;
  dispatchJson(intentJson: string): string;
  close(): void;
}
export interface CreateRendererOptions {
  metadataJson: string;
  glyphNamesJson: string;
  engineVersion?: string;
  engineModule?: EngineModule;
}
export interface CreateRendererUrlOptions extends Omit<CreateRendererOptions, "metadataJson" | "glyphNamesJson"> {
  metadataUrl: string | URL;
  glyphNamesUrl: string | URL;
}
export class MeconRenderer {
  constructor(engine: { renderScoreJson(scoreJson: string): string });
  layout(score: string | object): FrozenScoreBundle;
  layoutFrame(score: string | object): any;
  layoutFreePracticeFrame(score: string | object, timeline: string | object, viewportWidth?: number, pixelsPerWhole?: number): any;
  layoutChordDetailConstruction(construction: string | object): {
    bundle: FrozenScoreBundle;
    mutedElementIds: string[];
    hiddenElementIds: string[];
  } | null;
  transposePreview(targets: object[], stepDelta: number): any;
  noteInputTarget(request: object): any;
  paletteSelectionInfo(selection: object[]): any;
  applyAccidentalToPitch(pitch: object, accidental: string): any;
  restMovePreview(targets: object[]): any;
  renderCanvas(canvas: HTMLCanvasElement, score: string | object, options?: RenderOptions): FrozenScoreBundle;
  renderSvg(score: string | object, options?: RenderOptions): { bundle: FrozenScoreBundle; svg: string };
  renderSvgElement(svg: SVGSVGElement, score: string | object, options?: RenderOptions): FrozenScoreBundle;
}
export function createMeconRenderer(options: CreateRendererOptions): Promise<MeconRenderer>;
export function createMeconRendererFromUrls(options: CreateRendererUrlOptions): Promise<MeconRenderer>;
export interface CreateScoreEditorOptions {
  score: string | object;
  engineModule?: EngineModule;
}
export class MeconScoreEditor {
  constructor(editor: KotlinScoreEditor);
  initialUpdate(): any;
  interactionCatalog(): Array<{
    commandId: string;
    family: "N" | "E" | "P" | "S" | "G" | "T" | "B" | "H" | "F";
    topology: string;
    toolGroup: string;
    successPolicy: string;
  }>;
  dispatch(intent: string | object): any;
  close(): void;
}
export function createMeconScoreEditor(options: CreateScoreEditorOptions): Promise<MeconScoreEditor>;
export interface CreateFreePracticeOptions {
  document: string | object;
  score: string | object;
  engineModule?: EngineModule;
}
export class MeconFreePractice {
  initialUpdate(): any;
  dispatch(intent: string | object): any;
  applyBackgroundResult(result: string | object): any;
  applyTeachingCatalogResult(result: string | object): any;
  applyFindingResult(result: string | object): any;
  applyBackgroundFailure(failure: string | object): any;
  applyTeachingCatalogFailure(failure: string | object): any;
  applyFindingFailure(failure: string | object): any;
  previewTimelineEdit(request: string | object): any;
  buildPlaybackExcerpt(range: string | object): { notes: Array<{
    midiNumber: number; velocity: number; startSeconds: number; durationSeconds: number;
  }>; durationSeconds: number };
  executeBackgroundRequest(request: string | object): any;
  executeTeachingCatalogRequest(request: string | object): any;
  executeFindingRequest(request: string | object): any;
  close(): void;
}
export function createMeconFreePractice(options: CreateFreePracticeOptions): Promise<MeconFreePractice>;
export interface MeconFreePracticeTimeline {
  toolbarDescriptor(): any;
  pixelsPerWhole(defaultChordDuration: any, defaultChordWidth?: number): number;
  project(request: string | object): any;
  handle(scene: string | object, request: string | object, input: string | object): any;
}
export function createMeconFreePracticeTimeline(options?: { engineModule?: EngineModule }):
  Promise<MeconFreePracticeTimeline>;
export interface MeconFreePracticeExecutor {
  execute(request: string | object): any;
  executeTeachingCatalogRequest(request: string | object): any;
  executeFindingRequest(request: string | object): any;
}
export function createMeconFreePracticeExecutor(options?: { engineModule?: EngineModule }):
  Promise<MeconFreePracticeExecutor>;
export function createMeconFreePracticePreset(options?: { engineModule?: EngineModule }):
  Promise<{ document: any; score: any }>;
export * from "@mecon/frozen-score";
