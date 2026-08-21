package com.mecon.features.scoreediting

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** UI-neutral interaction families shared by Desktop, Web and mobile adapters. */
@Serializable
enum class ScoreInteractionFamily { N, E, P, S, G, T, B, H, F }

@Serializable
enum class ScoreInteractionTopology {
    NONE,
    INSERTION_CURSOR,
    POINT,
    ORDERED_SPAN,
    EVENT_GROUP,
    SELECTION,
    MEASURE_BOUNDARY_OR_RANGE,
    SEMANTIC_HANDLE,
    PROPERTY_DRAFT,
}

/** Semantic candidates an adapter may retarget to before it creates an intent. */
@Serializable
enum class ScoreSnapTopology {
    NONE,
    MEASURE_BOUNDARY,
    EVENT_TIME,
    INSERTION_BOUNDARY,
    PRESERVE_SEMANTIC_ANCHOR,
}

/** Geometry that remains editable after the semantic anchor has been chosen. */
@Serializable
enum class ScoreCoordinateFreedom {
    NOT_APPLICABLE,
    FIXED_XY,
    FIXED_X_ADJUSTABLE_Y,
    ADJUSTABLE_XY,
}

/** Stable operation variants whose targeting policy differs inside one interaction family. */
@Serializable
enum class ScoreTargetingKind {
    DEFAULT,
    NOTE_ENTRY,
    TUPLET_REGION,
    CLEF,
    KEY_SIGNATURE,
    TIME_SIGNATURE,
    DYNAMIC,
    TEMPO,
    FERMATA,
    BREATH,
    ORNAMENT,
    SPAN,
    EVENT_GROUP,
    SELECTION_TRANSFORM,
    VERTICAL_TRANSFORM,
    STRUCTURE,
    VERTICAL_HANDLE,
    FREE_HANDLE,
    ATTACHMENT_HANDLE,
    BOUNDARY_HANDLE,
    NAVIGATION_HANDLE,
    PROPERTY,
}

@Serializable
data class ScoreTargetingSpec(
    val kind: ScoreTargetingKind,
    val snapTopology: ScoreSnapTopology,
    val coordinateFreedom: ScoreCoordinateFreedom,
)

@Serializable
enum class ScoreInteractionSuccessPolicy {
    /** Keep the tool group active, but require a fresh point/span for the next operation. */
    KEEP_TOOL_RESET_TARGET,
    /** Keep entry active and use the session's authoritative next input position. */
    KEEP_ENTRY_ADVANCE_CURSOR,
    /** Switch to ordinary note entry at the result's returned input position. */
    SWITCH_TO_ENTRY,
    /** Keep the current selection-oriented command available. */
    KEEP_SELECTION_COMMAND,
}

@Serializable
enum class ScoreToolGroup { NOTES, POINT_SYMBOLS, SPANS, STRUCTURE, SELECTION, ENGRAVING }

@Serializable
enum class ScorePointerKind { TOUCH, STYLUS, MOUSE }

@Serializable
enum class ScoreViewportClass { COMPACT, MEDIUM, EXPANDED }

@Serializable
data class ScoreInputCapabilities(
    val pointerKinds: Set<ScorePointerKind> = emptySet(),
    val hover: Boolean = false,
    val hardwareKeyboard: Boolean = false,
    val midi: Boolean = false,
    val viewportClass: ScoreViewportClass = ScoreViewportClass.COMPACT,
)

@Serializable
data class ScoreInteractionSpec(
    val commandId: String,
    val family: ScoreInteractionFamily,
    val topology: ScoreInteractionTopology,
    val toolGroup: ScoreToolGroup,
    val successPolicy: ScoreInteractionSuccessPolicy,
    /** Independent of [topology]: where the target snaps and which geometry may later move. */
    val targeting: List<ScoreTargetingSpec>,
)

/** Semantic anchors only. Platform pixels are discarded before entering this model. */
@Serializable
sealed interface ScoreInteractionAnchor {
    @Serializable
    @SerialName("selection")
    data class Selection(val targets: List<ScoreSelectionTarget>) : ScoreInteractionAnchor

    @Serializable
    @SerialName("staffTime")
    data class StaffTime(val staffTrackId: TrackId, val time: TimeCode) : ScoreInteractionAnchor

