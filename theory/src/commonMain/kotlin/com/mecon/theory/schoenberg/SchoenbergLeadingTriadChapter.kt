package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.textbook.TextbookTriadPosition

object SchoenbergLeadingTriadChapter {
    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 128),
    ): ConstraintProgram {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val slotDomains = progression?.let { exactProgressionDomains(it, triads, LEADING_TRIAD_LENGTH) }
            ?: defaultLeadingTriadDomains(triads, includeFirstInversion = false)
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = slotDomains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                avoidDoublings = listOf(
                    AvoidDoublingRequirement(
                        slot = LEADING_TRIAD_SLOT,
                        tone = ChordTone.FIFTH,
                        required = true,
                        selector = TargetSelector(degrees = setOf(LEADING_TONE_DEGREE)),
                    )
                ),
                chordToneNeighbors = leadingTriadNeighborRequirements(SlotWindow(0, LEADING_TRIAD_LENGTH - 1)),
                searchConfig = searchConfig,
                // 勋伯格模式只保留一般四部写作规则，关掉 textbook 关于具体和弦的模块与派生约束。见 §4 / AGENTS.md。
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    fun enumerate(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val leading = leadingTriad(triads)
        val fifth = leading.chord.pitchClasses[FIFTH_INDEX]
        val mediants = triads.filter { it.degree == MEDIANT_DEGREE }
        val preparations = triads
            .filterNot { it.isLeadingTriad() }
            .filter { fifth in it.chord.pitchClasses }
        return preparations.flatMap { preparation ->
            mediants.map { resolution ->
                SchoenbergSymbolicProgression(
                    slots = listOf(
                        preparation.toSymbolic(TextbookTriadPosition.ROOT_POSITION),
                        leading.toSymbolic(TextbookTriadPosition.ROOT_POSITION),
                        resolution.toSymbolic(TextbookTriadPosition.ROOT_POSITION),
                    ),
                    kind = SchoenbergConnectionKind.LEADING_PREPARATION_RESOLUTION,
                    knowledgeTags = setOf(SchoenbergKnowledgeTag.LEADING_TRIAD),
                )
            }
        }
    }

    private fun defaultLeadingTriadDomains(
        triads: List<NaturalTriad>,
        includeFirstInversion: Boolean,
    ): List<SlotDomain> {
        val leading = leadingTriad(triads)
        val fifth = leading.chord.pitchClasses[FIFTH_INDEX]
        val positions = if (includeFirstInversion) {
            listOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION)
        } else {
            listOf(TextbookTriadPosition.ROOT_POSITION)
        }
        val preparations = triads
            .filterNot { it.isLeadingTriad() }
            .filter { fifth in it.chord.pitchClasses }
            .flatMap { triad -> positions.map { triad.toTarget(it) } }
        val leadingTargets = positions.map { leading.toTarget(it) }
        val resolutions = triads
            .filter { it.degree == MEDIANT_DEGREE }
            .flatMap { triad -> positions.map { triad.toTarget(it) } }
        return listOf(SlotDomain(preparations), SlotDomain(leadingTargets), SlotDomain(resolutions))
    }

    private const val LEADING_TRIAD_SLOT = 1
    private const val LEADING_TRIAD_LENGTH = 3
}
