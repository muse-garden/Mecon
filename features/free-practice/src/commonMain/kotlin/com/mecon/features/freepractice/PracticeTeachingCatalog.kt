package com.mecon.features.freepractice

import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.schoenberg.SchoenbergFreePracticeCatalog
import com.mecon.theory.schoenberg.SchoenbergFreePracticeChordFocus
import com.mecon.theory.schoenberg.SchoenbergFreePracticeDiscoveryRequest
import com.mecon.theory.schoenberg.allFreePracticeTargetKeys

/** CPU-only teaching catalog executor. Platforms run it on their catalog worker/dispatcher. */
object PracticeTeachingCatalogExecutor {
    fun execute(request: PracticeTeachingCatalogRequest): PracticeTeachingCatalogResult = runCatching {
        val catalogKey = request.catalogKey.toTheory()
        val focus = request.focus?.let { SchoenbergFreePracticeChordFocus(catalogKey, it) }
        val defaultDiscovery = SchoenbergFreePracticeDiscoveryRequest(
            initialKey = request.initialKey.toTheory(),
            activeKeys = request.activeKeys.map { it.toTheory() },
            catalogKey = catalogKey,
            onlyAvailableByDefault = true,
        )
        val defaults = SchoenbergFreePracticeCatalog.buildIndex(defaultDiscovery).discover()
        val focused = focus?.let { selected ->
            val focusedDiscovery = defaultDiscovery.copy(
                onlyAvailableByDefault = false,
                includeOffKey = request.includeOffKey,
                targetKeys = if (request.includeOffKey) allFreePracticeTargetKeys() else emptyList(),
            )
            SchoenbergFreePracticeCatalog.buildIndex(focusedDiscovery).discover(selected)
        }
        val focusedDefinitions = focused?.idioms.orEmpty()
        val defaultDefinitions = defaults.idioms
        val definitions = (focusedDefinitions + defaultDefinitions)
            .groupBy { it.id }
            .map { (definitionId, sameDefinitions) ->
                val definition = sameDefinitions.first()
                val focusedVariants = focusedDefinitions.filter { it.id == definitionId }.flatMap { it.variants }
                val defaultVariants = defaultDefinitions.filter { it.id == definitionId }.flatMap { it.variants }
                val variants = (focusedVariants + defaultVariants).groupBy { it.id }.map { (_, sameVariants) ->
                    val variant = sameVariants.first()
                    variant to Pair(
                        focusedVariants.any { it.id == variant.id },
                        defaultVariants.any { it.id == variant.id },
                    )
                }
                PracticeIdiomDefinitionView(
                    id = definitionId,
                    title = definition.title,
                    sourceExerciseId = definition.sourceExerciseId,
                    sourceChapterId = definition.sourceChapterId,
                    availableByDefault = defaultDefinitions.any { it.id == definitionId },
                    variants = variants.mapNotNull { (variant, membership) ->
                        val view = PracticeIdiomVariantView(
                            id = variant.id,
                            title = variant.title,
                            durations = variant.durations,
                            chordIdentities = variant.chordIdentities,
                            chordChoices = variant.chordChoices,
                            suggestedKey = variant.suggestedKey?.let {
                                PracticeKeyView(it.fifths, WorkspaceKeyMode.fromTheory(it.mode))
                            },
                            targetKeyDistance = variant.targetKeyDistance,
                            parameters = variant.parameters,
                            anchorStepIndex = variant.anchorStepIndex,
                            fixedInversionStepIndices = variant.fixedInversionStepIndices,
                            relatedToFocus = membership.first,
                            availableByDefault = membership.second,
                        )
                        view.takeIf { !request.harmonicRoleFilterEnabled || request.accepts(it) }
                    },
                    relatedToFocus = focusedVariants.isNotEmpty(),
                )
            }
        PracticeTeachingCatalogResult(
            requestId = request.requestId,
            baseRevision = request.baseRevision,
            fingerprint = request.fingerprint,
            definitions = definitions,
        )
    }.getOrElse {
        PracticeTeachingCatalogResult(
            requestId = request.requestId,
            baseRevision = request.baseRevision,
            fingerprint = request.fingerprint,
            errorKey = "freePractice.catalog.failed",
        )
    }

    private fun PracticeKeyView.toTheory(): ModulationKey = ModulationKey(fifths, mode.toTheory())

    private fun PracticeTeachingCatalogRequest.accepts(variant: PracticeIdiomVariantView): Boolean {
        if (variant.chordChoices.size != variant.durations.size) return false
        val prefix = variant.durations.take(variant.anchorStepIndex)
            .fold(com.mecon.api.primitive.Fraction.ZERO) { total, duration -> total + duration }
        val start = focusOnset - prefix
        return harmonicRoleConstraints.all { constraint ->
            var cursor = start
            val index = variant.durations.indexOfFirst { duration ->
                val contains = constraint.onset >= cursor && constraint.onset < cursor + duration
                cursor += duration
                contains
            }
            if (index < 0) true else {
                val belongs = constraint.pitchClass in variant.chordChoices[index].pitchClasses
                belongs == (constraint.role == com.mecon.exploration.PracticeHarmonicRole.CHORD_TONE)
            }
        }
    }
}
