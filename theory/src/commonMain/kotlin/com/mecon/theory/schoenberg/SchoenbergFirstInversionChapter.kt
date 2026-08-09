package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.textbook.TextbookTriadPosition

object SchoenbergFirstInversionChapter {
    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 128),
    ): ConstraintProgram {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val slotDomains = progression?.let { exactProgressionDomains(it, triads, FIRST_INVERSION_LENGTH) }
            ?: listOf(
                SlotDomain(targets = triads.flatMap { triad ->
                    listOf(
                        triad.toTarget(TextbookTriadPosition.ROOT_POSITION),
                        triad.toTarget(TextbookTriadPosition.FIRST_INVERSION),
                    )
                }),
                SlotDomain(targets = triads.map { it.toTarget(TextbookTriadPosition.FIRST_INVERSION) }),
            )
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = slotDomains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                avoidDoublings = firstInversionAvoidDoublings(
                    length = FIRST_INVERSION_LENGTH,
                    progression = progression,
                    triads = triads,
                    required = true,
                ) + leadingFifthAvoidDoublings(FIRST_INVERSION_LENGTH, required = true),
                adjacentCommonTones = listOf(
                    AdjacentCommonToneRequirement(SlotWindow(0, FIRST_INVERSION_LENGTH - 1), holdInSameVoice = false),
                ),
                chordToneNeighbors = leadingTriadNeighborRequirements(
                    window = SlotWindow(0, FIRST_INVERSION_LENGTH - 1),
                    includeResolution = false,
                ),
                searchConfig = searchConfig,
                // 勋伯格模式只保留一般四部写作规则，关掉 textbook 关于具体和弦的模块与派生约束。见 §4 / AGENTS.md。
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    fun enumerate(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val rootToFirst = triads.flatMap { before ->
            triads
                .filter { after -> before.allowsFirstInversionConnectionTo(after) }
                .map { after ->
                    SchoenbergSymbolicProgression(
                        slots = listOf(
                            before.toSymbolic(TextbookTriadPosition.ROOT_POSITION),
                            after.toSymbolic(TextbookTriadPosition.FIRST_INVERSION),
                        ),
                        kind = SchoenbergConnectionKind.ROOT_TO_FIRST_INVERSION,
                        knowledgeTags = setOf(SchoenbergKnowledgeTag.FIRST_INVERSION),
                    )
                }
        }
        val firstToFirst = triads.flatMap { before ->
            triads
                .filter { after -> before.degree != after.degree || before.quality != after.quality }
                .filter { after -> before.allowsFirstInversionConnectionTo(after) }
                .map { after ->
                    SchoenbergSymbolicProgression(
                        slots = listOf(
                            before.toSymbolic(TextbookTriadPosition.FIRST_INVERSION),
                            after.toSymbolic(TextbookTriadPosition.FIRST_INVERSION),
                        ),
                        kind = SchoenbergConnectionKind.FIRST_TO_FIRST_INVERSION,
                        knowledgeTags = setOf(SchoenbergKnowledgeTag.FIRST_INVERSION),
                    )
                }
        }
        return rootToFirst + firstToFirst
    }

    private const val FIRST_INVERSION_LENGTH = 2
}
