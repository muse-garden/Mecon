import React, { forwardRef, useEffect, useId, useImperativeHandle, useRef, useState, useSyncExternalStore } from "react";
import { hitTest, renderCanvas } from "@mecon/frozen-score";
import { createScoreEditorCommandController } from "./controller.js";
import { createScoreEditorDragController } from "./drag-controller.js";
import {
  selectedElementIds,
  selectionIdentity,
  selectionTargetForElement,
  surfacePointToGlobal,
} from "./interaction.js";
import { FULL_SCORE_EDITOR_TOOLBAR, resolveToolbarLayout } from "./toolbar.js";
import { DURATION_GLYPHS, SMUFL_GLYPHS } from "./music-glyphs.js";
import { scorePlayheadGeometry } from "./playback.js";
import { createControlledScrollSync } from "./scroll-sync.js";
import {
  ChevronLeft,
  ChevronRight,
  MousePointer2,
  Redo2,
  SquareDashedMousePointer,
  Undo2,
} from "lucide-react";

const SELECTABLE_ELEMENT_TYPES = [
  "NOTEHEAD", "ACCIDENTAL", "DOT", "REST", "ARTICULATION", "ORNAMENT", "DYNAMIC",
  "FERMATA", "HAIRPIN", "OCTAVE_SHIFT", "TEMPO_MARKING", "TEXT_ANNOTATION",
  "REHEARSAL_MARK", "PEDAL", "SLUR", "BARLINE", "VOLTA_ENDING", "NAVIGATION_MARK",
  "TIE", "BEAM",
];

const VISUALLY_HIDDEN_STYLE = {
  position: "absolute",
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: "hidden",
  clip: "rect(0, 0, 0, 0)",
  whiteSpace: "nowrap",
  border: 0,
};
const IDLE_PLAYBACK_SNAPSHOT = Object.freeze({ state: "idle", time: null });
const subscribeToNothing = () => () => {};
const idlePlaybackSnapshot = () => IDLE_PLAYBACK_SNAPSHOT;

const FLAGGED_NOTE_GLYPHS = new Set([
  SMUFL_GLYPHS.noteEighthUp,
  SMUFL_GLYPHS.note16thUp,
  SMUFL_GLYPHS.note32ndUp,
  SMUFL_GLYPHS.note64thUp,
  SMUFL_GLYPHS.note128thUp,
]);

function MusicGlyphButton({ glyph, label, active = false, className = "", ...props }) {
  const flaggedClass = FLAGGED_NOTE_GLYPHS.has(glyph) ? " flagged-note" : "";
  return <button type="button" className={`music-glyph-button${flaggedClass}${active ? " active" : ""} ${className}`.trim()}
    aria-label={label} title={label} {...props}><span aria-hidden="true">{glyph}</span></button>;
}

function EditorIconButton({ icon: Icon, label, active = false, className = "", ...props }) {
  return <button type="button" className={`editor-icon-button${active ? " active" : ""} ${className}`.trim()}
    aria-label={label} title={label} {...props}><Icon aria-hidden="true" size={17} strokeWidth={1.8} /></button>;
}

function CurveNotePairButton({ samePitch, label, active = false, disabled = false, onClick }) {
  return <button type="button"
    className={`music-glyph-button music-glyph-composite curve ${samePitch ? "tie" : "slur"}${active ? " active" : ""}`}
    aria-label={label} title={label} aria-pressed={active} disabled={disabled} onClick={onClick}>
    <span className="curve-note left" aria-hidden="true">{SMUFL_GLYPHS.noteQuarterUp}</span>
    <span className="curve-note right" aria-hidden="true">{SMUFL_GLYPHS.noteQuarterUp}</span>
    <svg className="curve-arc" viewBox="0 0 46 28" preserveAspectRatio="none" aria-hidden="true">
      {samePitch
        ? <path d="M 9 13 Q 23 4 37 13 Q 23 6.5 9 13 Z" />
        : <path d="M 9 17 Q 23 5 37 9 Q 23 7.5 9 17 Z" />}
    </svg>
  </button>;
}

function BeamPatternButton({ label, beamLeft = false, beamRight = false, isGroup = false,
  active = false, disabled = false, onClick }) {
  return <button type="button"
    className={`music-glyph-button beam-button${beamLeft ? " beam-left" : ""}${beamRight ? " beam-right" : ""}${active ? " active" : ""}${isGroup ? " group" : ""}`}
    aria-label={label} title={label} aria-pressed={active} disabled={disabled} onClick={onClick}>
    {isGroup ? <svg className="beam-pattern-svg beam-group-symbol" viewBox="0 0 28 28" aria-hidden="true">
      <ellipse className="beam-notehead" cx="8" cy="21.5" rx="4" ry="2.8"
        transform="rotate(-18 8 21.5)" />
      <ellipse className="beam-notehead" cx="19" cy="19.5" rx="4" ry="2.8"
        transform="rotate(-18 19 19.5)" />
      <path className="beam-stems" d="M 11.4 20.5 L 11.4 6.4 M 22.4 18.5 L 22.4 4.4" />
      <path className="beam-bar" d="M 10.7 5.2 L 23.1 3 L 23.1 6 L 10.7 8.2 Z" />
      <path className="beam-bracket" d="M 2.5 4 L 5 4 M 2.5 4 L 2.5 24 M 2.5 24 L 5 24 M 25.5 4 L 23 4 M 25.5 4 L 25.5 24 M 25.5 24 L 23 24" />
    </svg> : <svg className="beam-pattern-svg beam-note-symbol" viewBox="0 0 28 28" aria-hidden="true">
      <ellipse className="beam-notehead" cx="14" cy="21.5" rx="4.2" ry="2.9"
        transform="rotate(-18 14 21.5)" />
      <path className="beam-stems" d="M 17.6 20.5 L 17.6 4" />
      {beamLeft && <path className="beam-bar" d="M 2 3 L 18.3 3 L 18.3 6 L 2 6 Z" />}
      {beamRight && <path className="beam-bar" d="M 16.9 3 L 27 3 L 27 6 L 16.9 6 Z" />}
    </svg>}
  </button>;
}

/** Stable click-selection and canvas-coordinate adapter shared by editor hosts. */
export function createScoreEditorSelectionController({
  frame, surfaceIndex, canvasRef, suppressClickRef, dispatch, tool = "select", onNoteInput,
  onNoteHover, onNoteHoverEnd,
}) {
  function canvasPoint(event) {
    const canvas = canvasRef.current;
    if (!canvas || !frame) return null;
    const rect = canvas.getBoundingClientRect();
    const surface = frame.bundle.surfaces.find((item) => item.index === surfaceIndex)
      ?? frame.bundle.surfaces[surfaceIndex];
    if (!surface || rect.width <= 0 || rect.height <= 0) return null;
    return {
      surface,
      x: (event.clientX - rect.left) * surface.width / rect.width,
      y: (event.clientY - rect.top) * surface.height / rect.height,
    };
  }

  function onClick(event) {
    if (suppressClickRef.current) {
      suppressClickRef.current = false;
      return;
    }
    const point = canvasPoint(event);
    if (!frame || !point) return;
    if (tool === "note") {
      onNoteInput?.(surfacePointToGlobal(frame.bundle, point.surface, point.x, point.y));
      return;
    }
    const element = hitTest(frame.bundle, surfaceIndex, point.x, point.y, {
      padding: 3,
      types: SELECTABLE_ELEMENT_TYPES,
    });
    const target = selectionTargetForElement(frame.update, element);
    const current = frame.update.selection;
    const additive = event.ctrlKey || event.metaKey || event.shiftKey;
    const same = (left, right) => selectionIdentity(left) === selectionIdentity(right);
    const targets = !target ? [] : additive
      ? (current.some((item) => same(item, target))
        ? current.filter((item) => !same(item, target))
        : [...current, target])
      : [target];
    dispatch({ type: "setSelection", targets });
  }

  function onPointerMove(event) {
    if (tool !== "note") return;
    const point = canvasPoint(event);
    if (!frame || !point) {
      onNoteHoverEnd?.();
      return;
    }
    onNoteHover?.(surfacePointToGlobal(frame.bundle, point.surface, point.x, point.y));
  }

  function onPointerLeave() {
    if (tool === "note") onNoteHoverEnd?.();
  }

  return { canvasPoint, onClick, onPointerMove, onPointerLeave };
}

