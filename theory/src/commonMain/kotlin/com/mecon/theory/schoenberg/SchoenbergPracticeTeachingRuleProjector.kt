package com.mecon.theory.schoenberg

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.TonalContextId
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordSelectionTargetCatalog
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.projectSlots
import com.mecon.theory.freepractice.PracticeTeachingRuleProjector
import com.mecon.theory.freepractice.PracticeTeachingRuleRequest
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceSlotId

/**
 * Adapts the registered Schoenberg chapter programs; it does not reimplement their rules.
 * Interpretation metadata is the only dispatch input, so enharmonically equal sonorities keep
 * the rules of the reading selected in the workspace.
 */
object SchoenbergPracticeTeachingRuleProjector : PracticeTeachingRuleProjector {
    override fun project(request: PracticeTeachingRuleRequest): List<Constraint> {
        val scopeIndexById = request.scope.slotIds.withIndex().associate { it.value to it.index }
        return request.workspace.idiomInstances
            .asSequence()
            .filter { instance -> instance.slotIds.any(scopeIndexById::containsKey) }
            .flatMap { instance -> projectInstance(request, instance, scopeIndexById).asSequence() }
            .distinctBy { listOf(it.ruleId, it.expr, it.scope) }
            .toList()
    }

    private fun projectInstance(
        request: PracticeTeachingRuleRequest,
        instance: WorkspaceIdiomInstance,
        scopeIndexById: Map<WorkspaceSlotId, Int>,
    ): List<Constraint> {
        val registration = SchoenbergChapterRegistry.exerciseRegistrations
            .firstOrNull { it.definition.exerciseId == instance.sourceExerciseId }
            ?: return emptyList()
        if (registration.definition.chapterId != instance.sourceChapterId) return emptyList()

        val workspaceSlotsById = request.workspace.slots.associateBy { it.id }
        val orderedSlotIds = instance.slotIds
            .distinct()
            .sortedBy { workspaceSlotsById[it]?.onset }
        val preferredTreatments = SchoenbergHarmonicTreatments.registry
            .resolve(registration.definition.harmonicTreatmentIds)
            .includedTreatmentIds
        val targets = orderedSlotIds.map { slotId ->
            val candidates = request.targetsBySlotId[slotId].orEmpty()
            candidates.maxByOrNull { target ->
                val treatmentIds = (target as? InterpretedChordTarget)
                    ?.interpretation?.treatmentIds.orEmpty()
                when {
                    treatmentIds.any(preferredTreatments::contains) -> 2
                    candidates.size == 1 -> 1
                    else -> 0
                }
            } ?: return emptyList()
        }
        val allowAudibleReinterpretation =
            instance.parameters[VIEWED_AS_RULE_PROJECTION_PARAMETER] == "true"
        val sourceTargets = if (allowAudibleReinterpretation) {
            instance.viewedAsSourceTargets(targets) ?: return emptyList()
        } else {
            targets
        }
        val symbolic = sourceTargets.map(ChordTarget::toSchoenbergSymbolicChord)
        val source = findSourceProgram(
            request,
            instance,
            symbolic,
            sourceTargets,
        )
            ?: return emptyList()
        val (sourceProgram, sourceStart) = source
        val selectorProjection = sourceProgram.selectorProjection(
            sourceStart = sourceStart,
            sourceTargets = sourceTargets,
            projectionTargets = targets,
        )
        val targetSlotBySource = orderedSlotIds.mapIndexedNotNull { idiomIndex, slotId ->
            scopeIndexById[slotId]?.let { sourceStart + idiomIndex to it }
        }.toMap()
        if (targetSlotBySource.isEmpty()) return emptyList()

        return sourceProgram.constraints
            .asSequence()
            .filter { constraint ->
                constraint.ruleId?.let(SchoenbergChapterRegistry::chapterFor) != null
            }
            .mapNotNull { constraint ->
                constraint.projectSlots(
                    sourceSlotCount = sourceProgram.length,
                    targetSlotBySource = targetSlotBySource,
                    modality = constraint.freePracticeModality(),
                    selectorProjection = selectorProjection,
                )
            }
            .distinctBy { listOf(it.ruleId, it.expr, it.scope) }
            .toList()
    }

