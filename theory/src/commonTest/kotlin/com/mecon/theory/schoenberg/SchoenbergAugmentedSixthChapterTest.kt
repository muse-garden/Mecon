package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.SearchConfig
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.constraint.AugmentedSixthVocabulary
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.harmony.ChordCatalogSnapshot
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordKnowledgeContext
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.AugmentedSixthConstructionOrigin
import com.mecon.theory.harmony.ConstructionTonePresence
import com.mecon.theory.harmony.SoundingInterpretationQuery
import com.mecon.theory.textbook.TextbookSeventhPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoenbergAugmentedSixthChapterTest {
    @Test
    fun classicCRecipesRetainAugmentedSixthSpelling() {
        val context = TonalContext.fromKey(Key.major(PitchClass.C))
        val towardDominant = AugmentedSixthVocabulary.types(context)
            .filter { it.targetDegree == 5 }
            .associateBy { it.family }

        assertEquals(
            setOf("F♯", "A♭", "C"),
            towardDominant.getValue(AugmentedSixthFamily.ITALIAN)
                .let { setOf(it.upperTone.displayName(), it.lowerTone.displayName(), it.supportTone.displayName()) },
        )
        assertEquals(
            setOf("F♯", "A♭", "C", "E♭"),
            towardDominant.getValue(AugmentedSixthFamily.GERMAN)
                .let {
                    setOf(
                        it.upperTone.displayName(),
                        it.lowerTone.displayName(),
                        it.supportTone.displayName(),
                        it.colorTone!!.displayName(),
                    )
                },
        )
        assertEquals(
            setOf("D", "F♯", "A♭", "C"),
            towardDominant.getValue(AugmentedSixthFamily.FRENCH)
                .let {
                    setOf(
                        it.upperTone.displayName(),
                        it.lowerTone.displayName(),
                        it.supportTone.displayName(),
                        it.colorTone!!.displayName(),
                    )
                },
        )
        assertTrue("F♯" in towardDominant.getValue(AugmentedSixthFamily.GERMAN).upperTone.displayName())
        assertFalse("G♭" in towardDominant.getValue(AugmentedSixthFamily.GERMAN).upperTone.displayName())
    }

    @Test
    fun leadingDegreeSourceUsesACompleteAppliedDominantFlatNinthBeforeAlteration() {
        val modulationKey = ModulationKey(0, KeySignatureMode.MAJOR)
        val choice = ChordSelectionCatalog.groups(
            modulationKey,
            providers = listOf(SchoenbergAugmentedSixthChapter),
        ).flatMap { it.chords }.single { it.functionalSymbol == "Ger+6/VII" }
        val snapshot = ChordCatalogSnapshot.create(
            modulationKey,
            selectionProviders = listOf(SchoenbergAugmentedSixthChapter),
            knowledgeProviders = listOf(SchoenbergAugmentedSixthChapter),
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )
        val detail = snapshot.resolve(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin),
            ChordKnowledgeContext(modulationKey.tonalContext("chord-selection")),
        )
        val construction = detail.explanations.single().routes.single().construction
            as ChordConstructionDetail.AugmentedSixthDerivation
        val origin = construction.origin as AugmentedSixthConstructionOrigin.RootlessAppliedChord

        assertEquals(
            listOf(4 to 1, 6 to 1, 1 to 1, 3 to 0, 5 to 0),
            origin.tones.map { it.degree to it.alteration },
        )
        assertEquals(ConstructionTonePresence.OMITTED, origin.tones.first().presence)
        assertEquals(
            listOf(6 to 1, 1 to 0, 3 to 0, 5 to 0),
            construction.augmentedSixthTones.map { it.degree to it.alteration },
        )

        val italian = AugmentedSixthVocabulary.types(modulationKey.tonalContext("test"))
            .single {
                it.family == AugmentedSixthFamily.ITALIAN && it.targetDegree == 7
            }
        assertEquals(
            listOf("A♯", "C♯", "E"),
            italian.basisTones.map { it.displayName() },
        )
        assertEquals(
            listOf("A♯", "C", "E"),
            italian.resultTones.map { it.displayName() },
        )
    }

    @Test
    fun classicFamiliesTargetEveryScaleDegreeAndHalfDiminishedIsAnIndependentType() {
        val key = Key.major(PitchClass.C)
        val types = SchoenbergAugmentedSixthChapter.types(key)
        assertEquals(22, types.size)
        assertEquals(AugmentedSixthFamily.entries.toSet(), types.map { it.family }.toSet())
        val classicFamilies = setOf(
            AugmentedSixthFamily.ITALIAN,
            AugmentedSixthFamily.GERMAN,
            AugmentedSixthFamily.FRENCH,
        )
        assertTrue(classicFamilies.all { family ->
            types.filter { it.family == family }.map { it.targetDegree }.toSet() == (1..7).toSet()
        })
        assertEquals(
            listOf(2 to -1),
            types.filter { it.family == AugmentedSixthFamily.HALF_DIMINISHED }
                .map { it.targetDegree to it.resolutionAlteration },
        )

        val chords = SchoenbergAugmentedSixthChapter.exerciseChords(key)
        assertEquals(81, chords.size)
        assertTrue(
            chords.any {
                it.augmentedSixthFamily == AugmentedSixthFamily.GERMAN &&
                    it.appliedToDegree == 5 &&
                    it.seventhPosition == TextbookSeventhPosition.THIRD_INVERSION
            }
        )
    }

    @Test
    fun germanSixthSharesFunctionalAndDeceptiveResolutionPolicy() {
        val key = Key.major(PitchClass.C)
        val resolutions = SchoenbergAugmentedSixthChapter.enumerate(key)
            .filter {
                it.kind == SchoenbergConnectionKind.AUGMENTED_SIXTH_RESOLUTION &&
                    it.slots.first().augmentedSixthFamily == AugmentedSixthFamily.GERMAN &&
                    it.slots.first().appliedToDegree == 5 &&
                    it.slots.first().seventhPosition == TextbookSeventhPosition.ROOT_POSITION
            }
        assertEquals(setOf(1, 3, 5), resolutions.map { it.slots.last().degree }.toSet())

        val standard = resolutions.single { it.slots.last().degree == 5 }
        val program = SchoenbergAugmentedSixthChapter.program(
            key = key,
            progression = standard,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 1024),
        )
        assertTrue(program.constraints.any { it.ruleId == SchoenbergAugmentedSixthChapter.LOWER_RESOLUTION_RULE_ID })
        assertTrue(program.constraints.any { it.ruleId == SchoenbergAugmentedSixthChapter.UPPER_RESOLUTION_RULE_ID })
        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty())
    }

    @Test
    fun majorModeIncludesHalfDiminishedEnharmonicPathToNeapolitan() {
        val progression = SchoenbergAugmentedSixthChapter.enumerate(Key.major(PitchClass.C))
            .single { it.kind == SchoenbergConnectionKind.ENHARMONIC_AUGMENTED_SIXTH_RESOLUTION }
        assertEquals(ChordArity.SEVENTH, progression.slots.first().arity)
        assertEquals(ChordQuality.CUSTOM, progression.slots.first().quality)
        assertEquals(AugmentedSixthFamily.HALF_DIMINISHED, progression.slots.first().augmentedSixthFamily)
        assertEquals(2, progression.slots.last().degree)
        assertEquals(-1, progression.slots.last().rootAlteration)
    }

    @Test
    fun catalogUsesAugmentedSixthNamesAndKeepsHalfDiminishedInterpretationsSeparate() {
        val modulationKey = ModulationKey(0, KeySignatureMode.MAJOR)
        val groups = ChordSelectionCatalog.groups(
            modulationKey,
            providers = listOf(SchoenbergAugmentedSixthChapter, SchoenbergMinorSubdominantChapter),
        )
        val augmented = groups
            .filter { it.category.id in setOf("dominant-augmented-sixths", "augmented-sixths") }
            .flatMap { it.chords }
        val symbols = augmented.map { it.functionalSymbol }.toSet()
        assertTrue("It+6" in symbols)
        assertTrue("Ger+6" in symbols)
        assertTrue("Fr+6" in symbols)
        assertTrue("It+6/VII" in symbols)
        assertTrue("ø+6" in symbols)

        val augmentedHalfDiminished = augmented.single { it.functionalSymbol == "ø+6" }
        val ordinaryHalfDiminished = groups
            .single { it.category.id == "minor-subdominant-related" }
            .chords.single { it.absoluteTones.toSet() == setOf("D", "F", "A♭", "C") }
        assertEquals("iiø7", ordinaryHalfDiminished.functionalSymbol)
        assertEquals(ordinaryHalfDiminished.audibleKey, augmentedHalfDiminished.audibleKey)
        assertTrue(
            ordinaryHalfDiminished.interpretationRefs.toSet()
                .intersect(augmentedHalfDiminished.interpretationRefs.toSet())
                .isEmpty()
        )
    }

    @Test
    fun catalogPlacesClearPredominantsFirstAndOrdersOtherSixthsByTargetDegree() {
        val groups = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
        val ids = groups.map { it.category.id }
        assertTrue(ids.indexOf("neapolitan") < ids.indexOf("dominant-augmented-sixths"))
        assertTrue(ids.indexOf("dominant-augmented-sixths") < ids.indexOf("minor-subdominant-related"))
        assertTrue(ids.indexOf("minor-subdominant-related") < ids.indexOf("augmented-sixths"))

        val dominantSymbols = groups.single { it.category.id == "dominant-augmented-sixths" }
            .chords.map { it.functionalSymbol }
        assertEquals(listOf("It+6", "Ger+6", "Fr+6"), dominantSymbols)

        val otherSymbols = groups.single { it.category.id == "augmented-sixths" }
            .chords.map { it.functionalSymbol }
        val targetDegrees = otherSymbols.map { symbol ->
            when {
                symbol == "ø+6" -> 2
                "/" in symbol -> when (symbol.substringAfter('/')) {
                    "I" -> 1
                    "II" -> 2
                    "III" -> 3
                    "IV" -> 4
                    "VI" -> 6
                    "VII" -> 7
                    else -> error("Unexpected augmented-sixth symbol $symbol")
                }
                else -> error("Non-dominant augmented sixth lacks target suffix: $symbol")
            }
        }
        assertEquals(targetDegrees.sorted(), targetDegrees)
        assertTrue(otherSymbols.takeLast(3).all { it.endsWith("/VII") })
    }

    @Test
    fun oneGeneralIntegratedStageSupportsMajorAndMinor() {
        val exerciseId = SchoenbergCommonToneExercises.INTEGRATED_AUGMENTED_SIXTH_EXERCISE_ID
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(exerciseId)
        assertEquals(SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID, descriptor.parentId)

        listOf(Key.major(PitchClass.C), Key.minor(PitchClass.A)).forEach { key ->
            val vocabulary = SchoenbergIntegratedTechTree.vocabularyForStage(exerciseId, key)
            assertTrue(vocabulary.any { it.augmentedSixthFamily != null })
            val program = SchoenbergIntegratedTechTree.programForStage(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = 8,
            )
            assertTrue(
                program.constraints.any {
                    it.ruleId == SchoenbergAugmentedSixthChapter.LOWER_RESOLUTION_RULE_ID
                }
            )
            assertTrue(
                program.constraints.any {
                    it.ruleId == SchoenbergAugmentedSixthChapter.UPPER_RESOLUTION_RULE_ID
                }
            )
        }
    }

    @Test
    fun catalogChoiceResolvesTypedConstructionAndVoiceLeadingDetail() {
        val modulationKey = ModulationKey(0, KeySignatureMode.MAJOR)
        val choice = ChordSelectionCatalog.groups(
            modulationKey,
            providers = listOf(SchoenbergAugmentedSixthChapter),
        ).flatMap { it.chords }.single { it.functionalSymbol == "It+6" }
        val snapshot = ChordCatalogSnapshot.create(
            modulationKey,
            selectionProviders = listOf(SchoenbergAugmentedSixthChapter),
            knowledgeProviders = listOf(SchoenbergAugmentedSixthChapter),
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )
        val detail = snapshot.resolve(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin),
            ChordKnowledgeContext(modulationKey.tonalContext("chord-selection")),
        )

        val explanation = detail.explanations.single()
        val route = explanation.routes.single()
        assertEquals(2, route.tendencyTones.size)
        assertTrue(route.construction is ChordConstructionDetail.AugmentedSixthDerivation)
        assertTrue(SchoenbergHarmonicTreatments.AUGMENTED_SIXTH in route.connectionRefs)
        assertTrue(detail.missingKnowledgeRefs.isEmpty())
    }
}
