package com.mecon.api.storage.tracks

import com.mecon.api.primitive.InstrumentId
import com.mecon.api.primitive.TrackId
import kotlinx.serialization.Serializable

/**
 * Portable playback target. MIDI is the always-available fallback; VST data is
 * opaque so the score format does not depend on a particular plugin host.
 */
@Serializable
data class InstrumentPlayback(
    val midiBank: Int = 0,
    val midiProgram: Int = 0,
    val soundFontId: String? = null,
    val pluginId: String? = null,
    val pluginState: Map<String, String> = emptyMap()
) {
    init {
        require(midiBank >= 0) { "MIDI bank must be non-negative" }
        require(midiProgram in 0..127) { "MIDI program must be 0..127" }
    }

    companion object {
        val PIANO = InstrumentPlayback()
    }
}

/**
 * A musical/playback instrument. It owns one or more staves. Display order,
 * brackets and connected barlines remain in StorageStaffGroup.
 */
@Serializable
data class StorageInstrument(
    val id: InstrumentId,
    val name: String = "Piano",
    val abbreviation: String? = null,
    /** Stable notation-catalog identity; unlike [name], this survives display-name edits. */
    val catalogId: String? = null,
    val staffIds: List<TrackId>,
    val playback: InstrumentPlayback = InstrumentPlayback.PIANO
) {
    init {
        require(staffIds.isNotEmpty()) { "An instrument must own at least one staff" }
    }
}
