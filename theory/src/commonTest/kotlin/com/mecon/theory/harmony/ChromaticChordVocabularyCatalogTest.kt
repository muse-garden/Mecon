package com.mecon.theory.harmony

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Key
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.RootlessDominantNinthVocabulary
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.constraint.SecondaryHarmonyVocabulary
import com.mecon.theory.constraint.toInterpretedTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromaticChordVocabularyCatalogTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(key)

    @Test
    fun secondaryConstructionsSurviveCollectionAsInterpretations() {
        val types = SecondaryHarmonyVocabulary.harmonyTypes(
            context = context,
            sourceMode = key.mode,
        ).filter { type ->
            type.tonicizedDegree != 1 || type.family in setOf(
                SecondaryHarmonyFamily.MODAL_AUGMENTED,
                SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
            )
        }
        val catalog = SecondaryHarmonyVocabulary.catalog(context, key)

        assertEquals(types.size, catalog.entries.sumOf { it.interpretations.size })
        assertEquals(
            catalog.entries.sumOf { entry ->
                entry.interpretations.sumOf { it.structuralToneOrder.size }
            },
            catalog.toInterpretedTargets(key).size,
        )
    }

    @Test
    fun symmetricDiminishedSonoritiesRetainEveryDominantReading() {
        val types = RootlessDominantNinthVocabulary.types(context)
        val catalog = RootlessDominantNinthVocabulary.catalog(context)

        assertTrue(catalog.entries.size < types.size)
        assertEquals(types.size, catalog.entries.sumOf { it.interpretations.size })
        assertTrue(catalog.entries.any { it.interpretations.size > 1 })
        assertEquals(
            types.map { "rootless-dominant-ninth.${it.id}" }.toSet(),
            catalog.entries
                .flatMap { it.interpretations }
                .map { it.id.value }
                .toSet(),
        )
    }
}
