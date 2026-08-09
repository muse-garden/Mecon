package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.api.runtime.RuntimeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLeadingAnalysisTest {
    @Test
    fun classifiesDenseAndOpenUpperVoiceArrangements() {
        val dense = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("G4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )
        val open = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        assertEquals(VerticalArrangement.DENSE, VoiceLeadingAnalysis.arrangementOf(dense.notesSoundingAt(firstTime())))
        assertEquals(VerticalArrangement.OPEN, VoiceLeadingAnalysis.arrangementOf(open.notesSoundingAt(firstTime())))
    }

    @Test
    fun detectsCrossingFromVerticalSnapshotsWhenVoicesAreNotIndexAligned() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("C4"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("C4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
            sopranoDuration = Duration.QUARTER,
            altoDuration = Duration.HALF,
        )

        val crossings = VoiceLeadingAnalysis.crossings(fixed)

        assertTrue(crossings.any { crossing ->
            crossing.higherBefore.pitch == Pitch.fromName("C5") &&
                crossing.lowerBefore.pitch == Pitch.fromName("E4") &&
                crossing.higherAfter.pitch == Pitch.fromName("E4") &&
                crossing.lowerAfter.pitch == Pitch.fromName("C4")
        })
    }

    @Test
    fun classifiesPairMotionKinds() {
        val hold = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4")), listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )
        val oblique = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4")), listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )
        val contrary = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4")), listOf(Pitch.fromName("D4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )
        val similar = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4")), listOf(Pitch.fromName("F4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )
        val parallel = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("G4")), listOf(Pitch.fromName("A4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        assertTrue(hasSopranoAltoMotion(hold, VoicePairMotionKind.HOLD))
        assertTrue(hasSopranoAltoMotion(oblique, VoicePairMotionKind.OBLIQUE))
        assertTrue(hasSopranoAltoMotion(contrary, VoicePairMotionKind.CONTRARY))
        assertTrue(hasSopranoAltoMotion(similar, VoicePairMotionKind.SIMILAR))
        assertTrue(hasSopranoAltoMotion(parallel, VoicePairMotionKind.PARALLEL))
    }

    @Test
    fun pairMotionsExposeCompoundPerfectIntervals() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("D3"))),
            sopranoDuration = Duration.QUARTER,
            bassDuration = Duration.QUARTER,
        )

        val sopranoBass = VoiceLeadingAnalysis.pairMotions(fixed).first { motion ->
            motion.firstBefore.voice.role == FixedVoiceRole.SOPRANO &&
                motion.secondBefore.voice.role == FixedVoiceRole.BASS
        }

        assertEquals(VoicePairMotionKind.PARALLEL, sopranoBass.kind)
        assertEquals(15, sopranoBass.afterInterval.number)
        assertEquals(1, sopranoBass.afterInterval.simpleNumber)
    }

    private fun fixedVoiceScore(
        sopranoPitches: List<List<Pitch>>,
        altoPitches: List<List<Pitch>>,
        tenorPitches: List<List<Pitch>>,
        bassPitches: List<List<Pitch>>,
        sopranoDuration: Duration = Duration.QUARTER,
        altoDuration: Duration = Duration.QUARTER,
        bassDuration: Duration = Duration.HALF,
    ): FixedVoiceScore {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = sopranoPitches,
                altoPitches = altoPitches,
                tenorPitches = tenorPitches,
                bassPitches = bassPitches,
                sopranoDuration = sopranoDuration,
                altoDuration = altoDuration,
                bassDuration = bassDuration,
            )
        )
        return FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
    }

    private fun hasSopranoAltoMotion(fixed: FixedVoiceScore, kind: VoicePairMotionKind): Boolean =
        VoiceLeadingAnalysis.pairMotions(fixed).any { motion ->
            motion.kind == kind &&
                motion.firstBefore.voice.role == FixedVoiceRole.SOPRANO &&
                motion.secondBefore.voice.role == FixedVoiceRole.ALTO
        }

    private fun firstTime() = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO)
}
