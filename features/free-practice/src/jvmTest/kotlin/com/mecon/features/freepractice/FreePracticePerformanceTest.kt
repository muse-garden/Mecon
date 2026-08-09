package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.runtime.RuntimeScore
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceSlotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

class FreePracticePerformanceTest {
    @Test
    fun timelinePreviewCommitAndCancelStayBoundedAt32And64Slots() {
        listOf(4 to 32, 6 to 64).forEach { (voiceCount, slotCount) ->
            val session = session(voiceCount, slotCount)
            val target = session.frame().document.workspace.slots.last()
            fun preview(sample: Int) {
                val result = session.previewTimelineEdit(
                    PracticeTimelinePreviewRequest(
                        requestId = sample.toLong(),
                        baseRevision = session.frame().revision,
                        edit = PracticeTimelineEdit.PlaceChordRange(
                            target.id,
                            target.onset,
                            if (sample % 2 == 0) Fraction.EIGHTH else Fraction.QUARTER,
                        ),
                    ),
                )
                assertTrue(result.accepted)
            }

            fun commit(sample: Int) {
                val frame = session.frame()
                val result = session.dispatch(
                    FreePracticeIntent.TimelineEdit(
                        frame.revision,
                        PracticeTimelineEdit.PlaceChordRange(
                            target.id,
                            target.onset,
                            if (sample % 2 == 0) Fraction.EIGHTH else Fraction.QUARTER,
                        ),
                    ),
                )
                assertTrue(result.effect.kind in setOf(FreePracticeEffectKind.APPLIED, FreePracticeEffectKind.NO_OP))
            }
            // Without warm-up the first samples are dominated by class loading and interpretation,
            // and JIT noise alone would swallow any regression the thresholds are meant to catch.
            repeat(WARMUP_COUNT) { sample ->
                preview(sample)
                commit(sample)
            }
            val previews = List(SAMPLE_COUNT) { sample -> measured { preview(sample) } }
            val commits = List(SAMPLE_COUNT) { sample -> measured { commit(sample) } }
            val cancellations = List(SAMPLE_COUNT) {
                measured {
                    val requested = session.dispatch(
                        FreePracticeIntent.RunWriting(session.frame().revision, target.id),
                    )
                    assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, requested.effect.kind)
                    val cancelled = session.dispatch(
                        FreePracticeIntent.CancelWriting(requested.frame.revision),
                    )
                    assertEquals(FreePracticeEffectKind.WRITING_CANCELLED, cancelled.effect.kind)
                }
            }
            val metrics = listOf(
                "preview" to previews,
                "commit" to commits,
                "cancel" to cancellations,
            )
            metrics.forEach { (phase, samples) ->
                val p50 = percentile(samples, 0.50)
                val p95 = percentile(samples, 0.95)
                println("free-practice $voiceCount voices/$slotCount slots $phase p50=$p50 p95=$p95")
                assertTrue(p95 < MAX_P95, "$voiceCount voices/$slotCount slots $phase p95=$p95")
            }
        }
    }

    private fun session(voiceCount: Int, slotCount: Int): FreePracticeSession {
        val preset = FreePracticePreset.document(voiceCount)
        val template = preset.workspace.slots.single()
        val workspace = preset.workspace.copy(
            slots = List(slotCount) { index ->
                WorkspaceHarmonySlot(
                    id = WorkspaceSlotId("slot-$index"),
                    onset = Fraction.QUARTER * index,
                    duration = Fraction.QUARTER,
                    chordChoice = template.chordChoice,
                    tonalLayoutId = template.tonalLayoutId,
                )
            },
        )
        val document = preset.copy(
            settings = preset.settings.copy(
                writing = preset.settings.writing.copy(autoWritingEnabled = false),
            ),
            workspace = workspace,
        )
        val score = VoicePlanScoreAssembler.emptyPracticeScore(
            workspace,
            com.mecon.api.primitive.KeySignature.majorByFifths(0),
            document.settings.staffVoices,
        )
        return FreePracticeSession.open(document, RuntimeScore.fromStorage(score))
    }

    private inline fun measured(block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow()
    }

    private fun percentile(samples: List<Duration>, percentile: Double): Duration {
        val sorted = samples.sorted()
        val index = ((sorted.lastIndex * percentile).toInt()).coerceIn(sorted.indices)
        return sorted[index]
    }

    private companion object {
        const val WARMUP_COUNT = 20
        const val SAMPLE_COUNT = 40

        /**
         * Warmed-up preview/commit at 64 slots measures in the low single-digit milliseconds, and
         * cancel is dominated by one `computeScore` of the practice score. The budget is a few times
         * the observed cost so ordinary machine noise passes while an order-of-magnitude regression —
         * an accidental whole-score scan or recompute per gesture — fails.
         */
        val MAX_P95: Duration = Duration.parse("250ms")
    }
}
