package com.mecon.exploration

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.schoenberg.SchoenbergCadenceOptions
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergSymbolicChord
import com.mecon.theory.schoenberg.SchoenbergSymbolicProgression
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

internal object SchoenbergExplorationRequestRunner {
    fun run(request: SchoenbergExerciseRequest): CellOutput {
        if (SchoenbergCommonToneExercises.exerciseDescriptors.none { it.exerciseId == request.exerciseId }) {
            return diagnosticOutput(
                request,
                listOf(Diagnostics.invalidSelection(request.exerciseId, "未知勋伯格练习。")),
            )
        }
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(request.exerciseId)
        val selections = request.selections
        descriptor.selectionValidationError(
            selections = selections,
            hasChordFilters = request.chordFilters.isNotEmpty(),
            enabledOptions = request.enabledSchoenbergOptions(),
        )?.let { message ->
            return diagnosticOutput(
                request,
                listOf(Diagnostics.invalidSelection(request.exerciseId, message)),
            )
        }

        val key = request.key.toTheoryKey()
        val chordSelectors = request.chordFilters.map { it.toTargetSelector() }
        val selectedProgression = request.progression?.let { selected ->
            runCatching { selected.toSchoenbergProgression() }.getOrElse { error ->
                return diagnosticOutput(
                    request,
                    listOf(Diagnostics.constraintInvalid(error.message ?: "Invalid Schoenberg progression")),
                )
            }.also { parsed ->
                SchoenbergCommonToneExercises.progressionSelectionError(
                    progression = parsed,
                    key = key,
                    chordSelectors = chordSelectors,
                    selections = selections,
                )?.let { message ->
                    return diagnosticOutput(
                        request,
                        listOf(Diagnostics.constraintInvalid(message)),
                    )
                }
            }
        }
        val cadenceOptions = SchoenbergCadenceOptions(
            includeDeceptiveCadence = request.includeDeceptiveCadence,
            includeCadentialSixFour = request.includeCadentialSixFour,
        )
        val progressions: List<SchoenbergSymbolicProgression?> =
            if (selectedProgression != null) {
                listOf(selectedProgression)
            } else if (descriptor.requiresEnumeratedProgression) {
                SchoenbergCommonToneExercises.enumerateForExercise(
                    exerciseId = request.exerciseId,
                    key = key,
                    continuationChordCount = request.continuationChordCount,
                    chordSelectors = chordSelectors,
                    cadenceOptions = cadenceOptions,
                    selections = selections,
                )
            } else {
                listOf(null)
            }
        if (progressions.isEmpty()) {
            return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        }

        val programs = progressions.map { progression ->
            SchoenbergCommonToneExercises.programForExercise(
                exerciseId = request.exerciseId,
                key = key,
                continuationChordCount = request.continuationChordCount,
                progression = progression,
                searchConfig = request.search.toSearchConfig(),
                cadenceOptions = cadenceOptions,
                sourceModulationKey = request.key.toModulationKey(),
                selections = selections,
            )
        }
        val solutions = if (selectedProgression != null || programs.size == 1) {
            ConstraintProgramSolver.solve(programs.single())
        } else {
            ConstraintProgramSolver.solveFirstFeasible(
                programs = programs,
                maxProgramAttempts = if (descriptor.exhaustEnumeratedPrograms) programs.size else 8,
            )
        }
        if (solutions.isEmpty()) {
            return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        }
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = solutions.map { solution ->
                OutputCandidate(
                    score = ExplorationScoreAssembler.assembleTextbookChords(
                        title = SchoenbergCommonToneExercises.scoreTitle(request.exerciseId),
                        keySignature = request.key.toApiKeySignature(),
                        voicings = solution.voicings,
                        program = programs.firstOrNull(),
                    ),
                    totalScore = solution.breakdown.total,
                    findings = solution.breakdown.findings
                        .sortedBy { finding ->
                            if (
                                finding.ruleId.value.startsWith("schoenberg.") ||
                                finding.ruleId.value.startsWith("solver.constraint.knowledge.")
                            ) {
                                0
                            } else {
                                1
                            }
                        }
                        .map { it.toStoredFinding(null) },
                    breakdownEntries = solution.breakdown.contributions.map {
                        StoredScoreEntry(
                            ruleId = it.ruleId.value,
                            amount = it.amount,
                            reason = it.reason,
                        )
                    },
                )
            },
        )
    }

    private fun SymbolicProgression.toSchoenbergProgression(): SchoenbergSymbolicProgression =
        SchoenbergSymbolicProgression(
            slots = slots.map { slot ->
                SchoenbergSymbolicChord(
                    degree = slot.degree,
                    quality = ChordQuality.valueOf(slot.quality),
                    position = if (slot.arity == ChordArity.TRIAD.name) {
                        TextbookTriadPosition.valueOf(slot.position)
                    } else {
                        TextbookTriadPosition.ROOT_POSITION
                    },
                    arity = ChordArity.valueOf(slot.arity),
                    seventhPosition = if (slot.arity == ChordArity.SEVENTH.name) {
                        TextbookSeventhPosition.valueOf(slot.position)
                    } else {
                        null
                    },
                    rootAlteration = slot.rootAlteration,
                    appliedToDegree = slot.appliedToDegree,
                    secondaryFamily = slot.secondaryFamily?.let {
                        com.mecon.theory.constraint.SecondaryHarmonyFamily.valueOf(it)
                    },
                    modalOrigins = slot.modalOrigins.mapTo(linkedSetOf()) {
                        com.mecon.theory.Mode.valueOf(it)
                    },
                    rootlessDominantNinthChordId = slot.rootlessDominantNinthChordId,
                    rootlessDominantNinthUsageId = slot.rootlessDominantNinthUsageId,
                    omittedRootDegree = slot.omittedRootDegree,
                    omittedRootAlteration = slot.omittedRootAlteration,
                )
            },
        )
}
