import React, { useEffect, useRef, useState, useSyncExternalStore } from "react";
import {
  ArrowRightLeft,
  Clock3,
  FilePlus2,
  FolderOpen,
  Music2,
  ListPlus,
  Pause,
  PenLine,
  Play,
  PlayCircle,
  RefreshCw,
  Redo2,
  Save,
  Settings2,
  Undo2,
} from "lucide-react";
import {
  loadMeconDocument,
  loadMusicFont,
  writeMeconDocument,
} from "@mecon/frozen-score";
import {
  completeNoteResumeOffset,
  createPlaybackCursorStore,
  FULL_SCORE_EDITOR_TOOLBAR,
  formatQuarterBeat,
  interpolateScoreTime,
  playbackAnchorForCursor,
  playbackTickAtOffset,
  playbackRangeForSelection,
  toolbarProfileFromDescriptor,
} from "@mecon/web-renderer/editor";
import {
  BravuraTimeSignatureGlyph,
  ScoreEditor,
  useScoreEditorController,
} from "@mecon/web-renderer/editor/react";
import { createRecoveryWriter, loadRecoveryState, saveRecovery } from "./recovery.js";
import { createPracticeIntentQueue } from "./practice-intents.js";
import { createNewPracticeDocument } from "./new-document.js";
import { saveMeconDocument } from "./save-document.js";
import { writingSlotIdsForScoreSelection } from "./writing-selection.js";
import { HarmonyTimeline } from "./HarmonyTimeline.jsx";
import { PracticeFeedbackPanel } from "./PracticeFeedbackPanel.jsx";
import { PracticePlanPanel } from "./PracticePlanPanel.jsx";
import { ResizableWorkbenchSide } from "./ResizableWorkbenchSide.jsx";
import { ResizableLowerWorkbench } from "./ResizableLowerWorkbench.jsx";
import { appAssetUrl } from "./paths.js";
import {
  createRhodyPlayback,
  ORGAN_PRESETS,
  PlaybackInstrument,
} from "./rhody-playback.js";

// Keep the classic branch available for the future Web piano-roll layout switch.
const DEFAULT_WEB_PRACTICE_LAYOUT = "writing-with-lower-panels";

