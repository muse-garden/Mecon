package com.mecon.theory

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass

enum class ModulationPitchDisplayMode {
    ABSOLUTE,
    RELATIVE,
}

data class ModulationKey(
    val fifths: Int,
    val mode: KeySignatureMode,
) {
    init {
        require(fifths in -7..7) { "Modulation key fifths must be between -7 and 7" }
    }

    val keySignature: KeySignature
        get() = when (mode) {
            KeySignatureMode.MAJOR -> KeySignature.majorByFifths(fifths)
            KeySignatureMode.MINOR -> KeySignature.minorByFifths(fifths)
        }

    val key: Key
        get() = Key.fromKeySignatureFifths(fifths, mode)

    val displayName: String
        get() = keySignature.displayName

    /** One of the twelve sounding positions on the circle; enharmonic spellings share a position. */
    val circlePosition: Int
        get() = fifths.mod(12)

    fun isEnharmonicWith(other: ModulationKey): Boolean =
        mode == other.mode && circlePosition == other.circlePosition

    fun tonalContext(idPrefix: String = "modulation"): TonalContext =
        TonalContext.fromKey(
            key = key,
            tonicSpelling = tonicSpelling(),
            id = TonalContextId("$idPrefix.$fifths.${mode.name.lowercase()}"),
        ).copy(keySignature = NotationalKeySignature(fifths))

    fun tonicSpelling(): SpelledPitchClass {
        val name = displayName
        val noteName = NoteName.valueOf(name.first().uppercase())
        val offset = name.drop(1).fold(0) { total, accidental ->
            total + when (accidental) {
                '#', '♯' -> 1
                'b', '♭' -> -1
                else -> 0
            }
        }
        return SpelledPitchClass(noteName, offset)
    }

    companion object {
        val circleOfFifths: List<ModulationKey> =
            (-7..7).flatMap { fifths ->
                listOf(
                    ModulationKey(fifths, KeySignatureMode.MAJOR),
                    ModulationKey(fifths, KeySignatureMode.MINOR),
                )
            }
    }
}

data class ModulationChordId(
    val root: PitchClass,
    val quality: ChordQuality,
)

data class ModulationChordInterpretation(
    val key: ModulationKey,
    val degree: Int,
    val absoluteTones: List<SpelledPitchClass>,
    val relativeTones: List<String>,
)

data class ModulationChordCandidate(
    val id: ModulationChordId,
    val chord: Chord,
    val interpretations: List<ModulationChordInterpretation>,
)

/** Shared labels for displaying pitches and target-key tonics relative to a reference key. */
object ModulationPitchLabels {
    fun relativePitchLabel(key: ModulationKey, pitchClass: PitchClass): String {
        val displayKey = key.degreeDisplayKey()
        val intervals = displayKey.key.mode.semitones()
        val offset = displayKey.key.root.intervalTo(pitchClass)
        intervals.indexOf(offset).takeIf { it >= 0 }?.let { return (it + 1).toString() }

        val candidates = intervals.flatMapIndexed { index, semitones ->
            listOf(
                RelativeCandidate(index + 1, semitoneDistance(semitones, offset), true),
                RelativeCandidate(index + 1, semitoneDistance(offset, semitones), false),
            )
        }
        val best = candidates.minWith(compareBy<RelativeCandidate> { it.distance }.thenBy { it.degree })
        val accidental = if (best.raised) "♯" else "♭"
        return accidental.repeat(best.distance.coerceAtLeast(1)) + best.degree
    }

    fun relativePitchLabel(key: ModulationKey, pitch: SpelledPitchClass): String {
        val displayKey = key.degreeDisplayKey()
        val tonic = displayKey.tonicSpelling()
        val degree = (pitch.noteName.ordinal - tonic.noteName.ordinal).mod(7) + 1
        val expected = displayKey.tonalContext("relative-label").spellDegree(degree)
        val alteration = centeredDelta(expected.pitchClass.value, pitch.pitchClass.value)
        val prefix = when {
            alteration > 0 -> "♯".repeat(alteration)
            alteration < 0 -> "♭".repeat(-alteration)
            else -> ""
        }
        return "$prefix$degree"
    }

    fun relativeTonicLabel(
        referenceKey: ModulationKey,
        targetKey: ModulationKey,
    ): String = relativePitchLabel(referenceKey, targetKey.tonicSpelling())

    private fun semitoneDistance(from: Int, to: Int): Int =
        (to - from).mod(12)

    private fun centeredDelta(natural: Int, target: Int): Int {
        val delta = (target - natural).mod(12)
        return if (delta > 6) delta - 12 else delta
    }

    /** Minor-key degree labels are written against the relative major (6, 7, 1, 2, 3, 4, 5). */
    private fun ModulationKey.degreeDisplayKey(): ModulationKey =
        if (mode == KeySignatureMode.MINOR) copy(mode = KeySignatureMode.MAJOR) else this

    private data class RelativeCandidate(
        val degree: Int,
        val distance: Int,
        val raised: Boolean,
    )
}

/** Distance utilities for the shared circle-of-fifths coordinate used by major and relative-minor keys. */
object ModulationCircleOfFifths {
    fun position(key: ModulationKey): Int = key.circlePosition

