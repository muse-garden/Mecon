package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScore
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.Key
import com.mecon.theory.MelodyAnalysis
import com.mecon.theory.MelodyDirection
import com.mecon.theory.MelodyMotion
import com.mecon.theory.RuleDiagnostic
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity

object MelodyTextbookRules {
    val UNIQUE_CLIMAX = RuleId("textbook.melody.unique-climax")
    val MOSTLY_STEPWISE = RuleId("textbook.melody.mostly-stepwise")
    val CONTOUR_CLARITY = RuleId("textbook.melody.contour-clarity")
    val DISCOURAGED_LEAP = RuleId("textbook.melody.discouraged-leap")
    val DIMINISHED_LEAP_RESOLUTION = RuleId("textbook.melody.diminished-leap-resolution")
    val CONSECUTIVE_LEAP_TRIAD_OUTLINE = RuleId("textbook.melody.consecutive-leap-triad-outline")
    val LEADING_TONE_RESOLUTION = RuleId("textbook.melody.leading-tone-resolution")
    val FOURTH_DEGREE_RESOLUTION = RuleId("textbook.melody.fourth-degree-resolution")

    fun checkPitches(
        pitches: List<Pitch>,
        key: Key,
        requireUniqueClimax: Boolean = true,
    ): List<RuleDiagnostic<Int>> =
        checkVoice(
            items = pitches.withIndex().toList(),
            key = key,
            pitchOf = { it.value },
            anchorOf = { it.index },
            requireUniqueClimax = requireUniqueClimax,
        )

    fun checkFixedVoiceScore(score: FixedVoiceScore, key: Key): List<RuleDiagnostic<EventId>> =
        score.voices.flatMap { voice ->
            checkVoice(
                items = score.noteEventsForVoice(voice),
                key = key,
                pitchOf = { it.requiredPitch() },
                anchorOf = { it.id },
                requireUniqueClimax = voice.role == FixedVoiceRole.SOPRANO,
            )
        }

    fun <T, A> checkVoice(
        items: List<T>,
        key: Key,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
        requireUniqueClimax: Boolean = true,
    ): List<RuleDiagnostic<A>> = buildList {
        if (requireUniqueClimax) addAll(checkUniqueClimax(items, pitchOf, anchorOf))
        addAll(checkMostlyStepwise(items, pitchOf, anchorOf))
        addAll(checkContourClarity(items, pitchOf, anchorOf))
        addAll(checkLeaps(items, pitchOf, anchorOf))
        addAll(checkConsecutiveLeaps(items, pitchOf, anchorOf))
        addAll(checkTendencyTones(items, key, pitchOf, anchorOf))
    }

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private fun <T, A> anchorsOf(
        items: Iterable<T>,
        anchorOf: (T) -> A?,
    ): List<A> =
        items.mapNotNull(anchorOf)