    @Serializable
    @SerialName("voiceTime")
    data class VoiceTime(val voiceTrackId: TrackId, val time: TimeCode) : ScoreInteractionAnchor

    @Serializable
    @SerialName("boundary")
    data class Boundary(val boundaryMeasure: Int) : ScoreInteractionAnchor

    @Serializable
    @SerialName("eventRange")
    data class EventRange(val startEventId: EventId, val endEventId: EventId) : ScoreInteractionAnchor

    @Serializable
    @SerialName("measureRange")
    data class MeasureRange(val startMeasure: Int, val endMeasure: Int) : ScoreInteractionAnchor
}

@Serializable
enum class ScoreInteractionPhase { IDLE, ARMED, TARGETING, PREVIEWING, COMMIT_PENDING }

@Serializable
data class ScoreEntryCursor(
    val voiceTrackId: TrackId,
    val position: TimeCode,
    val anchorEventId: EventId? = null,
)

@Serializable
data class ScoreInteractionState(
    val activeCommandId: String? = null,
    val family: ScoreInteractionFamily = ScoreInteractionFamily.N,
    val phase: ScoreInteractionPhase = ScoreInteractionPhase.IDLE,
    val anchors: List<ScoreInteractionAnchor> = emptyList(),
    val entryCursor: ScoreEntryCursor? = null,
    val smallNoteAppendStartEventId: EventId? = null,
)

/**
 * Shared presentation state machine. It never mutates a score: platforms resolve semantic anchors,
 * build an intent, dispatch it to [ScoreEditingSession], then feed the authoritative result back.
 */
