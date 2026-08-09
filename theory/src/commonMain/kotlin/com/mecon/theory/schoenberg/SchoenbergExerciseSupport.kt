package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.SlotWindow
import com.mecon.theory.theoryMemoMap
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget

internal const val TONIC_DEGREE = 1
internal const val MEDIANT_DEGREE = 3
internal const val SUBMEDIANT_DEGREE = 6
internal const val LEADING_TONE_DEGREE = 7
internal const val THIRD_INDEX = 1
internal const val FIFTH_INDEX = 2

internal fun exactProgressionDomains(
    progression: SchoenbergSymbolicProgression,
    triads: List<NaturalTriad>,
    expectedLength: Int,
): List<SlotDomain> {
    require(progression.slots.size == expectedLength) {
        "progression size must equal $expectedLength"
    }
    val key = triads.firstOrNull()?.key ?: error("Current key has no triads")
    return SchoenbergChordCatalog.targets(key, progression.slots)
        .map { target -> SlotDomain(listOf(target)) }
}

internal fun exerciseTriads(key: Key, includeLeadingTriad: Boolean): List<NaturalTriad> =
    NaturalTriads.inKey(key)
        .distinctBy { it.degree to it.quality }
        .filter { includeLeadingTriad || !it.isLeadingTriad() }

internal fun leadingTriad(triads: List<NaturalTriad>): NaturalTriad =
    triads.firstOrNull { it.isLeadingTriad() }
        ?: error("Current key has no leading diminished triad")

internal fun NaturalTriad.isLeadingTriad(): Boolean =
    degree == LEADING_TONE_DEGREE && quality == ChordQuality.DIMINISHED

internal fun NaturalTriad.sharesChordToneWith(other: NaturalTriad): Boolean =
    chord.pitchClasses.any { it in other.chord.pitchClasses }

internal fun NaturalTriad.allowsFirstInversionConnectionTo(after: NaturalTriad): Boolean {
    if (!sharesChordToneWith(after)) return false
    if (!after.isLeadingTriad()) return true
    return after.chord.pitchClasses[FIFTH_INDEX] in chord.pitchClasses
}

internal fun NaturalTriad.toSymbolic(position: TextbookTriadPosition): SchoenbergSymbolicChord =
    SchoenbergSymbolicChord(
        degree = degree,
        quality = quality,
        position = position,
    )

internal fun NaturalTriad.toTarget(position: TextbookTriadPosition): ChordTarget =
    TextbookTriadTarget(this, position)

/**
 * Single-chord target resolution.
 *
 * [SchoenbergChordCatalog.targets] collects a whole catalog per call, so resolving one symbolic
 * chord costs O(catalog). Symbolic enumeration and transition pruning resolve the same few dozen
 * chords per key thousands of times over — this used to be ~80% of free-practice idiom discovery.
 * The result is a pure function of (key, chord) and every value is immutable, so it is memoized.
 * [triads] only supplies the key; two triad lists of the same key resolve identically.
 */
internal fun SchoenbergSymbolicChord.toTarget(triads: List<NaturalTriad>): ChordTarget {
    val key = triads.firstOrNull()?.key ?: error("Current key has no triads")
    return schoenbergChordTargetMemo.getOrPut(key to this) {
        SchoenbergChordCatalog.targets(key, listOf(this)).single()
    }
}

private val schoenbergChordTargetMemo =
    theoryMemoMap<Pair<Key, SchoenbergSymbolicChord>, ChordTarget>()

internal fun SchoenbergSymbolicChord.isRootlessDominantNinth(): Boolean =
    rootlessDominantNinthUsageId != null

internal fun SchoenbergSymbolicChord.triadIn(triads: List<NaturalTriad>): NaturalTriad =
    triads.firstOrNull { it.degree == degree && it.quality == quality }
        ?: error("No triad $degree/$quality in current key")

internal fun List<NaturalTriad>.toProgression(
    kind: SchoenbergConnectionKind,
    tags: Set<SchoenbergKnowledgeTag>,
): SchoenbergSymbolicProgression =
    SchoenbergSymbolicProgression(
        slots = map { triad -> triad.toSymbolic(TextbookTriadPosition.ROOT_POSITION) },
        kind = kind,
        knowledgeTags = tags,
    )

internal fun firstInversionAvoidDoublings(
    length: Int,
    progression: SchoenbergSymbolicProgression?,
    triads: List<NaturalTriad>,
    required: Boolean,
): List<AvoidDoublingRequirement> =
    (0 until length).mapNotNull { slot ->
        if (progression != null) {
            val chord = progression.slots.getOrNull(slot) ?: return@mapNotNull null
            if (chord.position != TextbookTriadPosition.FIRST_INVERSION) return@mapNotNull null
            if (progression.requiresLeadingPrepThirdDoublingAt(slot, triads)) return@mapNotNull null
        }
        AvoidDoublingRequirement(
            slot = slot,
            tone = ChordTone.THIRD,
            required = required,
            selector = TargetSelector(
                inversions = setOf(TextbookTriadPosition.FIRST_INVERSION.ordinal),
                arities = setOf(ChordArity.TRIAD),
            ),
        )
    }

