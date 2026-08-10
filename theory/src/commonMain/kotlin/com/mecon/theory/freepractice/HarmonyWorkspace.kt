package com.mecon.theory.freepractice

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.HarmonicPattern
import com.mecon.theory.constraint.HarmonicPatternId
import com.mecon.theory.constraint.PatternCompletion
import com.mecon.theory.constraint.PatternMatchState
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordSelectionOriginRef
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.AudibleSonorityKey
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class WorkspaceSlotId(val value: String) {
    init {
        require(value.isNotBlank()) { "A workspace slot id cannot be blank" }
    }
}

@Serializable
@JvmInline
value class WorkspaceNoteId(val value: String) {
    init {
        require(value.isNotBlank()) { "A workspace note id cannot be blank" }
    }
}

@Serializable
@JvmInline
value class WorkspaceTonalLayoutId(val value: String) {
    init {
        require(value.isNotBlank()) { "A tonal-layout id cannot be blank" }
    }
}

@Serializable
@JvmInline
value class WorkspaceIdiomInstanceId(val value: String) {
    init {
        require(value.isNotBlank()) { "A customary-progression id cannot be blank" }
    }
}

@Serializable
enum class WorkspaceKeyMode {
    MAJOR,
    MINOR;

    fun toTheory(): KeySignatureMode =
        when (this) {
            MAJOR -> KeySignatureMode.MAJOR
            MINOR -> KeySignatureMode.MINOR
        }

    companion object {
        fun fromTheory(mode: KeySignatureMode): WorkspaceKeyMode =
            when (mode) {
                KeySignatureMode.MAJOR -> MAJOR
                KeySignatureMode.MINOR -> MINOR
            }
    }
}

/**
 * One horizontal key line in free practice. A null [end] follows the workspace tail as it grows.
 */
@Serializable
data class WorkspaceTonalLayout(
    val id: WorkspaceTonalLayoutId,
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val start: Fraction,
    val end: Fraction? = null,
    val isBaseline: Boolean = false,
) {
    init {
        require(fifths in -7..7) { "A tonal layout must use a valid key signature" }
        require(!start.isNegative) { "A tonal layout cannot start before the score" }
        require(end == null || end > start) { "A tonal layout must have a positive duration" }
        require(!isBaseline || start == Fraction.ZERO) {
            "The initial tonal-layout baseline must start at the workspace origin"
        }
    }

    val key: ModulationKey get() = ModulationKey(fifths, mode.toTheory())

    fun contains(time: Fraction): Boolean =
        time >= start && (end == null || time < end)
}

/** One key-specific reading stored directly on a harmony chord. */
@Serializable
data class WorkspaceChordTonalReading(
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val interpretationRef: ChordInterpretationRef? = null,
) {
    init {
        require(fifths in -7..7) { "A chord tonal reading must use a valid key signature" }
    }

    val key: ModulationKey get() = ModulationKey(fifths, mode.toTheory())

    companion object {
        fun of(
            key: ModulationKey,
            interpretationRef: ChordInterpretationRef? = null,
        ): WorkspaceChordTonalReading = WorkspaceChordTonalReading(
            fifths = key.fifths,
            mode = WorkspaceKeyMode.fromTheory(key.mode),
            interpretationRef = interpretationRef,
        )
    }
}

/**
 * Chord-owned tonality. [primary] is inherited by following input; alternates are simultaneous
 * readings of this chord. Entries deliberately carry no customary-progression provenance.
 */
@Serializable
data class WorkspaceChordTonality(
    val primary: WorkspaceChordTonalReading,
    val alternates: List<WorkspaceChordTonalReading> = emptyList(),
) {
    init {
        val keys = readings.map { it.key }
        require(keys.distinct().size == keys.size) {
            "A chord cannot store the same tonal reading more than once"
        }
    }

    val readings: List<WorkspaceChordTonalReading> get() = listOf(primary) + alternates

    fun readingFor(key: ModulationKey): WorkspaceChordTonalReading? =
        readings.firstOrNull { it.key == key }
}

/** Read-only tonal span derived from chord-owned markers. */
data class WorkspaceDerivedTonalSpan(
    val key: ModulationKey,
    val start: Fraction,
    val end: Fraction,
) {
    init {
        require(end > start) { "A derived tonal span must have a positive duration" }
    }
}

/** A chapter-owned customary progression inserted as one editable/undoable unit. */
@Serializable
data class WorkspaceIdiomInstance(
    val id: WorkspaceIdiomInstanceId,
    val definitionId: String,
    val variantId: String,
    val sourceExerciseId: String,
    val sourceChapterId: String,
    val tonalLayoutId: WorkspaceTonalLayoutId? = null,
    val slotIds: List<WorkspaceSlotId>,
    /** Expected chord tonalities aligned with [slotIds]; empty for schema v7 instances. */
    val tonalities: List<WorkspaceChordTonality> = emptyList(),
    val parameters: Map<String, String> = emptyMap(),
    /** Null keeps legacy fixed-bass semantics; an explicit list is projected from chapter rules. */
    val inversionLockedSlotIds: List<WorkspaceSlotId>? = null,
) {
    init {
        require(definitionId.isNotBlank()) { "A customary progression needs a definition id" }
        require(variantId.isNotBlank()) { "A customary progression needs a variant id" }
        require(sourceExerciseId.isNotBlank()) {
            "A customary progression needs a source exercise id"
        }
        require(sourceChapterId.isNotBlank()) {
            "A customary progression needs a source chapter id"
        }
        require(slotIds.isNotEmpty()) { "A customary progression must own at least one slot" }
        require(slotIds.distinct().size == slotIds.size) {
            "A customary progression cannot own the same slot more than once"
        }
        require(inversionLockedSlotIds == null || inversionLockedSlotIds.all(slotIds::contains)) {
            "Inversion-locked customary-progression slots must belong to the instance"
        }
        require(tonalities.isEmpty() || tonalities.size == slotIds.size) {
            "Customary-progression tonalities must align with its member slots"
        }
    }
}

@Serializable
data class WorkspaceVoiceSpec(
    val id: TrackId,
    val order: Int,
    val boundary: WorkspaceVoiceBoundary,
    val lowest: Pitch,
    val highest: Pitch,
    val label: String? = null,
) {
    fun toTheory(): VoiceSpec =
        VoiceSpec(
            id = id,
            order = order,
            boundary = boundary.toTheory(),
            range = VoiceRange(lowest, highest),
            label = label,
        )

    companion object {
        fun fromTheory(spec: VoiceSpec): WorkspaceVoiceSpec =
            WorkspaceVoiceSpec(
                id = spec.id,
                order = spec.order,
                boundary = WorkspaceVoiceBoundary.valueOf(spec.boundary.name),
                lowest = spec.range.lowest,
                highest = spec.range.highest,
                label = spec.label,
            )
    }
}

@Serializable
enum class WorkspaceVoiceBoundary {
    UPPER_OUTER,
    INNER,
    LOWER_OUTER;

    fun toTheory(): VoiceBoundary = VoiceBoundary.valueOf(name)
}

@Serializable
data class WorkspaceChordChoice(
    val pitchClasses: List<Int>,
    val origin: ChordSelectionOriginRef? = null,
    val pinnedInterpretationRef: ChordInterpretationRef? = null,
    /** Null leaves inversion open; otherwise the selected chord member must be the bass. */
    val bassPitchClass: Int? = null,
) {
    init {
        require(pitchClasses.isNotEmpty()) { "A workspace chord choice must contain pitch classes" }
        require(pitchClasses.all { it in 0..11 }) { "Pitch classes must be in 0..11" }
        require(pitchClasses == pitchClasses.distinct().sorted()) {
            "Workspace chord pitch classes must be sorted and unique"
        }
        require(bassPitchClass == null || bassPitchClass in pitchClasses) {
            "A fixed workspace bass must be a member of the chord"
        }
    }

    companion object {
        fun of(
            pitchClasses: Collection<Int>,
            origin: ChordSelectionOriginRef? = null,
            pinnedInterpretationRef: ChordInterpretationRef? = null,
            bassPitchClass: Int? = null,
        ): WorkspaceChordChoice = WorkspaceChordChoice(
            pitchClasses = pitchClasses.distinct().sorted(),
            origin = origin,
            pinnedInterpretationRef = pinnedInterpretationRef,
            bassPitchClass = bassPitchClass,
        )
    }
}

