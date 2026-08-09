package com.mecon.theory.harmony

import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.ChordIdentityMode
import com.mecon.theory.constraint.identityKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChordCatalogTest {
    private val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
    private val tonalContext = TonalContext.fromKey(key)
    private val context = ChordConstructionContext(tonalContext)
    private val major = BuiltInChordDefinitions.forQuality(ChordQuality.MAJOR)

    @Test
    fun collectsSameSpelledTonesAndRetainsDistinctInterpretations() {
        val first = majorConstruction(
            rootDegree = 1,
            rootAlteration = 0,
            interpretationId = "diatonic.tonic",
            function = HarmonicFunction.TONIC,
        )
        val second = majorConstruction(
            rootDegree = 1,
            rootAlteration = 0,
            interpretationId = "borrowed.reference",
            function = HarmonicFunction.COLOR,
        )

        val catalog = ChordCatalogCollector.collect(listOf(first, second))

        assertEquals(1, catalog.entries.size)
        assertEquals(
            listOf("borrowed.reference", "diatonic.tonic"),
            catalog.entries.single().interpretations.map { it.id.value },
        )
    }

    @Test
    fun spellingPreservingCollectionDoesNotMergeEnharmonicMajorTriads() {
        val cSharp = majorConstruction(
            rootDegree = 1,
            rootAlteration = 1,
            interpretationId = "c-sharp-major",
        )
        val dFlat = majorConstruction(
            rootDegree = 2,
            rootAlteration = -1,
            interpretationId = "d-flat-major",
        )

        val catalog = ChordCatalogCollector.collect(listOf(cSharp, dFlat))

        assertEquals(2, catalog.entries.size)
        assertEquals(
            1,
            catalog.entries
                .map { it.sonority.definedSonority.pitchClasses.toSet() }
                .distinct()
                .size,
            "The two entries sound alike but retain different spellings.",
        )
    }

    @Test
    fun interpretedTargetsShareRealizationButKeepFunctionalIdentity() {
        val catalog = ChordCatalogCollector.collect(
            listOf(
                majorConstruction(1, 0, "diatonic.tonic", HarmonicFunction.TONIC),
                majorConstruction(1, 0, "temporary.subdominant", HarmonicFunction.PREDOMINANT),
            )
        )
        val entry = catalog.entries.single()
        val bassTone = entry.sonority.toneIdForMember(entry.sonority.definition.members.first().id)!!
        val first = InterpretedChordTarget(key, entry, entry.interpretations[0], bassTone)
        val second = InterpretedChordTarget(key, entry, entry.interpretations[1], bassTone)

        assertEquals(first.sonorityIdentityKey(), second.sonorityIdentityKey())
        assertEquals(first.realizationIdentityKey(), second.realizationIdentityKey())
        assertNotEquals(first.interpretationIdentityKey(), second.interpretationIdentityKey())
        assertNotEquals(first.identityKey(), second.identityKey())
        assertEquals(first.identityKey(ChordIdentityMode.SONORITY), second.identityKey(ChordIdentityMode.SONORITY))
        assertNotEquals(
            first.identityKey(ChordIdentityMode.INTERPRETATION),
            second.identityKey(ChordIdentityMode.INTERPRETATION),
        )
    }

    private fun majorConstruction(
        rootDegree: Int,
        rootAlteration: Int,
        interpretationId: String,
        function: HarmonicFunction = HarmonicFunction.OTHER,
    ): ConstructedChord {
        val recipeId = ChordRecipeId("test.$interpretationId")
        val root = tonalContext.spellDegree(rootDegree, rootAlteration)
        val interpretation = ChordInterpretation(
            id = InterpretationId(interpretationId),
            lens = TonalLens(tonalContext.id),
            symbol = FunctionalChordSymbol(
                degree = rootDegree,
                alteration = rootAlteration,
                quality = ChordQuality.MAJOR,
                arity = ChordArity.TRIAD,
            ),
            function = function,
            toneRoles = ChordBuilder.structuralToneRoles(major, root),
            structuralToneOrder = ChordBuilder.structuralToneOrder(major, root),
            trace = InterpretationTrace(recipeId),
        )
        return ChordBuilder.fromDefinition(
            context = context,
            definition = major,
            rootDegree = rootDegree,
            rootAlteration = rootAlteration,
            interpretation = interpretation,
            trace = ConstructionTrace(recipeId),
        )
    }
}