class ScoreInteractionController(initial: ScoreInteractionState = ScoreInteractionState()) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<ScoreInteractionState> = mutableState.asStateFlow()

    fun begin(commandId: String, cursor: ScoreEntryCursor? = mutableState.value.entryCursor) {
        val spec = ScoreInteractionCatalog.spec(commandId)
        mutableState.value = mutableState.value.copy(
            activeCommandId = spec.commandId,
            family = spec.family,
            phase = ScoreInteractionPhase.ARMED,
            anchors = emptyList(),
            entryCursor = cursor,
            smallNoteAppendStartEventId = if (spec.family == ScoreInteractionFamily.E) {
                mutableState.value.smallNoteAppendStartEventId
            } else {
                null
            },
        )
    }

    fun target(anchors: List<ScoreInteractionAnchor>, preview: Boolean = false) {
        check(mutableState.value.activeCommandId != null) { "Begin an interaction before targeting" }
        mutableState.value = mutableState.value.copy(
            anchors = anchors,
            phase = if (preview) ScoreInteractionPhase.PREVIEWING else ScoreInteractionPhase.TARGETING,
        )
    }

    fun markCommitPending() {
        check(mutableState.value.activeCommandId != null) { "Begin an interaction before committing" }
        mutableState.value = mutableState.value.copy(phase = ScoreInteractionPhase.COMMIT_PENDING)
    }

    /** Cancel only the current run; a sticky Phone tool group remains armed. */
    fun cancelRun() {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = if (current.activeCommandId == null) ScoreInteractionPhase.IDLE else ScoreInteractionPhase.ARMED,
            anchors = emptyList(),
        )
    }

    fun deactivate() {
        mutableState.value = mutableState.value.copy(
            activeCommandId = null,
            family = ScoreInteractionFamily.N,
            phase = ScoreInteractionPhase.IDLE,
            anchors = emptyList(),
            smallNoteAppendStartEventId = null,
        )
    }

    fun accept(intent: ScoreEditIntent, result: ScoreEditDispatchResult) {
        val current = mutableState.value
        if (result.effect.kind != ScoreEditEffectKind.APPLIED &&
            result.effect.kind != ScoreEditEffectKind.PASTED &&
            result.effect.kind != ScoreEditEffectKind.CUT
        ) {
            mutableState.value = current.copy(phase = ScoreInteractionPhase.ARMED, anchors = emptyList())
            return
        }
        val spec = ScoreInteractionCatalog.spec(intent)
        // Selection and history are global auxiliaries, not tools. They must not disarm a sticky
        // point/span command or move the Phone's persistent tool-group selection.
        if (spec.commandId == ScoreInteractionCatalog.NAVIGATION) {
            mutableState.value = current.copy(
                phase = if (current.activeCommandId == null) {
                    ScoreInteractionPhase.IDLE
                } else {
                    ScoreInteractionPhase.ARMED
                },
                anchors = emptyList(),
            )
            return
        }
        when (spec.successPolicy) {
            ScoreInteractionSuccessPolicy.KEEP_TOOL_RESET_TARGET -> {
                mutableState.value = current.copy(
                    activeCommandId = spec.commandId,
                    family = spec.family,
                    phase = ScoreInteractionPhase.ARMED,
                    anchors = emptyList(),
                )
            }
            ScoreInteractionSuccessPolicy.KEEP_ENTRY_ADVANCE_CURSOR -> {
                val previous = current.entryCursor
                val voiceId = ScoreInteractionCatalog.voiceTrackId(intent) ?: previous?.voiceTrackId
                val position = result.frame.nextInputPosition ?: previous?.position
                mutableState.value = current.copy(
                    activeCommandId = ScoreInteractionCatalog.ENTRY_NOTE,
                    family = ScoreInteractionFamily.E,
                    phase = ScoreInteractionPhase.ARMED,
                    anchors = emptyList(),
                    entryCursor = if (voiceId != null && position != null) {
                        ScoreEntryCursor(voiceId, position)
                    } else {
                        previous
                    },
                    smallNoteAppendStartEventId = result.noteInputTransition?.smallNoteAppendStartEventId
                        ?: current.smallNoteAppendStartEventId,
                )
            }
            ScoreInteractionSuccessPolicy.SWITCH_TO_ENTRY -> {
                val voiceId = ScoreInteractionCatalog.voiceTrackId(intent)
                    ?: current.entryCursor?.voiceTrackId
                val position = result.frame.nextInputPosition ?: current.entryCursor?.position
                mutableState.value = current.copy(
                    activeCommandId = ScoreInteractionCatalog.ENTRY_NOTE,
                    family = ScoreInteractionFamily.E,
                    phase = ScoreInteractionPhase.ARMED,
                    anchors = emptyList(),
                    entryCursor = if (voiceId != null && position != null) {
                        ScoreEntryCursor(voiceId, position)
                    } else {
                        current.entryCursor
                    },
                    smallNoteAppendStartEventId = result.noteInputTransition?.smallNoteAppendStartEventId,
                )
            }
            ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND -> {
                mutableState.value = current.copy(
                    activeCommandId = spec.commandId,
                    family = spec.family,
                    phase = ScoreInteractionPhase.ARMED,
                    anchors = emptyList(),
                )
            }
        }
    }

    fun moveEntryCursor(runtime: RuntimeScore, action: ScoreEntryCursorAction) {
        val cursor = mutableState.value.entryCursor ?: return
        mutableState.value = mutableState.value.copy(
            entryCursor = ScoreEntryCursorNavigator.move(runtime, cursor, action),
            smallNoteAppendStartEventId = null,
        )
    }
}

@Serializable
enum class ScoreEntryCursorAction { PREVIOUS_NOTE, NEXT_NOTE, PREVIOUS_MEASURE, NEXT_MEASURE }