@Serializable
data class WorkspaceHarmonySlot(
    val id: WorkspaceSlotId,
    /** Linear score time in whole-note units. Projection to measures uses the workspace meter. */
    val onset: Fraction,
    val duration: Fraction,
    /**
     * Schema v1-v3 migration input. New edits persist [chordInterpretationRef]; unresolved legacy
     * symbols remain here so the UI can show a diagnostic instead of choosing an arbitrary route.
     */
    val chordIdentity: String? = null,
    val chordInterpretationRef: ChordInterpretationRef? = null,
    /** Schema v5 source of truth. The two fields above are legacy decode-only inputs. */
    val chordChoice: WorkspaceChordChoice? = null,
    /** The active tonal reading. Other overlapping layouts remain available as alternate readings. */
    val tonalLayoutId: WorkspaceTonalLayoutId? = null,
    /** Schema v8 chord-owned tonal truth. Non-null values outrank editable tonal layouts. */
    val tonality: WorkspaceChordTonality? = null,
    /** A visual/user-intent marker only; it does not add harmonic constraints. */
    val isPivotChord: Boolean = false,
) {
    init {
        require(!onset.isNegative) { "A harmony slot cannot start before the score" }
        require(duration.isPositive) { "A harmony slot must have a positive duration" }
        require(chordInterpretationRef == null || chordIdentity == null) {
            "A harmony slot cannot contain both an exact interpretation and a legacy symbol"
        }
        require(chordChoice == null || (chordIdentity == null && chordInterpretationRef == null)) {
            "A v5 chord choice cannot coexist with legacy chord fields"
        }
    }
}

@Serializable
data class WorkspaceNote(
    val id: WorkspaceNoteId,
    val voiceId: TrackId,
    val onset: Fraction,
    val duration: Fraction,
    val pitch: Pitch,
) {
    init {
        require(!onset.isNegative) { "A workspace note cannot start before the score" }
        require(duration.isPositive) { "A workspace note must have a positive duration" }
    }
}

@Serializable
enum class VoiceAssignmentSource {
    AUTOMATIC,
    MANUAL,
}

@Serializable
data class WorkspacePatternChoice(
    val requirementId: String,
    val patternId: String,
    val order: Int,
) {
    init {
        require(requirementId.isNotBlank()) { "A pattern choice needs a requirement id" }
        require(patternId.isNotBlank()) { "A pattern choice needs a pattern id" }
        require(order >= 0) { "A pattern-choice order cannot be negative" }
    }
}

@Serializable
data class HarmonyWorkspaceState(
    val voices: List<WorkspaceVoiceSpec>,
    val slots: List<WorkspaceHarmonySlot>,
    @Deprecated("Legacy decode only; editable notes live in RuntimeScore")
    val notes: List<WorkspaceNote> = emptyList(),
    /**
     * Legacy schema input only. New editing flows do not write assignment provenance because
     * notation lanes are freely editable and are not analytical voice identities.
     */
    val voiceAssignmentSources: Map<EventId, VoiceAssignmentSource> = emptyMap(),
    val patternChoices: List<WorkspacePatternChoice> = emptyList(),
    val tonalLayouts: List<WorkspaceTonalLayout> = emptyList(),
    val idiomInstances: List<WorkspaceIdiomInstance> = emptyList(),
) {
    init {
        require(voices.isNotEmpty()) { "A free-practice workspace must contain voices" }
        require(voices.map { it.id }.toSet().size == voices.size) {
            "Free-practice voice ids must be unique"
        }
        require(slots.isNotEmpty()) { "A free-practice workspace must retain one harmony slot" }
        require(slots.map { it.id }.toSet().size == slots.size) {
            "Harmony slot ids must be unique"
        }
        require(slots.zipWithNext().all { (a, b) -> a.onset < b.onset }) {
            "Harmony slots must be ordered by onset"
        }
        require(slots.zipWithNext().all { (a, b) -> a.onset + a.duration <= b.onset }) {
            "Harmony slots must not overlap"
        }
        val voiceIds = voices.mapTo(hashSetOf()) { it.id }
        require(notes.all { it.voiceId in voiceIds }) {
            "Every legacy workspace note must reference an existing voice"
        }
        require(tonalLayouts.map { it.id }.toSet().size == tonalLayouts.size) {
            "Tonal-layout ids must be unique"
        }
        require(tonalLayouts.count { it.isBaseline } <= 1) {
            "A workspace can contain only one initial tonal-layout baseline"
        }
        val tonalLayoutIds = tonalLayouts.mapTo(hashSetOf()) { it.id }
        require(slots.all { it.tonalLayoutId == null || it.tonalLayoutId in tonalLayoutIds }) {
            "Every selected tonal reading must reference an existing tonal layout"
        }
        require(idiomInstances.map { it.id }.toSet().size == idiomInstances.size) {
            "Customary-progression ids must be unique"
        }
        val slotsById = slots.associateBy { it.id }
        idiomInstances.forEach { instance ->
            require(instance.tonalLayoutId == null || instance.tonalLayoutId in tonalLayoutIds) {
                "A customary progression must reference an existing tonal layout"
            }
            require(instance.slotIds.all(slotsById::containsKey)) {
                "A customary progression must reference existing harmony slots"
            }
        }
    }

    fun idiomInstancesForSlot(slotId: WorkspaceSlotId): List<WorkspaceIdiomInstance> =
        idiomInstances.filter { slotId in it.slotIds }

    fun isIdiomSlot(slotId: WorkspaceSlotId): Boolean =
        idiomInstances.any { slotId in it.slotIds }

    fun isIdiomInversionLocked(slotId: WorkspaceSlotId): Boolean {
        val slot = slots.firstOrNull { it.id == slotId } ?: return false
        return idiomInstancesForSlot(slotId).any { instance ->
            instance.inversionLockedSlotIds?.contains(slotId)
                ?: (slot.chordChoice?.bassPitchClass != null)
        }
    }

    val voicePlan: VoicePlan get() = VoicePlan(voices.map(WorkspaceVoiceSpec::toTheory))

    fun activeTonalLayouts(time: Fraction): List<WorkspaceTonalLayout> =
        tonalLayouts.filter { it.contains(time) }

    fun selectedTonalLayout(slot: WorkspaceHarmonySlot): WorkspaceTonalLayout? =
        slot.tonalLayoutId
            ?.let { id -> tonalLayouts.firstOrNull { it.id == id } }
            ?.takeIf { it.contains(slot.onset) }
            ?: activeTonalLayouts(slot.onset).firstOrNull()

    /** Tonal contexts used by harmonic business logic; chord-owned readings have priority. */
    fun harmonicTonalReadings(slot: WorkspaceHarmonySlot): List<WorkspaceChordTonalReading> =
        slot.tonality?.readings ?: listOfNotNull(
            selectedTonalLayout(slot)?.let { WorkspaceChordTonalReading.of(it.key) }
        )

    /** The key inherited by a following ordinary chord or customary progression. */
    fun continuationKey(slot: WorkspaceHarmonySlot): ModulationKey? =
        slot.tonality?.primary?.key ?: selectedTonalLayout(slot)?.key

    /**
     * Coalesces adjacent chord markers by key. These spans are render projections, never editable
     * workspace entities.
     */
    fun derivedTonalSpans(): List<WorkspaceDerivedTonalSpan> {
        val pieces = slots.flatMap { slot ->
            slot.tonality?.readings.orEmpty().map { reading ->
                WorkspaceDerivedTonalSpan(reading.key, slot.onset, slot.onset + slot.duration)
            }
        }
        return pieces.groupBy { it.key }.flatMap { (key, spans) ->
            val ordered = spans.sortedBy { it.start }
            if (ordered.isEmpty()) return@flatMap emptyList()
            val merged = mutableListOf<WorkspaceDerivedTonalSpan>()
            var current = ordered.first()
            ordered.drop(1).forEach { next ->
                current = if (next.start <= current.end) {
                    current.copy(end = maxOf(current.end, next.end))
                } else {
                    merged += current
                    next
                }
            }
            merged += current
            merged.map { it.copy(key = key) }
        }.sortedWith(compareBy(WorkspaceDerivedTonalSpan::start, WorkspaceDerivedTonalSpan::end))
    }
}

enum class InsertChordMode {
    SPLIT_SPAN,
    RIPPLE,
}

enum class DeleteChordMode {
    SYMBOL_ONLY,
    RIPPLE_SPAN,
}

sealed interface HarmonyWorkspaceCommand {
    data class InsertChord(
        val index: Int,
        val mode: InsertChordMode,
        val chordIdentity: String? = null,
        val duration: Fraction = Fraction.QUARTER,
        val splitOffset: Fraction? = null,
        val chordInterpretationRef: ChordInterpretationRef? = null,
        val chordChoice: WorkspaceChordChoice? = null,
        /** Null inherits the surrounding chord context. */
        val tonality: WorkspaceChordTonality? = null,
    ) : HarmonyWorkspaceCommand {
        init {
            require(chordIdentity == null || chordInterpretationRef == null)
            require(chordChoice == null || (chordIdentity == null && chordInterpretationRef == null))
        }
    }

