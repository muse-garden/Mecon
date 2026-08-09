package com.mecon.core.musicxml.import

import com.mecon.core.musicxml.model.*
import com.mecon.api.primitive.*
import com.mecon.api.storage.tracks.Clef

/**
 * Stateful context for MusicXML import.
 *
 * Tracks current state during parsing such as:
 * - Current divisions (timing resolution)
 * - Current key/time signatures
 * - Current clefs per staff
 * - Position tracking per voice
 */
class ImportContext {

    /** Current divisions per quarter note (from <attributes>) */
    var divisions: Int = 1
        private set

    /** Current key signature per staff (staff number -> key) */
    private val currentKeys = mutableMapOf<Int, KeySignature>()

    /** Current time signature per staff */
    private val currentTimes = mutableMapOf<Int, TimeSignature>()

    /** Current clef per staff */
    private val currentClefs = mutableMapOf<Int, Clef>()

    /** Number of staves in current part */
    var staves: Int = 1
        private set

    /** Current position per voice (in ticks from start of measure) */
    private val voicePositions = mutableMapOf<Int, Int>()

    /** Default key signature */
    var defaultKeySignature: KeySignature = KeySignature.C_MAJOR
        private set

    /** Default time signature */
    var defaultTimeSignature: TimeSignature = TimeSignature.COMMON
        private set

    /**
     * Update context from MusicXML attributes element.
     */
    fun updateFromAttributes(attributes: MusicXmlAttributes) {
        attributes.divisions?.let { divisions = it }
        attributes.staves.let { staves = it }

        // Update key signatures
        for (key in attributes.keys) {
            val keySignature = convertKey(key)
            val staff = key.staff ?: 0  // 0 = applies to all staves
            if (staff == 0) {
                // Apply to all staves
                for (s in 1..staves) {
                    currentKeys[s] = keySignature
                }
                defaultKeySignature = keySignature
            } else {
                currentKeys[staff] = keySignature
            }
        }

        // Update time signatures
        for (time in attributes.times) {
            val timeSignature = convertTime(time)
            val staff = time.staff ?: 0
            if (staff == 0) {
                for (s in 1..staves) {
                    currentTimes[s] = timeSignature
                }
                defaultTimeSignature = timeSignature
            } else {
                currentTimes[staff] = timeSignature
            }
        }

        // Update clefs
        for (clef in attributes.clefs) {
            val meconClef = convertClef(clef)
            val staff = clef.staff ?: 1
            currentClefs[staff] = meconClef
        }
    }

    /**
     * Get current key signature for a staff.
     */
    fun getKeySignature(staff: Int = 1): KeySignature =
        currentKeys[staff] ?: defaultKeySignature

    /**
     * Get current time signature for a staff.
     */
    fun getTimeSignature(staff: Int = 1): TimeSignature =
        currentTimes[staff] ?: defaultTimeSignature

    /**
     * Get current clef for a staff.
     */
    fun getClef(staff: Int = 1): Clef =
        currentClefs[staff] ?: Clef.TREBLE

    /**
     * Get current position for a voice in ticks.
     */
    fun getVoicePosition(voice: Int): Int = voicePositions[voice] ?: 0

    /**
     * Advance voice position by duration in divisions.
     */
    fun advanceVoice(voice: Int, durationDivisions: Int) {
        val currentPos = voicePositions[voice] ?: 0
        val ticks = divisionsToTicks(durationDivisions)
        voicePositions[voice] = currentPos + ticks
    }

    /**
     * Set voice position (for backup/forward elements).
     */
    fun setVoicePosition(voice: Int, positionTicks: Int) {
        voicePositions[voice] = positionTicks
    }

    /**
     * Reset voice positions for new measure.
     */
    fun resetVoicePositions() {
        voicePositions.clear()
    }

    /**
     * Convert divisions to internal ticks.
     * Mecon uses 4096 ticks per whole note, so quarter = 1024 ticks.
     */
    fun divisionsToTicks(durationDivisions: Int): Int {
        // divisions = divisions per quarter note
        // 1024 ticks = quarter note
        return if (divisions > 0) {
            (durationDivisions * 1024) / divisions
        } else {
            durationDivisions
        }
    }

    /**
     * Convert internal ticks to divisions.
     */
    fun ticksToDivisions(ticks: Int): Int {
        return (ticks * divisions) / 1024
    }

