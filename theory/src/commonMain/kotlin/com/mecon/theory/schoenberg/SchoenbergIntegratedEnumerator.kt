package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

internal object SchoenbergIntegratedEnumerator {
    fun enumerate(
        key: Key,
        options: SchoenbergIntegratedTechTree.EnumerationOptions,
    ): List<SchoenbergSymbolicProgression> {
        val continuationChordCount = options.continuationChordCount
        val stage = SchoenbergIntegratedStageSpec(options.treatmentIds)
        val includeLeadingTriad = stage.includes(SchoenbergHarmonicTreatments.LEADING_TRIAD)
        val selectedSecondaryHarmony = options.selectedSecondaryHarmony
        val requiredKnowledgeTags = options.requiredKnowledgeTags
        val preferredKnowledgeTags = options.preferredKnowledgeTags
        val budget = options.budget
        val chordSelectors = options.chordSelectors
        val requireAdjacentCommonTone = options.requireAdjacentCommonTone
        val applyRootMotionDirection = options.applyRootMotionDirection
        val applyHarmonicRepetitionPolicy = options.applyHarmonicRepetitionPolicy
        val dissonanceTreatment = options.dissonanceTreatment
        val sequencePolicy = options.sequencePolicy
        val shouldContinue = options.shouldContinue
        var cancelled = false
        fun continueSearch(): Boolean {
            if (cancelled) return false
            if (shouldContinue()) return true
            cancelled = true
            return false
        }
        require(continuationChordCount >= 1) { "continuationChordCount must be >= 1" }
        val minor = key.mode == Mode.AEOLIAN
        val length = continuationChordCount + 1
        val triads = exerciseTriads(key, includeLeadingTriad = includeLeadingTriad)
        val tonic = triads.firstOrNull { it.degree == TONIC_DEGREE }
            ?: error("Current key has no tonic triad")
        val vocabulary = SchoenbergIntegratedTechTree.vocabulary(
            key = key,
            treatmentIds = options.treatmentIds,
        ).filter { chord ->
            selectedSecondaryHarmony == null ||
                chord.secondaryFamily == null ||
                chord.transitionToken() == selectedSecondaryHarmony.transitionToken()
        }
        val start = tonic.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
        val terminal = start
        // Ranking after collecting only maxResults preserves DFS bias. The repetition chapter therefore
        // gathers a bounded wider pool, while incremental scoring also guides which branches are visited first.
        val collectionLimit = if (applyHarmonicRepetitionPolicy) {
            minOf(budget.maxResults * ENUMERATION_SCORE_OVERSAMPLE_FACTOR, MAX_SCORED_ENUMERATION_POOL)
        } else {
            budget.maxResults
        }
        val allowedStepCache = HashMap<Pair<SchoenbergSymbolicChord, SchoenbergSymbolicChord>, Boolean>()
        fun pairAllows(
            before: SchoenbergSymbolicChord,
            after: SchoenbergSymbolicChord,
        ): Boolean = allowedStepCache.getOrPut(before to after) {
            SchoenbergIntegratedTechTree.allowsIntegratedStep(
                prefix = listOf(before),
                after = after,
                triads = triads,
                key = key,
                includeLeadingTriad = includeLeadingTriad,
                minor = minor,
                requireAdjacentCommonTone = requireAdjacentCommonTone,
                applyRootMotionDirection = false,
                applyHarmonicRepetitionPolicy = false,
                dissonanceTreatment = dissonanceTreatment,
                sequencePolicy = null,
                totalLength = length,
            )
        }
        // A reverse dynamic-programming feasibility oracle gives this symbolic DFS the same kind
        // of forward pruning as the voicing searcher: never enter a branch that cannot reach the
        // fixed terminal tonic in exactly the remaining number of transitions. Prefix policies
        // are deliberately ignored here, so this is a safe necessary-condition prune.
        val terminalReachability = HashMap<Pair<SchoenbergSymbolicChord, Int>, Boolean>()
        fun canReachTerminal(
            chord: SchoenbergSymbolicChord,
            remainingTransitions: Int,
        ): Boolean {
            if (!continueSearch()) return false
            if (remainingTransitions == 0) return chord == terminal
            return terminalReachability.getOrPut(chord to remainingTransitions) {
                val nextCandidates =
                    if (remainingTransitions == 1) listOf(terminal) else vocabulary
                nextCandidates.any { next ->
                    pairAllows(chord, next) &&
                        canReachTerminal(next, remainingTransitions - 1)
                }
            }
        }
        val chordKnowledgeTags = vocabulary.associateWith { it.knowledgeTags(triads) } +
            (start to start.knowledgeTags(triads))
        val selectorPreferredChords = if (chordSelectors.isEmpty()) {
            emptySet()
        } else {
            vocabulary.filterTo(hashSetOf()) { chord ->
                val target = chord.toTarget(triads)
                chordSelectors.any { it.matches(target) }
            }
        }
        val collected = buildList {
            var visitedNodes = 0
            val prefix = mutableListOf(start)
            val usedChords = hashSetOf(start)
            val knowledgeTagCounts = mutableMapOf<SchoenbergKnowledgeTag, Int>()
            val usedRootProgressions = hashSetOf<Pair<Int, Int>>()

            fun addKnowledgeTags(chord: SchoenbergSymbolicChord) {
                chordKnowledgeTags.getValue(chord).forEach { tag ->
                    knowledgeTagCounts[tag] = (knowledgeTagCounts[tag] ?: 0) + 1
                }
            }

            fun removeKnowledgeTags(chord: SchoenbergSymbolicChord) {
                chordKnowledgeTags.getValue(chord).forEach { tag ->
                    val remaining = knowledgeTagCounts.getValue(tag) - 1
                    if (remaining == 0) knowledgeTagCounts.remove(tag)
                    else knowledgeTagCounts[tag] = remaining
                }
            }

            fun repetitionAllows(candidate: SchoenbergSymbolicChord): Boolean {
                if (!applyHarmonicRepetitionPolicy) return true
                val prospectiveSlot = prefix.size
                val candidateExempt =
                    sequencePolicy?.exemptsHarmonicRepetitionAt(prospectiveSlot, length) == true
                val recentStart = (prospectiveSlot - 2).coerceAtLeast(0)
                for (slot in recentStart until prospectiveSlot) {
                    if (
                        prefix[slot].degree == candidate.degree &&
                        !candidateExempt &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(slot, length) != true
                    ) return false
                }
                val previousSlot = prospectiveSlot - 1
                val transitionExempt =
                    candidateExempt ||
                        sequencePolicy?.exemptsHarmonicRepetitionAt(previousSlot, length) == true
                return transitionExempt ||
                    (prefix.last().degree to candidate.degree) !in usedRootProgressions
            }

            fun directionAllows(candidate: SchoenbergSymbolicChord): Boolean {
                if (!applyRootMotionDirection || prefix.size < 2) return true
                val twoStepsBefore = prefix[prefix.lastIndex - 1]
                val before = prefix.last()
                return SchoenbergRootMotionAndRepetitionChapter.classify(
                    twoStepsBefore.degree,
                    before.degree,
                ) != SchoenbergRootMotionDirection.DESCENDING ||
                    SchoenbergRootMotionAndRepetitionChapter.classify(
                        twoStepsBefore.degree,
                        candidate.degree,
                    ) != SchoenbergRootMotionDirection.DESCENDING
            }

            fun visit() {
                if (
                    !continueSearch() ||
                    size >= collectionLimit ||
                    visitedNodes >= budget.maxVisitedNodes
                ) return
                visitedNodes++
                if (prefix.size == length) {
                    if (sequencePolicy?.allowsPrefix(prefix, length) == false) return
                    if (prefix.last().triadIn(triads).isLeadingTriad()) return
                    if (applyRootMotionDirection &&
                        !SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(prefix)
                    ) return
                    if (applyHarmonicRepetitionPolicy &&
                        !SchoenbergIntegratedTransitionPolicy
                            .followsHarmonicRepetitionPolicy(prefix, sequencePolicy)
                    ) return
                    val progression = SchoenbergSymbolicProgression(
                        slots = prefix.toList(),
                        kind = SchoenbergConnectionKind.INTEGRATED,
                        knowledgeTags = knowledgeTagCounts.keys.toSet(),
                    )
                    if (progression.knowledgeTags.containsAll(requiredKnowledgeTags) &&
                        SchoenbergIntegratedVocabulary.matchesSelectors(
                            prefix,
                            chordSelectors,
                            triads,
                        )
                    ) add(progression)
                    return
                }
                val slot = prefix.size
                val before = prefix.last()
                val candidates = if (slot == length - 1) listOf(terminal) else vocabulary
                candidates
                    .filter { candidate ->
                        (slot == length - 1 && candidate == terminal) ||
                            candidate !in usedChords
                    }
                    .filter(::directionAllows)
                    .filter(::repetitionAllows)
                    .filter { candidate ->
                        pairAllows(before, candidate) &&
                            sequencePolicy?.allowsPrefix(prefix + candidate, length) != false &&
                            canReachTerminal(
                                candidate,
                                remainingTransitions = length - slot - 1,
                            )
                    }
                    .sortedWith(
                        compareByDescending<SchoenbergSymbolicChord> { candidate ->
                            candidate.priorityFor(
                                existingKnowledgeTags = knowledgeTagCounts.keys,
                                before = before,
                                triads = triads,
                                preferredKnowledgeTags = preferredKnowledgeTags,
                                selectorPreferred = candidate in selectorPreferredChords,
                            )
                        }.thenBy { candidate ->
                            if (applyHarmonicRepetitionPolicy) {
                                SchoenbergRootMotionAndRepetitionChapter
                                    .enumerationScore(prefix + candidate)
                                    .total
                            } else {
                                0.0
                            }
                        }
                    )
                    .forEach { candidate ->
                        if (!continueSearch()) return@forEach
                        val previousSlot = prefix.lastIndex
                        val rootProgression = before.degree to candidate.degree
                        val trackRootProgression =
                            applyHarmonicRepetitionPolicy &&
                                sequencePolicy?.exemptsHarmonicRepetitionAt(previousSlot, length) != true &&
                                sequencePolicy?.exemptsHarmonicRepetitionAt(previousSlot + 1, length) != true
                        prefix += candidate
                        val addedChord = usedChords.add(candidate)
                        addKnowledgeTags(candidate)
                        if (trackRootProgression) usedRootProgressions += rootProgression
                        visit()
                        if (trackRootProgression) usedRootProgressions -= rootProgression
                        removeKnowledgeTags(candidate)
                        if (addedChord) usedChords -= candidate
                        prefix.removeAt(prefix.lastIndex)
                    }
            }
            addKnowledgeTags(start)
            visit()
        }
        val ranked = if (applyHarmonicRepetitionPolicy) {
            collected.sortedWith(
                compareBy<SchoenbergSymbolicProgression> {
                    SchoenbergRootMotionAndRepetitionChapter.enumerationScore(it.slots).total
                }.thenByDescending { it.knowledgeTags.richnessScore() }
            )
        } else {
            collected.sortedByDescending { it.knowledgeTags.richnessScore() }
        }
        return ranked.take(budget.maxResults)
    }

