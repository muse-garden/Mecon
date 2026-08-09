package com.mecon.exploration.schoenberg

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TrackId
import com.mecon.exploration.KeySpec
import com.mecon.exploration.SchoenbergExerciseRequest
import com.mecon.exploration.SearchSpec
import com.mecon.exploration.SolveRequest
import com.mecon.exploration.SolverEngine
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergDistantTonalPaths
import com.mecon.theory.schoenberg.SchoenbergExerciseSelectionKeys
import com.mecon.theory.schoenberg.TonalConfirmationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchoenbergDistantModulationExplorationTest {
    @Test
    fun establishedSharpExerciseOutputsTwoFourAndMaterializedSustainedTone() {
        val result = SolverEngine.solve(
            SolveRequest(
                convenience = SchoenbergExerciseRequest(
                    key = KeySpec(fifths = 0),
                    exerciseId = SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID,
                    continuationChordCount = 1,
                    selections = mapOf(
                        SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH to
                            listOf(SchoenbergDistantTonalPaths.THREE_SHARPS.id.value),
                        SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION to
                            listOf(TonalConfirmationLevel.ESTABLISHED.name),
                    ),
                    search = SearchSpec(maxResults = 1, beamWidth = 192),
                )
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        val score = result.output.candidates.single().score
        assertEquals(2, score.defaultTimeSignature.numerator)
        assertEquals(4, score.defaultTimeSignature.denominator)
        assertEquals(3, score.measures[1].keySignature?.fifths)
        assertEquals(null, score.measures.last().keySignature)
        val soprano = score.voiceTracks.getValue(TrackId("solver-soprano"))
        val sustainedStart = soprano.events.single { it.id.value.endsWith("sustain-start") }
        val sustainedEnd = soprano.events.single { it.id.value.endsWith("sustain-end") }
        val successor = soprano.events.single { it.id.value.endsWith("sustain-successor") }

        assertEquals(1, sustainedStart.ties.size)
        assertEquals(Fraction.ZERO, sustainedEnd.onset.beat)
        assertEquals(Fraction.QUARTER, successor.onset.beat)
        assertTrue(successor.pitchEventId != sustainedEnd.pitchEventId)
    }
}
