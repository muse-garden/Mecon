package com.mecon.theory.schoenberg

import com.mecon.api.primitive.Fraction
import com.mecon.theory.BeatWeight
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.HarmonicVoiceParticipation
import com.mecon.theory.constraint.WritingRulePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchoenbergDistantModulationChapterTest {
    private val cMajor = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun lightAndEstablishedAreIndependentPrograms() {
        val light = compile(SchoenbergDistantTonalPaths.THREE_SHARPS, TonalConfirmationLevel.LIGHT)
        val established = compile(
            SchoenbergDistantTonalPaths.THREE_SHARPS,
            TonalConfirmationLevel.ESTABLISHED,
        )

        assertNull(light.sustainedWindow)
        assertNotNull(established.sustainedWindow)
        assertTrue(light.program.length < established.program.length)
        assertEquals(WritingRulePreset.SCHOENBERG_GENERAL, established.program.writingRulePreset)
        assertTrue(light.program.ruleModules?.isEmpty() == true)
        assertTrue(!light.program.includeDerivedTextbookConstraints)
    }

    @Test
    fun establishedSharpPathPlacesReleaseAtWeakBeatInsideFinalDominant() {
        val compiled = compile(
            SchoenbergDistantTonalPaths.FOUR_SHARPS,
            TonalConfirmationLevel.ESTABLISHED,
        )
        val window = assertNotNull(compiled.sustainedWindow)
        val finalDominant = window.end!!
        val finalSpan = compiled.program.slots[finalDominant].time
        val release = compiled.program.texturePlan.sustainedToneReleases.single()

        assertEquals(Fraction.HALF, finalSpan.duration)
        assertEquals(BeatWeight.STRONG, compiled.program.meterPlan.beatWeightAt(finalSpan.onset))
        assertEquals(BeatWeight.WEAK, compiled.program.meterPlan.beatWeightAt(release.releaseOnset))
        assertTrue(
            compiled.program.texturePlan.participations.single().participation
                is HarmonicVoiceParticipation.Sustained
        )
        assertEquals(finalSpan.onset + Fraction.QUARTER, release.releaseOnset)
    }

    @Test
    fun flatEstablishedUsesVariationWithoutSustainedTexture() {
        val compiled = compile(
            SchoenbergDistantTonalPaths.FOUR_FLATS,
            TonalConfirmationLevel.ESTABLISHED,
        )

        assertNull(compiled.sustainedWindow)
        assertTrue(compiled.program.texturePlan.participations.isEmpty())
        assertEquals(listOf(5, 6, 2, 5, 1), compiled.program.slotDomains.takeLast(5).map {
            it.targets.single().degree
        })
    }

    @Test
    fun pivotRecommendationsFollowNearAndThreeFourSignatureRules() {
        listOf(1, 2).forEach { distance ->
            val target = ModulationKey(distance, KeySignatureMode.MAJOR)
            val recipes = SchoenbergDistantModulationChapter.pivotRecipes(cMajor, target)
            assertTrue(recipes.isNotEmpty())
            assertTrue(recipes.all { recipe ->
                com.mecon.theory.NaturalTriads.matchesPitchClassValues(
                    cMajor.key,
                    recipe.pitchClasses,
                ).isNotEmpty() && com.mecon.theory.NaturalTriads.matchesPitchClassValues(
                    target.key,
                    recipe.pitchClasses,
                ).isNotEmpty()
            })
        }

        listOf(3, 4).forEach { distance ->
            val recipe = SchoenbergDistantModulationChapter.pivotRecipes(
                cMajor,
                ModulationKey(distance, KeySignatureMode.MAJOR),
            ).single()
            assertEquals(setOf(4, 8, 11), recipe.pitchClasses)
            assertTrue(recipe.sourceReading.contains("3–♯5–7"), recipe.sourceReading)
        }
        mapOf(-3 to setOf(2, 7, 11), -4 to setOf(0, 4, 7)).forEach { (distance, pitches) ->
            val recipe = SchoenbergDistantModulationChapter.pivotRecipes(
                cMajor,
                ModulationKey(distance, KeySignatureMode.MAJOR),
            ).single()
            assertEquals(pitches, recipe.pitchClasses)
            assertTrue(recipe.targetReading.contains("3–♯5–7"), recipe.targetReading)
        }
    }

    @Test
    fun standaloneDominantPedalReusesTheEstablishedWindowShape() {
        assertEquals(
            listOf(5, 6, 5),
            SchoenbergDistantModulationChapter.dominantSustainedProgression(cMajor)
                .map { it.degree },
        )
        assertEquals(
            listOf(Fraction.QUARTER, Fraction.QUARTER, Fraction.HALF),
            SchoenbergDistantModulationChapter.dominantSustainedDurations(),
        )
    }

    @Test
    fun everyPathChangesKeySignatureAtStrongBeatWhereTargetContextBegins() {
        SchoenbergDistantTonalPaths.all.forEach { path ->
            TonalConfirmationLevel.entries.forEach { level ->
                val compiled = compile(path, level)
                val targetContextId = compiled.path.target.context.id
                val targetEntry = compiled.program.slots.indices.indexOfFirst { slot ->
                    compiled.program.tonalPlan.contextsAt(slot).any { it.id == targetContextId }
                }
                assertTrue(targetEntry >= 0, "Missing target context for ${path.id}/$level")
                val targetOnset = compiled.program.slots[targetEntry].time.onset

                assertEquals(
                    BeatWeight.STRONG,
                    compiled.program.meterPlan.beatWeightAt(targetOnset),
                    "Target context must begin on a strong beat for ${path.id}/$level",
                )
                assertEquals(
                    mapOf(targetOnset.measure to compiled.path.target.key.keySignature),
                    compiled.program.keySignatureChangesByMeasure,
                    "Key signature must change at target entry for ${path.id}/$level",
                )
            }
        }
    }

    @Test
    fun representativeLightAndEstablishedProgramsAreWritable() {
        listOf(
            compile(SchoenbergDistantTonalPaths.THREE_SHARPS, TonalConfirmationLevel.LIGHT),
            compile(SchoenbergDistantTonalPaths.THREE_SHARPS, TonalConfirmationLevel.ESTABLISHED),
            compile(SchoenbergDistantTonalPaths.THREE_FLATS, TonalConfirmationLevel.ESTABLISHED),
        ).forEach { compiled ->
            val trace = ConstraintProgramSolver.trace(compiled.program)
            assertTrue(
                trace.solutions.isNotEmpty(),
                "Expected ${compiled.path.templateId}/${compiled.confirmationLevel} to be writable; " +
                    "visited=${trace.trace.visitedNodes}, exhausted=${trace.trace.exhaustedBudget}, " +
                    "tail=${trace.trace.entries.takeLast(6)}",
            )
        }
    }

    private fun compile(
        template: TonalPathTemplate,
        level: TonalConfirmationLevel,
    ) = SchoenbergDistantModulationChapter.compile(
        DistantModulationExerciseRequest(
            sourceKey = cMajor,
            pathId = template.id,
            confirmationLevel = level,
        )
    )
}
