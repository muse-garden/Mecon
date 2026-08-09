package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore

/**
 * Utilities for building track relationship mappings.
 *
 * These utilities handle the relationships between:
 * - Voice tracks (contain events)
 * - Staff tracks (contain voice tracks, displayed on a staff)
 * - Part tracks (contain staff tracks, represent an instrument)
 */
object TrackMappingUtils {

    /**
     * Build a mapping from voice track ID to staff track ID.
     *
     * Each voice track belongs to exactly one staff track.
     */
    fun buildVoiceToStaffMapping(runtime: RuntimeScore): Map<TrackId, TrackId> {
        val mapping = mutableMapOf<TrackId, TrackId>()

        for ((_, staffTrack) in runtime.staffTracks) {
            for (voiceTrack in staffTrack.voiceTracks) {
                mapping[voiceTrack.id] = staffTrack.id
            }
        }

        return mapping
    }

    /**
     * Find which voice track an event belongs to.
     *
     * @param runtime The runtime score
     * @param eventId The event ID to find
     * @return The voice track ID, or null if not found
     */
    fun findVoiceTrackForEvent(runtime: RuntimeScore, eventId: EventId): TrackId? {
        for ((voiceTrackId, voiceTrack) in runtime.voiceTracks) {
            if (voiceTrack.events.any { it.id == eventId }) {
                return voiceTrackId
            }
        }
        return null
    }

    /**
     * Find which voice track a computed event belongs to.
     *
     * @param runtime The runtime score
     * @param event The computed voice event
     * @return The voice track ID, or null if not found
     */
    fun findVoiceTrackForEvent(runtime: RuntimeScore, event: ComputedVoiceEvent): TrackId? =
        findVoiceTrackForEvent(runtime, event.id)

    /**
     * Find the staff track ID for an event.
     *
     * @param runtime The runtime score
     * @param eventId The event ID
     * @return The staff track ID, or null if not found
     */
    fun findStaffTrackForEvent(runtime: RuntimeScore, eventId: EventId): TrackId? {
        val voiceTrackId = findVoiceTrackForEvent(runtime, eventId) ?: return null
        val voiceToStaff = buildVoiceToStaffMapping(runtime)
        return voiceToStaff[voiceTrackId]
    }
}

/**
 * Location of an event within the score layout.
 */
data class EventLocation(
    /** The event ID */
    val eventId: EventId,
    /** The voice track containing this event */
    val voiceTrackId: TrackId,
    /** The staff track this event is displayed on */
    val staffTrackId: TrackId,
    /** The system index (for multi-system layouts) */
    val systemIndex: Int,
    /** The staff index within the system */
    val staffIndex: Int,
    /** The measure number */
    val measureNumber: Int
)
