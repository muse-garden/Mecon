package com.mecon.core.engine.edit

import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.primitive.Tuplet
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.api.storage.events.TupletSpan

/**
 * Pure, immutable note-insertion / rest-insertion engine operating on a single voice track of a
 * [RuntimeScore]. It implements the editing rules from `docs/data_model/incremental-update.md`
 * (the "音符插入" downstream): clearing an interval before insertion, splitting notes that fully
 * contain the cleared span, chording onto a same-duration note already at the onset, and tying a
 * note that overflows a barline into the next measure.
 *
 * The result is a brand-new [RuntimeScore]; the caller recomputes the computed/render layers and
 * commits it (see `ScoreSession.applyNoteEdit`). Untouched events of the edited voice keep their
 * identity so an incremental recompute could be slotted in later.
 *
 * Coordinate model: positions are compared in **absolute whole-note units** ([EditGeometry.absolute])
 * — the cumulative length of all preceding measures plus the in-measure beat — so overlap math is
 * trivial across varying time signatures. New events are emitted measure-by-measure so onsets stay
 * in the natural `(measure, beat)` form.
 *
 * This object is a thin, API-stable facade: it declares the request/result types and public
 * entry points, delegating every implementation to a feature object in this package —
 * [NoteInsertion], [NoteCopyPaste], [NoteDeletion], [NoteTranspose], [NotePropertyEdits],
 * [VoiceMoveEngine] — with [EditGeometry], [StaffTrackOps], [VoiceSpanEditing] and [TupletSupport]
 * as the shared primitives they're built from.
 */
object NoteEditEngine {
    data class RangeNote(
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val duration: Fraction,
        val pitch: Pitch,
    ) {
        init { require(duration.isPositive) { "Range note duration must be positive" } }
    }

    data class RangeReplacementResult(
        val score: RuntimeScore,
        val editInterval: TimeRange,
        val insertedEventIdsByNoteIndex: Map<Int, List<EventId>>,
    )

    /**
     * Atomically replaces complete monodic material in `[start, end)` for the supplied voices.
     * Notes outside the range keep identity; boundary-crossing events are split by the shared span
     * editor. Each edited voice must cover the full range without gaps or overlaps.
     */
    fun replaceRange(
        runtime: RuntimeScore,
        voiceTrackIds: Set<TrackId>,
        start: TimeCode,
        end: TimeCode,
        notes: List<RangeNote>,
    ): RangeReplacementResult {
        require(voiceTrackIds.isNotEmpty()) { "Range replacement needs at least one voice" }
        val timeMap = ScoreTimeMap.from(runtime)
        val startAbs = timeMap.absolute(start)
        val endAbs = timeMap.absolute(end)
        require(endAbs > startAbs) { "Range replacement must have positive length" }
        require(notes.all { it.voiceTrackId in voiceTrackIds }) {
            "Every range note must belong to an edited voice"
        }
        var current = runtime
        val inserted = linkedMapOf<Int, List<EventId>>()
        voiceTrackIds.forEach { voiceId ->
            val voice = current.getVoiceTrack(voiceId)
                ?: error("Unknown voice track ${voiceId.value}")
            val indexed = notes.withIndex()
                .filter { it.value.voiceTrackId == voiceId }
                .sortedBy { timeMap.absolute(it.value.start) }
            var cursor = startAbs
            val generated = mutableListOf<com.mecon.api.runtime.events.RuntimeVoiceEvent>()
            indexed.forEach { (inputIndex, note) ->
                val noteStart = timeMap.absolute(note.start)
                require(noteStart >= cursor) { "Range notes must not overlap within one voice" }
                if (noteStart > cursor) {
                    generated += fillRange(
                        runtime = current,
                        start = timeMap.timeCodeAt(cursor),
                        length = noteStart - cursor,
                        pitches = emptyList(),
                        articulations = emptyList(),
                        isRest = true,
                        trailingTie = false,
                    )
                }
                cursor = noteStart + note.duration
                require(cursor <= endAbs) { "Range note extends beyond replacement end" }
                val pieces = fillRange(
                    runtime = current,
                    start = note.start,
                    length = note.duration,
                    pitches = listOf(note.pitch),
                    articulations = emptyList(),
                    isRest = false,
                    trailingTie = false,
                )
                inserted[inputIndex] = pieces.map { it.id }
                generated += pieces
            }
            if (cursor < endAbs) {
                generated += fillRange(
                    runtime = current,
                    start = timeMap.timeCodeAt(cursor),
                    length = endAbs - cursor,
                    pitches = emptyList(),
                    articulations = emptyList(),
                    isRest = true,
                    trailingTie = false,
                )
            }
            val surviving = clearInterval(current, voice, start, end)
            current = StaffTrackOps.replaceVoice(
                current,
                voice,
                (surviving + generated).sortedBy { timeMap.absolute(it.onset) },
            )
        }
        return RangeReplacementResult(current, TimeRange(start, end), inserted)
    }