/** Reusable form state for note input and event editing controls. */
export function useScoreEditorInputState() {
  const [insertMeasure, setInsertMeasure] = useState(1);
  const [insertBeat, setInsertBeat] = useState(0);
  const [insertPitch, setInsertPitch] = useState(0);
  const [insertChordPitches, setInsertChordPitches] = useState("0,2,4");
  const [insertDuration, setInsertDuration] = useState("QUARTER");
  const [insertDots, setInsertDots] = useState(0);
  const [editDuration, setEditDuration] = useState("QUARTER");
  const [editDots, setEditDots] = useState(0);
  const [graceMode, setGraceMode] = useState(false);
  const [graceNoteType, setGraceNoteType] = useState("APPOGGIATURA");
  const [graceDuration, setGraceDuration] = useState("EIGHTH");
  const [graceTimeSource, setGraceTimeSource] = useState("PRINCIPAL");
  const [tupletCount, setTupletCount] = useState(0);
  const [articulation, setArticulation] = useState("STACCATO");
  const [arpeggioType, setArpeggioType] = useState("UP");
  const [restStaffPosition, setRestStaffPosition] = useState(0);
  const [moveDestination, setMoveDestination] = useState("");
  const [stepInputEnabled, setStepInputEnabled] = useState(false);
  const [midiStatus, setMidiStatus] = useState("MIDI 未连接");
  const [editorTool, setEditorTool] = useState("select");
  const [paletteExpanded, setPaletteExpanded] = useState(true);
  const [uncommonDurationsExpanded, setUncommonDurationsExpanded] = useState(false);
  const [articulationsExpanded, setArticulationsExpanded] = useState(false);
  const [restMode, setRestMode] = useState(false);
  const [customTupletText, setCustomTupletText] = useState("");
  const [insertionBeaming, setInsertionBeaming] = useState(null);
  return {
    insertMeasure, setInsertMeasure, insertBeat, setInsertBeat, insertPitch, setInsertPitch,
    insertChordPitches, setInsertChordPitches, insertDuration, setInsertDuration,
    insertDots, setInsertDots, editDuration, setEditDuration, editDots, setEditDots,
    graceMode, setGraceMode, graceNoteType, setGraceNoteType, graceDuration, setGraceDuration,
    graceTimeSource, setGraceTimeSource, tupletCount, setTupletCount,
    articulation, setArticulation, arpeggioType, setArpeggioType,
    restStaffPosition, setRestStaffPosition, moveDestination, setMoveDestination,
    stepInputEnabled, setStepInputEnabled, midiStatus, setMidiStatus,
    editorTool, setEditorTool, paletteExpanded, setPaletteExpanded,
    uncommonDurationsExpanded, setUncommonDurationsExpanded,
    articulationsExpanded, setArticulationsExpanded, restMode, setRestMode,
    customTupletText, setCustomTupletText, insertionBeaming, setInsertionBeaming,
  };
}

/** Reusable form state for measure, signature, repeat, navigation, and slur controls. */
export function useScoreEditorStructureState() {
  const [structureCount, setStructureCount] = useState(1);
  const [clefValue, setClefValue] = useState("TREBLE");
  const [keyValue, setKeyValue] = useState("0|MAJOR");
  const [meterNumerator, setMeterNumerator] = useState(4);
  const [meterDenominator, setMeterDenominator] = useState(4);
  const [boundaryMeasure, setBoundaryMeasure] = useState(0);
  const [targetBoundaryMeasure, setTargetBoundaryMeasure] = useState(1);
  const [barlineValue, setBarlineValue] = useState("SINGLE");
  const [repeatCount, setRepeatCount] = useState(2);
  const [navigationMark, setNavigationMark] = useState("SEGNO");
  const [slurDraft, setSlurDraft] = useState(null);
  return {
    structureCount, setStructureCount, clefValue, setClefValue, keyValue, setKeyValue,
    meterNumerator, setMeterNumerator, meterDenominator, setMeterDenominator,
    boundaryMeasure, setBoundaryMeasure, targetBoundaryMeasure, setTargetBoundaryMeasure,
    barlineValue, setBarlineValue, repeatCount, setRepeatCount,
    navigationMark, setNavigationMark, slurDraft, setSlurDraft,
  };
}

/** Reusable form state for notation attachments, layout, and performance controls. */
export function useScoreEditorExpressionState() {
  const [expressionEndMeasure, setExpressionEndMeasure] = useState(2);
  const [dynamicLevel, setDynamicLevel] = useState("MF");
  const [hairpinType, setHairpinType] = useState("CRESCENDO");
  const [hairpinStyle, setHairpinStyle] = useState("WEDGE");
  const [octaveShiftType, setOctaveShiftType] = useState("OTTAVA");
  const [tempoMarkType, setTempoMarkType] = useState("METRONOME");
  const [tempoBpm, setTempoBpm] = useState(120);
  const [layoutBreakKind, setLayoutBreakKind] = useState("SYSTEM");
  const [visibilityEndMeasure, setVisibilityEndMeasure] = useState(1);
  const [ornamentKind, setOrnamentKind] = useState("TRILL");
  const [ornamentOscillations, setOrnamentOscillations] = useState(4);
  const [performanceAmount, setPerformanceAmount] = useState(1.5);
  const [tempoDisplayStyle, setTempoDisplayStyle] = useState("METRONOME");
  return {
    expressionEndMeasure, setExpressionEndMeasure, dynamicLevel, setDynamicLevel,
    hairpinType, setHairpinType, hairpinStyle, setHairpinStyle,
    octaveShiftType, setOctaveShiftType, tempoMarkType, setTempoMarkType,
    tempoBpm, setTempoBpm, layoutBreakKind, setLayoutBreakKind,
    visibilityEndMeasure, setVisibilityEndMeasure, ornamentKind, setOrnamentKind,
    ornamentOscillations, setOrnamentOscillations, performanceAmount, setPerformanceAmount,
    tempoDisplayStyle, setTempoDisplayStyle,
  };
}

/** Complete controlled editor state and interaction adapters shared by every React host. */
export function useScoreEditorController({
  frame, dispatch, requestTransposePreview, requestRestMovePreview, requestNoteInputTarget,
}) {
  const canvasRef = useRef(null);
  const dragRef = useRef(null);
  const suppressClickRef = useRef(false);
  const [dragPreview, setDragPreview] = useState(null);
  const [noteInputPreview, setNoteInputPreview] = useState(null);
  const notePreviewRequestRef = useRef(0);
  const lastNoteInputTransitionRevisionRef = useRef(null);
  const notePreviewPointerVersionRef = useRef(0);
  const notePreviewPendingRef = useRef(false);
  const notePreviewPointRef = useRef(null);
  const notePreviewContextRef = useRef(null);
  const [surfaceIndex, setSurfaceIndex] = useState(0);
  const input = useScoreEditorInputState();
  const structure = useScoreEditorStructureState();
  const expression = useScoreEditorExpressionState();
  const destinations = Object.values(frame?.update.score.staffTracks ?? {}).flatMap((staff) =>
    (staff.voiceTrackIds ?? []).map((voiceTrackId) => {
      const voice = frame?.update.score.voiceTracks?.[voiceTrackId];
      return {
        value: `${staff.id}|${voice?.voiceNumber ?? 1}`,
        staffId: staff.id,
        voiceNumber: voice?.voiceNumber ?? 1,
        label: `${staff.name ?? staff.id} · 声部 ${voice?.voiceNumber ?? 1}`,
      };
    }),
  );
  const commands = createScoreEditorCommandController({
    update: frame?.update,
    input,
    structure,
    expression,
    destinations,
    dispatch,
  });
  const noteInsertion = {
    duration: { base: input.insertDuration, dots: Number(input.insertDots) || 0 },
    isRest: input.restMode,
    decoration: {
      ...(Number(input.tupletCount) > 1 ? { tupletCount: Number(input.tupletCount) } : {}),
      ...(input.graceMode ? {
        grace: {
          totalDuration: { base: input.graceDuration },
          stealFrom: input.graceTimeSource,
          noteType: input.graceNoteType,
        },
      } : {}),
      ...(input.insertionBeaming ? { beaming: input.insertionBeaming } : {}),
    },
  };
  const noteInputRequest = (point) => ({
    ...point,
    duration: noteInsertion.duration,
    restMode: noteInsertion.isRest,
    voiceNumber: destinations.find((item) => item.value === input.moveDestination)?.voiceNumber ?? 1,
    ...(Number(input.tupletCount) > 1 ? { tupletCount: Number(input.tupletCount) } : {}),
    graceMode: input.graceMode,
  });
  notePreviewContextRef.current = { requestNoteInputTarget, noteInputRequest };

  const flushNotePreview = () => {
    const pendingPoint = notePreviewPointRef.current;
    const context = notePreviewContextRef.current;
    if (!pendingPoint || notePreviewPendingRef.current || !context?.requestNoteInputTarget) return;
    notePreviewPointRef.current = null;
    notePreviewPendingRef.current = true;
    const requestId = notePreviewRequestRef.current;
    context.requestNoteInputTarget(context.noteInputRequest(pendingPoint.point), (preview) => {
      notePreviewPendingRef.current = false;
      if (requestId === notePreviewRequestRef.current &&
          pendingPoint.version === notePreviewPointerVersionRef.current) {
        setNoteInputPreview(preview);
      }
      if (notePreviewPointRef.current) flushNotePreview();
    });
  };
  const requestNotePreview = (point) => {
    notePreviewPointerVersionRef.current += 1;
    notePreviewPointRef.current = { point, version: notePreviewPointerVersionRef.current };
    flushNotePreview();
  };
  const clearNotePreview = () => {
    notePreviewRequestRef.current += 1;
    notePreviewPointerVersionRef.current += 1;
    notePreviewPointRef.current = null;
    setNoteInputPreview(null);
  };
  const selection = createScoreEditorSelectionController({
    frame,
    surfaceIndex,
    canvasRef,
    suppressClickRef,
    dispatch,
    tool: input.editorTool,
    onNoteHover: requestNotePreview,
    onNoteHoverEnd: clearNotePreview,
    onNoteInput: (point) => {
      if (!requestNoteInputTarget) return;
      requestNoteInputTarget(noteInputRequest(point), (target) => {
        if (target) commands.insertEventAtPointer(target, noteInsertion);
      });
    },
  });
  const drag = createScoreEditorDragController({
    frame,
    surfaceIndex,
    canvasPoint: selection.canvasPoint,
    dragRef,
    suppressClickRef,
    setDragPreview,
    requestTransposePreview,
    requestRestMovePreview,
    dispatch,
    tool: input.editorTool,
  });

  useEffect(() => {
    if (!destinations.some((item) => item.value === input.moveDestination)) {
      input.setMoveDestination(destinations[0]?.value ?? "");
    }
  }, [frame, input.moveDestination]);

  useEffect(() => {
    const selected = frame?.update.selection.find((target) => target.type === "slur");
    structure.setSlurDraft(selected ? frame?.geometry?.slurs?.[selected.slurId] ?? null : null);
  }, [frame]);

  useEffect(() => {
    const transition = frame?.update.noteInputTransition;
    if (!transition) return;
    if (lastNoteInputTransitionRevisionRef.current === frame.update.revision) return;
    lastNoteInputTransitionRevisionRef.current = frame.update.revision;
    input.setInsertDuration(transition.duration.base);
    input.setInsertDots(Number(transition.duration.dots) || 0);
    input.setTupletCount(transition.tupletCount ?? 0);
  }, [frame?.update.revision, frame?.update.noteInputTransition]);

  useEffect(() => {
    clearNotePreview();
  }, [frame, surfaceIndex, input.editorTool, input.insertDuration, input.insertDots,
    input.restMode, input.moveDestination, input.tupletCount, input.graceMode]);

  return {
    frame,
    dispatch,
    canvasRef,
    dragRef,
    input,
    structure,
    expression,
    destinations,
    commands,
    surfaceIndex,
    setSurfaceIndex,
    dragPreview,
    noteInputPreview,
    selection,
    drag,
  };
}

