export async function createEngineFacade(options, viewerApi) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Constructor = module.com?.mecon?.web?.MeconWebEngine
    ?? module.MeconWebEngine
    ?? module.default?.com?.mecon?.web?.MeconWebEngine;
  if (!Constructor) throw new Error("MeconWebEngine export was not found in the Kotlin module");
  return new EngineFacade(
    new Constructor(options.metadataJson, options.glyphNamesJson, options.engineVersion ?? "web"),
    viewerApi,
  );
}

export async function createScoreEditorFacade(options) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Constructor = module.com?.mecon?.web?.MeconScoreEditor
    ?? module.MeconScoreEditor
    ?? module.default?.com?.mecon?.web?.MeconScoreEditor;
  if (!Constructor) throw new Error("MeconScoreEditor export was not found in the Kotlin module");
  const scoreJson = typeof options.score === "string" ? options.score : JSON.stringify(options.score);
  return new ScoreEditorFacade(new Constructor(scoreJson));
}

export async function createFreePracticeFacade(options) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Session = module.com?.mecon?.web?.MeconFreePractice
    ?? module.MeconFreePractice
    ?? module.default?.com?.mecon?.web?.MeconFreePractice;
  const Executor = module.com?.mecon?.web?.MeconFreePracticeExecutor
    ?? module.MeconFreePracticeExecutor
    ?? module.default?.com?.mecon?.web?.MeconFreePracticeExecutor;
  if (!Session || !Executor) throw new Error("Free-practice exports were not found in the Kotlin module");
  const documentJson = typeof options.document === "string"
    ? options.document : JSON.stringify(options.document);
  const scoreJson = typeof options.score === "string" ? options.score : JSON.stringify(options.score);
  return new FreePracticeFacade(new Session(documentJson, scoreJson), new Executor());
}

export async function createFreePracticeTimelineFacade(options = {}) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Constructor = module.com?.mecon?.web?.MeconFreePracticeTimeline
    ?? module.MeconFreePracticeTimeline
    ?? module.default?.com?.mecon?.web?.MeconFreePracticeTimeline;
  if (!Constructor) throw new Error("Free-practice timeline export was not found in the Kotlin module");
  const timeline = new Constructor();
  return {
    toolbarDescriptor() {
      return JSON.parse(timeline.toolbarDescriptorJson());
    },
    pixelsPerWhole(defaultChordDuration, defaultChordWidth = 144) {
      return timeline.pixelsPerWholeJson(asJson(defaultChordDuration), defaultChordWidth);
    },
    project(request) {
      return JSON.parse(timeline.projectJson(asJson(request)));
    },
    handle(scene, request, input) {
      return JSON.parse(timeline.handleJson(asJson(scene), asJson(request), asJson(input)));
    },
  };
}

export async function createFreePracticeExecutorFacade(options = {}) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Executor = module.com?.mecon?.web?.MeconFreePracticeExecutor
    ?? module.MeconFreePracticeExecutor
    ?? module.default?.com?.mecon?.web?.MeconFreePracticeExecutor;
  if (!Executor) throw new Error("MeconFreePracticeExecutor export was not found in the Kotlin module");
  const executor = new Executor();
  return {
    execute(request) {
      return JSON.parse(executor.executeJson(asJson(request)));
    },
    executeTeachingCatalogRequest(request) {
      return JSON.parse(executor.executeTeachingCatalogJson(asJson(request)));
    },
    executeFindingRequest(request) {
      return JSON.parse(executor.executeFindingJson(asJson(request)));
    },
  };
}

export async function createFreePracticePresetFacade(options = {}) {
  const module = options.engineModule
    ?? await import("./kotlin/mecon-bridge-web-engine.mjs");
  const Preset = module.com?.mecon?.web?.MeconFreePracticePreset
    ?? module.MeconFreePracticePreset
    ?? module.default?.com?.mecon?.web?.MeconFreePracticePreset;
  if (!Preset) throw new Error("Free-practice preset export was not found in the Kotlin module");
  const preset = new Preset();
  return {
    document: JSON.parse(preset.documentJson()),
    score: JSON.parse(preset.scoreJson()),
    module: {
      id: preset.moduleId(),
      type: preset.moduleType(),
      schemaVersion: preset.moduleSchemaVersion(),
    },
  };
}

export class FreePracticeFacade {
  constructor(session, executor) {
    this.session = session;
    this.executor = executor;
  }

  initialUpdate() {
    return JSON.parse(this.session.initialUpdateJson());
  }

  dispatch(intent) {
    return JSON.parse(this.session.dispatchJson(asJson(intent)));
  }

  applyBackgroundResult(result) {
    return JSON.parse(this.session.applyBackgroundResultJson(asJson(result)));
  }

  applyTeachingCatalogResult(result) {
    return JSON.parse(this.session.applyTeachingCatalogResultJson(asJson(result)));
  }

  applyFindingResult(result) {
    return JSON.parse(this.session.applyFindingResultJson(asJson(result)));
  }

  /**
   * Report that a background worker crashed and will never answer. `failure` is
   * `{ requestId, reason }`; the session owns the rollback and unlocks the workbench.
   */
  applyBackgroundFailure(failure) {
    return JSON.parse(this.session.applyBackgroundFailureJson(asJson(failure)));
  }

  applyTeachingCatalogFailure(failure) {
    return JSON.parse(this.session.applyTeachingCatalogFailureJson(asJson(failure)));
  }

  applyFindingFailure(failure) {
    return JSON.parse(this.session.applyFindingFailureJson(asJson(failure)));
  }

  previewTimelineEdit(request) {
    return JSON.parse(this.session.previewTimelineEditJson(asJson(request)));
  }

