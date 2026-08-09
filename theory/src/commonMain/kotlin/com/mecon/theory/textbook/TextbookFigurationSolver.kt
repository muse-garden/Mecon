package com.mecon.theory.textbook

import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.Key
import com.mecon.theory.SearchConfig
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.api.primitive.Pitch

/**
 * 和弦外音写作的第一阶段输入。它把表面装饰所要求的声部运动反投影到通用四部和声求解器，
 * 因而装饰层不会再绕过声部进行、音域、排列与平行音程规则。
 */
data class TextbookFigurationProblem(
    val key: Key,
    val slots: List<TextbookTriadWritingSlot>,
    val figuredVoice: FixedVoiceRole? = null,
    val requiredDiatonicDeltas: List<Int> = emptyList(),
    val requiredVoiceTones: List<ChordTone?> = emptyList(),
    val suspensionIntervals: List<SuspensionInterval>? = null,
    val searchConfig: SearchConfig = SearchConfig(maxResults = 64, beamWidth = 256),
) {
    init {
        require(slots.isNotEmpty()) { "A figuration problem must include harmony slots" }
        require(requiredDiatonicDeltas.isEmpty() || requiredDiatonicDeltas.size == slots.size - 1) {
            "Figuration voice delta count must match adjacent harmony slots"
        }
        require(requiredVoiceTones.isEmpty() || requiredVoiceTones.size == slots.size) {
            "Figuration voice tone count must match harmony slots"
        }
        require(suspensionIntervals == null || suspensionIntervals.size == slots.size - 1) {
            "Suspension interval count must match adjacent harmony slots"
        }
        require(suspensionIntervals == null || figuredVoice == FixedVoiceRole.SOPRANO || figuredVoice == FixedVoiceRole.BASS) {
            "Interval-labelled suspensions currently require soprano or bass projection"
        }
    }
}

/** 教材数字低音的延留音程：在新和声上先保持前槽音高，再解决到当前骨架音高。 */
data class SuspensionInterval(val dissonance: Int, val resolution: Int)

data class TextbookFigurationSolution(
    val harmony: List<ChordVoicing>,
    val figuredVoice: FixedVoiceRole?,
    val breakdown: ScoreBreakdown,
)

object TextbookFigurationSolver {
    fun solve(problem: TextbookFigurationProblem): TextbookFigurationSolution? {
        val textbookProblem = TextbookTriadWritingProblem(
            key = problem.key,
            slots = problem.slots,
            searchConfig = problem.searchConfig,
        )
        val baseProgram = textbookProblem.toConstraintProgram()
        val motionConstraint = if (problem.requiredDiatonicDeltas.isNotEmpty()) {
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.VoiceDiatonicSteps(
                        voiceFilter = requireNotNull(problem.figuredVoice).toVoiceFilter(),
                        slots = problem.slots.indices.toList(),
                        allowedDeltas = problem.requiredDiatonicDeltas.map(::setOf),
                    )
                ),
                explanation = ConstraintExplanation(
                    satisfied = "装饰声部为延留与解决保留了所需的级进骨架。",
                    violated = "装饰声部没有按指定方向级进解决。",
                ),
            )
        } else null
        val voiceToneConstraints = problem.requiredVoiceTones.mapIndexedNotNull { slot, tone ->
            tone?.let {
                Constraint(
                    expr = ConstraintExpr.Atom(
                        ConstraintPredicate.ToneInVoiceFilter(
                            slot = slot,
                            tone = it,
                            voiceFilter = requireNotNull(problem.figuredVoice).toVoiceFilter(),
                        )
                    )
                )
            }
        }
        val program = baseProgram.copy(
            constraints = baseProgram.constraints + listOfNotNull(motionConstraint) + voiceToneConstraints,
        )
        val candidates = ConstraintProgramSolver.solve(program).filterNot { it.breakdown.hasHardViolation }
        val solution = candidates.firstOrNull { candidate ->
            candidate.voicings.matchesProjection(problem) && (problem.suspensionIntervals?.let { intervals ->
                candidate.voicings.zipWithNext().zip(intervals).all { (pair, interval) ->
                    pair.matchesSuspension(problem.figuredVoice, interval)
                }
            } ?: true)
        } ?: return null
        return TextbookFigurationSolution(solution.voicings, problem.figuredVoice, solution.breakdown)
    }
}

private fun List<ChordVoicing>.matchesProjection(problem: TextbookFigurationProblem): Boolean {
    if (problem.figuredVoice == null) return true
    val pitches = map { it.pitchFor(problem.figuredVoice) }
    val motionOk = problem.requiredDiatonicDeltas.isEmpty() ||
        pitches.zipWithNext().zip(problem.requiredDiatonicDeltas).all { (pair, delta) ->
            pair.second.diatonicSteps - pair.first.diatonicSteps == delta
        }
    val tonesOk = problem.requiredVoiceTones.isEmpty() || indices.all { slot ->
        val tone = problem.requiredVoiceTones[slot] ?: return@all true
        this[slot].target.pitchClassFor(tone) == pitches[slot].pitchClass
    }
    return motionOk && tonesOk
}

private fun ChordVoicing.pitchFor(role: FixedVoiceRole): Pitch = when (role) {
    FixedVoiceRole.SOPRANO -> soprano
    FixedVoiceRole.ALTO -> alto
    FixedVoiceRole.TENOR -> tenor
    FixedVoiceRole.BASS -> bass
    else -> error("Figuration projection requires a concrete SATB voice")
}

private fun FixedVoiceRole.toVoiceFilter(): ChordToneVoiceFilter = when (this) {
    FixedVoiceRole.SOPRANO -> ChordToneVoiceFilter.SOPRANO
    FixedVoiceRole.ALTO -> ChordToneVoiceFilter.ALTO
    FixedVoiceRole.TENOR -> ChordToneVoiceFilter.TENOR
    FixedVoiceRole.BASS -> ChordToneVoiceFilter.BASS
    FixedVoiceRole.BARITONE, FixedVoiceRole.INNER -> ChordToneVoiceFilter.INNER
    FixedVoiceRole.OUTER -> ChordToneVoiceFilter.OUTER
}

private fun Pair<ChordVoicing, ChordVoicing>.matchesSuspension(
    voice: FixedVoiceRole?,
    interval: SuspensionInterval,
): Boolean {
    val (before, current) = this
    return when (voice) {
        FixedVoiceRole.SOPRANO ->
            diatonicInterval(current.bass, before.soprano).matchesClass(interval.dissonance) &&
                diatonicInterval(current.bass, current.soprano).matchesClass(interval.resolution) &&
                !current.target.sonority.contains(before.soprano) &&
                (interval != SuspensionInterval(9, 8) ||
                    listOf(current.alto, current.tenor).none { it.pitchClass == current.soprano.pitchClass })
        FixedVoiceRole.BASS -> listOf(current.soprano, current.alto, current.tenor).any { upper ->
            diatonicInterval(before.bass, upper).matchesClass(interval.dissonance) &&
                diatonicInterval(current.bass, upper).matchesClass(interval.resolution) &&
                !current.target.sonority.contains(before.bass)
        }
        else -> false
    }
}

private fun diatonicInterval(lower: com.mecon.api.primitive.Pitch, upper: com.mecon.api.primitive.Pitch): Int =
    upper.diatonicSteps - lower.diatonicSteps + 1

/** SATB 实际音域常把 4-3、7-6、9-8 写成复音程；数字低音类别按七度等价类匹配。 */
private fun Int.matchesClass(expected: Int): Boolean = this >= expected && (this - expected).mod(7) == 0