object ScoreEntryCursorNavigator {
    fun move(runtime: RuntimeScore, cursor: ScoreEntryCursor, action: ScoreEntryCursorAction): ScoreEntryCursor {
        val maxMeasure = runtime.measures.maxOfOrNull { it.key } ?: 1
        if (action == ScoreEntryCursorAction.PREVIOUS_MEASURE || action == ScoreEntryCursorAction.NEXT_MEASURE) {
            val delta = if (action == ScoreEntryCursorAction.PREVIOUS_MEASURE) -1 else 1
            return cursor.copy(
                position = TimeCode.of(
                    (cursor.position.measure + delta).coerceIn(1, maxMeasure),
                    com.mecon.api.primitive.Fraction.ZERO,
                ),
                anchorEventId = null,
            )
        }
        val events = runtime.getVoiceTrack(cursor.voiceTrackId)?.events?.toList()
            .orEmpty()
            .filter { !it.isRest && it.pitches.isNotEmpty() }
            .sortedBy { it.onset }
        if (events.isEmpty()) return cursor
        val anchored = cursor.anchorEventId?.let { id -> events.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        val target = when (action) {
            ScoreEntryCursorAction.PREVIOUS_NOTE -> if (anchored != null) {
                events.getOrNull(anchored - 1)
            } else {
                events.lastOrNull { it.onset < cursor.position }
            }
            ScoreEntryCursorAction.NEXT_NOTE -> if (anchored != null) {
                events.getOrNull(anchored + 1)
            } else {
                events.firstOrNull { it.onset >= cursor.position }
            }
            else -> null
        }
        if (target == null && action == ScoreEntryCursorAction.NEXT_NOTE && anchored == events.lastIndex) {
            val last = events.last()
            val timeMap = ScoreTimeMap.from(runtime)
            return cursor.copy(
                position = timeMap.timeCodeAt(
                    timeMap.absolute(last.onset) + last.duration.toFraction(),
                ),
                anchorEventId = null,
            )
        }
        target ?: return cursor
        return cursor.copy(position = target.onset, anchorEventId = target.id)
    }
}

/** Stable command catalog plus an exhaustive intent-to-family mapping. */
object ScoreInteractionCatalog {
    const val NAVIGATION = "navigation.selection"
    const val ENTRY_NOTE = "entry.note"
    const val ENTRY_TUPLET_REGION = "entry.tuplet-region"
    const val POINT_SYMBOL = "placement.point"
    const val SPAN_SYMBOL = "placement.span"
    const val GROUP_EVENTS = "group.events"
    const val GROUP_SMALL_NOTES = "group.small-notes"
    const val TRANSFORM_SELECTION = "transform.selection"
    const val STRUCTURE = "structure.boundary-range"
    const val HANDLE = "engraving.handle"
    const val PROPERTY = "property.edit"

    private fun targeting(
        kind: ScoreTargetingKind,
        snap: ScoreSnapTopology,
        coordinates: ScoreCoordinateFreedom,
    ) = ScoreTargetingSpec(kind, snap, coordinates)

