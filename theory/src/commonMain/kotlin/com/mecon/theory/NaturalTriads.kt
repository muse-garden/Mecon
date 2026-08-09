package com.mecon.theory

import com.mecon.api.primitive.PitchClass

enum class MinorScaleForm {
    NATURAL,
    HARMONIC,
    MELODIC,
}

enum class MinorAlteration(val signatureDegree: Int) {
    RAISED_4(4),
    RAISED_5(5),
}

data class NaturalTriad(
    val key: Key,
    val degree: Int,
    val signatureDegree: Int,
    val chord: Chord,
    val scaleForms: Set<MinorScaleForm> = emptySet(),
    val minorAlterations: Set<MinorAlteration> = emptySet(),
) {
    val root: PitchClass get() = chord.root
    val quality: ChordQuality get() = chord.quality
    val usesMinorRaised4: Boolean get() = MinorAlteration.RAISED_4 in minorAlterations
    val usesMinorRaised5: Boolean get() = MinorAlteration.RAISED_5 in minorAlterations
}

data class NaturalTriadMatch(
    val key: Key,
    val triad: NaturalTriad,
)

data class NaturalTriadKeySignatureMatch(
    val fifths: Int,
    val interpretation: KeySignatureMode,
    val key: Key,
    val triad: NaturalTriad,
)

object NaturalTriads {
    fun inKey(key: Key): List<NaturalTriad> = when (key.mode) {
        Mode.AEOLIAN -> minorUnionTriads(key)
        else -> triadsFromScale(key, key.mode.semitones(), scaleForm = key.mode.minorScaleForm())
    }

    fun contains(key: Key, chord: Chord): Boolean =
        matches(key, chord).isNotEmpty()

    fun matches(key: Key, chord: Chord): List<NaturalTriad> =
        inKey(key).filter { it.chord.hasSameTriadIdentity(chord) }

    fun matchesPitchClasses(
        key: Key,
        pitchClasses: Collection<PitchClass>,
    ): List<NaturalTriad> {
        val observed = pitchClasses.toSet()
        return inKey(key).filter { it.chord.pitchClasses.toSet() == observed }
    }

    fun matchesPitchClassValues(
        key: Key,
        pitchClasses: Collection<Int>,
    ): List<NaturalTriad> =
        matchesPitchClasses(key, pitchClasses.map { PitchClass(it.mod(12)) })

    fun minorAlterationsUsedBy(key: Key, chord: Chord): Set<MinorAlteration> =
        matches(key, chord).flatMap { it.minorAlterations }.toSet()

    fun usesMinorRaised4(key: Key, chord: Chord): Boolean =
        MinorAlteration.RAISED_4 in minorAlterationsUsedBy(key, chord)

    fun usesMinorRaised5(key: Key, chord: Chord): Boolean =
        MinorAlteration.RAISED_5 in minorAlterationsUsedBy(key, chord)

    fun possibleKeys(
        chord: Chord,
        modes: Collection<Mode> = listOf(Mode.IONIAN, Mode.AEOLIAN),
    ): List<NaturalTriadMatch> =
        modes.flatMap { mode ->
            (0..11).flatMap { root ->
                val key = Key(PitchClass(root), mode)
                matches(key, chord).map { NaturalTriadMatch(key, it) }
            }
        }

    fun possibleKeySignatures(
        chord: Chord,
        interpretations: Collection<KeySignatureMode> = KeySignatureMode.entries,
        fifthsRange: IntRange = -7..7,
    ): List<NaturalTriadKeySignatureMatch> =
        fifthsRange.flatMap { fifths ->
            interpretations.flatMap { interpretation ->
                val key = Key.fromKeySignatureFifths(fifths, interpretation)
                matches(key, chord).map { triad ->
                    NaturalTriadKeySignatureMatch(
                        fifths = fifths,
                        interpretation = interpretation,
                        key = key,
                        triad = triad,
                    )
                }
            }
        }

