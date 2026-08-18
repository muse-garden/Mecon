@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.StorageScore
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeWritingSettings
import com.mecon.exploration.PracticeHarmonicRole
import com.mecon.exploration.PracticeNoteheadRef
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreEditingFrame
import com.mecon.features.scoreediting.ScoreEditUpdate
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json

@Serializable
sealed interface FreePracticeIntent {
    val expectedRevision: Long

    @Serializable
    @SerialName("score")
    data class Score(
        override val expectedRevision: Long,
        val inner: ScoreEditIntent,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("selectSlot")
    data class SelectSlot(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("selectTonalLayout")
    data class SelectTonalLayout(
        override val expectedRevision: Long,
        val tonalLayoutId: WorkspaceTonalLayoutId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("selectIdiomTonalLayout")
    data class SelectIdiomTonalLayout(
        override val expectedRevision: Long,
        val tonalLayoutId: WorkspaceTonalLayoutId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("selectIdiom")
    data class SelectIdiom(
        override val expectedRevision: Long,
        val idiomInstanceId: WorkspaceIdiomInstanceId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("replaceChord")
    data class ReplaceChord(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
        val chordChoice: WorkspaceChordChoice?,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setChordBass")
    data class SetChordBass(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
        val bassPitchClass: Int?,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setChordTonality")
    data class SetChordTonality(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
        val tonality: WorkspaceChordTonality?,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setPivotChord")
    data class SetPivotChord(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
        val selected: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setTonalLayoutKey")
    data class SetTonalLayoutKey(
        override val expectedRevision: Long,
        val tonalLayoutId: WorkspaceTonalLayoutId,
        val fifths: Int,
        val mode: WorkspaceKeyMode,
    ) : FreePracticeIntent {
        init {
            require(fifths in -7..7) { "A tonal-layout intent must use a valid key signature" }
        }
    }

    @Serializable
    @SerialName("insertTonalLayout")
    data class InsertTonalLayout(
        override val expectedRevision: Long,
        val fifths: Int,
        val mode: WorkspaceKeyMode,
        val start: Fraction,
        val end: Fraction? = null,
        val terminatePreviousAt: Fraction? = null,
    ) : FreePracticeIntent {
        init {
            require(fifths in -7..7) { "A tonal-layout intent must use a valid key signature" }
        }
    }

    @Serializable
    @SerialName("removeTonalLayout")
    data class RemoveTonalLayout(
        override val expectedRevision: Long,
        val tonalLayoutId: WorkspaceTonalLayoutId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("selectChordTonalLayout")
    data class SelectChordTonalLayout(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
        val tonalLayoutId: WorkspaceTonalLayoutId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("removeIdiom")
    data class RemoveIdiom(
        override val expectedRevision: Long,
        val idiomInstanceId: WorkspaceIdiomInstanceId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("insertIdiom")
    data class InsertIdiom(
        override val expectedRevision: Long,
        val anchorSlotId: WorkspaceSlotId,
        val definitionId: String,
        val variantId: String,
    ) : FreePracticeIntent

    /** Replaces the next chord slot, or appends one when [sourceSlotId] is last; never creates an idiom. */
    @Serializable
    @SerialName("insertVoiceLeadingChord")
    data class InsertVoiceLeadingChord(
        override val expectedRevision: Long,
        val sourceSlotId: WorkspaceSlotId,
        val targetPitchClasses: List<Int>,
        /** Index in the candidate's deterministic shortest-path list; preserves move order. */
        val pathIndex: Int,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("replaceIdiom")
    data class ReplaceIdiom(
        override val expectedRevision: Long,
        val idiomInstanceId: WorkspaceIdiomInstanceId,
        val definitionId: String,
        val variantId: String,
    ) : FreePracticeIntent

    /** Changes one step's chord size while preserving the selected basic progression formula. */
    @Serializable
    @SerialName("setIdiomChordToneCount")
    data class SetIdiomChordToneCount(
        override val expectedRevision: Long,
        val idiomInstanceId: WorkspaceIdiomInstanceId,
        val stepIndex: Int,
        val toneCount: Int,
    ) : FreePracticeIntent {
        init {
            require(stepIndex >= 0)
            require(toneCount > 0)
        }
    }

    @Serializable
    @SerialName("insertChordRange")
    data class InsertChordRange(
        override val expectedRevision: Long,
        val onset: Fraction,
        val duration: Fraction,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setDefaultChordDuration")
    data class SetDefaultChordDuration(
        override val expectedRevision: Long,
        val duration: Fraction,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setPracticeTimeSignature")
    data class SetPracticeTimeSignature(
        override val expectedRevision: Long,
        val timeSignature: TimeSignature,
    ) : FreePracticeIntent

    @Serializable
    enum class MeasureInsertionPosition { END, AFTER_SELECTED_NOTE, AT_SELECTED_BARLINE }

    @Serializable
    @SerialName("insertPracticeMeasures")
    data class InsertPracticeMeasures(
        override val expectedRevision: Long,
        val position: MeasureInsertionPosition,
        val count: Int,
        val chordDuration: Fraction,
    ) : FreePracticeIntent

    /**
     * Commits a timeline gesture. The payload is the very [PracticeTimelineEdit] the platform
     * already sent to [PracticeTimelinePreviewRequest], so preview and commit cannot drift apart —
     * they resolve to the same workspace command through one mapping in the session.
     */
    @Serializable
    @SerialName("timelineEdit")
    data class TimelineEdit(
        override val expectedRevision: Long,
        val edit: PracticeTimelineEdit,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("removeChordRange")
    data class RemoveChordRange(
        override val expectedRevision: Long,
        val slotId: WorkspaceSlotId,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("runWriting")
    data class RunWriting(
        override val expectedRevision: Long,
        val triggerSlotId: WorkspaceSlotId,
        val requiredSlotIds: List<WorkspaceSlotId>? = null,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("rewriteSelection")
    data class RewriteSelection(
        override val expectedRevision: Long,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("alternateWriting")
    data class AlternateWriting(override val expectedRevision: Long) : FreePracticeIntent

    @Serializable
    @SerialName("cancelWriting")
    data class CancelWriting(override val expectedRevision: Long) : FreePracticeIntent

    @Serializable
    @SerialName("updateWritingSettings")
    data class UpdateWritingSettings(
        override val expectedRevision: Long,
        val settings: FreePracticeWritingSettings,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("updateStaffVoices")
    data class UpdateStaffVoices(
        override val expectedRevision: Long,
        val staffVoices: GrandStaffVoiceLayout,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setCatalogFilter")
    data class SetCatalogFilter(
        override val expectedRevision: Long,
        val includeOffKey: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setHarmonicRole")
    data class SetHarmonicRole(
        override val expectedRevision: Long,
        val noteheads: Set<PracticeNoteheadRef>,
        val role: PracticeHarmonicRole? = null,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setHarmonicRoleFilters")
    data class SetHarmonicRoleFilters(
        override val expectedRevision: Long,
        val chordCatalogEnabled: Boolean,
        val idiomCatalogEnabled: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setNoteheadLock")
    data class SetNoteheadLock(
        override val expectedRevision: Long,
        val noteheads: Set<PracticeNoteheadRef>,
        val locked: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setVoiceLock")
    data class SetVoiceLock(
        override val expectedRevision: Long,
        val voiceTrackId: TrackId,
        val locked: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setVoiceLocks")
    data class SetVoiceLocks(
        override val expectedRevision: Long,
        val voiceTrackIds: Set<TrackId>,
        val locked: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setStaffLock")
    data class SetStaffLock(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val locked: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("setStaffLocks")
    data class SetStaffLocks(
        override val expectedRevision: Long,
        val staffTrackIds: Set<TrackId>,
        val locked: Boolean,
    ) : FreePracticeIntent

    @Serializable
    @SerialName("rebuildPractice")
    data class RebuildPractice(
        override val expectedRevision: Long,
        val polyphonyLimit: Int,
        val fifths: Int,
        val mode: WorkspaceKeyMode,
    ) : FreePracticeIntent {
        init {
            require(polyphonyLimit in 3..6) { "Free practice supports 3-6 simultaneous notes" }
            require(fifths in -7..7) { "A free-practice rebuild must use a valid key signature" }
        }
    }

    @Serializable
    @SerialName("undo")
    data class Undo(override val expectedRevision: Long) : FreePracticeIntent

    @Serializable
    @SerialName("redo")
    data class Redo(override val expectedRevision: Long) : FreePracticeIntent
}

@Serializable
enum class FreePracticeEffectKind {
    APPLIED,
    SELECTION_CHANGED,
    WRITING_REQUESTED,
    WRITING_APPLIED,
    WRITING_CANCELLED,
    UNDONE,
    REDONE,
    STALE_REVISION,
    STALE_TARGET,
    STALE_BACKGROUND_RESULT,
    CATALOG_UPDATED,
    FINDINGS_UPDATED,
    PRACTICE_REBUILT,
    INVALID,
    NO_OP,
}

@Serializable
data class FreePracticeEffect(
    val kind: FreePracticeEffectKind,
    val messageKey: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val expectedRevision: Long? = null,
    val actualRevision: Long? = null,
)

@Serializable
data class PracticeDiagnostic(
    val code: String,
    val messageKey: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class PracticeReplayRange(
    val firstSlotId: WorkspaceSlotId,
    val lastSlotId: WorkspaceSlotId,
    val start: Fraction,
    val end: Fraction,
    val tempoBpm: Int,
)

/** One-shot playback decided by the shared session after an edit; platforms only render audio. */
@Serializable
sealed interface PracticeEditPlayback {
    @Serializable
    @SerialName("audition")
    data class Audition(val midiNumbers: List<Int>) : PracticeEditPlayback

    @Serializable
    @SerialName("excerpt")
    data class Excerpt(val range: PracticeReplayRange) : PracticeEditPlayback
}

@Serializable
sealed interface PracticeWritingOutcome {
    @Serializable
    @SerialName("solved")
    data class Solved(
        val scope: List<WorkspaceSlotId>,
        val replayRange: PracticeReplayRange?,
    ) : PracticeWritingOutcome

    @Serializable
    @SerialName("noSolution")
    data object NoSolution : PracticeWritingOutcome

    @Serializable
    @SerialName("budgetExhausted")
    data object BudgetExhausted : PracticeWritingOutcome

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : PracticeWritingOutcome

    @Serializable
    @SerialName("invalid")
    data class Invalid(val diagnostics: List<PracticeDiagnostic>) : PracticeWritingOutcome

    /**
     * The background engine crashed instead of answering. Deliberately distinct from [Invalid]:
     * nothing about the request was wrong, so shells must report a defect — and say that the
     * workbench rolled back — rather than present it as a teaching result.
     */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String) : PracticeWritingOutcome
}

@Serializable
enum class PracticeWritingPhase { IDLE, RUNNING, READY }

@Serializable
data class PracticeWritingStatus(
    val phase: PracticeWritingPhase = PracticeWritingPhase.IDLE,
    val outcome: PracticeWritingOutcome? = null,
    val canAlternate: Boolean = false,
    val lastScope: List<WorkspaceSlotId> = emptyList(),
)

@Serializable
enum class PracticeFindingSeverity { INFO, WARNING, ERROR }

@Serializable
data class PracticeFindingView(
    val messageKey: String,
    val arguments: Map<String, String> = emptyMap(),
    val severity: PracticeFindingSeverity,
    val ruleId: String? = null,
    val anchors: List<com.mecon.api.primitive.EventId> = emptyList(),
    /** Domain-provided rule explanation; null when the platform should localize [messageKey]. */
    val message: String? = null,
)

@Serializable
data class PracticeFindingsView(
    val generation: Long,
    val stale: Boolean = false,
    val items: List<PracticeFindingView> = emptyList(),
)

@Serializable
data class PracticeChordCatalogAlternateReadingView(
    val key: PracticeKeyView,
    val keyLabel: String,
    val functionalSymbol: String,
    val relativeLabel: String,
    val absoluteLabel: String,
)

@Serializable
data class PracticeChordCatalogItem(
    val id: String,
    val symbol: String,
    val choice: WorkspaceChordChoice,
    val absoluteTones: List<String> = emptyList(),
    val relativeTones: List<String> = emptyList(),
    val rootPitchClass: Int? = null,
    val interpretationCount: Int = 0,
    val relativeLabel: String = "",
    val absoluteLabel: String = "",
    val alternateTonalReadings: List<PracticeChordCatalogAlternateReadingView> = emptyList(),
)

@Serializable
data class PracticeChordCatalogGroupView(
    val id: String,
    val titleLabel: String,
    val descriptionLabel: String,
    val choices: List<PracticeChordCatalogItem>,
)

@Serializable
enum class PracticeVoiceLeadingRootMotion {
    RISING,
    DESCENDING,
    SUPERSTRONG,
    REPEATED,
    UNCLASSIFIED,
}

@Serializable
enum class PracticeVoiceLeadingParallelRisk {
    PARALLEL_FIFTH,
    PARALLEL_OCTAVE_IF_DOUBLED,
}

@Serializable
data class PracticeVoiceLeadingMoveView(
    val order: Int,
    val sourceToneIndex: Int,
    val fromPitchClass: Int,
    val toPitchClass: Int,
    val semitones: Int,
    val absoluteLabel: String,
    val relativeLabel: String,
)

@Serializable
data class PracticeVoiceLeadingPathView(
    val id: String,
    val moves: List<PracticeVoiceLeadingMoveView>,
    val parallelRisks: Set<PracticeVoiceLeadingParallelRisk> = emptySet(),
    val warningLabel: String = "",
    val threeTonesSameDirection: Boolean = false,
)

@Serializable
data class PracticeVoiceLeadingRootConnectionView(
    val sourceRootPitchClass: Int,
    val targetRootPitchClass: Int,
    val motion: PracticeVoiceLeadingRootMotion,
    val colorToken: String,
    val absoluteLabel: String,
    val relativeLabel: String,
    val hintLabel: String,
)

@Serializable
data class PracticeVoiceLeadingToneView(
    val pitchClass: Int,
    val absoluteLabel: String,
    val relativeLabel: String,
    val changed: Boolean,
)

@Serializable
data class PracticeVoiceLeadingCandidateView(
    val id: String,
    val choice: WorkspaceChordChoice,
    val transformationCount: Int,
    val quality: String,
    val absoluteLabel: String,
    val relativeLabel: String,
    /** Primary deterministic path used by the compact source → target presentation and insertion. */
    val primaryPathIndex: Int,
    val sourceTones: List<PracticeVoiceLeadingToneView>,
    val targetTones: List<PracticeVoiceLeadingToneView>,
    val paths: List<PracticeVoiceLeadingPathView>,
    /** False when every shortest path consists of three distinct tones moving in one direction. */
    val availableWhenThreeToneSameDirectionFiltered: Boolean,
    val rootConnection: PracticeVoiceLeadingRootConnectionView,
)

@Serializable
data class PracticeVoiceLeadingStepGroupView(
    val transformationCount: Int,
    val titleLabel: String,
    val candidates: List<PracticeVoiceLeadingCandidateView>,
)

@Serializable
data class PracticeVoiceLeadingView(
    val available: Boolean = false,
    val familyId: String? = null,
    val titleLabel: String = "新里曼 / Voice leading",
    val descriptionLabel: String = "每一步只移动一个原始和弦音 1–2 个半音；同一路径不重复移动同一音。",
    val filterThreeToneSameDirectionLabel: String = "过滤三音同向变换",
    val emptyLabel: String = "所选和弦不属于已注册的三和弦或七和弦类型。",
    val groups: List<PracticeVoiceLeadingStepGroupView> = emptyList(),
)

@Serializable
data class PracticeCatalogView(
    val requestKey: String,
    val chordChoices: List<PracticeChordCatalogItem>,
    val chordGroups: List<PracticeChordCatalogGroupView> = emptyList(),
    val harmonicRoleFilterEnabled: Boolean = false,
)

@Serializable
data class PracticeNoteheadRoleView(
    val notehead: PracticeNoteheadRef,
    val inferredRole: PracticeHarmonicRole? = null,
    val explicitRole: PracticeHarmonicRole? = null,
    val conflict: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class PracticeNoteConstraintView(
    val noteheads: List<PracticeNoteheadRoleView> = emptyList(),
    val chordCatalogFilterEnabled: Boolean = false,
    val idiomCatalogFilterEnabled: Boolean = false,
    val lockedVoiceTrackIds: Set<TrackId> = emptySet(),
    val lockedStaffTrackIds: Set<TrackId> = emptySet(),
)

@Serializable
data class PracticeChordCatalogFilterView(
    val id: String,
    val key: PracticeKeyView,
    val keyLabel: String,
    val tonalLayoutId: WorkspaceTonalLayoutId,
    val selected: Boolean = false,
    val chordGroups: List<PracticeChordCatalogGroupView> = emptyList(),
    val toneCountFilters: List<PracticeChordToneCountFilterView> = emptyList(),
)

@Serializable
data class PracticeChordToneCountFilterView(
    val id: String,
    /** Null means no tone-count restriction. */
    val toneCount: Int? = null,
    val label: String,
    /** Already filtered by commonMain; platforms only choose and render one result. */
    val chordGroups: List<PracticeChordCatalogGroupView> = emptyList(),
)

@Serializable
data class PracticeTimelineSlotCapabilities(
    val canTranslate: Boolean = true,
    val canResizeStart: Boolean = true,
    val canResizeEnd: Boolean = true,
    val canRemove: Boolean = true,
)

@Serializable
data class PracticeTimelineChordReadingView(
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val keyLabel: String,
    val functionalSymbol: String,
    val absoluteTones: List<String> = emptyList(),
    val relativeTones: List<String> = emptyList(),
    val symbolLabel: String = functionalSymbol,
    val absoluteTonesLabel: String = absoluteTones.joinToString(" · "),
    val relativeTonesLabel: String = relativeTones.joinToString(" · "),
)

@Serializable
data class PracticeTimelineSlotView(
    val id: WorkspaceSlotId,
    val onset: Fraction,
    val duration: Fraction,
    val symbol: String? = null,
    val absoluteTones: List<String> = emptyList(),
    val relativeTones: List<String> = emptyList(),
    val readings: List<PracticeTimelineChordReadingView> = emptyList(),
    val pitchClasses: List<Int> = emptyList(),
    val bassPitchClass: Int? = null,
    val isPivotChord: Boolean = false,
    val inversionLocked: Boolean = false,
    val capabilities: PracticeTimelineSlotCapabilities = PracticeTimelineSlotCapabilities(),
    /** Non-functional fallback labels, selected by the shared timeline's tone-label mode. */
    val absoluteSymbol: String? = null,
    val relativeSymbol: String? = null,
)

@Serializable
data class PracticeTimelineEmptySlotView(
    /** Stable within one projection; activating it creates a persisted workspace slot. */
    val id: String,
    val onset: Fraction,
    val duration: Fraction,
)

@Serializable
data class PracticeDerivedTonalSpanView(
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val keyLabel: String,
    val start: Fraction,
    val end: Fraction,
)

@Serializable
data class PracticeTonalLayoutView(
    val id: WorkspaceTonalLayoutId,
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val start: Fraction,
    val end: Fraction? = null,
    val isBaseline: Boolean = false,
    val keyLabel: String = "",
    val rangeLabel: String = "",
    val baselineLabel: String? = null,
)

@Serializable
data class PracticeIdiomView(
    val id: WorkspaceIdiomInstanceId,
    val definitionId: String,
    val variantId: String,
    val slotIds: List<WorkspaceSlotId>,
    val title: String? = null,
    val start: Fraction? = null,
    val end: Fraction? = null,
)

@Serializable
data class PracticeTimelineView(
    val end: Fraction = Fraction.ZERO,
    val slots: List<PracticeTimelineSlotView> = emptyList(),
    val tonalLayouts: List<PracticeTonalLayoutView> = emptyList(),
    val derivedTonalSpans: List<PracticeDerivedTonalSpanView> = emptyList(),
    val idioms: List<PracticeIdiomView> = emptyList(),
    val emptySlots: List<PracticeTimelineEmptySlotView> = emptyList(),
)

@Serializable
data class PracticeStructureView(
    val pristine: Boolean = true,
    val effectiveTimeSignature: TimeSignature = TimeSignature.COMMON,
    val timeSignatureMeasure: Int = 1,
    val lastMeasure: Int = 1,
    val selectedNoteMeasure: Int? = null,
    val selectedBarlineMeasure: Int? = null,
    val rewriteSelectionAvailable: Boolean = false,
)

@Serializable
sealed interface PracticeTimelineEdit {
    @Serializable
    @SerialName("placeChordRange")
    data class PlaceChordRange(
        val slotId: WorkspaceSlotId,
        val onset: Fraction,
        val duration: Fraction,
    ) : PracticeTimelineEdit

    @Serializable
    @SerialName("moveSharedBoundary")
    data class MoveSharedBoundary(
        val leftSlotId: WorkspaceSlotId,
        val boundary: Fraction,
    ) : PracticeTimelineEdit

    @Serializable
    @SerialName("translateChordRange")
    data class TranslateChordRange(
        val slotId: WorkspaceSlotId,
        val delta: Fraction,
        val includeFollowing: Boolean = false,
    ) : PracticeTimelineEdit

    @Serializable
    @SerialName("moveBoundaryWithFollowing")
    data class MoveBoundaryWithFollowing(
        val leftSlotId: WorkspaceSlotId,
        val boundary: Fraction,
    ) : PracticeTimelineEdit

    @Serializable
    @SerialName("setTonalLayoutBounds")
    data class SetTonalLayoutBounds(
        val tonalLayoutId: WorkspaceTonalLayoutId,
        val start: Fraction,
        val end: Fraction? = null,
    ) : PracticeTimelineEdit
}

/**
 * Slot whose material auto-writing should re-solve after this edit, or `null` when the edit only
 * moves tonal-layout bounds and leaves every chord slot in place.
 */
val PracticeTimelineEdit.autoWritingTriggerSlotId: WorkspaceSlotId?
    get() = when (this) {
        is PracticeTimelineEdit.PlaceChordRange -> slotId
        is PracticeTimelineEdit.MoveSharedBoundary -> leftSlotId
        is PracticeTimelineEdit.TranslateChordRange -> slotId
        is PracticeTimelineEdit.MoveBoundaryWithFollowing -> leftSlotId
        is PracticeTimelineEdit.SetTonalLayoutBounds -> null
    }

@Serializable
data class PracticeTimelinePreviewRequest(
    val requestId: Long,
    val baseRevision: Long,
    val edit: PracticeTimelineEdit,
)

@Serializable
data class PracticeTimelinePreviewResult(
    val requestId: Long,
    val baseRevision: Long,
    val accepted: Boolean,
    val timeline: PracticeTimelineView? = null,
    val reasonKey: String? = null,
)

@Serializable
data class PracticeKeyView(
    val fifths: Int,
    val mode: WorkspaceKeyMode,
)

@Serializable
data class PracticePlanNavigationView(
    val previousSlotId: WorkspaceSlotId? = null,
    val nextSlotId: WorkspaceSlotId? = null,
    val lastSlotId: WorkspaceSlotId? = null,
    val appendOnset: Fraction? = null,
    val previousLabel: String = "上一个和弦",
    val nextLabel: String = "下一个和弦",
    val lastLabel: String = "末尾和弦",
    val appendLabel: String = "在末尾添加和弦",
    val removeChord: String = "删除当前和弦槽",
)

@Serializable
data class PracticeChordTonalityChoiceView(
    val id: String,
    val key: PracticeKeyView,
    val keyLabel: String,
    val functionalSymbol: String,
    val absoluteTones: List<String> = emptyList(),
    val relativeTones: List<String> = emptyList(),
    val relativeTonesLabel: String = "",
    val absoluteTonesLabel: String = "",
    val displayLabel: String = "",
    val directionLabel: String = "",
    /** Ready-to-dispatch payload. Platforms must not reconstruct tonal readings. */
    val tonality: WorkspaceChordTonality? = null,
    val selected: Boolean = false,
)

@Serializable
data class PracticeTonalityReadingRowView(
    val id: String,
    val headingLabel: String,
    val relativeDetailLabel: String = "",
    val absoluteDetailLabel: String = "",
    val primary: Boolean,
    val removeTonality: WorkspaceChordTonality? = null,
)

@Serializable
data class PracticeBassOptionView(
    val pitchClass: Int? = null,
    val relativeLabel: String,
    val absoluteLabel: String,
    val selected: Boolean = false,
)

@Serializable
data class PracticeCoveredIdiomView(
    val id: WorkspaceIdiomInstanceId,
    val displayLabel: String,
    val startsHere: Boolean,
)

@Serializable
data class PracticePlanKeyFilterView(
    val id: String,
    val label: String,
    val key: PracticeKeyView? = null,
)

@Serializable
data class PracticePlanStrings(
    val panelAriaLabel: String = "自由练习计划",
    val unloadedTitle: String = "计划",
    val unloadedMessage: String = "打开带自由练习模块的 .mecon 文件以加载共享工作台。",
    val currentTonalityTitle: String = "当前调性",
    val harmonySelectionTitle: String = "和声选择",
    val chordDetailTitle: String = "和弦详情",
    val idiomTitle: String = "惯用进行",
    val schoenbergProgressionsTab: String = "勋伯格",
    val voiceLeadingProgressionsTab: String = "Voice leading",
    val insertTonalLayout: String = "＋ 插入",
    val editTonalLayoutTitle: String = "修改当前调性",
    val insertTonalLayoutTitle: String = "在当前和弦插入调性线",
    val terminatePreviousLayout: String = "在当前和弦结束处终止上一条调性线",
    val insert: String = "插入",
    val tonalLayoutHelp: String = "点击当前调性可修改；插入的新旧调性会在当前和弦重叠。",
    val deleteTonalLayout: String = "删除调性线",
    val selectedChordEmpty: String = "—",
    val chordTonesEmpty: String = "未选择组成音",
    val coveredIdioms: String = "覆盖当前和弦的惯用进行",
    val offKey: String = "离调",
    val continueTemporaryTonality: String = "是否延续前一和弦的临时调性",
    val temporaryTonalityHelp: String = "选择原调会在此终止临时调性；选择临时调则由后续和弦继续继承。未显示功能名的调性与当前和弦不兼容，选中后会清空和弦。",
    val currentChordTonality: String = "当前和弦的调性解释",
    val primaryTonality: String = "主解释",
    val alternateTonality: String = "双重解释",
    val createDoubleTonality: String = "＋ 创建双重调性",
    val collapseDoubleTonality: String = "收起双重调性候选",
    val lockedTonalityHelp: String = "惯用进行内的调性由进行整体修改。",
    val removeChordTonality: String = "删除和弦调性解释",
    val followManualTonality: String = "跟随调性线",
    val chordCatalog: String = "选择和弦",
    val chordCatalogTonality: String = "按哪个调选和弦",
    val chordToneCount: String = "组成音个数",
    val idiomCatalogTonality: String = "按哪个调选惯用进行",
    val chooseChord: String = "选用和弦",
    val bass: String = "低音",
    val anyBass: String = "任意",
    val relativePitch: String = "相对音",
    val absolutePitch: String = "绝对音",
    val pivotChord: String = "枢纽和弦",
    val noChordDetail: String = "当前槽尚未选择和弦。",
    val rootPitchClass: String = "根音类",
    val interpretationCount: String = "读法",
    val showOffKeyIdioms: String = "展示离调进行",
    val filterTargetKey: String = "筛选目标调性",
    val allTargetKeys: String = "全部",
    val relatedIdioms: String = "与当前和弦相关",
    val allIdioms: String = "全部教材进行",
    val loadingRelatedIdioms: String = "正在查找与当前和弦相关的进行…",
    val loadingAllIdioms: String = "正在加载全部教材进行…",
    val noRelatedIdioms: String = "当前和弦没有可插入的惯用进行。",
    val noAllIdioms: String = "当前调性没有章节提供可插入的惯用进行。",
    val removeIdiom: String = "删除惯用进行",
    val detachIdiom: String = "解除",
    val insertIdiom: String = "插入到当前和弦",
    val replaceIdiom: String = "替换当前进行",
    val idiomChordForms: String = "调整和弦形态",
    val idiomCatalogError: String = "目录加载失败",
)

@Serializable
data class PracticePlanView(
    val strings: PracticePlanStrings = PracticePlanStrings(),
    val selectedSlotId: WorkspaceSlotId? = null,
    val selectedSlot: PracticeTimelineSlotView? = null,
    val navigation: PracticePlanNavigationView = PracticePlanNavigationView(),
    val currentKey: PracticeKeyView? = null,
    val tonalKeyChoices: List<PracticePlanKeyFilterView> = emptyList(),
    val activeTonalLayoutIds: List<WorkspaceTonalLayoutId> = emptyList(),
    val activeTonalLayouts: List<PracticeTonalLayoutView> = emptyList(),
    val editableTonalLayoutId: WorkspaceTonalLayoutId? = null,
    val editableTonalLayout: PracticeTonalLayoutView? = null,
    val selectedChord: PracticeChordCatalogItem? = null,
    val chordDetail: PracticeChordDetailView? = null,
    val voiceLeading: PracticeVoiceLeadingView = PracticeVoiceLeadingView(),
    val chordCatalogGroups: List<PracticeChordCatalogGroupView> = emptyList(),
    val chordCatalogFilters: List<PracticeChordCatalogFilterView> = emptyList(),
    val selectedChordReadings: List<PracticeTimelineChordReadingView> = emptyList(),
    val bassOptions: List<Int> = emptyList(),
    val bassChoices: List<PracticeBassOptionView> = emptyList(),
    val pivotEnabled: Boolean = false,
    val chordLocked: Boolean = false,
    val inversionLocked: Boolean = false,
    val coveredIdioms: List<PracticeIdiomView> = emptyList(),
    val coveredIdiomRows: List<PracticeCoveredIdiomView> = emptyList(),
    val tonalityChoices: List<PracticeChordTonalityChoiceView> = emptyList(),
    val continuationTonalityChoices: List<PracticeChordTonalityChoiceView> = emptyList(),
    val currentTonalityRows: List<PracticeTonalityReadingRowView> = emptyList(),
    val doubleTonalityChoices: List<PracticeChordTonalityChoiceView> = emptyList(),
    val idiomTargetKeys: List<PracticePlanKeyFilterView> = emptyList(),
    val idiomCatalogFilters: List<PracticePlanTonalLayoutFilterView> = emptyList(),
    val idiomCatalog: PracticeIdiomCatalogView = PracticeIdiomCatalogView(),
    val selectedIdiomForm: PracticeSelectedIdiomFormView? = null,
)

@Serializable
data class PracticePlanTonalLayoutFilterView(
    val id: String,
    val key: PracticeKeyView,
    val label: String,
    val tonalLayoutId: WorkspaceTonalLayoutId,
    val selected: Boolean,
)

/** Stable workbench selection projected from both the harmony and score sessions. */
@Serializable
data class FreePracticeSelection(
    val slotId: WorkspaceSlotId? = null,
    val tonalLayoutId: WorkspaceTonalLayoutId? = null,
    val idiomInstanceId: WorkspaceIdiomInstanceId? = null,
    val scoreTargets: List<ScoreSelectionTarget> = emptyList(),
)

@Serializable
data class PracticeIdiomVariantView(
    val id: String,
    /** Shared basic-progression identity; variants differ only in selectable chord sizes. */
    val structureId: String = id,
    /** Reinterpretation lineage that must survive a chord-size change. */
    val interpretationContextId: String = "",
    val title: String,
    val durations: List<Fraction>,
    val chordIdentities: List<String>,
    val chordChoices: List<WorkspaceChordChoice> = emptyList(),
    /** Open integer axis rather than a triad/seventh enum, so higher stacks extend the same wire. */
    val chordToneCounts: List<Int> = emptyList(),
    val suggestedKey: PracticeKeyView? = null,
    val targetKeyDistance: Int = 0,
    val parameters: Map<String, String> = emptyMap(),
    val anchorStepIndex: Int = 0,
    val fixedInversionStepIndices: Set<Int> = emptySet(),
    val customaryBassStepIndices: Set<Int> = emptySet(),
    val avoidSecondInversionStepIndices: Set<Int> = emptySet(),
    val displayLabel: String = "",
    val enabled: Boolean = true,
    val disabledReasonLabel: String? = null,
    /** The focused catalog aligned this concrete variant to the selected chord. */
    val relatedToFocus: Boolean = false,
    /** The focus-independent catalog exposes this concrete variant by default. */
    val availableByDefault: Boolean = false,
)

@Serializable
data class PracticePivotRecipeView(
    val sourceKey: PracticeKeyView,
    val targetKey: PracticeKeyView,
    val pitchClasses: Set<Int>,
    val sourceReading: String,
    val targetReading: String,
    val definition: String,
)

@Serializable
data class PracticeIdiomDefinitionView(
    val id: String,
    val title: String,
    val sourceExerciseId: String,
    val sourceChapterId: String,
    val availableByDefault: Boolean,
    val variants: List<PracticeIdiomVariantView>,
    val relatedToFocus: Boolean = false,
    /** Collapsed, presentation-ready basic progressions. Concrete [variants] stay session-owned. */
    val choices: List<PracticeIdiomChoiceView> = emptyList(),
)

@Serializable
data class PracticeIdiomChoiceView(
    val id: String,
    val title: String,
    val displayLabel: String,
    val variantIds: List<String>,
    val defaultVariantId: String,
    val relatedVariantId: String? = null,
    val suggestedKey: PracticeKeyView? = null,
    val availableByDefault: Boolean = false,
    val relatedToFocus: Boolean = false,
    val enabled: Boolean = true,
    val disabledReasonLabel: String? = null,
)

internal data class PracticeIdiomRealizationFamilyKey(
    val structureId: String,
    val interpretationContextId: String,
    val suggestedKey: PracticeKeyView?,
)

internal fun PracticeIdiomVariantView.realizationFamilyKey(): PracticeIdiomRealizationFamilyKey =
    PracticeIdiomRealizationFamilyKey(structureId, interpretationContextId, suggestedKey)

@Serializable
data class PracticeIdiomToneCountOptionView(
    val toneCount: Int,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
)

@Serializable
data class PracticeIdiomToneCountStepView(
    val stepIndex: Int,
    val chordLabel: String,
    val options: List<PracticeIdiomToneCountOptionView>,
)

@Serializable
data class PracticeSelectedIdiomFormView(
    val idiomInstanceId: WorkspaceIdiomInstanceId,
    val definitionId: String,
    val structureId: String,
    val title: String,
    val steps: List<PracticeIdiomToneCountStepView>,
)

@Serializable
data class PracticeIdiomCatalogView(
    val generation: Long = 0,
    val requestKey: String? = null,
    val loading: Boolean = false,
    val errorKey: String? = null,
    val includeOffKey: Boolean = false,
    val definitions: List<PracticeIdiomDefinitionView> = emptyList(),
    val pivotRecipes: List<PracticePivotRecipeView> = emptyList(),
)

@Serializable
data class PracticeTeachingCatalogRequest(
    val requestId: Long,
    val baseRevision: Long,
    val fingerprint: String,
    val initialKey: PracticeKeyView,
    val activeKeys: List<PracticeKeyView>,
    val catalogKey: PracticeKeyView,
    val focus: WorkspaceChordChoice? = null,
    val includeOffKey: Boolean = false,
    val focusOnset: Fraction = Fraction.ZERO,
    val harmonicRoleFilterEnabled: Boolean = false,
    val harmonicRoleConstraints: List<PracticeHarmonicRoleConstraint> = emptyList(),
)

@Serializable
data class PracticeHarmonicRoleConstraint(
    val onset: Fraction,
    val pitchClass: Int,
    val role: PracticeHarmonicRole,
) {
    init {
        require(pitchClass in 0..11) { "A harmonic-role pitch class must be in 0..11" }
    }
}

@Serializable
data class PracticeTeachingCatalogResult(
    val requestId: Long,
    val baseRevision: Long,
    val fingerprint: String,
    val definitions: List<PracticeIdiomDefinitionView> = emptyList(),
    val pivotRecipes: List<PracticePivotRecipeView> = emptyList(),
    val errorKey: String? = null,
)

@Serializable
data class PracticeFindingRequest(
    val requestId: Long,
    val baseRevision: Long,
    val fingerprint: String,
    val document: FreePracticeDocument,
    val score: StorageScore,
)

@Serializable
data class PracticeFindingResult(
    val requestId: Long,
    val baseRevision: Long,
    val fingerprint: String,
    val items: List<PracticeFindingView> = emptyList(),
)

@Serializable
data class PracticeVoicingFrame(
    val slotId: WorkspaceSlotId,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
    val segmentId: String = slotId.value,
    val onset: Fraction? = null,
    val duration: Fraction? = null,
)

@Serializable
data class PracticeVoicingCandidate(
    val frames: List<PracticeVoicingFrame>,
    val diversityGroupKey: String,
    val score: Double,
    val diagnosticKeys: List<String> = emptyList(),
)

@Serializable
enum class PracticeBackgroundRequestKind { FIRST_SOLVE, OPTIMIZE_CANDIDATES }

@Serializable
data class PracticeSearchConfig(
    val maxResults: Int,
    val beamWidth: Int,
    val seed: Long = 0L,
)

@Serializable
data class PracticeBackgroundRequest(
    val requestId: Long,
    val baseRevision: Long,
    val scopeFingerprint: String,
    val kind: PracticeBackgroundRequestKind,
    val document: FreePracticeDocument,
    val score: StorageScore,
    val scopeSlotIds: List<WorkspaceSlotId>,
    val triggerSlotId: WorkspaceSlotId,
    val leftBoundarySlotId: WorkspaceSlotId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val replayWholeScope: Boolean = false,
    val excludedDiversityGroupKeys: Set<String> = emptySet(),
    val search: PracticeSearchConfig,
)

@Serializable
data class PracticeBackgroundResult(
    val requestId: Long,
    val baseRevision: Long,
    val scopeFingerprint: String,
    val kind: PracticeBackgroundRequestKind,
    val candidates: List<PracticeVoicingCandidate> = emptyList(),
    val outcome: PracticeWritingOutcome,
)

/**
 * A background channel reporting that it crashed and will never deliver a result.
 *
 * Only [requestId] identifies it: the session already holds every other property of the request it
 * issued, and a crashed worker must not be trusted to reconstruct them. Shells send this whenever a
 * background worker throws, dies or fails to load — otherwise the session waits forever and the
 * workbench stays locked in [PracticeWritingPhase.RUNNING].
 */
@Serializable
data class PracticeBackgroundFailure(
    val requestId: Long,
    val reason: String,
)

/** Serializable update used by JVM/JS traces and the web worker boundary. */
@Serializable
data class FreePracticeUpdate(
    val schemaVersion: Int = FREE_PRACTICE_WIRE_SCHEMA_VERSION,
    val revision: Long,
    val baseRevision: Long? = null,
    val document: FreePracticeDocument,
    val score: ScoreEditUpdate,
    /**
     * Whether this operation committed something worth persisting — practice document, settings or
     * score. Shells must read this instead of classifying effect kinds themselves: a commit can be
     * followed by an [FreePracticeEffectKind.INVALID] outcome (an unsolvable writing scope still
     * changes the document), and that combination is unrepresentable as an effect allowlist.
     */
    val documentChanged: Boolean = false,
    val selection: FreePracticeSelection = FreePracticeSelection(),
    /** Compatibility alias for wire-v2 clients; new clients should read [selection]. */
    val selectedSlotId: WorkspaceSlotId?,
    val findings: PracticeFindingsView,
    val catalog: PracticeCatalogView,
    val noteConstraints: PracticeNoteConstraintView = PracticeNoteConstraintView(),
    val timeline: PracticeTimelineView = PracticeTimelineView(),
    val structure: PracticeStructureView = PracticeStructureView(),
    val plan: PracticePlanView = PracticePlanView(),
    val writing: PracticeWritingStatus,
    val effect: FreePracticeEffect,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val editPlayback: PracticeEditPlayback? = null,
    val requests: List<PracticeBackgroundRequest> = emptyList(),
    val catalogRequests: List<PracticeTeachingCatalogRequest> = emptyList(),
    val findingRequests: List<PracticeFindingRequest> = emptyList(),
)

data class FreePracticeFrame(
    val revision: Long,
    val document: FreePracticeDocument,
    val score: ScoreEditingFrame,
    val selection: FreePracticeSelection,
    val selectedSlotId: WorkspaceSlotId?,
    val findings: PracticeFindingsView,
    val catalog: PracticeCatalogView,
    val noteConstraints: PracticeNoteConstraintView,
    val timeline: PracticeTimelineView,
    val structure: PracticeStructureView,
    val plan: PracticePlanView,
    val writing: PracticeWritingStatus,
)

data class FreePracticeDispatchResult(
    val frame: FreePracticeFrame,
    val baseRevision: Long?,
    val effect: FreePracticeEffect,
    val editPlayback: PracticeEditPlayback? = null,
    val requests: List<PracticeBackgroundRequest> = emptyList(),
    val catalogRequests: List<PracticeTeachingCatalogRequest> = emptyList(),
    val findingRequests: List<PracticeFindingRequest> = emptyList(),
    /** Exact inner update when this result originated from [FreePracticeIntent.Score]. */
    val scoreUpdate: ScoreEditUpdate? = null,
)

const val FREE_PRACTICE_WIRE_SCHEMA_VERSION: Int = 7

object FreePracticeCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeIntent(value: FreePracticeIntent): String =
        json.encodeToString(FreePracticeIntent.serializer(), value)

    fun decodeIntent(value: String): FreePracticeIntent =
        json.decodeFromString(FreePracticeIntent.serializer(), value)

    fun encodeUpdate(value: FreePracticeUpdate): String =
        json.encodeToString(FreePracticeUpdate.serializer(), value)

    fun decodeUpdate(value: String): FreePracticeUpdate =
        json.decodeFromString(FreePracticeUpdate.serializer(), value)

    fun encodeBackgroundResult(value: PracticeBackgroundResult): String =
        json.encodeToString(PracticeBackgroundResult.serializer(), value)

    fun decodeBackgroundResult(value: String): PracticeBackgroundResult =
        json.decodeFromString(PracticeBackgroundResult.serializer(), value)

    fun encodeBackgroundFailure(value: PracticeBackgroundFailure): String =
        json.encodeToString(PracticeBackgroundFailure.serializer(), value)

    fun decodeBackgroundFailure(value: String): PracticeBackgroundFailure =
        json.decodeFromString(PracticeBackgroundFailure.serializer(), value)

    fun encodeBackgroundRequest(value: PracticeBackgroundRequest): String =
        json.encodeToString(PracticeBackgroundRequest.serializer(), value)

    fun decodeBackgroundRequest(value: String): PracticeBackgroundRequest =
        json.decodeFromString(PracticeBackgroundRequest.serializer(), value)

    fun encodeTeachingCatalogRequest(value: PracticeTeachingCatalogRequest): String =
        json.encodeToString(PracticeTeachingCatalogRequest.serializer(), value)

    fun decodeTeachingCatalogRequest(value: String): PracticeTeachingCatalogRequest =
        json.decodeFromString(PracticeTeachingCatalogRequest.serializer(), value)

    fun encodeTeachingCatalogResult(value: PracticeTeachingCatalogResult): String =
        json.encodeToString(PracticeTeachingCatalogResult.serializer(), value)

    fun decodeTeachingCatalogResult(value: String): PracticeTeachingCatalogResult =
        json.decodeFromString(PracticeTeachingCatalogResult.serializer(), value)

    fun encodeFindingRequest(value: PracticeFindingRequest): String =
        json.encodeToString(PracticeFindingRequest.serializer(), value)

    fun decodeFindingRequest(value: String): PracticeFindingRequest =
        json.decodeFromString(PracticeFindingRequest.serializer(), value)

    fun encodeFindingResult(value: PracticeFindingResult): String =
        json.encodeToString(PracticeFindingResult.serializer(), value)

    fun decodeFindingResult(value: String): PracticeFindingResult =
        json.decodeFromString(PracticeFindingResult.serializer(), value)

    fun encodeTimelinePreviewRequest(value: PracticeTimelinePreviewRequest): String =
        json.encodeToString(PracticeTimelinePreviewRequest.serializer(), value)

    fun decodeTimelinePreviewRequest(value: String): PracticeTimelinePreviewRequest =
        json.decodeFromString(PracticeTimelinePreviewRequest.serializer(), value)

    fun encodeTimelinePreviewResult(value: PracticeTimelinePreviewResult): String =
        json.encodeToString(PracticeTimelinePreviewResult.serializer(), value)

    fun decodeTimelinePreviewResult(value: String): PracticeTimelinePreviewResult =
        json.decodeFromString(PracticeTimelinePreviewResult.serializer(), value)
}