    private const val ENUMERATION_SCORE_OVERSAMPLE_FACTOR = 8
    private const val MAX_SCORED_ENUMERATION_POOL = 512

    private fun List<SchoenbergSymbolicChord>.knowledgeTags(triads: List<NaturalTriad>): Set<SchoenbergKnowledgeTag> {
        val chords = this
        return buildSet {
            add(SchoenbergKnowledgeTag.COMMON_TONE)
            // 导七和弦与导三和弦同承载「导和弦」知识点：否则七和弦阶段要求导和弦知识时，vii°7 无法单独满足，
            // 会被迫与 vii° 三和弦同现（同为 7 级、四部难以共存）而枚举无解。
            // 大调唯一的减三和弦即 vii°，故「任意减三和弦」在大调等价于 isLeadingTriad；小调下同样把 ii° 计入
            // 「导 / 减三和弦」知识点，否则只用 ii° 的进行拿不到 LEADING_TRIAD 标签而被阶段过滤掉。
            val hasLeading = chords.any {
                SchoenbergIntegratedVocabulary.isLeadingTriad(it, triads) ||
                    SchoenbergIntegratedVocabulary.isLeadingSeventh(it) ||
                    (it.arity == ChordArity.TRIAD && it.quality == ChordQuality.DIMINISHED)
            }
            val hasFirst = chords.any { it.arity == ChordArity.TRIAD && it.position == TextbookTriadPosition.FIRST_INVERSION }
            if (hasLeading) add(SchoenbergKnowledgeTag.LEADING_TRIAD)
            if (hasFirst) add(SchoenbergKnowledgeTag.FIRST_INVERSION)
            if (chords.any {
                    it.arity == ChordArity.TRIAD &&
                        it.position == TextbookTriadPosition.FIRST_INVERSION &&
                        SchoenbergIntegratedVocabulary.isLeadingTriad(it, triads)
                }
            ) {
                add(SchoenbergKnowledgeTag.LEADING_FIRST_INVERSION)
            }
            if (chords.any(SchoenbergIntegratedVocabulary::isSixFour)) {
                add(SchoenbergKnowledgeTag.SECOND_INVERSION)
            }
            if (chords.any { it.arity == ChordArity.SEVENTH }) add(SchoenbergKnowledgeTag.SEVENTH_CHORD)
            if (chords.any { it.secondaryFamily != null }) add(SchoenbergKnowledgeTag.SECONDARY_HARMONY)
            if (chords.any { it.isRootlessDominantNinth() }) {
                add(SchoenbergKnowledgeTag.DIMINISHED_SEVENTH)
            }
            if (chords.any { it.augmentedSixthFamily != null }) {
                add(SchoenbergKnowledgeTag.AUGMENTED_SIXTH)
                add(SchoenbergKnowledgeTag.VAGRANT_CHORD)
            }
            if (chords.any {
                    it.isRootlessDominantNinth() ||
                        it.secondaryFamily == com.mecon.theory.constraint.SecondaryHarmonyFamily.MODAL_AUGMENTED
                }
            ) {
                add(SchoenbergKnowledgeTag.VAGRANT_CHORD)
            }
        }
    }

