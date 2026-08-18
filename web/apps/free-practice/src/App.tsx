import React, { useEffect, useRef, useState } from "react";
import {
  FilePlus2,
  FolderOpen,
  Save,
} from "lucide-react";
import {
  loadMeconDocument,
  loadMusicFont,
  writeMeconDocument,
} from "@mecon/frozen-score";
import {
  FULL_SCORE_EDITOR_TOOLBAR,
  formatQuarterBeat,
  toolbarProfileFromDescriptor,
} from "@mecon/web-renderer/editor";
import { ScoreEditor, useScoreEditorController } from "@mecon/web-renderer/editor/react";
import { createRecoveryWriter, loadRecoveryState, saveRecovery } from "./recovery.js";
import { createPracticeIntentQueue } from "./practice-intents.js";
import { createNewPracticeDocument } from "./new-document.js";
import { saveMeconDocument } from "./save-document.js";
import { HarmonyTimeline } from "./HarmonyTimeline.jsx";
import { PracticeFeedbackPanel } from "./PracticeFeedbackPanel.jsx";
import {
  AudioSettingsDialog,
  LoadingOverlay,
  type AudioSettings,
} from "./AudioSettingsDialog.tsx";
import { PracticePlanPanel } from "./PracticePlanPanel.jsx";
import {
  derivePracticeNoteSelection,
  PracticeNoteProperties,
} from "./PracticeNoteProperties.tsx";
import { PracticeTopToolbar } from "./PracticeTopToolbar.tsx";
import { PracticePlaybackController } from "./PracticePlaybackController.ts";
import { ResizableWorkbenchSide } from "./ResizableWorkbenchSide.jsx";
import { ResizableLowerWorkbench } from "./ResizableLowerWorkbench.jsx";
import { appAssetUrl } from "./paths.js";
import { PlaybackInstrument } from "./rhody-playback.js";

// Keep the classic branch available for the future Web piano-roll layout switch.
const DEFAULT_WEB_PRACTICE_LAYOUT = "writing-with-lower-panels";
const DEFAULT_AUDIO_SETTINGS: AudioSettings = {
  instrument: PlaybackInstrument.piano,
  organPreset: 0,
  reverbEnabled: true,
};

/**
 * Effects the session raises when a background engine crashed. The session has already rolled the
 * workbench back to its last committed state by the time one of these frames arrives; the shell
 * only has to say so instead of clearing the alert like it does for ordinary frames.
 */
const PRACTICE_FAILURE_ALERTS = {
  "freePractice.writing.failed": "自动写作出错，已回退到上一个正常状态",
  "freePractice.writing.alternateFailed": "备选写法搜索出错，已保留当前写作结果",
  "freePractice.catalog.failed": "教学目录加载出错",
  "freePractice.findings.failed": "规则检查出错",
};

function practiceEffectAlert(effect) {
  const text = PRACTICE_FAILURE_ALERTS[effect?.messageKey];
  if (!text) return "";
  const reason = effect.arguments?.reason;
  return reason ? `${text}：${reason}` : text;
}

function ToolbarIcon({ icon: Icon, size = 16 }) {
  return <Icon aria-hidden="true" size={size} strokeWidth={1.8} />;
}

function addFraction(left, right) {
  const numerator = left.numerator * right.denominator + right.numerator * left.denominator;
  const denominator = left.denominator * right.denominator;
  const gcd = (a, b) => (b ? gcd(b, a % b) : Math.abs(a));
  const divisor = gcd(numerator, denominator) || 1;
  return { numerator: numerator / divisor, denominator: denominator / divisor };
}

