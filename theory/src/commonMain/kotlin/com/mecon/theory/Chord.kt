package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass

enum class ChordQuality {
    MAJOR, MINOR, DIMINISHED, AUGMENTED, SUS2, SUS4,
    MAJOR7, MINOR7, DOMINANT7, DIMINISHED7, HALF_DIMINISHED7,
    MINOR_MAJOR7, AUGMENTED7,
    ADD9, ADD11, MAJOR9, MINOR9, DOMINANT9,
    MAJOR11, MINOR11, DOMINANT11,
    MAJOR13, MINOR13, DOMINANT13,
    DOMINANT7_FLAT5, DOMINANT7_SHARP5, DOMINANT7_FLAT9, DOMINANT7_SHARP9, DOMINANT7_SHARP11, ALTERED,
    /** Compatibility value for an open [ChordDefinition] without a legacy quality name. */
    CUSTOM,
}

/** Minimal capability consumed by harmony targets and non-chord-tone analysis. */
interface Sonority {
    val root: PitchClass
    val pitchClasses: List<PitchClass>
    fun contains(pitch: Pitch): Boolean = contains(pitch.pitchClass)
    fun contains(pitchClass: PitchClass): Boolean = pitchClass in pitchClasses
}

data class Chord(
    override val root: PitchClass,
    val quality: ChordQuality,
    val bass: PitchClass? = null
) : Sonority {
    override val pitchClasses: List<PitchClass>
        get() {
            val intervals = BuiltInChordDefinitions.forQuality(quality).members.map { it.semitones }
            val pcs = intervals.map { root.transpose(it) }
            if (bass != null && pcs.contains(bass)) {
                val index = pcs.indexOf(bass)
                return pcs.drop(index) + pcs.take(index)
            }
            return pcs
        }

    companion object {
        fun create(root: PitchClass, quality: ChordQuality) = Chord(root, quality)
    }
}