    enum class InsertionPolicy {
        CHORDAL,
        MONODIC,
    }

    /** A single editing request, resolved into concrete musical values by the caller (UI). */
    data class Insertion(
        val voiceTrackId: TrackId,
        /** Snapped onset where the new note/rest begins. */
        val start: TimeCode,
        /** The selected note value (base + dots). */
        val duration: Duration,
        /** The pitch to place; ignored when [isRest]. */
        val pitch: Pitch?,
        val isRest: Boolean = false,
        /** When true, the inserted note carries a tie out of its final piece (the tie toggle). */
        val trailingTie: Boolean = false,
        /** Staff under the cursor; when set, [voiceNumber] is resolved on this staff. */
        val staffTrackId: TrackId? = null,
        /** Voice number edited by the note pen. Missing voice tracks are created lazily. */
        val voiceNumber: Int = 1,
        /** When set, [duration] is the full tuplet group span and the first click creates beat one. */
        val tupletCount: Int? = null,
        /** Explicit beam override for the inserted note; null means auto-beam. */
        val beaming: BeamingInfo? = null,
        /** Articulations applied to the inserted note/chord; multiple values may be active. */
        val articulations: List<Articulation> = emptyList(),
        /** Non-null inserts into a grace-note group anchored at [start]. */
        val grace: GraceInsertion? = null,
        /**
         * Explicit small-note append target emitted by the renderer's visual append zone.
         *
         * This must not be inferred from [start]: a small-note span's exclusive endpoint is also
         * the ordinary onset immediately following the span (possibly the next measure or a normal
         * note), and therefore belongs to the normal time axis unless this id is present.
         */
        val smallNoteAppendStartEventId: EventId? = null,
    )

    data class GraceInsertion(
        val totalDuration: Duration = Duration(DurationBase.EIGHTH),
        val stealFrom: GraceTimeSource = GraceTimeSource.PRINCIPAL,
        val noteType: GraceNoteType = GraceNoteType.APPOGGIATURA,
    )

    /**
     * Atomic step-input request. Every pitch is resolved against the same score snapshot and the
     * result is committed as one history unit. At an existing pitched event, that event's duration
     * wins over [duration] so moving the entry caret backwards reliably adds chord tones.
     */
    data class ChordInsertion(
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val duration: Duration,
        val pitches: List<Pitch>,
        val isRest: Boolean = false,
        val trailingTie: Boolean = false,
        /** Per-pitch tie-out used by realtime held-set segmentation. [trailingTie] still ties all. */
        val tieOutMidi: Set<Int> = emptySet(),
        val staffTrackId: TrackId? = null,
        val voiceNumber: Int = 1,
        val tupletCount: Int? = null,
        val beaming: BeamingInfo? = null,
        val articulations: List<Articulation> = emptyList(),
        val grace: GraceInsertion? = null,
    )

    /** Outcome of an edit: the new score plus the absolute time span it touched (for incremental). */
    data class Result(
        val score: RuntimeScore,
        val editInterval: TimeRange,
        /**
         * Id of the primary event the edit produced — the new note/rest at [Insertion.start], or the
         * chord it merged into. The UI selects it so a freshly inserted note shows as selected.
         */
        val insertedEventId: EventId? = null,
    )

