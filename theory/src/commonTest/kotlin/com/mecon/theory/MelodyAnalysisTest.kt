package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.runtime.RuntimeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MelodyAnalysisTest {
    @Test
    fun findsHighestPitchAndWhetherItIsUnique() {
        val indexedPitches = listOf(
            Pitch.fromName("C4"),
            Pitch.fromName("G4"),
            Pitch.fromName("E4"),
            Pitch.fromName("G4"),
        ).withIndex().toList()

        val peak = MelodyAnalysis.peak(indexedPitches, pitchOf = { it.value })!!

        assertEquals(Pitch.fromName("G4"), peak.pitch)
        assertFalse(peak.isUnique)
        assertEquals(listOf(1, 3), peak.items.map { it.index })
    }

    @Test
    fun analyzesFixedVoiceScoreEventsWithoutWrappingThem() {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = listOf(
                    listOf(Pitch.fromName("C5")),
                    listOf(Pitch.fromName("E5")),
                    listOf(Pitch.fromName("G5")),
                )
            )
        )
        val fixed = FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
        val soprano = fixed.voices.first { it.role == FixedVoiceRole.SOPRANO }
        val noteEvents = fixed.noteEventsForVoice(soprano)

        val peak = MelodyAnalysis.peak(noteEvents, pitchOf = { it.pitch!! })!!
        val motions = MelodyAnalysis.motions(noteEvents, pitchOf = { it.pitch!! })

        assertEquals(Pitch.fromName("G5"), peak.pitch)
        assertEquals(noteEvents.last().id, peak.first.id)
        assertEquals(listOf(MelodyDirection.ASCENDING, MelodyDirection.ASCENDING), motions.map { it.direction })
    }

    @Test
    fun exposesMotionFactsWithoutApplyingTextbookRules() {
        val motions = MelodyAnalysis.motions(
            listOf(
                Pitch.fromName("C4"),
                Pitch.fromName("D4"),
                Pitch.fromName("C5"),
                Pitch.fromName("B4"),
            ),
            pitchOf = { it },
        )

        assertTrue(motions[0].isStep)
        assertTrue(motions[1].isSeventh)
        assertTrue(motions[2].isStep)
        assertEquals(MelodyDirection.ASCENDING, motions[1].direction)
        assertEquals(MelodyDirection.DESCENDING, motions[2].direction)
    }

    @Test
    fun detectsScaleDegreeFragmentsAndTriadOutlines() {
        val key = Key.major(PitchClass.C)
        val descending = listOf(
            Pitch.fromName("C5"),
            Pitch.fromName("B4"),
            Pitch.fromName("A4"),
            Pitch.fromName("G4"),
        )
        val triad = listOf(
            Pitch.fromName("C4"),
            Pitch.fromName("E4"),
            Pitch.fromName("G4"),
        )

        assertEquals(listOf(1, 7, 6, 5), MelodyAnalysis.scaleDegrees(descending, key, pitchOf = { it }))
        assertTrue(MelodyAnalysis.hasDescendingScaleFragment(descending, key, 0, pitchOf = { it }))
        assertTrue(MelodyAnalysis.outlinesTriad(triad, pitchOf = { it }))
    }
}
