package com.mecon.api.runtime

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark

/**
 * Playback-only time added after a written voice event. Written notation
 * duration is deliberately not changed.
 */
data class PerformanceTimingAdjustment(
    val fermataExtension: Fraction = Fraction.ZERO,
    /** Score-wide pause inserted into the playback timeline. */
    val globalBreathPause: Fraction = Fraction.ZERO,
    /** Voice/staff breath taken from the preceding written note without moving later beats. */
    val localBreathPause: Fraction = Fraction.ZERO,
) {
    val breathPause: Fraction get() = globalBreathPause + localBreathPause
    val total: Fraction get() = fermataExtension + breathPause
}

/**
 * Resolve global/staff/voice performance marks to one concrete voice event.
 *
 * Marks are stored at the TimeCode immediately after the affected material;
 * each scope therefore targets its last non-grace event strictly before that
 * TimeCode.
 */
fun RuntimeScore.performanceTimingFor(
    voiceTrackId: TrackId,
    eventId: EventId,
): PerformanceTimingAdjustment {
    val voice = voiceTracks[voiceTrackId] ?: return PerformanceTimingAdjustment()
    val event = voice.events.toList().firstOrNull { it.id == eventId }
        ?: return PerformanceTimingAdjustment()
    val staff = staffTracks.values.firstOrNull { candidate ->
        candidate.voiceTracks.any { it.id == voiceTrackId }
    }

    fun targets(after: com.mecon.api.primitive.TimeCode): Boolean =
        voice.events.toList().lastOrNull { !it.isGrace && it.onset < after }?.id == event.id

    val fermata = globalTrack.events.filterIsInstance<StorageFermata>()
        .filter { targets(it.onset) }
        .fold(Fraction.ZERO) { sum, mark -> sum + mark.extension }

    val globalBreath = globalTrack.events.filterIsInstance<StorageGlobalBreathMark>()
        .filter { targets(it.onset) }
        .fold(Fraction.ZERO) { sum, mark -> sum + mark.pause }

    val localBreath = staff?.attachments
        ?.filterIsInstance<StorageBreathMark>()
        ?.filter { mark ->
            (mark.voiceNumber == null || mark.voiceNumber == voice.voiceNumber) && targets(mark.onset)
        }
        ?.fold(Fraction.ZERO) { sum, mark -> sum + mark.pause }
        ?: Fraction.ZERO

    return PerformanceTimingAdjustment(
        fermataExtension = fermata,
        globalBreathPause = globalBreath,
        localBreathPause = localBreath,
    )
}