    data class DeleteChord(
        val index: Int,
        val mode: DeleteChordMode,
    ) : HarmonyWorkspaceCommand

    data class ReplaceChord(
        val index: Int,
        val chordIdentity: String? = null,
        val chordInterpretationRef: ChordInterpretationRef? = null,
        val chordChoice: WorkspaceChordChoice? = null,
    ) : HarmonyWorkspaceCommand {
        init {
            require(chordIdentity == null || chordInterpretationRef == null)
            require(chordChoice == null || (chordIdentity == null && chordInterpretationRef == null))
        }
    }

    /** Changes only the bass while preserving a customary progression's chord identity. */
    data class SetChordBass(
        val index: Int,
        val bassPitchClass: Int?,
    ) : HarmonyWorkspaceCommand

    data class InsertChordRange(
        val onset: Fraction,
        val duration: Fraction,
        val chordIdentity: String? = null,
        val chordInterpretationRef: ChordInterpretationRef? = null,
        val chordChoice: WorkspaceChordChoice? = null,
        /** Null inherits the preceding/current chord context. */
        val tonality: WorkspaceChordTonality? = null,
    ) : HarmonyWorkspaceCommand {
        init {
            require(chordIdentity == null || chordInterpretationRef == null)
            require(chordChoice == null || (chordIdentity == null && chordInterpretationRef == null))
        }
    }

    data class PlaceChordRange(
        val index: Int,
        val onset: Fraction,
        val duration: Fraction,
    ) : HarmonyWorkspaceCommand

    data class MoveSharedBoundary(
        val leftIndex: Int,
        val boundary: Fraction,
    ) : HarmonyWorkspaceCommand

    data class TranslateChordRange(
        val index: Int,
        val delta: Fraction,
        val includeFollowing: Boolean = false,
    ) : HarmonyWorkspaceCommand

    data class MoveBoundaryWithFollowing(
        val leftIndex: Int,
        val boundary: Fraction,
    ) : HarmonyWorkspaceCommand

    data class RemoveChordRange(
        val index: Int,
    ) : HarmonyWorkspaceCommand

    data class InsertTonalLayout(
        val key: ModulationKey,
        val start: Fraction,
        val end: Fraction? = null,
        /**
         * When present, active layouts that began before [start] are shortened to this point.
         * Keeping this later than [start] lets the old and new readings overlap on the pivot chord.
         */
        val terminatePreviousAt: Fraction? = null,
    ) : HarmonyWorkspaceCommand

    data class SetTonalLayoutBounds(
        val id: WorkspaceTonalLayoutId,
        val start: Fraction,
        val end: Fraction?,
    ) : HarmonyWorkspaceCommand

    data class SetTonalLayoutKey(
        val id: WorkspaceTonalLayoutId,
        val key: ModulationKey,
    ) : HarmonyWorkspaceCommand

    data class RemoveTonalLayout(
        val id: WorkspaceTonalLayoutId,
    ) : HarmonyWorkspaceCommand

    data class SelectChordTonalLayout(
        val index: Int,
        val tonalLayoutId: WorkspaceTonalLayoutId,
    ) : HarmonyWorkspaceCommand

    data class SetPivotChord(
        val index: Int,
        val selected: Boolean,
    ) : HarmonyWorkspaceCommand

    data class SetChordTonality(
        val index: Int,
        val tonality: WorkspaceChordTonality?,
    ) : HarmonyWorkspaceCommand

    data class InsertIdiom(
        val onset: Fraction,
        val definitionId: String,
        val variantId: String,
        val sourceExerciseId: String,
        val sourceChapterId: String,
        val tonalLayoutId: WorkspaceTonalLayoutId? = null,
        val chordIdentities: List<String>,
        val durations: List<Fraction>,
        val parameters: Map<String, String> = emptyMap(),
        val chordInterpretationRefs: List<ChordInterpretationRef> = emptyList(),
        val chordChoices: List<WorkspaceChordChoice> = emptyList(),
        /** Chord tonalities aligned with [durations]. Empty preserves schema v7 behavior. */
        val tonalities: List<WorkspaceChordTonality> = emptyList(),
        val fixedInversionStepIndices: Set<Int>? = null,
    ) : HarmonyWorkspaceCommand

    data class RemoveIdiom(
        val id: WorkspaceIdiomInstanceId,
    ) : HarmonyWorkspaceCommand

    data class ReplaceIdiom(
        val id: WorkspaceIdiomInstanceId,
        val definitionId: String? = null,
        val sourceExerciseId: String? = null,
        val sourceChapterId: String? = null,
        val tonalLayoutId: WorkspaceTonalLayoutId? = null,
        val variantId: String,
        val chordIdentities: List<String>,
        val durations: List<Fraction>,
        val parameters: Map<String, String> = emptyMap(),
        val chordInterpretationRefs: List<ChordInterpretationRef> = emptyList(),
        val chordChoices: List<WorkspaceChordChoice> = emptyList(),
        /** Chord tonalities aligned with [durations]. Empty preserves the existing instance data. */
        val tonalities: List<WorkspaceChordTonality> = emptyList(),
        val fixedInversionStepIndices: Set<Int>? = null,
        /**
         * Onset of the rightmost harmony slot explicitly edited by the user. When replacement is shorter,
         * an unedited generated tail may be removed, while material at or after this marker is
         * preserved. This interaction marker is intentionally not persisted in the workspace.
         */
        val lastUserEditedOnset: Fraction? = null,
    ) : HarmonyWorkspaceCommand
}

data class HarmonyWorkspaceEditResult(
    val state: HarmonyWorkspaceState,
    val errorMessage: String? = null,
) {
    val succeeded: Boolean get() = errorMessage == null
}

object HarmonyWorkspaceEditor {
    fun apply(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand,
        onError: (String) -> Unit = {},
    ): HarmonyWorkspaceState {
        val result = applyResult(state, command)
        result.errorMessage?.let(onError)
        return result.state
    }

    fun applyResult(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand,
    ): HarmonyWorkspaceEditResult =
        try {
            HarmonyWorkspaceEditResult(reduce(state, command))
        } catch (error: IllegalArgumentException) {
            HarmonyWorkspaceEditResult(
                state = state,
                errorMessage = error.message ?: "The free-practice edit is invalid",
            )
        } catch (error: IllegalStateException) {
            HarmonyWorkspaceEditResult(
                state = state,
                errorMessage = error.message ?: "The free-practice edit could not be completed",
            )
        }

    private fun reduce(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand,
    ): HarmonyWorkspaceState =
        when (command) {
            is HarmonyWorkspaceCommand.InsertChord -> insert(state, command)
            is HarmonyWorkspaceCommand.DeleteChord -> delete(state, command)
            is HarmonyWorkspaceCommand.ReplaceChord -> {
                require(command.index in state.slots.indices) {
                    "The selected harmony slot no longer exists"
                }
                val slot = state.slots[command.index]
                requireSlotEditable(state, slot)
                val effectiveKey = slot.tonality?.primary?.key
                    ?: state.selectedTonalLayout(slot)?.key
                command.chordChoice?.let {
                    requireChoiceMatches(it, effectiveKey)
                }
                val updatedTonality = slot.tonality?.let { existing ->
                    WorkspaceChordTonality(
                        primary = WorkspaceChordTonalReading.of(
                            key = existing.primary.key,
                            interpretationRef = command.chordChoice?.pinnedInterpretationRef,
                        )
                    )
                }
                state.copy(
                    slots = state.slots.updated(
                        command.index,
                        slot.copy(
                            chordIdentity = command.chordIdentity,
                            chordInterpretationRef = command.chordInterpretationRef,
                            chordChoice = command.chordChoice,
                            tonality = updatedTonality,
                        ),
                    )
                )
            }
            is HarmonyWorkspaceCommand.SetChordBass -> setChordBass(state, command)
            is HarmonyWorkspaceCommand.InsertChordRange -> insertRange(state, command)
            is HarmonyWorkspaceCommand.PlaceChordRange -> placeRange(state, command)
            is HarmonyWorkspaceCommand.MoveSharedBoundary -> moveSharedBoundary(state, command)
            is HarmonyWorkspaceCommand.TranslateChordRange -> translateRange(state, command)
            is HarmonyWorkspaceCommand.MoveBoundaryWithFollowing ->
                moveBoundaryWithFollowing(state, command)
            is HarmonyWorkspaceCommand.RemoveChordRange -> removeRange(state, command)
            is HarmonyWorkspaceCommand.InsertTonalLayout -> insertTonalLayout(state, command)
            is HarmonyWorkspaceCommand.SetTonalLayoutBounds -> setTonalLayoutBounds(state, command)
            is HarmonyWorkspaceCommand.SetTonalLayoutKey -> setTonalLayoutKey(state, command)
            is HarmonyWorkspaceCommand.RemoveTonalLayout -> removeTonalLayout(state, command)
            is HarmonyWorkspaceCommand.SelectChordTonalLayout ->
                selectChordTonalLayout(state, command)
            is HarmonyWorkspaceCommand.SetPivotChord -> setPivotChord(state, command)
            is HarmonyWorkspaceCommand.SetChordTonality -> setChordTonality(state, command)
            is HarmonyWorkspaceCommand.InsertIdiom -> insertIdiom(state, command)
            is HarmonyWorkspaceCommand.RemoveIdiom -> removeIdiom(state, command.id)
            is HarmonyWorkspaceCommand.ReplaceIdiom -> replaceIdiom(state, command)
        }

