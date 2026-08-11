package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 章节规则归属守卫。
 *
 * `SchoenbergPracticeTeachingRuleProjector` 只投影 `chapterFor(ruleId) != null` 的约束，
 * `withoutChapters` 的消融诊断也按同一映射删规则。因此任何章节程序里出现、却不被
 * `ownedRulePrefixes` 覆盖的 `schoenberg.*` 规则，都会静默地既进不了自由练习、也无法被诊断消融
 * （2026-08-11 曾因 `schoenberg.root-motion.*` / `schoenberg.repetition.*` 未被
 * `schoenberg.root-motion-and-repetition` 前缀覆盖而整章丢失）。
 */
class SchoenbergChapterRuleOwnershipTest {
    @Test
    fun everySchoenbergRuleInChapterProgramsMapsToItsChapter() {
        val key = Key.major(PitchClass.C)
        val unowned = linkedMapOf<String, MutableSet<String>>()
        var built = 0
        SchoenbergChapterRegistry.exerciseRegistrations.forEach { registration ->
            val definition = registration.definition
            val program = runCatching {
                SchoenbergChapterRegistry.program(
                    SchoenbergProgramRequest(
                        exerciseId = definition.exerciseId,
                        key = key,
                        continuationChordCount = definition.continuationChordCountRange.first,
                        progression = null,
                        searchConfig = SearchConfig(maxResults = 1, beamWidth = 8),
                        cadenceOptions = SchoenbergCadenceOptions(),
                    )
                )
            }.getOrNull() ?: return@forEach
            built++
            program.constraints
                .mapNotNull { it.ruleId }
                .filter { it.value.startsWith(SCHOENBERG_RULE_NAMESPACE) }
                .filterNot { ruleId -> UNOWNED_BY_DESIGN.any(ruleId.value::startsWith) }
                .filter { SchoenbergChapterRegistry.chapterFor(it) == null }
                .forEach { ruleId ->
                    unowned.getOrPut(definition.exerciseId) { linkedSetOf() } += ruleId.value
                }
        }

        assertTrue(built >= MIN_BUILDABLE_EXERCISES, "只成功编译了 $built 个练习程序，守卫失效")
        assertTrue(
            unowned.isEmpty(),
            "以下章节规则没有被任何 ownedRulePrefixes 覆盖，投影与消融都会漏掉它们：\n" +
                unowned.entries.joinToString("\n") { (exercise, rules) ->
                    "  $exercise: ${rules.sorted().joinToString()}"
                },
        )
    }

    @Test
    fun rootMotionAndRepetitionRulesBelongToTheirChapter() {
        listOf(
            SchoenbergRootMotionAndRepetitionChapter.RISING_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.DESCENDING_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.SUPERSTRONG_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.DESCENDING_COMPENSATION_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORE_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.SIMILAR_CHORD_DISTANCE_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.SIMILAR_PROGRESSION_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.UNIQUE_SOPRANO_CLIMAX_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.UNIQUE_BASS_NADIR_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.MELODIC_REPETITION_RULE_ID,
        ).forEach { ruleId ->
            assertTrue(
                SchoenbergChapterRegistry.chapterFor(ruleId) != null,
                "${ruleId.value} 未归属任何章节",
            )
        }
    }

    @Test
    fun unrelatedSolverRulesStayUnowned() {
        assertTrue(SchoenbergChapterRegistry.chapterFor(RuleId("solver.parallel-fifth")) == null)
        assertTrue(SchoenbergChapterRegistry.chapterFor(RuleId("free.melody.unique-high")) == null)
    }

    private companion object {
        const val SCHOENBERG_RULE_NAMESPACE = "schoenberg."
        const val MIN_BUILDABLE_EXERCISES = 10

        /**
         * `schoenberg.four-part.*` 是一般四部写作规则，不属于任何具体和弦教学章节；
         * 禁忌进行诊断会把它归到 [SchoenbergChapterRegistry.GENERAL_FOUR_PART_CHAPTER_ID] 兜底。
         */
        val UNOWNED_BY_DESIGN = listOf("schoenberg.four-part.")
    }
}
