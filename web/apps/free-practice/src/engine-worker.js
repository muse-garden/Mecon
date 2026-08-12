import {
  createMeconFreePractice,
  createMeconFreePracticeTimeline,
  createMeconFreePracticePreset,
  createMeconRenderer,
  createMeconScoreEditor,
} from "@mecon/web-renderer";

// Static module imports include the Kotlin/JS engine chunk. Reaching this line means the browser
// has downloaded and evaluated the Worker and the shell can distinguish that from document layout.
self.postMessage({ type: "ready" });

let editor = null;
let renderer = null;
let freePractice = null;
let timelineController = null;
let timelineScene = null;
let timelineRequest = null;
const searchWorkers = new Map();
let catalogWorker = null;
let findingWorker = null;
let pendingCatalogRequestId = null;
let pendingFindingRequestId = null;
let rendered = null;
let renderedTimelineKey = null;
let queue = Promise.resolve();
let viewportWidth = 0;
let latestFreePracticeUpdate = null;
let pendingTimelineMove = null;
let timelineMoveFlushQueued = false;
let timelineGridUnit = { numerator: 1, denominator: 8 };
let timelineDefaultChordDuration = { numerator: 1, denominator: 4 };

self.onmessage = (event) => {
  if (event.data.type === "timelineInput" && event.data.input?.type === "MOVE") {
    pendingTimelineMove = event.data;
    if (!timelineMoveFlushQueued) {
      timelineMoveFlushQueued = true;
      enqueue({ type: "timelineMoveFlush" });
    }
    return;
  }
  enqueue(event.data);
};

/**
 * Every message runs on one serial queue, and a failure always answers the `clientRequestId` that
 * caused it. The shell tracks one in-flight practice intent at a time, so an error that carried no
 * request id would leave it waiting forever and silently freeze the whole workbench.
 */
const RESULT_FAILURE_TYPES = {
  backgroundResult: "backgroundFailure",
  teachingCatalogResult: "teachingCatalogFailure",
  findingResult: "findingFailure",
};

function enqueue(message) {
  queue = queue.then(() => handle(message)).catch((error) => {
    const reason = error?.message ?? String(error);
    self.postMessage({
      type: "error",
      message: reason,
      clientRequestId: message.clientRequestId ?? null,
    });
    // Applying a result is itself engine work and can throw. The request stays active in the
    // session when it does, so route the same crash back through the failure channel; otherwise
    // the workbench keeps waiting for an answer that was already lost.
    const failureType = RESULT_FAILURE_TYPES[message.type];
    if (failureType && message.result?.requestId != null) {
      enqueue({ type: failureType, failure: { requestId: message.result.requestId, reason } });
    }
  });
}