function practiceRoleStatus(noteheads, roleViewByRef) {
  if (!noteheads.length) return "和弦角色：—";
  const views = noteheads.map((ref) => roleViewByRef.get(`${ref.eventId}:${ref.pitchIndex}`))
    .filter(Boolean);
  if (views.some((item) => item.conflict)) return "和弦角色：存在冲突";
  const explicit = new Set(views.map((item) => item.explicitRole).filter(Boolean));
  const resolved = new Set(views.map((item) => item.explicitRole ?? item.inferredRole).filter(Boolean));
  if (explicit.size > 1 || resolved.size > 1) return "和弦角色：混合";
  const role = [...(explicit.size ? explicit : resolved)][0];
  if (!role) return "和弦角色：未判定";
  const label = role === "CHORD_TONE" ? "和弦内音" : "和弦外音";
  return `${explicit.size ? "已标记" : "推断"}：${label}`;
}

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
  const pendingDocumentRecoveryRef = useRef(new Map());
  const latestRef = useRef({ update: null, bundle: null });
  const midiAccessRef = useRef(null);
  const stepInsertRef = useRef(null);
  const audioContextRef = useRef(null);
  const playbackNodesRef = useRef([]);
  const rhodyPlaybackRef = useRef(null);
  const audioScheduleIdRef = useRef(0);
  const playbackFrameRef = useRef(null);
  const playbackRequestIdRef = useRef(0);
  const activePlaybackRef = useRef(null);
  const playbackCursorRef = useRef(null);
  if (!playbackCursorRef.current) playbackCursorRef.current = createPlaybackCursorStore();
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
  const playbackRateRef = useRef(1);
  const audioSettingsRef = useRef(null);
  const playbackTraceRef = useRef([]);
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
  const [audioSettings, setAudioSettings] = useState({
    instrument: PlaybackInstrument.piano,
    organPreset: 0,
    reverbEnabled: true,
  });
  const [audioSettingsOpen, setAudioSettingsOpen] = useState(false);
  const [status, setStatus] = useState("请选择 .mecon 文件");
  const [practiceAlert, setPracticeAlert] = useState("");
  const [loadingMessage, setLoadingMessage] = useState("正在加载练习引擎…");
  audioSettingsRef.current = audioSettings;
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

  function tracePlayback(kind, details) {
    if (!import.meta.env.DEV && import.meta.env.MODE !== "e2e") return;
    const entry = { kind, atMs: performance.now(), ...details };
    playbackTraceRef.current.push(entry);
    if (playbackTraceRef.current.length > 200) playbackTraceRef.current.shift();
    console.debug(`[mecon-playback] ${kind}`, entry);
  }
  const inputState = editor.input;
  const {
    insertMeasure, setInsertMeasure, insertBeat, setInsertBeat, insertDuration, insertDots,
    graceMode, tupletCount,
    stepInputEnabled, setMidiStatus, setEditorTool,
  } = inputState;
  const editorCommands = editor.commands;
  const selectedPracticeSlotId = practiceUpdate?.selection?.slotId ?? practiceUpdate?.selectedSlotId;
  const roleViewByRef = new Map((practiceUpdate?.noteConstraints?.noteheads ?? []).map((item) => [
    `${item.notehead.eventId}:${item.notehead.pitchIndex}`, item,
  ]));
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

  function selectedPracticeNoteheads() {
    const views = practiceUpdate?.noteConstraints?.noteheads ?? [];
    const selected = practiceUpdate?.selection?.scoreTargets ?? [];
    return selected.flatMap((target) => {
      if (target.type !== "event") return [];
      const indices = target.pitchIndices?.length
        ? new Set(target.pitchIndices)
        : null;
      return views.filter((item) => item.notehead.eventId === target.eventId
        && (indices == null || indices.has(item.notehead.pitchIndex)))
        .map((item) => item.notehead);
    });
  }
  const selectedNoteheads = selectedPracticeNoteheads();
  const selectedPracticeVoiceId = (practiceUpdate?.selection?.scoreTargets ?? [])
    .find((target) => target.type === "event")?.voiceTrackId ?? null;
  const selectedPracticeStaffId = Object.values(practiceUpdate?.score?.score?.staffTracks ?? {})
    .find((staff) => staff.voiceTrackIds?.includes(selectedPracticeVoiceId))?.id ?? null;
  const selectedPracticeNotesLocked = selectedNoteheads.length > 0
    && selectedNoteheads.every((ref) => roleViewByRef.get(
      `${ref.eventId}:${ref.pitchIndex}`,
    )?.locked);

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
      onError: (error) => reportError(`自动恢复写入失败：${error.message}`),
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
        if (data.playbackRequestId !== playbackRequestIdRef.current) return;
        playExcerpt(data.excerpt, data.trackCursor === false ? null : data.range);
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
      setPracticeUpdate(data.type === "freePracticeFrame" ? data.update : null);
      setFrame(editorFrame);
      documentLoadingRef.current = false;
      setLoadingMessage(null);
      if (data.type === "freePracticeFrame") {
        practiceIntentsRef.current.settle(data.clientRequestId ?? null, data.update);
        requestEditPlayback(data.update.editPlayback);
      }
      // The session owns where sequential input continues: it knows the committed duration,
      // including tuplet ratios and cross-measure splits.
      const next = editorFrame.update.nextInputPosition;
      if (next) {
        setInsertMeasure(next.measure);
        setInsertBeat(formatQuarterBeat(next.beat));
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
    loadRecoveryState().then((recovery) => {
      if (recovery && !disposed) {
        return openDocument(new Uint8Array(recovery.bytes), "自动恢复", {
          unsaved: recovery.unsaved,
        });
      }
      if (!disposed) createDocument();
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
      stopPlayback();
      rhodyPlaybackRef.current?.backend?.dispose();
      rhodyPlaybackRef.current = null;
      audioContextRef.current?.close();
      audioContextRef.current = null;
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
    playbackRateRef.current = playbackRate;
  }, [playbackRate]);

  useEffect(() => {
    if (!import.meta.env.DEV && import.meta.env.MODE !== "e2e") return undefined;
    window.__MECON_E2E__ = Object.freeze({
      snapshot: () => latestRef.current,
      hasUnsavedChanges: () => unsavedRef.current,
      dragState: () => editor.dragRef.current,
      timelineTrace: () => [...timelineTraceRef.current],
      toolbarDescriptor: () => practiceToolbarDescriptorRef.current,
      editorInput: () => editorInputRef.current,
      noteInputPreview: () => noteInputPreviewRef.current,
      playback: () => playbackCursorRef.current.getSnapshot(),
      playbackRange: () => activePlaybackRef.current?.range ?? null,
      playbackTrace: () => [...playbackTraceRef.current],
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
    documentLoadingRef.current = true;
    setLoadingMessage("正在加载文档与练习引擎…");
    setStatus(`正在打开${label ? ` ${label}` : ""}…`);
    const document = await loadMeconDocument(source);
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
    const documentRequestId = ++documentRequestIdRef.current;
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
    const state = playbackCursorRef.current.getSnapshot().state;
    if (state === "playing") {
      pausePlayback();
    } else if (state === "paused") {
      resumePlayback();
    } else {
      requestTimelinePlayback(false);
    }
  }

  function requestTimelinePlayback(selectedOnly = false) {
    const slots = practiceUpdate?.timeline?.slots ?? [];
    if (!slots.length) return;
    if (!audioContextRef.current) audioContextRef.current = new AudioContext();
    audioContextRef.current.resume();
    const tempoBpm = practiceUpdate.document.settings.writing.playbackTempoBpm;
    const first = slots[0];
    const last = slots.at(-1);
    const range = selectedOnly
      ? playbackRangeForSelection(frame, practiceUpdate, tempoBpm)
      : {
        firstSlotId: first.id,
        lastSlotId: last.id,
        start: first.onset,
        end: practiceUpdate.timeline.end ?? addFraction(last.onset, last.duration),
        tempoBpm,
      };
    if (!range) return;
    stopPlayback();
    workerRef.current?.postMessage({
      type: "playback", range, playbackRequestId: playbackRequestIdRef.current,
    });
  }

  function requestEditPlayback(request) {
    if (!request) return;
    if (request.type === "audition" && playbackCursorRef.current.getSnapshot().state === "playing") return;
    tracePlayback("edit-playback", { type: request.type });
    if (!audioContextRef.current) audioContextRef.current = new AudioContext();
    void audioContextRef.current.resume();
    if (request.type === "excerpt") {
      stopPlayback();
      workerRef.current?.postMessage({
        type: "playback", range: request.range,
        playbackRequestId: playbackRequestIdRef.current,
        trackCursor: false,
      });
      return;
    }
    if (request.type !== "audition" || !request.midiNumbers?.length) return;
    const durationSeconds = 0.45;
    playExcerpt({
      notes: request.midiNumbers.map((midiNumber) => ({
        midiNumber, velocity: 88, startSeconds: 0, durationSeconds,
      })),
      durationSeconds,
      startTick: 0,
      endTick: 1,
      secondsPerTick: durationSeconds,
    }, null);
  }

  function stopPlayback(invalidateRequest = true) {
    if (invalidateRequest) playbackRequestIdRef.current++;
    clearScheduledPlayback();
    activePlaybackRef.current = null;
    playbackCursorRef.current.set({ state: "idle", time: null });
  }

  function clearScheduledPlayback() {
    audioScheduleIdRef.current++;
    if (playbackFrameRef.current != null) cancelAnimationFrame(playbackFrameRef.current);
    playbackFrameRef.current = null;
    rhodyPlaybackRef.current?.backend?.reset();
    for (const node of playbackNodesRef.current) {
      try { node.stop(); } catch { /* already stopped */ }
      node.disconnect();
    }
    playbackNodesRef.current = [];
  }

  function playExcerpt(excerpt, range) {
    const context = audioContextRef.current;
    if (!context || (range && (!range.start || !range.end))) return;
    stopPlayback(false);
    const playback = { excerpt, range, offsetSeconds: 0, baseContextTime: null, rate: 1 };
    activePlaybackRef.current = playback;
    schedulePlayback(playback);
  }

  async function rhodyBackend(settings) {
    const context = audioContextRef.current;
    if (!context || settings.instrument === PlaybackInstrument.default) return null;
    const key = JSON.stringify(settings);
    if (rhodyPlaybackRef.current?.key === key) return rhodyPlaybackRef.current.promise;
    rhodyPlaybackRef.current?.backend?.dispose();
    const entry = { key, backend: null, promise: null };
    entry.promise = createRhodyPlayback(context, {
      ...settings,
      onDiagnostic: (diagnostic) => {
        tracePlayback("rhody-worklet", diagnostic);
      },
    }).then((backend) => {
      if (rhodyPlaybackRef.current !== entry) {
        backend?.dispose();
        return null;
      }
      entry.backend = backend;
      return backend;
    }).catch((error) => {
      tracePlayback("rhody-fallback", { message: error.message });
      return null;
    });
    rhodyPlaybackRef.current = entry;
    return entry.promise;
  }

  async function schedulePlayback(playback) {
    const context = audioContextRef.current;
    if (!context) return;
    clearScheduledPlayback();
    const scheduleId = audioScheduleIdRef.current;
    const settings = audioSettingsRef.current;
    const rhody = await rhodyBackend(settings);
    if (scheduleId !== audioScheduleIdRef.current || activePlaybackRef.current !== playback) return;
    const base = context.currentTime + 0.025;
    const rate = playbackRateRef.current;
    const excerptDuration = Math.max(0, Number(playback.excerpt.durationSeconds));
    playback.baseContextTime = base;
    playback.rate = rate;
    playback.lastLoggedAnchorTick = null;
    tracePlayback("schedule", {
      contextTime: context.currentTime, baseContextTime: base, rate,
      startTick: playback.excerpt.startTick, endTick: playback.excerpt.endTick,
      secondsPerTick: playback.excerpt.secondsPerTick,
      durationSeconds: excerptDuration,
      noteStarts: [...new Set(playback.excerpt.notes.map((note) => Number(note.startSeconds)))],
    });
    const playableNotes = playback.excerpt.notes.filter((note) => (
      Number(note.startSeconds) + Number(note.durationSeconds) > playback.offsetSeconds
    ));
    if (rhody) {
      for (const note of playableNotes) {
        const noteEndSeconds = Number(note.startSeconds) + Number(note.durationSeconds);
        const audibleStart = Math.max(Number(note.startSeconds), playback.offsetSeconds);
        const start = base + Math.max(0, Number(note.startSeconds) - playback.offsetSeconds) / rate;
        const end = start + Math.max(0.03, (noteEndSeconds - audibleStart) / rate);
        const frequency = 440 * (2 ** ((note.midiNumber - 69) / 12));
        rhody.noteOn(frequency, Math.max(0.01, Math.min(1, Number(note.velocity) / 127)), start);
        rhody.noteOff(frequency, end);
      }
      tracePlayback("rhody-schedule", {
        instrument: settings.instrument,
        organPreset: settings.organPreset,
        roomPreset: rhody.roomPreset,
      });
    }
    playbackNodesRef.current = rhody ? [] : playableNotes.map((note) => {
      const noteEndSeconds = Number(note.startSeconds) + Number(note.durationSeconds);
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = "triangle";
      oscillator.frequency.value = 440 * (2 ** ((note.midiNumber - 69) / 12));
      const audibleStart = Math.max(Number(note.startSeconds), playback.offsetSeconds);
      const start = base + Math.max(0, Number(note.startSeconds) - playback.offsetSeconds) / rate;
      const end = start + Math.max(0.03, (noteEndSeconds - audibleStart) / rate);
      const level = Math.max(0.015, Math.min(0.12, note.velocity / 900));
      gain.gain.setValueAtTime(0, start);
      gain.gain.linearRampToValueAtTime(level, start + 0.01);
      gain.gain.setValueAtTime(level, Math.max(start + 0.01, end - 0.03));
      gain.gain.linearRampToValueAtTime(0, end);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(start);
      oscillator.stop(end + 0.01);
      return oscillator;
    });
    const advancePlayhead = () => {
      const elapsed = Math.max(0, context.currentTime - base) * rate;
      const offset = Math.min(excerptDuration, playback.offsetSeconds + elapsed);
      const progress = excerptDuration === 0 ? 1 : offset / excerptDuration;
      const time = playback.range
        ? interpolateScoreTime(playback.range.start, playback.range.end, progress)
        : null;
      const tick = playbackTickAtOffset(playback.excerpt, offset);
      if (playback.range) {
        playbackCursorRef.current.set({
          state: "playing",
          time,
          tick,
        });
      }
      const anchor = playback.range ? playbackAnchorForCursor(latestRef.current, time, tick) : null;
      if (anchor && anchor.tick !== playback.lastLoggedAnchorTick) {
        playback.lastLoggedAnchorTick = anchor.tick;
        tracePlayback("cursor-anchor", {
          contextTime: context.currentTime, elapsedSeconds: elapsed, offsetSeconds: offset,
          cursorTick: tick, anchorTick: anchor.tick, scoreTime: anchor.scoreTime,
        });
      }
      if (progress < 1) playbackFrameRef.current = requestAnimationFrame(advancePlayhead);
      else stopPlayback(false);
    };
    advancePlayhead();
  }

  function currentPlaybackOffset(playback) {
    const context = audioContextRef.current;
    if (!context || playback.baseContextTime == null) return playback.offsetSeconds;
    return Math.min(
      Number(playback.excerpt.durationSeconds),
      playback.offsetSeconds + Math.max(0, context.currentTime - playback.baseContextTime) * playback.rate,
    );
  }

  function pausePlayback() {
    const playback = activePlaybackRef.current;
    if (!playback) return;
    const currentOffset = currentPlaybackOffset(playback);
    const resumeOffset = completeNoteResumeOffset(playback.excerpt.notes, currentOffset);
    playback.offsetSeconds = resumeOffset;
    playback.baseContextTime = null;
    clearScheduledPlayback();
    const duration = Number(playback.excerpt.durationSeconds);
    const pausedTime = interpolateScoreTime(
      playback.range.start,
      playback.range.end,
      duration <= 0 ? 1 : currentOffset / duration,
    );
    const pausedTick = playbackTickAtOffset(playback.excerpt, currentOffset);
    playbackCursorRef.current.set({
      state: "paused",
      time: pausedTime,
      tick: pausedTick,
    });
    tracePlayback("pause", {
      currentOffsetSeconds: currentOffset, resumeOffsetSeconds: resumeOffset,
      pausedTick, resumeTick: playbackTickAtOffset(playback.excerpt, resumeOffset),
      visibleAnchorTick: playbackAnchorForCursor(latestRef.current, pausedTime, pausedTick)?.tick ?? null,
    });
  }

  function resumePlayback() {
    const playback = activePlaybackRef.current;
    if (!playback) return;
    audioContextRef.current?.resume();
    void schedulePlayback(playback);
  }

  function updateAudioSettings(next) {
    stopPlayback();
    rhodyPlaybackRef.current?.backend?.dispose();
    rhodyPlaybackRef.current = null;
    setAudioSettings(next);
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

  const practiceNoteProperties = practiceUpdate && <details
    className="plan-section workbench-panel practice-note-properties" open>
    <summary><h2>音符属性</h2></summary>
    <p className="practice-note-properties-summary">
      {selectedNoteheads.length
        ? `已选择 ${selectedNoteheads.length} 个符头`
        : "选择一个或多个符头以编辑属性"}
    </p>
    <section className="practice-note-property-group" aria-labelledby="practice-role-heading">
      <h3 id="practice-role-heading">和弦内外音</h3>
      <p>{practiceRoleStatus(selectedNoteheads, roleViewByRef)}</p>
      <div className="practice-note-property-actions">
        <button disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
          type: "setHarmonicRole", noteheads: selectedNoteheads, role: "CHORD_TONE",
        })}>标记为和弦内音</button>
        <button disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
          type: "setHarmonicRole", noteheads: selectedNoteheads, role: "NON_CHORD_TONE",
        })}>标记为和弦外音</button>
        <button disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
          type: "setHarmonicRole", noteheads: selectedNoteheads,
        })}>清除内外音标记</button>
        <label><input type="checkbox"
          checked={practiceUpdate.noteConstraints?.chordCatalogFilterEnabled ?? false}
          onChange={(event) => dispatchPractice({
            type: "setHarmonicRoleFilters",
            chordCatalogEnabled: event.target.checked,
            idiomCatalogEnabled: practiceUpdate.noteConstraints?.idiomCatalogFilterEnabled ?? false,
          })} />筛选和弦</label>
        <label><input type="checkbox"
          checked={practiceUpdate.noteConstraints?.idiomCatalogFilterEnabled ?? false}
          onChange={(event) => dispatchPractice({
            type: "setHarmonicRoleFilters",
            chordCatalogEnabled: practiceUpdate.noteConstraints?.chordCatalogFilterEnabled ?? false,
            idiomCatalogEnabled: event.target.checked,
          })} />筛选惯用进行</label>
      </div>
    </section>
    <section className="practice-note-property-group" aria-labelledby="practice-lock-heading">
      <h3 id="practice-lock-heading">锁定情况</h3>
      <p>{selectedNoteheads.length
        ? `当前音符：${selectedPracticeNotesLocked ? "已锁定" : "未锁定或混合"}`
        : "当前音符：—"}</p>
      <p className="practice-note-properties-summary">锁定音符以符头中央圆点标记</p>
      <div className="practice-note-property-actions">
        <button disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
          type: "setNoteheadLock", noteheads: selectedNoteheads,
          locked: !selectedPracticeNotesLocked,
        })}>{selectedPracticeNotesLocked ? "解锁音符" : "锁定音符"}</button>
        <button disabled={!selectedPracticeVoiceId} onClick={() => dispatchPractice({
          type: "setVoiceLock", voiceTrackId: selectedPracticeVoiceId,
          locked: !(practiceUpdate.noteConstraints?.lockedVoiceTrackIds ?? [])
            .includes(selectedPracticeVoiceId),
        })}>{(practiceUpdate.noteConstraints?.lockedVoiceTrackIds ?? []).includes(selectedPracticeVoiceId)
          ? "解锁声部" : "锁定声部"}</button>
        <button disabled={!selectedPracticeStaffId} onClick={() => dispatchPractice({
          type: "setStaffLock", staffTrackId: selectedPracticeStaffId,
          locked: !(practiceUpdate.noteConstraints?.lockedStaffTrackIds ?? [])
            .includes(selectedPracticeStaffId),
        })}>{(practiceUpdate.noteConstraints?.lockedStaffTrackIds ?? []).includes(selectedPracticeStaffId)
          ? "解锁谱表" : "锁定谱表"}</button>
      </div>
    </section>
  </details>;

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
        playbackStore: playbackCursorRef.current,
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
          stopPlayback={stopPlayback}
          playbackStore={playbackCursorRef.current}
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
          onOpenSettings={() => setAudioSettingsOpen(true)}
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
              const tabs = [...event.currentTarget.parentElement.querySelectorAll('[role="tab"]')];
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

