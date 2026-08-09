package com.mecon.theory

import kotlin.jvm.JvmInline

@JvmInline
value class ModulationChordVocabularyId(val value: String) {
    init { require(value.isNotBlank()) }

    companion object {
        val NATURAL_TRIADS = ModulationChordVocabularyId("natural-triads")
        val PARALLEL_COMMON_TONE = ModulationChordVocabularyId("parallel-common-tone")
    }
}

data class CommonChordPivotStep(
    val target: ModulationKey,
    val pivotChordId: ModulationChordId,
    val vocabularyId: ModulationChordVocabularyId =
        ModulationChordVocabularyId.NATURAL_TRIADS,
)

enum class TonalEndingIntent {
    OPEN_FRAGMENT,
    LIGHT_CONFIRMATION,
    ESTABLISHED,
}

@JvmInline
value class TonalTechniqueId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class TonalTechniqueSelection(
    val id: TonalTechniqueId,
)

data class TonalRoutePlan(
    val source: ModulationKey,
    val steps: List<CommonChordPivotStep>,
    val endingIntent: TonalEndingIntent = TonalEndingIntent.OPEN_FRAGMENT,
    val techniques: List<TonalTechniqueSelection> = emptyList(),
) {
    init {
        var from = source
        steps.forEach { step ->
            when (step.vocabularyId) {
                ModulationChordVocabularyId.NATURAL_TRIADS -> require(
                    ModulationCommonChordCatalog.commonChords(from, step.target)
                        .any { it.id == step.pivotChordId }
                ) {
                    "${step.pivotChordId} is not a common chord of ${from.displayName} and ${step.target.displayName}"
                }
                ModulationChordVocabularyId.PARALLEL_COMMON_TONE -> {
                    require(from.key.root == step.target.key.root && from.mode != step.target.mode) {
                        "Parallel common-tone steps require opposite modes on the same tonic"
                    }
                    val available = NaturalTriads.inKey(from.key) + NaturalTriads.inKey(step.target.key)
                    require(available.any {
                        it.root == step.pivotChordId.root && it.quality == step.pivotChordId.quality
                    })
                }
                else -> error("Unsupported modulation vocabulary ${step.vocabularyId.value}")
            }
            from = step.target
        }
        require(techniques.map { it.id }.toSet().size == techniques.size)
    }

    val keys: List<ModulationKey>
        get() = buildList {
            add(source)
            steps.forEach { add(it.target) }
        }
}

data class TonalTechniqueNode(
    val id: TonalTechniqueId,
    val prerequisites: Set<TonalTechniqueId> = emptySet(),
    val conflicts: Set<TonalTechniqueId> = emptySet(),
    val applicable: (TonalRoutePlan) -> Boolean = { true },
    val recommended: (TonalRoutePlan) -> Boolean = { false },
)

object TonalTechniqueGraph {
    val CADENTIAL_CONFIRMATION = TonalTechniqueId("cadential-confirmation")
    val SUSTAINED_TONE = TonalTechniqueId("sustained-tone")

    val nodes: List<TonalTechniqueNode> = listOf(
        TonalTechniqueNode(
            id = CADENTIAL_CONFIRMATION,
            applicable = { it.endingIntent != TonalEndingIntent.OPEN_FRAGMENT },
        ),
        TonalTechniqueNode(
            id = SUSTAINED_TONE,
            prerequisites = setOf(CADENTIAL_CONFIRMATION),
            applicable = { it.endingIntent == TonalEndingIntent.ESTABLISHED },
            recommended = { route ->
                route.keys.zipWithNext().any { (from, to) ->
                    from.mode == KeySignatureMode.MINOR &&
                        to.mode == KeySignatureMode.MAJOR &&
                        from.key.root == to.key.root
                }
            },
        ),
    )

    fun recommendations(route: TonalRoutePlan): Set<TonalTechniqueId> =
        nodes.filter { it.applicable(route) && it.recommended(route) }.mapTo(linkedSetOf()) { it.id }

    fun validateAcyclic() {
        val byId = nodes.associateBy { it.id }
        val visiting = hashSetOf<TonalTechniqueId>()
        val visited = hashSetOf<TonalTechniqueId>()
        fun visit(id: TonalTechniqueId) {
            if (id in visited) return
            check(visiting.add(id)) { "Technique graph contains a cycle at $id" }
            byId.getValue(id).prerequisites.forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        nodes.forEach { visit(it.id) }
    }
}