    private fun setChordBass(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SetChordBass,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The selected harmony slot no longer exists"
        }
        val slot = state.slots[command.index]
        require(!state.isIdiomInversionLocked(slot.id)) {
            "This customary-progression inversion is fixed by its source rule"
        }
        val choice = slot.chordChoice ?: error("The selected chord has no editable bass")
        require(command.bassPitchClass == null || command.bassPitchClass in choice.pitchClasses) {
            "The selected bass must be a member of the chord"
        }
        return state.copy(
            slots = state.slots.updated(
                command.index,
                slot.copy(chordChoice = choice.copy(bassPitchClass = command.bassPitchClass)),
            )
        )
    }

    private fun insert(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.InsertChord,
    ): HarmonyWorkspaceState {
        require(command.index in 0..state.slots.size) {
            "The requested insertion position is outside the harmony timeline"
        }
        val validationOnset = state.slots.getOrNull(command.index)?.onset
            ?: state.slots.last().let { it.onset + it.duration }
        command.chordChoice?.let { choice ->
            val tonality = command.tonality
            if (tonality != null) {
                requireTonalityMatchesChoice(tonality, choice)
            } else {
                requireChoiceMatches(choice, state.activeTonalLayouts(validationOnset).firstOrNull()?.key)
            }
        }
        return when (command.mode) {
            InsertChordMode.SPLIT_SPAN -> {
                require(command.index in state.slots.indices) {
                    "Split insertion must target an existing span"
                }
                val current = state.slots[command.index]
                requireSlotEditable(state, current)
                val offset = command.splitOffset ?: current.duration / 2
                require(offset.isPositive && offset < current.duration) {
                    "The split point must remain inside the selected harmony slot"
                }
                val first = current.copy(duration = offset)
                val inserted = WorkspaceHarmonySlot(
                    id = nextSlotId(state),
                    onset = current.onset + offset,
                    duration = current.duration - offset,
                    chordIdentity = command.chordIdentity,
                    chordInterpretationRef = command.chordInterpretationRef,
                    chordChoice = command.chordChoice,
                    tonalLayoutId = current.tonalLayoutId,
                    tonality = command.tonality ?: inheritedTonality(
                        source = current.tonality,
                        choice = command.chordChoice,
                    ),
                )
                state.copy(
                    slots = state.slots.take(command.index) +
                        first + inserted + state.slots.drop(command.index + 1)
                )
            }
            InsertChordMode.RIPPLE -> {
                require(command.duration.isPositive) {
                    "A harmony slot must have a positive duration"
                }
                val onset = state.slots.getOrNull(command.index)?.onset
                    ?: state.slots.last().let { it.onset + it.duration }
                val inserted = WorkspaceHarmonySlot(
                    id = nextSlotId(state),
                    onset = onset,
                    duration = command.duration,
                    chordIdentity = command.chordIdentity,
                    chordInterpretationRef = command.chordInterpretationRef,
                    chordChoice = command.chordChoice,
                    tonalLayoutId = state.activeTonalLayouts(onset).firstOrNull()?.id,
                    tonality = command.tonality ?: inheritedTonalityAt(
                        state = state,
                        onset = onset,
                        choice = command.chordChoice,
                    ),
                )
                state.copy(
                    slots = state.slots.map {
                        if (it.onset >= onset) it.copy(onset = it.onset + command.duration) else it
                    }.toMutableList().also { it.add(command.index, inserted) },
                    notes = state.notes.map {
                        if (it.onset >= onset) it.copy(onset = it.onset + command.duration) else it
                    },
                    tonalLayouts = shiftTonalLayouts(
                        state.tonalLayouts,
                        pivot = onset,
                        delta = command.duration,
                    ),
                )
            }
        }
    }

    private fun delete(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.DeleteChord,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to delete no longer exists"
        }
        val removed = state.slots[command.index]
        requireSlotEditable(state, removed)
        return when (command.mode) {
            DeleteChordMode.SYMBOL_ONLY -> state.copy(
                slots = state.slots.updated(
                    command.index,
                    removed.copy(chordIdentity = null, chordInterpretationRef = null, chordChoice = null),
                )
            )
            DeleteChordMode.RIPPLE_SPAN -> {
                require(state.slots.size > 1) { "A workspace must retain at least one slot" }
                val end = removed.onset + removed.duration
                val layouts = compressTonalLayouts(
                    state.tonalLayouts,
                    removed.onset,
                    end,
                )
                val retainedLayoutIds = layouts.mapTo(hashSetOf()) { it.id }
                val fallback = layouts.firstOrNull { it.isBaseline }?.id
                state.copy(
                    slots = state.slots
                        .filterIndexed { index, _ -> index != command.index }
                        .map {
                            val shifted = if (it.onset >= end) {
                                it.copy(onset = it.onset - removed.duration)
                            } else {
                                it
                            }
                            if (shifted.tonalLayoutId in retainedLayoutIds) {
                                shifted
                            } else {
                                shifted.copy(tonalLayoutId = fallback)
                            }
                        },
                    notes = state.notes
                        .filterNot { note -> note.onset >= removed.onset && note.onset < end }
                        .map {
                            if (it.onset >= end) it.copy(onset = it.onset - removed.duration) else it
                        },
                    tonalLayouts = layouts,
                )
            }
        }
    }

    private fun insertRange(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.InsertChordRange,
    ): HarmonyWorkspaceState {
        require(!command.onset.isNegative) {
            "A harmony range cannot start before the score"
        }
        require(command.duration.isPositive) {
            "A harmony range must have a positive duration"
        }
        command.chordChoice?.let { choice ->
            val tonality = command.tonality
            if (tonality != null) {
                requireTonalityMatchesChoice(tonality, choice)
            } else {
                requireChoiceMatches(choice, state.activeTonalLayouts(command.onset).firstOrNull()?.key)
            }
        }
        val end = command.onset + command.duration
        val overlap = state.slots.firstOrNull { slot ->
            slot.onset < end && command.onset < slot.onset + slot.duration
        }
        require(overlap == null) {
            "Inserted harmony range ${command.onset}..$end overlaps " +
                "${overlap?.id?.value} ${overlap?.onset}..${overlap?.let { it.onset + it.duration }}"
        }
        val inserted = WorkspaceHarmonySlot(
            id = nextSlotId(state),
            onset = command.onset,
            duration = command.duration,
            chordIdentity = command.chordIdentity,
            chordInterpretationRef = command.chordInterpretationRef,
            chordChoice = command.chordChoice,
            tonalLayoutId = state.activeTonalLayouts(command.onset).firstOrNull()?.id,
            tonality = command.tonality ?: inheritedTonalityAt(
                state = state,
                onset = command.onset,
                choice = command.chordChoice,
            ),
        )
        return state.copy(slots = (state.slots + inserted).sortedBy(WorkspaceHarmonySlot::onset))
    }

    private fun placeRange(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.PlaceChordRange,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to move no longer exists"
        }
        require(!command.onset.isNegative) {
            "A harmony range cannot start before the score"
        }
        require(command.duration.isPositive) {
            "A harmony range must have a positive duration"
        }
        requireSlotEditable(state, state.slots[command.index])
        val end = command.onset + command.duration
        require(
            state.slots.none { slot ->
                state.isIdiomSlot(slot.id) &&
                    slot.onset < end &&
                    command.onset < slot.onset + slot.duration
            }
        ) { "Customary progression slots can only be adjusted from the plan panel" }
        val moving = state.slots[command.index].copy(
            onset = command.onset,
            duration = command.duration,
        )
        return state.copy(
            slots = placeOverExisting(
                slots = state.slots.filterIndexed { index, _ -> index != command.index },
                placed = moving,
            )
        )
    }

    private fun moveSharedBoundary(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.MoveSharedBoundary,
    ): HarmonyWorkspaceState {
        require(command.leftIndex in 0 until state.slots.lastIndex) {
            "The shared harmony boundary no longer exists"
        }
        val left = state.slots[command.leftIndex]
        val right = state.slots[command.leftIndex + 1]
        requireSlotEditable(state, left)
        requireSlotEditable(state, right)
        require(left.onset + left.duration == right.onset) {
            "A shared boundary requires adjacent harmony ranges"
        }
        val rightEnd = right.onset + right.duration
        require(command.boundary > left.onset && command.boundary < rightEnd) {
            "The shared boundary must leave a positive duration on both sides"
        }
        return state.copy(
            slots = state.slots
                .updated(
                    command.leftIndex,
                    left.copy(duration = command.boundary - left.onset),
                )
                .updated(
                    command.leftIndex + 1,
                    right.copy(
                        onset = command.boundary,
                        duration = rightEnd - command.boundary,
                    ),
                )
        )
    }

    private fun translateRange(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.TranslateChordRange,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to move no longer exists"
        }
        requireSlotEditable(state, state.slots[command.index])
        if (command.delta == Fraction.ZERO) return state
        if (!command.includeFollowing) {
            val slot = state.slots[command.index]
            val onset = slot.onset + command.delta
            require(!onset.isNegative) {
                "A harmony range cannot be moved before the score"
            }
            return placeRange(
                state,
                HarmonyWorkspaceCommand.PlaceChordRange(command.index, onset, slot.duration),
            )
        }

        val shifted = state.slots.drop(command.index).map { slot ->
            val onset = slot.onset + command.delta
            require(!onset.isNegative) {
                "The selected harmony group cannot be moved before the score"
            }
            slot.copy(onset = onset)
        }
        val first = shifted.first()
        val earlier = state.slots.take(command.index).flatMap { slot ->
            subtractRange(slot, first.onset, first.onset + first.duration)
        }
        return state.copy(
            slots = (earlier + shifted).sortedBy(WorkspaceHarmonySlot::onset),
            tonalLayouts = shiftTonalLayouts(
                state.tonalLayouts,
                pivot = state.slots[command.index].onset,
                delta = command.delta,
            ),
        )
    }

    private fun moveBoundaryWithFollowing(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.MoveBoundaryWithFollowing,
    ): HarmonyWorkspaceState {
        require(command.leftIndex in state.slots.indices) {
            "The harmony boundary to move no longer exists"
        }
        val left = state.slots[command.leftIndex]
        state.slots.drop(command.leftIndex).forEach { requireSlotEditable(state, it) }
        require(command.boundary > left.onset) {
            "The moved boundary must leave a positive duration"
        }
        val oldBoundary = left.onset + left.duration
        val delta = command.boundary - oldBoundary
        return state.copy(
            slots = state.slots.mapIndexed { index, slot ->
                when {
                    index == command.leftIndex ->
                        slot.copy(duration = command.boundary - slot.onset)
                    index > command.leftIndex ->
                        slot.copy(onset = slot.onset + delta)
                    else -> slot
                }
            },
            tonalLayouts = shiftTonalLayouts(state.tonalLayouts, oldBoundary, delta),
        )
    }

    private fun removeRange(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.RemoveChordRange,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to remove no longer exists"
        }
        requireSlotEditable(state, state.slots[command.index])
        return if (state.slots.size == 1) {
            state.copy(
                slots = listOf(
                    state.slots.single().copy(
                        chordIdentity = null,
                        chordInterpretationRef = null,
                        chordChoice = null,
                    )
                )
            )
        } else {
            state.copy(
                slots = state.slots.filterIndexed { index, _ -> index != command.index }
            )
        }
    }

    private fun placeOverExisting(
        slots: List<WorkspaceHarmonySlot>,
        placed: WorkspaceHarmonySlot,
    ): List<WorkspaceHarmonySlot> {
        val end = placed.onset + placed.duration
        return (slots.flatMap { subtractRange(it, placed.onset, end) } + placed)
            .sortedBy(WorkspaceHarmonySlot::onset)
    }

    /**
     * Harmony ranges keep one stable id. If a placed range cuts through the middle of another
     * range, retain the larger remainder (the left remainder wins ties).
     */
    private fun subtractRange(
        slot: WorkspaceHarmonySlot,
        cutStart: Fraction,
        cutEnd: Fraction,
    ): List<WorkspaceHarmonySlot> {
        val slotEnd = slot.onset + slot.duration
        if (slotEnd <= cutStart || slot.onset >= cutEnd) return listOf(slot)
        val leftDuration = cutStart - slot.onset
        val rightDuration = slotEnd - cutEnd
        return when {
            leftDuration.isPositive && leftDuration >= rightDuration ->
                listOf(slot.copy(duration = leftDuration))
            rightDuration.isPositive ->
                listOf(slot.copy(onset = cutEnd, duration = rightDuration))
            else -> emptyList()
        }
    }

    private fun insertTonalLayout(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.InsertTonalLayout,
    ): HarmonyWorkspaceState {
        require(!command.start.isNegative) {
            "A tonal layout cannot start before the score"
        }
        require(command.end == null || command.end > command.start) {
            "A tonal layout must have a positive duration"
        }
        require(command.terminatePreviousAt == null || command.terminatePreviousAt > command.start) {
            "A previous tonal layout must end after the new layout starts"
        }
        val id = nextTonalLayoutId(state)
        val shortenedLayouts = state.tonalLayouts.map { layout ->
            val terminateAt = command.terminatePreviousAt
            if (
                terminateAt != null &&
                layout.start < command.start &&
                layout.contains(command.start) &&
                (layout.end == null || layout.end > terminateAt)
            ) {
                layout.copy(end = terminateAt)
            } else {
                layout
            }
        }
        val newLayout = WorkspaceTonalLayout(
            id = id,
            fifths = command.key.fifths,
            mode = WorkspaceKeyMode.fromTheory(command.key.mode),
            start = command.start,
            end = command.end,
        )
        val slotsFollowingNewLayout = command.terminatePreviousAt?.let { terminateAt ->
            state.slots.map { slot ->
                val inheritedManualKey = state.selectedTonalLayout(slot)?.key
                val redundantManualReading = slot.tonality?.readings?.singleOrNull()?.key == inheritedManualKey
                if (
                    !state.isIdiomSlot(slot.id) &&
                    slot.onset >= terminateAt &&
                    newLayout.contains(slot.onset) &&
                    redundantManualReading
                ) {
                    slot.copy(tonality = null)
                } else {
                    slot
                }
            }
        } ?: state.slots
        return state.copy(
            slots = slotsFollowingNewLayout,
            tonalLayouts = shortenedLayouts + newLayout,
        )
    }

    private fun setTonalLayoutBounds(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SetTonalLayoutBounds,
    ): HarmonyWorkspaceState {
        val index = state.tonalLayouts.indexOfFirst { it.id == command.id }
        require(index >= 0) { "Unknown tonal layout ${command.id.value}" }
        val current = state.tonalLayouts[index]
        require(!current.isBaseline || command.start == Fraction.ZERO) {
            "The initial tonal-layout baseline must remain anchored at the workspace origin"
        }
        val updated = current.copy(start = command.start, end = command.end)
        val ownedIdiomSlots = state.slots.filter {
            it.tonalLayoutId == current.id && state.isIdiomSlot(it.id)
        }
        require(ownedIdiomSlots.all { updated.covers(it) }) {
            "A tonal layout cannot move away from a customary progression that uses it"
        }
        val updatedLayouts = state.tonalLayouts.updated(index, updated)
        return state.copy(
            tonalLayouts = updatedLayouts,
            slots = state.slots.map { slot ->
                if (
                    slot.tonalLayoutId == current.id &&
                    !state.isIdiomSlot(slot.id) &&
                    !updated.contains(slot.onset)
                ) {
                    slot.copy(
                        tonalLayoutId = updatedLayouts.firstOrNull {
                            it.id != current.id && it.contains(slot.onset)
                        }?.id
                    )
                } else {
                    slot
                }
            },
        )
    }

    private fun setTonalLayoutKey(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SetTonalLayoutKey,
    ): HarmonyWorkspaceState {
        val index = state.tonalLayouts.indexOfFirst { it.id == command.id }
        require(index >= 0) { "Unknown tonal layout ${command.id.value}" }
        val current = state.tonalLayouts[index]
        return state.copy(
            tonalLayouts = state.tonalLayouts.updated(
                index,
                current.copy(
                    fifths = command.key.fifths,
                    mode = WorkspaceKeyMode.fromTheory(command.key.mode),
                ),
            )
        )
    }

    private fun removeTonalLayout(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.RemoveTonalLayout,
    ): HarmonyWorkspaceState {
        val layout = state.tonalLayouts.firstOrNull { it.id == command.id }
            ?: error("Unknown tonal layout ${command.id.value}")
        require(!layout.isBaseline) { "The initial tonal-layout baseline cannot be removed" }
        require(state.idiomInstances.none { it.tonalLayoutId == command.id }) {
            "Remove customary progressions on this tonal layout first"
        }
        val retainedLayouts = state.tonalLayouts.filterNot { it.id == command.id }
        return state.copy(
            tonalLayouts = retainedLayouts,
            slots = state.slots.map { slot ->
                if (slot.tonalLayoutId == command.id) {
                    slot.copy(
                        tonalLayoutId = retainedLayouts.firstOrNull {
                            it.contains(slot.onset)
                        }?.id
                    )
                } else {
                    slot
                }
            },
        )
    }

    private fun selectChordTonalLayout(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SelectChordTonalLayout,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to reinterpret no longer exists"
        }
        val slot = state.slots[command.index]
        val activeLayouts = state.activeTonalLayouts(slot.onset)
        require(activeLayouts.any { it.id == command.tonalLayoutId }) {
            "Selected tonal layout does not cover this chord"
        }
        val redundantManualTonality = slot.tonality?.readings?.singleOrNull()?.key
            ?.takeIf { key -> activeLayouts.any { it.key == key } }
        return state.copy(
            slots = state.slots.updated(
                command.index,
                slot.copy(
                    tonalLayoutId = command.tonalLayoutId,
                    tonality = slot.tonality.takeIf { redundantManualTonality == null },
                ),
            )
        )
    }

    private fun setPivotChord(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SetPivotChord,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The harmony slot to mark no longer exists"
        }
        val slot = state.slots[command.index]
        return state.copy(
            slots = state.slots.updated(
                command.index,
                slot.copy(isPivotChord = command.selected),
            )
        )
    }

    private fun setChordTonality(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.SetChordTonality,
    ): HarmonyWorkspaceState {
        require(command.index in state.slots.indices) {
            "The selected harmony slot no longer exists"
        }
        val slot = state.slots[command.index]
        requireSlotEditable(state, slot)
        val targetKey = command.tonality?.primary?.key
            ?: state.selectedTonalLayout(slot)?.key
        val matchingChoice = slot.chordChoice?.let { choice ->
            targetKey?.let { key ->
                ChordSelectionCatalog.choices(key).firstOrNull { catalogChoice ->
                    catalogChoice.pitchClasses == choice.pitchClasses.toSet()
                }
            }
        }
        val chordCompatible = slot.chordChoice == null || targetKey == null || matchingChoice != null
        if (chordCompatible) {
            slot.chordChoice?.let { choice ->
                command.tonality?.let { requireTonalityMatchesChoice(it, choice) }
            }
        }
        val updatedChoice = slot.chordChoice
            ?.takeIf { chordCompatible }
            ?.copy(
                pinnedInterpretationRef = command.tonality?.primary?.interpretationRef,
            )
        return state.copy(
            slots = state.slots.updated(
                command.index,
                slot.copy(
                    chordIdentity = slot.chordIdentity.takeIf { chordCompatible },
                    chordInterpretationRef = slot.chordInterpretationRef.takeIf { chordCompatible },
                    chordChoice = updatedChoice,
                    tonality = command.tonality,
                    isPivotChord = slot.isPivotChord && chordCompatible,
                ),
            )
        )
    }

    private fun insertIdiom(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.InsertIdiom,
    ): HarmonyWorkspaceState {
        require(command.chordIdentities.isNotEmpty() || command.chordInterpretationRefs.isNotEmpty() || command.chordChoices.isNotEmpty()) {
            "A customary progression must contain at least one chord"
        }
        val exact = command.chordInterpretationRefs
        val choices = command.chordChoices
        require(
            listOf(command.chordIdentities, exact, choices).count { it.isNotEmpty() } == 1 &&
                listOf(command.chordIdentities.size, exact.size, choices.size).max() == command.durations.size
        ) {
            "Each customary-progression chord must have one duration"
        }
        require(command.durations.all { it.isPositive }) {
            "Customary-progression durations must be positive"
        }
        require(
            command.fixedInversionStepIndices == null ||
                command.fixedInversionStepIndices.all(command.durations.indices::contains)
        ) {
            "Fixed inversion steps must belong to the customary progression"
        }
        require(command.tonalities.isEmpty() || command.tonalities.size == command.durations.size) {
            "Each customary-progression step must have one chord tonality"
        }
        val legacyLayout = command.tonalLayoutId?.let { layoutId ->
            state.tonalLayouts.firstOrNull { it.id == layoutId }
                ?: error("The selected tonal layout no longer exists")
        }
        command.chordChoices.forEachIndexed { index, choice ->
            command.tonalities.getOrNull(index)?.let { tonality ->
                requireTonalityMatchesChoice(tonality, choice)
            } ?: requireChoiceMatches(choice, legacyLayout?.key)
        }
        val instanceId = nextIdiomInstanceId(state)
        var onset = command.onset
        val selections = when {
            choices.isNotEmpty() -> choices.map { IdiomSelection(chordChoice = it) }
            exact.isNotEmpty() -> exact.map { IdiomSelection(chordInterpretationRef = it) }
            else -> command.chordIdentities.map { IdiomSelection(chordIdentity = it) }
        }
        val steps = selections.zip(command.durations).mapIndexed { index, (selection, duration) ->
            IdiomTimelineStep(
                onset = onset,
                duration = duration,
                selection = selection,
                tonality = command.tonalities.getOrNull(index),
            ).also { onset += duration }
        }
        legacyLayout?.takeIf { command.tonalities.isEmpty() }?.let { tonalLayout ->
            val tonalLayoutEnd = tonalLayout.end
            require(steps.all { step ->
                tonalLayout.start <= step.onset && (tonalLayoutEnd == null || step.end <= tonalLayoutEnd)
            }) {
                "The selected tonal layout must cover the complete customary progression"
            }
        }
        val rangeEnd = onset
        val normalized = splitSlotsAtBoundaries(
            state,
            steps.flatMapTo(linkedSetOf()) { listOf(it.onset, it.end) },
        )
        val reservedIds = normalized.slots.mapTo(linkedSetOf()) { it.id.value }
        val replacedManualIds = linkedSetOf<WorkspaceSlotId>()
        val createdSlots = mutableListOf<WorkspaceHarmonySlot>()
        val instanceSlotIds = mutableListOf<WorkspaceSlotId>()
        val inversionLockedSlotIds = mutableListOf<WorkspaceSlotId>()
        val sharedChoiceUpdates = mutableMapOf<WorkspaceSlotId, WorkspaceChordChoice>()
        val sharedTonalityUpdates = mutableMapOf<WorkspaceSlotId, WorkspaceChordTonality>()
        val instanceTonalities = mutableListOf<WorkspaceChordTonality>()
        steps.forEachIndexed { stepIndex, step ->
            val boundaries = buildSet {
                add(step.onset)
                add(step.end)
                normalized.slots.forEach { slot ->
                    val slotEnd = slot.onset + slot.duration
                    if (slot.onset > step.onset && slot.onset < step.end) add(slot.onset)
                    if (slotEnd > step.onset && slotEnd < step.end) add(slotEnd)
                }
            }.sorted()
            boundaries.zipWithNext().forEach { (partStart, partEnd) ->
                val duration = partEnd - partStart
                val existing = normalized.slots.firstOrNull {
                    it.onset == partStart && it.duration == duration
                }
                if (existing != null && normalized.isIdiomSlot(existing.id)) {
                    val requireBassCompatibility = command.fixedInversionStepIndices == null ||
                        stepIndex in command.fixedInversionStepIndices
                    require(
                        step.selection.matches(existing, requireBassCompatibility)
                    ) {
                        "Customary progressions can share only harmonically identical chord slots"
                    }
                    instanceSlotIds += existing.id
                    step.tonality?.let {
                        sharedTonalityUpdates[existing.id] = it
                        instanceTonalities += it
                    }
                    if (stepIndex in command.fixedInversionStepIndices.orEmpty()) {
                        inversionLockedSlotIds += existing.id
                    }
                    val incomingChoice = step.selection.chordChoice
                    val existingChoice = existing.chordChoice
                    if (incomingChoice?.bassPitchClass != null && existingChoice?.bassPitchClass == null) {
                        sharedChoiceUpdates[existing.id] = requireNotNull(existingChoice).copy(
                            bassPitchClass = incomingChoice.bassPitchClass,
                        )
                    }
                } else {
                    existing?.let { replacedManualIds += it.id }
                    val id = nextSlotId(normalized, reservedIds)
                    reservedIds += id.value
                    createdSlots += WorkspaceHarmonySlot(
                        id = id,
                        onset = partStart,
                        duration = duration,
                        chordIdentity = step.selection.chordIdentity,
                        chordInterpretationRef = step.selection.chordInterpretationRef,
                        chordChoice = step.selection.chordChoice,
                        tonalLayoutId = command.tonalLayoutId,
                        tonality = step.tonality,
                    )
                    instanceSlotIds += id
                    step.tonality?.let { instanceTonalities += it }
                    if (stepIndex in command.fixedInversionStepIndices.orEmpty()) {
                        inversionLockedSlotIds += id
                    }
                }
            }
        }
        val remaining = normalized.slots
            .filterNot { it.id in replacedManualIds }
            .filterNot { slot ->
                !normalized.isIdiomSlot(slot.id) &&
                    slot.onset < rangeEnd && command.onset < slot.onset + slot.duration
            }
        val instance = WorkspaceIdiomInstance(
            id = instanceId,
            definitionId = command.definitionId,
            variantId = command.variantId,
            sourceExerciseId = command.sourceExerciseId,
            sourceChapterId = command.sourceChapterId,
            tonalLayoutId = command.tonalLayoutId,
            slotIds = instanceSlotIds,
            tonalities = if (command.tonalities.isEmpty()) emptyList() else instanceTonalities,
            parameters = command.parameters,
            inversionLockedSlotIds = command.fixedInversionStepIndices?.let {
                inversionLockedSlotIds.distinct()
            },
        )
        return normalized.copy(
            slots = (remaining.map { slot ->
                val choice = sharedChoiceUpdates[slot.id]
                val tonality = sharedTonalityUpdates[slot.id]
                if (choice != null || tonality != null) {
                    slot.copy(
                        chordChoice = choice ?: slot.chordChoice,
                        tonality = tonality ?: slot.tonality,
                    )
                } else {
                    slot
                }
            } + createdSlots).sortedBy(WorkspaceHarmonySlot::onset),
            idiomInstances = normalized.idiomInstances + instance,
        )
    }

    private fun removeIdiom(
        state: HarmonyWorkspaceState,
        id: WorkspaceIdiomInstanceId,
    ): HarmonyWorkspaceState {
        if (state.idiomInstances.none { it.id == id }) {
            error("Unknown customary progression ${id.value}")
        }
        return state.copy(
            idiomInstances = state.idiomInstances.filterNot { it.id == id },
        )
    }

    private fun replaceIdiom(
        state: HarmonyWorkspaceState,
        command: HarmonyWorkspaceCommand.ReplaceIdiom,
    ): HarmonyWorkspaceState {
        val instance = state.idiomInstances.firstOrNull { it.id == command.id }
            ?: error("Unknown customary progression ${command.id.value}")
        require(command.chordIdentities.isNotEmpty() || command.chordInterpretationRefs.isNotEmpty() || command.chordChoices.isNotEmpty()) {
            "A customary progression must contain at least one chord"
        }
        val exact = command.chordInterpretationRefs
        val choices = command.chordChoices
        require(
            listOf(command.chordIdentities, exact, choices).count { it.isNotEmpty() } == 1 &&
                listOf(command.chordIdentities.size, exact.size, choices.size).max() == command.durations.size
        ) {
            "Each customary-progression chord must have one duration"
        }
        require(command.durations.all { it.isPositive }) {
            "Customary-progression durations must be positive"
        }
        require(
            command.fixedInversionStepIndices == null ||
                command.fixedInversionStepIndices.all(command.durations.indices::contains)
        ) {
            "Fixed inversion steps must belong to the customary progression"
        }
        val replacementTonalities = command.tonalities.ifEmpty {
            instance.tonalities.takeIf { it.size == command.durations.size }.orEmpty()
        }
        require(replacementTonalities.isEmpty() || replacementTonalities.size == command.durations.size) {
            "Each customary-progression step must have one chord tonality"
        }
        val replacementLayoutId = command.tonalLayoutId ?: instance.tonalLayoutId
        val legacyKey = replacementLayoutId?.let { id ->
            state.tonalLayouts.firstOrNull { it.id == id }?.key
                ?: error("The selected tonal layout no longer exists")
        }
        command.chordChoices.forEachIndexed { index, choice ->
            replacementTonalities.getOrNull(index)?.let { tonality ->
                requireTonalityMatchesChoice(tonality, choice)
            } ?: requireChoiceMatches(choice, legacyKey)
        }
        val oldSlots = instance.slotIds.map { id -> state.slots.first { it.id == id } }
        val onset = oldSlots.minOf(WorkspaceHarmonySlot::onset)
        val oldEnd = oldSlots.maxOf { it.onset + it.duration }
        val newEnd = onset + command.durations.fold(Fraction.ZERO) { total, duration ->
            total + duration
        }
        val lastUserEditedOnset = command.lastUserEditedOnset
        val base = removeIdiom(state, instance.id)
        val inserted = insertIdiom(
            base,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = onset,
                definitionId = command.definitionId ?: instance.definitionId,
                variantId = command.variantId,
                sourceExerciseId = command.sourceExerciseId ?: instance.sourceExerciseId,
                sourceChapterId = command.sourceChapterId ?: instance.sourceChapterId,
                tonalLayoutId = replacementLayoutId,
                chordIdentities = command.chordIdentities,
                durations = command.durations,
                parameters = command.parameters,
                chordInterpretationRefs = command.chordInterpretationRefs,
                chordChoices = command.chordChoices,
                tonalities = replacementTonalities,
                fixedInversionStepIndices = command.fixedInversionStepIndices,
            ),
        )
        val generated = inserted.idiomInstances.last()
        val relabeled = inserted.copy(
            idiomInstances = inserted.idiomInstances.map { current ->
                if (current.id == generated.id) current.copy(id = instance.id) else current
            },
        )
        if (newEnd >= oldEnd || (lastUserEditedOnset != null && lastUserEditedOnset >= newEnd)) {
            return relabeled
        }
        val referencedSlotIds = relabeled.idiomInstances
            .flatMapTo(hashSetOf()) { it.slotIds }
        val removableTailIds = relabeled.slots.asSequence()
            .filter { slot ->
                slot.onset >= newEnd && slot.onset + slot.duration <= oldEnd
            }
            .map { it.id }
            .filter { it !in referencedSlotIds }
            .toSet()
        return relabeled.copy(
            slots = relabeled.slots.filterNot { it.id in removableTailIds },
        )
    }

    private data class IdiomSelection(
        val chordIdentity: String? = null,
        val chordInterpretationRef: ChordInterpretationRef? = null,
        val chordChoice: WorkspaceChordChoice? = null,
    ) {
        fun matches(
            slot: WorkspaceHarmonySlot,
            requireBassCompatibility: Boolean = true,
        ): Boolean = when {
            chordChoice != null -> slot.chordChoice?.harmonicallyEquivalent(
                chordChoice,
                requireBassCompatibility,
            ) == true
            chordInterpretationRef != null -> slot.chordInterpretationRef == chordInterpretationRef
            else -> slot.chordIdentity == chordIdentity
        }
    }

    private data class IdiomTimelineStep(
        val onset: Fraction,
        val duration: Fraction,
        val selection: IdiomSelection,
        val tonality: WorkspaceChordTonality?,
    ) {
        val end: Fraction get() = onset + duration
    }

    private fun WorkspaceChordChoice.harmonicallyEquivalent(
        other: WorkspaceChordChoice,
        requireBassCompatibility: Boolean,
    ): Boolean =
        pitchClasses == other.pitchClasses &&
            (!requireBassCompatibility || bassPitchClass == null || other.bassPitchClass == null ||
                bassPitchClass == other.bassPitchClass)

    private fun splitSlotsAtBoundaries(
        state: HarmonyWorkspaceState,
        boundaries: Set<Fraction>,
    ): HarmonyWorkspaceState {
        val reserved = state.slots.mapTo(linkedSetOf()) { it.id.value }
        val replacements = linkedMapOf<WorkspaceSlotId, List<WorkspaceSlotId>>()
        val split = state.slots.flatMap { slot ->
            val slotEnd = slot.onset + slot.duration
            val cuts = boundaries.filter { it > slot.onset && it < slotEnd }.sorted()
            if (cuts.isEmpty()) return@flatMap listOf(slot)
            val points = listOf(slot.onset) + cuts + slotEnd
            val parts = points.zipWithNext().mapIndexed { index, (start, end) ->
                val id = if (index == 0) {
                    slot.id
                } else {
                    nextSlotId(state, reserved).also { reserved += it.value }
                }
                slot.copy(id = id, onset = start, duration = end - start)
            }
            replacements[slot.id] = parts.map { it.id }
            parts
        }
        if (replacements.isEmpty()) return state
        return state.copy(
            slots = split.sortedBy(WorkspaceHarmonySlot::onset),
            idiomInstances = state.idiomInstances.map { instance ->
                instance.copy(
                    slotIds = instance.slotIds.flatMap { replacements[it] ?: listOf(it) },
                    tonalities = if (instance.tonalities.isEmpty()) {
                        emptyList()
                    } else {
                        instance.slotIds.zip(instance.tonalities).flatMap { (slotId, tonality) ->
                            (replacements[slotId] ?: listOf(slotId)).map { tonality }
                        }
                    },
                    inversionLockedSlotIds = instance.inversionLockedSlotIds?.flatMap {
                        replacements[it] ?: listOf(it)
                    },
                )
            },
        )
    }

    private fun requireSlotEditable(state: HarmonyWorkspaceState, slot: WorkspaceHarmonySlot) {
        require(!state.isIdiomSlot(slot.id)) {
            "Customary progression slots can only be adjusted from the plan panel"
        }
    }

    private fun requireChoiceMatches(choice: WorkspaceChordChoice, key: ModulationKey?) {
        val pinned = choice.pinnedInterpretationRef ?: return
        require(key != null) { "A pinned chord interpretation requires an active tonal layout" }
        val catalogChoice = ChordSelectionCatalog.choices(key).firstOrNull {
            pinned in it.interpretationRefs
        } ?: error("The pinned chord interpretation is unavailable in the active tonal layout")
        require(catalogChoice.audibleKey == AudibleSonorityKey.from(choice.pitchClasses)) {
            "The pinned chord interpretation does not match the stored audible sonority"
        }
    }

    private fun requireTonalityMatchesChoice(
        tonality: WorkspaceChordTonality,
        choice: WorkspaceChordChoice,
    ) {
        tonality.readings.forEach { reading ->
            val ref = reading.interpretationRef ?: return@forEach
            val catalogChoice = ChordSelectionCatalog.choices(reading.key).firstOrNull {
                ref in it.interpretationRefs
            } ?: error(
                "The chord interpretation is unavailable in ${reading.key.displayName}"
            )
            require(catalogChoice.audibleKey == AudibleSonorityKey.from(choice.pitchClasses)) {
                "The chord tonal interpretation does not match the stored audible sonority"
            }
        }
    }

    private fun inheritedTonalityAt(
        state: HarmonyWorkspaceState,
        onset: Fraction,
        choice: WorkspaceChordChoice?,
    ): WorkspaceChordTonality? {
        val preceding = state.slots
            .filter { it.onset + it.duration <= onset }
            .maxByOrNull { it.onset + it.duration }
        val containing = state.slots.firstOrNull {
            it.onset <= onset && onset < it.onset + it.duration
        }
        return inheritedTonality((preceding ?: containing)?.tonality, choice)
    }

    private fun inheritedTonality(
        source: WorkspaceChordTonality?,
        choice: WorkspaceChordChoice?,
    ): WorkspaceChordTonality? = source?.primary?.key?.let {
        WorkspaceChordTonality(
            primary = WorkspaceChordTonalReading.of(
                key = it,
                interpretationRef = choice?.pinnedInterpretationRef,
            )
        )
    }

    private fun WorkspaceTonalLayout.covers(slot: WorkspaceHarmonySlot): Boolean =
        contains(slot.onset) && (end == null || slot.onset + slot.duration <= end)

    private fun shiftTonalLayouts(
        layouts: List<WorkspaceTonalLayout>,
        pivot: Fraction,
        delta: Fraction,
    ): List<WorkspaceTonalLayout> =
        layouts.map { layout ->
            if (layout.isBaseline) {
                layout
            } else {
                layout.copy(
                    start = if (layout.start >= pivot) layout.start + delta else layout.start,
                    end = layout.end?.let { if (it >= pivot) it + delta else it },
                )
            }
        }

    private fun compressTonalLayouts(
        layouts: List<WorkspaceTonalLayout>,
        cutStart: Fraction,
        cutEnd: Fraction,
    ): List<WorkspaceTonalLayout> {
        val delta = cutEnd - cutStart
        fun compress(time: Fraction): Fraction =
            when {
                time >= cutEnd -> time - delta
                time > cutStart -> cutStart
                else -> time
            }
        return layouts.mapNotNull { layout ->
            if (layout.isBaseline) return@mapNotNull layout
            val start = compress(layout.start)
            val end = layout.end?.let(::compress)
            if (end != null && end <= start) null else layout.copy(start = start, end = end)
        }
    }

    private fun List<WorkspaceHarmonySlot>.withUniqueIds(
        state: HarmonyWorkspaceState,
    ): List<WorkspaceHarmonySlot> {
        val used = state.slots.mapTo(linkedSetOf()) { it.id.value }
        return map { slot ->
            var candidate = slot.id.value
            var suffix = used.size
            while (candidate in used) {
                candidate = "slot-${suffix++}"
            }
            used += candidate
            slot.copy(id = WorkspaceSlotId(candidate))
        }
    }

    private fun nextTonalLayoutId(state: HarmonyWorkspaceState): WorkspaceTonalLayoutId {
        val used = state.tonalLayouts.mapTo(hashSetOf()) { it.id.value }
        var index = state.tonalLayouts.size
        while ("tonal-layout-$index" in used) index++
        return WorkspaceTonalLayoutId("tonal-layout-$index")
    }

    private fun nextIdiomInstanceId(state: HarmonyWorkspaceState): WorkspaceIdiomInstanceId {
        val used = state.idiomInstances.mapTo(hashSetOf()) { it.id.value }
        var index = state.idiomInstances.size
        while ("idiom-$index" in used) index++
        return WorkspaceIdiomInstanceId("idiom-$index")
    }

    private fun nextSlotId(
        state: HarmonyWorkspaceState,
        reserved: Set<String> = emptySet(),
    ): WorkspaceSlotId {
        val used = state.slots.mapTo(hashSetOf()) { it.id.value }
        used += reserved
        var index = state.slots.size
        while ("slot-$index" in used) index++
        return WorkspaceSlotId("slot-$index")
    }
}

