import React from "react";
import { Lock, LockOpen } from "lucide-react";

export interface PracticeNoteheadRef {
  eventId: string;
  pitchIndex: number;
}

export interface PracticeIntent {
  type: string;
  [key: string]: unknown;
}

export type DispatchPractice = (intent: PracticeIntent) => void;
export type LockState = "locked" | "unlocked" | "mixed";

interface NoteheadView {
  notehead: PracticeNoteheadRef;
  explicitRole?: "CHORD_TONE" | "NON_CHORD_TONE" | null;
  inferredRole?: "CHORD_TONE" | "NON_CHORD_TONE" | null;
  conflict?: boolean;
  locked?: boolean;
}

interface SelectionTarget {
  type: string;
  eventId?: string;
  voiceTrackId?: string;
  pitchIndices?: number[];
}

interface PracticeUpdateLike {
  selection?: { scoreTargets?: SelectionTarget[] };
  score?: { score?: { staffTracks?: Record<string, { id: string; voiceTrackIds?: string[] }> } };
  noteConstraints?: {
    noteheads?: NoteheadView[];
    chordCatalogFilterEnabled?: boolean;
    idiomCatalogFilterEnabled?: boolean;
    lockedVoiceTrackIds?: string[];
    lockedStaffTrackIds?: string[];
  };
}

const noteheadKey = (ref: PracticeNoteheadRef) => `${ref.eventId}:${ref.pitchIndex}`;

function uniformLockState(values: boolean[]): LockState {
  if (!values.length) return "mixed";
  if (values.every(Boolean)) return "locked";
  if (values.every((value) => !value)) return "unlocked";
  return "mixed";
}

/** One typed selector shared by notehead tinting and the properties panel. */
export function derivePracticeNoteSelection(update: PracticeUpdateLike | null | undefined) {
  const views = update?.noteConstraints?.noteheads ?? [];
  const roleViewByRef = new Map(views.map((item) => [noteheadKey(item.notehead), item]));
  const selectedTargets = update?.selection?.scoreTargets ?? [];
  const selectedNoteheads = selectedTargets.flatMap((target) => {
    if (target.type !== "event" || !target.eventId) return [];
    const indices = target.pitchIndices?.length ? new Set(target.pitchIndices) : null;
    return views.filter((item) => item.notehead.eventId === target.eventId
      && (indices == null || indices.has(item.notehead.pitchIndex)))
      .map((item) => item.notehead);
  });
  const selectedVoiceIds = [...new Set(selectedTargets
    .filter((target) => target.type === "event" && target.voiceTrackId)
    .map((target) => target.voiceTrackId as string))];
  const selectedStaffIds = Object.values(update?.score?.score?.staffTracks ?? {})
    .filter((staff) => staff.voiceTrackIds?.some((id) => selectedVoiceIds.includes(id)))
    .map((staff) => staff.id);
  const selectedRoleViews = selectedNoteheads.map((ref) => roleViewByRef.get(noteheadKey(ref)));
  const selectedExplicitRole = !selectedNoteheads.length || selectedRoleViews.some((item) => !item)
    ? undefined
    : selectedRoleViews.every((item) => item?.explicitRole == null) ? "UNMARKED"
      : selectedRoleViews.every((item) => item?.explicitRole === "CHORD_TONE") ? "CHORD_TONE"
        : selectedRoleViews.every((item) => item?.explicitRole === "NON_CHORD_TONE")
          ? "NON_CHORD_TONE" : undefined;

  return {
    roleViewByRef,
    selectedNoteheads,
    selectedVoiceIds,
    selectedStaffIds,
    selectedExplicitRole,
    noteLockState: uniformLockState(selectedRoleViews.map((item) => item?.locked === true)),
    voiceLockState: uniformLockState(selectedVoiceIds.map((id) =>
      (update?.noteConstraints?.lockedVoiceTrackIds ?? []).includes(id))),
    staffLockState: uniformLockState(selectedStaffIds.map((id) =>
      (update?.noteConstraints?.lockedStaffTrackIds ?? []).includes(id))),
  };
}

interface LockScopeProps {
  label: string;
  state: LockState;
  enabled: boolean;
  setLocked: (locked: boolean) => void;
}