async function handle(message) {
  switch (message.type) {
    case "new": {
      closeCurrent();
      const [preset, metadataJson, glyphNamesJson] = await Promise.all([
        createMeconFreePracticePreset(),
        fetchText(message.metadataUrl),
        fetchText(message.glyphNamesUrl),
      ]);
      freePractice = await createMeconFreePractice(preset);
      timelineController = await createMeconFreePracticeTimeline();
      self.postMessage({ type: "practiceToolbarDescriptor", descriptor: timelineController.toolbarDescriptor() });
      renderer = await createMeconRenderer({
        metadataJson,
        glyphNamesJson,
        engineVersion: message.engineVersion ?? "web-editor",
      });
      publishFreePractice(
        freePractice.initialUpdate(), null, { module: preset.module }, true,
        message.documentRequestId ?? null,
      );
      break;
    }
    case "open": {
      closeCurrent();
      const [metadataJson, glyphNamesJson] = await Promise.all([
        fetchText(message.metadataUrl),
        fetchText(message.glyphNamesUrl),
      ]);
      if (message.document) {
        freePractice = await createMeconFreePractice({
          document: message.document,
          score: message.score,
        });
        timelineController = await createMeconFreePracticeTimeline();
        self.postMessage({ type: "practiceToolbarDescriptor", descriptor: timelineController.toolbarDescriptor() });
      } else {
        editor = await createMeconScoreEditor({ score: message.score });
      }
      renderer = await createMeconRenderer({
        metadataJson,
        glyphNamesJson,
        engineVersion: message.engineVersion ?? "web-editor",
      });
      if (freePractice) publishFreePractice(
        freePractice.initialUpdate(), null, null, true, message.documentRequestId ?? null,
      );
      else publish(editor.initialUpdate(), message.documentRequestId ?? null);
      break;
    }
    case "dispatch":
      requireOpen();
      if (freePractice) publishFreePractice(freePractice.dispatch(message.intent), message.clientRequestId);
      else publish(editor.dispatch(message.intent));
      break;
    case "resize":
      if (freePractice && latestFreePracticeUpdate && Math.abs(viewportWidth - message.width) >= 2) {
        viewportWidth = Math.max(0, Number(message.width) || 0);
        rendered = null;
        publishFreePractice(latestFreePracticeUpdate, null, null, false);
      }
      break;
    case "timelinePreferences":
      timelineGridUnit = message.gridUnit ?? timelineGridUnit;
      timelineDefaultChordDuration = message.defaultChordDuration ?? timelineDefaultChordDuration;
      if (latestFreePracticeUpdate) publishTimelineScene(latestFreePracticeUpdate, timelineRequest?.gesture ?? null);
      break;
    case "timelineInput":
      handleTimelineInput(message.input);
      break;
    case "timelineMoveFlush": {
      const latest = pendingTimelineMove;
      pendingTimelineMove = null;
      timelineMoveFlushQueued = false;
      if (latest) handleTimelineInput(latest.input);
      break;
    }
    case "transposePreview":
      self.postMessage({
        type: "transposePreview",
        requestId: message.requestId,
        preview: renderer?.transposePreview(message.targets, message.stepDelta) ?? null,
      });
      break;
    case "restMovePreview":
      self.postMessage({
        type: "restMovePreview",
        requestId: message.requestId,
        preview: renderer?.restMovePreview(message.targets) ?? null,
      });
      break;
    case "noteInputTarget":
      self.postMessage({
        type: "noteInputTarget",
        requestId: message.requestId,
        target: renderer?.noteInputTarget(message.request) ?? null,
      });
      break;
    case "backgroundResult":
      if (!freePractice) throw new Error("No free-practice session is open");
      publishFreePractice(freePractice.applyBackgroundResult(message.result));
      break;
    case "teachingCatalogResult":
      if (!freePractice) throw new Error("No free-practice session is open");
      publishFreePractice(freePractice.applyTeachingCatalogResult(message.result));
      break;
    case "findingResult":
      if (!freePractice) throw new Error("No free-practice session is open");
      publishFreePractice(freePractice.applyFindingResult(message.result));
      break;
    case "backgroundFailure":
      if (!freePractice) break;
      publishFreePractice(freePractice.applyBackgroundFailure(message.failure));
      break;
    case "teachingCatalogFailure":
      if (!freePractice) break;
      publishFreePractice(freePractice.applyTeachingCatalogFailure(message.failure));
      break;
    case "findingFailure":
      if (!freePractice) break;
      publishFreePractice(freePractice.applyFindingFailure(message.failure));
      break;
    case "playback":
      if (!freePractice) throw new Error("No free-practice session is open");
      self.postMessage({
        type: "playbackExcerpt",
        excerpt: freePractice.buildPlaybackExcerpt(message.range),
        range: message.range,
        playbackRequestId: message.playbackRequestId,
        trackCursor: message.trackCursor,
      });
      break;
    case "close":
      closeCurrent();
      self.postMessage({ type: "closed" });
      break;
    default:
      throw new Error(`Unknown worker message: ${message.type}`);
  }
}

/**
 * Layout input is `(score, timeline)`, so the session's `scoreChanged` alone cannot decide whether
 * the cached frame is still valid: a pure timeline edit leaves the score byte-identical yet moves
 * every slot boundary the aligned time axis is built from. Compare the timeline projection too.
 */
function publishFreePractice(
  update, clientRequestId = null, newDocument = null, runRequests = true, documentRequestId = null,
) {
  latestFreePracticeUpdate = update;
  const scoreUpdate = update.score;
  const timelineKey = JSON.stringify(update.timeline ?? null);
  if (scoreUpdate.scoreChanged !== false || !rendered || timelineKey !== renderedTimelineKey) {
    rendered = renderer.layoutFreePracticeFrame(scoreUpdate.score, update.timeline, viewportWidth);
    renderedTimelineKey = timelineKey;
  }
  // A resize or same-revision background frame may arrive between DOWN and MOVE. Preserve the
  // active gesture only while the authoritative revision is unchanged; a real commit clears it.
  const activeGesture = timelineRequest?.revision === update.revision
    ? timelineRequest.gesture ?? null
    : null;
  publishTimelineScene(update, activeGesture);
  const displayedUpdate = withChordDetailBundles(update);
  self.postMessage({
    type: "freePracticeFrame",
    update: displayedUpdate,
    bundle: rendered.bundle,
    geometry: rendered.geometry,
    timeAxis: rendered.timeAxis,
    playbackAnchors: rendered.playbackAnchors,
    clientRequestId,
    newDocument,
    documentRequestId,
  });
  if (runRequests) {
    for (const request of update.requests ?? []) runBackground(request);
    const catalogRequest = update.catalogRequests?.at(-1);
    if (catalogRequest) runTeachingCatalog(catalogRequest);
    const findingRequest = update.findingRequests?.at(-1);
    if (findingRequest) runFindings(findingRequest);
  }
}