function AudioSettingsDialog({ settings, onChange, onClose }) {
  const dialogRef = useRef(null);
  useEffect(() => {
    dialogRef.current?.showModal();
    return () => { if (dialogRef.current?.open) dialogRef.current.close(); };
  }, []);
  const roomLabel = settings.instrument === PlaybackInstrument.organ ? "教堂" : "录音室";
  return <dialog ref={dialogRef} className="audio-settings-dialog" aria-modal="true" aria-labelledby="audio-settings-title"
    onCancel={(event) => { event.preventDefault(); onClose(); }}>
    <form method="dialog" onSubmit={onClose}>
      <header>
        <h2 id="audio-settings-title">播放音色</h2>
        <button type="submit" className="dialog-close-button" aria-label="关闭">×</button>
      </header>
      <fieldset>
        <legend>音色</legend>
        {[
          [PlaybackInstrument.default, "默认合成器"],
          [PlaybackInstrument.piano, "钢琴"],
          [PlaybackInstrument.organ, "管风琴"],
        ].map(([value, label]) => <label className="audio-radio-row" key={value}>
          <input type="radio" name="instrument" value={value}
            checked={settings.instrument === value}
            onChange={() => onChange({ ...settings, instrument: value })} />
          <span>{label}</span>
        </label>)}
      </fieldset>
      {settings.instrument === PlaybackInstrument.organ && <label className="audio-settings-field">
        <span>音栓组合</span>
        <select value={settings.organPreset}
          onChange={(event) => onChange({ ...settings, organPreset: Number(event.target.value) })}>
          {ORGAN_PRESETS.map((preset) => <option value={preset.value} key={preset.value}>
            {preset.label}
          </option>)}
        </select>
      </label>}
      {settings.instrument !== PlaybackInstrument.default && <label className="audio-reverb-row">
        <span>
          <strong>混响</strong>
          <small>{roomLabel}配置</small>
        </span>
        <input type="checkbox" checked={settings.reverbEnabled}
          onChange={(event) => onChange({ ...settings, reverbEnabled: event.target.checked })} />
      </label>}
      <p className="audio-settings-hint">Rhody 未配置或加载失败时，将自动使用默认合成器。</p>
      <footer><button type="submit">完成</button></footer>
    </form>
  </dialog>;
}