    private fun findSourceProgram(
        request: PracticeTeachingRuleRequest,
        instance: WorkspaceIdiomInstance,
        symbolic: List<SchoenbergSymbolicChord>,
        targets: List<ChordTarget>,
    ): Pair<ConstraintProgram, Int>? {
        val selections = instance.parameters.mapValues { (_, value) -> listOf(value) }
        val teachingSource = instance.teachingSource()
        val sourceProgression = teachingSource?.progression
            ?: SchoenbergSymbolicProgression(
                slots = symbolic,
                kind = SchoenbergConnectionKind.INTEGRATED,
            )
        // A viewed-as reading deliberately re-reads the chords in another key, so it outranks the
        // key the variant was originally cut from.
        val sourceKey = instance.viewedAsSourceKey()
            ?: teachingSource?.key
            ?: instance.tonalities.firstOrNull()?.let { tonality ->
                tonality.alternates.firstOrNull()?.key ?: tonality.primary.key
            }
            ?: request.workspace.tonalLayouts
                .firstOrNull { it.id == instance.tonalLayoutId }
                ?.key
            ?: request.fallbackKey
        val continuationCount = (sourceProgression.slots.size - 1).coerceAtLeast(1)
        val preferLatestSpan =
            instance.parameters[VIEWED_AS_RULE_PROJECTION_PARAMETER] == "true"
        return runCatching {
            SchoenbergChapterRegistry.freePracticeProgram(
                SchoenbergProgramRequest(
                    exerciseId = instance.sourceExerciseId,
                    key = sourceKey.key,
                    continuationChordCount = continuationCount,
                    progression = sourceProgression,
                    searchConfig = request.searchConfig,
                    cadenceOptions = teachingSource?.cadenceOptions ?: SchoenbergCadenceOptions(),
                    sourceModulationKey = sourceKey,
                    selections = selections,
                )
            )
        }.getOrNull()?.let { program ->
            // A persisted start indexes the source progression, so it only addresses program slots
            // when the chapter compiled that progression as-is. Chapters that widen the program to
            // their own teaching length (the cadence exercise) are located by search instead.
            val persistedStart = teachingSource?.start
                ?.takeIf { program.length == sourceProgression.slots.size }
                ?.takeIf { start -> start + targets.size <= program.length }
            val sourceStart = persistedStart ?: program.findTargetSpan(
                targets,
                allowAudibleReinterpretation = false,
                preferLatest = preferLatestSpan,
            )
            sourceStart?.let { program to it }
        }
    }

    private fun ConstraintProgram.findTargetSpan(
        targets: List<ChordTarget>,
        allowAudibleReinterpretation: Boolean,
        preferLatest: Boolean,
    ): Int? {
        if (targets.size > slotDomains.size) return null
        val starts = 0..slotDomains.size - targets.size
        val matchingStarts = starts.filter { start ->
            targets.indices.all { offset ->
                slotDomains[start + offset].targets.any { source ->
                    source.matchesSelectedInterpretation(
                        targets[offset],
                        allowAudibleReinterpretation,
                    )
                }
            }
        }
        return if (preferLatest) matchingStarts.lastOrNull() else matchingStarts.firstOrNull()
    }

    private fun ConstraintProgram.selectorProjection(
        sourceStart: Int,
        sourceTargets: List<ChordTarget>,
        projectionTargets: List<ChordTarget>,
    ): (TargetSelector) -> TargetSelector {
        val identityKeys = linkedMapOf<String, String>()
        val sonorityKeys = linkedMapOf<String, String>()
        val interpretationKeys = linkedMapOf<String, String>()
        val primaryContexts = linkedMapOf<TonalContextId, TonalContextId>()
        val compatibleContexts = linkedMapOf<TonalContextId, TonalContextId>()
        val mappedTargets = mutableListOf<Pair<ChordTarget, ChordTarget>>()
        sourceTargets.forEachIndexed { offset, sourceTarget ->
            val target = projectionTargets[offset]
            val source = slotDomains[sourceStart + offset].targets
                .firstOrNull {
                    it.matchesSelectedInterpretation(sourceTarget, allowAudibleReinterpretation = false)
                }
                ?: return@forEachIndexed
            mappedTargets += source to target
            identityKeys[source.identityKey()] = target.identityKey()
            sonorityKeys[source.sonorityIdentityKey()] = target.sonorityIdentityKey()
            interpretationKeys[source.interpretationIdentityKey()] = target.interpretationIdentityKey()
            val targetPrimary = target.primaryTonalContextId()
            source.primaryTonalContextId()?.let { sourceContext ->
                targetPrimary?.let { primaryContexts[sourceContext] = it }
            }
            val targetCompatible = target.tonalContextIds().firstOrNull()
            if (targetCompatible != null) {
                source.tonalContextIds().forEach { compatibleContexts[it] = targetCompatible }
            }
        }
        return { selector ->
            val exactProjectionTargets = mappedTargets
                .filter { (source, _) -> selector.matches(source) }
                .map { (_, target) -> target }
            if (exactProjectionTargets.isNotEmpty()) {
                TargetSelector(
                    identityKeys = exactProjectionTargets
                        .mapTo(linkedSetOf(), ChordTarget::identityKey),
                )
            } else selector.copy(
                identityKeys = selector.identityKeys.mapTo(linkedSetOf()) { identityKeys[it] ?: it },
                sonorityIdentityKeys = selector.sonorityIdentityKeys.mapTo(linkedSetOf()) {
                    sonorityKeys[it] ?: it
                },
                interpretationIdentityKeys = selector.interpretationIdentityKeys.mapTo(linkedSetOf()) {
                    interpretationKeys[it] ?: it
                },
                primaryContextIds = selector.primaryContextIds.mapTo(linkedSetOf()) {
                    primaryContexts[it] ?: it
                },
                compatibleContextIds = selector.compatibleContextIds.mapTo(linkedSetOf()) {
                    compatibleContexts[it] ?: it
                },
            )
        }
    }

