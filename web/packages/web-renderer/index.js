import {
  parseFrozenScore,
  renderCanvas,
  renderSvg,
  renderSvgElement,
} from "@mecon/frozen-score";
import {
  EngineFacade,
  ScoreEditorFacade,
  FreePracticeFacade,
  createEngineFacade,
  createFreePracticeFacade,
  createFreePracticeTimelineFacade,
  createFreePracticeExecutorFacade,
  createFreePracticePresetFacade,
  createScoreEditorFacade,
} from "./engine-core.js";

/**
 * Creates the complete browser engine. Resource text is explicit so the engine is safe in a
 * Worker and does not depend on document, fetch, classpath, or a global font-loading race.
 */
export async function createMeconRenderer(options) {
  return createEngineFacade(options, {
    parseFrozenScore,
    renderCanvas,
    renderSvg,
    renderSvgElement,
  });
}

export async function createMeconRendererFromUrls(options) {
  const [metadataJson, glyphNamesJson] = await Promise.all([
    fetchText(options.metadataUrl),
    fetchText(options.glyphNamesUrl),
  ]);
  return createMeconRenderer({ ...options, metadataJson, glyphNamesJson });
}

/** Create the serial editor intended to live inside one persistent Web Worker. */
export async function createMeconScoreEditor(options) {
  return createScoreEditorFacade(options);
}

/** Create a free-writing session plus a request executor for worker use. */
export async function createMeconFreePractice(options) {
  return createFreePracticeFacade(options);
}

/** Create the shared raw-scene timeline projector/reducer for Worker use. */
export async function createMeconFreePracticeTimeline(options = {}) {
  return createFreePracticeTimelineFacade(options);
}

export async function createMeconFreePracticeExecutor(options = {}) {
  return createFreePracticeExecutorFacade(options);
}

export async function createMeconFreePracticePreset(options = {}) {
  return createFreePracticePresetFacade(options);
}

async function fetchText(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Unable to load ${url}: HTTP ${response.status}`);
  return response.text();
}

export { EngineFacade as MeconRenderer };
export { ScoreEditorFacade as MeconScoreEditor };
export { FreePracticeFacade as MeconFreePractice };

export {
  parseFrozenScore,
  renderCanvas,
  renderSvg,
  renderSvgElement,
} from "@mecon/frozen-score";
