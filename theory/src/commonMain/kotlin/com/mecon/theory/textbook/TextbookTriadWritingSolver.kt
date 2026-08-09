package com.mecon.theory.textbook

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleProfile
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.SearchConfig
import com.mecon.theory.VoiceRangeProfile
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.ConstraintProgramSolver

enum class TextbookTriadPosition {
    ROOT_POSITION,
    FIRST_INVERSION,
    SECOND_INVERSION,
}

data class TextbookTriadTarget(
    val triad: NaturalTriad,
    val position: TextbookTriadPosition,
) : ChordTarget {
    override val key: Key get() = triad.key
    override val sonority: com.mecon.theory.Chord get() = triad.chord
    override val degree: Int get() = triad.degree
    override val quality: ChordQuality get() = triad.quality
    override val inversion: Int get() = position.ordinal
    override val arity: ChordArity get() = ChordArity.TRIAD

    override val bassPitchClass: PitchClass
        get() = when (position) {
            TextbookTriadPosition.ROOT_POSITION -> triad.root
            TextbookTriadPosition.FIRST_INVERSION -> FirstInversionTriadRules.firstInversionBassPitchClass(triad)
            TextbookTriadPosition.SECOND_INVERSION -> SecondInversionTriadRules.secondInversionBassPitchClass(triad)
        }

    override fun pitchClassFor(tone: ChordTone): PitchClass? =
        when (tone) {
            ChordTone.ROOT -> triad.root
            ChordTone.THIRD -> triad.chord.pitchClasses.getOrNull(1)
            ChordTone.FIFTH -> triad.chord.pitchClasses.getOrNull(2)
            ChordTone.SEVENTH -> null
            ChordTone.BASS -> bassPitchClass
        }

    override fun identityKey(): String = "triad:${triad.degree}:${triad.quality}:${position.ordinal}"
}

data class TextbookTriadWritingSlot(
    val triad: NaturalTriad,
    val allowedPositions: Set<TextbookTriadPosition>,
) {
    init {
        require(allowedPositions.isNotEmpty()) { "A triad writing slot must allow at least one position" }
    }

    companion object {
        fun rootPosition(triad: NaturalTriad) =
            TextbookTriadWritingSlot(triad, setOf(TextbookTriadPosition.ROOT_POSITION))

        fun firstInversion(triad: NaturalTriad) =
            TextbookTriadWritingSlot(triad, setOf(TextbookTriadPosition.FIRST_INVERSION))

        fun secondInversion(triad: NaturalTriad) =
            TextbookTriadWritingSlot(triad, setOf(TextbookTriadPosition.SECOND_INVERSION))

        fun rootOrFirstInversion(triad: NaturalTriad) =
            TextbookTriadWritingSlot(
                triad,
                setOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION),
            )

        fun rootFirstOrSecondInversion(triad: NaturalTriad) =
            TextbookTriadWritingSlot(triad, TextbookTriadPosition.entries.toSet())
    }
}

data class TextbookTriadWritingProblem(
    val key: Key,
    val slots: List<TextbookTriadWritingSlot>,
    val constraintPreset: TextbookTriadConstraintPreset = TextbookTriadConstraintPreset.INTRODUCTORY,
    val ruleProfile: RuleProfile = FirstInversionTriadRules.INTRODUCTORY_PROFILE,
    val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    val searchConfig: SearchConfig = SearchConfig(maxResults = 8, beamWidth = 48),
    val finalTonicMayOmitFifth: Boolean = true,
) {
    init {
        require(slots.isNotEmpty()) { "A triad writing problem must include at least one slot" }
        require(slots.all { it.triad.key == key }) { "All triads must belong to the problem key" }
    }
}

data class TextbookTriadWritingSolution(
    val voicings: List<TextbookTriadVoicing>,
    val breakdown: ScoreBreakdown,
)

data class TextbookTriadVoicing(
    val slotIndex: Int,
    val triad: NaturalTriad,
    val position: TextbookTriadPosition,
    val soprano: Pitch,
    val alto: Pitch,
    val tenor: Pitch,
    val bass: Pitch,
)

/** 兼容 textbook API 的薄适配器；候选、规则与搜索均由 [ConstraintProgramSolver] 执行。 */
object TextbookTriadWritingSolver {
    fun solve(problem: TextbookTriadWritingProblem): List<TextbookTriadWritingSolution> =
        ConstraintProgramSolver.solve(problem.toConstraintProgram()).map { solution ->
            TextbookTriadWritingSolution(
                voicings = solution.voicings.map(ChordVoicing::toTextbookTriadVoicing),
                breakdown = solution.breakdown,
            )
        }
}

fun textbookTriadInKey(
    key: Key,
    degree: Int,
    quality: ChordQuality? = null,
): NaturalTriad =
    NaturalTriads.inKey(key).firstOrNull { triad ->
        triad.degree == degree && (quality == null || triad.quality == quality)
    } ?: error("No natural triad for degree $degree in $key")

private fun ChordVoicing.toTextbookTriadVoicing(): TextbookTriadVoicing {
    val triadTarget = target as? TextbookTriadTarget
        ?: error("Expected textbook triad target at slot $slotIndex, got ${target::class.simpleName}")
    return TextbookTriadVoicing(
        slotIndex = slotIndex,
        triad = triadTarget.triad,
        position = triadTarget.position,
        soprano = soprano,
        alto = alto,
        tenor = tenor,
        bass = bass,
    )
}