function LoadingOverlay({ message }) {
  return <div className="app-loading-overlay" role="progressbar" aria-label={message}
    aria-live="polite" aria-busy="true">
    <span className="app-loading-spinner" aria-hidden="true" />
    <span>{message}</span>
  </div>;
}

function PracticeTopToolbar({
  descriptor, tokens, frame, update, onNew, onFile, onSave, dispatch, dispatchPractice,
  togglePlayback, requestTimelinePlayback, stopPlayback, playbackStore,
  gridDenominator, setGridDenominator, defaultChordBeats, setDefaultChordBeats,
  applyTimeSignature, playbackRate, setPlaybackRate,
  onOpenSettings,
}) {
  const [explorationMode, setExplorationMode] = useState("free");
  const playback = useSyncExternalStore(
    playbackStore.subscribe,
    playbackStore.getSnapshot,
    playbackStore.getSnapshot,
  );
  if (!descriptor) return null;
  const settings = update?.document?.settings;
  const writing = settings?.writing;
  const selectedSlotId = update?.selection?.slotId ?? update?.selectedSlotId;
  const rewriteSlotIds = writingSlotIdsForScoreSelection(frame, update);
  const updateWriting = (fields) => writing && dispatchPractice({
    type: "updateWritingSettings", settings: { ...writing, ...fields },
  });
  const rebuild = (fields = {}) => settings && dispatchPractice({
    type: "rebuildPractice",
    polyphonyLimit: fields.polyphonyLimit ?? settings.polyphonyLimit,
    fifths: fields.fifths ?? settings.initialKey.fifths,
    mode: fields.mode ?? settings.initialKey.mode,
  });
  const controls = {
    "file.new": <button onClick={onNew}><ToolbarIcon icon={FilePlus2} />新建</button>,
    "file.open": <label className="file-button"><ToolbarIcon icon={FolderOpen} />打开
      <input aria-label="打开 .mecon" type="file" accept=".mecon,application/zip" onChange={onFile} />
    </label>,
    "file.save": <button disabled={!frame} onClick={onSave}><ToolbarIcon icon={Save} />保存</button>,
    "history.undo": <button disabled={!frame?.update.canUndo} onClick={() => dispatch({ type: "undo" })}><ToolbarIcon icon={Undo2} />撤销</button>,
    "history.redo": <button disabled={!frame?.update.canRedo} onClick={() => dispatch({ type: "redo" })}><ToolbarIcon icon={Redo2} />重做</button>,
    "writing.rewrite": <button disabled={!rewriteSlotIds.length || update?.writing?.phase === "RUNNING"}
      onClick={() => dispatchPractice({ type: "rewriteSelection", slotIds: rewriteSlotIds })}>
      <ToolbarIcon icon={RefreshCw} />重新写作
    </button>,
    "writing.alternate": <button disabled={!update?.writing?.canAlternate}
      onClick={() => dispatchPractice({ type: "alternateWriting" })}>
      <ToolbarIcon icon={ArrowRightLeft} />换一个结果
    </button>,
    "writing.auto": writing && <button type="button" aria-pressed={writing.autoWritingEnabled}
      disabled={update?.writing?.phase === "RUNNING"}
      onClick={() => updateWriting({ autoWritingEnabled: !writing.autoWritingEnabled })}>
      <ToolbarIcon icon={PenLine} />自动写作
    </button>,
    "writing.backtrack-count": writing && <ToolbarNumber label="回溯和弦" value={writing.backtrackChordCount}
      min={0} max={16} onChange={(value) => updateWriting({ backtrackChordCount: value })} />,
    "writing.replay-count": writing && <ToolbarNumber label="回放个数" value={writing.replayChordCount}
      min={0} max={16} onChange={(value) => updateWriting({ replayChordCount: value })} />,
    "writing.tempo": writing && <ToolbarNumber label="BPM" value={writing.playbackTempoBpm}
      min={30} max={240} onChange={(value) => updateWriting({ playbackTempoBpm: value })} />,
    "writing.voice-count": settings && <ToolbarNumber label="声部数" value={settings.polyphonyLimit}
      min={3} max={6} onChange={(value) => rebuild({ polyphonyLimit: value })} />,
    "writing.upper-voice-count": settings && <ToolbarNumber label="上谱声部" value={settings.staffVoices.upperVoiceCount}
      min={1} max={settings.polyphonyLimit - 1} onChange={(upperVoiceCount) => dispatchPractice({
        type: "updateStaffVoices",
        staffVoices: { upperVoiceCount, lowerVoiceCount: settings.polyphonyLimit - upperVoiceCount },
      })} />,
    "writing.grid-unit": <label className="toolbar-setting">自动吸附单位<select aria-label="自动吸附单位"
      value={gridDenominator}
      onChange={(event) => setGridDenominator(Number(event.target.value))}>
      {[[4, "四分音符"], [8, "八分音符"], [16, "十六分音符"], [32, "三十二分音符"], [64, "六十四分音符"]]
        .map(([value, label]) => <option value={value} key={value}>{label}</option>)}
    </select></label>,
    "writing.default-chord-beats": <ToolbarNumber label="默认和弦拍数" value={defaultChordBeats}
      min={1} max={16} onChange={setDefaultChordBeats} />,
    "writing.initial-key": settings && <TonalKeyDropdown value={settings.initialKey}
      choices={update?.plan?.tonalKeyChoices} onChange={rebuild} />,
    "structure.time-signature": <PracticeMeterDropdown update={update}
      onChange={applyTimeSignature} />,
    "structure.insert-measures": <PracticeInsertMeasuresDropdown update={update}
      defaultChordBeats={defaultChordBeats}
      onInsert={(position, count, chordBeats) => dispatchPractice({
        type: "insertPracticeMeasures",
        position,
        count,
        chordDuration: { numerator: chordBeats, denominator: 4 },
      })} />,
    "playback.from-start": <button disabled={!update?.timeline?.slots?.length}
      onClick={() => requestTimelinePlayback(false)}><ToolbarIcon icon={PlayCircle} />从头播放</button>,
    "playback.play-pause": <button disabled={!update?.timeline?.slots?.length}
      aria-label={playback.state === "playing" ? "暂停" : "播放"} onClick={togglePlayback}>
      <ToolbarIcon icon={playback.state === "playing" ? Pause : Play} />
      {playback.state === "playing" ? "暂停" : "播放"}
    </button>,
    "playback.from-selection": <button disabled={!selectedSlotId}
      onClick={() => requestTimelinePlayback(true)}><ToolbarIcon icon={PlayCircle} />从选择播放</button>,
    "settings.application": <button className="icon-only-button" onClick={onOpenSettings} aria-label="音色设置" title="音色设置"><ToolbarIcon icon={Settings2} /></button>,
  };
  const style = tokens ? {
    "--practice-toolbar-height": `${tokens.topHeight}px`,
    "--practice-toolbar-padding": `${tokens.horizontalPadding}px`,
    "--practice-toolbar-gap": `${tokens.groupGap}px`,
  } : undefined;
  return <div className="practice-toolbar-groups" style={style}>
    {descriptor.groups.filter((group) => group.id !== "mode" && group.id !== "playback.speed" && group.id !== "playback.audio-settings").map((group, index) => <React.Fragment key={group.id}>
      {index > 0 && !group.trailing && <span className="toolbar-divider" aria-hidden="true" />}
      <div className={`practice-toolbar-group${group.trailing ? " trailing" : ""}`}
        role="group" data-group={group.id}>
        {group.controls.map((id) => controls[id] == null ? null : <span
          className="practice-toolbar-control" data-control-id={id} key={id}>{controls[id]}</span>)}
      </div>
    </React.Fragment>)}
  </div>;
}

