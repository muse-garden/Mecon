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
  Square,
  Undo2,
} from "lucide-react";
import { BravuraTimeSignatureGlyph } from "@mecon/web-renderer/editor/react";

function ToolbarIcon({ icon: Icon, size = 16 }) {
  return <Icon aria-hidden="true" size={size} strokeWidth={1.8} />;
}

export function PracticeTopToolbar({
  descriptor, tokens, frame, update, onNew, onFile, onSave, dispatch, dispatchPractice,
  togglePlayback, requestTimelinePlayback, playbackStore,
  gridDenominator, setGridDenominator, defaultChordBeats, setDefaultChordBeats,
  applyTimeSignature, playbackRate, setPlaybackRate,
  onOpenSettings,
}) {
  const playback = useSyncExternalStore(
    playbackStore.subscribe,
    playbackStore.getSnapshot,
    playbackStore.getSnapshot,
  ) as { state: string };
  if (!descriptor) return null;
  const settings = update?.document?.settings;
  const writing = settings?.writing;
  const selectedSlotId = update?.selection?.slotId ?? update?.selectedSlotId;
  const updateWriting = (fields) => writing && dispatchPractice({
    type: "updateWritingSettings", settings: { ...writing, ...fields },
  });
  const rebuild = (fields: { polyphonyLimit?: number; fifths?: number; mode?: string } = {}) =>
    settings && dispatchPractice({
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
    "writing.rewrite": <button disabled={!update?.structure?.rewriteSelectionAvailable || update?.writing?.phase === "RUNNING"}
      onClick={() => dispatchPractice({ type: "rewriteSelection" })}>
      <ToolbarIcon icon={RefreshCw} />重新写作
    </button>,
    "writing.alternate": <button disabled={!update?.writing?.canAlternate}
      onClick={() => dispatchPractice({ type: "alternateWriting" })}>
      <ToolbarIcon icon={ArrowRightLeft} />换一个结果
    </button>,
    "writing.cancel": <button disabled={update?.writing?.phase !== "RUNNING"}
      onClick={() => dispatchPractice({ type: "cancelWriting" })}>
      <ToolbarIcon icon={Square} />取消写作
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
    "playback.speed": <label className="toolbar-setting">速度<select aria-label="播放速度"
      value={playbackRate} onChange={(event) => setPlaybackRate(Number(event.target.value))}>
      {[0.5, 0.75, 1, 1.25, 1.5, 2].map((rate) =>
        <option key={rate} value={rate}>{rate}×</option>)}
    </select></label>,
    "playback.audio-settings": <button className="icon-only-button" onClick={onOpenSettings}
      aria-label="音频设置" title="音频设置"><ToolbarIcon icon={Settings2} /></button>,
  };
  const style = tokens ? ({
    "--practice-toolbar-height": `${tokens.topHeight}px`,
    "--practice-toolbar-padding": `${tokens.horizontalPadding}px`,
    "--practice-toolbar-gap": `${tokens.groupGap}px`,
  } as React.CSSProperties) : undefined;
  const missingControlIds = descriptor.groups.flatMap((group) => group.controls)
    .filter((id) => !(id in controls));
  if (missingControlIds.length) {
    throw new Error(`Unsupported free-practice toolbar controls: ${missingControlIds.join(", ")}`);
  }
  return <div className="practice-toolbar-groups" style={style}>
    {descriptor.groups.map((group, index) => <React.Fragment key={group.id}>
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
