package com.mecon.exploration

import com.mecon.theory.NaturalTriad
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey

internal const val FREE_TRIADS_POLICY = "free-triads"
internal const val FIRST_INVERSION_TRIADS_POLICY = "first-inversion-triads"
internal const val SECOND_INVERSION_TRIADS_POLICY = "second-inversion-triads"

internal object ProgressionRequestRunner {
    fun run(request: ProgressionRequest): CellOutput {
        val key = request.key.toTheoryKey()
        val textbookPolicy = request.policyId in setOf(
            FREE_TRIADS_POLICY,
            FIRST_INVERSION_TRIADS_POLICY,
            SECOND_INVERSION_TRIADS_POLICY,
        )
        if (!textbookPolicy) {
            return TextbookExplorationRunner.solveRootPosition(
                request = request,
                keySpec = request.key,
                triads = request.slots.map { textbookTriadInKey(key, it.degree.degree) },
                requirements = emptyList(),
                search = request.search,
                demonstrationRuleId = null,
            )
        }
        val secondInversion = request.policyId == SECOND_INVERSION_TRIADS_POLICY
        val degrees = if (secondInversion) {
            request.slots.map { it.degree.degree }.ensureSecondInversionProgression()
        } else {
            request.slots.map { it.degree.degree }
        }
        val triads = degrees.map { textbookTriadInKey(key, it) }
        val slots = when (request.policyId) {
            FREE_TRIADS_POLICY -> triads.map(TextbookTriadWritingSlot::rootOrFirstInversion)
            FIRST_INVERSION_TRIADS_POLICY -> triads.map(TextbookTriadWritingSlot::firstInversion)
            else -> secondInversionProgressionSlots(triads)
        }
        return TextbookExplorationRunner.solveTriads(
            request = request,
            keySpec = request.key,
            slots = slots,
            requirements = emptyList(),
            search = request.search,
            demonstrationRuleId = null,
            profile = if (secondInversion) {
                SecondInversionTriadRules.INTRODUCTORY_PROFILE
            } else {
                FirstInversionTriadRules.INTRODUCTORY_PROFILE
            },
            title = if (secondInversion) {
                "三和弦第二转位写作"
            } else {
                "三和弦第一转位写作"
            },
        )
    }

    private fun secondInversionProgressionSlots(
        triads: List<NaturalTriad>,
    ): List<TextbookTriadWritingSlot> =
        triads.mapIndexed { index, triad ->
            if (index == triads.lastIndex) {
                TextbookTriadWritingSlot.rootOrFirstInversion(triad)
            } else {
                TextbookTriadWritingSlot.rootFirstOrSecondInversion(triad)
            }
        }

    private fun List<Int>.ensureSecondInversionProgression(): List<Int> =
        when (size) {
            0 -> listOf(1, 5, 1)
            1 -> listOf(first(), 5, first())
            2 -> listOf(first(), last(), first())
            else -> this
        }
}
