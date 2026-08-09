package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass

enum class ChordTone {
    ROOT,
    THIRD,
    FIFTH,
    SEVENTH,
    SECOND,
    FOURTH,
}

data class MissingChordTone(
    val tone: ChordTone,
    val pitchClass: PitchClass,
)

data class EnharmonicSubstitution(
    val tone: ChordTone,
    val written: Pitch,
    val expected: Pitch,
)

data class ChordRecognitionCandidate(
    val chord: Chord,
    val presentTones: Set<ChordTone>,
    val missingTones: List<MissingChordTone>,
    val enharmonicSubstitutions: List<EnharmonicSubstitution> = emptyList(),
) {
    val complete: Boolean get() = missingTones.isEmpty()
}

object ChordRecognizer {
    fun recognize(pitches: List<Pitch>): List<ChordRecognitionCandidate> =
        recognizePitchClasses(
            pitchClasses = pitches.map { it.pitchClass.value },
            bass = pitches.minByOrNull { it.midiNumber }?.pitchClass?.value,
        ).map { it.withEnharmonicSubstitutions(pitches) }

    fun recognizePitchClasses(
        pitchClasses: Collection<Int>,
        bass: Int? = null,
    ): List<ChordRecognitionCandidate> {
        val observed = pitchClasses.map { it.mod(12) }.toSet()
        if (observed.size < 2) return emptyList()

        val exact = recognizeExact(observed, bass)
        if (exact.isNotEmpty()) return exact

        return recognizePartial(observed, bass)
    }

    private fun recognizeExact(
        observed: Set<Int>,
        bass: Int?,
    ): List<ChordRecognitionCandidate> {
        val candidates = buildList {
            for (root in observed.sorted()) {
                for (template in EXACT_TEMPLATES) {
                    val pcs = template.pitchClasses(root)
                    if (pcs == observed) {
                        add(template.candidate(root, observed, bass, missing = false))
                    }
                }
            }
        }
        return candidates.preferSymmetricRoot(bass)
    }

    private fun recognizePartial(
        observed: Set<Int>,
        bass: Int?,
    ): List<ChordRecognitionCandidate> {
        val candidates = buildList {
            for (root in observed.sorted()) {
                for (template in PARTIAL_TEMPLATES) {
                    val pcs = template.pitchClasses(root)
                    if (observed.all { it in pcs }) {
                        val candidate = template.candidate(root, observed, bass, missing = true)
                        if (candidate.missingTones.isNotEmpty()) add(candidate)
                    }
                }
            }
        }
        val bestMissingCount = candidates.minOfOrNull { it.missingTones.size } ?: return emptyList()
        return candidates.filter { it.missingTones.size == bestMissingCount }.preferSymmetricRoot(bass)
    }
}

private data class ToneInterval(val tone: ChordTone, val semitones: Int)

private data class ChordTemplate(
    val quality: ChordQuality,
    val tones: List<ToneInterval>,
) {
    fun pitchClasses(root: Int): Set<Int> =
        tones.map { (root + it.semitones).mod(12) }.toSet()

    fun candidate(
        root: Int,
        observed: Set<Int>,
        bass: Int?,
        missing: Boolean,
    ): ChordRecognitionCandidate {
        val present = tones
            .filter { (root + it.semitones).mod(12) in observed }
            .map { it.tone }
            .toSet()
        val missingTones = if (!missing) emptyList() else tones
            .filter { (root + it.semitones).mod(12) !in observed }
            .map { MissingChordTone(it.tone, PitchClass((root + it.semitones).mod(12))) }
        return ChordRecognitionCandidate(
            chord = Chord(
                root = PitchClass(root),
                quality = quality,
                bass = bass
                    ?.mod(12)
                    ?.takeIf { it != root && it in observed }
                    ?.let { PitchClass(it) },
            ),
            presentTones = present,
            missingTones = missingTones,
        )
    }
}

private fun List<ChordRecognitionCandidate>.preferSymmetricRoot(bass: Int?): List<ChordRecognitionCandidate> {
    val symmetricQualities = setOf(ChordQuality.DIMINISHED7, ChordQuality.AUGMENTED)
    val symmetric = filter { it.chord.quality in symmetricQualities }
    if (symmetric.isEmpty()) return this
    val preferred = bass?.mod(12)?.let { bassPc ->
        symmetric.firstOrNull { it.chord.root.value == bassPc }
    } ?: symmetric.first()
    return filter { it.chord.quality !in symmetricQualities } + preferred
}

private fun ChordRecognitionCandidate.withEnharmonicSubstitutions(
    pitches: List<Pitch>,
): ChordRecognitionCandidate {
    if (chord.quality == ChordQuality.DIMINISHED7 || chord.quality == ChordQuality.AUGMENTED) return this
    val template = EXACT_TEMPLATES.firstOrNull { it.quality == chord.quality } ?: return this
    val substitutions = template.bestEnharmonicSubstitutions(chord.root, pitches)
    return copy(enharmonicSubstitutions = substitutions)
}

