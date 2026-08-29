package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.schoenberg.ModulationExerciseRequest
import com.mecon.theory.schoenberg.ModulationSolverPreset
import com.mecon.theory.schoenberg.SchoenbergModulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModulationTest {
    private val cMajor = ModulationKey(0, KeySignatureMode.MAJOR)
    private val gMajor = ModulationKey(1, KeySignatureMode.MAJOR)

    @Test
    fun commonChordKeepsFunctionAndRelativeToneInterpretationsForEveryKey() {
        val common = ModulationCommonChordCatalog.commonChords(listOf(cMajor, gMajor))
        val cMajorChord = common.first { it.id == ModulationChordId(PitchClass.C, ChordQuality.MAJOR) }

        assertEquals(listOf(1, 4), cMajorChord.interpretations.map { it.degree })
        assertEquals(listOf("1", "3", "5"), cMajorChord.interpretations[0].relativeTones)
        assertEquals(listOf("4", "6", "1"), cMajorChord.interpretations[1].relativeTones)
        assertEquals(listOf("C", "E", "G"), cMajorChord.interpretations[0].absoluteTones.map { it.toString() })
    }

    @Test
    fun inverseLookupReturnsOnlyKeysContainingEverySelectedChord() {
        val selected = listOf(
            ModulationChordId(PitchClass.C, ChordQuality.MAJOR),
            ModulationChordId(PitchClass.E, ChordQuality.MINOR),
        )
        val keys = ModulationCommonChordCatalog.keysContaining(selected)

        assertTrue(cMajor in keys)
        assertTrue(gMajor in keys)
        assertTrue(keys.all { key ->
            selected.all { id ->
                NaturalTriads.inKey(key.key).any { it.root == id.root && it.quality == id.quality }
            }
        })
    }

    @Test
    fun routeComposesMultiplePairwisePivotsAndMayRemainOpen() {
        val dMajor = ModulationKey(2, KeySignatureMode.MAJOR)
        val firstPivot = ModulationCommonChordCatalog.commonChords(cMajor, gMajor).first().id
        val secondPivot = ModulationCommonChordCatalog.commonChords(gMajor, dMajor).first().id

        val route = TonalRoutePlan(
            source = cMajor,
            steps = listOf(
                CommonChordPivotStep(gMajor, firstPivot),
                CommonChordPivotStep(dMajor, secondPivot),
            ),
            endingIntent = TonalEndingIntent.OPEN_FRAGMENT,
        )

        assertEquals(listOf(cMajor, gMajor, dMajor), route.keys)
        assertEquals(TonalEndingIntent.OPEN_FRAGMENT, route.endingIntent)
    }

    @Test
    fun parallelMinorToMajorRecommendsButDoesNotEnableSustainedTone() {
        val cMinor = ModulationKey(-3, KeySignatureMode.MINOR)
        val pivot = ModulationChordId(PitchClass.C, ChordQuality.MINOR)
        val route = TonalRoutePlan(
            source = cMinor,
            steps = listOf(
                CommonChordPivotStep(
                    target = cMajor,
                    pivotChordId = pivot,
                    vocabularyId = ModulationChordVocabularyId.PARALLEL_COMMON_TONE,
                )
            ),
            endingIntent = TonalEndingIntent.ESTABLISHED,
        )

        TonalTechniqueGraph.validateAcyclic()
        assertTrue(TonalTechniqueGraph.SUSTAINED_TONE in TonalTechniqueGraph.recommendations(route))
        assertTrue(route.techniques.isEmpty())
    }

    @Test
    fun relativeKeyLabelsPreserveEnharmonicSpelling() {
        val dFlatMajor = ModulationKey(-5, KeySignatureMode.MAJOR)
        val cSharpMajor = ModulationKey(7, KeySignatureMode.MAJOR)
        val aMinor = ModulationKey(0, KeySignatureMode.MINOR)

        assertEquals(
            "♭2",
            ModulationPitchLabels.relativeTonicLabel(cMajor, dFlatMajor),
        )
        assertEquals(
            "♯1",
            ModulationPitchLabels.relativeTonicLabel(cMajor, cSharpMajor),
        )
        assertEquals("5", ModulationPitchLabels.relativeTonicLabel(cMajor, gMajor))
        assertEquals("6", ModulationPitchLabels.relativeTonicLabel(cMajor, aMinor))
    }

    @Test
    fun minorPitchLabelsUseTheRelativeMajorDegrees() {
        val aMinor = ModulationKey(0, KeySignatureMode.MINOR)

        assertEquals("6", ModulationPitchLabels.relativePitchLabel(aMinor, PitchClass(9)))
        assertEquals("7", ModulationPitchLabels.relativePitchLabel(aMinor, PitchClass(11)))
        assertEquals("1", ModulationPitchLabels.relativePitchLabel(aMinor, PitchClass(0)))
        assertEquals("5", ModulationPitchLabels.relativePitchLabel(aMinor, PitchClass(7)))
    }

    @Test
    fun circleOfFifthsDistanceUsesTheShortestSignedPath() {
        val fMajor = ModulationKey(-1, KeySignatureMode.MAJOR)
        val cSharpMajor = ModulationKey(7, KeySignatureMode.MAJOR)
        val cFlatMajor = ModulationKey(-7, KeySignatureMode.MAJOR)
        val fSharpMajor = ModulationKey(6, KeySignatureMode.MAJOR)
        val gFlatMajor = ModulationKey(-6, KeySignatureMode.MAJOR)

        assertEquals("+1", ModulationCircleOfFifths.signedDistanceLabel(cMajor, gMajor))
        assertEquals("-1", ModulationCircleOfFifths.signedDistanceLabel(cMajor, fMajor))
        assertEquals("-5", ModulationCircleOfFifths.signedDistanceLabel(cMajor, cSharpMajor))
        assertEquals("+5", ModulationCircleOfFifths.signedDistanceLabel(cMajor, cFlatMajor))
        assertEquals("+6", ModulationCircleOfFifths.signedDistanceLabel(cMajor, fSharpMajor))
        assertEquals("-6", ModulationCircleOfFifths.signedDistanceLabel(cMajor, gFlatMajor))
        assertEquals("0", ModulationCircleOfFifths.signedDistanceLabel(cMajor, cMajor))
    }

    @Test
    fun enharmonicKeysShareCirclePositionsAndHaveZeroCircleDistance() {
        val pairs = listOf(
            ModulationKey(-7, KeySignatureMode.MAJOR) to
                ModulationKey(5, KeySignatureMode.MAJOR),
            ModulationKey(-6, KeySignatureMode.MAJOR) to
                ModulationKey(6, KeySignatureMode.MAJOR),
            ModulationKey(-5, KeySignatureMode.MAJOR) to
                ModulationKey(7, KeySignatureMode.MAJOR),
        )

        pairs.forEach { (flatKey, sharpKey) ->
            assertEquals(
                ModulationCircleOfFifths.position(flatKey),
                ModulationCircleOfFifths.position(sharpKey),
            )
            assertTrue(ModulationCircleOfFifths.areEnharmonic(flatKey, sharpKey))
            assertEquals(0, ModulationCircleOfFifths.signedDistance(flatKey, sharpKey))
            assertEquals(0, ModulationCircleOfFifths.signedDistance(sharpKey, flatKey))
        }
        assertTrue(
            !ModulationCircleOfFifths.areEnharmonic(
                ModulationKey(-7, KeySignatureMode.MAJOR),
                ModulationKey(-7, KeySignatureMode.MINOR),
            )
        )
    }

    @Test
    fun commonChordsUseEnharmonicPitchIdentityButKeepEachKeysSpelling() {
        val bMajor = ModulationKey(5, KeySignatureMode.MAJOR)
        val cFlatMajor = ModulationKey(-7, KeySignatureMode.MAJOR)
        val tonic = ModulationCommonChordCatalog.commonChords(bMajor, cFlatMajor)
            .first { it.id == ModulationChordId(PitchClass.B, ChordQuality.MAJOR) }

        assertEquals(
            listOf(listOf("B", "D#", "F#"), listOf("Cb", "Eb", "Gb")),
            tonic.interpretations.map { interpretation ->
                interpretation.absoluteTones.map(SpelledPitchClass::toString)
            },
        )
    }

    @Test
    fun nextKeyRankingTreatsEnharmonicRespellingAsZeroCircleSteps() {
        val bMajor = ModulationKey(5, KeySignatureMode.MAJOR)
        val cFlatMajor = ModulationKey(-7, KeySignatureMode.MAJOR)
        val fSharpMajor = ModulationKey(6, KeySignatureMode.MAJOR)
        val bMajorChord = ModulationChordId(PitchClass.B, ChordQuality.MAJOR)

        assertEquals(
            listOf(cFlatMajor, fSharpMajor),
            ModulationCommonChordCatalog.nextKeys(
                source = bMajor,
                pivotChordId = bMajorChord,
                keys = listOf(fSharpMajor, cFlatMajor, bMajor),
            ),
        )
    }

    @Test
    fun firstExerciseUsesPivotThenCharacteristicToneAndTargetCadence() {
        val eMinor = ModulationChordId(PitchClass.E, ChordQuality.MINOR)
        val compiled = SchoenbergModulation.compile(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = gMajor,
                pivotChord = eMinor,
                sourceChordCount = 1,
                targetChordCount = 2,
                solverPreset = ModulationSolverPreset.FREE,
            )
        )

        val pivot = compiled.program.slotDomains[compiled.pivotSlot].targets.single()
        assertTrue(pivot.sonority.pitchClasses.toSet() == setOf(PitchClass.E, PitchClass.G, PitchClass.B))
        assertEquals(
            setOf(
                cMajor.tonalContext("modulation.source").id,
                gMajor.tonalContext("modulation.target").id,
            ),
            pivot.tonalContextIds(),
        )
        assertNotNull(
            compiled.program.constraints.firstOrNull {
                it.ruleId == SchoenbergModulation.CHARACTERISTIC_TONE_RULE_ID
            }
        )

        val solution = ConstraintProgramSolver.solve(compiled.program).first()
        val targetFrames = solution.voicings.drop(compiled.targetStartSlot)
        assertTrue(targetFrames.any { PitchClass(6) in it.target.sonority.pitchClasses })
        assertEquals(5, solution.voicings[solution.voicings.lastIndex - 1].target.degree)
        assertEquals(1, solution.voicings.last().target.degree)
    }

    @Test
    fun schoenbergPresetAddsChapterRulesAndRemainsSolvableAcrossStableRegions() {
        val programs = SchoenbergModulation.compileCandidates(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = gMajor,
                pivotChord = ModulationChordId(PitchClass.E, ChordQuality.MINOR),
                sourceChordCount = 2,
                targetChordCount = 4,
                solverPreset = ModulationSolverPreset.SCHOENBERG,
            ),
            maxPrograms = 12,
        )
        val compiled = programs.first()

        assertTrue(
            compiled.program.constraints.any {
                it.ruleId == com.mecon.theory.schoenberg
                    .SchoenbergRootMotionAndRepetitionChapter.DESCENDING_COMPENSATION_RULE_ID
            }
        )
        assertTrue(
            ConstraintProgramSolver.solveFirstFeasible(
                programs = programs.map { it.program },
                maxProgramAttempts = 12,
            ).isNotEmpty()
        )
    }

    @Test
    fun schoenbergModulationVocabularyIncludesSeventhsAndEveryInversion() {
        val compiled = SchoenbergModulation.compile(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = gMajor,
                pivotChord = ModulationChordId(PitchClass.G, ChordQuality.MAJOR),
                sourceChordCount = 2,
                targetChordCount = 4,
                solverPreset = ModulationSolverPreset.SCHOENBERG,
            )
        )
        val openTargets = compiled.program.slotDomains[1].targets

        assertTrue(openTargets.any { it.arity == ChordArity.SEVENTH })
        assertEquals(
            setOf(0, 1, 2),
            openTargets.filter { it.arity == ChordArity.TRIAD }.mapTo(hashSetOf()) { it.inversion },
        )
        assertEquals(
            setOf(0, 1, 2, 3),
            openTargets.filter { it.arity == ChordArity.SEVENTH }.mapTo(hashSetOf()) { it.inversion },
        )
    }

    @Test
    fun rankedSchoenbergModulationCandidatesExerciseSeventhsAndInversions() {
        val programs = SchoenbergModulation.compileCandidates(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = gMajor,
                pivotChord = ModulationChordId(PitchClass.G, ChordQuality.MAJOR),
                sourceChordCount = 5,
                targetChordCount = 5,
                solverPreset = ModulationSolverPreset.SCHOENBERG,
            ),
            maxPrograms = 96,
        )
        val targets = programs.flatMap { candidate ->
            candidate.program.slotDomains.map { it.targets.single() }
        }

        assertTrue(targets.any { it.arity == ChordArity.SEVENTH })
        assertTrue(targets.any { it.inversion != 0 })
    }

    @Test
    fun fivePlusFiveSchoenbergRealizationRetainsCadentialSeventhAndInversion() {
        val programs = SchoenbergModulation.compileCandidates(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = gMajor,
                pivotChord = ModulationChordId(PitchClass.G, ChordQuality.MAJOR),
                sourceChordCount = 5,
                targetChordCount = 5,
                solverPreset = ModulationSolverPreset.SCHOENBERG,
                searchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
            ),
            maxPrograms = 96,
        )
        val solution = ConstraintProgramSolver.solveFirstFeasible(
            programs = programs.map { it.program },
            maxProgramAttempts = 12,
        ).first()

        assertEquals(ChordArity.SEVENTH, solution.voicings[solution.voicings.lastIndex - 1].target.arity)
        assertTrue(solution.voicings.any { it.target.inversion != 0 })
    }

    @Test
    fun minorCadenceUsesRaisedLeadingToneDominantOrDominantSeventh() {
        val aMinor = ModulationKey(0, KeySignatureMode.MINOR)
        val compiled = SchoenbergModulation.compile(
            ModulationExerciseRequest(
                sourceKey = cMajor,
                targetKey = aMinor,
                pivotChord = ModulationChordId(PitchClass.C, ChordQuality.MAJOR),
                sourceChordCount = 2,
                targetChordCount = 4,
                solverPreset = ModulationSolverPreset.SCHOENBERG,
            )
        )
        val dominants = compiled.program.slotDomains[compiled.program.length - 2].targets
        val tonics = compiled.program.slotDomains.last().targets
        val aMinorLeadingTone = PitchClass(8)

        assertTrue(dominants.isNotEmpty())
        assertTrue(dominants.all { it.inversion == 0 })
        assertTrue(dominants.all { it.quality in setOf(ChordQuality.MAJOR, ChordQuality.DOMINANT7) })
        assertTrue(dominants.all { aMinorLeadingTone in it.sonority.pitchClasses })
        assertTrue(tonics.all { it.quality == ChordQuality.MINOR && it.sonority.root == PitchClass.A })
        assertTrue(
            compiled.program.slotDomains.withIndex().all { (slot, domain) ->
                slot == compiled.pivotSlot ||
                    domain.targets
                        .filterIsInstance<com.mecon.theory.constraint.InterpretedChordTarget>()
                        .all { it.interpretation.compatibleContextIds.isEmpty() }
            }
        )
    }

    @Test
    fun fivePlusFiveSchoenbergExerciseEnumeratesCandidatesForEveryCMajorGMajorPivot() {
        val pivots = ModulationCommonChordCatalog.commonChords(listOf(cMajor, gMajor)).map { it.id }

        assertEquals(4, pivots.size)
        pivots.forEach { pivot ->
            val programs = SchoenbergModulation.compileCandidates(
                ModulationExerciseRequest(
                    sourceKey = cMajor,
                    targetKey = gMajor,
                    pivotChord = pivot,
                    sourceChordCount = 5,
                    targetChordCount = 5,
                    solverPreset = ModulationSolverPreset.SCHOENBERG,
                ),
                maxPrograms = 12,
            )
            assertTrue(programs.isNotEmpty(), "No symbolic modulation candidate for $pivot")
            assertTrue(programs.all { program -> program.program.slotDomains.all { it.targets.size == 1 } })
        }
    }

}