    val specs: List<ScoreInteractionSpec> = listOf(
        ScoreInteractionSpec(
            NAVIGATION, ScoreInteractionFamily.N, ScoreInteractionTopology.NONE,
            ScoreToolGroup.SELECTION, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(targeting(ScoreTargetingKind.DEFAULT, ScoreSnapTopology.NONE, ScoreCoordinateFreedom.NOT_APPLICABLE)),
        ),
        ScoreInteractionSpec(
            ENTRY_NOTE, ScoreInteractionFamily.E, ScoreInteractionTopology.INSERTION_CURSOR,
            ScoreToolGroup.NOTES, ScoreInteractionSuccessPolicy.KEEP_ENTRY_ADVANCE_CURSOR,
            listOf(targeting(ScoreTargetingKind.NOTE_ENTRY, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.FIXED_X_ADJUSTABLE_Y)),
        ),
        ScoreInteractionSpec(
            ENTRY_TUPLET_REGION, ScoreInteractionFamily.E, ScoreInteractionTopology.INSERTION_CURSOR,
            ScoreToolGroup.NOTES, ScoreInteractionSuccessPolicy.SWITCH_TO_ENTRY,
            listOf(targeting(ScoreTargetingKind.TUPLET_REGION, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.FIXED_XY)),
        ),
        ScoreInteractionSpec(
            POINT_SYMBOL, ScoreInteractionFamily.P, ScoreInteractionTopology.POINT,
            ScoreToolGroup.POINT_SYMBOLS, ScoreInteractionSuccessPolicy.KEEP_TOOL_RESET_TARGET,
            listOf(
                targeting(ScoreTargetingKind.CLEF, ScoreSnapTopology.INSERTION_BOUNDARY, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.KEY_SIGNATURE, ScoreSnapTopology.MEASURE_BOUNDARY, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.TIME_SIGNATURE, ScoreSnapTopology.MEASURE_BOUNDARY, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.DYNAMIC, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.ADJUSTABLE_XY),
                targeting(ScoreTargetingKind.TEMPO, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.ADJUSTABLE_XY),
                targeting(ScoreTargetingKind.FERMATA, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.BREATH, ScoreSnapTopology.INSERTION_BOUNDARY, ScoreCoordinateFreedom.ADJUSTABLE_XY),
                targeting(ScoreTargetingKind.ORNAMENT, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.ADJUSTABLE_XY),
            ),
        ),
        ScoreInteractionSpec(
            SPAN_SYMBOL, ScoreInteractionFamily.S, ScoreInteractionTopology.ORDERED_SPAN,
            ScoreToolGroup.SPANS, ScoreInteractionSuccessPolicy.KEEP_TOOL_RESET_TARGET,
            listOf(targeting(ScoreTargetingKind.SPAN, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.ADJUSTABLE_XY)),
        ),
        ScoreInteractionSpec(
            GROUP_EVENTS, ScoreInteractionFamily.G, ScoreInteractionTopology.EVENT_GROUP,
            ScoreToolGroup.SELECTION, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(targeting(ScoreTargetingKind.EVENT_GROUP, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.FIXED_XY)),
        ),
        ScoreInteractionSpec(
            GROUP_SMALL_NOTES, ScoreInteractionFamily.G, ScoreInteractionTopology.EVENT_GROUP,
            ScoreToolGroup.NOTES, ScoreInteractionSuccessPolicy.SWITCH_TO_ENTRY,
            listOf(targeting(ScoreTargetingKind.EVENT_GROUP, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.FIXED_XY)),
        ),
        ScoreInteractionSpec(
            TRANSFORM_SELECTION, ScoreInteractionFamily.T, ScoreInteractionTopology.SELECTION,
            ScoreToolGroup.SELECTION, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(
                targeting(ScoreTargetingKind.SELECTION_TRANSFORM, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.VERTICAL_TRANSFORM, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.FIXED_X_ADJUSTABLE_Y),
            ),
        ),
        ScoreInteractionSpec(
            STRUCTURE, ScoreInteractionFamily.B, ScoreInteractionTopology.MEASURE_BOUNDARY_OR_RANGE,
            ScoreToolGroup.STRUCTURE, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(targeting(ScoreTargetingKind.STRUCTURE, ScoreSnapTopology.MEASURE_BOUNDARY, ScoreCoordinateFreedom.FIXED_XY)),
        ),
        ScoreInteractionSpec(
            HANDLE, ScoreInteractionFamily.H, ScoreInteractionTopology.SEMANTIC_HANDLE,
            ScoreToolGroup.ENGRAVING, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(
                targeting(ScoreTargetingKind.VERTICAL_HANDLE, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.FIXED_X_ADJUSTABLE_Y),
                targeting(ScoreTargetingKind.FREE_HANDLE, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.ADJUSTABLE_XY),
                targeting(ScoreTargetingKind.ATTACHMENT_HANDLE, ScoreSnapTopology.EVENT_TIME, ScoreCoordinateFreedom.ADJUSTABLE_XY),
                targeting(ScoreTargetingKind.BOUNDARY_HANDLE, ScoreSnapTopology.MEASURE_BOUNDARY, ScoreCoordinateFreedom.FIXED_XY),
                targeting(ScoreTargetingKind.NAVIGATION_HANDLE, ScoreSnapTopology.MEASURE_BOUNDARY, ScoreCoordinateFreedom.FIXED_X_ADJUSTABLE_Y),
            ),
        ),
        ScoreInteractionSpec(
            PROPERTY, ScoreInteractionFamily.F, ScoreInteractionTopology.PROPERTY_DRAFT,
            ScoreToolGroup.SELECTION, ScoreInteractionSuccessPolicy.KEEP_SELECTION_COMMAND,
            listOf(targeting(ScoreTargetingKind.PROPERTY, ScoreSnapTopology.PRESERVE_SEMANTIC_ANCHOR, ScoreCoordinateFreedom.NOT_APPLICABLE)),
        ),
    )
    private val byId = specs.associateBy { it.commandId }

    /** Portable descriptor consumed by JS and future native presentation adapters. */
    fun encodeSpecs(): String = Json.encodeToString(specs)

    fun spec(commandId: String): ScoreInteractionSpec =
        requireNotNull(byId[commandId]) { "Unknown score interaction command: $commandId" }

    fun spec(intent: ScoreEditIntent): ScoreInteractionSpec = spec(commandId(intent))

    fun family(intent: ScoreEditIntent): ScoreInteractionFamily = spec(intent).family

    fun targeting(kind: ScoreTargetingKind): ScoreTargetingSpec = specs.asSequence()
        .flatMap { it.targeting.asSequence() }
        .firstOrNull { it.kind == kind }
        ?: error("Unknown score targeting kind: $kind")

    fun targeting(intent: ScoreEditIntent): ScoreTargetingSpec = targeting(targetingKind(intent))