function TonalKeyDropdown({ value, choices = [], onChange }) {
  const [expanded, setExpanded] = useState(false);
  const [menuPosition, setMenuPosition] = useState(null);
  const rootRef = useRef(null);
  const currentId = value ? `${value.fifths}:${value.mode}` : "";
  const currentChoice = choices.find((choice) => choice.id === currentId);
  const orderedFifths = [0, ...Array.from({ length: 7 }, (_, index) => index + 1),
    ...Array.from({ length: 7 }, (_, index) => -(index + 1))];
  const modeLabel = value?.mode === "MINOR" ? "小调" : "大调";

  function updateMenuPosition() {
    const bounds = rootRef.current?.getBoundingClientRect();
    if (!bounds) return;
    setMenuPosition({
      top: bounds.bottom + 7,
      left: Math.max(130, Math.min(window.innerWidth - 130, bounds.left + bounds.width / 2)),
    });
  }

  useEffect(() => {
    if (!expanded) return undefined;
    updateMenuPosition();
    const dismiss = (event) => {
      if (!rootRef.current?.contains(event.target)) setExpanded(false);
    };
    const onKeyDown = (event) => {
      if (event.key === "Escape") setExpanded(false);
    };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("resize", updateMenuPosition);
    window.addEventListener("scroll", updateMenuPosition, true);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("resize", updateMenuPosition);
      window.removeEventListener("scroll", updateMenuPosition, true);
    };
  }, [expanded]);

  return <div className="toolbar-key-setting" ref={rootRef}>
    <button type="button" className="toolbar-key-button" aria-label="调性"
      aria-haspopup="dialog" aria-expanded={expanded} onClick={() => setExpanded((current) => !current)}>
      <ToolbarIcon icon={Music2} />{currentChoice?.label ?? value?.fifths ?? "—"}
    </button>
    {expanded && <div className="toolbar-key-menu" style={menuPosition} role="dialog" aria-label="调性选择">
      <strong>调式</strong>
      <div className="toolbar-key-mode-options">
        {["MAJOR", "MINOR"].map((mode) => <button key={mode} type="button"
          aria-pressed={value?.mode === mode}
          onClick={() => { onChange({ fifths: value.fifths, mode }); setExpanded(false); }}>
          {mode === "MAJOR" ? "大调" : "小调"}
        </button>)}
      </div>
      <strong>调性</strong>
      <div className="toolbar-key-options">
        {orderedFifths.map((fifths) => {
          const option = choices.find((choice) => choice.id === `${fifths}:${value?.mode}`);
          if (!option) return null;
          return <button key={option.id} type="button" aria-pressed={option.id === currentId}
            onClick={() => { onChange(option.key); setExpanded(false); }}>
            {option.label}
          </button>;
        })}
      </div>
      <small>{modeLabel}</small>
    </div>}
  </div>;
}

