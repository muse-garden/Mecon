package com.mecon.renderer.snapshot

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [RenderEngine.renderStreaming] — the Phase 3 per-page progressive render.
 *
 * Guards four properties:
 *  1. [onPage] is called exactly once per page.
 *  2. The anchor page (containing [ComputeChangeSet.affectedMeasures]) is always emitted first.
 *  3. The union of per-page elements in the final [RenderResult.pages] equals a full render
 *     (command-multiset equivalent).
 *  4. For the splice path (non-reflow, bounded edit after a priming render), [onPage] is NOT
 *     called; the result is command-multiset equivalent to [RenderEngine.renderIncremental].
 */
class RenderStreamingTest {

    // Several measures per system, several systems per page.
    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(400f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(420f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    // Short page height → at least 3 pages for a 40-measure score.
    private val multiPage = multiSystem.copy(pageContentHeight = StaffSpace(40f), paperHeight = StaffSpace(50f))

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, measure: Int): RuntimeScore {
        val tc = TimeCode.of(measure, Fraction(0, 4))
        val pe = RuntimePitchEvent(EventId("p-$tag"), tc, listOf(Pitch.C4))
        val ve = RuntimeVoiceEvent(EventId(tag), tc, pe, Duration.HALF)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun buildBase(measures: Int): RuntimeScore {
        var base = emptyScore()
        for (m in 1..measures) base = base.addNote("n_$m", m)
        return base
    }

    private fun RuntimeScore.addNoteTo(
        voiceTrackId: TrackId, pitchTrackId: TrackId,
        tag: String, onset: TimeCode, pitch: Pitch, duration: Duration,
        slurStarts: Int = 0, slurEnds: Int = 0,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration, slurStarts = slurStarts, slurEnds = slurEnds)
        return addPitchEvent(pitchTrackId, pe).addVoiceEvent(voiceTrackId, ve)
    }

    /**
     * A titled two-staff grand staff (PIANO_GRAND ⇒ BRACE header + connected barlines), each staff carrying
     * auto-beamed eighths and an in-measure slur. Exercises the streaming full path against the features the
     * simple single-staff base does not: the page-anchored title block (must appear only on page 0, not on
     * every page's render), multi-staff connected barlines (whose "system-start" detection must survive
     * per-page `systemFilter`), beams, and slurs.
     */
    private fun buildTitledGrandStaff(measures: Int, title: String): RuntimeScore {
        var rt = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(title = title, layout = StaffLayoutPreset.PIANO_GRAND, measureCount = measures))
        )
        for ((si, staff) in rt.orderedStaffs().withIndex()) {
            val vt = staff.voiceTracks.first()
            val vtId = vt.id; val ptId = vt.pitchTrack.id
            val prefix = if (si == 0) "n" else "b"
            for (m in 1..measures) {
                rt = rt
                    .addNoteTo(vtId, ptId, "${prefix}_${m}_0", TimeCode.of(m, Fraction(0, 8)), Pitch.C4, Duration.EIGHTH, slurStarts = 1)
                    .addNoteTo(vtId, ptId, "${prefix}_${m}_1", TimeCode.of(m, Fraction(1, 8)), Pitch.E4, Duration.EIGHTH, slurEnds = 1)
                    .addNoteTo(vtId, ptId, "${prefix}_${m}_2", TimeCode.of(m, Fraction(1, 4)), Pitch.G4, Duration.QUARTER)
                    .addNoteTo(vtId, ptId, "${prefix}_${m}_3", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
            }
        }
        return rt
    }

    @Test
    fun multiStaffBarlinesRegisterTheirSelectableSection() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildTitledGrandStaff(4, "Barline selection")
            val computed = computeScore(runtime)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
            val barline = computed.barlines.first { it.measureNumber > 0 }
            val elementIds = result.sectionIndex.elementsForSectionId(BarlineSection(barline).id).elementIds