function LockScope({ label, state, enabled, setLocked }: LockScopeProps) {
  return <div className="practice-lock-scope">
    <span>{label}</span>
    <div role="group" aria-label={`${label}锁定状态`}>
      <button type="button" className="practice-lock-button" aria-label={`锁定${label}`}
        aria-pressed={state === "locked"} disabled={!enabled} onClick={() => setLocked(true)}>
        <Lock aria-hidden="true" size={16} />
      </button>
      <button type="button" className="practice-lock-button" aria-label={`解锁${label}`}
        aria-pressed={state === "unlocked"} disabled={!enabled} onClick={() => setLocked(false)}>
        <LockOpen aria-hidden="true" size={16} />
      </button>
    </div>
  </div>;
}

interface PracticeNotePropertiesProps {
  update: PracticeUpdateLike;
  selection: ReturnType<typeof derivePracticeNoteSelection>;
  dispatchPractice: DispatchPractice;
}

export function PracticeNoteProperties({
  update,
  selection,
  dispatchPractice,
}: PracticeNotePropertiesProps) {
  const {
    selectedNoteheads, selectedVoiceIds, selectedStaffIds, selectedExplicitRole,
    noteLockState, voiceLockState, staffLockState,
  } = selection;
  return <details className="plan-section workbench-panel practice-note-properties" open>
    <summary><h2>音符属性</h2></summary>
    <p className="practice-note-properties-summary">
      {selectedNoteheads.length ? `已选择 ${selectedNoteheads.length} 个符头` : "选择一个或多个符头以编辑属性"}
    </p>
    <section className="practice-note-property-group" aria-labelledby="practice-role-heading">
      <h3 id="practice-role-heading">和弦内外音</h3>
      <div className="practice-role-buttons" role="group" aria-label="和弦内外音标记">
        <button aria-label="清除内外音标记" aria-pressed={selectedExplicitRole === "UNMARKED"}
          disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
            type: "setHarmonicRole", noteheads: selectedNoteheads,
          })}>无标记</button>
        <button aria-label="标记为和弦内音" aria-pressed={selectedExplicitRole === "CHORD_TONE"}
          disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
            type: "setHarmonicRole", noteheads: selectedNoteheads, role: "CHORD_TONE",
          })}>和弦内音</button>
        <button aria-label="标记为和弦外音" aria-pressed={selectedExplicitRole === "NON_CHORD_TONE"}
          disabled={!selectedNoteheads.length} onClick={() => dispatchPractice({
            type: "setHarmonicRole", noteheads: selectedNoteheads, role: "NON_CHORD_TONE",
          })}>和弦外音</button>
      </div>
      <div className="practice-role-filters">
        <label><input type="checkbox"
          checked={update.noteConstraints?.chordCatalogFilterEnabled ?? false}
          onChange={(event) => dispatchPractice({
            type: "setHarmonicRoleFilters",
            chordCatalogEnabled: event.target.checked,
            idiomCatalogEnabled: update.noteConstraints?.idiomCatalogFilterEnabled ?? false,
          })} /><span>筛选和弦</span></label>
        <label><input type="checkbox"
          checked={update.noteConstraints?.idiomCatalogFilterEnabled ?? false}
          onChange={(event) => dispatchPractice({
            type: "setHarmonicRoleFilters",
            chordCatalogEnabled: update.noteConstraints?.chordCatalogFilterEnabled ?? false,
            idiomCatalogEnabled: event.target.checked,
          })} /><span>筛选惯用进行</span></label>
      </div>
    </section>
    <section className="practice-note-property-group" aria-labelledby="practice-lock-heading">
      <h3 id="practice-lock-heading">锁定情况</h3>
      <div className="practice-lock-scopes">
        <LockScope label="音符" state={noteLockState} enabled={selectedNoteheads.length > 0}
          setLocked={(locked) => dispatchPractice({ type: "setNoteheadLock", noteheads: selectedNoteheads, locked })} />
        <LockScope label="声部" state={voiceLockState} enabled={selectedVoiceIds.length > 0}
          setLocked={(locked) => dispatchPractice({ type: "setVoiceLocks", voiceTrackIds: selectedVoiceIds, locked })} />
        <LockScope label="谱表" state={staffLockState} enabled={selectedStaffIds.length > 0}
          setLocked={(locked) => dispatchPractice({ type: "setStaffLocks", staffTrackIds: selectedStaffIds, locked })} />
      </div>
      <p className="practice-note-properties-summary">锁定音符以符头中央圆点标记</p>
    </section>
  </details>;
}