const COMMON_METERS = Object.freeze([
  { numerator: 4, denominator: 4, symbol: "COMMON" },
  { numerator: 3, denominator: 4 },
  { numerator: 2, denominator: 4 },
  { numerator: 2, denominator: 2, symbol: "CUT" },
  { numerator: 6, denominator: 8 },
  { numerator: 9, denominator: 8 },
  { numerator: 12, denominator: 8 },
]);

function beatGroupCandidates(numerator) {
  if (!(numerator > 3 && (numerator % 3 === 0 || numerator >= 5))) return [];
  const compose = (remaining) => {
    if (remaining === 0) return [[]];
    if (remaining < 0) return [];
    return [2, 3].flatMap((part) => compose(remaining - part).map((rest) => [part, ...rest]));
  };
  const canonical = numerator % 3 === 0
    ? Array(numerator / 3).fill(3)
    : numerator % 2 === 0
      ? Array(numerator / 2).fill(2)
      : [...Array((numerator - 3) / 2).fill(2), 3];
  return [canonical, ...compose(numerator)].filter((groups, index, values) =>
    values.findIndex((candidate) => candidate.join("+") === groups.join("+")) === index);
}

function useToolbarMenuPosition(expanded, rootRef, setExpanded) {
  const [position, setPosition] = useState(null);
  useEffect(() => {
    if (!expanded) return undefined;
    const update = () => {
      const bounds = rootRef.current?.getBoundingClientRect();
      if (!bounds) return;
      setPosition({
        top: bounds.bottom + 7,
        left: Math.max(150, Math.min(window.innerWidth - 150, bounds.left + bounds.width / 2)),
      });
    };
    update();
    const dismiss = (event) => {
      if (!rootRef.current?.contains(event.target)) setExpanded(false);
    };
    const keydown = (event) => { if (event.key === "Escape") setExpanded(false); };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", keydown);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", keydown);
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [expanded, rootRef, setExpanded]);
  return position;
}

