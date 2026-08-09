package com.mecon.renderer.layout.stem

import com.mecon.renderer.enums.StemDirection
import kotlinx.serialization.Serializable

/**
 * Beam layout configuration (reference: LilyPond parameters).
 *
 * These parameters control how beams are positioned and how stems
 * are calculated for beamed note groups.
 */
@Serializable
data class BeamLayoutConfig(
    /** Beam thickness in staff spaces */
    val beamThickness: Float = 0.48f,

    /** Slope damping coefficient (0 = flat, 1 = full slope) */
    val damping: Float = 0.6f,

    /** Threshold below which slope is rounded to zero */
    val roundToZeroSlope: Float = 0.02f,

    /** Note gap above which a kneed beam is created (staff spaces) */
    val autoKneeGap: Float = 5.5f,

    /** Ideal stem lengths by beam count (index 0 = 1 beam, etc.) */
    val beamedLengths: List<Float> = listOf(3.26f, 3.5f, 3.6f),

    /** Minimum free lengths from chord to beam */
    val beamedMinimumFreeLengths: List<Float> = listOf(1.83f, 1.5f, 1.25f),

    /** Default direction when note is on middle line */
    val neutralDirection: StemDirection = StemDirection.DOWN
) {
    /**
     * Get ideal stem length for the given beam count.
     *
     * @param beamCount Number of beams (1 = eighth notes, 2 = sixteenth, etc.)
     * @return Ideal stem length in staff spaces
     */
    fun idealStemLength(beamCount: Int): Float {
        // beamCount 1 -> index 0
        val index = (beamCount - 1).coerceAtLeast(0)
        return beamedLengths.getOrElse(index) { beamedLengths.lastOrNull() ?: 3.5f }
    }

    /**
     * Get minimum free length for the given beam count.
     *
     * @param beamCount Number of beams
     * @return Minimum free length from chord to beam
     */
    fun minimumFreeLength(beamCount: Int): Float {
        val index = (beamCount - 1).coerceAtLeast(0)
        return beamedMinimumFreeLengths.getOrElse(index) {
            beamedMinimumFreeLengths.lastOrNull() ?: 1.25f
        }
    }

    companion object {
        val DEFAULT = BeamLayoutConfig()
    }
}
