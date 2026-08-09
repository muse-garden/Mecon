package com.mecon.features.scoreediting

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.NavigationMark as StorageNavigationMark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a platform currently has selected, as stable musical identity.
 *
 * Each variant carries exactly the fields its kind needs, so adding a new selectable element cannot
 * widen a shared struct with more nullable fields and platform adapters get an exhaustive `when`
 * instead of guessing which fields a kind populates. Serialized with the codec's `type`
 * discriminator, e.g. `{"type":"event","eventId":"..."}`.
 */
@Serializable
sealed interface ScoreSelectionTarget {
    @Serializable
    @SerialName("event")
    data class Event(
        val eventId: EventId,
        val voiceTrackId: TrackId? = null,
        val pitchIndices: Set<Int>? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("clef")
    data class Clef(val staffTrackId: TrackId?, val onset: TimeCode) : ScoreSelectionTarget

    @Serializable
    @SerialName("keySignature")
    data class KeySignature(val staffTrackId: TrackId?, val onset: TimeCode) : ScoreSelectionTarget

    @Serializable
    @SerialName("timeSignature")
    data class TimeSignature(val staffTrackId: TrackId?, val onset: TimeCode) : ScoreSelectionTarget

    /** [boundaryMeasure] is the measure the barline closes. */
    @Serializable
    @SerialName("barline")
    data class Barline(
        val boundaryMeasure: Int,
        val onset: TimeCode? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("voltaEnding")
    data class VoltaEnding(
        val startMeasure: Int,
        val endMeasure: Int,
        val numbers: Set<Int>,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("navigationMark")
    data class NavigationMark(
        val boundaryMeasure: Int,
        val mark: StorageNavigationMark,
        val onset: TimeCode? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("slur")
    data class Slur(
        val slurId: EventId,
        val voiceTrackId: TrackId,
        val startEventId: EventId,
        val endEventId: EventId,
    ) : ScoreSelectionTarget

    /** A tie leaving one pitch of [sourceEventId]; chords carry several. */
    @Serializable
    @SerialName("tie")
    data class Tie(
        val sourceEventId: EventId,
        val sourcePitchIndex: Int,
        val voiceTrackId: TrackId? = null,
        val targetEventId: EventId? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("beam")
    data class Beam(val groupId: String) : ScoreSelectionTarget

    @Serializable
    @SerialName("articulation")
    data class Articulation(
        val eventId: EventId,
        val articulationIndex: Int? = null,
        val voiceTrackId: TrackId? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val attachmentId: EventId,
        val staffTrackId: TrackId? = null,
    ) : ScoreSelectionTarget

    @Serializable
    @SerialName("layoutBreak")
    data class LayoutBreak(val beforeMeasure: Int) : ScoreSelectionTarget

    @Serializable
    @SerialName("staffVisibility")
    data class StaffVisibility(
        val staffTrackId: TrackId,
        val startMeasure: Int,
        val endMeasure: Int,
    ) : ScoreSelectionTarget
}

/**
 * The element id a target points at, for adapters that only need "which thing is selected".
 * Prefer an exhaustive `when` when the behaviour differs per kind.
 */
val ScoreSelectionTarget.eventIdOrNull: EventId?
    get() = when (this) {
        is ScoreSelectionTarget.Event -> eventId
        is ScoreSelectionTarget.Articulation -> eventId
        is ScoreSelectionTarget.Tie -> sourceEventId
        is ScoreSelectionTarget.Slur -> slurId
        is ScoreSelectionTarget.Attachment -> attachmentId
        else -> null
    }

/** Owning voice track, where the selected element belongs to one. */
val ScoreSelectionTarget.voiceTrackIdOrNull: TrackId?
    get() = when (this) {
        is ScoreSelectionTarget.Event -> voiceTrackId
        is ScoreSelectionTarget.Articulation -> voiceTrackId
        is ScoreSelectionTarget.Tie -> voiceTrackId
        is ScoreSelectionTarget.Slur -> voiceTrackId
        else -> null
    }