function withChordDetailBundles(update) {
  const detail = update.plan?.chordDetail;
  if (!detail || !renderer) return update;
  const decorateRoute = (route) => route.construction
    ? (() => {
        const preview = renderer.layoutChordDetailConstruction(route.construction);
        return {
        ...route,
        construction: {
          ...route.construction,
          bundle: preview?.bundle ?? null,
          mutedElementIds: preview?.mutedElementIds ?? [],
          hiddenElementIds: preview?.hiddenElementIds ?? [],
        },
      };
    })()
    : route;
  return {
    ...update,
    plan: {
      ...update.plan,
      chordDetail: {
        ...detail,
        routes: (detail.routes ?? []).map(decorateRoute),
        explanations: (detail.explanations ?? []).map((explanation) => ({
          ...explanation,
          routes: (explanation.routes ?? []).map(decorateRoute),
        })),
      },
    },
  };
}

function publishTimelineScene(update, gesture, timeline = update.timeline) {
  if (!timelineController || !rendered?.timeAxis) return;
  timelineRequest = {
    revision: update.revision,
    axisRevision: rendered.timeAxis.revision,
    viewportWidth: Math.max(0, viewportWidth),
    scrollLeft: timelineRequest?.scrollLeft ?? 0,
    contentOriginX: rendered.timeAxis.contentOriginX ?? 0,
    axisAnchors: rendered.timeAxis.anchors,
    axisContentEndX: rendered.timeAxis.contentEndX,
    axisSurfaceWidth: rendered.timeAxis.surfaceWidth,
    timeline,
    selectedSlotId: update.selection?.slotId ?? update.selectedSlotId ?? null,
    selectedTonalLayoutId: update.selection?.tonalLayoutId ?? null,
    selectedIdiomId: update.selection?.idiomInstanceId ?? null,
    gridUnit: timelineGridUnit,
    defaultChordDuration: timelineDefaultChordDuration,
    showRemoveAction: false,
    gesture,
  };
  timelineScene = timelineController.project(timelineRequest);
  self.postMessage({ type: "timelineScene", scene: timelineScene });
}

function handleTimelineInput(input) {
  if (!timelineController || !timelineScene || !timelineRequest || !latestFreePracticeUpdate) return;
  if (input.type === "WHEEL" && Number.isFinite(input.deltaX)) {
    timelineRequest = { ...timelineRequest, scrollLeft: Math.max(0, timelineRequest.scrollLeft + input.deltaX) };
    timelineScene = timelineController.project(timelineRequest);
    self.postMessage({ type: "timelineScene", scene: timelineScene });
    return;
  }
  const result = timelineController.handle(timelineScene, timelineRequest, input);
  if (result.gesture) timelineRequest = { ...timelineRequest, gesture: result.gesture };
  else if (input.type === "UP" || input.type === "CANCEL" || input.key === "Escape") {
    timelineRequest = { ...timelineRequest, gesture: null };
  }
  if (result.previewEdit) {
    const preview = freePractice.previewTimelineEdit({
      requestId: Date.now(),
      baseRevision: latestFreePracticeUpdate.revision,
      edit: result.previewEdit,
    });
    if (preview.accepted && preview.timeline) publishTimelineScene(latestFreePracticeUpdate, result.gesture, preview.timeline);
    else self.postMessage({ type: "timelineInteraction", result: { ...result, reasonKey: preview.reasonKey } });
  } else if (result.accepted && timelineRequest) {
    timelineScene = timelineController.project(timelineRequest);
    self.postMessage({ type: "timelineScene", scene: timelineScene });
  }
  self.postMessage({ type: "timelineInteraction", result });
}

/**
 * Findings and the teaching catalog run on one resident worker each. Terminating a worker reloads
 * the whole Kotlin/JS engine on the next request, which on a per-selection request cadence means a
 * cold engine start per click. Superseded answers are cheap to drop instead: the session validates
 * `requestId / baseRevision / fingerprint` and reports STALE_BACKGROUND_RESULT.
 */
