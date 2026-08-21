package com.mecon.desktop.ui.views.drag

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.views.AuditionTarget
import com.mecon.desktop.voiceTrackIdOf
import com.mecon.renderer.render.RenderResult

/**
 * Targets and preview state for the note/rest semantic handle (family H).
 *
 * Dragging a notehead transposes it; dragging a rest moves its staff position. Both hide their
 * originals, show a re-engraved preview, and hold it until the committed re-render lands, so the
 * lifecycle below is deliberately kind-agnostic: [TransposeDragState.transposeTargets] drives a
 * transpose, a non-null [TransposeDragState.restMove] drives a rest move.
 */
internal data class TransposeDragState(
    val previewTargets: Map<EventId, Set<Int>?>,
    val transposeTargets: List<NoteEditEngine.TransposeTarget>,
    /** The glyph that started the drag; unrelated selected events are never added to its audition. */
    val auditionTarget: AuditionTarget? = null,
    /** Last effective (range-clamped) delta sounded, so pinned edge pitches are not retriggered. */
    val auditionStepDelta: Int = 0,
    /** Non-null when this drag moves rest(s) rather than transposing notes. */
    val restMove: RestMoveInfo? = null,
    /** For a transpose: diatonic step delta. For a rest move: staff-position step delta (+up). */
    val stepDelta: Int = 0,
    /** Current re-engraved preview (two colour layers), or null when there is nothing to show
     *  (delta 0, or clamped to 0 at the MIDI-range edge). Null ⇒ the originals stay visible. */
    val preview: com.mecon.renderer.render.edit.TransposePreview? = null,
    /** True after the mouse is released: the preview is held (originals still hidden, interaction
     *  blocked) until the committed re-render lands, so the score never flashes the pre-drag notes. */
    val committing: Boolean = false,
    /** Render frame displayed at mouse-up. Captured before invoking the edit callback so a fast commit
     *  cannot race ahead of the effect that waits for its replacement. Compared by identity. */
    val commitBaseline: RenderResult? = null,
    /** Monotonic mouse-up time used only by the opt-in hand-off performance probe. */
    val commitStartedAtNanos: Long = 0L,
)

/** A rest being moved: where it sits now (its effective position) and its type default (so a drag
 *  back to the default normalizes the stored override to null). */
internal data class RestTargetInfo(
    val voiceTrackId: TrackId,
    val eventId: EventId,
    val startPosition: Int,
    val defaultPosition: Int,
)

/** The rests grabbed by a drag-to-move-rest gesture; null on a note-transpose drag. */
internal data class RestMoveInfo(val targets: List<RestTargetInfo>)

/** The non-rest computed event a section refers to, or null for rests / non-note sections. */
internal fun EventSection.movableEvent(): ComputedVoiceEvent? = when (this) {
    is VoiceNoteSection -> event.takeUnless { it.isRest }
    is VoiceEventSection -> event.takeUnless { it.isRest }
    else -> null
}

/** The rest computed event a section refers to (whole-event selection of a rest), or null otherwise. */
internal fun EventSection.restEvent(): ComputedVoiceEvent? = when (this) {
    is VoiceEventSection -> event.takeIf { it.isRest }
    else -> null
}

/** A drag edits one event: sound its whole resulting chord, transposing only the moved pitches. */
internal fun EventSection.dragAuditionTarget(
    targets: List<NoteEditEngine.TransposeTarget>,
): AuditionTarget? {
    val event = when (this) {
        is VoiceNoteSection -> event
        is VoiceEventSection -> event
        else -> return null
    }.takeUnless { it.isRest } ?: return null
    val target = targets.firstOrNull { it.eventId == event.id } ?: return null
    return AuditionTarget(event, soundingPitchIndices = null, transposedPitchIndices = target.pitchIndices)
}

/** Mirrors the renderer/edit engine clamp so drag audition follows the pitch actually on screen. */
internal fun clampTransposeDelta(
    runtime: RuntimeScore,
    computed: ComputedScore,
    targets: Map<EventId, Set<Int>?>,
    requested: Int,
): Int {
    val moved = buildList {
        for ((eventId, pitchIndices) in targets) {
            val event = computed.getComputedEvent(eventId) ?: continue
            val key = runtime.getKeySignatureAt(event.onset.measure)
            val indices = pitchIndices?.takeIf { it.isNotEmpty() }
                ?.filter { it in event.pitchData.indices }
                ?: event.pitchData.indices.toList()
            indices.forEach { add(event.pitchData[it].pitch to key) }
        }
    }
    return DiatonicTranspose.clampDelta(moved, requested)
}

/**
 * The sections that should move when a transpose drag grabs [picked]:
 * - [picked] is already part of the selection → the whole selection moves;
 * - shift held → the selection plus the grabbed note;
 * - otherwise → just the grabbed note.
 */
internal fun resolveMoveSections(
    picked: EventSection,
    selection: Set<EventSection>,
    shiftHeld: Boolean,
): Set<EventSection> = when {
    picked in selection -> selection
    shiftHeld -> selection + picked
    else -> setOf(picked)
}

/**
 * Aggregate [sections] into transpose targets, one per note event: a whole-event section moves every
 * pitch (`pitchIndices = null`); individual notehead sections move only their pitch. Mirrors
 * `App.buildDeletions`. Rests and non-note sections are skipped.
 */
internal fun buildTransposeTargets(
    sections: Set<EventSection>,
    runtime: RuntimeScore?,
): List<NoteEditEngine.TransposeTarget> {
    if (runtime == null) return emptyList()
    class Acc { var whole = false; val pitches = mutableSetOf<Int>() }
    val byEvent = LinkedHashMap<EventId, Acc>()
    for (section in sections) {
        when (section) {
            is VoiceNoteSection -> if (!section.event.isRest)
                byEvent.getOrPut(section.event.id) { Acc() }.pitches.add(section.pitchIndex)
            is VoiceEventSection -> if (!section.event.isRest)
                byEvent.getOrPut(section.event.id) { Acc() }.whole = true
            else -> {}
        }
    }
    return byEvent.mapNotNull { (eventId, acc) ->
        val voiceTrackId = runtime.voiceTrackIdOf(eventId) ?: return@mapNotNull null
        NoteEditEngine.TransposeTarget(
            voiceTrackId = voiceTrackId,
            eventId = eventId,
            pitchIndices = if (acc.whole) null else acc.pitches,
        )
    }
}

/**
 * Aggregate the rest events in [sections] into per-rest move info: each rest's current effective
 * staff position (override, else type default) and its type default. Mirrors [buildTransposeTargets]
 * for notes. Non-rest / non-event sections are skipped.
 */
internal fun buildRestMoveInfo(
    sections: Set<EventSection>,
    runtime: RuntimeScore?,
): RestMoveInfo {
    if (runtime == null) return RestMoveInfo(emptyList())
    val targets = LinkedHashMap<EventId, RestTargetInfo>()
    for (section in sections) {
        val event = section.restEvent() ?: continue
        if (event.id in targets) continue
        val voiceTrackId = runtime.voiceTrackIdOf(event.id) ?: continue
        val default = com.mecon.renderer.layout.RestLayout.defaultRestStaffPosition(event.duration)
        val start = event.rendering?.restStaffPosition ?: default
        targets[event.id] = RestTargetInfo(voiceTrackId, event.id, start, default)
    }
    return RestMoveInfo(targets.values.toList())
}
