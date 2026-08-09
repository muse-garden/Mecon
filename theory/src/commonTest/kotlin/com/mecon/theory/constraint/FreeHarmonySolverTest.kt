package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.Key
import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordMember
import com.mecon.theory.ChordMemberId
import com.mecon.theory.ChordMemberRole
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SlotWindow
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.TonalLens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreeHarmonySolverTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(
        key,
        tonicSpelling = SpelledPitchClass(NoteName.C),
    )
    private val plan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), context)))
    private val tonic = DiatonicChordVocabulary.forContext(
        context,
        compatibilityKey = key,
        includeSevenths = false,
        includeInversions = false,
    ).single { it.degree == 1 }

    @Test
    fun adjacentVoiceUnisonRemainsSolvableAndSoftInFreeAndTextbookWriting() {
        val exactUnisonPlan = exactVoicePlan(listOf("C5", "C5", "E4", "C3"))
        val freeProgram = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 1,
                vocabulary = listOf(tonic),
                voicePlan = exactUnisonPlan,
            )
        )

        listOf(
            WritingRulePreset.FREE_CLASSICAL,
            WritingRulePreset.TEXTBOOK,
            WritingRulePreset.SCHOENBERG_GENERAL,
        ).forEach { preset ->
            val solution = ConstraintProgramSolver.solvePolyphonic(
                freeProgram.copy(writingRulePreset = preset),
            ).first()
            val finding = solution.breakdown.findings
                .single { it.ruleId == AdjacentVoiceUnisonRule.RULE_ID }

            assertEquals(RuleSeverity.SOFT, finding.severity, "preset=$preset")
        }
    }

    @Test
    fun prefersDistinctAbsolutePitchesForBasicCadence() {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
        )
        val byDegree = vocabulary.associateBy { it.degree }
        val identities = listOf(1, 4, 5, 1).map { degree ->
            requireNotNull(byDegree[degree]).identityKey()
        }
        val solution = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = identities.size,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                fixedTargetIdentityBySlot = identities.withIndex().associate { it.index to it.value },
            )
        ).first()

        solution.voicings.forEach { voicing ->
            val midi = voicing.pitchesByVoiceId.values.map(Pitch::midiNumber)
            assertEquals(midi.size, midi.distinct().size, "slot=${voicing.slotIndex}, pitches=$midi")
        }
        assertFalse(
            solution.breakdown.findings.any { it.ruleId == AdjacentVoiceUnisonRule.RULE_ID },
        )
    }

    @Test
    fun acceptsInterpretationSelectedTargetsInFreeVocabulary() {
        val interpreted = SecondaryHarmonyVocabulary
            .catalog(
                context = context,
                compatibilityKey = key,
                includeModalColorChords = false,
            )
            .toInterpretedTargets(key, includeInversions = false)
            .take(2)
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 1,
                vocabulary = interpreted,
                voicePlan = exactVoicePlan(listOf("G4", "E4", "C3")),
            )
        )

        assertTrue(program.slotDomains.single().targets.all { it is InterpretedChordTarget })
    }

    @Test
    fun solvesTwoThreeAndSixVoicesThroughSamePolyphonicEntryPoint() {
        listOf(
            listOf("E4", "C3"),
            listOf("G4", "E4", "C3"),
            listOf("G4", "E4", "C4", "G3", "E3", "C3"),
        ).forEach { pitchNames ->
            val voicePlan = exactVoicePlan(pitchNames)
            val solutions = FreeHarmonySolver.solve(
                FreeHarmonyRequest(
                    key = key,
                    tonalPlan = plan,
                    slotCount = 1,
                    vocabulary = listOf(tonic),
                    voicePlan = voicePlan,
                )
            )
            assertTrue(solutions.isNotEmpty(), "Expected a solution for ${pitchNames.size} voices")
            assertEquals(
                pitchNames.size,
                solutions.first().voicings.single().pitchesByVoiceId.size,
            )
        }
    }

    @Test
    fun compilesSharedChordToneCompletenessAsHardRequirements() {
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 1,
                vocabulary = listOf(tonic),
                voicePlan = exactVoicePlan(listOf("C5", "G4", "E4", "C3")),
            )
        )

        val triad = program.toneCompleteness.filter { it.ruleId == FreeHarmonySolver.TRIAD_COMPLETE }
        val seventh = program.toneCompleteness.single { it.ruleId == FreeHarmonySolver.SEVENTH_COMPLETE }

        assertEquals(2, triad.size)
        assertTrue(triad.single { it.required }.required)
        assertEquals(
            setOf(ChordTone.ROOT, ChordTone.THIRD),
            triad.single { it.required }.requiredTones,
        )
        assertEquals(setOf(ChordTone.FIFTH), triad.single { !it.required }.requiredTones)
        assertTrue(seventh.required)
        assertEquals(setOf(ChordTone.ROOT, ChordTone.SEVENTH), seventh.requiredTones)
    }

    @Test
    fun rejectsFourRootsForAFourVoiceTonicTriad() {
        val outcome = ConstraintProgramSolver.solvePolyphonicOutcome(
            FreeHarmonySolver.compile(
                FreeHarmonyRequest(
                    key = key,
                    tonalPlan = plan,
                    slotCount = 1,
                    vocabulary = listOf(tonic),
                    voicePlan = exactVoicePlan(listOf("C5", "C4", "C3", "C2")),
                )
            )
        )

        assertTrue(outcome is ConstraintSolveOutcome.NoSolution)
    }

    @Test
    fun solvesACompleteFourVoiceTonicTriad() {
        val solution = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 1,
                vocabulary = listOf(tonic),
                voicePlan = exactVoicePlan(listOf("C5", "G4", "E4", "C3")),
            )
        ).first()
        val pitchClasses = solution.voicings.single().pitchesByVoiceId.values.map { it.pitchClass }.toSet()

        assertEquals(tonic.sonority.pitchClasses.toSet(), pitchClasses)
    }

    @Test
    fun compilesHabitualProgressionAsConstraintsWithoutFixingChordDefinitions() {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context,
            compatibilityKey = key,
            includeSevenths = true,
            includeInversions = true,
        )
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 3,
                vocabulary = vocabulary,
                voicePlan = exactVoicePlan(listOf("G4", "E4", "C3")),
                style = FreeHarmonyStyle.JAZZ,
                progressionPlacements = listOf(
                    ProgressionPlacement(HabitualProgressions.JAZZ_II_V_I, 0)
                ),
            )
        )

        assertEquals(WritingRulePreset.FREE_JAZZ, program.writingRulePreset)
        assertTrue(program.slotDomains.all { it.targets.size > 1 })
        assertEquals(
            3,
            program.constraints.count { it.ruleId?.value?.startsWith("free.progression.jazz-ii-v-i") == true },
        )
        assertFalse(program.includeDerivedTextbookConstraints)
        assertTrue(program.ruleModules?.isEmpty() == true)
    }

    @Test
    fun userPitchPinHasPriorityOverGeneralPreferences() {
        val upper = TrackId("free-voice-0")
        val pinned = Pitch.fromName("E4")
        val request = FreeHarmonyRequest(
            key = key,
            tonalPlan = plan,
            slotCount = 1,
            vocabulary = listOf(tonic),
            voicePlan = exactVoicePlan(listOf("E4", "C3")),
            pitchPins = listOf(VoicePitchPin(0, upper, pinned)),
        )

        val solution = FreeHarmonySolver.solve(request).first()
        assertEquals(pinned, solution.voicings.single().pitchesByVoiceId[upper])
    }

    @Test
    fun windowFeasibilityRejectsExcessiveAdjacentSpacing() {
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 1,
                vocabulary = listOf(tonic),
                voicePlan = exactVoicePlan(listOf("E5", "C3")),
            ),
        )

        val outcome = ConstraintProgramSolver.solvePolyphonicOutcome(program)

        assertTrue(outcome is ConstraintSolveOutcome.NoSolution)
        assertTrue(
            outcome.trace.entries.flatMap { it.hardViolations }
                .any { WindowFeasibilityRuleProvider.ADJACENT_SPACING.value in it },
        )
    }

    @Test
    fun simultaneousOctaveMotionIsAllowedButLargerMotionIsPruned() {
        val upper = TrackId("free-voice-0")
        val inner = TrackId("free-voice-1")
        val bass = TrackId("free-voice-2")
        val voicePlan = VoicePlan(
            listOf(
                VoiceSpec(upper, 0, VoiceBoundary.UPPER_OUTER, VoiceRange(Pitch.fromName("C4"), Pitch.fromName("E5"))),
                VoiceSpec(inner, 1, VoiceBoundary.INNER, VoiceRange(Pitch.fromName("E3"), Pitch.fromName("G4"))),
                VoiceSpec(bass, 2, VoiceBoundary.LOWER_OUTER, VoiceRange(Pitch.fromName("C2"), Pitch.fromName("C3"))),
            ),
        )
        fun outcome(second: List<Pitch>) = ConstraintProgramSolver.solvePolyphonicOutcome(
            FreeHarmonySolver.compile(
                FreeHarmonyRequest(
                    key = key,
                    tonalPlan = plan,
                    slotCount = 2,
                    vocabulary = listOf(tonic),
                    voicePlan = voicePlan,
                    pitchPins = listOf(
                        VoicePitchPin(0, upper, Pitch.fromName("C4")),
                        VoicePitchPin(0, inner, Pitch.fromName("E3")),
                        VoicePitchPin(0, bass, Pitch.fromName("C2")),
                        VoicePitchPin(1, upper, second[0]),
                        VoicePitchPin(1, inner, second[1]),
                        VoicePitchPin(1, bass, second[2]),
                    ),
                ),
            ),
        )

        assertTrue(
            outcome(listOf(Pitch.fromName("C5"), Pitch.fromName("E4"), Pitch.fromName("C3")))
                is ConstraintSolveOutcome.Solved,
        )
        val tooLarge = outcome(
            listOf(Pitch.fromName("E5"), Pitch.fromName("G4"), Pitch.fromName("C3")),
        )
        assertTrue(tooLarge is ConstraintSolveOutcome.NoSolution)
        assertTrue(
            tooLarge.trace.entries.flatMap { it.hardViolations }
                .any { WindowFeasibilityRuleProvider.SIMULTANEOUS_LARGE_LEAPS.value in it },
        )
    }

    @Test
    fun pivotTargetCanCarrySourceAndDestinationInterpretations() {
        val destination = TonalContext.fromKey(
            Key.major(PitchClass.G),
            tonicSpelling = SpelledPitchClass(NoteName.G),
        )
        val modulationPlan = TonalPlan(
            listOf(
                TonalSpan(SlotWindow(0, 1), context),
                TonalSpan(SlotWindow(1, 2), destination),
            )
        )
        val pivotInterpretation = tonic.interpretation.copy(
            compatibleContextIds = setOf(destination.id),
        )
        val pivot = tonic.copy(
            entry = tonic.entry.copy(interpretations = listOf(pivotInterpretation)),
            interpretation = pivotInterpretation,
        )
        val destinationVocabulary = DiatonicChordVocabulary.forContext(
            destination,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
        )
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = modulationPlan,
                slotCount = 3,
                vocabulary = listOf(pivot) + destinationVocabulary,
                voicePlan = exactVoicePlan(listOf("G4", "E4", "C3")),
            )
        )

        assertTrue(program.slotDomains[1].targets.any { it.identityKey() == pivot.identityKey() })
        assertTrue(
            program.slotDomains[1].targets
                .filterIsInstance<InterpretedChordTarget>()
                .any { destination.id in it.tonalContextIds() }
        )
    }

    @Test
    fun generatedPitchesKeepFlatChordSpelling() {
        val flatContext = TonalContext.fromKey(
            Key.major(PitchClass(1)),
            tonicSpelling = SpelledPitchClass(NoteName.D, -1),
        )
        val target = interpretedTarget(
            key = Key.major(PitchClass(1)),
            context = flatContext,
            definition = BuiltInChordDefinitions.forQuality(ChordQuality.MAJOR),
            spelledRoot = SpelledPitchClass(NoteName.D, -1),
            degree = 1,
        )
        val solution = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = target.key,
                tonalPlan = TonalPlan(listOf(TonalSpan(SlotWindow(0, 0), flatContext))),
                slotCount = 1,
                vocabulary = listOf(target),
                voicePlan = exactVoicePlan(listOf("Ab4", "F4", "Db3")),
            )
        ).first()
        val bass = solution.voicings.single().pitchesByVoiceId.getValue(TrackId("free-voice-2"))

        assertEquals(NoteName.D, bass.noteName)
        assertEquals(-1, bass.chromaticOffset)
    }

    @Test
    fun diatonicVocabularyCanIncludeSharedSecondaryHarmonyTypes() {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
            includeSecondaryHarmony = true,
        )
        val secondary = vocabulary.filter { SecondaryHarmonyMetadata.familyOf(it) != null }

        assertTrue(secondary.isNotEmpty())
        assertTrue(
            secondary.any {
                SecondaryHarmonyMetadata.familyOf(it) ==
                    SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                    SecondaryHarmonyMetadata.tonicizedDegreeOf(it) == 5 &&
                    it.degree == 2 &&
                    it.quality == ChordQuality.MAJOR
            }
        )
        assertTrue(secondary.none { SecondaryHarmonyMetadata.tonicizedDegreeOf(it) == 7 })
    }

    @Test
    fun freeVocabularyCollectsEqualSonorityBeforeExpandingInterpretations() {
        val catalog = DiatonicChordVocabulary.catalog(
            context = context,
            compatibilityKey = key,
            includeSevenths = false,
            includeSecondaryHarmony = true,
        )
        val shared = catalog.entries.firstOrNull { entry ->
            entry.interpretations.any {
                InterpretationTag("function.diatonic") in it.tags
            } &&
                entry.interpretations.any {
                    SecondaryHarmonyMetadata.FAMILY_NAME in it.attributes
                }
        }
        assertNotNull(shared)

        val targets = catalog.toInterpretedTargets(key, includeInversions = false)
            .filter { it.entry.sonority.id == shared.sonority.id }
        assertTrue(targets.size > 1)
        assertEquals(targets.size, targets.map { it.interpretationIdentityKey() }.distinct().size)
        assertEquals(1, targets.map { it.sonorityIdentityKey() }.distinct().size)
    }

    @Test
    fun freeVocabularyKeepsSymmetricDiminishedSeventhUsesAsDistinctIdentities() {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
            includeDiminishedSevenths = true,
        )
        val rootless = vocabulary.filter {
            RootlessDominantNinthMetadata.isRootlessDominantNinth(it)
        }

        assertEquals(6, rootless.size)
        val sharpTwoCollection = rootless.filter {
            it.degree == 2 && it.entry.sonority.spelledRoot.chromaticOffset == 1
        }
        assertEquals(setOf(3, 5), sharpTwoCollection.mapNotNull {
            SecondaryHarmonyMetadata.tonicizedDegreeOf(it)
        }.toSet())
        assertEquals(2, sharpTwoCollection.map { it.identityKey() }.distinct().size)
    }

    @Test
    fun freeClassicalRulesResolveAppliedLeadingToneToLocalTonicInMinor() {
        val minorKey = Key.minor(PitchClass.A)
        val minorContext = TonalContext.fromKey(
            minorKey,
            tonicSpelling = SpelledPitchClass(NoteName.A),
        )
        val minorPlan = TonalPlan(listOf(TonalSpan(SlotWindow(0, 1), minorContext)))
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = minorContext,
            compatibilityKey = minorKey,
            includeSevenths = false,
            includeInversions = false,
            includeSecondaryHarmony = true,
        )
        val applied = vocabulary.single {
            SecondaryHarmonyMetadata.familyOf(it) == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                SecondaryHarmonyMetadata.tonicizedDegreeOf(it) == 5 &&
                it.quality == ChordQuality.MAJOR
        }
        val resolution = vocabulary.first {
            SecondaryHarmonyMetadata.familyOf(it) == null &&
                it.degree == 5 &&
                it.quality == ChordQuality.MINOR
        }
        val upper = TrackId("applied-upper")
        val inner = TrackId("applied-inner")
        val lower = TrackId("applied-lower")
        val voicePlan = VoicePlan(
            listOf(
                VoiceSpec(
                    upper,
                    0,
                    VoiceBoundary.UPPER_OUTER,
                    VoiceRange(Pitch.fromName("D#4"), Pitch.fromName("G4")),
                ),
                VoiceSpec(
                    inner,
                    1,
                    VoiceBoundary.INNER,
                    VoiceRange(Pitch.fromName("F#3"), Pitch.fromName("G3")),
                ),
                VoiceSpec(
                    lower,
                    2,
                    VoiceBoundary.LOWER_OUTER,
                    VoiceRange(Pitch.fromName("B2"), Pitch.fromName("E3")),
                ),
            )
        )
        fun solve(destination: Pitch) = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = minorKey,
                tonalPlan = minorPlan,
                slotCount = 2,
                vocabulary = listOf(applied, resolution),
                voicePlan = voicePlan,
                fixedTargetIdentityBySlot = mapOf(
                    0 to applied.identityKey(),
                    1 to resolution.identityKey(),
                ),
                pitchPins = listOf(
                    VoicePitchPin(0, upper, Pitch.fromName("D#4")),
                    VoicePitchPin(0, inner, Pitch.fromName("F#3")),
                    VoicePitchPin(0, lower, Pitch.fromName("B2")),
                    VoicePitchPin(1, upper, destination),
                    VoicePitchPin(1, inner, Pitch.fromName("G3")),
                    VoicePitchPin(1, lower, Pitch.fromName("E3")),
                ),
            )
        ).first()

        assertTrue(
            solve(Pitch.fromName("E4")).breakdown.findings
                .none { it.ruleId == FreeHarmonyRuleProvider.TENDENCY_TONE },
        )
        assertTrue(
            solve(Pitch.fromName("G4")).breakdown.findings
                .any { it.ruleId == FreeHarmonyRuleProvider.TENDENCY_TONE },
        )
    }

    @Test
    fun freeDefaultsNeverEmitHardFindings() {
        val solution = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 2,
                vocabulary = listOf(tonic),
                voicePlan = exactVoicePlan(listOf("G4", "E4", "C3")),
            )
        ).first()

        assertTrue(solution.breakdown.findings.none { it.severity == RuleSeverity.HARD })
    }

    @Test
    fun jazzPresetOmitsClassicalParallelPerfectPreference() {
        val powerDefinition = ChordDefinition(
            id = ChordDefinitionId("test.power"),
            members = listOf(
                ChordMember(ChordMemberId("root"), 1, 0, ChordMemberRole.STRUCTURAL),
                ChordMember(ChordMemberId("fifth"), 5, 7, ChordMemberRole.STRUCTURAL),
            ),
        )
        val first = interpretedTarget(
            key = key,
            context = context,
            definition = powerDefinition,
            spelledRoot = SpelledPitchClass(NoteName.C),
            degree = 1,
        )
        val second = interpretedTarget(
            key = key,
            context = context,
            definition = powerDefinition.copy(id = ChordDefinitionId("test.power.ii")),
            spelledRoot = SpelledPitchClass(NoteName.D),
            degree = 2,
        )
        val upper = TrackId("parallel-upper")
        val lower = TrackId("parallel-lower")
        val voicePlan = VoicePlan(
            listOf(
                VoiceSpec(
                    upper,
                    0,
                    VoiceBoundary.UPPER_OUTER,
                    VoiceRange(Pitch.fromName("G4"), Pitch.fromName("A4")),
                ),
                VoiceSpec(
                    lower,
                    1,
                    VoiceBoundary.LOWER_OUTER,
                    VoiceRange(Pitch.fromName("C4"), Pitch.fromName("D4")),
                ),
            )
        )
        val pins = listOf(
            VoicePitchPin(0, upper, Pitch.fromName("G4")),
            VoicePitchPin(0, lower, Pitch.fromName("C4")),
            VoicePitchPin(1, upper, Pitch.fromName("A4")),
            VoicePitchPin(1, lower, Pitch.fromName("D4")),
        )
        fun solve(style: FreeHarmonyStyle) = FreeHarmonySolver.solve(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = 2,
                vocabulary = listOf(first, second),
                voicePlan = voicePlan,
                style = style,
                fixedTargetIdentityBySlot = mapOf(
                    0 to first.identityKey(),
                    1 to second.identityKey(),
                ),
                pitchPins = pins,
            )
        ).first()

        assertTrue(
            solve(FreeHarmonyStyle.CLASSICAL).breakdown.findings
                .any { it.ruleId == FreeHarmonyRuleProvider.PARALLEL_PERFECT }
        )
        assertTrue(
            solve(FreeHarmonyStyle.JAZZ).breakdown.findings
                .none { it.ruleId == FreeHarmonyRuleProvider.PARALLEL_PERFECT }
        )
    }

    private fun interpretedTarget(
        key: Key,
        context: TonalContext,
        definition: ChordDefinition,
        spelledRoot: SpelledPitchClass,
        degree: Int,
    ): InterpretedChordTarget {
        val recipeId = ChordRecipeId("test.free-harmony")
        val interpretation = ChordInterpretation(
            id = InterpretationId("test.${definition.id.value}.$degree.${spelledRoot.pitchClass.value}"),
            lens = TonalLens(context.id, context),
            symbol = FunctionalChordSymbol(
                degree = degree,
                quality = definition.compatibilityQuality,
                arity = if (definition.members.size <= 3) ChordArity.TRIAD else ChordArity.SEVENTH,
            ),
            toneRoles = ChordBuilder.structuralToneRoles(definition, spelledRoot),
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, spelledRoot),
            tags = setOf(InterpretationTag("test")),
            trace = InterpretationTrace(recipeId),
        )
        return ChordCatalogCollector.collect(
            listOf(
                ConstructedChord(
                    definition = definition,
                    spelledRoot = spelledRoot,
                    interpretation = interpretation,
                    trace = ConstructionTrace(recipeId),
                )
            )
        ).toInterpretedTargets(key, includeInversions = false).single()
    }

    private fun exactVoicePlan(pitchNamesHighToLow: List<String>): VoicePlan =
        VoicePlan(
            pitchNamesHighToLow.mapIndexed { index, name ->
                val pitch = Pitch.fromName(name)
                VoiceSpec(
                    id = TrackId("free-voice-$index"),
                    order = index,
                    boundary = when (index) {
                        0 -> VoiceBoundary.UPPER_OUTER
                        pitchNamesHighToLow.lastIndex -> VoiceBoundary.LOWER_OUTER
                        else -> VoiceBoundary.INNER
                    },
                    range = VoiceRange(pitch, pitch),
                )
            }
        )
}
