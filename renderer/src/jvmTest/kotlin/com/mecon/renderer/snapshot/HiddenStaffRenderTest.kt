package com.mecon.renderer.snapshot

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the "hidden staff strands its glyphs at the global Y" bug: when a staff is fully hidden
 * on a later system it collapses out of that system, but its notes/rests were still being rendered — and
 * because the render passes fell back to the flat (pre-pagination) [staffLayouts] Y when a staff was
 * absent from its system, those glyphs landed at the top of the score (above the title on page 1) instead
 * of being skipped. See [com.mecon.renderer.layout.UnifiedLayoutResult.staffForSystem].
 */
class HiddenStaffRenderTest {

    // Narrow lines force many systems (line breaks) while the tall page keeps them on one page.
    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(50f),
        pageContentHeight = StaffSpace(4000f),
        paperWidth = StaffSpace(60f),
        paperHeight = StaffSpace(4200f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private fun grandStaff(measures: Int): RuntimeScore {
        var rt = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(title = "My Title", layout = StaffLayoutPreset.PIANO_GRAND, measureCount = measures))
        )
        // Notes on the TOP staff every measure, so the hidden staff carries real noteheads to strand.
        val top = rt.orderedStaffs().first()
        val vt = top.voiceTracks.first()
        for (m in 1..measures) {
            val pe = RuntimePitchEvent(EventId("p_$m"), TimeCode.of(m, Fraction(0, 4)), listOf(Pitch.C4))
            val ve = RuntimeVoiceEvent(EventId("n_$m"), TimeCode.of(m, Fraction(0, 4)), pe, Duration.WHOLE)
            rt = rt.addPitchEvent(vt.pitchTrack.id, pe).addVoiceEvent(vt.id, ve)
        }
        return rt
    }

    private fun hideTopStaff(rt: RuntimeScore, range: MeasureRange): RuntimeScore {
        val topId = rt.orderedStaffs().first().id
        val updated = rt.staffTracks.mapValues { (id, s) ->
            if (id == topId) s.copy(hiddenRanges = listOf(range)) else s
        }
        return rt.replaceTracks(staffTracks = updated)
    }

    @Test
    fun hiddenStaffOnLaterSystemDoesNotStrandGlyphsAtGlobalY() {
        val font = loadFont() ?: return
        with(font) {
            val measures = 24
            // Hide the (noted) top staff from measure 9 to the end — visible on early lines, collapsed later.
            val runtime = hideTopStaff(grandStaff(measures), MeasureRange(9, measures))
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime, pageGeometry = multiSystem)

            assertTrue(result.lastSystem > 0, "score must break into multiple systems")

            // Systems where staff 0 collapsed out (it has no staff region there).
            val collapsed = result.spatialIndex.allSystems()
                .filter { sys -> sys.staffRegions.none { it.staffIndex == 0 } }
                .map { it.systemIndex }
                .toSet()
            assertTrue(collapsed.isNotEmpty(), "the top staff must collapse on at least one later system")

            // Regression: no hidden-staff glyph may be rendered on a collapsed system.
            val stranded = result.elements.filter {
                it.staffIndex == 0 && it.systemIndex in collapsed &&
                    (it.type == RenderElementType.NOTEHEAD || it.type == RenderElementType.REST)
            }
            assertTrue(
                stranded.isEmpty(),
                "hidden staff glyphs leaked onto collapsed systems: ${stranded.map { it.type to it.systemIndex }}",
            )

            // Sanity: the top staff still renders on the early (visible) systems.
            val visibleTop = result.elements.any {
                it.staffIndex == 0 && it.systemIndex !in collapsed && it.type == RenderElementType.NOTEHEAD
            }
            assertTrue(visibleTop, "top staff must still render where it is visible")
        }
    }
}