    /** Exhaustive targeting classification, independent of the broader interaction family. */
    fun targetingKind(intent: ScoreEditIntent): ScoreTargetingKind = when (intent) {
        is ScoreEditIntent.SetSelection, is ScoreEditIntent.Undo, is ScoreEditIntent.Redo -> ScoreTargetingKind.DEFAULT
        is ScoreEditIntent.InsertNote, is ScoreEditIntent.InsertChord, is ScoreEditIntent.PasteNotes -> ScoreTargetingKind.NOTE_ENTRY
        is ScoreEditIntent.CreateTupletRegion -> ScoreTargetingKind.TUPLET_REGION
        is ScoreEditIntent.SetClef -> ScoreTargetingKind.CLEF
        is ScoreEditIntent.SetKeySignature -> ScoreTargetingKind.KEY_SIGNATURE
        is ScoreEditIntent.SetTimeSignature -> ScoreTargetingKind.TIME_SIGNATURE
        is ScoreEditIntent.AddDynamic -> ScoreTargetingKind.DYNAMIC
        is ScoreEditIntent.AddTempoMark -> ScoreTargetingKind.TEMPO
        is ScoreEditIntent.AddFermata -> ScoreTargetingKind.FERMATA
        is ScoreEditIntent.AddBreathMark -> ScoreTargetingKind.BREATH
        is ScoreEditIntent.AddOrnament -> if (intent.endOnset == null) ScoreTargetingKind.ORNAMENT else ScoreTargetingKind.SPAN
        is ScoreEditIntent.AddSlurs,
        is ScoreEditIntent.AddHairpin,
        is ScoreEditIntent.AddOctaveShift,
        is ScoreEditIntent.AddGradualTempo -> ScoreTargetingKind.SPAN
        is ScoreEditIntent.ApplyTuplets,
        is ScoreEditIntent.SetGraceGroups,
        is ScoreEditIntent.CreateSmallNoteRegions -> ScoreTargetingKind.EVENT_GROUP
        is ScoreEditIntent.MoveRests -> ScoreTargetingKind.VERTICAL_TRANSFORM
        is ScoreEditIntent.CopyNotes,
        is ScoreEditIntent.CutNotes,
        is ScoreEditIntent.MoveVoices,
        is ScoreEditIntent.DeleteNotes,
        is ScoreEditIntent.TransposeNotes,
        is ScoreEditIntent.SetDurations,
        is ScoreEditIntent.SetAccidentals,
        is ScoreEditIntent.SetTies,
        is ScoreEditIntent.SetBeaming,
        is ScoreEditIntent.ToggleArticulation,
        is ScoreEditIntent.SetArpeggio,
        is ScoreEditIntent.DeleteSlurs,
        is ScoreEditIntent.DeleteExpressions -> ScoreTargetingKind.SELECTION_TRANSFORM
        is ScoreEditIntent.InsertMeasures,
        is ScoreEditIntent.DeleteMeasures,
        is ScoreEditIntent.SetBarline,
        is ScoreEditIntent.SetBarlineRepeatCount,
        is ScoreEditIntent.ToggleVoltaPair,
        is ScoreEditIntent.DeleteVolta,
        is ScoreEditIntent.ToggleNavigationMark,
        is ScoreEditIntent.DeleteNavigationMark,
        is ScoreEditIntent.SetLayoutBreak,
        is ScoreEditIntent.SetStaffVisibility -> ScoreTargetingKind.STRUCTURE
        is ScoreEditIntent.SetSlurGeometry,
        is ScoreEditIntent.SetTieGeometry,
        is ScoreEditIntent.SetBeamGeometry -> ScoreTargetingKind.VERTICAL_HANDLE
        is ScoreEditIntent.SetArticulationGeometry -> ScoreTargetingKind.FREE_HANDLE
        is ScoreEditIntent.MoveAttachment -> ScoreTargetingKind.ATTACHMENT_HANDLE
        is ScoreEditIntent.ResizeSecondVolta,
        is ScoreEditIntent.ResizeFirstVoltaStart -> ScoreTargetingKind.BOUNDARY_HANDLE
        is ScoreEditIntent.MoveNavigationMark -> ScoreTargetingKind.NAVIGATION_HANDLE
        is ScoreEditIntent.UpdateOrnament,
        is ScoreEditIntent.UpdateTempo,
        is ScoreEditIntent.UpdatePerformanceMark -> ScoreTargetingKind.PROPERTY
    }