export function App() {
  const workerRef = useRef(null);
  const documentRef = useRef(null);
  const scoreIdRef = useRef(null);
  const moduleIdRef = useRef(null);
  const freePracticeRef = useRef(false);
  const unsavedRef = useRef(false);
  const documentRequestIdRef = useRef(0);
  const completedDocumentRequestIdRef = useRef(0);
  const pendingDocumentRecoveryRef = useRef(new Map());
  const latestRef = useRef({ update: null, bundle: null, practiceUpdate: null });
  const midiAccessRef = useRef(null);
  const stepInsertRef = useRef(null);
  const playbackControllerRef = useRef<PracticePlaybackController | null>(null);
  if (!playbackControllerRef.current) playbackControllerRef.current = new PracticePlaybackController({
    worker: () => workerRef.current,
    frame: () => latestRef.current,
    practiceUpdate: () => latestRef.current?.practiceUpdate,
    latestFrame: () => latestRef.current,
  }, DEFAULT_AUDIO_SETTINGS);
  const playback = playbackControllerRef.current;
  const practiceIntentsRef = useRef(null);
  const notationPreviewRequestIdRef = useRef(0);
  const notationPreviewCallbackRef = useRef(null);
  const noteInputRequestIdRef = useRef(0);
  const noteInputCallbacksRef = useRef(new Map());
  const editorInputRef = useRef(null);
  const noteInputPreviewRef = useRef(null);
  const practiceViewportWidthRef = useRef(0);
  const documentLoadingRef = useRef(false);
  const timelineTraceRef = useRef([]);
  const defaultChordBeatsRef = useRef(1);
  const practiceToolbarDescriptorRef = useRef(null);
  const [frame, setFrame] = useState(null);
  const [practiceUpdate, setPracticeUpdate] = useState(null);
  const [timelineScene, setTimelineScene] = useState(null);
  const [practiceToolbarDescriptor, setPracticeToolbarDescriptor] = useState(null);
  const [practiceCatalogChoiceId, setPracticeCatalogChoiceId] = useState("");
  const [mobileTab, setMobileTab] = useState("score");
  const [sharedScrollLeft, setSharedScrollLeft] = useState(0);
  const [gridDenominator, setGridDenominator] = useState(8);
  const [defaultChordBeats, setDefaultChordBeats] = useState(1);
  const [timelineDisplayMode, setTimelineDisplayMode] = useState("FULL");
  const [playbackRate, setPlaybackRate] = useState(1);
  const [audioSettings, setAudioSettings] = useState(DEFAULT_AUDIO_SETTINGS);
  const [audioSettingsOpen, setAudioSettingsOpen] = useState(false);
  const [status, setStatus] = useState("请选择 .mecon 文件");
  const [practiceAlert, setPracticeAlert] = useState("");
  const [loadingMessage, setLoadingMessage] = useState("正在加载练习引擎…");
  const reportError = (message) => {
    documentLoadingRef.current = false;
    setLoadingMessage(null);
    setStatus(message);
    setPracticeAlert(message);
  };
  const editor = useScoreEditorController({
    frame, dispatch, requestTransposePreview, requestRestMovePreview, requestNoteInputTarget,
  });
  editorInputRef.current = editor.input;
  noteInputPreviewRef.current = editor.noteInputPreview;

  const inputState = editor.input;
  const {
    insertMeasure, setInsertMeasure, insertBeat, setInsertBeat, insertDuration, insertDots,
    graceMode, tupletCount,
    stepInputEnabled, setMidiStatus, setEditorTool,
  } = inputState;
  const editorCommands = editor.commands;
  const selectedPracticeSlotId = practiceUpdate?.selection?.slotId ?? practiceUpdate?.selectedSlotId;
  const practiceNoteSelection = derivePracticeNoteSelection(practiceUpdate);
  const { roleViewByRef, selectedNoteheads } = practiceNoteSelection;
  const practiceElementTints = Object.fromEntries((frame?.bundle?.surfaces ?? [])
    .flatMap((surface) => surface.elements)
    .filter((element) => element.type === "NOTEHEAD" && element.eventId != null
      && element.metadata?.pitchIndex != null)
    .map((element) => [element.id, roleViewByRef.get(
      `${element.eventId}:${Number(element.metadata.pitchIndex)}`,
    )])
    .filter(([, item]) => item?.inferredRole != null || item?.explicitRole != null)
    .map(([id, item]) => [id, item.conflict ? "#dc3737"
      : (item.explicitRole ?? item.inferredRole) === "CHORD_TONE" ? "#41aa5f" : "#e19b2d"]));
  const practiceElementCenterMarkers = Object.fromEntries((frame?.bundle?.surfaces ?? [])
    .flatMap((surface) => surface.elements)
    .filter((element) => element.type === "NOTEHEAD" && element.eventId != null
      && element.metadata?.pitchIndex != null)
    .map((element) => [element, roleViewByRef.get(
      `${element.eventId}:${Number(element.metadata.pitchIndex)}`,
    )])
    .filter(([, item]) => item?.locked)
    .map(([element]) => [element.id, {
      color: element.metadata?.noteheadFilled === "true" ? "#fff" : "#000",
    }]));

  useEffect(() => {
    const choices = practiceUpdate?.catalog?.chordChoices ?? [];
    const selectedChordId = practiceUpdate?.plan?.selectedChord?.id;
    setPracticeCatalogChoiceId((current) => selectedChordId
      ?? choices.find((item) => item.id === current)?.id
      ?? choices[0]?.id
      ?? "");
  }, [practiceUpdate?.catalog?.requestKey, selectedPracticeSlotId,
    practiceUpdate?.plan?.selectedChord?.id]);

  useEffect(() => {
    let disposed = false;
    const worker = new Worker(new URL("./engine-worker.js", import.meta.url), { type: "module" });
    const recoveryWriter = createRecoveryWriter({
      write: async () => {
        const bytes = currentArchiveBytes();
        if (bytes) await saveRecovery(bytes);
      },
      onError: () => reportError("自动恢复写入失败"),
    });
    workerRef.current = worker;
    practiceIntentsRef.current = createPracticeIntentQueue({
      send: ({ clientRequestId, intent }) => worker.postMessage({ type: "dispatch", clientRequestId, intent }),
    });
    worker.onmessage = async ({ data }) => {
      if (disposed) return;
      if (data.type === "ready") {
        if (!documentLoadingRef.current) setLoadingMessage(null);
        return;
      }
      if (data.type === "error") {
        setLoadingMessage(null);
        reportError(data.message);
        // A failed dispatch never produces a frame, so the queue has to be released here;
        // otherwise it stalls and every later edit is silently dropped.
        practiceIntentsRef.current?.settle(data.clientRequestId, latestRef.current?.practiceUpdate);
        return;
      }
      if (data.type === "playbackExcerpt") {
        playback.handleExcerpt(data);
        return;
      }
      if (data.type === "timelineScene") {
        setTimelineScene(data.scene);
        return;
      }
      if (data.type === "practiceToolbarDescriptor") {
        practiceToolbarDescriptorRef.current = data.descriptor;
        setPracticeToolbarDescriptor(data.descriptor);
        return;
      }
      if (data.type === "timelineInteraction") {
        const result = data.result;
        timelineTraceRef.current.push({ stage: "interaction", result });
        if (timelineTraceRef.current.length > 80) timelineTraceRef.current.shift();
        const queueTimelineIntent = (intent) => {
          const update = latestRef.current?.practiceUpdate;
          if (update) practiceIntentsRef.current?.push(intent, update);
        };
        if (!result?.accepted) {
          if (result?.reasonKey && !result.ignored) reportError(`时间轴：${result.reasonKey}`);
          return;
        }
        if (result.commitEdit) queueTimelineIntent({ type: "timelineEdit", edit: result.commitEdit });
        else if (result.appendAt) queueTimelineIntent({
          type: "insertChordRange", onset: result.appendAt,
          duration: result.appendDuration ?? { numerator: defaultChordBeatsRef.current, denominator: 4 },
        });
        else if (result.selectSlotId && !result.gesture) queueTimelineIntent({ type: "selectSlot", slotId: result.selectSlotId });
        else if (result.removeSlotId) queueTimelineIntent({ type: "removeChordRange", slotId: result.removeSlotId });
        else if (result.selectTonalLayoutId && !result.gesture) queueTimelineIntent({ type: "selectTonalLayout", tonalLayoutId: result.selectTonalLayoutId });
        else if (result.selectIdiomId) queueTimelineIntent({ type: "selectIdiom", idiomInstanceId: result.selectIdiomId });
        return;
      }
      if (data.type === "transposePreview" || data.type === "restMovePreview") {
        if (data.requestId === notationPreviewRequestIdRef.current) {
          notationPreviewCallbackRef.current?.(data.preview);
        }
        return;
      }
      if (data.type === "noteInputTarget") {
        const callback = noteInputCallbacksRef.current.get(data.requestId);
        noteInputCallbacksRef.current.delete(data.requestId);
        callback?.(data.target ?? null);
        return;
      }
      if (data.type !== "frame" && data.type !== "freePracticeFrame") return;
      if (data.documentRequestId != null
        && data.documentRequestId !== documentRequestIdRef.current) {
        // New/open requests are serialized by the Worker, but the user may choose a file while the
        // startup "new" request is still computing. Never let that older frame replace the newer
        // archive reference; otherwise edits target the opened session while Save writes the
        // discarded new-document container and silently drops every sidecar entry.
        pendingDocumentRecoveryRef.current.delete(data.documentRequestId);
        return;
      }
      const editorFrame = data.type === "freePracticeFrame"
        ? { ...data, practiceUpdate: data.update, update: data.update.score }
        : data;
      const replacement = data.documentRequestId == null
        ? null
        : pendingDocumentRecoveryRef.current.get(data.documentRequestId) ?? null;
      if (data.documentRequestId != null) {
        pendingDocumentRecoveryRef.current.delete(data.documentRequestId);
      }
      notationPreviewRequestIdRef.current += 1;
      notationPreviewCallbackRef.current = null;
      if (data.newDocument) {
        const created = createNewPracticeDocument({
          score: data.update.score.score,
          document: data.update.document,
          module: data.newDocument.module,
        });
        documentRef.current = created;
        scoreIdRef.current = created.manifest.activeScoreId;
        moduleIdRef.current = data.newDocument.module.id;
        editor.setSurfaceIndex(0);
      }
      if (replacement) {
        unsavedRef.current = replacement.unsaved;
      } else if (data.type === "freePracticeFrame"
        ? data.update.documentChanged === true
        : data.update.scoreChanged === true) {
        unsavedRef.current = true;
      }
      freePracticeRef.current = data.type === "freePracticeFrame";
      latestRef.current = editorFrame;
      const recoveryScheduled = replacement ? false : recoveryWriter.schedule(editorFrame);
      if (replacement) {
        const bytes = currentArchiveBytes();
        if (bytes) {
          try {
            await saveRecovery(bytes, replacement.unsaved);
          } catch (error) {
            reportError(`自动恢复写入失败：${error.message}`);
          }
        }
      }
      // A free-practice frame is the durable workspace commit boundary. Do not expose its new
      // revision to the shell (or release the next queued intent) until IndexedDB contains the
      // matching .mecon bytes; an immediate reload must never reopen the preceding timeline edit.
      if (recoveryScheduled && data.type === "freePracticeFrame") await recoveryWriter.flush();
      if (data.documentRequestId != null) {
        completedDocumentRequestIdRef.current = data.documentRequestId;
      }
      setPracticeUpdate(data.type === "freePracticeFrame" ? data.update : null);
      setFrame(editorFrame);
      documentLoadingRef.current = false;
      setLoadingMessage(null);
      if (data.type === "freePracticeFrame") {
        practiceIntentsRef.current.settle(data.clientRequestId ?? null, data.update);
        playback.requestEdit(data.update.editPlayback);
      }
      // The session owns where sequential input continues: it knows the committed duration,
      // including tuplet ratios and cross-measure splits.
      const next = editorFrame.update.nextInputPosition;
      if (next) {
        setInsertMeasure(next.measure);
        setInsertBeat(Number(formatQuarterBeat(next.beat)));
      }
      // A rollback frame is still a normal frame, so clearing the alert unconditionally would
      // erase the only notice the user gets that a background engine crashed.
      setPracticeAlert(practiceEffectAlert(data.update?.effect));
      if (data.type !== "freePracticeFrame") {
        setStatus(`revision ${editorFrame.update.revision}`);
      } else {
        setStatus("");
      }
    };
    worker.onerror = (event) => {
      if (disposed) return;
      setLoadingMessage(null);
      reportError(`练习引擎加载失败：${event.message || "Worker 无法启动"}`);
    };
    // Closing the tab does not wait for the 300ms debounce window, so the last edit before a close
    // would otherwise be lost. `pagehide` is the last reliable point to write it.
    const flushRecovery = () => { void recoveryWriter.flush(); };
    const flushWhenHidden = () => {
      if (document.visibilityState === "hidden") flushRecovery();
    };
    window.addEventListener("pagehide", flushRecovery);
    document.addEventListener("visibilitychange", flushWhenHidden);
    loadMusicFont(appAssetUrl("fonts/Bravura.otf")).catch((error) => reportError(error.message));
    const startupDocumentRequestBase = documentRequestIdRef.current;
    loadRecoveryState().then((recovery) => {
      if (disposed || documentRequestIdRef.current !== startupDocumentRequestBase) return null;
      if (recovery) {
        return openDocument(new Uint8Array(recovery.bytes), "自动恢复", {
          unsaved: recovery.unsaved,
        });
      }
      createDocument();
      return null;
    }).catch((error) => reportError(`自动恢复读取失败：${error.message}`));
    return () => {
      disposed = true;
      window.removeEventListener("pagehide", flushRecovery);
      document.removeEventListener("visibilitychange", flushWhenHidden);
      recoveryWriter.cancel();
      const midiAccess = midiAccessRef.current;
      if (midiAccess) {
        midiAccess.onstatechange = null;
        for (const input of midiAccess.inputs.values()) input.onmidimessage = null;
        midiAccessRef.current = null;
      }
      worker.postMessage({ type: "close" });
      worker.terminate();
      workerRef.current = null;
      playback.dispose();
    };
  }, []);

  useEffect(() => {
    defaultChordBeatsRef.current = defaultChordBeats;
    workerRef.current?.postMessage({
      type: "timelinePreferences",
      gridUnit: { numerator: 1, denominator: gridDenominator },
      defaultChordDuration: { numerator: defaultChordBeats, denominator: 4 },
      displayMode: timelineDisplayMode,
    });
  }, [gridDenominator, defaultChordBeats, timelineDisplayMode]);

  useEffect(() => {
    const duration = practiceUpdate?.document?.settings?.defaultChordDuration;
    if (!duration) return;
    const beats = duration.numerator * 4 / duration.denominator;
    if (Number.isInteger(beats) && beats >= 1 && beats <= 16) setDefaultChordBeats(beats);
  }, [practiceUpdate?.document?.settings?.defaultChordDuration]);

  useEffect(() => {
    playback.setRate(playbackRate);
  }, [playbackRate]);

  useEffect(() => {
    if (!import.meta.env.DEV && import.meta.env.MODE !== "e2e") return undefined;
    window.__MECON_E2E__ = Object.freeze({
      snapshot: () => latestRef.current,
      documentManifest: () => documentRef.current?.manifest ?? null,
      documentRequest: () => ({
        latest: documentRequestIdRef.current,
        completed: completedDocumentRequestIdRef.current,
      }),
      hasUnsavedChanges: () => unsavedRef.current,
      dragState: () => editor.dragRef.current,
      timelineTrace: () => [...timelineTraceRef.current],
      toolbarDescriptor: () => practiceToolbarDescriptorRef.current,
      editorInput: () => editorInputRef.current,
      noteInputPreview: () => noteInputPreviewRef.current,
      playback: () => playback.cursorStore.getSnapshot(),
      playbackRange: () => playback.activeRange,
      playbackTrace: () => playback.traceSnapshot(),
      intentQueue: () => ({
        inFlightRequestId: practiceIntentsRef.current?.inFlightRequestId ?? null,
        pendingCount: practiceIntentsRef.current?.pendingCount ?? 0,
      }),
    });
    return () => { delete window.__MECON_E2E__; };
  }, []);

  useEffect(() => {
    function onKeyDown(event) {
      if (!frame || event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
      const command = event.ctrlKey || event.metaKey;
      if (event.key === "Escape") {
        event.preventDefault();
        editor.drag.cancel();
        setEditorTool("select");
        if (frame.update.selection.length) dispatch({ type: "setSelection", targets: [] });
      } else if (command && event.key.toLowerCase() === "a") {
        event.preventDefault();
        selectAllEvents();
      } else if (command && event.key.toLowerCase() === "z") {
        event.preventDefault();
        dispatch({ type: event.shiftKey ? "redo" : "undo" });
      } else if (command && event.key.toLowerCase() === "y") {
        event.preventDefault();
        dispatch({ type: "redo" });
      } else if (command && event.key.toLowerCase() === "c") {
        event.preventDefault();
        editSelection("copyNotes");
      } else if (command && event.key.toLowerCase() === "x") {
        event.preventDefault();
        editSelection("cutNotes");
      } else if (command && event.key.toLowerCase() === "v") {
        event.preventDefault();
        pasteAtInputPosition();
      } else if (event.key === "Delete" || event.key === "Backspace") {
        event.preventDefault();
        editSelection("deleteNotes");
      } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        event.preventDefault();
        editSelection("transposeNotes", { stepDelta: event.key === "ArrowUp" ? 1 : -1 });
      } else if (stepInputEnabled && !command) {
        const step = { a: 0, s: 1, d: 2, f: 3, g: 4, h: 5, j: 6 }[event.key.toLowerCase()];
        if (step != null) {
          event.preventDefault();
          insertEvent(false, { pitch: { diatonicSteps: step } });
        } else if (event.key.toLowerCase() === "r") {
          event.preventDefault();
          insertEvent(true);
        }
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [frame, stepInputEnabled, insertMeasure, insertBeat, insertDuration, insertDots, tupletCount,
    graceMode, setEditorTool, editor.drag]);

  async function openDocument(source, label, { unsaved = false } = {}) {
    // Reserve the request before archive parsing. IndexedDB recovery resolves independently, so
    // waiting until after `loadMeconDocument` lets a late startup "new" overtake a file the user
    // has already chosen.
    const documentRequestId = ++documentRequestIdRef.current;
    documentLoadingRef.current = true;
    setLoadingMessage("正在加载文档与练习引擎…");
    setStatus(`正在打开${label ? ` ${label}` : ""}…`);
    const document = await loadMeconDocument(source);
    if (documentRequestId !== documentRequestIdRef.current) return;
    const scoreRef = document.manifest.scores?.find(
      (item) => item.id === document.manifest.activeScoreId,
    ) ?? document.manifest.scores?.[0];
    if (!scoreRef) throw new Error(".mecon 中没有乐谱");
    const score = document.scores.get(scoreRef.id);
    if (!score) throw new Error(`缺少乐谱 ${scoreRef.id}`);
    if (!scoreRef.geometryPath) {
      document.manifest = {
        ...document.manifest,
        scores: document.manifest.scores.map((item) => item.id === scoreRef.id
          ? { ...item, geometryPath: `geometry/${item.id}.json` }
          : item),
      };
    }
    documentRef.current = document;
    scoreIdRef.current = scoreRef.id;
    const activeModuleId = document.manifest.workspace?.activeModuleId;
    const moduleRef = document.manifest.modules?.find((item) => item.id === activeModuleId);
    const moduleEntry = moduleRef?.type === "exploration.free-practice"
      ? document.modules.get(moduleRef.id)
      : null;
    moduleIdRef.current = moduleEntry ? moduleRef.id : null;
    unsavedRef.current = unsaved;
    practiceIntentsRef.current.reset();
    editor.setSurfaceIndex(0);
    pendingDocumentRecoveryRef.current.set(documentRequestId, { unsaved });
    workerRef.current.postMessage({
      type: "open",
      documentRequestId,
      score,
      ...(moduleEntry?.payload ? { document: moduleEntry.payload } : {}),
      metadataUrl: appAssetUrl("bravura/bravuraMetadata.json"),
      glyphNamesUrl: appAssetUrl("bravura/glyphnames.json"),
    });
  }

  async function onFile(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      if (unsavedRef.current
        && !window.confirm("当前工作尚未保存，打开其他文件将丢失这些更改。是否继续？")) {
        return;
      }
      await openDocument(file, file.name);
    } catch (error) {
      reportError(error.message);
    } finally {
      event.target.value = "";
    }
  }

  function createDocument() {
    if (unsavedRef.current
      && !window.confirm("当前工作尚未保存，新建将丢失这些更改。是否继续？")) {
      return;
    }
    setStatus("正在新建自由练习…");
    documentLoadingRef.current = true;
    setLoadingMessage("正在创建自由练习…");
    practiceIntentsRef.current.reset();
    setSharedScrollLeft(0);
    unsavedRef.current = false;
    const documentRequestId = ++documentRequestIdRef.current;
    pendingDocumentRecoveryRef.current.set(documentRequestId, { unsaved: false });
    workerRef.current.postMessage({
      type: "new",
      documentRequestId,
      metadataUrl: appAssetUrl("bravura/bravuraMetadata.json"),
      glyphNamesUrl: appAssetUrl("bravura/glyphnames.json"),
    });
  }

  function dispatch(intent) {
    if (!frame) return;
    if (freePracticeRef.current) {
      if (intent.type === "undo" || intent.type === "redo") {
        dispatchPractice(intent);
      } else {
        dispatchPractice({ type: "score", inner: intent });
      }
      return;
    }
    workerRef.current.postMessage({
      type: "dispatch",
      intent: { ...intent, expectedRevision: frame.update.revision },
    });
  }

  function requestTransposePreview(targets, stepDelta, callback) {
    const requestId = notationPreviewRequestIdRef.current + 1;
    notationPreviewRequestIdRef.current = requestId;
    notationPreviewCallbackRef.current = callback;
    workerRef.current?.postMessage({
      type: "transposePreview",
      requestId,
      targets: targets.map(({ eventId, pitchIndices }) => ({ eventId, pitchIndices })),
      stepDelta,
    });
  }

  function requestRestMovePreview(targets, callback) {
    const requestId = notationPreviewRequestIdRef.current + 1;
    notationPreviewRequestIdRef.current = requestId;
    notationPreviewCallbackRef.current = callback;
    workerRef.current?.postMessage({ type: "restMovePreview", requestId, targets });
  }

  function requestNoteInputTarget(request, callback) {
    const requestId = noteInputRequestIdRef.current + 1;
    noteInputRequestIdRef.current = requestId;
    noteInputCallbacksRef.current.set(requestId, callback);
    workerRef.current?.postMessage({ type: "noteInputTarget", requestId, request });
  }

  function updatePracticeViewportWidth(width) {
    if (!freePracticeRef.current || Math.abs(practiceViewportWidthRef.current - width) < 2) return;
    practiceViewportWidthRef.current = width;
    workerRef.current?.postMessage({ type: "resize", width });
  }

  function dispatchPractice(intent) {
    if (!practiceUpdate) return;
    practiceIntentsRef.current.push(intent, practiceUpdate);
  }

  /** The gesture's own `PracticeTimelineEdit`, wrapped verbatim — no second edit-to-intent mapping. */
  function commitTimelineEdit(edit) {
    dispatchPractice({ type: "timelineEdit", edit });
  }

  function sendTimelineInput(input) {
    timelineTraceRef.current.push({ stage: "input", input });
    if (timelineTraceRef.current.length > 80) timelineTraceRef.current.shift();
    workerRef.current?.postMessage({ type: "timelineInput", input });
  }

  function togglePlayback() {
    playback.toggle();
  }

  function requestTimelinePlayback(selectedOnly = false) {
    playback.requestTimeline(selectedOnly);
  }

  function updateAudioSettings(next: AudioSettings) {
    playback.updateSettings(next);
    setAudioSettings(next);
  }

  function openAudioSettings() {
    playback.stop();
    setAudioSettingsOpen(true);
  }
  function replacePracticeChord(chordChoice) {
    const slotId = selectedPracticeSlotId;
    if (!slotId || !chordChoice) return;
    dispatchPractice({ type: "replaceChord", slotId, chordChoice });
  }

  const {
    editSelection,
    insertChord,
    insertEvent,
    pasteAtInputPosition,
    selectAllEvents,
  } = editorCommands;

  // MIDI note numbers go to the session verbatim; Pitch.fromMidi does the spelling for every platform.
  stepInsertRef.current = (midiNote) => insertEvent(false, { midiNote });

  async function enableMidiInput() {
    if (!navigator.requestMIDIAccess) {
      setMidiStatus("此浏览器不支持 Web MIDI");
      return;
    }
    try {
      const access = await navigator.requestMIDIAccess();
      midiAccessRef.current = access;
      const attach = () => {
        let count = 0;
        for (const input of access.inputs.values()) {
          count++;
          input.onmidimessage = ({ data }) => {
            const [statusByte, note, velocity] = data;
            if ((statusByte & 0xf0) === 0x90 && velocity > 0) {
              stepInsertRef.current?.(note);
            }
          };
        }
        setMidiStatus(count ? `MIDI 已连接（${count}）` : "MIDI 已授权，等待设备");
      };
      access.onstatechange = attach;
      attach();
    } catch (error) {
      setMidiStatus(`MIDI 连接失败：${error.message}`);
    }
  }

  function deleteStructureMeasure() {
    const measure = Math.max(1, Number(insertMeasure) || 1);
    if (!window.confirm(`删除第 ${measure} 小节？此操作可撤销。`)) return;
    dispatch({ type: "deleteMeasures", measureNumbers: [measure] });
  }

  async function download() {
    const bytes = currentArchiveBytes();
    if (!bytes) return;
    let result;
    try {
      result = await saveMeconDocument(bytes);
    } catch (error) {
      reportError(`保存失败：${error.message}`);
      return;
    }
    if (result === "cancelled") {
      setStatus("已取消保存");
      return;
    }
    if (result === "downloaded") {
      setStatus("已下载 free-practice.mecon；当前浏览器无法确认文件是否实际保存");
      return;
    }
    unsavedRef.current = false;
    try {
      await saveRecovery(bytes, false);
    } catch (error) {
      reportError(`自动恢复写入失败：${error.message}`);
      return;
    }
    setStatus("已生成 free-practice.mecon");
  }

  function currentArchiveBytes() {
    const document = documentRef.current;
    const scoreId = scoreIdRef.current;
    const current = latestRef.current;
    if (!document || !scoreId || !current.update || !current.bundle) return null;
    const moduleId = moduleIdRef.current;
    const existingModule = moduleId ? document.modules.get(moduleId) : null;
    const modules = moduleId && current.practiceUpdate?.document && existingModule
      ? new Map([[moduleId, { ...existingModule, payload: current.practiceUpdate.document }]])
      : undefined;
    return writeMeconDocument(document, {
      manifest: document.manifest,
      scores: new Map([[scoreId, current.update.score]]),
      geometries: new Map([[scoreId, current.bundle]]),
      modules,
    });
  }

  const practiceNoteProperties = practiceUpdate && <PracticeNoteProperties
    update={practiceUpdate}
    selection={practiceNoteSelection}
    dispatchPractice={dispatchPractice}
  />;

  const renderPlanPanel = (sections, chordDetailsInitiallyOpen = false) => <PracticePlanPanel
    update={practiceUpdate}
    catalogChoiceId={practiceCatalogChoiceId}
    onCatalogChoiceChange={setPracticeCatalogChoiceId}
    onReplaceChord={replacePracticeChord}
    onSelectSlot={(slotId) => slotId && dispatchPractice({ type: "selectSlot", slotId })}
    onAppendChord={(onset) => dispatchPractice({
      type: "insertChordRange",
      onset,
      duration: { numerator: defaultChordBeats, denominator: 4 },
    })}
    onRemoveChord={() => selectedPracticeSlotId && dispatchPractice({
      type: "removeChordRange", slotId: selectedPracticeSlotId,
    })}
    onSetBass={(bassPitchClass) => selectedPracticeSlotId && dispatchPractice({
      type: "setChordBass", slotId: selectedPracticeSlotId, bassPitchClass,
    })}
    onSetTonality={(tonality) => selectedPracticeSlotId && dispatchPractice({
      type: "setChordTonality", slotId: selectedPracticeSlotId, tonality,
    })}
    onSetPivot={(selected) => selectedPracticeSlotId && dispatchPractice({
      type: "setPivotChord", slotId: selectedPracticeSlotId, selected,
    })}
    onSetTonalKey={(fifths, mode) => practiceUpdate?.plan?.editableTonalLayoutId && dispatchPractice({
      type: "setTonalLayoutKey",
      tonalLayoutId: practiceUpdate.plan.editableTonalLayoutId,
      fifths,
      mode,
    })}
    onInsertTonalLayout={({ fifths, mode }, terminatePrevious) => {
      const slot = practiceUpdate?.plan?.selectedSlot;
      if (!slot) return;
      dispatchPractice({
        type: "insertTonalLayout",
        fifths,
        mode,
        start: slot.onset,
        end: null,
        terminatePreviousAt: terminatePrevious ? addFraction(slot.onset, slot.duration) : null,
      });
    }}
    onRemoveTonalLayout={(tonalLayoutId) => dispatchPractice({
      type: "removeTonalLayout", tonalLayoutId,
    })}
    onSelectTonalLayout={(tonalLayoutId) => selectedPracticeSlotId && dispatchPractice({
      type: "selectChordTonalLayout", slotId: selectedPracticeSlotId, tonalLayoutId,
    })}
    onSelectIdiomTonalLayout={(tonalLayoutId) => dispatchPractice({
      type: "selectIdiomTonalLayout", tonalLayoutId,
    })}
    onInsertIdiom={(definitionId, variantId) => selectedPracticeSlotId && dispatchPractice({
      type: "insertIdiom", anchorSlotId: selectedPracticeSlotId, definitionId, variantId,
    })}
    onReplaceIdiom={(idiomInstanceId, definitionId, variantId) => dispatchPractice({
      type: "replaceIdiom", idiomInstanceId, definitionId, variantId,
    })}
    onSetIdiomChordToneCount={(idiomInstanceId, stepIndex, toneCount) => dispatchPractice({
      type: "setIdiomChordToneCount", idiomInstanceId, stepIndex, toneCount,
    })}
    onSelectIdiom={(idiomInstanceId) => dispatchPractice({ type: "selectIdiom", idiomInstanceId })}
    onSetCatalogFilter={(includeOffKey) => dispatchPractice({ type: "setCatalogFilter", includeOffKey })}
    onRemoveIdiom={(idiomInstanceId) => dispatchPractice({ type: "removeIdiom", idiomInstanceId })}
    sections={sections}
    chordDetailsInitiallyOpen={chordDetailsInitiallyOpen}
    beforeTonality={sections.includes("tonality") ? practiceNoteProperties : null}
  />;

  return (
    <ScoreEditor
      controller={editor}
      toolbarConfig={practiceUpdate
        ? toolbarProfileFromDescriptor(practiceToolbarDescriptor?.score)
        : FULL_SCORE_EDITOR_TOOLBAR}
      hiddenControlIds={practiceUpdate ? [] : [
        "input.position", "input.duration", "input.rest", "input.chord",
        "input.step", "input.midi", "input.grace", "input.tuplet",
      ]}
      toolbarSlots={{
        file: <>
          <button onClick={createDocument}><ToolbarIcon icon={FilePlus2} />新建自由练习</button>
          <label className="file-button"><ToolbarIcon icon={FolderOpen} />打开 .mecon
            <input type="file" accept=".mecon,application/zip" onChange={onFile} />
          </label>
        </>,
        "document-actions": <button disabled={!frame} onClick={download}><ToolbarIcon icon={Save} />导出 .mecon</button>,
        status: <span className="status" role="status">{status}</span>,
      }}
      onEnableMidi={enableMidiInput}
      onExport={download}
      onDeleteMeasure={deleteStructureMeasure}
      surfaceProps={{
        background: "#fff",
        emptyContent: "打开桌面版保存的 .mecon 文件开始编辑",
        ariaLabel: "五线谱编辑区",
        scrollLeft: sharedScrollLeft,
        onScroll: setSharedScrollLeft,
        onViewportWidth: updatePracticeViewportWidth,
        // The timeline owns one append-duration tail after the score. Give notation the same
        // scrollable extent so both native scrollbars reach and leave their right edge together.
        scrollContentWidth: timelineScene?.contentWidth ?? 0,
        playbackStore: playback.cursorStore,
        elementTints: practiceElementTints,
        elementCenterMarkers: practiceElementCenterMarkers,
      }}
    >
    {({ toolbar, surface, inspectors }) => (
    <main className="app-shell">
      {loadingMessage && <LoadingOverlay message={loadingMessage} />}
      {practiceUpdate ? <header className="toolbar workbench-toolbar" role="toolbar" aria-label="自由练习工具栏">
        <PracticeTopToolbar
          descriptor={practiceToolbarDescriptor?.top}
          tokens={practiceToolbarDescriptor?.tokens}
          frame={frame}
          update={practiceUpdate}
          onNew={createDocument}
          onFile={onFile}
          onSave={download}
          dispatch={dispatch}
          dispatchPractice={dispatchPractice}
          togglePlayback={togglePlayback}
          requestTimelinePlayback={requestTimelinePlayback}
          playbackStore={playback.cursorStore}
          gridDenominator={gridDenominator}
          setGridDenominator={setGridDenominator}
          defaultChordBeats={defaultChordBeats}
          setDefaultChordBeats={(value) => {
            setDefaultChordBeats(value);
            dispatchPractice({
              type: "setDefaultChordDuration",
              duration: { numerator: value, denominator: 4 },
            });
          }}
          applyTimeSignature={(timeSignature) => {
            if (practiceUpdate.structure?.pristine !== false) {
              dispatchPractice({ type: "setPracticeTimeSignature", timeSignature });
            } else {
              editor.armTimeSignature(timeSignature);
              setMobileTab("score");
            }
          }}
          playbackRate={playbackRate}
          setPlaybackRate={setPlaybackRate}
              onOpenSettings={openAudioSettings}
        />
      </header> : toolbar}
      {audioSettingsOpen && <AudioSettingsDialog
        settings={audioSettings}
        onChange={updateAudioSettings}
        onClose={() => setAudioSettingsOpen(false)}
      />}
      {practiceUpdate && practiceAlert && <div className="workbench-status" role="alert">{practiceAlert}</div>}
      {practiceUpdate && <span className="practice-revision-announcer" role="status" aria-live="polite">
        revision {practiceUpdate.revision}
      </span>}

      <nav className="workbench-tabs" role="tablist" aria-label="自由练习视图" hidden={!practiceUpdate}>
        {[["timeline", "时间轴"], ["score", "五线谱"], ["plan", "计划"], ["feedback", "反馈"]]
          .map(([id, label]) => <button key={id} className={mobileTab === id ? "active" : ""}
            id={`workbench-tab-${id}`} role="tab" aria-selected={mobileTab === id}
            aria-controls={`workbench-panel-${id}`} tabIndex={mobileTab === id ? 0 : -1}
            onKeyDown={(event) => {
              const tabs = [...event.currentTarget.parentElement
                .querySelectorAll<HTMLButtonElement>('[role="tab"]')];
              const current = tabs.indexOf(event.currentTarget);
              const next = event.key === "ArrowRight" ? (current + 1) % tabs.length
                : event.key === "ArrowLeft" ? (current - 1 + tabs.length) % tabs.length
                  : event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : -1;
              if (next < 0) return;
              event.preventDefault();
              tabs[next].click();
              tabs[next].focus();
            }}
            onClick={() => setMobileTab(id)}>{label}</button>)}
      </nav>

      <section className={`workspace ${practiceUpdate && DEFAULT_WEB_PRACTICE_LAYOUT === "writing-with-lower-panels"
        ? "partitioned-layout" : "classic-layout"}`}>
        <section className={`workbench-main ${practiceUpdate && DEFAULT_WEB_PRACTICE_LAYOUT === "writing-with-lower-panels"
          ? "partitioned-main" : ""}`}>
          <div id="workbench-panel-timeline" role="tabpanel" aria-labelledby="workbench-tab-timeline"
            className={`workbench-pane timeline-pane ${mobileTab === "timeline" ? "active" : ""}`}>
            {practiceUpdate
              ? <HarmonyTimeline scene={timelineScene} onInput={sendTimelineInput}
                  scrollLeft={sharedScrollLeft} onScroll={setSharedScrollLeft}
                  displayMode={timelineDisplayMode}
                  onDisplayModeChange={setTimelineDisplayMode} />
              : <section className="harmony-timeline empty" aria-label="和声时间轴">
                  新建或打开自由练习后显示时间轴
                </section>}
          </div>
          <div id="workbench-panel-score" role="tabpanel" aria-labelledby="workbench-tab-score"
            className={`workbench-pane score-pane ${mobileTab === "score" ? "active" : ""}`}>
            {practiceUpdate && toolbar}
            {surface}
          </div>
          {practiceUpdate && DEFAULT_WEB_PRACTICE_LAYOUT === "writing-with-lower-panels" &&
            <ResizableLowerWorkbench
              left={renderPlanPanel(["harmony"])}
              right={renderPlanPanel(["idioms"])}
            />}
        </section>
        <ResizableWorkbenchSide>
          <div id="workbench-panel-plan" role="tabpanel" aria-labelledby="workbench-tab-plan"
            className={`workbench-pane plan-pane ${mobileTab === "plan" ? "active" : ""}`}>
            {renderPlanPanel(
              practiceUpdate && DEFAULT_WEB_PRACTICE_LAYOUT === "writing-with-lower-panels"
                ? ["tonality", "details"]
                : ["tonality", "harmony", "details", "idioms"],
              practiceUpdate && DEFAULT_WEB_PRACTICE_LAYOUT === "writing-with-lower-panels",
            )}
          </div>
          <div id="workbench-panel-feedback" role="tabpanel" aria-labelledby="workbench-tab-feedback"
            className={`workbench-pane feedback-pane ${mobileTab === "feedback" ? "active" : ""}`}>
            <PracticeFeedbackPanel update={practiceUpdate} onFocusFinding={(finding) => dispatch({
              type: "setSelection",
              targets: finding.anchors.map((eventId) => ({ type: "event", eventId })),
            })} />
          </div>
        {!practiceUpdate && <aside className="panel score-editor-inspector">
          <p>{frame?.update.selection.length ? `已选择 ${frame.update.selection.length} 个元素` : "尚未选择"}</p>
          {inspectors}
        </aside>}
        </ResizableWorkbenchSide>
      </section>
    </main>
    )}
    </ScoreEditor>
  );
}
