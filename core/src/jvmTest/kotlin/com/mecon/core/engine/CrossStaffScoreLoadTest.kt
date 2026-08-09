package com.mecon.core.engine

import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.serializer.ScoreSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loads test-scores/20_cross_staff.mscore.yaml and verifies the cross-staff computed
 * effects end-to-end:
 *  - a note borrowed onto the treble staff is positioned with the TREBLE clef;
 *  - a tie crossing staves degrades to let-ring.
 */
class CrossStaffScoreLoadTest {

    private fun locateScore(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "test-scores/20_cross_staff.mscore.yaml")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("Could not locate test-scores/20_cross_staff.mscore.yaml from ${System.getProperty("user.dir")}")
    }

    @Test
    fun borrowedNoteUsesTargetClefAndCrossStaffTieIsLetRing() {
        val storage = ScoreSerializer.fromYaml(locateScore().readText())
        val runtime = RuntimeScore.fromStorage(storage)
        val computed = computeScore(runtime)

        // ve-lh-4 is C5 borrowed UP onto the treble staff (crossStaffOffset: -1).
        // C5 has diatonicSteps 7. Treble middle line (B4) = 6 -> staffPosition 1.
        // If the home BASS clef were (wrongly) used it would be 7 - (-6) = 13.
        val borrowed = computed.computedEvents[EventId("ve-lh-4")]
        assertNotNull(borrowed, "ve-lh-4 must be computed")
        assertEquals(1, borrowed.pitchData[0].staffPosition,
            "borrowed note should be positioned with the treble (target) clef")

        // ve-lh-7 (B3 borrowed to treble) ties to ve-lh-8 (B3 on bass) -> different
        // rendered staves -> let-ring.
        val tied = computed.computedEvents[EventId("ve-lh-7")]
        assertNotNull(tied, "ve-lh-7 must be computed")
        val tie = tied.pitchData[0].tieTarget
        assertNotNull(tie, "ve-lh-7 should carry a tie target")
        assertTrue(tie.isLetRing, "cross-staff tie must be let-ring")
    }
}
