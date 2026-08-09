package com.mecon.renderer.layout.stem

import com.mecon.renderer.enums.StemDirection
import kotlinx.serialization.Serializable

/**
 * Voice stem direction configuration.
 *
 * Defines default stem directions based on voice number.
 * By convention, voice 1 (melody) stems up, voice 2 (accompaniment) stems down.
 */
@Serializable
data class VoiceStemConfig(
    /** Default direction for voice 1 */
    val voice1Default: StemDirection = StemDirection.UP,
    /** Default direction for voice 2 */
    val voice2Default: StemDirection = StemDirection.DOWN,
    /** Whether to alternate directions for additional voices */
    val alternateVoices: Boolean = true
) {
    /**
     * Get the default stem direction for a voice number.
     *
     * @param voiceNumber Voice number (1-based)
     * @return Default stem direction for that voice
     */
    fun defaultForVoice(voiceNumber: Int): StemDirection {
        return when (voiceNumber) {
            1 -> voice1Default
            2 -> voice2Default
            else -> if (alternateVoices) {
                // Odd voices up, even voices down
                if (voiceNumber % 2 == 1) StemDirection.UP else StemDirection.DOWN
            } else {
                voice1Default
            }
        }
    }

    companion object {
        val DEFAULT = VoiceStemConfig()
    }
}
