package com.mecon.exploration

import com.mecon.theory.ChapterId
import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SceneMatcher
import com.mecon.theory.SelectionContext
import com.mecon.theory.textbook.FIRST_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.NonChordToneRules
import com.mecon.theory.textbook.SECOND_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey

internal object RuleExampleRequestRunner {
    private val rootOnly = setOf(TextbookTriadPosition.ROOT_POSITION)

    fun run(request: RuleExampleRequest): CellOutput {
        val nonChordToneRule = (request.selectedRules.singleOrNull()
            ?: request.demonstrate?.ruleId)?.let(::RuleId)
        if (nonChordToneRule != null && NonChordToneRules.typeFor(nonChordToneRule) != null) {
            return NonChordToneExamples.output(request, nonChordToneRule)
        }
        val semantics = request.compileSemantics()
        val validationRuleIds = semantics.ruleIds
        val validation = RuleCatalog.validateSelection(
            selected = validationRuleIds,
            context = SelectionContext(request.from.degree, request.to.degree),
        )
        if (!validation.isValid || validation.unavailable.isNotEmpty()) {
            val diagnostics = validation.errors.map {
                Diagnostics.invalidSelection(it.ruleId.value, it.message)
            } + validation.unavailable.map {
                Diagnostics.ruleNotApplicable(it.ruleId.value, it.message)
            }
            return diagnosticOutput(request, diagnostics)
        }
        if (semantics.seventhSlots != null) {
            return runSeventhExample(request, semantics.key, semantics.seventhSlots, semantics.requirements)
        }
        val slots = requireNotNull(semantics.triadSlots)
        val demonstrationRuleId = request.demonstrate?.ruleId
        return if (slots.all { it.allowedPositions == rootOnly }) {
            TextbookExplorationRunner.solveRootPosition(
                request = request,
                keySpec = request.key,
                triads = slots.map { it.triad },
                requirements = semantics.requirements,
                search = request.search,
                demonstrationRuleId = demonstrationRuleId,
            )
        } else {
            val chapter = semantics.scene?.ruleId?.let { RuleCatalog.descriptor(it)?.chapter }
            TextbookExplorationRunner.solveTriads(
                request = request,
                keySpec = request.key,
                slots = slots,
                requirements = semantics.requirements,
                search = request.search,
                demonstrationRuleId = demonstrationRuleId,
                profile = profileForChapter(chapter),
                title = titleForChapter(chapter),
            )
        }
    }

    private fun runSeventhExample(
        request: RuleExampleRequest,
        key: Key,
        slots: List<com.mecon.theory.textbook.TextbookSeventhWritingSlot>,
        requirements: List<RuleRequirement>,
    ): CellOutput {
        val demonstrationRuleId = request.demonstrate?.ruleId
        if (demonstrationRuleId == null) {
            return TextbookExplorationRunner.solveSevenths(
                request, key, slots, requirements, null
            )
        }
        val correct = TextbookExplorationRunner.solveSevenths(
            request, key, slots, emptyList(), null
        )
        val incorrect = TextbookExplorationRunner.solveSevenths(
            request, key, slots, requirements, demonstrationRuleId
        )
        if (correct.candidates.isNotEmpty() && incorrect.candidates.isNotEmpty()) {
            return CellOutput(
                fingerprint = ExplorationRequestRunner.fingerprint(request),
                candidates = listOf(correct.candidates.first(), incorrect.candidates.first()),
                comparisonGroups = listOf(
                    CandidateComparison(
                        title = "正确 / 错误对照",
                        correctCandidateIndex = 0,
                        incorrectCandidateIndex = 1,
                        explanation = "同一属七语境下对照常规解决与目标违规。",
                    )
                ),
            )
        }
        return if (incorrect.candidates.isNotEmpty()) incorrect else correct
    }

    private fun profileForChapter(chapter: ChapterId?): RuleProfile =
        when (chapter) {
            SECOND_INVERSION_TRIAD_CHAPTER -> SecondInversionTriadRules.INTRODUCTORY_PROFILE
            else -> FirstInversionTriadRules.INTRODUCTORY_PROFILE
        }

    private fun titleForChapter(chapter: ChapterId?): String =
        when (chapter) {
            SECOND_INVERSION_TRIAD_CHAPTER -> "三和弦第二转位写作"
            FIRST_INVERSION_TRIAD_CHAPTER -> "三和弦第一转位写作"
            else -> "三和弦写作"
        }
}
