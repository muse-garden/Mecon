package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.textbook.MelodyTextbookRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MelodyTextbookRulesTest {
    @Test
    fun uniqueClimaxCanBeRequiredPerVoice() {
        val pitches = listOf(
            Pitch.fromName("C4"),
            Pitch.fromName("G4"),
            Pitch.fromName("E4"),
            Pitch.fromName("G4"),
        )
        val key = Key.major(PitchClass.C)

        val sopranoRules = MelodyTextbookRules.checkPitches(pitches, key, requireUniqueClimax = true)
        val innerVoiceRules = MelodyTextbookRules.checkPitches(pitches, key, requireUniqueClimax = false)

        assertTrue(sopranoRules.any { it.ruleId == MelodyTextbookRules.UNIQUE_CLIMAX })
        assertFalse(innerVoiceRules.any { it.ruleId == MelodyTextbookRules.UNIQUE_CLIMAX })
    }

    @Test
    fun diminishedLeapIsAllowedOnlyWhenResolvedByOppositeStep() {
        val key = Key.major(PitchClass.C)
        val resolved = listOf(
            Pitch.fromName("C#4"),
            Pitch.fromName("G4"),
            Pitch.fromName("F#4"),
        )
        val unresolved = listOf(
            Pitch.fromName("C#4"),
            Pitch.fromName("G4"),
            Pitch.fromName("A4"),
        )

        assertFalse(
            MelodyTextbookRules.checkPitches(resolved, key)
                .any { it.ruleId == MelodyTextbookRules.DIMINISHED_LEAP_RESOLUTION }
        )
        assertTrue(
            MelodyTextbookRules.checkPitches(unresolved, key)
                .any { it.ruleId == MelodyTextbookRules.DIMINISHED_LEAP_RESOLUTION }
        )
    }

    @Test
    fun consecutiveSameDirectionSmallLeapsMustOutlineTriad() {
        val key = Key.major(PitchClass.C)
        val triadOutline = listOf(
            Pitch.fromName("C4"),
            Pitch.fromName("E4"),
            Pitch.fromName("G4"),
        )
        val nonTriadOutline = listOf(
            Pitch.fromName("C4"),
            Pitch.fromName("E4"),
            Pitch.fromName("Gb4"),
        )

        assertFalse(
            MelodyTextbookRules.checkPitches(triadOutline, key)
                .any { it.ruleId == MelodyTextbookRules.CONSECUTIVE_LEAP_TRIAD_OUTLINE }
        )
        assertTrue(
            MelodyTextbookRules.checkPitches(nonTriadOutline, key)
                .any { it.ruleId == MelodyTextbookRules.CONSECUTIVE_LEAP_TRIAD_OUTLINE }
        )
    }

    @Test
    fun tendencyToneRulesUseDifferentSeverityAndDescendingScaleException() {
        val key = Key.major(PitchClass.C)
        val unresolvedLeadingTone = listOf(
            Pitch.fromName("B4"),
            Pitch.fromName("A4"),
        )
        val descendingScaleException = listOf(
            Pitch.fromName("C5"),
            Pitch.fromName("B4"),
            Pitch.fromName("A4"),
            Pitch.fromName("G4"),
        )
        val unresolvedFourthDegree = listOf(
            Pitch.fromName("F4"),
            Pitch.fromName("G4"),
        )

        val leadingTone = MelodyTextbookRules.checkPitches(unresolvedLeadingTone, key)
            .single { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION }
        val fourthDegree = MelodyTextbookRules.checkPitches(unresolvedFourthDegree, key)
            .single { it.ruleId == MelodyTextbookRules.FOURTH_DEGREE_RESOLUTION }

        assertEquals(RuleSeverity.SOFT, leadingTone.severity)
        assertEquals(RuleSeverity.HINT, fourthDegree.severity)
        assertFalse(
            MelodyTextbookRules.checkPitches(descendingScaleException, key)
                .any { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION }
        )
    }

    @Test
    fun canCheckFixedVoiceScoreDirectly() {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = listOf(
                    listOf(Pitch.fromName("C5")),
                    listOf(Pitch.fromName("G5")),
                    listOf(Pitch.fromName("E5")),
                    listOf(Pitch.fromName("G5")),
                ),
                altoPitches = listOf(
                    listOf(Pitch.fromName("F4")),
                    listOf(Pitch.fromName("G4")),
                ),
            )
        )
        val fixed = FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
        val soprano = fixed.voices.first { it.role == FixedVoiceRole.SOPRANO }
        val alto = fixed.voices.first { it.role == FixedVoiceRole.ALTO }
        val sopranoHighestIds = fixed.noteEventsForVoice(soprano)
            .filter { it.pitch == Pitch.fromName("G5") }
            .map { it.id }

        val diagnostics = MelodyTextbookRules.checkFixedVoiceScore(fixed, Key.major(PitchClass.C))
        val uniqueClimax = diagnostics.single { it.ruleId == MelodyTextbookRules.UNIQUE_CLIMAX }

        assertEquals(sopranoHighestIds, uniqueClimax.anchors)
        assertFalse(
            diagnostics.any {
                it.ruleId == MelodyTextbookRules.UNIQUE_CLIMAX &&
                    it.anchors.any { anchor -> anchor in fixed.noteEventsForVoice(alto).map { event -> event.id } }
            }
        )
    }
}
