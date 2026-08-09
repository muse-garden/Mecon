package com.mecon.core.musicxml.import

import com.mecon.core.musicxml.model.MusicXmlPitch
import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch

/**
 * Converts between MusicXML pitch representation and Mecon Pitch.
 *
 * MusicXML uses:
 * - step: C, D, E, F, G, A, B
 * - alter: -2 to +2 (chromatic alteration)
 * - octave: 0-9 (where 4 = the octave containing middle C)
 *
 * Mecon uses:
 * - diatonicSteps: position relative to C4 (0 = C4, 7 = C5, -7 = C3)
 * - chromaticOffset: accidental offset (-2 to +2)
 */
object PitchConverter {

    private val STEP_TO_INDEX = mapOf(
        "C" to 0, "D" to 1, "E" to 2, "F" to 3,
        "G" to 4, "A" to 5, "B" to 6
    )

    private val INDEX_TO_STEP = arrayOf("C", "D", "E", "F", "G", "A", "B")

    /**
     * Convert MusicXML pitch to Mecon Pitch.
     *
     * Formula:
     * diatonicSteps = (octave - 4) * 7 + stepIndex
     * chromaticOffset = alter
     */
    fun fromMusicXml(xmlPitch: MusicXmlPitch): Pitch {
        val stepIndex = STEP_TO_INDEX[xmlPitch.step.uppercase()]
            ?: throw IllegalArgumentException("Invalid step: ${xmlPitch.step}")

        val diatonicSteps = (xmlPitch.octave - 4) * 7 + stepIndex
        return Pitch(diatonicSteps, xmlPitch.alter)
    }

    /**
     * Convert Mecon Pitch to MusicXML pitch.
     *
     * Formula:
     * step = INDEX_TO_STEP[diatonicSteps mod 7]
     * octave = 4 + floor(diatonicSteps / 7)
     * alter = chromaticOffset
     */
    fun toMusicXml(pitch: Pitch): MusicXmlPitch {
        val stepIndex = pitch.diatonicSteps.mod(7)
        val octave = 4 + floorDiv(pitch.diatonicSteps, 7)
        val step = INDEX_TO_STEP[stepIndex]

        return MusicXmlPitch(
            step = step,
            alter = pitch.chromaticOffset,
            octave = octave
        )
    }

    /**
     * Convert a list of MusicXML pitches to Mecon pitches.
     */
    fun fromMusicXmlList(xmlPitches: List<MusicXmlPitch>): List<Pitch> =
        xmlPitches.map { fromMusicXml(it) }

    /**
     * Convert a list of Mecon pitches to MusicXML pitches.
     */
    fun toMusicXmlList(pitches: List<Pitch>): List<MusicXmlPitch> =
        pitches.map { toMusicXml(it) }

    // Helper for integer division that rounds toward negative infinity
    private fun floorDiv(a: Int, b: Int): Int {
        val r = a / b
        return if ((a xor b) < 0 && r * b != a) r - 1 else r
    }
}