function residentSearchWorker(current, resultType, pendingRequestId, onDead) {
  if (current) return current;
  const worker = new Worker(new URL("./search-worker.js", import.meta.url), { type: "module" });
  const fail = (reason, requestId = pendingRequestId()) => {
    self.postMessage({ type: "error", message: reason });
    if (requestId != null) {
      enqueue({ type: RESULT_FAILURE_TYPES[resultType], failure: { requestId, reason } });
    }
  };
  worker.onmessage = ({ data }) => {
    if (data.type === resultType) enqueue({ type: resultType, result: data.result });
    else if (data.type === "error") fail(data.message ?? "后台搜索失败", data.requestId ?? pendingRequestId());
  };
  // A resident worker that dies (engine chunk failed to load, out of memory) can never answer
  // again. Drop the reference so the next request starts a fresh one instead of posting into a
  // dead worker forever.
  worker.onerror = (event) => {
    onDead();
    worker.terminate();
    fail(event?.message || "后台搜索 Worker 已崩溃");
  };
  return worker;
}

function runFindings(request) {
  pendingFindingRequestId = request.requestId;
  findingWorker = residentSearchWorker(
    findingWorker,
    "findingResult",
    () => pendingFindingRequestId,
    () => { findingWorker = null; },
  );
  findingWorker.postMessage({ type: "executeFindings", request });
}

/**
 * Free writing is a long, non-cooperative CPU search, so terminating the worker stays the only way
 * to cancel it — the reload cost is paid once per solve rather than once per selection.
 */
function runBackground(request) {
  searchWorkers.get(request.kind)?.terminate();
  const worker = new Worker(new URL("./search-worker.js", import.meta.url), { type: "module" });
  searchWorkers.set(request.kind, worker);
  // A crashed solve must reach the session, not just the status bar: the request stays active
  // until it does, and the workbench stays locked in RUNNING with the pending workspace showing.
  const fail = (reason) => {
    self.postMessage({ type: "error", message: reason });
    enqueue({ type: "backgroundFailure", failure: { requestId: request.requestId, reason } });
  };
  worker.onmessage = ({ data }) => {
    if (data.type === "backgroundResult") {
      self.postMessage({ type: "backgroundProgress", requestId: request.requestId });
      enqueue({ type: "backgroundResult", result: data.result });
    } else if (data.type === "error") {
      fail(data.message ?? "自动写作失败");
    }
  };
  worker.onerror = (event) => fail(event?.message || "自动写作 Worker 已崩溃");
  worker.postMessage({ type: "execute", request });
}

function runTeachingCatalog(request) {
  pendingCatalogRequestId = request.requestId;
  catalogWorker = residentSearchWorker(
    catalogWorker,
    "teachingCatalogResult",
    () => pendingCatalogRequestId,
    () => { catalogWorker = null; },
  );
  catalogWorker.postMessage({ type: "executeTeachingCatalog", request });
}

/**
 * Selection changes, clipboard copies, no-ops and rejected intents leave the score byte-identical,
 * so re-laying out the whole score for them is pure waste on a large document. The session reports
 * `scoreChanged` authoritatively — never infer it from the effect kind here.
 */
function publish(update, documentRequestId = null) {
  if (update.scoreChanged !== false || !rendered) {
    rendered = renderer.layoutFrame(update.score);
  }
  self.postMessage({
    type: "frame", update, bundle: rendered.bundle, geometry: rendered.geometry, documentRequestId,
  });
}

function requireOpen() {
  if ((!editor && !freePractice) || !renderer) throw new Error("No score is open");
}

function closeCurrent() {
  editor?.close();
  freePractice?.close();
  for (const worker of searchWorkers.values()) worker.terminate();
  searchWorkers.clear();
  catalogWorker?.terminate();
  findingWorker?.terminate();
  editor = null;
  freePractice = null;
  timelineController = null;
  timelineScene = null;
  timelineRequest = null;
  catalogWorker = null;
  findingWorker = null;
  pendingCatalogRequestId = null;
  pendingFindingRequestId = null;
  renderer = null;
  latestFreePracticeUpdate = null;
  renderedTimelineKey = null;
  rendered = null;
  renderedTimelineKey = null;
  pendingTimelineMove = null;
  timelineMoveFlushQueued = false;
}

async function fetchText(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Unable to load ${url}: HTTP ${response.status}`);
  return response.text();
}