  buildPlaybackExcerpt(range) {
    return JSON.parse(this.session.buildPlaybackExcerptJson(asJson(range)));
  }

  executeBackgroundRequest(request) {
    return JSON.parse(this.executor.executeJson(asJson(request)));
  }

  executeTeachingCatalogRequest(request) {
    return JSON.parse(this.executor.executeTeachingCatalogJson(asJson(request)));
  }

  executeFindingRequest(request) {
    return JSON.parse(this.executor.executeFindingJson(asJson(request)));
  }

  close() {
    this.session.close();
  }
}

function asJson(value) {
  return typeof value === "string" ? value : JSON.stringify(value);
}

export class ScoreEditorFacade {
  constructor(editor) {
    this.editor = editor;
  }

  initialUpdate() {
    return JSON.parse(this.editor.initialUpdateJson());
  }

  interactionCatalog() {
    return JSON.parse(this.editor.interactionCatalogJson());
  }

  dispatch(intent) {
    const intentJson = typeof intent === "string" ? intent : JSON.stringify(intent);
    return JSON.parse(this.editor.dispatchJson(intentJson));
  }

  close() {
    this.editor.close();
  }
}

export class EngineFacade {
  constructor(engine, viewerApi) {
    this.engine = engine;
    this.viewerApi = viewerApi;
  }

  layout(score) {
    return this.layoutFrame(score).bundle;
  }

  layoutFrame(score) {
    const scoreJson = typeof score === "string" ? score : JSON.stringify(score);
    if (typeof this.engine.renderScoreFrameJson === "function") {
      const frame = JSON.parse(this.engine.renderScoreFrameJson(scoreJson));
      return {
        ...frame,
        bundle: this.viewerApi ? this.viewerApi.parseFrozenScore(frame.bundle) : frame.bundle,
      };
    }
    const bundle = this.viewerApi
      ? this.viewerApi.parseFrozenScore(this.engine.renderScoreJson(scoreJson))
      : JSON.parse(this.engine.renderScoreJson(scoreJson));
    return { bundle, geometry: null };
  }

  layoutFreePracticeFrame(score, timeline, viewportWidth = 0, pixelsPerWhole = 576) {
    const scoreJson = typeof score === "string" ? score : JSON.stringify(score);
    if (typeof this.engine.renderFreePracticeFrameJson !== "function") return this.layoutFrame(score);
    const timelineJson = typeof timeline === "string" ? timeline : JSON.stringify(timeline);
    const frameJson = viewportWidth > 0 && typeof this.engine.renderFreePracticeFrameForWidthScaledJson === "function"
      ? this.engine.renderFreePracticeFrameForWidthScaledJson(scoreJson, timelineJson, viewportWidth, pixelsPerWhole)
      : typeof this.engine.renderFreePracticeFrameScaledJson === "function"
        ? this.engine.renderFreePracticeFrameScaledJson(scoreJson, timelineJson, pixelsPerWhole)
        : viewportWidth > 0 && typeof this.engine.renderFreePracticeFrameForWidthJson === "function"
          ? this.engine.renderFreePracticeFrameForWidthJson(scoreJson, timelineJson, viewportWidth)
          : this.engine.renderFreePracticeFrameJson(scoreJson, timelineJson);
    const frame = JSON.parse(frameJson);
    return {
      ...frame,
      bundle: this.viewerApi ? this.viewerApi.parseFrozenScore(frame.bundle) : frame.bundle,
    };
  }

  layoutChordDetailConstruction(construction) {
    if (typeof this.engine.renderChordDetailConstructionJson !== "function") return null;
    const frame = JSON.parse(this.engine.renderChordDetailConstructionJson(asJson(construction)));
    return {
      ...frame,
      bundle: this.viewerApi ? this.viewerApi.parseFrozenScore(frame.bundle) : frame.bundle,
    };
  }

  transposePreview(targets, stepDelta) {
    if (typeof this.engine.transposePreviewJson !== "function") return null;
    return JSON.parse(this.engine.transposePreviewJson(asJson(targets), stepDelta));
  }

  restMovePreview(targets) {
    if (typeof this.engine.restMovePreviewJson !== "function") return null;
    return JSON.parse(this.engine.restMovePreviewJson(asJson(targets)));
  }

  noteInputTarget(request) {
    if (typeof this.engine.noteInputTargetJson !== "function") return null;
    return JSON.parse(this.engine.noteInputTargetJson(asJson(request)));
  }

  paletteSelectionInfo(selection) {
    if (typeof this.engine.paletteSelectionInfoJson !== "function") return null;
    return JSON.parse(this.engine.paletteSelectionInfoJson(asJson(selection ?? [])));
  }

  applyAccidentalToPitch(pitch, accidental) {
    return JSON.parse(this.engine.applyAccidentalToPitchJson(asJson(pitch), accidental));
  }

  renderCanvas(canvas, score, options) {
    if (!this.viewerApi) throw new Error("Canvas rendering is unavailable without the viewer API");
    const bundle = this.layout(score);
    this.viewerApi.renderCanvas(canvas, bundle, options);
    return bundle;
  }

  renderSvg(score, options) {
    if (!this.viewerApi) throw new Error("SVG rendering is unavailable without the viewer API");
    const bundle = this.layout(score);
    return { bundle, svg: this.viewerApi.renderSvg(bundle, options) };
  }

  renderSvgElement(svg, score, options) {
    if (!this.viewerApi) throw new Error("SVG rendering is unavailable without the viewer API");
    const bundle = this.layout(score);
    this.viewerApi.renderSvgElement(svg, bundle, options);
    return bundle;
  }
}