/** Complete note-input inspector shared by standalone and workbench hosts. */
export function ScoreEditorInputInspector({ frame, state, commands, dispatch, onEnableMidi }) {
  const {
    insertMeasure, setInsertMeasure, insertBeat, setInsertBeat, insertPitch, setInsertPitch,
    insertChordPitches, setInsertChordPitches, insertDuration, setInsertDuration,
    insertDots, setInsertDots, graceMode, setGraceMode, graceNoteType, setGraceNoteType,
    graceDuration, setGraceDuration, graceTimeSource, setGraceTimeSource,
    tupletCount, setTupletCount, stepInputEnabled, setStepInputEnabled, midiStatus,
  } = state;
  const targets = commands.eventTargets();
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>步进输入</legend>
    <label>小节<input type="number" min="1" value={insertMeasure}
      onChange={(event) => setInsertMeasure(event.target.value)} /></label>
    <label>四分拍<input type="text" inputMode="decimal" value={insertBeat}
      onChange={(event) => setInsertBeat(event.target.value)} /></label>
    <label>C4 起音级<input type="number" value={insertPitch}
      onChange={(event) => setInsertPitch(event.target.value)} /></label>
    <label>和弦音级（逗号分隔）<input value={insertChordPitches}
      onChange={(event) => setInsertChordPitches(event.target.value)} /></label>
    <label>时值<select aria-label="输入时值" value={insertDuration}
      onChange={(event) => setInsertDuration(event.target.value)}>
      <option value="WHOLE">全音符</option><option value="HALF">二分</option>
      <option value="QUARTER">四分</option><option value="EIGHTH">八分</option>
      <option value="SIXTEENTH">十六分</option>
    </select></label>
    <label>附点<select aria-label="输入附点" value={insertDots}
      onChange={(event) => setInsertDots(event.target.value)}>
      <option value="0">无附点</option><option value="1">单附点</option>
      <option value="2">双附点</option><option value="3">三附点</option>
    </select></label>
    <label>连音组<select value={tupletCount} onChange={(event) => setTupletCount(event.target.value)}>
      <option value="0">普通</option>{[2, 3, 4, 5, 6, 7, 8, 9].map(
        (value) => <option key={value} value={value}>{value} 连音</option>,
      )}
    </select></label>
    <div className="insert-actions">
      <button onClick={() => commands.insertEvent(false)}>插入音符</button>
      <button onClick={commands.insertChord}>插入和弦</button>
      <button onClick={() => commands.insertEvent(true)}>插入休止</button>
    </div>
    <label><input type="checkbox" checked={stepInputEnabled}
      onChange={(event) => setStepInputEnabled(event.target.checked)} /> 键盘步进（A–J，R 休止）</label>
    <div className="insert-actions">
      <button type="button" onClick={onEnableMidi}>连接 MIDI</button>
      <span aria-live="polite">{midiStatus}</span>
    </div>
    <label><input type="checkbox" checked={graceMode}
      onChange={(event) => setGraceMode(event.target.checked)} /> 倚音输入</label>
    <label>倚音类型<select value={graceNoteType}
      onChange={(event) => setGraceNoteType(event.target.value)}>
      <option value="APPOGGIATURA">倚音</option><option value="ACCIACCATURA">短倚音</option>
    </select></label>
    <label>倚音组总时值<select value={graceDuration}
      onChange={(event) => setGraceDuration(event.target.value)}>
      {["WHOLE", "HALF", "QUARTER", "EIGHTH", "SIXTEENTH"].map(
        (value) => <option key={value}>{value}</option>,
      )}
    </select></label>
    <label>占用时值<select value={graceTimeSource}
      onChange={(event) => setGraceTimeSource(event.target.value)}>
      <option value="PRINCIPAL">之后主音</option><option value="PREVIOUS">之前音符</option>
    </select></label>
    <button disabled={!targets.length} onClick={() => commands.editSelection(
      "setGraceGroups", {}, (target) => ({
        ...target, totalDuration: { base: graceDuration }, stealFrom: graceTimeSource,
      }),
    )}>更新所选倚音组</button>
    <div className="insert-actions">
      <button disabled={!targets.length || Number(tupletCount) < 2} onClick={() => dispatch({
        type: "applyTuplets", targets: commands.groupedEventTargets(Number(tupletCount)),
      })}>所选设为连音组</button>
      <button disabled={!commands.selectionIsOnlyRests()} onClick={() => dispatch({
        type: "createSmallNoteRegions", targets: commands.groupedEventTargets(),
      })}>创建小音符休止区</button>
    </div>
  </fieldset>;
}

/** Layout and staff-visibility controls for the complete editor profile. */
export function ScoreEditorLayoutInspector({ frame, input, expression, commands, dispatch }) {
  const { insertMeasure } = input;
  const {
    layoutBreakKind, setLayoutBreakKind, visibilityEndMeasure, setVisibilityEndMeasure,
  } = expression;
  const setVisibility = (hidden) => {
    const staffTrackId = commands.targetStaffId();
    if (!staffTrackId) return;
    dispatch({
      type: "setStaffVisibility",
      staffTrackIds: [staffTrackId],
      startMeasure: Math.max(1, Number(insertMeasure) || 1),
      endMeasure: Math.max(1, Number(visibilityEndMeasure) || 1),
      hidden,
    });
  };
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>布局与谱表可见性</legend>
    <p>在“步进输入”的小节之前强制换行/分页；可见性范围从该小节开始。</p>
    <label>布局断点<select value={layoutBreakKind}
      onChange={(event) => setLayoutBreakKind(event.target.value)}>
      <option value="SYSTEM">系统换行</option><option value="PAGE">分页</option>
    </select></label>
    <div className="insert-actions">
      <button onClick={() => dispatch({
        type: "setLayoutBreak",
        beforeMeasure: Math.max(2, Number(insertMeasure) || 2),
        kind: layoutBreakKind,
      })}>设置断点</button>
      <button onClick={() => dispatch({
        type: "setLayoutBreak",
        beforeMeasure: Math.max(2, Number(insertMeasure) || 2),
        kind: null,
      })}>清除断点</button>
    </div>
    <label>可见性终点小节<input type="number" min="1" value={visibilityEndMeasure}
      onChange={(event) => setVisibilityEndMeasure(event.target.value)} /></label>
    <div className="insert-actions">
      <button onClick={() => setVisibility(true)}>隐藏目标谱表</button>
      <button onClick={() => setVisibility(false)}>显示目标谱表</button>
    </div>
  </fieldset>;
}