    private fun ChordTarget.matchesSelectedInterpretation(
        target: ChordTarget,
        allowAudibleReinterpretation: Boolean,
    ): Boolean {
        if (allowAudibleReinterpretation) {
            return bassPitchClass == target.bassPitchClass &&
                sonority.pitchClasses.map { it.value }.toSet() ==
                target.sonority.pitchClasses.map { it.value }.toSet()
        }
        val sourceInterpreted = this as? InterpretedChordTarget
        val targetInterpreted = target as? InterpretedChordTarget
        if (
            sourceInterpreted != null && targetInterpreted != null &&
            sourceInterpreted.interpretation.id == targetInterpreted.interpretation.id
        ) {
            return inversion == target.inversion &&
                bassPitchClass == target.bassPitchClass &&
                sonority.pitchClasses.map { it.value }.toSet() ==
                target.sonority.pitchClasses.map { it.value }.toSet()
        }
        if (
            degree != target.degree || quality != target.quality || arity != target.arity ||
            inversion != target.inversion ||
            sonority.pitchClasses.map { it.value }.toSet() !=
            target.sonority.pitchClasses.map { it.value }.toSet()
        ) return false
        return true
    }

    private fun WorkspaceIdiomInstance.viewedAsSourceKey(): ModulationKey? {
        val fifths = parameters[VIEWED_AS_SOURCE_FIFTHS_PARAMETER]?.toIntOrNull() ?: return null
        val mode = parameters[VIEWED_AS_SOURCE_MODE_PARAMETER]
            ?.let { runCatching { KeySignatureMode.valueOf(it) }.getOrNull() }
            ?: return null
        return ModulationKey(fifths, mode)
    }

    private fun WorkspaceIdiomInstance.teachingSource(): SchoenbergTeachingSource? =
        parameters[TEACHING_SOURCE_PARAMETER]?.let(SchoenbergTeachingSourceCodec::decode)

    private fun WorkspaceIdiomInstance.viewedAsSourceTargets(
        projectionTargets: List<ChordTarget>,
    ): List<ChordTarget>? {
        val sourceKey = viewedAsSourceKey() ?: return null
        val interpretationIds = parameters[VIEWED_AS_SOURCE_INTERPRETATIONS_PARAMETER]
            ?.split('|')
            ?.takeIf { it.size == projectionTargets.size }
            ?: return null
        val candidates = ChordSelectionTargetCatalog.targets(sourceKey)
            .filterIsInstance<InterpretedChordTarget>()
        return interpretationIds.zip(projectionTargets).map { (interpretationId, projected) ->
            candidates.firstOrNull { source ->
                source.interpretation.id.value == interpretationId &&
                    source.bassPitchClass == projected.bassPitchClass &&
                    source.sonority.pitchClasses.map { it.value }.toSet() ==
                    projected.sonority.pitchClasses.map { it.value }.toSet()
            } ?: return null
        }
    }

    private fun Constraint.freePracticeWeight(): Double =
        if (expr.containsExplicitVoiceLeading()) {
            VOICE_LEADING_RULE_WEIGHT
        } else {
            CHAPTER_RULE_WEIGHT
        }

    private fun Constraint.freePracticeModality(): ConstraintModality =
        when (modality) {
            ConstraintModality.Annotate -> ConstraintModality.Annotate
            else -> ConstraintModality.Prefer(freePracticeWeight())
        }

    private fun ConstraintExpr.containsExplicitVoiceLeading(): Boolean = when (this) {
        is ConstraintExpr.Atom -> predicate is ConstraintPredicate.NeighborTone ||
            predicate is ConstraintPredicate.VoiceDiatonicSteps
        is ConstraintExpr.And -> terms.any { it.containsExplicitVoiceLeading() }
        is ConstraintExpr.Or -> branches.any { it.expr.containsExplicitVoiceLeading() }
        is ConstraintExpr.Not -> term.containsExplicitVoiceLeading()
    }

    /** Ordinary chapter preferences remain explanatory nudges in free practice. */
    const val CHAPTER_RULE_WEIGHT: Double = 4.0

    /** Explicit smooth voice-leading outranks the generic adjacent-voice unison penalty (100). */
    const val VOICE_LEADING_RULE_WEIGHT: Double = 120.0
}
