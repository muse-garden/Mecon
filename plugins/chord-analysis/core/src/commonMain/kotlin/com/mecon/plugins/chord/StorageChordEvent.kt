package com.mecon.plugins.chord

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.theory.ChordQuality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Storage event for a chord symbol attached to a point in time.
 *
 * [root] and [bass] are stored as raw pitch-class integers (0..11) rather than
 * `PitchClass` value classes — kotlinx-serialization treats them as plain ints
 * on disk so the YAML/JSON shape stays stable across plugin versions.
 */
@Serializable
@SerialName("mecon.chord_analysis.chord")
data class StorageChordEvent(
    override val id: EventId,
    override val onset: TimeCode,
    val root: Int,
    val quality: ChordQuality,
    val bass: Int? = null
) : StoragePluginEvent() {
    init {
        require(root in 0..11) { "root pitch class must be 0..11, got $root" }
        require(bass == null || bass in 0..11) { "bass pitch class must be 0..11 or null, got $bass" }
    }

    companion object {
        /** Plugin track type used in [StoragePluginTrack.type]. */
        const val TRACK_TYPE: String = "mecon.chord_analysis"

        fun create(
            onset: TimeCode,
            root: Int,
            quality: ChordQuality,
            bass: Int? = null
        ): StorageChordEvent = StorageChordEvent(
            id = EventId.generate(),
            onset = onset,
            root = root,
            quality = quality,
            bass = bass
        )
    }
}
