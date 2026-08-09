package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.spatial.ScoreHittableElement
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the incremental **index** assemble (`SectionIndex.spliceWindow` + `ScoreSpatialAdapter
 * .buildIndexIncremental`, wired through `RenderEngine.assembleResultIncremental`) produces hit-testing
 * and selection behaviour identical to a full-render rebuild.
 *
 * The spliced render reuses cached section / spatial entries for the prefix + tail and patches only the
 * edit window. Render-element ids are ephemeral and differ from a full render, so equivalence is checked
 * id-independently: every spatial-index hittable is keyed by (type, cell-relative bbox, section ids), and
 * each event's section-index resolution is keyed by the element *types* it maps to.
 */
class RenderIndexSpliceEquivalenceTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun spatialKeys(r: RenderResult): List<String> {
        val b = r.bounds
        val whole = AbsoluteRect(
            origin = AbsolutePoint(Pixels(b.origin.x.value - 1000f), Pixels(b.origin.y.value - 1000f)),
            width = Pixels(b.width.value + 2000f),
            height = Pixels(b.height.value + 2000f),
        )
        fun q(f: Float) = (f * 100f).roundToInt()
        return r.hitTestRegion(whole).map { e: ScoreHittableElement ->
            val bb = e.boundingBox()
            "${e.type}|${q(bb.left.value)},${q(bb.top.value)},${q(bb.width.value)},${q(bb.height.value)}" +
                "|${e.sections.map { it.sectionId }.sorted()}"
        }.sorted()
    }

    private fun eventTypeMap(r: RenderResult): Map<RenderElementId, com.mecon.renderer.render.RenderElementType> =
        r.elements.associate { it.id to it.type }

    @Test
    fun splicedIndicesMatchFullRender() {
        val font = loadFont() ?: return
        val measures = 40
        var base = emptyScore()
        val pitches = listOf(Pitch.C4, Pitch.E4)
        for (m in 1..measures) {
            base = base
                .addNote("n_${m}_0", TimeCode.of(m, Fraction(0, 4)), pitches[0], Duration.HALF)
                .addNote("n_${m}_1", TimeCode.of(m, Fraction(2, 4)), pitches[1], Duration.HALF)
        }

        with(font) {
            val editTag = "n_20_1"
            val ve = base.voiceTracks.getValue(base.vtId()).events.toList().first { it.id == EventId(editTag) }
            val edited = base.editPitch(editTag, Pitch.G4) // same-width pitch edit → splice
            val previous = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, TimeRange(ve.onset, TimeCode.of(20, Fraction(3, 4))))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base)
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)
            assertTrue(engine.lastRenderWasSpliced(), "expected the element-level splice to engage")
            assertTrue(engine.lastAssembleWasIncremental(), "expected the incremental index assemble to engage")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)

            // 1. Spatial index: identical multiset of hittables (type + cell-relative bbox + section ids).
            assertEquals(spatialKeys(fullRender), spatialKeys(incRender), "spatial index hittables differ")

            // 2. Section index: every event resolves to the same element types.
            val fTypes = eventTypeMap(fullRender)
            val iTypes = eventTypeMap(incRender)
            for (e in edited.voiceTracks.getValue(edited.vtId()).events) {
                val full = fullRender.sectionIndex.elementsForEvent(e.id).elementIds.mapNotNull { fTypes[it] }.sorted()
                val incl = incRender.sectionIndex.elementsForEvent(e.id).elementIds.mapNotNull { iTypes[it] }.sorted()
                assertEquals(full, incl, "section index resolution differs for event ${e.id}")
            }
        }
    }
}
