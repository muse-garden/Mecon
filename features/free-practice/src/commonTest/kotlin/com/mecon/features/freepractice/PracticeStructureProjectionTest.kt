package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.VoicePlanScoreAssembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PracticeStructureProjectionTest {
    @Test
    fun structureProjectionRecognizesTheUntouchedTonicPractice() {
        val document = FreePracticePreset.document()
        val runtime = runtime(document)

        val view = PracticeStructureProjector.project(
            selection = emptyList(),
            runtime = runtime,
            workspace = document.workspace,
            settings = document.settings,
        )

        assertTrue(view.pristine)
        assertEquals(1, view.lastMeasure)
        assertEquals(runtime.defaultTimeSignature, view.effectiveTimeSignature)
        assertEquals(false, view.rewriteSelectionAvailable)
    }

    @Test
    fun timelineSynchronizerTrimsOnlyTheEmptyScoreTail() {
        val document = FreePracticePreset.document()
        val oneMeasure = runtime(document)
        val threeMeasures = assertNotNull(MeasureEditEngine.insertAfter(oneMeasure, 1, 2))

        assertEquals(
            listOf(Fraction.HALF, Fraction.ONE, Fraction(3, 2)),
            PracticeTimelineScoreSynchronizer.measureBoundaries(threeMeasures),
        )

        val synchronized = PracticeTimelineScoreSynchronizer.synchronize(
            score = threeMeasures,
            workspace = document.workspace,
            trimEmptyTail = true,
        )
        assertEquals(listOf(1), synchronized.measures.map { it.value.number })
    }

    private fun runtime(document: com.mecon.exploration.FreePracticeDocument): RuntimeScore {
        val key = if (document.settings.initialKey.mode == KeyModeSpec.MAJOR) {
            KeySignature.majorByFifths(document.settings.initialKey.fifths)
        } else {
            KeySignature.minorByFifths(document.settings.initialKey.fifths)
        }
        return RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(
                document.workspace,
                key,
                document.settings.staffVoices,
            ),
        )
    }
}