    /** A note/chord to copy from a voice track. Rests and unknown events are ignored. */
    data class CopyTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val pitchIndices: Set<Int>? = null,
        /**
         * Effective beaming captured from the source layout. Supplying this freezes automatic source
         * grouping as explicit beaming in the clipboard so paste does not regroup in the target context.
         */
        val beaming: BeamingInfo? = null,
    )

    data class NoteClipboard(
        val events: List<ClipboardEvent>,
        val slurs: List<ClipboardSlur> = emptyList(),
    ) {
        val isEmpty: Boolean get() = events.isEmpty()
    }

    data class ClipboardEvent(
        val voiceNumberOffset: Int,
        val offset: Fraction,
        val duration: Duration,
        val pitches: List<Pitch>,
        val articulations: List<Articulation>,
        val rendering: RenderingProps?,
        val ties: List<com.mecon.api.runtime.events.RuntimeTieInfo>,
        val tupletSpan: ClipboardTupletSpan? = null,
        /** Source identity used only to reconnect copied slur endpoints after paste. */
        val sourceEventId: EventId? = null,
    )

    data class ClipboardSlur(
        val voiceNumberOffset: Int,
        val startEventId: EventId,
        val endEventId: EventId,
    )

    data class ClipboardTupletSpan(
        val endOffset: Fraction,
        val count: Int,
        val beatUnit: DurationBase,
        val displayStyle: TupletDisplayStyle,
        val smallNotes: Boolean = false,
    )

    data class PasteTarget(
        val voiceTrackId: TrackId,
        val start: TimeCode,
        /** Clear the destination staff's whole target measure before materialising the clipboard. */
        val clearMeasure: Boolean = false,
    )

    data class PasteResult(
        val score: RuntimeScore,
        val intervals: List<TimeRange>,
        val pastedEventIds: List<EventId>,
    )

    sealed interface PasteOutcome {
        data class Changed(val result: PasteResult) : PasteOutcome
        object TupletCrossesBarline : PasteOutcome
        object NoOp : PasteOutcome
    }

    data class TupletEdit(
        val voiceTrackId: TrackId,
        val eventIds: Set<EventId>,
        val count: Int,
    )

    data class SmallNoteEdit(
        val voiceTrackId: TrackId,
        val eventIds: Set<EventId>,
    )

    fun createSmallNoteRegions(runtime: RuntimeScore, edits: List<SmallNoteEdit>): EditOutcome =
        NotePropertyEdits.createSmallNoteRegions(runtime, edits)

    data class TupletSpec(
        val count: Int,
        val normal: Int,
        val beatUnit: DurationBase,
        val displayStyle: TupletDisplayStyle = TupletDisplayStyle.BRACKET_AND_NUMBER,
    ) {
        val ratio: Fraction get() = Fraction(normal, count)
        val tuplet: Tuplet get() = Tuplet(count, normal)
    }

    /**
     * Apply [insertion] to [runtime], returning the new score, or `null` if it was a no-op
     * (unknown voice, or chording a pitch that is already present).
     */
    fun insert(
        runtime: RuntimeScore,
        insertion: Insertion,
        policy: InsertionPolicy = InsertionPolicy.CHORDAL,
    ): Result? = NoteInsertion.insert(runtime, insertion, policy)

    data class GraceGroupEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val totalDuration: Duration,
        val stealFrom: GraceTimeSource,
    )

    fun editGraceGroups(runtime: RuntimeScore, edits: List<GraceGroupEdit>): EditOutcome =
        GraceNoteEditing.editGroups(runtime, edits)

    fun insertChord(
        runtime: RuntimeScore,
        insertion: ChordInsertion,
        policy: InsertionPolicy = InsertionPolicy.CHORDAL,
    ): Result? = NoteInsertion.insertChord(runtime, insertion, policy)

    /** One realtime take, already quantized into notation-ready cells. */
    data class CaptureInsertion(
        val voiceTrackId: TrackId,
        val staffTrackId: TrackId,
        val voiceNumber: Int,
        val start: TimeCode,
        val end: TimeCode,
        val cells: List<ChordInsertion>,
        /** Replaces only this voice's take span when true; other voices are never touched. */
        val replace: Boolean = true,
    )

    /** Atomically materialize a realtime take; callers commit the returned score once. */
    fun insertCapture(runtime: RuntimeScore, insertion: CaptureInsertion): Result? =
        CaptureMaterializer.insert(runtime, insertion)

    fun copyNotes(runtime: RuntimeScore, targets: List<CopyTarget>): NoteClipboard? =
        NoteCopyPaste.copyNotes(runtime, targets)

    fun pasteNotes(runtime: RuntimeScore, clipboard: NoteClipboard, target: PasteTarget): PasteResult? =
        NoteCopyPaste.pasteNotes(runtime, clipboard, target)

    fun pasteNotesWithStatus(runtime: RuntimeScore, clipboard: NoteClipboard, target: PasteTarget): PasteOutcome =
        NoteCopyPaste.pasteNotesWithStatus(runtime, clipboard, target)

    data class SlurTarget(
        val voiceTrackId: TrackId,
        val startEventId: EventId,
        val endEventId: EventId,
    )

    data class SlurEditResult(
        val score: RuntimeScore,
        val affectedMeasures: IntRange,
        val slurIds: Set<EventId>,
    )

    fun addSlurs(runtime: RuntimeScore, targets: List<SlurTarget>): SlurEditResult? =
        SlurEditEngine.add(runtime, targets)

    fun deleteSlurs(runtime: RuntimeScore, slurIds: Set<EventId>): SlurEditResult? =
        SlurEditEngine.delete(runtime, slurIds)

    /** A single deletion request: which event in which voice, and (optionally) which chord pitches. */
    data class Deletion(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        /**
         * Pitch indices to remove from a chord. `null`, empty, or a set covering *every* pitch means
         * the whole event is deleted and its time span re-filled with rest(s). Ignored for rests.
         */
        val pitchIndices: Set<Int>? = null,
    )

    /**
     * Apply [deletion] to [runtime], returning the new score, or `null` if it was a no-op (unknown
     * voice or event — e.g. an implicit whole-measure rest with no backing runtime event).
     */
    fun delete(runtime: RuntimeScore, deletion: Deletion): Result? =
        NoteDeletion.delete(runtime, deletion)

    // ---- transpose ---------------------------------------------------------

    /** A single transpose request: which event, and (optionally) which chord pitches move. */
    data class TransposeTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        /**
         * Pitch indices to move within a chord. `null` (or empty) means the whole event (every
         * notehead) moves. Ignored for rests.
         */
        val pitchIndices: Set<Int>? = null,
    )

    /** One event that moved, plus which of its (post-sort) pitches moved — for re-selecting at the
     *  original granularity. [pitchIndices] is null when the whole event moved (select the event),
     *  else the new pitch indices of the moved noteheads (select just those). */
    data class MovedEvent(val eventId: EventId, val pitchIndices: Set<Int>?)

    /** Outcome of a transpose: the new score, the touched measure spans, and the moved events. */
    data class TransposeResult(
        val score: RuntimeScore,
        /**
         * One widened whole-measure interval per touched measure (deduped, ascending). A transpose
         * may change a note's accidental, which affects the *effective* accidental of later notes at
         * the same staff position in the same measure, so the whole measure is reported for the
         * incremental recompute. A single interval lets the caller take the incremental path.
         */
        val intervals: List<TimeRange>,
        /** Events that moved (identity preserved), with the post-move pitch granularity to re-select. */
        val movedEvents: List<MovedEvent>,
    ) {
        /** Ids of all moved events (whatever the pitch granularity). */
        val eventIds: List<EventId> get() = movedEvents.map { it.eventId }
    }

    /**
     * Transpose the [targets] by [stepDelta] diatonic steps (positive = up), spelling each moved
     * pitch with the **key signature's default accidental** for its new note name — i.e. any
     * temporary accidental on the original note is dropped (the "平移后默认删去临时升降号" rule). Timing
     * and durations are untouched; only pitches change in place.
     *
     * Returns `null` for a no-op (`stepDelta == 0`, no targets, or nothing actually changed —
     * e.g. all targets are rests or unknown events).
     */
    fun transpose(runtime: RuntimeScore, targets: List<TransposeTarget>, stepDelta: Int): TransposeResult? =
        NoteTranspose.transpose(runtime, targets, stepDelta)

    /** Set one piano-roll notehead to an exact spelled pitch (MIDI-row editing, not diatonic drag). */
    data class ExactPitchEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val pitchIndex: Int,
        val pitch: Pitch,
    )

    fun editExactPitches(
        runtime: RuntimeScore,
        edits: List<ExactPitchEdit>,
    ): TransposeResult? = DirectNoteEditing.editExactPitches(runtime, edits)

    enum class RangeBoundary { START, END }

    /**
     * Move one edge of a sounding note/chord. A directly adjacent pitched event in the same voice
     * shares the moved boundary, so the two durations are rewritten atomically.
     */
    data class RangeBoundaryEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val boundary: RangeBoundary,
        val target: TimeCode,
        val minimumLength: Fraction,
    )

    data class RangeBoundaryResult(
        val score: RuntimeScore,
        val intervals: List<TimeRange>,
        val resultEventIds: List<EventId>,
        val replacementEventIds: Map<EventId, EventId>,
    )

    fun editRangeBoundary(
        runtime: RuntimeScore,
        edit: RangeBoundaryEdit,
    ): RangeBoundaryResult? = DirectNoteEditing.editRangeBoundary(runtime, edit)

    // ---- in-place property edits (duration / accidental / tie) -------------

    /**
     * Outcome shared by the in-place property edits ([editDurations] / [editAccidentals] /
     * [editTies]). Mirrors the [TransposeResult]/[Result] shape but adds an explicit [Conflict] case
     * so the UI can tell "rejected (would overlap)" apart from "nothing changed".
     */
    sealed interface EditOutcome {
        /**
         * The edit changed the score. [intervals] are the touched (whole-measure) spans for the
         * incremental recompute — one per edited event; [resultEventIds] are the representative event
         * ids to re-point the selection at (the new note/rest standing in for each edited event).
         */
        data class Changed(
            val score: RuntimeScore,
            val intervals: List<TimeRange>,
            val resultEventIds: List<EventId>,
        ) : EditOutcome

        /** A duration *grow* would overlap a following note — the whole batch is rejected, no change. */
        object Conflict : EditOutcome

        /** No targets, or every target already had the requested value. */
        object NoOp : EditOutcome
    }

    /** Change one event's note value, keeping its onset, pitches, articulations and tie-out flag. */
    data class DurationEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val duration: Duration,
    )

    fun applyTuplets(runtime: RuntimeScore, edits: List<TupletEdit>): EditOutcome =
        NotePropertyEdits.applyTuplets(runtime, edits)

    /**
     * Change each selected event's displayed note value. Ordinary events rewrite their metered span;
     * ordinary tuplet members retain the group's ratio and cannot grow past its endpoint. Small-note
     * members keep the fixed group span and re-derive one common ratio, without changing other
     * members' displayed values or inserting rests.
     */
    fun editDurations(runtime: RuntimeScore, edits: List<DurationEdit>): EditOutcome =
        NotePropertyEdits.editDurations(runtime, edits)

    /**
     * Set the accidental on the selected pitches of an event. Unlike a duration edit (which is always
     * chord-wide), an accidental change touches **only** the pitches in [pitchIndices] (the spec's
     * "变音记号…只修改和弦中部分音符状态"); [pitchIndices] null means the whole chord. [accidental] null
     * clears the targeted pitches to the key-signature default spelling for their note names.
     */
    data class AccidentalEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val accidental: Accidental?,
        val pitchIndices: Set<Int>? = null,
    )

    /** Respell the selected pitches of each [AccidentalEdit]'s event. Timing is untouched, so there is never a conflict. */
    fun editAccidentals(runtime: RuntimeScore, edits: List<AccidentalEdit>): EditOutcome =
        NotePropertyEdits.editAccidentals(runtime, edits)

    /**
     * Set or clear the trailing tie on the selected pitches of an event. Like the accidental edit (and
     * unlike duration) this touches **only** the pitches in [pitchIndices] (null = whole chord), leaving
     * the other chord notes' ties as they were. [tieOut] true ties the targeted pitches out to the next
     * event; false removes the tie from just those pitches.
     */
    data class TieEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val tieOut: Boolean,
        val pitchIndices: Set<Int>? = null,
    )

    /** Add or remove the trailing tie on the selected pitches of each [TieEdit]'s event. Timing is untouched. */
    fun editTies(runtime: RuntimeScore, edits: List<TieEdit>): EditOutcome =
        NotePropertyEdits.editTies(runtime, edits)

    // ---- explicit beaming overrides ----------------------------------------

    /** Set or clear the explicit beam override on an event. [beaming] null = reset to auto. */
    data class BeamingEdit(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val beaming: BeamingInfo?,
    )

    /** Apply explicit beam overrides to the given events. */
    fun editBeaming(runtime: RuntimeScore, edits: List<BeamingEdit>): EditOutcome =
        NotePropertyEdits.editBeaming(runtime, edits)

    // ---- voice moves -------------------------------------------------------

    /** Move an event, or selected chord pitches, to another notation voice/staff. */
    data class VoiceMoveTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val targetVoiceNumber: Int,
        val pitchIndices: Set<Int>? = null,
        /** null keeps the source staff, preserving the historical same-staff operation. */
        val targetStaffId: TrackId? = null,
    )

    /** A moved event plus the selected pitch granularity after the move. */
    data class VoiceMovedEvent(val eventId: EventId, val pitchIndices: Set<Int>?)

    /** Outcome of moving notes between voices. */
    data class VoiceMoveResult(
        val score: RuntimeScore,
        val intervals: List<TimeRange>,
        val movedEvents: List<VoiceMovedEvent>,
    )

    /**
     * Move selected notes to [VoiceMoveTarget.targetVoiceNumber] on its requested staff. A partial
     * chord selection is split: selected pitches are removed from the source event and added to the
     * target notation lane, while the unselected pitches remain in the original lane.
     */
    fun moveVoices(runtime: RuntimeScore, targets: List<VoiceMoveTarget>): VoiceMoveResult? =
        VoiceMoveEngine.moveVoices(runtime, targets)

    data class VoiceReassignmentResult(
        val score: RuntimeScore,
        val voiceByEventId: Map<EventId, TrackId>,
    )

    /**
     * Reassign complete monodic events to explicit voice tracks, including voices on other staves.
     * Event identities are preserved so external selection and intent metadata remain stable.
     */
    fun reassignVoices(
        runtime: RuntimeScore,
        voiceByEventId: Map<EventId, TrackId>,
    ): VoiceReassignmentResult? =
        VoiceReassignmentEngine.reassign(runtime, voiceByEventId)

    /**
     * Move a rest to an explicit display staff position. [staffPosition] is the absolute position the
     * rest should render at (0 = middle line, +up, -down); `null` clears the override so the rest falls
     * back to its type's default position. Ignored for non-rest events.
     *
     * The position is resolved against the rendered default by the caller (the renderer owns the
     * default-per-type mapping), so this engine only stores the value — it does not interpret it. This
     * is purely a rendering override (it never changes timing or playback), so there is never a conflict.
     */
    data class RestMoveTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        /** Absolute display staff position, or null to reset to the type default. */
        val staffPosition: Int?,
    )

    /**
     * Set (or clear, when [RestMoveTarget.staffPosition] is null) the display staff position of each
     * target rest via [com.mecon.api.storage.RenderingProps.restStaffPosition]. Only the rest's own
     * measure is touched (a rest's vertical position affects nothing else), so each edit reports a
     * single whole-measure interval and the batch is always incremental-friendly.
     */
    fun moveRest(runtime: RuntimeScore, targets: List<RestMoveTarget>): EditOutcome =
        NotePropertyEdits.moveRest(runtime, targets)

    // ---- shared span primitives (exposed for tests / callers that need the raw building blocks) --

    /**
     * Subtract the half-open span `[start, end)` from every event of [voice] that overlaps it. See
     * [VoiceSpanEditing.clearInterval] for the full contract.
     */
    fun clearInterval(
        runtime: RuntimeScore,
        voice: com.mecon.api.runtime.tracks.RuntimeVoiceTrack,
        start: TimeCode,
        end: TimeCode,
    ): List<com.mecon.api.runtime.events.RuntimeVoiceEvent> =
        VoiceSpanEditing.clearInterval(runtime, voice, start, end)

    /** List-based variant of [clearInterval]. See [VoiceSpanEditing.clearIntervalEvents]. */
    fun clearIntervalEvents(
        runtime: RuntimeScore,
        events: List<com.mecon.api.runtime.events.RuntimeVoiceEvent>,
        start: TimeCode,
        end: TimeCode,
    ): List<com.mecon.api.runtime.events.RuntimeVoiceEvent> =
        VoiceSpanEditing.clearIntervalEvents(runtime, events, start, end)

    /** Materialise a span into engraved note/rest values. See [VoiceSpanEditing.fillRange]. */
    fun fillRange(
        runtime: RuntimeScore,
        start: TimeCode,
        length: Fraction,
        pitches: List<Pitch>,
        articulations: List<Articulation>,
        isRest: Boolean,
        trailingTie: Boolean,
    ): List<com.mecon.api.runtime.events.RuntimeVoiceEvent> =
        VoiceSpanEditing.fillRange(runtime, start, length, pitches, articulations, isRest, trailingTie)

    /** Pick a normal-count/beat-unit pairing for an arbitrary tuplet, or `null` if none fits evenly. */
    fun tupletSpecFor(totalLength: Fraction, count: Int): TupletSpec? =
        TupletSupport.tupletSpecFor(totalLength, count)
}
