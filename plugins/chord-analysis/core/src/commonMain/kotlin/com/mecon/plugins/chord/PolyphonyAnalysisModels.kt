package com.mecon.plugins.chord

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.events.StoragePluginForwardAffectingEvent
import com.mecon.api.storage.events.StoragePluginIntervalEvent
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable tonal identity used by polyphonic-analysis regions.
 *
 * Keeping fifths (rather than only a pitch class) preserves enharmonic spelling
 * such as C-sharp versus D-flat when a region is edited and saved.
 */
@Serializable
data class PolyphonyTonalKey(
    val fifths: Int,
    val mode: KeySignatureMode,
) {
    init {
        require(fifths in -7..7) { "fifths must be between -7 and 7" }
    }

    fun toModulationKey(): ModulationKey = ModulationKey(fifths, mode)

    companion object {
        fun from(key: ModulationKey): PolyphonyTonalKey =
            PolyphonyTonalKey(key.fifths, key.mode)
    }
}

/**
 * A user-declared non-chord-tone slice of one notehead.
 *
 * The half-open range [onset, endOnset) can cover the entire note or just a
 * fraction of it. [voiceEventId] + [pitchIndex] keeps chord noteheads distinct.
 */
@Serializable
@SerialName("mecon.chord_analysis.non_chord_tone")
data class StorageNonChordToneEvent(
    override val id: EventId,
    override val onset: TimeCode,
    override val endOnset: TimeCode,
    val voiceEventId: EventId,
    val pitchIndex: Int,
) : StoragePluginEvent(), StoragePluginIntervalEvent {
    init {
        require(endOnset > onset) { "non-chord-tone range must be non-empty" }
        require(pitchIndex >= 0) { "pitchIndex must be non-negative" }
    }

    fun contains(time: TimeCode): Boolean = time >= onset && time < endOnset

    companion object {
        const val TRACK_TYPE: String = "mecon.chord_analysis.non_chord_tones"

        fun create(
            onset: TimeCode,
            endOnset: TimeCode,
            voiceEventId: EventId,
            pitchIndex: Int,
        ): StorageNonChordToneEvent = StorageNonChordToneEvent(
            id = EventId.generate(),
            onset = onset,
            endOnset = endOnset,
            voiceEventId = voiceEventId,
            pitchIndex = pitchIndex,
        )
    }
}

/**
 * A definite or ambiguous tonal region.
 *
 * A single [keys] entry is a definite region; multiple entries are displayed
 * simultaneously. [resolvedKey], when present, must be one of the candidates
 * and becomes the tonal center after [endOnset].
 */
@Serializable
enum class TonalRegionRole {
    INSERTED,
    SCORE_KEY_BASELINE,
}

@Serializable
@SerialName("mecon.chord_analysis.tonal_region")
data class StorageTonalRegionEvent(
    override val id: EventId,
    override val onset: TimeCode,
    override val endOnset: TimeCode,
    val keys: List<PolyphonyTonalKey>,
    val resolvedKey: PolyphonyTonalKey? = keys.singleOrNull(),
    val role: TonalRegionRole = TonalRegionRole.INSERTED,
) : StoragePluginEvent(), StoragePluginIntervalEvent, StoragePluginForwardAffectingEvent {
    init {
        require(endOnset > onset) { "tonal region must be non-empty" }
        require(keys.isNotEmpty()) { "tonal region must contain at least one key" }
        require(keys.distinct().size == keys.size) { "tonal region keys must be unique" }
        require(resolvedKey == null || resolvedKey in keys) {
            "resolvedKey must be one of the tonal-region candidates"
        }
    }

    val isAmbiguous: Boolean get() = keys.size > 1

    fun contains(time: TimeCode): Boolean = time >= onset && time < endOnset

    companion object {
        const val TRACK_TYPE: String = "mecon.chord_analysis.tonal_regions"

        fun create(
            onset: TimeCode,
            endOnset: TimeCode,
            keys: List<PolyphonyTonalKey>,
            resolvedKey: PolyphonyTonalKey? = keys.singleOrNull(),
            role: TonalRegionRole = TonalRegionRole.INSERTED,
        ): StorageTonalRegionEvent = StorageTonalRegionEvent(
            id = EventId.generate(),
            onset = onset,
            endOnset = endOnset,
            keys = keys,
            resolvedKey = resolvedKey,
            role = role,
        )
    }
}

/**
 * Display-only preferences intentionally live outside score history.
 * Persisted analysis facts are the two storage events above.
 */
object PolyphonyDisplaySettings {
    var isEnabled: Boolean = false
    var showDegreeTrack: Boolean = true
    var showPassingChords: Boolean = true
    var showSelectedDegrees: Boolean = true
}
