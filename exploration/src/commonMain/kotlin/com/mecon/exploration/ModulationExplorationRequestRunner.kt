package com.mecon.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.theory.ModulationChordId
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.schoenberg.ModulationExerciseRequest
import com.mecon.theory.schoenberg.ModulationSolverPreset
import com.mecon.theory.schoenberg.SchoenbergModulation

internal object ModulationExplorationRequestRunner {
    private const val SYMBOLIC_PROGRAM_LIMIT = 96
    private const val REALIZATION_ATTEMPTS = 12

    fun run(request: ModulationExerciseCellRequest): CellOutput {
        val sourceKey = request.sourceKey.toModulationKey()
        val targetKey = request.targetKey.toModulationKey()
        val exerciseRequest = ModulationExerciseRequest(
            sourceKey = sourceKey,
            targetKey = targetKey,
            pivotChord = ModulationChordId(
                root = com.mecon.api.primitive.PitchClass(request.pivotRoot),
                quality = request.pivotQuality,
            ),
            sourceChordCount = request.sourceChordCount,
            targetChordCount = request.targetChordCount,
            solverPreset = when (request.solverPreset) {
                ModulationSolverSpec.FREE -> ModulationSolverPreset.FREE
                ModulationSolverSpec.SCHOENBERG -> ModulationSolverPreset.SCHOENBERG
            },
            searchConfig = request.search.toSearchConfig(),
        )
        val compiledPrograms = runCatching {
            SchoenbergModulation.compileCandidates(exerciseRequest, SYMBOLIC_PROGRAM_LIMIT)
        }.getOrElse { error ->
            return diagnosticOutput(
                request,
                listOf(Diagnostics.constraintInvalid(error.message ?: "Invalid modulation exercise")),
            )
        }
        if (compiledPrograms.isEmpty()) {
            return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        }
        val compiled = compiledPrograms.first()
        val solutions = if (compiledPrograms.size == 1) {
            ConstraintProgramSolver.solve(compiled.program)
        } else {
            ConstraintProgramSolver.solveFirstFeasible(
                programs = compiledPrograms.map { it.program },
                maxProgramAttempts = REALIZATION_ATTEMPTS,
            )
        }
        if (solutions.isEmpty()) {
            return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        }
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = solutions.map { solution ->
                val score = ExplorationScoreAssembler.assembleTextbookChords(
                    title = "${sourceKey.displayName} → ${targetKey.displayName} 转调",
                    keySignature = request.sourceKey.toApiKeySignature(),
                    voicings = solution.voicings,
                    measureBreakBeforeSlots = setOf(compiled.targetStartSlot),
                    showTimeSignatures = false,
                )
                OutputCandidate(
                    score = score.copy(
                        globalTrack = score.globalTrack.copy(
                            events = score.globalTrack.events + StorageKeySignatureChange(
                                onset = TimeCode.of(2, Fraction.ZERO),
                                keySignature = request.targetKey.toApiKeySignature(),
                            )
                        )
                    ),
                    totalScore = solution.breakdown.total,
                    findings = solution.breakdown.findings
                        .sortedBy { it.ruleId.value }
                        .map { it.toStoredFinding(null) },
                    breakdownEntries = solution.breakdown.contributions.map {
                        StoredScoreEntry(it.ruleId.value, it.amount, it.reason)
                    },
                )
            },
        )
    }
}
