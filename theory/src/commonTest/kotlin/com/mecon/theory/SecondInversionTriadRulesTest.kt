package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertTrue

class SecondInversionTriadRulesTest {
    @Test
    fun recognizesCadentialPassingPedalAndSameChordSecondInversionUses() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val subdominant = RootPositionTriadRules.triadInKey(key, 4)

        val cadential = SecondInversionTriadRules.checkContextualUses(
            listOf(
                frame(0, TextbookTriadTarget(tonic, TextbookTriadPosition.SECOND_INVERSION), "C5", "G4", "E4", "G2"),
                frame(1, TextbookTriadTarget(dominant, TextbookTriadPosition.ROOT_POSITION), "D5", "B4", "G3", "G2"),
                frame(2, TextbookTriadTarget(tonic, TextbookTriadPosition.ROOT_POSITION), "C5", "G4", "E4", "C3"),
            ),
            voices,
        )
        assertTrue(cadential.any { it.ruleId == SecondInversionTriadRules.CADENTIAL_SIX_FOUR })

        val passing = SecondInversionTriadRules.checkContextualUses(
            listOf(
                frame(0, TextbookTriadTarget(tonic, TextbookTriadPosition.FIRST_INVERSION), "C5", "G4", "C4", "E3"),
                frame(1, TextbookTriadTarget(dominant, TextbookTriadPosition.SECOND_INVERSION), "B4", "G4", "G3", "D3"),
                frame(2, TextbookTriadTarget(tonic, TextbookTriadPosition.ROOT_POSITION), "C5", "G4", "E3", "C3"),
            ),
            voices,
        )
        assertTrue(passing.any { it.ruleId == SecondInversionTriadRules.PASSING_SIX_FOUR })

        val pedal = SecondInversionTriadRules.checkContextualUses(
            listOf(
                frame(0, TextbookTriadTarget(tonic, TextbookTriadPosition.ROOT_POSITION), "C5", "G4", "E4", "C3"),
                frame(1, TextbookTriadTarget(subdominant, TextbookTriadPosition.SECOND_INVERSION), "C5", "A4", "F4", "C3"),
                frame(2, TextbookTriadTarget(tonic, TextbookTriadPosition.ROOT_POSITION), "C5", "G4", "E4", "C3"),
            ),
            voices,
        )
        assertTrue(pedal.any { it.ruleId == SecondInversionTriadRules.PEDAL_SIX_FOUR })

        val sameChord = SecondInversionTriadRules.checkContextualUses(
            listOf(
                frame(0, TextbookTriadTarget(tonic, TextbookTriadPosition.ROOT_POSITION), "C5", "G4", "E4", "C3"),
                frame(1, TextbookTriadTarget(tonic, TextbookTriadPosition.SECOND_INVERSION), "C5", "G4", "E4", "G2"),
                frame(2, TextbookTriadTarget(tonic, TextbookTriadPosition.FIRST_INVERSION), "C5", "G4", "C4", "E3"),
            ),
            voices,
        )
        assertTrue(sameChord.any { it.ruleId == SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION })
    }

    @Test
    fun flagsSecondInversionOutsideStandardUse() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)

        val findings = SecondInversionTriadRules.checkContextualUses(
            listOf(frame(0, TextbookTriadTarget(tonic, TextbookTriadPosition.SECOND_INVERSION), "C5", "G4", "E4", "G2")),
            voices,
        )

        assertTrue(findings.any { it.ruleId == SecondInversionTriadRules.UNSUPPORTED_SECOND_INVERSION })
    }

    private fun frame(
        slotIndex: Int,
        target: TextbookTriadTarget,
        soprano: String,
        alto: String,
        tenor: String,
        bass: String,
    ): FixedVoiceWritingFrame<TextbookTriadTarget> =
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

    private val voices = standardFourPartWritingVoices()
}

