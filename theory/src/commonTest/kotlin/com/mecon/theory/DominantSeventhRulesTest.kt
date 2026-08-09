package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.constraint.evaluateNamedSeventhPreparations
import com.mecon.theory.constraint.evaluateNamedV7ResolutionConstraints
import com.mecon.theory.textbook.DominantSeventhConnection
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import kotlin.test.Test
import kotlin.test.assertTrue

class DominantSeventhRulesTest {
    @Test
    fun flagsSeventhResolutionDirectionAndRootV7ToOmittedFifthTonic() {
        val key = Key.major(PitchClass.C)
        val dominant = DominantSeventhRules.dominantSeventhInKey(key)
        val tonic = DominantSeventhRules.tonicTriadInKey(key)

        val findings = DominantSeventhRules.checkTransition(
            transition = FixedVoiceTransition(
                previous = verticality(0, "B4", "F4", "D3", "G2"),
                current = verticality(1, "C5", "E4", "C4", "C3"),
            ),
            connection = DominantSeventhConnection(
                key = key,
                before = dominant,
                beforePosition = TextbookSeventhPosition.ROOT_POSITION,
                after = tonic,
                afterPosition = TextbookSeventhPosition.ROOT_POSITION,
            ),
        )

        assertTrue(findings.any { it.ruleId == DominantSeventhRules.SEVENTH_RESOLVES_DOWN && it.kind == RuleFindingKind.INDICATION })
        assertTrue(findings.any { it.ruleId == DominantSeventhRules.OUTER_LEADING_TONE_RESOLUTION && it.kind == RuleFindingKind.INDICATION })
        val namedResolutionFindings = evaluateNamedV7ResolutionConstraints(
            frames = listOf(
                frame(0, TextbookSeventhTarget(dominant, TextbookSeventhPosition.ROOT_POSITION), "B4", "F4", "D3", "G2"),
                frame(1, TextbookSeventhTarget(tonic, TextbookSeventhPosition.ROOT_POSITION), "C5", "E4", "C4", "C3"),
            ),
            voices = voices,
        )
        assertTrue(namedResolutionFindings.any { it.ruleId == DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH })
        val ascendingSeventh = DominantSeventhRules.checkTransition(
            transition = FixedVoiceTransition(
                previous = verticality(0, "B4", "F4", "D3", "G2"),
                current = verticality(1, "C5", "G4", "E3", "C3"),
            ),
            connection = DominantSeventhConnection(
                key = key,
                before = dominant,
                beforePosition = TextbookSeventhPosition.ROOT_POSITION,
                after = tonic,
                afterPosition = TextbookSeventhPosition.ROOT_POSITION,
            ),
        )

        assertTrue(ascendingSeventh.any { it.ruleId == DominantSeventhRules.SEVENTH_ASCENDS })
    }

    @Test
    fun recognizesAllNamedRootV7ToTonicShapes() {
        val key = Key.major(PitchClass.C)
        val dominant = DominantSeventhRules.dominantSeventhInKey(key)
        val tonic = DominantSeventhRules.tonicTriadInKey(key)
        fun evaluate(before: List<String>, after: List<String>) = evaluateNamedV7ResolutionConstraints(
            frames = listOf(
                frame(0, TextbookSeventhTarget(dominant, TextbookSeventhPosition.ROOT_POSITION), before[0], before[1], before[2], before[3]),
                frame(1, TextbookSeventhTarget(tonic, TextbookSeventhPosition.ROOT_POSITION), after[0], after[1], after[2], after[3]),
            ),
            voices = voices,
        )

        val omittedFifth = evaluate(listOf("B4", "F4", "D3", "G2"), listOf("C5", "E4", "C4", "C3"))
        val incompleteToComplete = evaluate(listOf("B4", "F4", "G3", "G2"), listOf("C5", "G4", "E3", "C3"))
        val innerLeadingTone = evaluate(listOf("F5", "B4", "D3", "G2"), listOf("C5", "G4", "E3", "C3"))

        assertTrue(omittedFifth.any { it.ruleId == DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH })
        assertTrue(incompleteToComplete.any { it.ruleId == DominantSeventhRules.INCOMPLETE_V7_TO_COMPLETE_I })
        assertTrue(innerLeadingTone.any { it.ruleId == DominantSeventhRules.INNER_LEADING_TONE_COMPLETE_I })
    }
    @Test
    fun flagsCompleteTonicParallelFifthComparison() {
        val key = Key.major(PitchClass.C)
        val dominant = DominantSeventhRules.dominantSeventhInKey(key)
        val tonic = DominantSeventhRules.tonicTriadInKey(key)

        val findings = DominantSeventhRules.checkTransition(
            transition = FixedVoiceTransition(
                previous = verticality(0, "B4", "F4", "D3", "G2"),
                current = verticality(1, "C5", "E4", "G3", "C3"),
            ),
            connection = DominantSeventhConnection(
                key = key,
                before = dominant,
                beforePosition = TextbookSeventhPosition.ROOT_POSITION,
                after = tonic,
                afterPosition = TextbookSeventhPosition.ROOT_POSITION,
            ),
        )

        assertTrue(findings.any { it.ruleId == DominantSeventhRules.ROOT_V7_COMPLETE_I_PARALLEL_FIFTH })
    }

