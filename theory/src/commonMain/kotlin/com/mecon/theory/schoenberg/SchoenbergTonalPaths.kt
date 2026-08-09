package com.mecon.theory.schoenberg

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.TonalContext
import kotlin.jvm.JvmInline

@JvmInline
value class TonalPathId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

enum class TonalRelation {
    DOMINANT_MAJOR,
    SUBDOMINANT_MAJOR,
    RELATIVE_MINOR,
    PARALLEL_MAJOR,
    PARALLEL_MINOR,
    MEDIANT_MINOR,
}

enum class TonalTransitionMechanism {
    RELATIVE_REINTERPRETATION,
    PARALLEL_MODE_DOMINANT,
    APPLIED_DOMINANT,
    COMMON_DOMINANT,
    BORROWED_PARALLEL_CHORD,
}

data class TonalTransitionTemplate(
    val mechanism: TonalTransitionMechanism,
)

data class TonalPathTemplate(
    val id: TonalPathId,
    val sourceMode: KeySignatureMode,
    val steps: List<TonalRelation>,
    val transitions: List<TonalTransitionTemplate>,
    val expectedFifthsDelta: Int,
) {
    init {
        require(steps.isNotEmpty())
        require(transitions.size == steps.size)
        require(expectedFifthsDelta in setOf(-4, -3, 3, 4))
    }
}

data class TonalPathNode(
    val key: ModulationKey,
    val context: TonalContext,
)

data class TonalTransition(
    val fromIndex: Int,
    val toIndex: Int,
    val mechanism: TonalTransitionMechanism,
)

data class ResolvedTonalPath(
    val templateId: TonalPathId,
    val nodes: List<TonalPathNode>,
    val transitions: List<TonalTransition>,
) {
    val source: TonalPathNode get() = nodes.first()
    val target: TonalPathNode get() = nodes.last()
    val fifthsDelta: Int get() = target.key.fifths - source.key.fifths
}

object SchoenbergTonalPathResolver {
    fun resolve(template: TonalPathTemplate, source: ModulationKey): ResolvedTonalPath {
        require(source.mode == template.sourceMode) {
            "Path ${template.id} requires ${template.sourceMode}, got ${source.mode}"
        }
        val keys = buildList {
            add(source)
            template.steps.forEach { relation -> add(resolveRelation(last(), relation)) }
        }
        val actualDelta = keys.last().fifths - source.fifths
        require(actualDelta == template.expectedFifthsDelta) {
            "Path ${template.id} resolved to fifths delta $actualDelta, expected ${template.expectedFifthsDelta}"
        }
        return ResolvedTonalPath(
            templateId = template.id,
            nodes = keys.mapIndexed { index, key ->
                TonalPathNode(key, key.tonalContext("schoenberg.distant.${template.id.value}.$index"))
            },
            transitions = template.transitions.mapIndexed { index, transition ->
                TonalTransition(index, index + 1, transition.mechanism)
            },
        )
    }

    private fun resolveRelation(key: ModulationKey, relation: TonalRelation): ModulationKey =
        when (relation) {
            TonalRelation.DOMINANT_MAJOR -> ModulationKey(
                key.fifths + if (key.mode == KeySignatureMode.MAJOR) 1 else 4,
                KeySignatureMode.MAJOR,
            )
            TonalRelation.SUBDOMINANT_MAJOR -> ModulationKey(
                key.fifths - 1,
                KeySignatureMode.MAJOR,
            )
            TonalRelation.RELATIVE_MINOR -> {
                require(key.mode == KeySignatureMode.MAJOR)
                ModulationKey(key.fifths, KeySignatureMode.MINOR)
            }
            TonalRelation.PARALLEL_MAJOR -> {
                require(key.mode == KeySignatureMode.MINOR)
                ModulationKey(key.fifths + 3, KeySignatureMode.MAJOR)
            }
            TonalRelation.PARALLEL_MINOR -> {
                require(key.mode == KeySignatureMode.MAJOR)
                ModulationKey(key.fifths - 3, KeySignatureMode.MINOR)
            }
            TonalRelation.MEDIANT_MINOR -> {
                require(key.mode == KeySignatureMode.MAJOR)
                ModulationKey(key.fifths + 1, KeySignatureMode.MINOR)
            }
        }
}

object SchoenbergDistantTonalPaths {
    val THREE_SHARPS = TonalPathTemplate(
        id = TonalPathId("three-sharps"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(TonalRelation.RELATIVE_MINOR, TonalRelation.PARALLEL_MAJOR),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.RELATIVE_REINTERPRETATION),
            TonalTransitionTemplate(TonalTransitionMechanism.PARALLEL_MODE_DOMINANT),
        ),
        expectedFifthsDelta = 3,
    )

    val FOUR_SHARPS = TonalPathTemplate(
        id = TonalPathId("four-sharps"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(
            TonalRelation.DOMINANT_MAJOR,
            TonalRelation.RELATIVE_MINOR,
            TonalRelation.PARALLEL_MAJOR,
        ),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.COMMON_DOMINANT),
            TonalTransitionTemplate(TonalTransitionMechanism.RELATIVE_REINTERPRETATION),
            TonalTransitionTemplate(TonalTransitionMechanism.PARALLEL_MODE_DOMINANT),
        ),
        expectedFifthsDelta = 4,
    )

    val FOUR_SHARPS_APPLIED = TonalPathTemplate(
        id = TonalPathId("four-sharps-applied"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(TonalRelation.RELATIVE_MINOR, TonalRelation.DOMINANT_MAJOR),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.RELATIVE_REINTERPRETATION),
            TonalTransitionTemplate(TonalTransitionMechanism.APPLIED_DOMINANT),
        ),
        expectedFifthsDelta = 4,
    )

    val FOUR_SHARPS_BORROWED = TonalPathTemplate(
        id = TonalPathId("four-sharps-borrowed"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(TonalRelation.MEDIANT_MINOR, TonalRelation.PARALLEL_MAJOR),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.BORROWED_PARALLEL_CHORD),
            TonalTransitionTemplate(TonalTransitionMechanism.PARALLEL_MODE_DOMINANT),
        ),
        expectedFifthsDelta = 4,
    )

    val THREE_FLATS = TonalPathTemplate(
        id = TonalPathId("three-flats"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(TonalRelation.PARALLEL_MINOR),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.COMMON_DOMINANT),
        ),
        expectedFifthsDelta = -3,
    )

    val FOUR_FLATS = TonalPathTemplate(
        id = TonalPathId("four-flats"),
        sourceMode = KeySignatureMode.MAJOR,
        steps = listOf(TonalRelation.SUBDOMINANT_MAJOR, TonalRelation.PARALLEL_MINOR),
        transitions = listOf(
            TonalTransitionTemplate(TonalTransitionMechanism.COMMON_DOMINANT),
            TonalTransitionTemplate(TonalTransitionMechanism.BORROWED_PARALLEL_CHORD),
        ),
        expectedFifthsDelta = -4,
    )

    val all: List<TonalPathTemplate> = listOf(
        THREE_SHARPS,
        FOUR_SHARPS,
        FOUR_SHARPS_APPLIED,
        FOUR_SHARPS_BORROWED,
        THREE_FLATS,
        FOUR_FLATS,
    )
}
