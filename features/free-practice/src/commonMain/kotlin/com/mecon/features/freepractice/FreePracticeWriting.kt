package com.mecon.features.freepractice

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.SearchCancellation
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintSolveContext
import com.mecon.theory.constraint.ConstraintSolveOutcome
import com.mecon.theory.freepractice.FreePracticeWindowVoicer
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.PracticeWritingScope
import com.mecon.theory.freepractice.PracticeWritingTrigger
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.writing.VoicingEventPlanner
import com.mecon.theory.writing.VoicingPlanFrame

data class FreePracticeMaterializedWriting(
    val score: RuntimeScore,
    val editInterval: TimeRange,
    val eventIdsBySlotAndVoice: Map<Pair<WorkspaceSlotId, TrackId>, List<EventId>>,
)

/** Common materializer consumed by desktop and JS; solver internals never cross the worker wire. */
object FreePracticeVoicingMaterializer {
    fun materialize(
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
        candidate: PracticeVoicingCandidate,
    ): FreePracticeMaterializedWriting {
        require(candidate.frames.isNotEmpty())
        val coveredScore = VoicePlanScoreAssembler.ensureTimelineMeasures(score, workspace)
        val timeMap = ScoreTimeMap.from(coveredScore)
        val slotsById = workspace.slots.associateBy { it.id }
        val slots = candidate.frames.map { frame ->
            requireNotNull(slotsById[frame.slotId]) {
                "Writing slot ${frame.slotId.value} no longer exists"
            }
        }
        val voiceIds = workspace.voices.sortedBy { it.order }.map { it.id }
        candidate.frames.forEach { frame ->
            require(frame.pitchesByVoiceId.keys == voiceIds.toSet()) {
                "Writing candidate must contain every configured voice exactly once"
            }
        }
        val eventPlan = VoicingEventPlanner.plan(
            frames = candidate.frames.mapIndexed { index, frame ->
                val slot = slots[index]
                VoicingPlanFrame(
                    slotKey = slot.id,
                    onset = timeMap.timeCodeAt(slot.onset),
                    duration = slot.duration,
                    pitchesByVoiceId = frame.pitchesByVoiceId,
                )
            },
            voiceIds = voiceIds,
        )
        val noteKeys = eventPlan.map { it.slotKey to it.voiceId }
        val notes = eventPlan.map { cell ->
            NoteEditEngine.RangeNote(
                voiceTrackId = cell.voiceId,
                start = cell.onset,
                duration = cell.duration,
                pitch = cell.pitch,
            )
        }
        val start = timeMap.timeCodeAt(slots.first().onset)
        val end = timeMap.timeCodeAt(slots.last().onset + slots.last().duration)
        val replaced = NoteEditEngine.replaceRange(
            runtime = coveredScore,
            voiceTrackIds = voiceIds.toSet(),
            start = start,
            end = end,
            notes = notes,
        )
        return FreePracticeMaterializedWriting(
            score = replaced.score,
            editInterval = replaced.editInterval,
            eventIdsBySlotAndVoice = replaced.insertedEventIdsByNoteIndex.mapKeys { (index, _) ->
                noteKeys[index]
            },
        )
    }
}

/** Executes immutable requests outside [FreePracticeSession]. */
object FreePracticeBackgroundExecutor {
    fun execute(
        request: PracticeBackgroundRequest,
        cancellation: SearchCancellation = SearchCancellation.NONE,
    ): PracticeBackgroundResult {
        if (cancellation.isCancelled()) return request.result(PracticeWritingOutcome.Cancelled)
        val runtime = RuntimeScore.fromStorage(request.score)
        val scope = PracticeWritingScope(
            slotIds = request.scopeSlotIds,
            triggerSlotId = request.triggerSlotId,
            leftBoundarySlotId = request.leftBoundarySlotId,
            trigger = if (request.kind == PracticeBackgroundRequestKind.OPTIMIZE_CANDIDATES) {
                PracticeWritingTrigger.ALTERNATE
            } else {
                PracticeWritingTrigger.SELECTION_REWRITE
            },
        )
        val key = request.document.settings.initialKey.let {
            ModulationKey(
                it.fifths,
                if (it.mode == KeyModeSpec.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
            )
        }
        val config = if (request.kind == PracticeBackgroundRequestKind.FIRST_SOLVE) {
            SearchConfig(
                maxResults = request.search.maxResults,
                beamWidth = request.search.beamWidth,
                prefixDiversity = PrefixDiversitySearchConfig(enabled = true, frontierWidth = 8),
            )
        } else {
            SearchConfig(
                maxResults = request.search.maxResults,
                beamWidth = request.search.beamWidth,
                prefixDiversity = PrefixDiversitySearchConfig(enabled = true, frontierWidth = 24),
                diversity = DiversitySearchConfig(
                    enabled = true,
                    seed = request.search.seed,
                    penaltyMutationBias = 2.0,
                ),
            )
        }
        val solved = FreePracticeWindowVoicer.solve(
            workspace = request.document.workspace,
            score = runtime,
            scope = scope,
            fallbackKey = key,
            searchConfig = config,
            context = ConstraintSolveContext(
                preserveProjectedBaseline = request.kind == PracticeBackgroundRequestKind.FIRST_SOLVE,
                excludedDiversityGroupKeys = request.excludedDiversityGroupKeys,
                cancellation = cancellation,
            ),
        ).outcome
        return when (solved) {
            is ConstraintSolveOutcome.Solved -> {
                val candidates = solved.solutions.map { solution ->
                    PracticeVoicingCandidate(
                        frames = solution.voicings.mapIndexed { index, voicing ->
                            PracticeVoicingFrame(scope.slotIds[index], voicing.pitchesByVoiceId)
                        },
                        diversityGroupKey = solution.diversityGroupKey,
                        score = solution.breakdown.total,
                        diagnosticKeys = solution.breakdown.findings.map { it.ruleId.value }.distinct(),
                    )
                }
                request.result(
                    outcome = PracticeWritingOutcome.Solved(scope.slotIds, replayRange = null),
                    candidates = candidates,
                )
            }
            is ConstraintSolveOutcome.NoSolution -> request.result(PracticeWritingOutcome.NoSolution)
            is ConstraintSolveOutcome.BudgetExhausted -> request.result(PracticeWritingOutcome.BudgetExhausted)
            is ConstraintSolveOutcome.Cancelled -> request.result(PracticeWritingOutcome.Cancelled)
            is ConstraintSolveOutcome.Invalid -> request.result(
                PracticeWritingOutcome.Invalid(
                    solved.diagnostics.map {
                        PracticeDiagnostic(
                            code = it.code.name,
                            messageKey = "freePractice.writing.invalid.${it.code.name.lowercase()}",
                            arguments = buildMap {
                                it.ruleId?.let { id -> put("ruleId", id.value) }
                                it.slotIndex?.let { index -> put("slotIndex", index.toString()) }
                            },
                        )
                    }
                )
            )
        }
    }

    private fun PracticeBackgroundRequest.result(
        outcome: PracticeWritingOutcome,
        candidates: List<PracticeVoicingCandidate> = emptyList(),
    ) = PracticeBackgroundResult(
        requestId = requestId,
        baseRevision = baseRevision,
        scopeFingerprint = scopeFingerprint,
        kind = kind,
        candidates = candidates,
        outcome = outcome,
    )
}
