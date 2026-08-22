package com.mecon.features.scoreediting

import com.mecon.api.primitive.Accidental
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.ArticulationGeometry
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.TieGeometry
import com.mecon.api.storage.TupletGeometry
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.tracks.Clef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Versioned, UI-free editing commands. Coordinates are already snapped musical coordinates; pixel
 * hit testing and gesture sampling stay in the platform adapter.
 */
@Serializable
sealed interface ScoreEditIntent {
    val expectedRevision: Long

    @Serializable
    @SerialName("setSelection")
    data class SetSelection(
        override val expectedRevision: Long,
        val targets: List<ScoreSelectionTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("insertNote")
    data class InsertNote(
        override val expectedRevision: Long,
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val duration: Duration,
        val pitch: Pitch? = null,
        /**
         * MIDI note number for keyboard/controller input. Spelling is resolved by the shared layer
         * via [Pitch.fromMidi] so every platform spells the same note identically; [pitch] wins when
         * both are supplied.
         */
        val midiNote: Int? = null,
        val preferSharps: Boolean = true,
        val isRest: Boolean = false,
        val trailingTie: Boolean = false,
        val staffTrackId: TrackId? = null,
        val voiceNumber: Int = 1,
        val tupletCount: Int? = null,
        val beaming: BeamingInfo? = null,
        val articulations: List<Articulation> = emptyList(),
        val grace: GraceInsertion? = null,
        val smallNoteAppendStartEventId: EventId? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("insertChord")
    data class InsertChord(
        override val expectedRevision: Long,
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val duration: Duration,
        val pitches: List<Pitch>,
        val trailingTie: Boolean = false,
        val staffTrackId: TrackId? = null,
        val voiceNumber: Int = 1,
        val tupletCount: Int? = null,
        val beaming: BeamingInfo? = null,
        val articulations: List<Articulation> = emptyList(),
        val grace: GraceInsertion? = null,
    ) : ScoreEditIntent

    /**
     * Creates an empty tuplet span filled with shared-engine rests. Mobile entry uses this before
     * entering notes so choosing the group and choosing its first pitch are two explicit actions.
     * Legacy desktop entry may keep using [InsertNote.tupletCount] for its combined first-note flow.
     */
    @Serializable
    @SerialName("createTupletRegion")
    data class CreateTupletRegion(
        override val expectedRevision: Long,
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val totalDuration: Duration,
        val count: Int,
        val staffTrackId: TrackId? = null,
        val voiceNumber: Int = 1,
    ) : ScoreEditIntent

    @Serializable
    data class GraceInsertion(
        val totalDuration: Duration = Duration.EIGHTH,
        val stealFrom: GraceTimeSource = GraceTimeSource.PRINCIPAL,
        val noteType: GraceNoteType = GraceNoteType.APPOGGIATURA,
    )

    @Serializable
    data class EventTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val pitchIndices: Set<Int>? = null,
    )

    @Serializable
    data class CopyTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val pitchIndices: Set<Int>? = null,
        val beaming: BeamingInfo? = null,
    )