    private fun minorUnionTriads(key: Key): List<NaturalTriad> {
        val triads = buildList {
            addAll(triadsFromScale(key, NATURAL_MINOR_INTERVALS, MinorScaleForm.NATURAL))
            addAll(triadsFromScale(key, HARMONIC_MINOR_INTERVALS, MinorScaleForm.HARMONIC))
            addAll(triadsFromScale(key, MELODIC_MINOR_INTERVALS, MinorScaleForm.MELODIC))
        }
        return triads
            .groupBy { it.chord.root to it.chord.quality }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    scaleForms = group.flatMap { it.scaleForms }.toSet(),
                    minorAlterations = group.flatMap { it.minorAlterations }.toSet(),
                )
            }
            .sortedWith(
                compareBy<NaturalTriad> { it.degree }
                    .thenBy { it.minorAlterations.size }
                    .thenBy { QUALITY_ORDER[it.quality] ?: Int.MAX_VALUE }
            )
    }

    private fun triadsFromScale(
        key: Key,
        intervals: List<Int>,
        scaleForm: MinorScaleForm?,
    ): List<NaturalTriad> {
        if (intervals.size != 7) return emptyList()
        return (0 until 7).mapNotNull { degreeIndex ->
            val rootSemitones = intervals[degreeIndex]
            val thirdSemitones = intervals[(degreeIndex + 2) % 7] + if (degreeIndex + 2 >= 7) 12 else 0
            val fifthSemitones = intervals[(degreeIndex + 4) % 7] + if (degreeIndex + 4 >= 7) 12 else 0
            val quality = triadQuality(
                third = thirdSemitones - rootSemitones,
                fifth = fifthSemitones - rootSemitones,
            ) ?: return@mapNotNull null
            val chord = Chord(
                root = key.root.transpose(rootSemitones),
                quality = quality,
            )
            NaturalTriad(
                key = key,
                degree = degreeIndex + 1,
                signatureDegree = key.signatureDegreeForModalDegree(degreeIndex + 1),
                chord = chord,
                scaleForms = scaleForm?.let { setOf(it) } ?: emptySet(),
                minorAlterations = minorAlterationsFor(key, chord.pitchClasses),
            )
        }
    }

    private fun triadQuality(third: Int, fifth: Int): ChordQuality? = when (third to fifth) {
        4 to 7 -> ChordQuality.MAJOR
        3 to 7 -> ChordQuality.MINOR
        3 to 6 -> ChordQuality.DIMINISHED
        4 to 8 -> ChordQuality.AUGMENTED
        else -> null
    }

    private fun minorAlterationsFor(
        key: Key,
        pitchClasses: Collection<PitchClass>,
    ): Set<MinorAlteration> {
        if (key.mode.signatureTonicDegree != 6) return emptySet()
        val signatureRoot = KeySignatureMode.MINOR.signatureRootForTonic(key.root)
        val signatureScale = Scale.major(signatureRoot).pitchClasses
        val raisedByAlteration = mapOf(
            MinorAlteration.RAISED_4 to signatureScale[3].transpose(1),
            MinorAlteration.RAISED_5 to signatureScale[4].transpose(1),
        )
        return raisedByAlteration
            .filterValues { it in pitchClasses }
            .keys
            .toSet()
    }

    private fun Key.signatureDegreeForModalDegree(degree: Int): Int {
        val tonicDegree = mode.signatureTonicDegree ?: return degree
        return ((tonicDegree - 1 + degree - 1) % 7) + 1
    }

    private fun Mode.minorScaleForm(): MinorScaleForm? = when (this) {
        Mode.AEOLIAN -> MinorScaleForm.NATURAL
        Mode.HARMONIC_MINOR -> MinorScaleForm.HARMONIC
        Mode.MELODIC_MINOR -> MinorScaleForm.MELODIC
        else -> null
    }

    private fun Chord.hasSameTriadIdentity(other: Chord): Boolean =
        root == other.root && quality == other.quality
}

private val NATURAL_MINOR_INTERVALS = listOf(0, 2, 3, 5, 7, 8, 10)
private val HARMONIC_MINOR_INTERVALS = listOf(0, 2, 3, 5, 7, 8, 11)
private val MELODIC_MINOR_INTERVALS = listOf(0, 2, 3, 5, 7, 9, 11)

private val QUALITY_ORDER = mapOf(
    ChordQuality.MAJOR to 0,
    ChordQuality.MINOR to 1,
    ChordQuality.DIMINISHED to 2,
    ChordQuality.AUGMENTED to 3,
)