    /**
     * Convert MusicXML key to Mecon KeySignature.
     */
    private fun convertKey(key: MusicXmlKey): KeySignature {
        val fifths = (key.fifths ?: 0).coerceIn(-7, 7)
        val mode = when (key.mode?.lowercase()) {
            "minor" -> Mode.MINOR
            "dorian" -> Mode.DORIAN
            "phrygian" -> Mode.PHRYGIAN
            "lydian" -> Mode.LYDIAN
            "mixolydian" -> Mode.MIXOLYDIAN
            "aeolian" -> Mode.AEOLIAN
            "locrian" -> Mode.LOCRIAN
            else -> Mode.MAJOR
        }

        return when (mode) {
            Mode.MAJOR -> KeySignature.majorByFifths(fifths)
            Mode.MINOR -> KeySignature.minorByFifths(fifths)
            else -> KeySignature(fifthsToPitchClass(fifths, mode), mode)
        }
    }

    /**
     * Convert fifths to root pitch class.
     */
    private fun fifthsToPitchClass(fifths: Int, mode: Mode): PitchClass {
        // Circle of fifths: C=0, G=1, D=2, A=3, E=4, B=5, F#=6, C#=7
        // Negative: F=-1, Bb=-2, Eb=-3, Ab=-4, Db=-5, Gb=-6, Cb=-7
        val majorRoots = mapOf(
            0 to PitchClass.C,
            1 to PitchClass.G,
            2 to PitchClass.D,
            3 to PitchClass.A,
            4 to PitchClass.E,
            5 to PitchClass.B,
            6 to PitchClass(6),   // F#
            7 to PitchClass(1),   // C#
            -1 to PitchClass.F,
            -2 to PitchClass(10), // Bb
            -3 to PitchClass(3),  // Eb
            -4 to PitchClass(8),  // Ab
            -5 to PitchClass(1),  // Db
            -6 to PitchClass(6),  // Gb
            -7 to PitchClass(11)  // Cb
        )

        val majorRoot = majorRoots[fifths.coerceIn(-7, 7)] ?: PitchClass.C

        // For minor keys, the root is 3 semitones below the relative major
        return if (mode == Mode.MINOR || mode == Mode.AEOLIAN) {
            majorRoot.transpose(-3)
        } else {
            majorRoot
        }
    }

    /**
     * Convert MusicXML time to Mecon TimeSignature.
     */
    private fun convertTime(time: MusicXmlTime): TimeSignature {
        if (time.senzaMisura) {
            // Handle senza misura as 4/4 for now
            return TimeSignature.COMMON
        }

        val numerator = time.getNumerator()
        val denominator = time.getDenominator()

        val symbol = when (time.symbol) {
            "common" -> TimeSignatureSymbol.COMMON
            "cut" -> TimeSignatureSymbol.CUT
            else -> null
        }

        return TimeSignature(numerator, denominator, symbol)
    }

    /**
     * Convert MusicXML clef to Mecon Clef.
     */
    private fun convertClef(clef: MusicXmlClef): Clef {
        return when (clef.sign.uppercase()) {
            "G" -> Clef.TREBLE
            "F" -> Clef.BASS
            "C" -> {
                when (clef.line) {
                    3 -> Clef.ALTO
                    4 -> Clef.TENOR
                    else -> Clef.ALTO
                }
            }
            "PERCUSSION" -> Clef.PERCUSSION
            else -> Clef.TREBLE
        }
    }

    /**
     * Reset context for new part.
     */
    fun resetForNewPart() {
        divisions = 1
        staves = 1
        currentKeys.clear()
        currentTimes.clear()
        currentClefs.clear()
        voicePositions.clear()
    }

    companion object {
        /**
         * Convert clef sign and line to Mecon Clef.
         */
        fun clefFromSignAndLine(sign: String, line: Int?): Clef {
            return when (sign.uppercase()) {
                "G" -> Clef.TREBLE
                "F" -> Clef.BASS
                "C" -> {
                    when (line) {
                        3 -> Clef.ALTO
                        4 -> Clef.TENOR
                        else -> Clef.ALTO
                    }
                }
                "PERCUSSION" -> Clef.PERCUSSION
                else -> Clef.TREBLE
            }
        }
    }
}