    @Serializable
    @SerialName("copyNotes")
    data class CopyNotes(
        override val expectedRevision: Long,
        val targets: List<CopyTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("cutNotes")
    data class CutNotes(
        override val expectedRevision: Long,
        val targets: List<CopyTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("pasteNotes")
    data class PasteNotes(
        override val expectedRevision: Long,
        val voiceTrackId: TrackId,
        val start: TimeCode,
        val clearMeasure: Boolean = false,
    ) : ScoreEditIntent

    @Serializable
    data class VoiceMoveTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val targetVoiceNumber: Int,
        val pitchIndices: Set<Int>? = null,
        val targetStaffId: TrackId? = null,
    )

    @Serializable
    @SerialName("moveVoices")
    data class MoveVoices(
        override val expectedRevision: Long,
        val targets: List<VoiceMoveTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setClef")
    data class SetClef(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val onset: TimeCode,
        val clef: Clef,
        val resolveExisting: Boolean = false,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setKeySignature")
    data class SetKeySignature(
        override val expectedRevision: Long,
        val onset: TimeCode,
        val keySignature: KeySignature,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setTimeSignature")
    data class SetTimeSignature(
        override val expectedRevision: Long,
        val measureNumber: Int,
        val timeSignature: TimeSignature,
    ) : ScoreEditIntent

    @Serializable
    enum class BoundaryInsertion { BEFORE_BREAK, AFTER_BREAK }

    @Serializable
    @SerialName("insertMeasures")
    data class InsertMeasures(
        override val expectedRevision: Long,
        val afterMeasure: Int,
        val count: Int,
        val boundaryInsertion: BoundaryInsertion = BoundaryInsertion.BEFORE_BREAK,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteMeasures")
    data class DeleteMeasures(
        override val expectedRevision: Long,
        val measureNumbers: Set<Int>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setBarline")
    data class SetBarline(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
        val barlineType: BarlineType,
        val repeatCount: Int = 2,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setBarlineRepeatCount")
    data class SetBarlineRepeatCount(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
        val repeatCount: Int,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("toggleVoltaPair")
    data class ToggleVoltaPair(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("resizeSecondVolta")
    data class ResizeSecondVolta(
        override val expectedRevision: Long,
        val startMeasure: Int,
        val endMeasure: Int,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("resizeFirstVoltaStart")
    data class ResizeFirstVoltaStart(
        override val expectedRevision: Long,
        val startMeasure: Int,
        val newStartMeasure: Int,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteVolta")
    data class DeleteVolta(
        override val expectedRevision: Long,
        val startMeasure: Int,
        val endMeasure: Int,
        val numbers: Set<Int>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("toggleNavigationMark")
    data class ToggleNavigationMark(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
        val mark: NavigationMark,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("moveNavigationMark")
    data class MoveNavigationMark(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
        val targetBoundaryMeasure: Int,
        val mark: NavigationMark,
        val offset: NavigationMarkOffset = NavigationMarkOffset(),
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteNavigationMark")
    data class DeleteNavigationMark(
        override val expectedRevision: Long,
        val boundaryMeasure: Int,
        val mark: NavigationMark,
    ) : ScoreEditIntent

    @Serializable
    data class SlurTarget(
        val voiceTrackId: TrackId,
        val startEventId: EventId,
        val endEventId: EventId,
    )

    @Serializable
    @SerialName("addSlurs")
    data class AddSlurs(
        override val expectedRevision: Long,
        val targets: List<SlurTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteSlurs")
    data class DeleteSlurs(
        override val expectedRevision: Long,
        val slurIds: Set<EventId>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setSlurGeometry")
    data class SetSlurGeometry(
        override val expectedRevision: Long,
        val slurId: EventId,
        val geometry: SlurGeometry,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setTieGeometry")
    data class SetTieGeometry(
        override val expectedRevision: Long,
        val sourceEventId: EventId,
        val geometry: TieGeometry,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setTupletGeometry")
    data class SetTupletGeometry(
        override val expectedRevision: Long,
        val startEventId: EventId,
        val geometry: TupletGeometry,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setBeamGeometry")
    data class SetBeamGeometry(
        override val expectedRevision: Long,
        val groupId: String,
        val geometry: BeamGeometry,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setArticulationGeometry")
    data class SetArticulationGeometry(
        override val expectedRevision: Long,
        val eventId: EventId,
        val geometry: ArticulationGeometry,
        val selectedIndex: Int? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addDynamic")
    data class AddDynamic(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val onset: TimeCode,
        val level: DynamicLevel,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addHairpin")
    data class AddHairpin(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val start: TimeCode,
        val end: TimeCode,
        val hairpinType: HairpinType,
        val style: HairpinStyle = HairpinStyle.WEDGE,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addOctaveShift")
    data class AddOctaveShift(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val start: TimeCode,
        val end: TimeCode,
        val shiftType: OctaveShiftType,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addTempoMark")
    data class AddTempoMark(
        override val expectedRevision: Long,
        val onset: TimeCode,
        val markType: TempoMarkType,
        val bpm: Float? = null,
        val beatUnit: DurationBase = DurationBase.QUARTER,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addGradualTempo")
    data class AddGradualTempo(
        override val expectedRevision: Long,
        val start: TimeCode,
        val end: TimeCode,
        val markType: TempoMarkType,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addFermata")
    data class AddFermata(
        override val expectedRevision: Long,
        val afterTime: TimeCode,
        val shape: FermataShape = FermataShape.NORMAL,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("addBreathMark")
    data class AddBreathMark(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val afterTime: TimeCode,
        val scope: BreathMarkScope = BreathMarkScope.STAFF,
        val shape: BreathMarkShape = BreathMarkShape.COMMA,
        val voiceNumber: Int? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteExpressions")
    data class DeleteExpressions(
        override val expectedRevision: Long,
        val ids: Set<EventId>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("moveAttachment")
    data class MoveAttachment(
        override val expectedRevision: Long,
        val attachmentId: EventId,
        val start: TimeCode,
        val end: TimeCode? = null,
        val geometry: AttachmentGeometry,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setLayoutBreak")
    data class SetLayoutBreak(
        override val expectedRevision: Long,
        val beforeMeasure: Int,
        val kind: LayoutBreakKind? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setStaffVisibility")
    data class SetStaffVisibility(
        override val expectedRevision: Long,
        val staffTrackIds: Set<TrackId>,
        val startMeasure: Int,
        val endMeasure: Int,
        val hidden: Boolean,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("deleteNotes")
    data class DeleteNotes(
        override val expectedRevision: Long,
        val targets: List<EventTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("transposeNotes")
    data class TransposeNotes(
        override val expectedRevision: Long,
        val targets: List<EventTarget>,
        val stepDelta: Int,
    ) : ScoreEditIntent

    @Serializable
    data class DurationTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val duration: Duration,
    )

    @Serializable
    @SerialName("setDurations")
    data class SetDurations(
        override val expectedRevision: Long,
        val targets: List<DurationTarget>,
    ) : ScoreEditIntent

    @Serializable
    data class AccidentalTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val accidental: Accidental? = null,
        val pitchIndices: Set<Int>? = null,
    )

    @Serializable
    @SerialName("setAccidentals")
    data class SetAccidentals(
        override val expectedRevision: Long,
        val targets: List<AccidentalTarget>,
    ) : ScoreEditIntent

    @Serializable
    data class TieTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val tieOut: Boolean,
        val pitchIndices: Set<Int>? = null,
    )

    @Serializable
    @SerialName("setTies")
    data class SetTies(
        override val expectedRevision: Long,
        val targets: List<TieTarget>,
    ) : ScoreEditIntent

    @Serializable
    data class BeamingTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val beaming: BeamingInfo? = null,
    )

    @Serializable
    @SerialName("setBeaming")
    data class SetBeaming(
        override val expectedRevision: Long,
        val targets: List<BeamingTarget>,
    ) : ScoreEditIntent

    @Serializable
    data class GraceGroupTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val totalDuration: Duration,
        val stealFrom: GraceTimeSource,
    )

    @Serializable
    @SerialName("setGraceGroups")
    data class SetGraceGroups(
        override val expectedRevision: Long,
        val targets: List<GraceGroupTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("toggleArticulation")
    data class ToggleArticulation(
        override val expectedRevision: Long,
        val targets: List<EventTarget>,
        val articulation: Articulation,
    ) : ScoreEditIntent

    @Serializable
    data class EventGroupTarget(
        val voiceTrackId: TrackId,
        val eventIds: Set<EventId>,
        val count: Int? = null,
    )

    @Serializable
    @SerialName("applyTuplets")
    data class ApplyTuplets(
        override val expectedRevision: Long,
        val targets: List<EventGroupTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("createSmallNoteRegions")
    data class CreateSmallNoteRegions(
        override val expectedRevision: Long,
        val targets: List<EventGroupTarget>,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("setArpeggio")
    data class SetArpeggio(
        override val expectedRevision: Long,
        val targets: List<EventTarget>,
        val arpeggioType: ArpeggioType? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("moveRests")
    data class MoveRests(
        override val expectedRevision: Long,
        val targets: List<RestPositionTarget>,
    ) : ScoreEditIntent

    @Serializable
    data class RestPositionTarget(
        val voiceTrackId: TrackId,
        val eventId: EventId,
        val staffPosition: Int? = null,
    )

    @Serializable
    @SerialName("addOrnament")
    data class AddOrnament(
        override val expectedRevision: Long,
        val staffTrackId: TrackId,
        val sourceEventId: EventId,
        val ornamentKind: OrnamentKind,
        val anchor: OrnamentAnchor = OrnamentAnchor.ON_NOTE,
        val endOnset: TimeCode? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("updateOrnament")
    data class UpdateOrnament(
        override val expectedRevision: Long,
        val ornamentId: EventId,
        val upperAccidental: Accidental? = null,
        val lowerAccidental: Accidental? = null,
        val elementDuration: Fraction? = null,
        val oscillations: Int? = null,
        val trillPlaybackMode: TrillPlaybackMode? = null,
        val updateUpperAccidental: Boolean = false,
        val updateLowerAccidental: Boolean = false,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("updateTempo")
    data class UpdateTempo(
        override val expectedRevision: Long,
        val tempoId: EventId,
        val effectiveBpm: Float? = null,
        val displayStyle: TempoDisplayStyle? = null,
        val transition: TempoTransition? = null,
        val text: String? = null,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("updatePerformanceMark")
    data class UpdatePerformanceMark(
        override val expectedRevision: Long,
        val markId: EventId,
        val amount: Fraction,
    ) : ScoreEditIntent

    @Serializable
    @SerialName("undo")
    data class Undo(override val expectedRevision: Long) : ScoreEditIntent

    @Serializable
    @SerialName("redo")
    data class Redo(override val expectedRevision: Long) : ScoreEditIntent
}

@Serializable
enum class ScoreEditUpdateKind { FULL }

@Serializable
enum class ScoreEditEffectKind {
    APPLIED,
    COPIED,
    CUT,
    PASTED,
    SELECTION_CHANGED,
    UNDONE,
    REDONE,
    STALE_REVISION,
    CONFLICT,
    NO_OP,
}

@Serializable
data class ScoreEditEffect(
    val kind: ScoreEditEffectKind,
    val messageKey: String? = null,
    val expectedRevision: Long? = null,
    val actualRevision: Long? = null,
)

/**
 * Range of the score the last accepted edit touched, mirroring the in-process `RenderHint` so remote
 * platforms can render incrementally instead of re-laying out the whole score.
 */
@Serializable
data class ScoreEditRenderHint(
    val firstMeasure: Int,
    val lastMeasure: Int,
    val notationChanged: Boolean = true,
    val structureReflow: Boolean = false,
)

/**
 * One-shot note-tool state to apply after an accepted insertion.
 *
 * Starting a tuplet consumes the pending tuplet count and changes the displayed input value to the
 * group's member unit. Platforms consume this transition instead of reimplementing tuplet maths or
 * guessing from the stored events. A null [tupletCount] explicitly clears the pending group switch.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScoreNoteInputTransition(
    val duration: Duration,
    val tupletCount: Int? = null,
    /** Explicit shared-engine append target for entry into an open small-note region. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val smallNoteAppendStartEventId: EventId? = null,
)

/** First wire version intentionally sends a full immutable score snapshot after every accepted edit. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScoreEditUpdate(
    val schemaVersion: Int = SCORE_EDIT_WIRE_SCHEMA_VERSION,
    val revision: Long,
    val baseRevision: Long? = null,
    val kind: ScoreEditUpdateKind = ScoreEditUpdateKind.FULL,
    val score: StorageScore,
    val selection: List<ScoreSelectionTarget> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canPaste: Boolean = false,
    /**
     * Whether this update actually committed a new score. Selection changes, clipboard copies,
     * no-ops and rejected intents leave the score identical, so a client may reuse its previous
     * layout instead of re-rendering. Authoritative — clients must not infer this from [effect].
     */
    val scoreChanged: Boolean = true,
    /**
     * Where sequential (step / MIDI) input should continue after this edit. The shared layer derives
     * it from the committed event so tuplet ratios, dots and ties are honoured identically on every
     * platform; null when the edit did not move the input cursor.
     */
    val nextInputPosition: TimeCode? = null,
    /** One-shot state change for the note-entry tool; absent for edits that do not consume a mode. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val noteInputTransition: ScoreNoteInputTransition? = null,
    val renderHint: ScoreEditRenderHint? = null,
    val effect: ScoreEditEffect,
)

const val SCORE_EDIT_WIRE_SCHEMA_VERSION: Int = 1

object ScoreEditCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeIntent(intent: ScoreEditIntent): String =
        json.encodeToString(ScoreEditIntent.serializer(), intent)

    fun decodeIntent(value: String): ScoreEditIntent =
        json.decodeFromString(ScoreEditIntent.serializer(), value)

    fun encodeUpdate(update: ScoreEditUpdate): String =
        json.encodeToString(ScoreEditUpdate.serializer(), update)

    fun decodeUpdate(value: String): ScoreEditUpdate =
        json.decodeFromString(ScoreEditUpdate.serializer(), value)
}