private fun ChordTemplate.bestEnharmonicSubstitutions(
    root: PitchClass,
    pitches: List<Pitch>,
): List<EnharmonicSubstitution> {
    val rootOctave = pitches.firstOrNull { it.pitchClass == root }?.octave
        ?: pitches.minByOrNull { it.midiNumber }?.octave
        ?: 4
    return rootSpellings(root, rootOctave)
        .map { rootPitch -> substitutionsForRoot(rootPitch, pitches) }
        .minWithOrNull(
            compareBy<List<EnharmonicSubstitution>> { it.size }
                .thenBy { substitutions -> substitutions.sumOf { kotlin.math.abs(it.expected.chromaticOffset) } }
        )
        ?: emptyList()
}

private fun ChordTemplate.substitutionsForRoot(
    rootPitch: Pitch,
    pitches: List<Pitch>,
): List<EnharmonicSubstitution> =
    tones.mapNotNull { tone ->
        val pitchClass = PitchClass((rootPitch.pitchClass.value + tone.semitones).mod(12))
        val written = pitches.firstOrNull { it.pitchClass == pitchClass } ?: return@mapNotNull null
        val expected = expectedPitch(rootPitch, tone, written.octave)
        if (written.noteName == expected.noteName && written.chromaticOffset == expected.chromaticOffset) {
            null
        } else {
            EnharmonicSubstitution(
                tone = tone.tone,
                written = written,
                expected = expected,
            )
        }
    }

private fun rootSpellings(root: PitchClass, octave: Int): List<Pitch> =
    (0..6).mapNotNull { noteIndex ->
        val diatonicSteps = (octave - 4) * 7 + noteIndex
        val natural = Pitch(diatonicSteps, 0)
        val accidental = (root.value - natural.pitchClass.value).floorModNearZero()
        if (accidental in -2..2) Pitch(diatonicSteps, accidental) else null
    }

private fun Int.floorModNearZero(): Int {
    val normalized = mod(12)
    return if (normalized > 6) normalized - 12 else normalized
}

private fun expectedPitch(root: Pitch, tone: ToneInterval, octaveHint: Int): Pitch {
    val diatonicOffset = when (tone.tone) {
        ChordTone.ROOT -> 0
        ChordTone.SECOND -> 1
        ChordTone.THIRD -> 2
        ChordTone.FOURTH -> 3
        ChordTone.FIFTH -> 4
        ChordTone.SEVENTH -> 6
    }
    val diatonicSteps = root.diatonicSteps + diatonicOffset
    val naturalMidi = Pitch(diatonicSteps, 0).midiNumber
    val rootMidi = root.midiNumber
    val targetMidiNearRoot = rootMidi + tone.semitones
    val octaveAdjustedTarget = targetMidiNearRoot + ((octaveHint - Pitch(diatonicSteps, 0).octave) * 12)
    val accidental = octaveAdjustedTarget - naturalMidi
    return Pitch(diatonicSteps + (octaveHint - Pitch(diatonicSteps, 0).octave) * 7, accidental)
}

private val EXACT_TEMPLATES = listOf(
    ChordTemplate(ChordQuality.MAJOR, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 4),
        ToneInterval(ChordTone.FIFTH, 7),
    )),
    ChordTemplate(ChordQuality.MINOR, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 7),
    )),
    ChordTemplate(ChordQuality.DIMINISHED, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 6),
    )),
    ChordTemplate(ChordQuality.AUGMENTED, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 4),
        ToneInterval(ChordTone.FIFTH, 8),
    )),
    ChordTemplate(ChordQuality.SUS2, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.SECOND, 2),
        ToneInterval(ChordTone.FIFTH, 7),
    )),
    ChordTemplate(ChordQuality.SUS4, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.FOURTH, 5),
        ToneInterval(ChordTone.FIFTH, 7),
    )),
    ChordTemplate(ChordQuality.DOMINANT7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 4),
        ToneInterval(ChordTone.FIFTH, 7),
        ToneInterval(ChordTone.SEVENTH, 10),
    )),
    ChordTemplate(ChordQuality.MAJOR7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 4),
        ToneInterval(ChordTone.FIFTH, 7),
        ToneInterval(ChordTone.SEVENTH, 11),
    )),
    ChordTemplate(ChordQuality.MINOR7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 7),
        ToneInterval(ChordTone.SEVENTH, 10),
    )),
    ChordTemplate(ChordQuality.HALF_DIMINISHED7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 6),
        ToneInterval(ChordTone.SEVENTH, 10),
    )),
    ChordTemplate(ChordQuality.DIMINISHED7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 6),
        ToneInterval(ChordTone.SEVENTH, 9),
    )),
    ChordTemplate(ChordQuality.MINOR_MAJOR7, listOf(
        ToneInterval(ChordTone.ROOT, 0),
        ToneInterval(ChordTone.THIRD, 3),
        ToneInterval(ChordTone.FIFTH, 7),
        ToneInterval(ChordTone.SEVENTH, 11),
    )),
)

private val PARTIAL_TEMPLATES = EXACT_TEMPLATES.filter { template ->
    template.tones.any { it.tone == ChordTone.THIRD } &&
        template.tones.any { it.tone == ChordTone.FIFTH && it.semitones == 7 }
}