enum class WorkspaceCheckTruth {
    SATISFIED,
    VIOLATED,
    UNDETERMINED,
}

data class WorkspacePatternProgress(
    val requirementId: String,
    val patternId: HarmonicPatternId,
    val truth: WorkspaceCheckTruth,
    val matchState: PatternMatchState,
)

object HarmonyWorkspaceProjector {
    /**
     * Missing chord symbols remain UNDETERMINED. A partial prefix is not a violation while future
     * slots can still complete the pattern.
     */
    fun patternProgress(
        state: HarmonyWorkspaceState,
        targetsByIdentity: Map<String, ChordTarget>,
        patternsById: Map<HarmonicPatternId, HarmonicPattern>,
        targetsByInterpretationRef: Map<ChordInterpretationRef, ChordTarget> = emptyMap(),
        effectiveInterpretationRefsBySlotId: Map<WorkspaceSlotId, ChordInterpretationRef> = emptyMap(),
    ): List<WorkspacePatternProgress> =
        state.patternChoices.sortedBy { it.order }.map { choice ->
            val id = HarmonicPatternId(choice.patternId)
            val pattern = patternsById[id] ?: error("Unknown pattern $id")
            val targets = state.slots.mapNotNull { slot ->
                val exactRef = slot.chordChoice?.pinnedInterpretationRef
                    ?: effectiveInterpretationRefsBySlotId[slot.id]
                    ?: slot.chordInterpretationRef
                exactRef?.let(targetsByInterpretationRef::get)
                    ?: slot.chordIdentity?.let(targetsByIdentity::get)
            }
            val missing = state.slots.any {
                it.chordIdentity == null && it.chordInterpretationRef == null &&
                    it.chordChoice?.pinnedInterpretationRef == null &&
                    effectiveInterpretationRefsBySlotId[it.id] == null
            }
            val match = pattern.matcher().stateFor(targets)
            val truth = when {
                match.completion == PatternCompletion.COMPLETE -> WorkspaceCheckTruth.SATISFIED
                missing || match.completion == PatternCompletion.PARTIAL ->
                    WorkspaceCheckTruth.UNDETERMINED
                else -> WorkspaceCheckTruth.VIOLATED
            }
            WorkspacePatternProgress(choice.requirementId, id, truth, match)
        }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> =
    mapIndexed { current, existing -> if (current == index) value else existing }

/**
 * Supplies the v2 initial-key line when opening a legacy workspace. Existing layouts are preserved.
 */
fun HarmonyWorkspaceState.withTonalLayoutBaseline(initialKey: ModulationKey): HarmonyWorkspaceState {
    if (tonalLayouts.isNotEmpty()) return this
    val baselineId = WorkspaceTonalLayoutId("tonal-layout-0")
    return copy(
        slots = slots.map { it.copy(tonalLayoutId = baselineId) },
        tonalLayouts = listOf(
            WorkspaceTonalLayout(
                id = baselineId,
                fifths = initialKey.fifths,
                mode = WorkspaceKeyMode.fromTheory(initialKey.mode),
                start = Fraction.ZERO,
                isBaseline = true,
            )
        ),
    )
}