    /** Exhaustive by construction: adding an intent forces this mapping to be updated. */
    fun commandId(intent: ScoreEditIntent): String = when (intent) {
        is ScoreEditIntent.SetSelection, is ScoreEditIntent.Undo, is ScoreEditIntent.Redo -> NAVIGATION
        is ScoreEditIntent.InsertNote, is ScoreEditIntent.InsertChord, is ScoreEditIntent.PasteNotes -> ENTRY_NOTE
        is ScoreEditIntent.CreateTupletRegion -> ENTRY_TUPLET_REGION
        is ScoreEditIntent.SetClef,
        is ScoreEditIntent.SetKeySignature,
        is ScoreEditIntent.SetTimeSignature,
        is ScoreEditIntent.AddDynamic,
        is ScoreEditIntent.AddTempoMark,
        is ScoreEditIntent.AddFermata,
        is ScoreEditIntent.AddBreathMark -> POINT_SYMBOL
        is ScoreEditIntent.AddOrnament -> if (intent.endOnset == null) POINT_SYMBOL else SPAN_SYMBOL
        is ScoreEditIntent.AddSlurs,
        is ScoreEditIntent.AddHairpin,
        is ScoreEditIntent.AddOctaveShift,
        is ScoreEditIntent.AddGradualTempo -> SPAN_SYMBOL
        is ScoreEditIntent.ApplyTuplets, is ScoreEditIntent.SetGraceGroups -> GROUP_EVENTS
        is ScoreEditIntent.CreateSmallNoteRegions -> GROUP_SMALL_NOTES
        is ScoreEditIntent.CopyNotes,
        is ScoreEditIntent.CutNotes,
        is ScoreEditIntent.MoveVoices,
        is ScoreEditIntent.DeleteNotes,
        is ScoreEditIntent.TransposeNotes,
        is ScoreEditIntent.SetDurations,
        is ScoreEditIntent.SetAccidentals,
        is ScoreEditIntent.SetTies,
        is ScoreEditIntent.SetBeaming,
        is ScoreEditIntent.ToggleArticulation,
        is ScoreEditIntent.SetArpeggio,
        is ScoreEditIntent.MoveRests,
        is ScoreEditIntent.DeleteSlurs,
        is ScoreEditIntent.DeleteExpressions -> TRANSFORM_SELECTION
        is ScoreEditIntent.InsertMeasures,
        is ScoreEditIntent.DeleteMeasures,
        is ScoreEditIntent.SetBarline,
        is ScoreEditIntent.SetBarlineRepeatCount,
        is ScoreEditIntent.ToggleVoltaPair,
        is ScoreEditIntent.DeleteVolta,
        is ScoreEditIntent.ToggleNavigationMark,
        is ScoreEditIntent.DeleteNavigationMark,
        is ScoreEditIntent.SetLayoutBreak,
        is ScoreEditIntent.SetStaffVisibility -> STRUCTURE
        is ScoreEditIntent.SetSlurGeometry,
        is ScoreEditIntent.SetTieGeometry,
        is ScoreEditIntent.SetBeamGeometry,
        is ScoreEditIntent.SetArticulationGeometry,
        is ScoreEditIntent.MoveAttachment,
        is ScoreEditIntent.ResizeSecondVolta,
        is ScoreEditIntent.ResizeFirstVoltaStart,
        is ScoreEditIntent.MoveNavigationMark -> HANDLE
        is ScoreEditIntent.UpdateOrnament,
        is ScoreEditIntent.UpdateTempo,
        is ScoreEditIntent.UpdatePerformanceMark -> PROPERTY
    }

    fun voiceTrackId(intent: ScoreEditIntent): TrackId? = when (intent) {
        is ScoreEditIntent.InsertNote -> intent.voiceTrackId
        is ScoreEditIntent.InsertChord -> intent.voiceTrackId
        is ScoreEditIntent.CreateTupletRegion -> intent.voiceTrackId
        is ScoreEditIntent.PasteNotes -> intent.voiceTrackId
        is ScoreEditIntent.CreateSmallNoteRegions -> intent.targets.singleOrNull()?.voiceTrackId
        else -> null
    }
}