/** Expression, ornament, tempo, performance, and rest-position controls. */
export function ScoreEditorExpressionInspector({ frame, input, expression, commands, dispatch }) {
  const {
    expressionEndMeasure, setExpressionEndMeasure, dynamicLevel, setDynamicLevel,
    hairpinType, setHairpinType, hairpinStyle, setHairpinStyle,
    octaveShiftType, setOctaveShiftType, tempoMarkType, setTempoMarkType,
    tempoBpm, setTempoBpm, ornamentKind, setOrnamentKind,
    ornamentOscillations, setOrnamentOscillations,
    performanceAmount, setPerformanceAmount, tempoDisplayStyle, setTempoDisplayStyle,
  } = expression;
  const { restStaffPosition, setRestStaffPosition } = input;
  const selectedAttachment = commands.selectedStructure("attachment");
  const hasEvents = commands.eventTargets().length > 0;
  const gradualTempo = (markType) => dispatch({
    type: "addGradualTempo",
    start: commands.expressionTime(),
    end: commands.expressionTime(expressionEndMeasure),
    markType,
  });
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>表情与演奏记号</legend>
    <p>起点沿用“步进输入”的小节和拍；谱表沿用目标声部。区间终点按小节设置。</p>
    <label>区间终点小节<input type="number" min="1" value={expressionEndMeasure}
      onChange={(event) => setExpressionEndMeasure(event.target.value)} /></label>
    <label>力度<select value={dynamicLevel} onChange={(event) => setDynamicLevel(event.target.value)}>
      {["PPP", "PP", "P", "MP", "MF", "F", "FF", "FFF", "FP", "SFZ"].map(
        (value) => <option key={value}>{value}</option>,
      )}
    </select></label>
    <button onClick={() => commands.addPointExpression("addDynamic", { level: dynamicLevel })}>添加力度</button>
    <label>发夹<select value={hairpinType} onChange={(event) => setHairpinType(event.target.value)}>
      <option value="CRESCENDO">渐强</option><option value="DIMINUENDO">渐弱</option>
    </select></label>
    <label>发夹样式<select value={hairpinStyle} onChange={(event) => setHairpinStyle(event.target.value)}>
      <option value="WEDGE">楔形</option><option value="TEXT_DASHED">文字虚线</option>
    </select></label>
    <button onClick={() => commands.addSpanExpression("addHairpin", {
      hairpinType, style: hairpinStyle,
    })}>添加发夹</button>
    <label>八度线<select value={octaveShiftType} onChange={(event) => setOctaveShiftType(event.target.value)}>
      <option value="OTTAVA">8va</option><option value="OTTAVA_BASSA">8vb</option>
    </select></label>
    <button onClick={() => commands.addSpanExpression("addOctaveShift", {
      shiftType: octaveShiftType,
    })}>添加八度线</button>
    <label>速度类型<select value={tempoMarkType} onChange={(event) => setTempoMarkType(event.target.value)}>
      {["METRONOME", "PIU_MOSSO", "MENO_MOSSO", "A_TEMPO", "TEMPO_I"].map(
        (value) => <option key={value}>{value}</option>,
      )}
    </select></label>
    <label>BPM<input type="number" min="10" max="600" value={tempoBpm}
      onChange={(event) => setTempoBpm(event.target.value)} /></label>
    <div className="insert-actions">
      <button onClick={() => dispatch({
        type: "addTempoMark", onset: commands.expressionTime(), markType: tempoMarkType,
        bpm: Math.max(10, Number(tempoBpm) || 120),
      })}>添加速度</button>
      <button onClick={() => gradualTempo("ACCELERANDO")}>渐快</button>
      <button onClick={() => gradualTempo("RITARDANDO")}>渐慢</button>
      <button onClick={() => commands.addPointExpression("addFermata")}>延长记号</button>
      <button onClick={() => commands.addPointExpression(
        "addBreathMark", { scope: "STAFF", shape: "COMMA" },
      )}>换气记号</button>
      <button disabled={!selectedAttachment} onClick={commands.deleteSelectedExpression}>删除所选记号</button>
    </div>
    <label>装饰音<select value={ornamentKind} onChange={(event) => setOrnamentKind(event.target.value)}>
      {["TRILL", "MORDENT", "INVERTED_MORDENT", "TURN", "INVERTED_TURN"].map(
        (value) => <option key={value}>{value}</option>,
      )}
    </select></label>
    <button disabled={!hasEvents} onClick={commands.addOrnamentToSelection}>添加到所选音符</button>
    <label>装饰振荡次数<input type="number" min="1" max="16" value={ornamentOscillations}
      onChange={(event) => setOrnamentOscillations(event.target.value)} /></label>
    <button disabled={!selectedAttachment} onClick={() => commands.updateSelectedAttachment(
      "updateOrnament", (ornamentId) => ({
        ornamentId, oscillations: Math.max(1, Number(ornamentOscillations) || 4),
      }),
    )}>更新所选装饰</button>
    <label>速度显示<select value={tempoDisplayStyle}
      onChange={(event) => setTempoDisplayStyle(event.target.value)}>
      <option value="METRONOME">节拍器</option><option value="TEXT">文字</option>
      <option value="HIDDEN">隐藏</option>
    </select></label>
    <button disabled={!selectedAttachment} onClick={() => commands.updateSelectedAttachment(
      "updateTempo", (tempoId) => ({
        tempoId, effectiveBpm: Math.max(10, Number(tempoBpm) || 120), displayStyle: tempoDisplayStyle,
      }),
    )}>更新所选速度</button>
    <label>延长/换气量<input type="number" min="0.125" step="0.125" value={performanceAmount}
      onChange={(event) => setPerformanceAmount(event.target.value)} /></label>
    <button disabled={!selectedAttachment} onClick={() => {
      const amount = Math.max(0.125, Number(performanceAmount) || 1);
      commands.updateSelectedAttachment("updatePerformanceMark", (markId) => ({
        markId, amount: { numerator: Math.round(amount * 1000), denominator: 1000 },
      }));
    }}>更新所选停顿量</button>
    <label>休止符谱表位置<input type="number" min="-12" max="12" value={restStaffPosition}
      onChange={(event) => setRestStaffPosition(event.target.value)} /></label>
    <button disabled={!hasEvents} onClick={() => commands.editSelection(
      "moveRests", {}, (target) => ({ ...target, staffPosition: Number(restStaffPosition) || 0 }),
    )}>移动所选休止符</button>
  </fieldset>;
}

/** Clef, key, meter, and measure-structure controls. */
export function ScoreEditorStructureInspector({ frame, input, structure, commands, dispatch, onDeleteMeasure }) {
  const { insertMeasure } = input;
  const {
    structureCount, setStructureCount, clefValue, setClefValue, keyValue, setKeyValue,
    meterNumerator, setMeterNumerator, meterDenominator, setMeterDenominator,
  } = structure;
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>谱面结构</legend>
    <p>沿用上方“小节”作为编辑位置；第 1 小节代表开头。</p>
    <label>谱号<select value={clefValue} onChange={(event) => setClefValue(event.target.value)}>
      <option value="TREBLE">高音</option><option value="BASS">低音</option>
      <option value="ALTO">中音</option><option value="TENOR">次中音</option>
      <option value="PERCUSSION">打击乐</option>
    </select></label>
    <button onClick={commands.applyClef}>设置谱号</button>
    <label>调号<select value={keyValue} onChange={(event) => setKeyValue(event.target.value)}>
      <option value="0|MAJOR">C 大调</option><option value="7|MAJOR">G 大调</option>
      <option value="2|MAJOR">D 大调</option><option value="5|MAJOR">F 大调</option>
      <option value="9|MINOR">a 小调</option>
    </select></label>
    <button onClick={commands.applyKeySignature}>设置调号</button>
    <label>拍号分子<input type="number" min="1" value={meterNumerator}
      onChange={(event) => setMeterNumerator(event.target.value)} /></label>
    <label>拍号分母<select value={meterDenominator}
      onChange={(event) => setMeterDenominator(event.target.value)}>
      {[1, 2, 4, 8, 16, 32].map((value) => <option key={value} value={value}>{value}</option>)}
    </select></label>
    <button onClick={commands.applyTimeSignature}>设置拍号</button>
    <label>小节数量<input type="number" min="1" value={structureCount}
      onChange={(event) => setStructureCount(event.target.value)} /></label>
    <div className="insert-actions">
      <button onClick={() => dispatch({
        type: "insertMeasures",
        afterMeasure: Math.max(0, Number(insertMeasure) || 0),
        count: Math.max(1, Number(structureCount) || 1),
      })}>插入小节</button>
      <button onClick={onDeleteMeasure}>删除小节</button>
    </div>
  </fieldset>;
}

/** Repeat barline, volta, and navigation controls. */
export function ScoreEditorRepeatInspector({ frame, structure, commands, dispatch }) {
  const {
    boundaryMeasure, setBoundaryMeasure, targetBoundaryMeasure, setTargetBoundaryMeasure,
    barlineValue, setBarlineValue, repeatCount, setRepeatCount,
    navigationMark, setNavigationMark,
  } = structure;
  const navigationSelected = commands.selectedStructure("navigationMark");
  const voltaSelected = commands.selectedStructure("voltaEnding");
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>反复、房子与导航</legend>
    <p>边界 0 是谱首；其余数字表示对应小节右侧边界。房子和导航记号可在画布上选择。</p>
    <label>边界<input type="number" min="0" value={boundaryMeasure}
      onChange={(event) => setBoundaryMeasure(event.target.value)} /></label>
    <label>小节线<select value={barlineValue} onChange={(event) => setBarlineValue(event.target.value)}>
      {["SINGLE", "DOUBLE", "FINAL", "REVERSE_FINAL", "DASHED", "DOTTED", "SHORT", "TICK",
        "REPEAT_LEFT", "REPEAT_RIGHT", "REPEAT_BOTH"].map(
        (value) => <option key={value} value={value}>{value}</option>,
      )}
    </select></label>
    <label>反复次数<input type="number" min="2" value={repeatCount}
      onChange={(event) => setRepeatCount(event.target.value)} /></label>
    <div className="insert-actions">
      <button onClick={commands.setBarline}>设置小节线</button>
      <button onClick={() => dispatch({
        type: "setBarlineRepeatCount",
        boundaryMeasure: Math.max(0, Number(boundaryMeasure) || 0),
        repeatCount: Math.max(2, Number(repeatCount) || 2),
      })}>更新反复次数</button>
      <button onClick={() => dispatch({
        type: "toggleVoltaPair",
        boundaryMeasure: Math.max(0, Number(boundaryMeasure) || 0),
      })}>切换 1/2 房子</button>
    </div>
    <label>导航记号<select value={navigationMark}
      onChange={(event) => setNavigationMark(event.target.value)}>
      {["SEGNO", "CODA", "TO_CODA", "FINE", "DA_CAPO", "DAL_SEGNO", "DA_CAPO_AL_FINE",
        "DAL_SEGNO_AL_FINE", "DA_CAPO_AL_CODA", "DAL_SEGNO_AL_CODA"].map(
        (value) => <option key={value} value={value}>{value}</option>,
      )}
    </select></label>
    <button onClick={() => dispatch({
      type: "toggleNavigationMark",
      boundaryMeasure: Math.max(0, Number(boundaryMeasure) || 0),
      mark: navigationMark,
    })}>切换导航记号</button>
    <label>移动/伸缩目标<input type="number" min="0" value={targetBoundaryMeasure}
      onChange={(event) => setTargetBoundaryMeasure(event.target.value)} /></label>
    <div className="insert-actions">
      <button disabled={!navigationSelected} onClick={commands.moveSelectedNavigation}>移动所选导航</button>
      <button disabled={!navigationSelected} onClick={commands.deleteSelectedNavigation}>删除所选导航</button>
      <button disabled={!voltaSelected} onClick={() => commands.resizeSelectedVolta("first")}>移动第一房子左端</button>
      <button disabled={!voltaSelected} onClick={() => commands.resizeSelectedVolta("second")}>移动第二房子右端</button>
      <button disabled={!voltaSelected} onClick={commands.deleteSelectedVolta}>删除所选房子</button>
    </div>
  </fieldset>;
}

