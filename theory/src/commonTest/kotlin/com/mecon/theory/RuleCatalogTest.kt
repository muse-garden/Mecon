package com.mecon.theory

import com.mecon.theory.textbook.ROOT_POSITION_TRIAD_CHAPTER
import com.mecon.theory.textbook.FIRST_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.SECOND_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleCatalogTest {
    @Test
    fun exposesRootPositionTriadChapterDescriptors() {
        val roots = RuleCatalog.chapter(ROOT_POSITION_TRIAD_CHAPTER)

        assertTrue(roots.isNotEmpty())
        assertNotNull(RuleCatalog.descriptor(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE))
        assertTrue(RuleCatalog.allDescriptors().all { RuleCatalog.descriptor(it.id) == it })
    }

    @Test
    fun exposesFirstInversionTriadChapterDescriptors() {
        val roots = RuleCatalog.chapter(FIRST_INVERSION_TRIAD_CHAPTER)

        assertTrue(roots.isNotEmpty())
        assertNotNull(RuleCatalog.descriptor(FirstInversionTriadRules.FIRST_INVERSION_BASS_LINE))
        assertNotNull(RuleCatalog.descriptor(FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH))
    }

    @Test
    fun exposesSecondInversionTriadChapterDescriptors() {
        val roots = RuleCatalog.chapter(SECOND_INVERSION_TRIAD_CHAPTER)

        assertTrue(roots.isNotEmpty())
        assertNotNull(RuleCatalog.descriptor(SecondInversionTriadRules.CADENTIAL_SIX_FOUR))
        assertNotNull(RuleCatalog.descriptor(SecondInversionTriadRules.UNSUPPORTED_SECOND_INVERSION))
    }

    @Test
    fun rejectsExclusiveFourthFifthPatterns() {
        val validation = RuleCatalog.validateSelection(
            selected = listOf(
                RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
            ),
            context = SelectionContext(fromDegree = 5, toDegree = 1),
        )

        assertFalse(validation.isValid)
    }

    @Test
    fun reportsRulesThatDoNotMatchTheRootRelation() {
        val validation = RuleCatalog.validateSelection(
            selected = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE),
            context = SelectionContext(fromDegree = 1, toDegree = 2),
        )

        assertTrue(validation.isValid)
        assertTrue(validation.unavailable.isNotEmpty())
    }

    @Test
    fun requiresInnerLeadingToneLeapToBeAttachedToAFourthFifthPattern() {
        val validation = RuleCatalog.validateSelection(
            selected = listOf(RootPositionTriadRules.INNER_LEADING_TONE_LEAP),
            context = SelectionContext(fromDegree = 5, toDegree = 1),
        )

        assertFalse(validation.isValid)
    }

    @Test
    fun derivesRuleExamplePairsFromApplicability() {
        val spec = RuleCatalog.exampleInputSpec(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE)

        assertEquals(RuleDegreePair(5, 1), spec.defaultPair)
        assertTrue(spec.degreePairs.all { SelectionContext(it.fromDegree, it.toDegree).degreeDistance == 3 })
        assertEquals(null, spec.keyMode)
    }

    @Test
    fun derivesCompanionRulesFromRequiresRelation() {
        val spec = RuleCatalog.exampleInputSpec(RootPositionTriadRules.INNER_LEADING_TONE_LEAP)

        assertEquals(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE, spec.defaultCompanionRuleId)
        assertEquals(
            listOf(
                RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
            ),
            spec.companionRuleOptions,
        )
    }

    @Test
    fun keepsSpecialInputOverridesOnExceptionalRules() {
        val majorSpec = RuleCatalog.exampleInputSpec(
            RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE
        )
        val minorSpec = RuleCatalog.exampleInputSpec(RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH)

        assertEquals(listOf(RuleDegreePair(5, 6)), majorSpec.degreePairs)
        assertEquals(RuleKeyModeConstraint.MAJOR, majorSpec.keyMode)
        assertEquals(listOf(RuleDegreePair(5, 6)), minorSpec.degreePairs)
        assertEquals(RuleKeyModeConstraint.MINOR, minorSpec.keyMode)
        assertEquals(ChordQuality.MAJOR, minorSpec.degreeQualities[5])
        assertEquals(RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH, minorSpec.defaultDemonstrationRuleId)
    }

    @Test
    fun keepsFirstInversionInputOverrides() {
        val diminishedSpec = RuleCatalog.exampleInputSpec(
            FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION
        )
        val majorDominantSpec = RuleCatalog.exampleInputSpec(
            FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH
        )

        assertEquals(listOf(RuleDegreePair(7, 7)), diminishedSpec.degreePairs)
        assertEquals(listOf(RuleDegreePair(5, 6)), majorDominantSpec.degreePairs)
        assertEquals(RuleKeyModeConstraint.MAJOR, majorDominantSpec.keyMode)
        assertEquals(ChordQuality.MAJOR, majorDominantSpec.degreeQualities[5])
        assertEquals(ChordQuality.MINOR, majorDominantSpec.degreeQualities[6])
        assertEquals(
            FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH,
            majorDominantSpec.defaultDemonstrationRuleId,
        )
    }

    @Test
    fun keepsSecondInversionInputOverrides() {
        val cadentialSpec = RuleCatalog.exampleInputSpec(SecondInversionTriadRules.CADENTIAL_SIX_FOUR)
        val pedalSpec = RuleCatalog.exampleInputSpec(SecondInversionTriadRules.PEDAL_SIX_FOUR)
        val sameChordSpec = RuleCatalog.exampleInputSpec(SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION)

        assertEquals(listOf(RuleDegreePair(1, 5)), cadentialSpec.degreePairs)
        assertEquals(RuleDegreePair(1, 5), cadentialSpec.defaultPair)
        assertTrue(pedalSpec.degreePairs.contains(RuleDegreePair(1, 4)))
        assertEquals(listOf(RuleDegreePair(1, 1), RuleDegreePair(5, 5)), sameChordSpec.degreePairs)
    }
}