    fun areEnharmonic(
        first: ModulationKey,
        second: ModulationKey,
    ): Boolean = first.isEnharmonicWith(second)

    fun signedDistance(
        referenceKey: ModulationKey,
        targetKey: ModulationKey,
    ): Int {
        val rawDistance = targetKey.fifths - referenceKey.fifths
        val clockwiseDistance = (position(targetKey) - position(referenceKey)).mod(12)
        return when {
            clockwiseDistance < 6 -> clockwiseDistance
            clockwiseDistance > 6 -> clockwiseDistance - 12
            rawDistance < 0 -> -6
            else -> 6
        }
    }

    fun signedDistanceLabel(
        referenceKey: ModulationKey,
        targetKey: ModulationKey,
    ): String {
        val distance = signedDistance(referenceKey, targetKey)
        return if (distance > 0) "+$distance" else distance.toString()
    }
}

object ModulationCommonChordCatalog {
    fun commonChords(
        source: ModulationKey,
        target: ModulationKey,
    ): List<ModulationChordCandidate> = commonChords(listOf(source, target))

    fun commonChords(keys: Collection<ModulationKey>): List<ModulationChordCandidate> {
        val distinctKeys = keys.distinct()
        if (distinctKeys.isEmpty()) return emptyList()
        val triadsByKey = distinctKeys.associateWith { key ->
            NaturalTriads.inKey(key.key).associateBy { it.toModulationChordId() }
        }
        val commonIds = triadsByKey.values
            .map { it.keys }
            .reduce(Set<ModulationChordId>::intersect)
        return commonIds
            .map { id ->
                val chord = triadsByKey.getValue(distinctKeys.first()).getValue(id).chord
                ModulationChordCandidate(
                    id = id,
                    chord = chord,
                    interpretations = distinctKeys.map { key ->
                        interpretation(key, triadsByKey.getValue(key).getValue(id))
                    },
                )
            }
            .sortedWith(
                compareBy<ModulationChordCandidate> { candidate ->
                    distinctKeys.first().key.root.intervalTo(candidate.id.root)
                }.thenBy { it.id.quality.ordinal }
            )
    }

    fun keysContaining(
        chordIds: Collection<ModulationChordId>,
        keys: Collection<ModulationKey> = ModulationKey.circleOfFifths,
    ): List<ModulationKey> {
        val required = chordIds.toSet()
        if (required.isEmpty()) return keys.distinct()
        return keys.distinct().filter { key ->
            val available = NaturalTriads.inKey(key.key).mapTo(hashSetOf()) { it.toModulationChordId() }
            required.all { it in available }
        }
    }

    fun nextKeys(
        source: ModulationKey,
        pivotChordId: ModulationChordId,
        keys: Collection<ModulationKey> = ModulationKey.circleOfFifths,
    ): List<ModulationKey> =
        keysContaining(listOf(pivotChordId), keys)
            .filter { it != source }
            .sortedWith(
                compareBy<ModulationKey> {
                    kotlin.math.abs(ModulationCircleOfFifths.signedDistance(source, it))
                }.thenBy {
                    kotlin.math.abs(it.fifths - source.fifths)
                }.thenBy { it.mode.ordinal }
            )

    fun transitionsFrom(
        source: ModulationKey,
        keys: Collection<ModulationKey> = ModulationKey.circleOfFifths,
    ): List<Pair<ModulationKey, List<ModulationChordCandidate>>> =
        keys.distinct()
            .asSequence()
            .filter { it != source }
            .map { it to commonChords(source, it) }
            .filter { (_, chords) -> chords.isNotEmpty() }
            .toList()

    fun relativePitchLabel(key: ModulationKey, pitchClass: PitchClass): String =
        ModulationPitchLabels.relativePitchLabel(key, pitchClass)

    fun relativePitchLabel(key: ModulationKey, pitch: SpelledPitchClass): String =
        ModulationPitchLabels.relativePitchLabel(key, pitch)

    fun spellChordTone(
        key: ModulationKey,
        degree: Int,
        pitchClass: PitchClass,
    ): SpelledPitchClass {
        val tonic = key.tonicSpelling()
        val noteName = NoteName.fromIndex(tonic.noteName.ordinal + degree - 1)
        return SpelledPitchClass(
            noteName = noteName,
            chromaticOffset = centeredDelta(noteName.toSemitone(), pitchClass.value),
        )
    }

    private fun interpretation(
        key: ModulationKey,
        triad: NaturalTriad,
    ): ModulationChordInterpretation {
        val tones = triad.chord.pitchClasses
        val absoluteTones = tones.mapIndexed { memberIndex, pitchClass ->
            spellChordTone(
                key = key,
                degree = ((triad.degree - 1 + memberIndex * 2) % 7) + 1,
                pitchClass = pitchClass,
            )
        }
        return ModulationChordInterpretation(
            key = key,
            degree = triad.degree,
            absoluteTones = absoluteTones,
            relativeTones = absoluteTones.map { ModulationPitchLabels.relativePitchLabel(key, it) },
        )
    }

    private fun NaturalTriad.toModulationChordId(): ModulationChordId =
        ModulationChordId(chord.root, chord.quality)

    private fun centeredDelta(natural: Int, target: Int): Int {
        val delta = (target - natural).mod(12)
        return if (delta > 6) delta - 12 else delta
    }
}