function PracticeMeterDropdown({ update, onChange }) {
  const [expanded, setExpanded] = useState(false);
  const rootRef = useRef(null);
  const position = useToolbarMenuPosition(expanded, rootRef, setExpanded);
  const structure = update?.structure ?? {};
  const meter = structure.effectiveTimeSignature ?? { numerator: 4, denominator: 4 };
  const [draft, setDraft] = useState(meter);
  useEffect(() => {
    if (!expanded) setDraft(meter);
  }, [expanded, meter.numerator, meter.denominator, meter.symbol, meter.beatGroups]);
  const groupings = beatGroupCandidates(draft.numerator);
  const activeGrouping = draft.beatGroups ?? groupings[0] ?? [];
  const label = structure.pristine === false ? "调整拍号" : "设置拍号";
  return <div className="toolbar-key-setting" ref={rootRef}>
    <button type="button" className="toolbar-key-button" aria-haspopup="dialog"
      aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
      <ToolbarIcon icon={Clock3} />{label}
    </button>
    {expanded && <div className="toolbar-key-menu toolbar-meter-menu" style={position}
      role="dialog" aria-label={label}>
      <strong>{structure.pristine === false
        ? "选择拍号后在谱面点击目标小节"
        : "设置总体拍号"}</strong>
      <div className="toolbar-meter-options">
        {COMMON_METERS.map((candidate) => <button type="button"
          key={`${candidate.numerator}/${candidate.denominator}`}
          className="toolbar-meter-chip"
          aria-label={`${candidate.numerator}/${candidate.denominator} 拍`}
          aria-pressed={draft.numerator === candidate.numerator
            && draft.denominator === candidate.denominator
            && draft.symbol === candidate.symbol}
          onClick={() => setDraft(candidate)}>
          <BravuraTimeSignatureGlyph timeSignature={candidate} />
        </button>)}
      </div>
      <strong>自定义拍号</strong>
      <div className="toolbar-meter-custom">
        <select aria-label="拍号分子" value={draft.numerator}
          onChange={(event) => setDraft({
            numerator: Number(event.target.value), denominator: draft.denominator,
          })}>
          {Array.from({ length: 32 }, (_, index) => index + 1)
            .map((value) => <option key={value} value={value}>{value}</option>)}
        </select>
        <span>/</span>
        <select aria-label="拍号分母" value={draft.denominator}
          onChange={(event) => setDraft({
            numerator: draft.numerator, denominator: Number(event.target.value),
          })}>
          {[1, 2, 4, 8, 16, 32].map((value) =>
            <option key={value} value={value}>{value}</option>)}
        </select>
      </div>
      {groupings.length > 1 && <>
        <strong>默认分组 (beam)</strong>
        <div className="toolbar-meter-groupings">
          {groupings.map((groups) => <button type="button" key={groups.join("+")}
            aria-pressed={groups.join("+") === activeGrouping.join("+")}
            onClick={() => setDraft({ ...draft, beatGroups: groups })}>{groups.join("+")}</button>)}
        </div>
      </>}
      <button type="button" className="toolbar-menu-primary" onClick={() => {
        onChange(draft);
        setExpanded(false);
      }}>应用</button>
    </div>}
  </div>;
}