            assertTrue(elementIds.isNotEmpty(), "multi-staff barline must own a selectable section")
            assertTrue(
                result.elements.any { it.id in elementIds && it.type == RenderElementType.BARLINE },
                "the barline section must resolve to the visible barline element",
            )
        }
    }

    /**
     * [onPage] is called once per page; command union of all pages in the returned result ≡ full render.
     */
    @Test
    fun streamingCallsOnPagePerPageAndResultMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(40)
            val computed = computeScore(runtime)

            val fullEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val fullResult = fullEngine.render(runtime, pageGeometry = multiPage)
            val pageCount = fullResult.pages.size
            assertTrue(pageCount >= 2, "Expected ≥2 pages for 40-measure multi-page score; got $pageCount")

            val receivedPages = mutableListOf<RenderPage>()
            val streamEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            // No prior render → no cache → full render path → streaming engages.
            val streamResult = streamEngine.renderStreaming(
                computed = computed,
                pageGeometry = multiPage,
                onPage = { _, page -> receivedPages.add(page) },
            )

            // onPage called exactly once per page.
            assertEquals(pageCount, receivedPages.size, "Expected $pageCount onPage calls, got ${receivedPages.size}")

            // All page indices covered.
            val receivedIndices = receivedPages.map { it.pageIndex }.toSet()
            assertEquals((0 until pageCount).toSet(), receivedIndices, "Missing pages in streaming output")

            // Final result ≡ full render.
            assertCommandMultisetEquivalent(fullResult, streamResult)
        }
    }

    /**
     * Streaming full path on a real score: a titled grand staff (BRACE + connected barlines + beams +
     * slurs) spanning multiple pages must stream once per page and the union must equal a full render.
     *
     * This guards the two features the [buildBase] union test cannot: (1) the page-anchored title block
     * is emitted only on page 0's render (not duplicated across pages), and (2) multi-staff connected
     * barlines are drawn identically under per-page `systemFilter` — the "system-start" barline (drawn
     * without staff connectivity) must be detected by time, not by a per-render running flag that would
     * mis-mark the first barline of every page ≥1.
     */
    @Test
    fun streamingTitledGrandStaffMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildTitledGrandStaff(40, "Streaming Symphony")
            val computed = computeScore(runtime)

            val fullResult = RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime, pageGeometry = multiPage)
            assertTrue(fullResult.pages.size >= 2, "Expected ≥2 pages; got ${fullResult.pages.size}")
            assertTrue(
                fullResult.elements.any {
                    it.type == RenderElementType.SYSTEM_BRACE || it.type == RenderElementType.SYSTEM_BRACKET
                },
                "grand staff must render a BRACE/BRACKET header",
            )
            assertTrue(
                fullResult.elements.any { it.type == RenderElementType.TEXT_ANNOTATION },
                "titled score must render a title text element",
            )
            assertTrue(fullResult.elements.any { it.type == RenderElementType.BEAM }, "must render beams")

            val receivedPages = mutableListOf<RenderPage>()
            val streamResult = RenderEngine(RenderLayoutConfig.DEFAULT).renderStreaming(
                computed = computed,
                pageGeometry = multiPage,
                onPage = { _, page -> receivedPages.add(page) },
            )

            assertEquals(fullResult.pages.size, receivedPages.size, "one onPage call per page")
            assertEquals(
                (0 until fullResult.pages.size).toSet(), receivedPages.map { it.pageIndex }.toSet(),
                "all pages covered",
            )
            // Title block appears the same number of times as in a full render — not multiplied per page
            // (it is page-anchored to page 0, and each page render is gated to system 0 for it).
            assertEquals(
                fullResult.elements.count { it.type == RenderElementType.TEXT_ANNOTATION },
                streamResult.elements.count { it.type == RenderElementType.TEXT_ANNOTATION },
                "title block must not be duplicated across page renders",
            )
            assertCommandMultisetEquivalent(fullResult, streamResult)
        }
    }

    /**
     * When [ComputeChangeSet.affectedMeasures] points to a late measure, the page containing that
     * measure is emitted first (before page 0).
     */
    @Test
    fun anchorPageEmittedFirst() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(40)
            val computed = computeScore(runtime)

            // No prior render → full path. changeSet.forRange(38..39) flags the last two measures.
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val receivedOrder = mutableListOf<Int>()
            val streamResult = engine.renderStreaming(
                computed = computed,
                changeSet = ComputeChangeSet.forRange(38..39),
                pageGeometry = multiPage,
                onPage = { pageIndex, _ -> receivedOrder.add(pageIndex) },
            )

            if (receivedOrder.isEmpty()) return@with // edge-case: single-page score (shouldn't happen with 40 measures)

            val anchorPageIndex = receivedOrder.first()
            val lastPageIndex = streamResult.pages.last().pageIndex
            assertTrue(lastPageIndex >= 1, "Score should span ≥2 pages (lastPage ≥ 1); got lastPage=$lastPageIndex")

            // Measures 38-39 are on the last page; anchor must be that page (not page 0).
            assertEquals(lastPageIndex, anchorPageIndex,
                "Anchor page should be the last page (contains measures 38-39), got $anchorPageIndex")

            // All pages covered in streaming output.
            assertEquals(streamResult.pages.size, receivedOrder.size,
                "Not all pages were streamed: got ${receivedOrder.size}, expected ${streamResult.pages.size}")
        }
    }

    /**
     * For a non-reflow (splice) edit on a paginated score, [onPage] must NOT be called —
     * the splice is already bounded to the edit window.
     */
    @Test
    fun splicePathDoesNotCallOnPage() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(40)
            val computed = computeScore(runtime)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(runtime, pageGeometry = multiSystem) // prime cache

            // ComputeChangeSet.forRange triggers the incremental layout path when the cache exists.
            var onPageCalled = false
            val streamResult = engine.renderStreaming(
                computed = computed,
                changeSet = ComputeChangeSet.forRange(5..5),
                pageGeometry = multiSystem,
                onPage = { _, _ -> onPageCalled = true },
            )

            val refResult = run {
                val e2 = RenderEngine(RenderLayoutConfig.DEFAULT)
                e2.render(runtime, pageGeometry = multiSystem)
                e2.renderIncremental(computed, ComputeChangeSet.forRange(5..5), pageGeometry = multiSystem)
            }

            assertFalse(onPageCalled, "onPage must NOT be called on the splice path")
            assertCommandMultisetEquivalent(refResult, streamResult)
        }
    }
}
