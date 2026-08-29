package com.mecon.plugins.chord

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonalRegionInferenceTest {
    private val cMajor = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun diatonicCandidatesWinAndReferenceKeyBreaksTheTie() {
        val candidates = TonalRegionKeyInference.candidates(
            listOf(Pitch.C4, Pitch.E4, Pitch.G4),
            cMajor,
        )

        assertEquals(cMajor, candidates.first().key)
        assertEquals(listOf("1", "3", "5"), candidates.first().degreeLabels)
        assertEquals(0, candidates.first().alteredToneCount)
    }

    @Test
    fun familiarRaisedFourthRanksAheadOfAnUnusualAlteration() {
        val candidates = TonalRegionKeyInference.candidates(
            listOf(Pitch.C4, Pitch(0, 1), Pitch.G4),
            cMajor,
        )
        val gMajor = candidates.first { it.key == ModulationKey(1, KeySignatureMode.MAJOR) }
        val cMajorCandidate = candidates.first { it.key == cMajor }

        assertEquals(listOf("4", "♯4", "1"), gMajor.degreeLabels)
        assertTrue(candidates.indexOf(gMajor) < candidates.indexOf(cMajorCandidate))
    }

    @Test
    fun singlePitchChoicesExposeMajorAndMinorForTheChosenDegree() {
        val choices = TonalRegionKeyInference.singlePitchChoices(Pitch.G4, cMajor)

        assertTrue(choices.any { it.key == cMajor && it.degreeLabels == listOf("5") })
        assertTrue(
            choices.any {
                it.key == ModulationKey(0, KeySignatureMode.MINOR) && it.degreeLabels == listOf("5")
            },
        )
    }

    @Test
    fun minorCandidatesUseRelativeMajorDegreeNumbers() {
        val aMinor = ModulationKey(0, KeySignatureMode.MINOR)
        val candidate = TonalRegionKeyInference.candidates(
            listOf(Pitch.C4, Pitch.E4, Pitch.A4),
            aMinor,
        ).first { it.key == aMinor }

        assertEquals(listOf("1", "3", "6"), candidate.degreeLabels)
    }

    @Test
    fun terminatingAnOverlappingRegionClearsItsContinuationAtomically() {
        val previous = region("previous", quarter(0), quarter(4), ModulationKey(0, KeySignatureMode.MAJOR))
        val inserted = region("inserted", quarter(1), quarter(2), ModulationKey(1, KeySignatureMode.MAJOR))

        val result = TonalRegionEditPolicy.insert(listOf(previous), inserted, terminatePrevious = true)
        val shortened = result.first { it.id == previous.id }

        assertEquals(inserted.endOnset, shortened.endOnset)
        assertNull(shortened.resolvedKey)
        assertEquals(inserted, result.first { it.id == inserted.id })
    }

    @Test
    fun previousRegionEndsAtTheSelectedRangeEndWhileTheNewRegionKeepsItsOpenTail() {
        val previous = region("previous", quarter(0), quarter(8), ModulationKey(0, KeySignatureMode.MAJOR))
        val inserted = region("inserted", quarter(1), quarter(8), ModulationKey(1, KeySignatureMode.MAJOR))

        val result = TonalRegionEditPolicy.insert(
            existing = listOf(previous),
            region = inserted,
            terminatePrevious = true,
            terminatePreviousAt = quarter(2),
        )

        assertEquals(quarter(2), result.first { it.id == previous.id }.endOnset)
        assertEquals(quarter(8), result.first { it.id == inserted.id }.endOnset)
        assertNull(result.first { it.id == previous.id }.resolvedKey)
    }

    @Test
    fun firstInsertionMaterializesTheScoreKeyAsAnEditableOverlappingBaseline() {
        val baseline = StorageTonalRegionEvent(
            id = EventId("baseline"),
            onset = quarter(0),
            endOnset = quarter(8),
            keys = listOf(PolyphonyTonalKey.from(cMajor)),
            resolvedKey = null,
            role = TonalRegionRole.SCORE_KEY_BASELINE,
        )
        val inserted = region(
            "inserted",
            quarter(1),
            quarter(8),
            ModulationKey(1, KeySignatureMode.MAJOR),
        )

        val result = TonalRegionEditPolicy.insert(
            existing = emptyList(),
            region = inserted,
            terminatePrevious = true,
            terminatePreviousAt = quarter(2),
            fallbackPrevious = baseline,
        )

        val storedBaseline = result.first { it.role == TonalRegionRole.SCORE_KEY_BASELINE }
        assertEquals(quarter(0), storedBaseline.onset)
        assertEquals(quarter(2), storedBaseline.endOnset)
        assertNull(storedBaseline.resolvedKey)
        assertEquals(quarter(8), result.first { it.id == inserted.id }.endOnset)
    }

    @Test
    fun insertionCanLeaveThePreviousRegionUntouched() {
        val previous = region("previous", quarter(0), quarter(4), ModulationKey(0, KeySignatureMode.MAJOR))
        val inserted = region("inserted", quarter(1), quarter(2), ModulationKey(1, KeySignatureMode.MAJOR))

        val result = TonalRegionEditPolicy.insert(listOf(previous), inserted, terminatePrevious = false)

        assertEquals(previous, result.first { it.id == previous.id })
    }

    @Test
    fun newRegionDefaultsToTheScoreEnd() {
        assertEquals(
            quarter(8),
            TonalRegionEditPolicy.defaultInsertionEnd(
                start = quarter(1),
                selectedEnd = quarter(2),
                scoreEnd = quarter(8),
            ),
        )
    }

    @Test
    fun newRegionStopsAtTheNextWrittenKeySignature() {
        assertEquals(
            quarter(4),
            TonalRegionEditPolicy.defaultInsertionEnd(
                start = quarter(1),
                selectedEnd = quarter(2),
                scoreEnd = quarter(8),
                nextKeySignatureChange = quarter(4),
            ),
        )
    }

    @Test
    fun endpointResizeKeepsTheRegionNonEmpty() {
        val original = region("region", quarter(1), quarter(4), cMajor)

        assertEquals(
            quarter(2),
            TonalRegionEditPolicy.resize(
                original,
                TonalRegionEditPolicy.Endpoint.START,
                quarter(2),
            ).onset,
        )
        assertEquals(
            original,
            TonalRegionEditPolicy.resize(
                original,
                TonalRegionEditPolicy.Endpoint.END,
                quarter(1),
            ),
        )

        val baseline = original.copy(
            role = TonalRegionRole.SCORE_KEY_BASELINE,
            resolvedKey = null,
        )
        assertEquals(
            baseline,
            TonalRegionEditPolicy.resize(
                baseline,
                TonalRegionEditPolicy.Endpoint.START,
                quarter(2),
            ),
        )
        assertEquals(
            quarter(3),
            TonalRegionEditPolicy.resize(
                baseline,
                TonalRegionEditPolicy.Endpoint.END,
                quarter(3),
            ).endOnset,
        )
    }

    private fun region(id: String, start: TimeCode, end: TimeCode, key: ModulationKey) =
        StorageTonalRegionEvent(
            id = EventId(id),
            onset = start,
            endOnset = end,
            keys = listOf(PolyphonyTonalKey.from(key)),
        )

    private fun quarter(value: Int): TimeCode = TimeCode.of(1, Fraction(value, 4))
}