function PracticeInsertMeasuresDropdown({ update, defaultChordBeats, onInsert }) {
  const [expanded, setExpanded] = useState(false);
  const [positionValue, setPositionValue] = useState("END");
  const [count, setCount] = useState(1);
  const [chordBeats, setChordBeats] = useState(defaultChordBeats);
  const rootRef = useRef(null);
  const menuPosition = useToolbarMenuPosition(expanded, rootRef, setExpanded);
  const structure = update?.structure ?? {};
  const choices = [
    ["END", "在末尾插入"],
    ...(structure.selectedNoteMeasure != null
      ? [["AFTER_SELECTED_NOTE", "在所选音符之后的小节线插入"]] : []),
    ...(structure.selectedBarlineMeasure != null
      ? [["AT_SELECTED_BARLINE", "在所选小节线插入"]] : []),
  ];
  const effectivePosition = choices.some(([value]) => value === positionValue) ? positionValue : "END";
  return <div className="toolbar-key-setting" ref={rootRef}>
    <button type="button" className="toolbar-key-button" aria-haspopup="dialog"
      aria-expanded={expanded} onClick={() => {
        setChordBeats(defaultChordBeats);
        setExpanded((value) => !value);
      }}><ToolbarIcon icon={ListPlus} />插入小节</button>
    {expanded && <div className="toolbar-key-menu toolbar-insert-measures-menu" style={menuPosition}
      role="dialog" aria-label="插入小节">
      <strong>插入位置</strong>
      <div className="toolbar-measure-position-options">
        {choices.map(([value, label]) => <button type="button" key={value}
          aria-pressed={effectivePosition === value}
          onClick={() => setPositionValue(value)}>{label}</button>)}
      </div>
      <div className="toolbar-measure-settings">
        <ToolbarNumber label="小节数量" value={count} min={1} max={999} onChange={setCount} />
        <ToolbarNumber label="每个和弦拍数" value={chordBeats} min={1} max={16} onChange={setChordBeats} />
      </div>
      <button type="button" className="toolbar-menu-primary" onClick={() => {
        onInsert(effectivePosition, count, chordBeats);
        setExpanded(false);
      }}>插入</button>
    </div>}
  </div>;
}

function ToolbarNumber({ label, value, min, max, onChange }) {
  const [draft, setDraft] = useState(String(value));
  const editingRef = useRef(false);

  useEffect(() => {
    if (!editingRef.current) setDraft(String(value));
  }, [value]);

  const commitIfValid = (candidate) => {
    const parsed = Number(candidate);
    if (!Number.isInteger(parsed) || parsed < min || parsed > max) return false;
    onChange(parsed);
    return true;
  };
  const finishEditing = () => {
    editingRef.current = false;
    if (!commitIfValid(draft)) setDraft(String(value));
  };
  const step = (delta) => {
    const parsed = Number(draft);
    const base = Number.isInteger(parsed) ? parsed : value;
    const next = Math.max(min, Math.min(max, base + delta));
    setDraft(String(next));
    onChange(next);
  };

  return <div className="toolbar-setting"><span>{label}</span><span className="toolbar-stepper">
    <button type="button" aria-label={`${label}减一`} disabled={Number(draft) <= min}
      onClick={() => step(-1)}>−</button>
    <input type="number" inputMode="numeric" aria-label={label} value={draft} min={min} max={max}
      onFocus={() => { editingRef.current = true; }} onBlur={finishEditing}
      onKeyDown={(event) => { if (event.key === "Enter") event.currentTarget.blur(); }}
      onChange={(event) => {
        setDraft(event.target.value);
        commitIfValid(event.target.value);
      }} />
    <button type="button" aria-label={`${label}加一`} disabled={Number(draft) >= max}
      onClick={() => step(1)}>+</button>
  </span></div>;
}
