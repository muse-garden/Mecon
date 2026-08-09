package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fully-hidden staves collapse out of a system's [SystemLayout.staffLayouts]; a merged dashed
 * [HiddenStaffMarker] fills the gap. Partially-hidden lines keep both staves (grey-out is a desktop
 * view concern, not a layout one). Uses a grand staff (2 staves) so a collapse is observable.
 */
class HiddenStaffLayoutTest {

    private fun loadFontOrNull(): BravuraFont? {
        val base = File("../apps/desktop/src/main/resources/bravura")
        val meta = File(base, "bravuraMetadata.json")
        val names = File(base, "glyphnames.json")
        if (!meta.exists() || !names.exists()) return null
        return BravuraFont.fromJson(meta.readText(), names.readText())
    }

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
            StorageScore.create(StorageScore.CreationOptions(title = "", layout = StaffLayoutPreset.PIANO_GRAND, measureCount = measures))
        )
        // Notes on the top staff every measure so line-breaking has real widths to work with.
        val top = rt.orderedStaffs().first()
        val vt = top.voiceTracks.first()
        for (m in 1..measures) {
            val pe = RuntimePitchEvent(EventId("p_$m"), TimeCode.of(m, Fraction(0, 4)), listOf(Pitch.C4))
            val ve = RuntimeVoiceEvent(EventId("n_$m"), TimeCode.of(m, Fraction(0, 4)), pe, Duration.WHOLE)
            rt = rt.addPitchEvent(vt.pitchTrack.id, pe).addVoiceEvent(vt.id, ve)
        }
        return rt
    }

    private fun hideBottomStaff(rt: RuntimeScore, range: MeasureRange): RuntimeScore {
        val bottomId = rt.orderedStaffs().last().id
        val updated = rt.staffTracks.mapValues { (id, s) ->
            if (id == bottomId) s.copy(hiddenRanges = listOf(range)) else s
        }
        return rt.replaceTracks(staffTracks = updated)
    }

    @Test
    fun fullyHiddenStaffCollapsesAndEmitsMergedMarker() {
        val font = loadFontOrNull() ?: return
        val measures = 24
        val base = hideBottomStaff(grandStaff(measures), MeasureRange(9, measures))

        val result = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(base), base, pageGeometry = multiSystem
            )
        }

        assertTrue(result.systems.size > 1, "score must break into multiple systems")

        fun notationCount(sys: SystemLayout) = sys.staffLayouts.count { it.kind == StaffKind.NOTATION }

        // A system entirely inside the hidden range keeps only the top staff.
        val collapsedSystem = result.systems.firstOrNull { sys ->
            sys.measureRange.first >= 9 && notationCount(sys) == 1
        }
        assertTrue(collapsedSystem != null, "a fully-hidden line must collapse to a single notation staff")

        // ...and carries a dashed marker.
        val markers = result.postLayoutMarkers.filterIsInstance<HiddenStaffMarker>()
        assertTrue(
            markers.any { it.systemIndex == collapsedSystem!!.systemIndex },
            "the collapsed line must carry a HiddenStaffMarker",
        )

        // An early system (before the hidden range) still shows both staves and has no marker.
        val fullSystem = result.systems.firstOrNull { it.measureRange.last < 9 }
        assertTrue(fullSystem != null && notationCount(fullSystem) == 2, "visible lines keep both staves")
        assertTrue(
            markers.none { it.systemIndex == fullSystem!!.systemIndex },
            "a fully-visible line must not carry a hidden-staff marker",
        )
    }

    @Test
    fun partiallyHiddenLineKeepsBothStavesAndNoMarker() {
        val font = loadFontOrNull() ?: return
        val measures = 24
        // Hide only measure 1 on the bottom staff: no line is fully hidden.
        val base = hideBottomStaff(grandStaff(measures), MeasureRange(1, 1))

        val result = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(base), base, pageGeometry = multiSystem
            )
        }
        val system = result.systems.first { 1 in it.measureRange }
        assertTrue(
            system.staffLayouts.count { it.kind == StaffKind.NOTATION } == 2,
            "a partially-hidden line keeps both staves (grey-out is a desktop concern)",
        )
        assertTrue(
            result.postLayoutMarkers.filterIsInstance<HiddenStaffMarker>().isEmpty(),
            "no fully-hidden line ⇒ no hidden-staff markers",
        )
    }
}