    private fun SchoenbergSymbolicChord.knowledgeTags(
        triads: List<NaturalTriad>,
    ): Set<SchoenbergKnowledgeTag> = listOf(this).knowledgeTags(triads)

    private fun Set<SchoenbergKnowledgeTag>.richnessScore(): Int =
        fold(0) { total, tag ->
            total + when (tag) {
                SchoenbergKnowledgeTag.COMMON_TONE -> 1
                SchoenbergKnowledgeTag.LEADING_TRIAD -> 3
                SchoenbergKnowledgeTag.FIRST_INVERSION -> 2
                SchoenbergKnowledgeTag.LEADING_FIRST_INVERSION -> 8
                SchoenbergKnowledgeTag.SECOND_INVERSION -> 4
                SchoenbergKnowledgeTag.SEVENTH_CHORD -> 6
                SchoenbergKnowledgeTag.SECONDARY_HARMONY -> 10
                SchoenbergKnowledgeTag.DIMINISHED_SEVENTH -> 12
                SchoenbergKnowledgeTag.MINOR_SUBDOMINANT -> 11
                SchoenbergKnowledgeTag.NEAPOLITAN -> 13
                SchoenbergKnowledgeTag.VAGRANT_CHORD -> 14
                SchoenbergKnowledgeTag.AUGMENTED_SIXTH -> 16
            }
        }
    private fun SchoenbergSymbolicChord.priorityFor(
        existingKnowledgeTags: Set<SchoenbergKnowledgeTag>,
        before: SchoenbergSymbolicChord,
        triads: List<NaturalTriad>,
        preferredKnowledgeTags: Set<SchoenbergKnowledgeTag>,
        selectorPreferred: Boolean,
    ): Int {
        var priority = 0
        if (SchoenbergKnowledgeTag.LEADING_TRIAD in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.LEADING_TRIAD !in existingKnowledgeTags &&
            SchoenbergIntegratedVocabulary.isLeadingTriad(this, triads)
        ) priority += 100
        if (SchoenbergKnowledgeTag.FIRST_INVERSION in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.FIRST_INVERSION !in existingKnowledgeTags &&
            position == TextbookTriadPosition.FIRST_INVERSION
        ) priority += 20
        if (SchoenbergKnowledgeTag.SECOND_INVERSION in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.SECOND_INVERSION !in existingKnowledgeTags &&
            SchoenbergIntegratedVocabulary.isSixFour(this)
        ) priority += 40
        if (SchoenbergKnowledgeTag.SEVENTH_CHORD in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.SEVENTH_CHORD !in existingKnowledgeTags && arity == ChordArity.SEVENTH
        ) priority += 60
        if (SchoenbergKnowledgeTag.SECONDARY_HARMONY in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.SECONDARY_HARMONY !in existingKnowledgeTags && secondaryFamily != null
        ) priority += 140
        if (SchoenbergKnowledgeTag.DIMINISHED_SEVENTH in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.DIMINISHED_SEVENTH !in existingKnowledgeTags && isRootlessDominantNinth()
        ) priority += 180
        if (SchoenbergKnowledgeTag.AUGMENTED_SIXTH in preferredKnowledgeTags &&
            SchoenbergKnowledgeTag.AUGMENTED_SIXTH !in existingKnowledgeTags && augmentedSixthFamily != null
        ) priority += 220
        // Cover the primary local-tonic resolution before the two allowed deceptive alternatives.
        // This only orders symbolic enumeration; both alternatives remain legal and retain the same
        // realization rules once the user selects a progression.
        if (
            secondaryFamily == null &&
            before.appliedToDegree == degree
        ) priority += 80
        if (selectorPreferred) priority += 120
        return priority
    }

}
