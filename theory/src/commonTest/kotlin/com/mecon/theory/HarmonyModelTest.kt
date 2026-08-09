package com.mecon.theory

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HarmonyModelTest {
    @Test
    fun spelledPitchClassesKeepEnharmonicIdentity() {
        val cSharp = SpelledPitchClass(NoteName.C, 1)
        val dFlat = SpelledPitchClass(NoteName.D, -1)

        assertEquals(cSharp.pitchClass, dFlat.pitchClass)
        assertNotEquals(cSharp, dFlat)
        assertEquals("C#", cSharp.toString())
        assertEquals("Db", dFlat.toString())
    }

    @Test
    fun alteredExtensionsReceiveFunctionalSpellings() {
        val c = SpelledPitchClass(NoteName.C)
        val flatNine = BuiltInChordDefinitions.forQuality(ChordQuality.DOMINANT7_FLAT9)
            .member(ChordMemberId("ninth"))!!
            .spellAbove(c)
        val sharpNine = BuiltInChordDefinitions.forQuality(ChordQuality.DOMINANT7_SHARP9)
            .member(ChordMemberId("ninth"))!!
            .spellAbove(c)

        assertEquals("Db", flatNine.toString())
        assertEquals("D#", sharpNine.toString())
        assertNotEquals(flatNine, sharpNine)
    }

    @Test
    fun extendedLegacyChordNoLongerFallsBackToMajorTriad() {
        val cMajorThirteen = Chord(PitchClass.C, ChordQuality.MAJOR13)

        assertEquals(
            setOf(0, 2, 4, 5, 7, 9, 11),
            cMajorThirteen.pitchClasses.map { it.value }.toSet(),
        )
    }

    @Test
    fun modalContextSpellsDegreesFromWrittenTonic() {
        val context = TonalContext(
            id = TonalContextId("d-flat-major"),
            tonic = SpelledPitchClass(NoteName.D, -1),
            scale = ScaleDefinition.fromMode(Mode.IONIAN),
            keySignature = NotationalKeySignature(-5),
        )

        assertEquals("Db", context.spellDegree(1).toString())
        assertEquals("Gb", context.spellDegree(4).toString())
        assertEquals("C", context.spellDegree(7).toString())
        assertEquals(PitchClass(1), context.pitchClassForDegree(1))
    }

    @Test
    fun voicePlanOrdersAnyNumberOfVoices() {
        val six = VoicePlan.standardFourPart().voices + listOf(
            VoicePlan.standardFourPart().voices[1].copy(
                id = com.mecon.api.primitive.TrackId("solver-inner-1"),
                order = 4,
            ),
            VoicePlan.standardFourPart().voices[2].copy(
                id = com.mecon.api.primitive.TrackId("solver-inner-2"),
                order = 5,
            ),
        )
        val plan = VoicePlan(
            six.mapIndexed { index, voice ->
                voice.copy(
                    order = index,
                    boundary = when (index) {
                        0 -> VoiceBoundary.UPPER_OUTER
                        six.lastIndex -> VoiceBoundary.LOWER_OUTER
                        else -> VoiceBoundary.INNER
                    },
                )
            }
        )

        assertEquals(6, plan.orderedHighToLow.size)
        assertTrue(plan.orderedHighToLow.zipWithNext().all { (left, right) -> left.order < right.order })
    }
}
