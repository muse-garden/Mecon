package com.mecon.exploration

import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.RootPositionTriadSolver
import com.mecon.theory.textbook.RootPositionTriadWritingProblem
import com.mecon.theory.textbook.TextbookSeventhWritingSlot
import com.mecon.theory.textbook.TextbookSeventhWritingProblem
import com.mecon.theory.textbook.TextbookSeventhWritingSolver
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.TextbookTriadWritingProblem
import com.mecon.theory.textbook.TextbookTriadWritingSolver

internal object TextbookExplorationRunner {
    fun solveRootPosition(
        request: CellRequest,
        keySpec: KeySpec,
        triads: List<NaturalTriad>,
        requirements: List<RuleRequirement>,
        search: SearchSpec,
        demonstrationRuleId: String?,
    ): CellOutput {
        val solutions = RootPositionTriadSolver.solve(
            RootPositionTriadWritingProblem(
                key = keySpec.toTheoryKey(),
                triads = triads,
                ruleProfile = RootPositionTriadRules.INTRODUCTORY_PROFILE.copy(
                    id = "textbook.root-position-triad.exploration",
                    requirements = requirements,
                ),
                searchConfig = search.toSearchConfig(),
            )
        )
        if (solutions.isEmpty()) return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = solutions.map { solution ->
                OutputCandidate(
                    score = ExplorationScoreAssembler.assemble(
                        title = "原位三和弦写作",
                        keySignature = keySpec.toApiKeySignature(),
                        voicings = solution.voicings,
                    ),
                    totalScore = solution.breakdown.total,
                    findings = solution.breakdown.findings.map {
                        it.toStoredFinding(demonstrationRuleId)
                    },
                    breakdownEntries = solution.breakdown.contributions.map {
                        StoredScoreEntry(it.ruleId.value, it.amount, it.reason)
                    },
                )
            },
        )
    }

    fun solveTriads(
        request: CellRequest,
        keySpec: KeySpec,
        slots: List<TextbookTriadWritingSlot>,
        requirements: List<RuleRequirement>,
        search: SearchSpec,
        demonstrationRuleId: String?,
        profile: RuleProfile,
        title: String,
    ): CellOutput {
        val solutions = TextbookTriadWritingSolver.solve(
            TextbookTriadWritingProblem(
                key = keySpec.toTheoryKey(),
                slots = slots,
                ruleProfile = profile.copy(
                    id = "${profile.id}.exploration",
                    requirements = requirements,
                ),
                searchConfig = search.toSearchConfig(),
            )
        )
        if (solutions.isEmpty()) return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = solutions.map { solution ->
                OutputCandidate(
                    score = ExplorationScoreAssembler.assembleTextbookTriads(
                        title = title,
                        keySignature = keySpec.toApiKeySignature(),
                        voicings = solution.voicings,
                    ),
                    totalScore = solution.breakdown.total,
                    findings = solution.breakdown.findings.map {
                        it.toStoredFinding(demonstrationRuleId)
                    },
                    breakdownEntries = solution.breakdown.contributions.map {
                        StoredScoreEntry(it.ruleId.value, it.amount, it.reason)
                    },
                )
            },
        )
    }

    fun solveSevenths(
        request: RuleExampleRequest,
        key: Key,
        slots: List<TextbookSeventhWritingSlot>,
        requirements: List<RuleRequirement>,
        demonstrationRuleId: String?,
    ): CellOutput {
        val solutions = TextbookSeventhWritingSolver.solve(
            TextbookSeventhWritingProblem(
                key = key,
                slots = slots,
                ruleProfile = DominantSeventhRules.INTRODUCTORY_PROFILE.copy(
                    id = "textbook.dominant-seventh.exploration",
                    requirements = requirements,
                ),
                searchConfig = request.search.toSearchConfig(),
            )
        )
        if (solutions.isEmpty()) return diagnosticOutput(request, listOf(Diagnostics.noSolution))
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = solutions.map { solution ->
                OutputCandidate(
                    score = ExplorationScoreAssembler.assembleSeventhChords(
                        title = "属七和弦写作",
                        keySignature = request.key.toApiKeySignature(),
                        voicings = solution.voicings,
                    ),
                    totalScore = solution.breakdown.total,
                    findings = solution.breakdown.findings.map {
                        it.toStoredFinding(demonstrationRuleId)
                    },
                    breakdownEntries = solution.breakdown.contributions.map {
                        StoredScoreEntry(it.ruleId.value, it.amount, it.reason)
                    },
                )
            },
        )
    }
}