    private fun <T, A> checkUniqueClimax(
        items: List<T>,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> {
        val peak = MelodyAnalysis.peak(items, pitchOf) ?: return emptyList()
        if (peak.isUnique) return emptyList()
        return listOf(
            RuleDiagnostic(
                ruleId = UNIQUE_CLIMAX,
                severity = RuleSeverity.SOFT,
                message = "旋律最高点应尽量唯一。",
                anchors = anchorsOf(peak.items, anchorOf),
            )
        )
    }

    private fun <T, A> checkMostlyStepwise(
        items: List<T>,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> {
        if (items.size < 3 || MelodyAnalysis.stepwiseRatio(items, pitchOf) >= 0.5) return emptyList()
        return listOf(
            RuleDiagnostic(
                ruleId = MOSTLY_STEPWISE,
                severity = RuleSeverity.SOFT,
                message = "旋律主要应由级进连接，当前跳进比例偏高。",
                anchors = anchorsOf(items, anchorOf),
            )
        )
    }

    private fun <T, A> checkContourClarity(
        items: List<T>,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> {
        val motions = MelodyAnalysis.motions(items, pitchOf).filter { it.direction != MelodyDirection.REPEATED }
        if (motions.size < 4) return emptyList()
        val changes = motions.zipWithNext().count { (previous, next) -> previous.direction != next.direction }
        return when {
            changes == 0 -> listOf(
                RuleDiagnostic(
                    ruleId = CONTOUR_CLARITY,
                    severity = RuleSeverity.HINT,
                    message = "旋律走向过于单一，可考虑加入清晰的转折。",
                    anchors = anchorsOf(items, anchorOf),
                )
            )
            changes == motions.size - 1 -> listOf(
                RuleDiagnostic(
                    ruleId = CONTOUR_CLARITY,
                    severity = RuleSeverity.HINT,
                    message = "旋律连续反向过多，可能显得缺少清晰曲线。",
                    anchors = anchorsOf(items, anchorOf),
                )
            )
            else -> emptyList()
        }
    }

    private fun <T, A> checkLeaps(
        items: List<T>,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> {
        val motions = MelodyAnalysis.motions(items, pitchOf)
        return motions.flatMapIndexed { index, motion ->
            val next = motions.getOrNull(index + 1)
            when {
                motion.isLeap && (motion.isAugmented || motion.isSeventh || motion.isGreaterThanOctave) -> listOf(
                    RuleDiagnostic(
                        ruleId = DISCOURAGED_LEAP,
                        severity = RuleSeverity.SOFT,
                        message = "应避免增音程、七度或大于纯八度的跳进。",
                        anchors = anchorsOf(listOf(motion.from, motion.to), anchorOf),
                    )
                )
                motion.isLeap &&
                    motion.isDiminished &&
                    (next == null || !motion.isOppositeDirectionTo(next) || !next.isStep) -> listOf(
                    RuleDiagnostic(
                        ruleId = DIMINISHED_LEAP_RESOLUTION,
                        severity = RuleSeverity.SOFT,
                        message = "减音程跳进后应立即向相反方向级进。",
                        anchors = anchorsOf(listOfNotNull(motion.from, motion.to, next?.to), anchorOf),
                    )
                )
                else -> emptyList()
            }
        }
    }

    private fun <T, A> checkConsecutiveLeaps(
        items: List<T>,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> {
        val motions = MelodyAnalysis.motions(items, pitchOf)
        return motions.zipWithNext().mapNotNull { (first, second) ->
            if (
                first.isSmallerLeap &&
                second.isSmallerLeap &&
                first.direction == second.direction &&
                !MelodyAnalysis.outlinesTriad(listOf(first.from, first.to, second.to), pitchOf)
            ) {
                RuleDiagnostic(
                    ruleId = CONSECUTIVE_LEAP_TRIAD_OUTLINE,
                    severity = RuleSeverity.SOFT,
                    message = "同方向连续较小跳进应构成三和弦外形。",
                    anchors = anchorsOf(listOf(first.from, first.to, second.to), anchorOf),
                )
            } else {
                null
            }
        }
    }

    private fun <T, A> checkTendencyTones(
        items: List<T>,
        key: Key,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): List<RuleDiagnostic<A>> =
        items.mapIndexedNotNull { index, item ->
            when (MelodyAnalysis.scaleDegree(pitchOf(item), key)) {
                7 -> checkLeadingTone(items, key, index, pitchOf, anchorOf)
                4 -> checkFourthDegree(items, key, index, pitchOf, anchorOf)
                else -> null
            }
        }

    private fun <T, A> checkLeadingTone(
        items: List<T>,
        key: Key,
        index: Int,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): RuleDiagnostic<A>? {
        if (MelodyAnalysis.hasDescendingScaleFragment(items, key, index - 1, pitchOf = pitchOf)) return null
        val motion = MelodyAnalysis.motions(items, pitchOf).getOrNull(index)
        val resolves = motion != null &&
            motion.direction == MelodyDirection.ASCENDING &&
            motion.isStep &&
            MelodyAnalysis.scaleDegree(motion.toPitch, key) == 1
        if (resolves) return null
        return RuleDiagnostic(
            ruleId = LEADING_TONE_RESOLUTION,
            severity = RuleSeverity.SOFT,
            message = "7 级音有强烈上行到 1 级的倾向，下行音阶 1-7-6-5 例外。",
            anchors = anchorsOf(listOfNotNull(items[index], motion?.to), anchorOf),
        )
    }

    private fun <T, A> checkFourthDegree(
        items: List<T>,
        key: Key,
        index: Int,
        pitchOf: (T) -> Pitch,
        anchorOf: (T) -> A?,
    ): RuleDiagnostic<A>? {
        val motion = MelodyAnalysis.motions(items, pitchOf).getOrNull(index)
        val resolves = motion != null &&
            motion.direction == MelodyDirection.DESCENDING &&
            motion.isStep &&
            MelodyAnalysis.scaleDegree(motion.toPitch, key) == 3
        if (resolves) return null
        return RuleDiagnostic(
            ruleId = FOURTH_DEGREE_RESOLUTION,
            severity = RuleSeverity.HINT,
            message = "4 级音通常有下行到 3 级的倾向，但弱于 7-1。",
            anchors = anchorsOf(listOfNotNull(items[index], motion?.to), anchorOf),
        )
    }
}