internal fun leadingFifthAvoidDoublings(length: Int, required: Boolean): List<AvoidDoublingRequirement> =
    (0 until length).map { slot ->
        AvoidDoublingRequirement(
            slot = slot,
            tone = ChordTone.FIFTH,
            required = required,
            // 导和弦规则不限 arity：书中三和弦规则在对应七和弦同样适用（vii° 与 vii°7 的五音同受此律）。
            selector = TargetSelector(degrees = setOf(LEADING_TONE_DEGREE)),
        )
    }

/**
 * 导和弦五音的同声部预备与下行解决。选择器只按度数（不限 arity）：书中三和弦规则在对应七和弦一体适用，
 * 故 vii° 与 vii°7 的五音同受此律——七和弦章节不再重写这条。
 */
internal fun leadingTriadNeighborRequirements(
    window: SlotWindow,
    includeResolution: Boolean = true,
): List<ChordToneNeighborRequirement> =
    buildList {
        add(
            ChordToneNeighborRequirement(
                window = window,
                sourceTone = ChordTone.FIFTH,
                direction = ChordToneNeighborDirection.PREVIOUS,
                candidateScaleDegrees = setOf(4),
                allowedDiatonicStepDeltas = setOf(0),
                sourceSelector = TargetSelector(degrees = setOf(LEADING_TONE_DEGREE)),
                ruleId = SchoenbergCommonToneExercises.LEADING_TRIAD_PREPARATION_RULE_ID,
                explanation = ConstraintExplanation(
                    satisfied = "导和弦的五音已由前一和弦同声部预备。",
                    violated = "导和弦的五音应由前一和弦同声部预备。",
                ),
            )
        )
        if (includeResolution) {
            add(
                ChordToneNeighborRequirement(
                    window = window,
                    sourceTone = ChordTone.FIFTH,
                    direction = ChordToneNeighborDirection.NEXT,
                    candidateScaleDegrees = setOf(MEDIANT_DEGREE),
                    allowedDiatonicStepDeltas = setOf(-1),
                    sourceSelector = TargetSelector(
                        degrees = setOf(LEADING_TONE_DEGREE),
                        arities = setOf(ChordArity.TRIAD),
                    ),
                    neighborSelector = TargetSelector(degrees = setOf(MEDIANT_DEGREE)),
                    ruleId = SchoenbergCommonToneExercises.LEADING_TRIAD_RESOLUTION_RULE_ID,
                    explanation = ConstraintExplanation(
                        satisfied = "导和弦的五音已下行级进解决到 III。",
                        violated = "导和弦的五音应下行级进解决到 III。",
                    ),
                )
            )
        }
    }

/**
 * 七和弦的七音必须下行级进解决——不限度数、不限转位的一般规则（书中三和弦规则在对应七和弦一体适用）。
 * 以 typed requirement 表达（`candidateScaleDegrees` 取满 1..7，由 `allowedDiatonicStepDeltas = {-1}` 约束
 * 「下行一个自然音级」，`voiceFilter` 默认 ANY 即七音所在声部），故禁忌表探测器会把它投影到各七和弦相邻对。
 *
 * **转位敏感性由探测器自动涌现，勿手工钉到某一转位**：七音在内声部（根位/一转/二转）时该声部自由，只要后继和弦
 * 含解决音即可写出；七音落在低音（三转 42）时低音被其强制下行锁死，从而决定后继和弦的低音（转位）。例如 I42 的
 * 七音（7 级）在低音必须下行到 6 级，只能进行到低音为 6 级的 IV6 / IV65，进行到 IV 根位写不出——这些「写不出」
 * 的相邻对由探测器逐对判定后落入禁忌表，本函数不区分转位。
 */
internal fun seventhResolutionNeighborRequirements(
    window: SlotWindow,
    sourceIdentityKeys: Set<String> = emptySet(),
): List<ChordToneNeighborRequirement> =
    listOf(
        ChordToneNeighborRequirement(
            window = window,
            sourceTone = ChordTone.SEVENTH,
            direction = ChordToneNeighborDirection.NEXT,
            candidateScaleDegrees = setOf(1, 2, 3, 4, 5, 6, 7),
            allowedDiatonicStepDeltas = setOf(-1),
            sourceSelector = if (sourceIdentityKeys.isEmpty()) {
                TargetSelector(arities = setOf(ChordArity.SEVENTH))
            } else {
                TargetSelector(identityKeys = sourceIdentityKeys)
            },
            ruleId = SchoenbergSeventhChordChapter.RESOLUTION_RULE_ID,
            explanation = ConstraintExplanation(
                satisfied = "七音已下行级进解决。",
                violated = "七和弦的七音必须下行级进解决。",
            ),
        ),
    )

private fun SchoenbergSymbolicProgression.requiresLeadingPrepThirdDoublingAt(
    slot: Int,
    triads: List<NaturalTriad>,
): Boolean {
    val before = slots.getOrNull(slot) ?: return false
    val leading = slots.getOrNull(slot + 1) ?: return false
    if (before.arity != ChordArity.TRIAD || before.position != TextbookTriadPosition.FIRST_INVERSION) return false
    // 只有三和弦才可能是导和弦；七和弦（如 V7）没有对应的自然三和弦，不能调用 triadIn。
    if (leading.arity != ChordArity.TRIAD) return false
    val beforeTriad = before.triadIn(triads)
    val leadingTriad = leading.triadIn(triads)
    return leadingTriad.isLeadingTriad() &&
        beforeTriad.chord.pitchClasses[THIRD_INDEX] == leadingTriad.chord.pitchClasses[FIFTH_INDEX]
}
