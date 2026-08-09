package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.computeScore
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.geometry.IntervalAttachmentGeometry
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check that test-scores/22 resolves an 8va and an 8vb bracket whose
 * dashed line spans from the first note to the last note (terminating before the
 * end event's trailing barline). The precise "skip barline-only slot" logic is
 * unit-tested in [UnifiedTimeSlotMapTest]; this verifies the wiring through the
 * real layout pipeline. Requires the Bravura font assets bundled with the desktop app.
 */
class OctaveShiftLayoutDebugTest {

    private fun loadFontOrNull(): BravuraFont? {
        val base = File("../apps/desktop/src/main/resources/bravura")
        val meta = File(base, "bravuraMetadata.json")
        val names = File(base, "glyphnames.json")
        if (!meta.exists() || !names.exists()) return null
        return BravuraFont.fromJson(meta.readText(), names.readText())
    }

    @Test
    fun octaveShiftBracketsResolveWithSaneSpan() {
        val font = loadFontOrNull() ?: return // skip if font assets unavailable

        val storage = ScoreSerializer.fromYaml(
            File("../test-scores/22_octave_dynamics_combined.mscore.yaml").readText()
        )
        val runtime = RuntimeScore.fromStorage(storage)
        val computed = computeScore(runtime)

        val result = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(computed, runtime)
        }

        val shifts = result.placedAttachments.filter { it.attachment is ComputedOctaveShift }
        assertEquals(2, shifts.size, "expected one 8va and one 8vb bracket")

        for (placed in shifts) {
            val shift = placed.attachment as ComputedOctaveShift
            val geo = placed.geometries.filterIsInstance<IntervalAttachmentGeometry>().single()
            assertTrue(
                geo.endX > geo.startX,
                "${shift.shiftType}: endX ${geo.endX} should be right of startX ${geo.startX}",
            )
        }
    }
}
