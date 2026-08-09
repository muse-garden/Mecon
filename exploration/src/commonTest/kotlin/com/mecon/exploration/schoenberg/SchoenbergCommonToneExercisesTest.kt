package com.mecon.exploration.schoenberg

import com.mecon.exploration.KeySpec
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.EnumerationRequest
import com.mecon.exploration.FormFieldKind
import com.mecon.exploration.SearchSpec
import com.mecon.exploration.SchoenbergExerciseRequest
import com.mecon.exploration.SolveRequest
import com.mecon.exploration.SolverEngine
import com.mecon.exploration.SchoenbergChordFilterSpec
import com.mecon.exploration.toKey
import com.mecon.theory.NaturalTriads
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergExerciseGroup
import com.mecon.theory.schoenberg.SchoenbergExerciseSelectionKeys
import com.mecon.theory.schoenberg.SchoenbergMinorSubdominantChapter
import com.mecon.theory.schoenberg.SchoenbergSecondaryDominantChapter
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchoenbergCommonToneExercisesTest {
    @Test
    fun firstExerciseEnumeratesRootPositionCommonToneProgressionsFromCountOnly() {
        val key = KeySpec()
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
            continuationChordCount = 2,
            key = key.toKey(),
        )

        assertTrue(progressions.isNotEmpty())
        progressions.forEach { progression ->
            assertEquals(3, progression.slots.size)
            assertEquals(1, progression.slots.first().degree)
            assertTrue(progression.slots.all { it.position.name == "ROOT_POSITION" })
            assertEquals(
                progression.slots.map { it.degree to it.quality }.size,
                progression.slots.map { it.degree to it.quality }.toSet().size,
            )
            progression.slots.zipWithNext().forEach { (before, after) ->
                assertTrue(before.sharesChordToneWith(after, key))
            }
        }
    }

    @Test
    fun firstExerciseSolvesFromContinuationCountWithoutChordInput() {
        val request = SchoenbergExerciseRequest(
            continuationChordCount = 1,
            search = SearchSpec(maxResults = 1, beamWidth = 96),
        )

        val result = SolverEngine.solve(SolveRequest(convenience = request))

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
    }

    @Test
    fun leadingTriadExerciseEnumeratesThroughSolverApi() {
        val result = SolverEngine.enumerate(
            com.mecon.exploration.EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.LEADING_TRIAD_RULE_ID.value),
                windowLimit = 3,
            )
        )

        assertTrue(result.diagnostics.isEmpty(), "诊断：${result.diagnostics}")
        assertTrue(result.progressions.isNotEmpty())
        assertTrue(result.progressions.all { progression ->
            progression.slots.map { it.degree }.let { it.size == 3 && it[1] == 7 && it[2] == 3 }
        })
    }

    @Test
    fun leadingTriadExerciseSolvesFromConvenienceRequest() {
        val request = SchoenbergExerciseRequest(
            exerciseId = SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID,
            search = SearchSpec(maxResults = 1, beamWidth = 160),
        )

        val result = SolverEngine.solve(SolveRequest(convenience = request))

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
        val findings = result.output.candidates.single().findings
        assertTrue(findings.first().ruleId.startsWith("schoenberg."))
        assertTrue(findings.any { it.ruleId == SchoenbergCommonToneExercises.LEADING_TRIAD_PREPARATION_RULE_ID.value })
        assertTrue(findings.any { it.ruleId == SchoenbergCommonToneExercises.LEADING_TRIAD_RESOLUTION_RULE_ID.value })
    }

    @Test
    fun secondaryHarmonyFieldsRoundTripFromEnumerationIntoSolve() {
        val harmonyId = SchoenbergSecondaryDominantChapter.harmonyChoices(KeySpec().toKey())
            .first { choice ->
                choice.chord.secondaryFamily?.name == "SECONDARY_DOMINANT" &&
                    choice.chord.appliedToDegree == 5
            }
            .id
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.SECONDARY_HARMONY_RULE_ID.value),
                windowLimit = SchoenbergCommonToneExercises
                    .minContinuationChordCount(SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID) + 1,
                selections = mapOf(
                    SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY to listOf(harmonyId)
                ),
            )
        )
        val selected = enumeration.progressions.first { progression ->
            progression.slots.zipWithNext().any { (applied, resolution) ->
                applied.secondaryFamily == "SECONDARY_DOMINANT" &&
                    applied.appliedToDegree == resolution.degree
            }
        }
        val applied = selected.slots.first { it.secondaryFamily != null }

        assertTrue(applied.appliedToDegree in 2..6)
        assertTrue(applied.modalOrigins.isNotEmpty())
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
                    continuationChordCount = SchoenbergCommonToneExercises
                        .minContinuationChordCount(SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID),
                    progression = selected,
                    selections = mapOf(
                        SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY to listOf(harmonyId)
                    ),
                    search = SearchSpec(maxResults = 1, beamWidth = 256),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
        val findings = result.output.candidates.first().findings
        assertTrue(
            findings.any {
                it.ruleId == SchoenbergSecondaryDominantChapter.LEADING_TONE_RULE_ID.value
            },
            "findings=${findings.map { it.ruleId }}",
        )
    }

    @Test
    fun neapolitanFieldsRoundTripFromEnumerationIntoSolve() {
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(
                    SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_RULE_ID.value
                ),
                windowLimit = 4,
            )
        )
        val selected = enumeration.progressions.first()
        val neapolitan = selected.slots.first()

        assertEquals(2, neapolitan.degree)
        assertEquals(-1, neapolitan.rootAlteration)
        assertTrue("AEOLIAN" in neapolitan.modalOrigins)
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID,
                    progression = selected,
                    search = SearchSpec(maxResults = 1, beamWidth = 256),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
        assertTrue(
            result.output.candidates.first().findings.any {
                it.ruleId == SchoenbergMinorSubdominantChapter.NEAPOLITAN_TO_SIX_FOUR_RULE_ID.value
            }
        )
    }

    @Test
    fun diminishedSeventhTwoLevelSelectionRoundTripsIntoSolve() {
        val key = KeySpec()
        val chord = SchoenbergDiminishedSeventhChapter.chordChoices(key.toKey())
            .first { it.chord.degree == 2 && it.chord.rootAlteration == 1 }
        val usage = SchoenbergDiminishedSeventhChapter.usageChoices(key.toKey(), chord.id)
            .first { it.tonicizedDegree == 5 }
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = key,
                ruleIds = listOf(SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_RULE_ID.value),
                windowLimit = 2,
                selections = mapOf(
                    SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD to listOf(chord.id),
                    SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE to listOf(usage.id),
                ),
            )
        )
        val selected = enumeration.progressions.single()
        val rootless = selected.slots.first()

        assertEquals(chord.id, rootless.rootlessDominantNinthChordId)
        assertEquals(usage.id, rootless.rootlessDominantNinthUsageId)
        assertEquals(usage.omittedRootDegree, rootless.omittedRootDegree)

        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID,
                    continuationChordCount = 1,
                    progression = selected,
                    selections = mapOf(
                        SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD to listOf(chord.id),
                        SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE to listOf(usage.id),
                    ),
                    search = SearchSpec(maxResults = 1, beamWidth = 512),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
    }

    @Test
    fun integratedEnumerationAppliesIntersectedChordProperties() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value),
                windowLimit = 6,
                chordFilters = listOf(
                    SchoenbergChordFilterSpec(degree = 7, arity = "TRIAD", inversion = 1),
                    SchoenbergChordFilterSpec(degree = 3),
                ),
            )
        )

        assertTrue(result.progressions.isNotEmpty())
        assertTrue(result.progressions.all { progression ->
            progression.slots.any { it.degree == 7 && it.arity == "TRIAD" && it.position == "FIRST_INVERSION" } &&
                progression.slots.any { it.degree == 3 }
        })
    }

    @Test
    fun firstInversionExerciseSolvesSelectedEnumeratedProgression() {
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.FIRST_INVERSION_RULE_ID.value),
                windowLimit = 2,
            )
        )
        val selected = enumeration.progressions.first()
        val request = SchoenbergExerciseRequest(
            exerciseId = SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            progression = selected,
            search = SearchSpec(maxResults = 1, beamWidth = 160),
        )

        val result = SolverEngine.solve(SolveRequest(convenience = request))

        assertTrue(result.output.diagnostics.isEmpty(), "璇婃柇锛?{result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
    }

    @Test
    fun descriptorsDeclareIndependentEnumerationAndIntegratedGroups() {
        val leading = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID,
        )
        val firstInversion = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
        )
        val integrated = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
        )

        assertEquals(SchoenbergExerciseGroup.INDEPENDENT, leading.group)
        assertTrue(leading.requiresEnumeratedProgression)
        assertEquals(SchoenbergExerciseGroup.INDEPENDENT, firstInversion.group)
        assertTrue(firstInversion.requiresEnumeratedProgression)
        assertTrue(
            SchoenbergCommonToneExercises.descriptorForExercise(
                SchoenbergCommonToneExercises.SECOND_INVERSION_EXERCISE_ID,
            ).requiresEnumeratedProgression,
        )
        assertTrue(
            SchoenbergCommonToneExercises.descriptorForExercise(
                SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID,
            ).requiresEnumeratedProgression,
        )
        assertEquals(SchoenbergExerciseGroup.INTEGRATED, integrated.group)
        assertTrue(integrated.requiresEnumeratedProgression)
    }


    @Test
    fun integratedExerciseEnumeratesLeadingTriadBeforeSolving() {
        val exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value),
                windowLimit = 5,
            )
        )
        assertTrue(enumeration.progressions.isNotEmpty())
        assertTrue(enumeration.progressions.first().slots.any { it.degree == 7 })

        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = exerciseId,
                    continuationChordCount = 4,
                    search = SearchSpec(maxResults = 1, beamWidth = 160),
                )
            )
        )
        assertTrue(result.output.diagnostics.isEmpty())
        assertTrue(result.output.candidates.isNotEmpty())
    }

    @Test
    fun integratedEnumerationHonorsPreviewBudget() {
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(
                    SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value,
                ),
                windowLimit = 5,
                maxResults = 3,
                maxVisitedNodes = 5_000,
            )
        )

        assertTrue(enumeration.progressions.isNotEmpty())
        assertTrue(enumeration.progressions.size <= 3)
    }

    @Test
    fun integratedPreviewBudgetStillFindsLongProgressions() {
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(
                    SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value,
                ),
                windowLimit = 13,
                maxResults = 8,
                maxVisitedNodes = 5_000,
            )
        )

        assertTrue(enumeration.progressions.isNotEmpty())
        assertTrue(enumeration.progressions.size <= 8)
        assertTrue(enumeration.progressions.all { it.slots.size == 13 })
    }

    @Test
    fun integratedEnumerationStopsWhenPreviewIsCancelled() {
        var probes = 0
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(
                    SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value,
                ),
                windowLimit = 13,
                maxResults = 8,
                maxVisitedNodes = 5_000,
            ),
            shouldContinue = { ++probes < 10 },
        )

        assertTrue(enumeration.progressions.isEmpty())
        assertTrue(probes <= 10)
    }


    @Test
    fun integratedLeadingAndSixthOutputContainsAllFiveChords() {
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
                    continuationChordCount = 4,
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
            )
        )

        println(
            "SCHOENBERG_INTEGRATED_OUTPUT diagnostics=" + result.output.diagnostics +
                " events=" + result.output.candidates.firstOrNull()?.score?.voiceTracks?.values?.sumOf { it.events.size },
        )
        assertTrue(result.output.diagnostics.isEmpty())
        assertEquals(20, result.output.candidates.single().score.voiceTracks.values.sumOf { it.events.size })
    }


    @Test
    fun integratedLeadingAndSixthOutputContainsAllSixChords() {
        val exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value),
                windowLimit = 6,
            )
        )

        assertTrue(enumeration.progressions.isNotEmpty())
        assertEquals(6, enumeration.progressions.first().slots.size)

        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = exerciseId,
                    continuationChordCount = 5,
                    progression = enumeration.progressions.first(),
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty())
        assertEquals(24, result.output.candidates.single().score.voiceTracks.values.sumOf { it.events.size })
    }

    @Test
    fun integratedLeadingOnlySolvesSixContinuationChords() {
        val enumeration = SolverEngine.enumerate(
            EnumerationRequest(
                key = KeySpec(),
                ruleIds = listOf(SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_RULE_ID.value),
                windowLimit = 7,
            )
        )
        println(
            "SCHOENBERG_LEADING_SIX_ENUM count=" + enumeration.progressions.size +
                " first=" + enumeration.progressions.firstOrNull()?.slots?.joinToString("-") { it.degree.toString() },
        )
        assertTrue(enumeration.progressions.isNotEmpty())
        assertEquals(7, enumeration.progressions.first().slots.size)

        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
                    continuationChordCount = 6,
                    progression = enumeration.progressions.first(),
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
            )
        )
        println(
            "SCHOENBERG_LEADING_SIX_SOLVE diagnostics=" + result.output.diagnostics +
                " candidates=" + result.output.candidates.size,
        )
        assertTrue(result.output.diagnostics.isEmpty())
        assertEquals(28, result.output.candidates.single().score.voiceTracks.values.sumOf { it.events.size })
    }

    @Test
    fun rootMotionExerciseSolvesTwelveMinorContinuationChordsWithoutSelectingAProgression() {
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    key = KeySpec(fifths = 0, mode = KeyModeSpec.MINOR),
                    exerciseId = SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID,
                    continuationChordCount = 12,
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty())
        assertTrue(result.output.candidates.isNotEmpty())
        val score = result.output.candidates.first().score
        assertEquals(52, score.voiceTracks.values.sumOf { it.events.size })
        assertEquals(listOf(1, 2, 3, 4), score.measures.map { it.number })
        assertEquals(4, score.voiceTracks.values.flatMap { it.events }.maxOf { it.onset.measure })
    }

    @Test
    fun freerExerciseWithDeceptiveCadenceSolvesAtMinimumLength() {
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    exerciseId = SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID,
                    continuationChordCount = 8,
                    includeDeceptiveCadence = true,
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
        assertEquals(36, result.output.candidates.first().score.voiceTracks.values.sumOf { it.events.size })
    }

    @Test
    fun describeExposesSchoenbergChapter() {
        val manifest = SolverEngine.describe()

        val chapter = manifest.chapters.firstOrNull { it.id == SchoenbergCommonToneExercises.CHAPTER_ID.value }
        assertTrue(chapter != null)
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.FIRST_EXERCISE_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.LEADING_TRIAD_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.FIRST_INVERSION_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.SECOND_INVERSION_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.SEVENTH_CHORD_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.CADENCE_RULE_ID.value })
        assertTrue(chapter.rules.any { it.id == SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_RULE_ID.value })
        val form = manifest.forms.firstOrNull { it.requestType == "schoenberg-exercise" }
        assertTrue(form != null)
        assertTrue(form.fields.any { it.id == "progression" && it.kind == FormFieldKind.PROGRESSION_PICKER })
        assertTrue(
            form.fields.any {
                it.id == SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY &&
                    it.kind == FormFieldKind.SELECT
            }
        )
        assertTrue(form.fields.any { it.id == "chordFilters" && it.kind == FormFieldKind.CHORD_FILTERS })
        assertTrue(form.fields.any { it.id == "includeDeceptiveCadence" && it.kind == FormFieldKind.TOGGLE })
        assertTrue(form.fields.any { it.id == "includeCadentialSixFour" && it.kind == FormFieldKind.TOGGLE })
    }

    private fun com.mecon.theory.schoenberg.SchoenbergSymbolicChord.sharesChordToneWith(
        other: com.mecon.theory.schoenberg.SchoenbergSymbolicChord,
        key: KeySpec,
    ): Boolean {
        val triads = NaturalTriads.inKey(key.toKey())
        val before = triads.first { it.degree == degree && it.quality == quality }
        val after = triads.first { it.degree == other.degree && it.quality == other.quality }
        return before.chord.pitchClasses.any { it in after.chord.pitchClasses }
    }
}