/** Slur creation, deletion, direction, and geometry controls. */
export function ScoreEditorSlurInspector({ frame, structure, commands }) {
  const { slurDraft, setSlurDraft } = structure;
  const updateDraft = (field, value) => setSlurDraft(
    (current) => current ? { ...current, [field]: Number(value) || 0 } : current,
  );
  return <fieldset className="insert-panel" disabled={!frame}>
    <legend>连音线</legend>
    <p>按 Ctrl/⌘ 或 Shift 在画布上选择同一声部的两个音符后创建；选择连音线可编辑自动捕获的锚点与曲线。</p>
    <div className="insert-actions">
      <button disabled={commands.eventTargets().length !== 2}
        onClick={commands.addSlurFromSelection}>连接所选音符</button>
      <button disabled={!commands.selectedStructure("slur")}
        onClick={commands.deleteSelectedSlur}>删除所选连音线</button>
      <button disabled={!slurDraft}
        onClick={() => commands.updateSlurGeometry({ above: true, directionOnly: true })}>上弓</button>
      <button disabled={!slurDraft}
        onClick={() => commands.updateSlurGeometry({ above: false, directionOnly: true })}>下弓</button>
    </div>
    {slurDraft && <>
      {[["startDx", "起点 X"], ["startDy", "起点 Y"], ["endDx", "终点 X"],
        ["endDy", "终点 Y"], ["minApex", "最小弧高"], ["maxApex", "最大弧高"]].map(
        ([field, label]) => <label key={field}>{label}<input type="number" step="0.1"
          value={slurDraft[field]} onChange={(event) => updateDraft(field, event.target.value)} /></label>,
      )}
      <button onClick={() => commands.updateSlurGeometry({ directionOnly: false })}>应用曲线控制点</button>
    </>}
  </fieldset>;
}

