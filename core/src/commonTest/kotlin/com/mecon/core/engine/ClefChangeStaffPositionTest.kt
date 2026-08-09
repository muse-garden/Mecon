package com.mecon.core.engine

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that mid-score clef changes affect vertical staff position.
 *
 * Staff position formula: pitch.diatonicSteps − middleLineDiatonicSteps(clef)
 *   Treble middle line (B4) = 6
 *   Bass middle line (D3)   = −6
 *
 * Notes at or after a clef change onset use the new clef; earlier notes use the
 * initial clef. The effective clef is resolved by ComputeEngine.effectiveClef().
 */
class ClefChangeStaffPositionTest {

    private fun buildAndCompute(
        initialClef: Clef = Clef.TREBLE,
        clefChanges: List<StorageClefChange> = emptyList(),
        vararg notes: Pair<TimeCode, Pitch>
    ): List<Int> {
        val base = StorageScore.create(StorageScore.CreationOptions("ClefTest"))
        val staffId = base.staffTracks.keys.first()
        val updatedStaff = base.staffTracks.getValue(staffId)
            .copy(clef = initialClef, clefChanges = clefChanges)
        val storage = base.copy(staffTracks = base.staffTracks + (staffId to updatedStaff))

        var runtime = RuntimeScore.fromStorage(storage)
        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        for ((onset, pitch) in notes) {
            val pe = RuntimePitchEvent.single(onset = onset, pitch = pitch)
            runtime = runtime.addPitchEvent(pitchTrackId, pe)
            runtime = runtime.addVoiceEvent(
                voiceTrackId,
                RuntimeVoiceEvent.create(onset = onset, pitchEvent = pe, duration = Duration.QUARTER)
            )
        }

        return computeScore(runtime).allEventsSorted()
            .filter { !it.isRest }  // skip implicit whole-measure rests in empty measures
            .map { it.pitchData.first().staffPosition }
    }

    // G4: diatonicSteps=4 — treble: 4−6 = −2 (2nd line from bottom)
    @Test
    fun g4InTreble_isOnSecondLine() {
        val positions = buildAndCompute(
            initialClef = Clef.TREBLE,
            notes = arrayOf(TimeCode.of(1, Fraction.ZERO) to Pitch.G4)
        )
        assertEquals(-2, positions.single())
    }

    // G2: diatonicSteps=−10 — treble: −10−6 = −16 (many ledger lines below)
    @Test
    fun g2InTreble_isFarBelowStaff() {
        val g2 = Pitch(-10, 0)
        val positions = buildAndCompute(
            initialClef = Clef.TREBLE,
            notes = arrayOf(TimeCode.of(1, Fraction.ZERO) to g2)
        )
        assertEquals(-16, positions.single())
    }

    // G2 in bass: −10−(−6) = −4 (bottom line)
    @Test
    fun g2InBass_isOnBottomLine() {
        val g2 = Pitch(-10, 0)
        val positions = buildAndCompute(
            initialClef = Clef.BASS,
            notes = arrayOf(TimeCode.of(1, Fraction.ZERO) to g2)
        )
        assertEquals(-4, positions.single())
    }

    // Clef change at M2 onset: M1 note uses treble, M2 note uses bass.
    @Test
    fun clefChange_noteBeforeUsesInitialClef_noteAtChangeOnsetUsesNewClef() {
        val g2 = Pitch(-10, 0)
        val clefChange = StorageClefChange(onset = TimeCode.of(2, Fraction.ZERO), clef = Clef.BASS)
        val positions = buildAndCompute(
            initialClef = Clef.TREBLE,
            clefChanges = listOf(clefChange),
            notes = arrayOf(
                TimeCode.of(1, Fraction.ZERO) to g2,
                TimeCode.of(2, Fraction.ZERO) to g2
            )
        )
        assertEquals(-16, positions[0], "M1 note should use initial treble clef")
        assertEquals(-4,  positions[1], "M2 note at change onset should use bass clef")
    }

    // Clef change mid-measure: note before the beat uses old clef, note at/after uses new clef.
    @Test
    fun clefChangeMidMeasure_splitsAtExactOnset() {
        val g2 = Pitch(-10, 0)
        val beatTwo = TimeCode.of(1, Fraction(1, 4))
        val clefChange = StorageClefChange(onset = beatTwo, clef = Clef.BASS)
        val positions = buildAndCompute(
            initialClef = Clef.TREBLE,
            clefChanges = listOf(clefChange),
            notes = arrayOf(
                TimeCode.of(1, Fraction.ZERO) to g2,  // beat 1 — treble
                beatTwo to g2                           // beat 2 — bass
            )
        )
        assertEquals(-16, positions[0], "Beat 1: treble")
        assertEquals(-4,  positions[1], "Beat 2 (at change onset): bass")
    }

    @Test
    fun computedClefChangeKeepsStorageOnset() {
        val changeOnset = TimeCode.of(1, Fraction(1, 4))
        val base = StorageScore.create(StorageScore.CreationOptions("ClefTest"))
        val staffId = base.staffTracks.keys.first()
        val storage = base.copy(
            staffTracks = base.staffTracks + (
                staffId to base.staffTracks.getValue(staffId)
                    .copy(clefChanges = listOf(StorageClefChange(changeOnset, Clef.BASS)))
                )
        )

        val computed = computeScore(RuntimeScore.fromStorage(storage))
        val changeClef = computed.allClefsSorted().single { !it.isInitial }
        assertEquals(changeOnset, changeClef.time)
    }

    // Multiple clef changes: treble → bass (M2) → treble (M3)
    @Test
    fun multipleClefChanges_eachNoteUsesCorrectClef() {
        val g2 = Pitch(-10, 0)
        val changes = listOf(
            StorageClefChange(onset = TimeCode.of(2, Fraction.ZERO), clef = Clef.BASS),
            StorageClefChange(onset = TimeCode.of(3, Fraction.ZERO), clef = Clef.TREBLE)
        )
        val positions = buildAndCompute(
            initialClef = Clef.TREBLE,
            clefChanges = changes,
            notes = arrayOf(
                TimeCode.of(1, Fraction.ZERO) to g2,
                TimeCode.of(2, Fraction.ZERO) to g2,
                TimeCode.of(3, Fraction.ZERO) to g2
            )
        )
        assertEquals(-16, positions[0], "M1: treble")
        assertEquals(-4,  positions[1], "M2: bass")
        assertEquals(-16, positions[2], "M3: treble again")
    }
}