    @Test
    fun recognizesSeventhPreparationTogetherWithResolution() {
        val key = Key.major(PitchClass.C)
        val dominant = DominantSeventhRules.dominantSeventhInKey(key)
        val tonic = DominantSeventhRules.tonicTriadInKey(key)
        val voices = standardFourPartWritingVoices()
        val findings = evaluateNamedSeventhPreparations(
            frames = listOf(
                frame(0, TextbookSeventhTarget(tonic, TextbookSeventhPosition.ROOT_POSITION), "C5", "G4", "E3", "C3"),
                frame(1, TextbookSeventhTarget(dominant, TextbookSeventhPosition.ROOT_POSITION), "B4", "F4", "D3", "G2"),
                frame(2, TextbookSeventhTarget(tonic, TextbookSeventhPosition.ROOT_POSITION), "C5", "E4", "C4", "C3"),
            ),
            voices = voices,
        )

        assertTrue(findings.any { it.ruleId == DominantSeventhRules.PREPARATION_PASSING })
    }

    @Test
    fun recognizesSupertonicAndLeadingSeventhResolutions() {
        val key = Key.major(PitchClass.C)
        val supertonic = DominantSeventhRules.seventhChordInKey(key, 2)
        val dominant = DominantSeventhRules.dominantSeventhInKey(key)
        val leading = DominantSeventhRules.leadingSeventhInKey(key)
        val tonic = DominantSeventhRules.tonicTriadInKey(key)

        val supertonicFindings = DominantSeventhRules.checkTransition(
            transition = FixedVoiceTransition(
                previous = verticality(0, "C5", "A4", "F3", "D3"),
                current = verticality(1, "B4", "G4", "D3", "G2"),
            ),
            connection = DominantSeventhConnection(
                key = key,
                before = supertonic,
                beforePosition = TextbookSeventhPosition.ROOT_POSITION,
                after = dominant,
                afterPosition = TextbookSeventhPosition.ROOT_POSITION,
            ),
        )
        assertTrue(supertonicFindings.any { it.ruleId == DominantSeventhRules.SUPERTONIC_TO_DOMINANT })

        val leadingFindings = DominantSeventhRules.checkTransition(
            transition = FixedVoiceTransition(
                previous = verticality(0, "B4", "A4", "F3", "B2"),
                current = verticality(1, "C5", "E4", "E3", "C3"),
            ),
            connection = DominantSeventhConnection(
                key = key,
                before = leading,
                beforePosition = TextbookSeventhPosition.ROOT_POSITION,
                after = tonic,
                afterPosition = TextbookSeventhPosition.ROOT_POSITION,
            ),
        )
        assertTrue(leadingFindings.any { it.ruleId == DominantSeventhRules.LEADING_TO_TONIC })
        assertTrue(leadingFindings.any { it.ruleId == DominantSeventhRules.LEADING_TONIC_DOUBLES_THIRD })
    }

