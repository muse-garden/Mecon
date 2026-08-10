package com.mecon.features.freepractice

import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.PracticeNoteConstraintState
import com.mecon.exploration.PracticeNoteheadRef
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
import com.mecon.theory.freepractice.PracticeWritingLockRules
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.PracticeWritingScope
import com.mecon.theory.freepractice.PracticeWritingTrigger
import com.mecon.theory.writing.VoicingEventPlanner
import com.mecon.theory.writing.VoicingPlanFrame
import com.mecon.theory.writing.SourceNoteheadId

data class FreePracticeMaterializedWriting(
    val score: RuntimeScore,
    val editInterval: TimeRange,
)

/** Common materializer consumed by desktop and JS; solver internals never cross the worker wire. */
object FreePracticeVoicingMaterializer {
    fun materialize(
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
        candidate: PracticeVoicingCandidate,
        constraints: PracticeNoteConstraintState = PracticeNoteConstraintState(),
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
                    slotKey = frame.segmentId,
                    onset = timeMap.timeCodeAt(frame.onset ?: slot.onset),
                    duration = frame.duration ?: slot.duration,
                    pitchesByVoiceId = frame.pitchesByVoiceId,
                )
            },
            voiceIds = voiceIds,
        )
        val notes = eventPlan.map { cell ->
            NoteEditEngine.RangeNote(
                voiceTrackId = cell.voiceId,
                start = cell.onset,
                duration = cell.duration,
                pitch = cell.pitch,
            )
        }
        val startAbsolute = candidate.frames.first().onset ?: slots.first().onset
        val endAbsolute = candidate.frames.last().let { frame ->
            (frame.onset ?: slots.last().onset) + (frame.duration ?: slots.last().duration)
        }
        val staffLockedVoiceIds = coveredScore.staffTracks.values
            .filter { it.id in constraints.lockedStaffTrackIds }
            .flatMapTo(linkedSetOf()) { staff -> staff.voiceTracks.map { it.id } }
        val dynamicLockedVoiceIds = constraints.lockedVoiceTrackIds + staffLockedVoiceIds
        val lockedIntervals = coveredScore.voiceTracks.values.associate { voice ->
            if (voice.id in dynamicLockedVoiceIds) {
                return@associate voice.id to listOf(startAbsolute to endAbsolute)
            }
            voice.id to voice.events.toList().mapNotNull { event ->
                val exact = event.pitches.indices.any { index ->
                    PracticeNoteheadRef(event.id, index) in constraints.lockedNoteheads
                }
                if (!exact) return@mapNotNull null
                val onset = timeMap.absolute(event.onset)
                val end = onset + event.duration.toFraction()
                if (onset < endAbsolute && end > startAbsolute) onset to end else null
            }
        }
        var rewritten = coveredScore
        voiceIds.forEach { voiceId ->
            val blockers = lockedIntervals[voiceId].orEmpty()
                .map { (start, end) -> maxOf(startAbsolute, start) to minOf(endAbsolute, end) }
                .sortedBy { it.first }
            val writable = buildList {
                var cursor = startAbsolute
                blockers.forEach { (lockedStart, lockedEnd) ->
                    if (lockedStart > cursor) add(cursor to lockedStart)
                    if (lockedEnd > cursor) cursor = lockedEnd
                }
                if (cursor < endAbsolute) add(cursor to endAbsolute)
            }
            writable.forEach { (rangeStart, rangeEnd) ->
                val rangeNotes = notes.filter { note ->
                    note.voiceTrackId == voiceId &&
                        timeMap.absolute(note.start) >= rangeStart &&
                        timeMap.absolute(note.start) < rangeEnd
                }
                rewritten = NoteEditEngine.replaceRange(
                    runtime = rewritten,
                    voiceTrackIds = setOf(voiceId),
                    start = timeMap.timeCodeAt(rangeStart),
                    end = timeMap.timeCodeAt(rangeEnd),
                    notes = rangeNotes,
                ).score
            }
        }
        return FreePracticeMaterializedWriting(
            score = rewritten,
            editInterval = TimeRange(timeMap.timeCodeAt(startAbsolute), timeMap.timeCodeAt(endAbsolute)),
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
        val solveResult = FreePracticeWindowVoicer.solve(
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
            lockRules = request.document.noteConstraints.let { constraints ->
                PracticeWritingLockRules(
                    lockedNoteheads = constraints.lockedNoteheads.mapTo(linkedSetOf()) {
                        SourceNoteheadId(it.eventId, it.pitchIndex)
                    },
                    lockedVoiceTrackIds = constraints.lockedVoiceTrackIds,
                    lockedStaffTrackIds = constraints.lockedStaffTrackIds,
                    explicitChordTones = constraints.harmonicRoles
                        .filter { it.role == com.mecon.exploration.PracticeHarmonicRole.CHORD_TONE }
                        .mapTo(linkedSetOf()) { SourceNoteheadId(it.notehead.eventId, it.notehead.pitchIndex) },
                )
            },
        )
        val solved = solveResult.outcome
        return when (solved) {
            is ConstraintSolveOutcome.Solved -> {
                val candidates = solved.solutions.map { solution ->
                    PracticeVoicingCandidate(
                        frames = solution.voicings.mapIndexed { index, voicing ->
                            val segment = solveResult.segments[index]
                            PracticeVoicingFrame(
                                slotId = segment.workspaceSlotId,
                                pitchesByVoiceId = voicing.pitchesByVoiceId,
                                segmentId = segment.id.value,
                                onset = segment.onset,
                                duration = segment.duration,
                            )
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