/** Builds the complete stable-id toolbar registry for a score-editor host. */
export function createScoreEditorToolbarControls({
  frame, input, commands, destinations = [], dispatch, onEnableMidi, onExport,
}) {
  const {
    insertMeasure, setInsertMeasure, insertBeat, setInsertBeat, insertPitch, setInsertPitch,
    insertChordPitches, setInsertChordPitches, insertDuration, setInsertDuration,
    insertDots, setInsertDots, editDuration, setEditDuration, editDots, setEditDots,
    graceMode, setGraceMode, tupletCount, setTupletCount,
    articulation, setArticulation, arpeggioType, setArpeggioType,
    moveDestination, setMoveDestination, stepInputEnabled, setStepInputEnabled, midiStatus,
    editorTool, setEditorTool, paletteExpanded, setPaletteExpanded,
    uncommonDurationsExpanded, setUncommonDurationsExpanded,
    articulationsExpanded, setArticulationsExpanded, restMode, setRestMode,
    customTupletText, setCustomTupletText, insertionBeaming, setInsertionBeaming,
  } = input;
  const hasSelection = Boolean(frame?.update.selection.length);
  const hasEventTargets = commands.eventTargets().length > 0;
  const editingSelection = editorTool !== "note" && hasEventTargets;
  const selectedInputDuration = DURATION_GLYPHS.find(({ value }) => value === insertDuration) ?? DURATION_GLYPHS[2];
  const applySelectionDuration = (base, dots = Number(editDots) || 0) => {
    setEditDuration(base);
    setEditDots(dots);
    commands.editSelection("setDurations", {}, (target) => ({
      ...target, duration: { base, dots },
    }));
  };
  const chooseDuration = (base) => {
    if (base !== insertDuration) setInsertDots(0);
    setInsertDuration(base);
    setEditorTool("note");
  };
  const chooseDots = (dots) => {
    setInsertDots(dots);
    setEditorTool("note");
  };
  const durationControl = (base, label, glyph) => <MusicGlyphButton
    glyph={glyph} label={label} className="duration-glyph"
    active={editorTool === "note" && insertDuration === base}
    aria-pressed={editorTool === "note" && insertDuration === base}
    onClick={() => chooseDuration(base)} />;
  const accidentalControl = (accidental, label, glyph) => <MusicGlyphButton
    glyph={glyph} label={label} active={false}
    onClick={() => hasSelection && commands.editSelection(
      "setAccidentals", {}, (target) => ({ ...target, accidental }),
    )} />;
  const voiceControl = (voiceNumber) => {
    const destination = destinations.find((item) => item.voiceNumber === voiceNumber);
    return <button type="button" disabled={!destination} aria-label={`声部 ${voiceNumber}`}
      title={`声部 ${voiceNumber}`}
      className={`voice-button${moveDestination === destination?.value ? " active" : ""}`}
      onClick={() => {
        if (!destination) return;
        setMoveDestination(destination.value);
        if (editingSelection) commands.editSelection("moveVoices", {}, (target) => ({
          ...target, targetVoiceNumber: destination.voiceNumber, targetStaffId: destination.staffId,
        }));
        else setEditorTool("note");
      }}><span className="voice-number">{voiceNumber}</span>
      <span className={`voice-color-bar voice-${voiceNumber}`} aria-hidden="true" /></button>;
  };
  const applyTuplet = (count) => {
    if (hasSelection) {
      const targets = commands.groupedEventTargets(count);
      if (targets.length) dispatch({ type: "applyTuplets", targets });
    } else setTupletCount(count);
  };
  const suggestedTuplets = Number(hasSelection ? editDots : insertDots) > 0 ? [2, 4, 3] : [3, 5, 6];
  const defaultTupletCount = Math.min(...suggestedTuplets);
  const beamControl = (label, beaming) => <BeamPatternButton label={label}
    beamLeft={beaming.beamLeft} beamRight={beaming.beamRight}
    active={!hasSelection && JSON.stringify(insertionBeaming) === JSON.stringify(beaming)}
    onClick={() => {
      if (hasSelection) commands.editSelection("setBeaming", {}, (target) => ({ ...target, beaming }));
      else setInsertionBeaming(beaming);
    }} />;
  const groupSelectionBeam = () => {
    const grouped = new Map();
    for (const target of commands.eventTargets()) {
      const targets = grouped.get(target.voiceTrackId) ?? [];
      targets.push(target);
      grouped.set(target.voiceTrackId, targets);
    }
    const targets = [...grouped.values()].flatMap((items) => items.map((target, index) => ({
      ...target,
      beaming: { beamLeft: index > 0, beamRight: index < items.length - 1 },
    })));
    if (targets.length > 1) dispatch({ type: "setBeaming", targets });
  };
  const articulationGlyphs = {
    STACCATO: SMUFL_GLYPHS.articStaccato,
    SPICCATO: SMUFL_GLYPHS.articStaccato,
    STACCATISSIMO: SMUFL_GLYPHS.articStaccatissimo,
    TENUTO: SMUFL_GLYPHS.articTenuto,
    ACCENT: SMUFL_GLYPHS.articAccent,
    MARCATO: SMUFL_GLYPHS.articMarcato,
    FERMATA: "\uE4C0",
  };
  const articulationLabels = {
    STACCATO: "断奏",
    SPICCATO: "跳弓",
    STACCATISSIMO: "极断奏",
    TENUTO: "保持音",
    ACCENT: "重音",
    MARCATO: "特重音",
    FERMATA: "延长记号",
  };
  const articulationControl = (value) => <MusicGlyphButton glyph={articulationGlyphs[value]}
    label={articulationLabels[value]} active={articulation === value} disabled={!hasEventTargets}
    onClick={() => {
      setArticulation(value);
      commands.editSelection("toggleArticulation", { articulation: value });
    }} />;
  return {
    "history.undo": <EditorIconButton icon={Undo2} label="撤销" active={false}
      disabled={!frame?.update.canUndo} onClick={() => dispatch({ type: "undo" })} />,
    "history.redo": <EditorIconButton icon={Redo2} label="重做" active={false}
      disabled={!frame?.update.canRedo} onClick={() => dispatch({ type: "redo" })} />,
    "tool.select": <EditorIconButton icon={MousePointer2} label="选择" active={editorTool === "select"}
      aria-pressed={editorTool === "select"} onClick={() => setEditorTool("select")} />,
    "tool.marquee": <EditorIconButton icon={SquareDashedMousePointer} label="框选" active={editorTool === "marquee"}
      aria-pressed={editorTool === "marquee"} onClick={() => setEditorTool("marquee")} />,
    "tool.palette-toggle": <MusicGlyphButton glyph={SMUFL_GLYPHS.noteQuarterUp} label="音符"
      className="tool-note-glyph" active={paletteExpanded}
      aria-expanded={paletteExpanded} onClick={() => setPaletteExpanded(!paletteExpanded)} />,
    "voice.1": paletteExpanded ? voiceControl(1) : null,
    "voice.2": paletteExpanded ? voiceControl(2) : null,
    "voice.3": paletteExpanded ? voiceControl(3) : null,
    "voice.4": paletteExpanded ? voiceControl(4) : null,
    "duration.whole": paletteExpanded ? durationControl("WHOLE", "全音符", SMUFL_GLYPHS.noteWhole) : null,
    "duration.half": paletteExpanded ? durationControl("HALF", "二分音符", SMUFL_GLYPHS.noteHalfUp) : null,
    "duration.quarter": paletteExpanded ? durationControl("QUARTER", "四分音符", SMUFL_GLYPHS.noteQuarterUp) : null,
    "duration.eighth": paletteExpanded ? durationControl("EIGHTH", "八分音符", SMUFL_GLYPHS.noteEighthUp) : null,
    "duration.16th": paletteExpanded ? durationControl("SIXTEENTH", "十六分音符", SMUFL_GLYPHS.note16thUp) : null,
    "duration.32nd": paletteExpanded ? durationControl("THIRTY_SECOND", "三十二分音符", SMUFL_GLYPHS.note32ndUp) : null,
    "duration.rest": paletteExpanded ? <MusicGlyphButton glyph={selectedInputDuration.rest} label="休止符模式"
      className="duration-rest"
      active={editorTool === "note" && restMode} onClick={() => {
        setRestMode(!restMode);
        setEditorTool("note");
      }} /> : null,
    "duration.dot.1": paletteExpanded ? <MusicGlyphButton glyph={SMUFL_GLYPHS.augmentationDot}
      label="单附点" active={editorTool === "note" && Number(insertDots) === 1}
      onClick={() => chooseDots(1)} /> : null,
    "duration.dot.2": paletteExpanded ? <MusicGlyphButton glyph={SMUFL_GLYPHS.augmentationDot.repeat(2)}
      className="augmentation-dots double" label="双附点"
      active={editorTool === "note" && Number(insertDots) === 2} onClick={() => chooseDots(2)} /> : null,
    "duration.uncommon-toggle": paletteExpanded ? <button type="button" aria-expanded={uncommonDurationsExpanded}
      aria-label={uncommonDurationsExpanded ? "收起更多时值" : "展开更多时值"}
      title={uncommonDurationsExpanded ? "收起更多时值" : "展开更多时值"}
      className="editor-icon-button collapse-toggle"
      onClick={() => setUncommonDurationsExpanded(!uncommonDurationsExpanded)}>
      {uncommonDurationsExpanded ? <ChevronLeft aria-hidden="true" size={21} strokeWidth={1.8} />
        : <ChevronRight aria-hidden="true" size={21} strokeWidth={1.8} />}</button> : null,
    "duration.breve": paletteExpanded && uncommonDurationsExpanded
      ? durationControl("BREVE", "倍全音符", SMUFL_GLYPHS.noteDoubleWhole) : null,
    "duration.64th": paletteExpanded && uncommonDurationsExpanded
      ? durationControl("SIXTY_FOURTH", "六十四分音符", SMUFL_GLYPHS.note64thUp) : null,
    "duration.longa": paletteExpanded && uncommonDurationsExpanded
      ? durationControl("LONGA", "长音符", SMUFL_GLYPHS.noteDoubleWhole) : null,
    "duration.maxima": paletteExpanded && uncommonDurationsExpanded
      ? durationControl("MAXIMA", "最大音符", SMUFL_GLYPHS.noteDoubleWhole) : null,
    "duration.128th": paletteExpanded && uncommonDurationsExpanded
      ? durationControl("ONE_TWENTY_EIGHTH", "一百二十八分音符", SMUFL_GLYPHS.note128thUp) : null,
    "accidental.sharp": paletteExpanded ? accidentalControl("SHARP", "升号", SMUFL_GLYPHS.accidentalSharp) : null,
    "accidental.flat": paletteExpanded ? accidentalControl("FLAT", "降号", SMUFL_GLYPHS.accidentalFlat) : null,
    "accidental.natural": paletteExpanded ? accidentalControl("NATURAL", "还原号", SMUFL_GLYPHS.accidentalNatural) : null,
    "accidental.double-sharp": paletteExpanded ? accidentalControl("DOUBLE_SHARP", "重升", SMUFL_GLYPHS.accidentalDoubleSharp) : null,
    "accidental.double-flat": paletteExpanded ? accidentalControl("DOUBLE_FLAT", "重降", SMUFL_GLYPHS.accidentalDoubleFlat) : null,
    "curve.tie": paletteExpanded ? <CurveNotePairButton samePitch label="连音线" disabled={!hasSelection}
      onClick={() => commands.editSelection("setTies", {}, (target) => ({ ...target, tieOut: true }))} /> : null,
    "curve.slur": paletteExpanded ? <CurveNotePairButton samePitch={false} label="圆滑线"
      disabled={commands.eventTargets().length !== 2} onClick={commands.addSlurFromSelection} /> : null,
    "grace.appoggiatura": paletteExpanded ? <MusicGlyphButton glyph={SMUFL_GLYPHS.graceAppoggiatura}
      label="倚音" active={graceMode && input.graceNoteType === "APPOGGIATURA"}
      onClick={() => { input.setGraceNoteType("APPOGGIATURA"); setGraceMode(!(graceMode && input.graceNoteType === "APPOGGIATURA")); }} /> : null,
    "grace.acciaccatura": paletteExpanded ? <MusicGlyphButton glyph={SMUFL_GLYPHS.graceAcciaccatura}
      label="短倚音" active={graceMode && input.graceNoteType === "ACCIACCATURA"}
      onClick={() => { input.setGraceNoteType("ACCIACCATURA"); setGraceMode(!(graceMode && input.graceNoteType === "ACCIACCATURA")); }} /> : null,
    "grace.small-note": paletteExpanded ? <button type="button" className="music-glyph-button grace-small-note"
      aria-label="小音符" title="小音符" disabled={!hasEventTargets}
      onClick={() => dispatch({ type: "createSmallNoteRegions", targets: commands.groupedEventTargets() })}>小</button> : null,
    ...Object.fromEntries([...Array(8)].map((_, index) => {
      const count = index + 2;
      return [`tuplet.suggested.${count}`, null];
    })),
    "tuplet.custom": paletteExpanded ? <select className="toolbar-tuplet-select" aria-label="连音数"
      title="选择连音数" value={Number(tupletCount) > 1 ? Number(tupletCount) : defaultTupletCount}
      onChange={(event) => {
        const count = Number(event.target.value);
        setCustomTupletText(String(count));
        setTupletCount(count);
      }}>
      {Array.from({ length: 8 }, (_, index) => index + 2).map((count) =>
        <option key={count} value={count}>{count}</option>)}
    </select> : null,
    "tuplet.confirm": paletteExpanded ? <button type="button" className="tuplet-apply-button"
      aria-label="连音" title="连音" aria-pressed={Number(tupletCount) > 1}
      onClick={() => applyTuplet(Number(tupletCount) > 1 ? Number(tupletCount) : defaultTupletCount)}>连音</button> : null,
    "tuplet.clear": null,
    "beam.independent": paletteExpanded ? <MusicGlyphButton glyph={SMUFL_GLYPHS.noteEighthUp}
      label="独立音符，无符杠" active={!hasSelection && JSON.stringify(insertionBeaming) === JSON.stringify({ beamLeft: false, beamRight: false })}
      onClick={() => {
        const beaming = { beamLeft: false, beamRight: false };
        if (hasSelection) commands.editSelection("setBeaming", {}, (target) => ({ ...target, beaming }));
        else setInsertionBeaming(beaming);
      }} /> : null,
    "beam.both": paletteExpanded ? beamControl("左右都连符杠", { beamLeft: true, beamRight: true }) : null,
    "beam.right": paletteExpanded ? beamControl("符杠仅连右", { beamLeft: false, beamRight: true }) : null,
    "beam.left": paletteExpanded ? beamControl("符杠仅连左", { beamLeft: true, beamRight: false }) : null,
    "beam.group": paletteExpanded ? <BeamPatternButton label="将选中音符组成符杠组" isGroup
      disabled={commands.eventTargets().length < 2} onClick={groupSelectionBeam} /> : null,
    "articulation.toggle": paletteExpanded ? <button type="button" aria-expanded={articulationsExpanded}
      className="editor-icon-button articulation-toggle"
      aria-label={articulationsExpanded ? "收起演奏法" : "展开演奏法"}
      title={articulationsExpanded ? "收起演奏法" : "展开演奏法"}
      onClick={() => setArticulationsExpanded(!articulationsExpanded)}>
      奏法{articulationsExpanded ? <ChevronLeft aria-hidden="true" size={21} strokeWidth={1.8} />
        : <ChevronRight aria-hidden="true" size={21} strokeWidth={1.8} />}</button> : null,
    "articulation.staccato": paletteExpanded && articulationsExpanded ? articulationControl("STACCATO") : null,
    "articulation.spiccato": paletteExpanded && articulationsExpanded ? articulationControl("SPICCATO") : null,
    "articulation.staccatissimo": paletteExpanded && articulationsExpanded ? articulationControl("STACCATISSIMO") : null,
    "articulation.tenuto": paletteExpanded && articulationsExpanded ? articulationControl("TENUTO") : null,
    "articulation.accent": paletteExpanded && articulationsExpanded ? articulationControl("ACCENT") : null,
    "articulation.marcato": paletteExpanded && articulationsExpanded ? articulationControl("MARCATO") : null,
    "articulation.fermata": paletteExpanded && articulationsExpanded ? articulationControl("FERMATA") : null,
    "selection.selectAll": <button disabled={!frame} onClick={commands.selectAllEvents}>全选</button>,
    "selection.delete": <button disabled={!hasSelection} onClick={() => commands.editSelection("deleteNotes")}>删除</button>,
    "selection.copy": <button disabled={!hasSelection} onClick={() => commands.editSelection("copyNotes")}>复制</button>,
    "selection.cut": <button disabled={!hasSelection} onClick={() => commands.editSelection("cutNotes")}>剪切</button>,
    "selection.paste": <button disabled={!frame?.update.canPaste} onClick={commands.pasteAtInputPosition}>粘贴</button>,
    "selection.transposeDown": <button disabled={!hasSelection}
      onClick={() => commands.editSelection("transposeNotes", { stepDelta: -1 })}>下移</button>,
    "selection.transposeUp": <button disabled={!hasSelection}
      onClick={() => commands.editSelection("transposeNotes", { stepDelta: 1 })}>上移</button>,
    "selection.moveVoice": <>
      <select aria-label="目标谱表声部" disabled={!hasSelection || !destinations.length}
        value={moveDestination} onChange={(event) => setMoveDestination(event.target.value)}>
        {destinations.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
      </select>
      <button disabled={!hasSelection || !moveDestination}
        onClick={commands.moveSelectionToDestination}>移动声部</button>
    </>,
    "selection.duration": <div className="toolbar-music-buttons">
      {DURATION_GLYPHS.map(({ value, label, note }) => <MusicGlyphButton key={value}
        glyph={note} label={`所选音符：${label}`} active={editDuration === value} disabled={!hasSelection}
        onClick={() => applySelectionDuration(value)} />)}
      {[1, 2, 3].map((dots) => <MusicGlyphButton key={`dots:${dots}`}
        glyph={SMUFL_GLYPHS.augmentationDot.repeat(dots)} label={`${dots} 个附点`}
        active={Number(editDots) === dots} disabled={!hasSelection}
        onClick={() => applySelectionDuration(editDuration, dots)} />)}
    </div>,
    "selection.accidental": <>
      {[[SMUFL_GLYPHS.accidentalDoubleFlat, "DOUBLE_FLAT", "重降"],
        [SMUFL_GLYPHS.accidentalFlat, "FLAT", "降号"],
        [SMUFL_GLYPHS.accidentalNatural, "NATURAL", "还原号"],
        [SMUFL_GLYPHS.accidentalSharp, "SHARP", "升号"],
        [SMUFL_GLYPHS.accidentalDoubleSharp, "DOUBLE_SHARP", "重升"]]
        .map(([glyph, accidental, label]) => <MusicGlyphButton key={accidental}
        glyph={glyph} label={label} disabled={!hasSelection} onClick={() => commands.editSelection(
          "setAccidentals", {}, (target) => ({ ...target, accidental }),
        )} />)}
      <button disabled={!hasSelection} onClick={() => commands.editSelection(
        "setAccidentals", {}, (target) => ({ ...target, accidental: null }),
      )}>自动临时记号</button>
    </>,
    "selection.tie": <>
      <button className="music-glyph-button music-glyph-composite tie" aria-label="添加连音线"
        title="添加连音线" disabled={!hasSelection} onClick={() => commands.editSelection(
        "setTies", {}, (target) => ({ ...target, tieOut: true }),
      )}><span aria-hidden="true">{SMUFL_GLYPHS.noteQuarterUp}{SMUFL_GLYPHS.noteQuarterUp}</span></button>
      <button disabled={!hasSelection} onClick={() => commands.editSelection(
        "setTies", {}, (target) => ({ ...target, tieOut: false }),
      )}>断开连音</button>
    </>,
    "selection.beam": <select aria-label="符杠" disabled={!hasSelection} defaultValue=""
      onChange={(event) => {
        const values = {
          auto: null, none: { beamLeft: false, beamRight: false },
          start: { beamLeft: false, beamRight: true }, middle: { beamLeft: true, beamRight: true },
          end: { beamLeft: true, beamRight: false },
        };
        if (!event.target.value) return;
        commands.editSelection("setBeaming", {}, (target) => ({
          ...target, beaming: values[event.target.value],
        }));
        event.target.value = "";
      }}>
      <option value="">符杠</option><option value="auto">自动</option><option value="none">断开</option>
      <option value="start">起始</option><option value="middle">中间</option><option value="end">结束</option>
    </select>,
    "selection.articulation": <>
      <select aria-label="发音法" value={articulation}
        onChange={(event) => setArticulation(event.target.value)}>
        {["STACCATO", "SPICCATO", "STACCATISSIMO", "TENUTO", "ACCENT", "MARCATO", "FERMATA"].map(
          (value) => <option key={value}>{value}</option>,
        )}
      </select>
      <button disabled={!hasEventTargets}
        onClick={() => commands.editSelection("toggleArticulation", { articulation })}>切换发音法</button>
    </>,
    "selection.arpeggio": <>
      <select aria-label="琶音" value={arpeggioType} onChange={(event) => setArpeggioType(event.target.value)}>
        <option value="">无琶音</option><option value="NORMAL">普通琶音</option>
        <option value="UP">上行琶音</option><option value="DOWN">下行琶音</option>
        <option value="NON_ARPEGGIATE">不琶音</option>
      </select>
      <button disabled={!hasEventTargets} onClick={() => commands.editSelection(
        "setArpeggio", { arpeggioType: arpeggioType || null },
      )}>设置琶音</button>
    </>,
    "input.position": <>
      <input className="toolbar-number" aria-label="输入小节" type="number" min="1"
        value={insertMeasure} onChange={(event) => setInsertMeasure(event.target.value)} />
      <input className="toolbar-number" aria-label="输入四分拍" type="text" inputMode="decimal"
        value={insertBeat} onChange={(event) => setInsertBeat(event.target.value)} />
    </>,
    "input.duration": <>
      <input className="toolbar-number" aria-label="输入音级" type="number" value={insertPitch}
        onChange={(event) => setInsertPitch(event.target.value)} />
      <div className="toolbar-music-buttons">{DURATION_GLYPHS.map(({ value, label, note }) =>
        <MusicGlyphButton key={value} glyph={note} label={`输入：${label}`} active={insertDuration === value}
          onClick={() => setInsertDuration(value)} />)}
      {[1, 2, 3].map((dots) => <MusicGlyphButton key={`input-dots:${dots}`}
        glyph={SMUFL_GLYPHS.augmentationDot.repeat(dots)} label={`输入 ${dots} 个附点`}
        active={Number(insertDots) === dots} onClick={() => setInsertDots(dots)} />)}</div>
      <MusicGlyphButton glyph={selectedInputDuration.note} label="插入音符" disabled={!frame}
        className="insert-glyph" onClick={() => commands.insertEvent(false)} />
    </>,
    "input.rest": <MusicGlyphButton glyph={selectedInputDuration.rest} label="插入休止符"
      disabled={!frame} className="insert-glyph" onClick={() => commands.insertEvent(true)} />,
    "input.chord": <>
      <input className="toolbar-chord" aria-label="和弦音级" value={insertChordPitches}
        onChange={(event) => setInsertChordPitches(event.target.value)} />
      <button disabled={!frame} onClick={commands.insertChord}>插入和弦</button>
    </>,
    "input.step": <label className="toolbar-check"><input type="checkbox" checked={stepInputEnabled}
      onChange={(event) => setStepInputEnabled(event.target.checked)} />键盘步进</label>,
    "input.midi": <button type="button" disabled={!frame} title={midiStatus}
      onClick={onEnableMidi}>连接 MIDI</button>,
    "input.grace": <label className="toolbar-check"><input type="checkbox" checked={graceMode}
      onChange={(event) => setGraceMode(event.target.checked)} />倚音</label>,
    "input.tuplet": <select aria-label="连音组" value={tupletCount}
      onChange={(event) => setTupletCount(event.target.value)}>
      <option value="0">普通</option>{[2, 3, 4, 5, 6, 7, 8, 9].map(
        (value) => <option key={value} value={value}>{value} 连音</option>,
      )}
    </select>,
    "document.export": <button disabled={!frame} onClick={onExport}>导出 .mecon</button>,
  };
}

export function ScoreEditorToolbar({
  config = FULL_SCORE_EDITOR_TOOLBAR,
  controls = {},
  slots = {},
  className = "toolbar score-editor-toolbar",
}) {
  const resolved = resolveToolbarLayout(config);
  const availableItems = resolved.items.filter((item) => {
    if (item.type === "group") return item.items.some((id) => controls[id] != null);
    if (item.type === "slot") return slots[item.id] != null;
    return true;
  });
  const renderedItems = availableItems.filter((item, index) => {
    if (item.type !== "separator") return true;
    const previous = availableItems[index - 1];
    const next = availableItems[index + 1];
    return previous && next && previous.type !== "separator" && next.type !== "separator";
  });
  return (
    <header className={className} data-overflow={resolved.overflow} role="toolbar" aria-label="五线谱工具栏">
      {renderedItems.map((item, index) => {
        if (item.type === "group") {
          const children = item.items
            .map((id) => ({ id, content: controls[id] }))
            .filter(({ content }) => content != null);
          if (!children.length) return null;
          return <div className="score-editor-toolbar-group" role="group" data-group={item.id}
            key={`group:${item.id}`}>{children.map(({ id, content }) => (
              <span className="score-editor-toolbar-control" data-control-id={id}
                key={`${item.id}:${id}`}>{content}</span>
            ))}</div>;
        }
        if (item.type === "separator") {
          return <span className="toolbar-divider" aria-hidden="true" key={`separator:${index}`} />;
        }
        if (item.type === "break") {
          return <span className="score-editor-toolbar-break" aria-hidden="true" key={`break:${index}`} />;
        }
        const content = slots[item.id];
        return content == null ? null : <React.Fragment key={`slot:${item.id}`}>{content}</React.Fragment>;
      })}
    </header>
  );
}

export const ScoreEditorSurface = forwardRef(function ScoreEditorSurface({
  snapshot,
  surfaceIndex = 0,
  onSurfaceIndexChange,
  dragPreview = null,
  noteInputPreview = null,
  background = "#fffdf8",
  emptyContent = "打开 .mecon 文件开始编辑",
  onClick,
  onPointerDown,
  onPointerMove,
  onPointerLeave,
  onPointerUp,
  onPointerCancel,
  scrollLeft = 0,
  onScroll,
  onViewportWidth,
  scrollContentWidth = 0,
  playbackStore = null,
  elementTints = null,
  className = "score-panel",
  ariaLabel = "五线谱编辑区",
  canvasAriaLabel = "可编辑五线谱",
}, canvasRef) {
  const localCanvasRef = useRef(null);
  const scrollRef = useRef(null);
  const scrollSyncRef = useRef(null);
  if (!scrollSyncRef.current) scrollSyncRef.current = createControlledScrollSync();
  const summaryId = useId();
  // The surface commonly mounts before its snapshot, so the canvas does not
  // exist during the first layout pass. Refresh the forwarded handle after
  // each render so hosts receive the canvas once the empty state is replaced.
  useImperativeHandle(canvasRef, () => localCanvasRef.current);
  const frame = snapshot;
  const playback = useSyncExternalStore(
    playbackStore?.subscribe ?? subscribeToNothing,
    playbackStore?.getSnapshot ?? idlePlaybackSnapshot,
    playbackStore?.getSnapshot ?? idlePlaybackSnapshot,
  );
  const surfaces = frame?.bundle?.surfaces ?? [];
  const surface = surfaces.find((item) => item.index === surfaceIndex) ?? surfaces[surfaceIndex];
  const playhead = playback.state === "playing" || playback.state === "paused"
    ? scorePlayheadGeometry(frame, playback.time, surfaceIndex, playback.tick)
    : null;
  const score = frame?.update?.score;
  const eventCount = Object.values(score?.voiceTracks ?? {})
    .reduce((total, voice) => total + (voice.events?.length ?? 0), 0);
  const scoreSummary = frame
    ? `${score?.metadata?.title ?? "未命名乐谱"}，${score?.measures?.length ?? 0} 小节，` +
      `${Object.keys(score?.staffTracks ?? {}).length} 个谱表，${eventCount} 个记谱事件，` +
      `当前选择 ${frame.update.selection?.length ?? 0} 项。画布获得焦点后可使用编辑快捷键。`
    : "尚未打开乐谱。";
  useEffect(() => {
    const canvas = localCanvasRef.current;
    if (!frame || !canvas) return;
    const selectedIds = selectedElementIds(frame.update, surface?.elements);
    renderCanvas(canvas, frame.bundle, {
      surfaceIndex,
      selectedIds: [...new Set([...selectedIds, ...(dragPreview?.elementIds ?? [])])],
      elementOffsets: dragPreview?.offsets,
      hiddenIds: dragPreview?.enginePreview?.hiddenElementIds,
      commandLayers: [
        ...(dragPreview?.enginePreview ? [
          { commands: dragPreview.enginePreview.baseCommands },
          { commands: dragPreview.enginePreview.movedCommands, color: "#2878ff" },
        ] : []),
        ...(noteInputPreview?.commands?.length ? [
          { commands: noteInputPreview.commands, color: "rgba(138, 138, 138, 0.55)" },
        ] : []),
      ],
      elementTints,
      selectionMode: "tint",
      background,
    });
  }, [frame, surfaceIndex, dragPreview, noteInputPreview, background, elementTints]);
  useEffect(() => {
    scrollSyncRef.current.apply(scrollRef.current, scrollLeft);
  }, [scrollLeft]);
  useEffect(() => {
    const element = scrollRef.current;
    if (!element || !onViewportWidth || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(() => {
      const style = getComputedStyle(element);
      const padding = parseFloat(style.paddingLeft || "0") + parseFloat(style.paddingRight || "0");
      onViewportWidth(Math.max(1, Math.floor(element.clientWidth - padding)));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [onViewportWidth, Boolean(frame)]);

  return (
    <section className={className} aria-label={ariaLabel}>
      <p id={summaryId} style={VISUALLY_HIDDEN_STYLE} aria-live="polite">{scoreSummary}</p>
      {surfaces.length > 1 && (
        <nav className="surface-tabs" aria-label="页面">
          {surfaces.map((surface) => (
            <button key={surface.index} className={surface.index === surfaceIndex ? "active" : ""}
              onClick={() => onSurfaceIndexChange?.(surface.index)}>
              {surface.index + 1}
            </button>
          ))}
        </nav>
      )}
      {frame ? (
        <div className="canvas-scroll" ref={scrollRef}
          onScroll={(event) => {
            const next = event.currentTarget.scrollLeft;
            if (scrollSyncRef.current.observe(next, scrollLeft)) onScroll?.(next);
          }}>
          <div className="score-canvas-stage" style={{
            position: "relative", width: Math.max(surface?.width ?? 0, scrollContentWidth),
            height: surface?.height ?? 0,
          }}>
            <canvas ref={localCanvasRef} role="img" aria-label={canvasAriaLabel}
              aria-describedby={summaryId} tabIndex={0} onClick={onClick} onPointerDown={onPointerDown}
              onPointerMove={onPointerMove} onPointerUp={onPointerUp}
              onPointerCancel={onPointerCancel} onPointerLeave={onPointerLeave} />
            {dragPreview?.marquee && <span className="score-marquee-preview" aria-hidden="true" style={{
              position: "absolute",
              pointerEvents: "none",
              left: dragPreview.marquee.x,
              top: dragPreview.marquee.y,
              width: dragPreview.marquee.width,
              height: dragPreview.marquee.height,
              boxSizing: "border-box",
              border: "1px dashed #78a6ff",
              background: "rgba(49, 93, 158, 0.16)",
              boxShadow: "0 0 0 1px rgba(120, 166, 255, 0.18)",
              zIndex: 5,
            }} />}
            {playhead && <span className="score-playhead" data-playback-state={playback.state} aria-hidden="true"
              style={{
                position: "absolute", pointerEvents: "none", left: playhead.x, top: playhead.top,
                width: 2, height: playhead.height, background: "#e53935", zIndex: 4,
                transform: "translateX(-1px)",
              }} />}
          </div>
        </div>
      ) : <div className="empty-state">{emptyContent}</div>}
    </section>
  );
});

/**
 * Public complete score editor. The default layout is toolbar + score + inspectors; workbenches may
 * provide a render function to place those same owned regions into a custom responsive layout.
 */
export function ScoreEditor({
  controller,
  toolbarConfig = FULL_SCORE_EDITOR_TOOLBAR,
  toolbarSlots = {},
  hiddenControlIds = [],
  onEnableMidi,
  onExport,
  onDeleteMeasure,
  surfaceProps = {},
  children,
}) {
  const controls = createScoreEditorToolbarControls({
    frame: controller.frame,
    input: controller.input,
    commands: controller.commands,
    destinations: controller.destinations,
    dispatch: controller.dispatch,
    onEnableMidi,
    onExport,
  });
  const hidden = new Set(hiddenControlIds);
  const visibleControls = Object.fromEntries(
    Object.entries(controls).filter(([id]) => !hidden.has(id)),
  );
  const regions = {
    toolbar: <ScoreEditorToolbar config={toolbarConfig} controls={visibleControls} slots={toolbarSlots} />,
    surface: <ScoreEditorSurface
      ref={controller.canvasRef}
      snapshot={controller.frame}
      surfaceIndex={controller.surfaceIndex}
      onSurfaceIndexChange={controller.setSurfaceIndex}
      dragPreview={controller.dragPreview}
      noteInputPreview={controller.noteInputPreview}
      onClick={controller.selection.onClick}
      onPointerDown={controller.drag.onPointerDown}
      onPointerMove={(event) => {
        controller.selection.onPointerMove(event);
        controller.drag.onPointerMove(event);
      }}
      onPointerLeave={controller.selection.onPointerLeave}
      onPointerUp={(event) => controller.drag.finish(event)}
      onPointerCancel={(event) => controller.drag.finish(event, true)}
      {...surfaceProps}
    />,
    inspectors: <>
      <ScoreEditorInputInspector frame={controller.frame} state={controller.input}
        commands={controller.commands} dispatch={controller.dispatch} onEnableMidi={onEnableMidi} />
      <ScoreEditorLayoutInspector frame={controller.frame} input={controller.input}
        expression={controller.expression} commands={controller.commands} dispatch={controller.dispatch} />
      <ScoreEditorExpressionInspector frame={controller.frame} input={controller.input}
        expression={controller.expression} commands={controller.commands} dispatch={controller.dispatch} />
      <ScoreEditorStructureInspector frame={controller.frame} input={controller.input}
        structure={controller.structure} commands={controller.commands} dispatch={controller.dispatch}
        onDeleteMeasure={onDeleteMeasure} />
      <ScoreEditorRepeatInspector frame={controller.frame} structure={controller.structure}
        commands={controller.commands} dispatch={controller.dispatch} />
      <ScoreEditorSlurInspector frame={controller.frame} structure={controller.structure}
        commands={controller.commands} />
    </>,
  };
  return typeof children === "function"
    ? children(regions)
    : <div className="score-editor">{regions.toolbar}{regions.surface}{regions.inspectors}</div>;
}