    @Test
    fun recognizesLeadingSeventhQualitiesByMode() {
        val major = Key.major(PitchClass.C)
        val minor = Key.minor(PitchClass.A)

        val majorFindings = DominantSeventhRules.checkVerticality(
            verticality = verticality(0, "B4", "A4", "F3", "B2"),
            target = TextbookSeventhTarget(DominantSeventhRules.leadingSeventhInKey(major), TextbookSeventhPosition.ROOT_POSITION),
            key = major,
        )
        assertTrue(majorFindings.any { it.ruleId == DominantSeventhRules.MAJOR_LEADING_HALF_DIMINISHED && it.kind == RuleFindingKind.INDICATION })

        val minorFindings = DominantSeventhRules.checkVerticality(
            verticality = verticality(0, "G#4", "F4", "D3", "G#2"),
            target = TextbookSeventhTarget(DominantSeventhRules.leadingSeventhInKey(minor), TextbookSeventhPosition.ROOT_POSITION),
            key = minor,
        )
        assertTrue(minorFindings.any { it.ruleId == DominantSeventhRules.MINOR_LEADING_DIMINISHED && it.kind == RuleFindingKind.INDICATION })
    }

    @Test
    fun recognizesCircleOfFifthsInversionAlternation() {
        val key = Key.major(PitchClass.C)
        val frames = listOf(4, 7, 3, 6, 2, 5).mapIndexed { index, degree ->
            val position = if (index % 2 == 0) TextbookSeventhPosition.FIRST_INVERSION else TextbookSeventhPosition.THIRD_INVERSION
            generatedFrame(index, TextbookSeventhTarget(DominantSeventhRules.seventhChordInKey(key, degree), position))
        } + generatedFrame(
            6,
            TextbookSeventhTarget(DominantSeventhRules.tonicTriadInKey(key), TextbookSeventhPosition.ROOT_POSITION),
        )

        val findings = DominantSeventhRules.checkCircleOfFifthsSequence(frames, voices)
        assertTrue(findings.any { it.ruleId == DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS })
        assertTrue(findings.any { it.ruleId == DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION })
    }

    private fun verticality(
        slotIndex: Int,
        soprano: String,
        alto: String,
        tenor: String,
        bass: String,
    ): FixedVoiceVerticality =
        frame(
            slotIndex = slotIndex,
            target = TextbookSeventhTarget(
                DominantSeventhRules.dominantSeventhInKey(Key.major(PitchClass.C)),
                TextbookSeventhPosition.ROOT_POSITION,
            ),
            soprano = soprano,
            alto = alto,
            tenor = tenor,
            bass = bass,
        ).toVerticality(voices)

    private fun frame(
        slotIndex: Int,
        target: TextbookSeventhTarget,
        soprano: String,
        alto: String,
        tenor: String,
        bass: String,
    ): FixedVoiceWritingFrame<TextbookSeventhTarget> =
        FixedVoiceWritingFrame(
            slotIndex = slotIndex,
            target = target,
            pitchesByVoiceId = mapOf(
                voices[0].id to Pitch.fromName(soprano),
                voices[1].id to Pitch.fromName(alto),
                voices[2].id to Pitch.fromName(tenor),
                voices[3].id to Pitch.fromName(bass),
            ),
        )

    private fun generatedFrame(
        slotIndex: Int,
        target: TextbookSeventhTarget,
    ): FixedVoiceWritingFrame<TextbookSeventhTarget> {
        val tones = target.chord.chord.pitchClasses
        val choices = if (tones.size >= 4) tones else listOf(tones[0], tones[1], tones[2], tones[0])
        return FixedVoiceWritingFrame(
            slotIndex = slotIndex,
            target = target,
            pitchesByVoiceId = mapOf(
                voices[0].id to pitchFor(choices[0], 5),
                voices[1].id to pitchFor(choices[1], 4),
                voices[2].id to pitchFor(choices[2], 3),
                voices[3].id to pitchFor(target.bassPitchClass, 2),
            ),
        )
    }

    private fun pitchFor(pitchClass: PitchClass, octave: Int): Pitch {
        val base = 12 * (octave + 1)
        val midi = (base..base + 11).first { it.mod(12) == pitchClass.value }
        return Pitch.fromMidi(midi, preferSharps = true)
    }

    private val voices = standardFourPartWritingVoices()
}
