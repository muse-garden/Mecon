package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.toStorage
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.engine.edit.BarlineEditEngine
import com.mecon.core.engine.edit.RepeatStructureEditEngine
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.UnifiedLayoutComputer
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.renderer.render.IncrementalRenderPath
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Golden-rule tests for **incremental line-breaking / pagination** ("增量分行分页",
 * docs/renderer/incremental-rendering.md): the paginated incremental render must be pixel-identical (order-independent,
 * sub-pixel) to a full render of the edited score, for every edit.
 *
 * The fast path reuses the cached line partition and re-justifies only the affected line(s)
 * ([com.mecon.renderer.layout.SystemBreaker.breakIntoSystemsIncremental]); when an edit would reflow the
 * line breaks (or hits a gated feature) it falls back to a full solve. A plain "translate + re-run the
 * full breaker" is *incorrect* here — sub-pixel drift in the translated coordinates flips the discrete
 * greedy line-break decision — so these tests guard exactly that.
 */
class PaginatedIncrementalLayoutTest {

    // Blank title keeps the common fixtures focused on notation; title-block parity has its own test.
    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    /** Add a note to an explicit voice/pitch track (for multi-staff scores), optionally opening/closing a slur. */
    private fun RuntimeScore.addNoteTo(
        voiceTrackId: TrackId, pitchTrackId: TrackId,
        tag: String, onset: TimeCode, pitch: Pitch, duration: Duration,
        slurStarts: Int = 0, slurEnds: Int = 0,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration, slurStarts = slurStarts, slurEnds = slurEnds)
        return addPitchEvent(pitchTrackId, pe).addVoiceEvent(voiceTrackId, ve)
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    /** Several measures per system, several systems; an accidental rarely overflows a line. */
    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(400f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(420f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    /** Short page height so the systems stack across more than one page. */
    private val multiPage = multiSystem.copy(pageContentHeight = StaffSpace(40f), paperHeight = StaffSpace(50f))

    private val FSHARP4 = Pitch(3, 1) // F#4 — adds a sharp accidental, widening its measure.

    private fun buildBase(measures: Int): RuntimeScore {
        var base = emptyScore()
        for (m in 1..measures) {
            base = base
                .addNote("n_${m}_0", TimeCode.of(m, Fraction(0, 4)), Pitch.C4, Duration.HALF)
                .addNote("n_${m}_1", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
        }
        return base
    }

    /**
     * Sprinkle staff attachments across the score — dynamics on measures spanning several systems plus a
     * couple of hairpins (one in-line, one crossing a system break). Roundtrips through storage so both the
     * staffTracks map and staffGroups are rebuilt consistently. [editPitch] preserves these (it only touches
     * the voice / pitch tracks), so base and edited carry the same attachments.
     */
    private fun RuntimeScore.withAttachments(): RuntimeScore {
        val storage = toStorage()
        val staffId = storage.staffTracks.keys.first()
        val staff = storage.staffTracks.getValue(staffId)
        val dynamics = listOf(3, 8, 14, 22, 30, 38).map { m ->
            StorageDynamicMark.create(TimeCode.of(m, Fraction(0, 4)), DynamicLevel.MF)
        }
        val hairpins = listOf(
            // In-line hairpin (start + end in the same measure region of one system).
            StorageHairpin.create(TimeCode.of(10, Fraction(0, 4)), TimeCode.of(10, Fraction(2, 4)), HairpinType.CRESCENDO),
            // Hairpin spanning many measures → very likely crosses a system break.
            StorageHairpin.create(TimeCode.of(24, Fraction(0, 4)), TimeCode.of(27, Fraction(2, 4)), HairpinType.DIMINUENDO),
        )
        val newStaff = staff.copy(attachments = staff.attachments + dynamics + hairpins)
        return RuntimeScore.fromStorage(storage.copy(staffTracks = storage.staffTracks + (staffId to newStaff)))
    }

    /** Add the paired first/second ending used by the repeat-structure editor. */
    private fun RuntimeScore.withVoltaPair(): RuntimeScore {
        val repeated = BarlineEditEngine.set(this, 3, BarlineType.REPEAT_RIGHT)?.score
            ?: error("failed to add repeat barline")
        return RepeatStructureEditEngine.toggleVoltaPair(repeated, boundaryMeasure = 0)?.score
            ?: error("failed to add volta pair")
    }

    private fun RuntimeScore.withNavigationMark(measure: Int): RuntimeScore {
        val storage = toStorage()
        return RuntimeScore.fromStorage(storage.copy(
            measures = storage.measures.map { item ->
                if (item.number == measure) {
                    item.copy(navigationMarks = item.navigationMarks + NavigationMark.SEGNO)
                } else item
            },
        ))
    }

    /**
     * Each measure: two beamed eighths (slurred over) + a quarter + a half. Auto-beaming groups the eighths
     * and the slur opens/closes within the measure — so the splice must reuse / regenerate BEAM and SLUR
     * elements (single-staff). The edit target `n_${m}_1` (the slurred second eighth) keeps its width.
     */
    private fun buildBeamedBase(measures: Int): RuntimeScore {
        var base = emptyScore()
        val vt = base.vtId(); val pt = base.ptId()
        for (m in 1..measures) {
            base = base
                .addNoteTo(vt, pt, "n_${m}_0", TimeCode.of(m, Fraction(0, 8)), Pitch.C4, Duration.EIGHTH, slurStarts = 1)
                .addNoteTo(vt, pt, "n_${m}_1", TimeCode.of(m, Fraction(1, 8)), Pitch.E4, Duration.EIGHTH, slurEnds = 1)
                .addNoteTo(vt, pt, "n_${m}_2", TimeCode.of(m, Fraction(1, 4)), Pitch.G4, Duration.QUARTER)
                .addNoteTo(vt, pt, "n_${m}_3", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
        }
        return base
    }

    /**
     * A two-staff grand staff (PIANO_GRAND ⇒ a BRACE header bracket), each staff carrying beamed eighths and
     * an in-measure slur. The treble staff's voice track is `voiceTracks.first()`, so its notes are tagged
     * `n_${m}_*` and the shared [editAndRender] (which edits `n_${editMeasure}_1`) drives a first-staff edit.
     * Exercises: relaxed multi-staff gate, per-system Δy across 2 staves, header brace regen, beam/slur splice.
     */
    private fun grandStaffBase(measures: Int): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            title = "", layout = StaffLayoutPreset.PIANO_GRAND, measureCount = measures
        ))
        var rt = RuntimeScore.fromStorage(storage)
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

    /** Give the score a non-blank title so a paginated title block (page-anchored TEXT_ANNOTATION) is laid out. */
    private fun RuntimeScore.withTitle(title: String): RuntimeScore {
        val storage = toStorage()
        return RuntimeScore.fromStorage(storage.copy(metadata = storage.metadata.copy(title = title)))
    }

    /** Incremental render result + which paths engaged, paired with a fresh full render for comparison. */
    private data class Outcome(
        val inc: RenderResult,
        val path: IncrementalRenderPath,
        val spliced: Boolean,
        val assembleIncremental: Boolean,
        val full: RenderResult,
    )

    /**
     * Render [base] then incrementally render the [editMeasure] pitch edit (paginated [geo]); return the
     * incremental result, which incremental paths engaged, and a fresh full render of the edited score.
     */
    context(BravuraFont)
    private fun editAndRender(
        base: RuntimeScore, editMeasure: Int, newPitch: Pitch, geo: PageGeometry,
    ): Outcome {
        val tag = "n_${editMeasure}_1"
        val ve = base.voiceTracks.getValue(base.vtId()).events.toList().first { it.id == EventId(tag) }
        val edited = base.editPitch(tag, newPitch)
        val previous = computeScore(base)
        val inc = computeScoreIncremental(
            previous, edited, TimeRange(ve.onset, TimeCode.of(editMeasure, Fraction(3, 4)))
        )
        val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
        engine.render(base, pageGeometry = geo)
        val incRender = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = geo)
        val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = geo)
        return Outcome(
            incRender, engine.lastIncrementalRenderPath(),
            engine.lastRenderWasSpliced(), engine.lastAssembleWasIncremental(), full
        )
    }

    @Test
    fun sameWidthEditUsesFastPathAndMatches() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "same-width edit must take the fast path")
            assertTrue(o.spliced, "paginated edit must engage the element-level splice")
            assertTrue(o.assembleIncremental, "paginated splice must use the incremental index assemble")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    @Test
    fun repeatedPaginatedSplicesDoNotAccumulateStructuralElements() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            var runtime = base
            var computed = computeScore(runtime)
            engine.render(runtime, pageGeometry = multiSystem)

            for (pitch in listOf(Pitch.G4, Pitch.A4, Pitch.B4)) {
                val event = runtime.voiceTracks.getValue(runtime.vtId()).events.toList()
                    .first { it.id == EventId("n_20_1") }
                val edited = runtime.editPitch("n_20_1", pitch)
                val inc = computeScoreIncremental(
                    computed, edited, TimeRange(event.onset, TimeCode.of(20, Fraction(3, 4)))
                )
                val actual = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiSystem)
                val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiSystem)
                assertEquals(full.elements.size, actual.elements.size, "splice must not accumulate structure")
                assertCommandMultisetEquivalent(full, actual)
                runtime = edited
                computed = inc.computed
            }
        }
    }

    @Test
    fun repeatedLaterPageSplicesDoNotRetainStaleNotation() {
        val font = loadFont() ?: return
        val base = buildBeamedBase(40).withAttachments()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            var runtime = base
            var computed = computeScore(runtime)
            val initial = engine.render(runtime, pageGeometry = multiPage)
            val editMeasure = 32
            assertTrue(initial.pages.size > 1, "geometry must produce later pages")
            assertTrue(
                initial.elements.filter { it.measureNumber != null }.all { it.systemIndex != null },
                "measure-anchored full-render elements must carry authoritative system identity",
            )
            assertTrue(
                initial.pages.any { page ->
                    page.pageIndex > 0 && page.elements.any { it.measureNumber == editMeasure }
                },
                "edit target must be rendered after the first page",
            )

            for (pitch in listOf(Pitch.G4, Pitch.A4, Pitch.B4)) {
                val event = runtime.voiceTracks.getValue(runtime.vtId()).events.toList()
                    .first { it.id == EventId("n_${editMeasure}_1") }
                val edited = runtime.editPitch("n_${editMeasure}_1", pitch)
                val inc = computeScoreIncremental(
                    computed, edited, TimeRange(event.onset, TimeCode.of(editMeasure, Fraction(3, 4)))
                )
                val actual = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiPage)
                val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiPage)
                assertTrue(engine.lastRenderWasSpliced(), "later-page edit must engage the splice")
                assertTrue(
                    actual.elements.filter { it.measureNumber != null }.all { it.systemIndex != null },
                    "regenerated splice elements must retain system identity for the next frame",
                )
                assertEquals(full.elements.size, actual.elements.size, "later-page splice must not retain stale notation")
                assertCommandMultisetEquivalent(full, actual)
                runtime = edited
                computed = inc.computed
            }
        }
    }

    @Test
    fun widthChangingEditMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            // A higher/accidental note also changes the staff's vertical extent → per-system Δy ≠ 0; the
            // splice must shift the reused systems correctly. Path may be splice or (on reflow) full.
            val o = editAndRender(base, 20, FSHARP4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            // Live reflow intentionally keeps the already-computed proportional X instead of paying for
            // a second strict full solve. Compare geometrically rather than relying on sort pairing:
            // equal-looking staff lines in different systems can swap order under sub-pixel drift.
            assertCommandsWithinEps(o.full, o.inc)
        }
    }

    @Test
    fun reflowOrSingleSystemFallsBackToFullAndMatches() {
        val font = loadFont() ?: return
        // Few measures + wide line ⇒ one system ⇒ the incremental breaker bails (size < 2) ⇒ full fallback.
        val base = buildBase(4)
        val wide = multiSystem.copy(lineWidth = StaffSpace(400f), paperWidth = StaffSpace(420f))
        with(font) {
            val o = editAndRender(base, 2, FSHARP4, wide)
            assertEquals(0, o.inc.lastSystem, "score should fit on a single system")
            assertEquals(IncrementalRenderPath.FULL, o.path, "single-system paginated edit falls back to full")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    @Test
    fun multiPageEditMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val o = editAndRender(base, 18, Pitch.G4, multiPage)
            assertTrue(o.full.pages.size > 1, "geometry must stack systems across more than one page")
            assertTrue(o.spliced, "multi-page edit must engage the splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /**
     * Phase 1 (staged per-page render, docs/renderer/incremental-rendering.md): a same-width edit confined to one page
     * must **reuse the cached [RenderPage] instances by reference** for every unaffected page (so the
     * per-page Skia cache replays them), re-slice only the edited page, and still be pixel-identical to a
     * full render. Reference identity is the contract the desktop per-page Picture cache keys on.
     */
    @Test
    fun multiPageEditReusesUnaffectedPagesByReference() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val baseRender = engine.render(base, pageGeometry = multiPage)
            assertTrue(baseRender.pages.size > 1, "geometry must stack systems across more than one page")

            val editMeasure = 18
            val tag = "n_${editMeasure}_1"
            val ve = base.voiceTracks.getValue(base.vtId()).events.toList().first { it.id == EventId(tag) }
            val edited = base.editPitch(tag, Pitch.G4)
            val previous = computeScore(base)
            val inc = computeScoreIncremental(
                previous, edited, TimeRange(ve.onset, TimeCode.of(editMeasure, Fraction(3, 4)))
            )
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiPage)
            assertTrue(engine.lastRenderWasSpliced(), "multi-page edit must engage the splice")

            val baseByIndex = baseRender.pages.associateBy { it.pageIndex }
            val incByIndex = incRender.pages.associateBy { it.pageIndex }
            assertEquals(baseByIndex.keys, incByIndex.keys, "page set unchanged in a non-reflow edit")

            var reused = 0
            var changed = 0
            for ((idx, incPage) in incByIndex) {
                if (incPage.elements === baseByIndex.getValue(idx).elements) reused++ else changed++
            }
            assertTrue(changed >= 1, "the edited page must be re-sliced (new element list)")
            assertTrue(reused >= 1, "unaffected pages must be reused by reference")

            val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiPage)
            assertCommandMultisetEquivalent(full, incRender)
        }
    }

    /**
     * Phase 2 (cooperative cancellation, docs/renderer/incremental-rendering.md): a `renderIncremental` whose
     * cancellation probe trips mid-flight (after it has already begun mutating engine caches) must throw
     * [CancellationException] **and roll the caches back**, so the engine still displays the prior frame
     * and the *next* (uncancelled) render of the same edit is correct (lineage guard still trusts the
     * cached frame). This is the safety property the staged/streaming pipeline relies on.
     */
    @Test
    fun cancelledIncrementalRenderRollsBackCachesAndNextRenderMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val baseComputed = computeScore(base)
            engine.renderIncremental(baseComputed, pageGeometry = multiSystem) // establishes the cached frame
            assertSame(baseComputed, engine.lastRenderedComputed(), "engine should display the base frame")

            val editMeasure = 20
            val tag = "n_${editMeasure}_1"
            val ve = base.voiceTracks.getValue(base.vtId()).events.toList().first { it.id == EventId(tag) }
            val edited = base.editPitch(tag, Pitch.G4)
            val inc = computeScoreIncremental(
                baseComputed, edited, TimeRange(ve.onset, TimeCode.of(editMeasure, Fraction(3, 4)))
            )

            // Trip on the *second* checkpoint: the first (after computeLayout) passes, by which point
            // lastComputedScore has been advanced to the edited frame — so the bail must roll it back.
            var calls = 0
            val probe = { calls++ >= 1 }
            assertFailsWith<CancellationException> {
                engine.renderIncremental(inc.computed, inc.changeSet, baseComputed, pageGeometry = multiSystem, isCancelled = probe)
            }
            assertTrue(calls >= 2, "cancellation must trip after the first checkpoint (caches already touched)")
            assertSame(baseComputed, engine.lastRenderedComputed(), "cancelled render must roll back to the base frame")

            // The next render of the same edit (no cancellation) still trusts the cached base frame and matches full.
            val good = engine.renderIncremental(inc.computed, inc.changeSet, baseComputed, pageGeometry = multiSystem)
            assertSame(inc.computed, engine.lastRenderedComputed(), "successful render advances the cached frame")
            val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiSystem)
            assertCommandMultisetEquivalent(full, good)
        }
    }

    @Test
    fun sweepOfEditsAllMatchFull() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            var splicedCount = 0
            for (m in listOf(6, 11, 16, 21, 26, 31, 36)) {
                val o = editAndRender(base, m, FSHARP4, multiSystem)
                if (o.spliced) splicedCount++
                assertCommandsWithinEps(o.full, o.inc)
            }
            // The splice must engage for at least some mid-line edits (others may legitimately reflow and
            // fall back to full); parity above holds for both.
            assertTrue(splicedCount > 0, "expected the paginated splice to engage at least once")
        }
    }

    /**
     * Safety net for relaxing the [com.mecon.renderer.layout.SystemBreaker] attachments gate: a paginated,
     * multi-system score **with** dynamics / hairpins must still take the incremental layout path on a
     * same-width edit and stay pixel-identical to a full render. The break decision is purely horizontal;
     * attachment placement / re-tag / split / extent-folding run after it, identically in both paths.
     */
    @Test
    fun paginatedWithAttachmentsSameWidthEditIsIncrementalAndMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40).withAttachments()
        with(font) {
            // Edit a mid-line measure that carries no attachment of its own.
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertTrue(
                o.full.elements.any {
                    it.type == com.mecon.renderer.render.RenderElementType.DYNAMIC ||
                        it.type == com.mecon.renderer.render.RenderElementType.HAIRPIN
                },
                "full render must actually have attachment elements"
            )
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "same-width edit with attachments must stay incremental")
            assertTrue(o.spliced, "edit away from any cross-break span must engage the element-level splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    @Test
    fun unchangedVerticalFootprintReusesCachedPaginationWithAttachments() {
        val font = loadFont() ?: return
        val base = buildBase(40).withAttachments()
        with(font) {
            val cached = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(base), base, pageGeometry = multiSystem,
            )
            val edited = base.editPitch("n_20_1", Pitch.D4)
            val computer = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
            val incremental = computer.computeLayout(
                computeScore(edited), edited, pageGeometry = multiSystem,
                reuseXFrom = cached, reuseWindow = 20..20,
            )
            val full = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(edited), edited, pageGeometry = multiSystem,
            )

            assertTrue(
                computer.incrementalVerticalPaginationReused,
                "unchanged system footprint should reuse cached page assignment and vertical tail",
            )
            assertEquals(full.systems.map { it.pageIndex }, incremental.systems.map { it.pageIndex })
            assertEquals(full.pages, incremental.pages)
            assertTrue(
                computer.incrementalVerticalSystemsVisited < cached.systems.size * 2,
                "both vertical passes should converge before visiting their full system lists",
            )
        }
    }

    @Test
    fun changedVerticalFootprintPropagatesOnlyUntilPageConvergence() {
        val font = loadFont() ?: return
        val base = buildBase(40)
        with(font) {
            val cached = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(base), base, pageGeometry = multiPage,
            )
            val editMeasure = 20
            // C7 changes the edited system's vertical footprint without adding an accidental or changing
            // the horizontal partition. The following page reset is the deterministic Y-convergence point.
            val edited = base.editPitch("n_${editMeasure}_1", Pitch(21, 0))
            val computer = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
            val incremental = computer.computeLayout(
                computeScore(edited), edited, pageGeometry = multiPage,
                reuseXFrom = cached, reuseWindow = editMeasure..editMeasure,
            )
            val full = UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(edited), edited, pageGeometry = multiPage,
            )

            assertTrue(computer.incrementalVerticalPaginationReused)
            assertTrue(
                computer.incrementalVerticalSystemsVisited in 1 until cached.systems.size,
                "Y propagation should stop at a later cached page boundary; " +
                    "visited=${computer.incrementalVerticalSystemsVisited}/${cached.systems.size}",
            )
            assertEquals(full.pages, incremental.pages)
            assertEquals(full.systems.map { it.pageIndex }, incremental.systems.map { it.pageIndex })
            assertEquals(full.systems.map { it.yOffset }, incremental.systems.map { it.yOffset })
            assertEquals(full.systems.map { it.staffLayouts }, incremental.systems.map { it.staffLayouts })
            assertTrue(
                cached.measureVerticalExtentTree.sharesMeasureWith(
                    incremental.measureVerticalExtentTree, editMeasure - 1,
                ),
                "untouched measure extent chunks must remain structurally shared",
            )
        }
    }

    /**
     * Safety net for making the incremental breaker forced-break-aware (instead of bailing): a paginated
     * score with manual system breaks must still take the fast path on an edit in a line adjacent to a
     * forced break, and stay pixel-identical to a full render. A forced boundary is a firewall — greedy
     * packing can't pull measures across it — so the partition near it is even more stable, not less.
     */
    @Test
    fun paginatedWithForcedBreaksSameWidthEditIsIncrementalAndMatchesFull() {
        val font = loadFont() ?: return
        // Manual system breaks before measures 15 and 25.
        val base = buildBase(40).copy(forcedSystemBreaks = setOf(15, 25))
        with(font) {
            // Edit measure 14 (ends the line right before the forced break at 15) with a same-width pitch.
            val o = editAndRender(base, 14, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "edit beside a forced break must stay incremental")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /** A forced break combined with attachments — both relaxations exercised together. */
    @Test
    fun paginatedWithForcedBreaksAndAttachmentsMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40).withAttachments().copy(forcedSystemBreaks = setOf(16, 28))
        with(font) {
            val o = editAndRender(base, 17, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "edit beside a forced break (with attachments) must stay incremental")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /**
     * Safety net for extending the splice to **spanners** (beams / slurs): a paginated score with auto-beamed
     * eighths and in-measure slurs must stay incremental + spliced on a same-width edit and be pixel-identical
     * to a full render. Each spanner element lives in one system, so non-affected systems reuse-by-translate
     * and the edited system regenerates its beams / slurs.
     */
    @Test
    fun paginatedBeamedSlurredSameWidthEditIsIncrementalAndMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBeamedBase(40)
        with(font) {
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertTrue(o.full.elements.any { it.type == RenderElementType.BEAM }, "full render must have beam elements")
            assertTrue(o.full.elements.any { it.type == RenderElementType.SLUR }, "full render must have slur elements")
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "beamed/slurred same-width edit must stay incremental")
            assertTrue(o.spliced, "beamed/slurred edit must engage the element-level splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /**
     * Safety net for extending the splice to **multi-staff** scores: a two-staff grand staff (with a header
     * brace, beams and slurs on both staves) must stay incremental + spliced on a same-width first-staff edit
     * and be pixel-identical to a full render. Non-affected systems shift as a rigid block by one per-system Δy
     * even with two staves; the header brace is regenerated fresh per system.
     */
    @Test
    fun paginatedGrandStaffWithBeamsAndSlursMatchesFull() {
        val font = loadFont() ?: return
        val base = grandStaffBase(40)
        with(font) {
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "grand staff must break into multiple systems")
            assertTrue(
                o.full.elements.any {
                    it.type == RenderElementType.SYSTEM_BRACE || it.type == RenderElementType.SYSTEM_BRACKET
                },
                "grand staff must render a header brace / bracket"
            )
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "grand-staff same-width edit must stay incremental")
            assertTrue(o.spliced, "multi-staff edit must engage the element-level splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /**
     * Safety net for splicing a score **with a title block**: a paginated, titled score must stay incremental +
     * spliced on a same-width edit and be pixel-identical to a full render. The title lines are anchored to the
     * page top margin, so they reuse verbatim (Δ = 0) rather than translate with any system.
     */
    @Test
    fun paginatedWithTitleSameWidthEditIsIncrementalAndMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40).withTitle("My Symphony")
        with(font) {
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertTrue(
                o.full.elements.any { it.type == RenderElementType.TEXT_ANNOTATION },
                "full render must have a title text element"
            )
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path, "titled same-width edit must stay incremental")
            assertTrue(o.spliced, "titled score edit must engage the element-level splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }

    /** Same, but the edited measure itself carries a dynamic mark — its system regenerates the attachment. */
    @Test
    fun paginatedWithAttachmentsEditOnDynamicMeasureMatchesFull() {
        val font = loadFont() ?: return
        val base = buildBase(40).withAttachments()
        with(font) {
            // Measure 14 carries a dynamic; a width-changing edit there exercises the affected-system path.
            val o = editAndRender(base, 14, FSHARP4, multiSystem)
            assertTrue(o.inc.lastSystem > 0, "score must break into multiple systems")
            assertCommandsWithinEps(o.full, o.inc)
        }
    }

    @Test
    fun paginatedNoteEditWithVoltaMatchesFreshFullLayout() {
        val font = loadFont() ?: return
        val base = buildBase(40).withVoltaPair()
        with(font) {
            val o = editAndRender(base, 2, Pitch.G4, multiSystem)
            assertTrue(
                o.full.elements.any { it.type == RenderElementType.VOLTA_ENDING },
                "full render must actually contain volta attachments",
            )
            fun RenderResult.census() = elements.groupingBy {
                listOf(it.type, it.systemIndex, it.measureNumber, it.eventId)
            }.eachCount()
            assertEquals(
                IncrementalRenderPath.INCREMENTAL,
                o.path,
                "a note edit with volta attachments should retain incremental layout",
            )
            assertTrue(o.spliced, "volta attachments must remain safe for paginated element splicing")
            assertEquals(o.full.census(), o.inc.census(), "element census differs")
            assertCommandMultisetEquivalent(o.full, o.inc)
            assertEquals(
                o.full.spatialIndex.allSystems().map { it.staffRegions.map { staff -> staff.centerY } },
                o.inc.spatialIndex.allSystems().map { it.staffRegions.map { staff -> staff.centerY } },
                "incremental hit-test systems must follow the displayed volta-aware layout",
            )
        }
    }

    @Test
    fun paginatedNoteEditWithNavigationMarkRemainsSpliceable() {
        val font = loadFont() ?: return
        val base = buildBase(40).withNavigationMark(1)
        with(font) {
            val o = editAndRender(base, 20, Pitch.G4, multiSystem)
            assertTrue(
                o.full.elements.any { it.type == RenderElementType.NAVIGATION_MARK },
                "full render must contain the navigation mark",
            )
            assertEquals(IncrementalRenderPath.INCREMENTAL, o.path)
            assertTrue(o.spliced, "navigation marks are regenerated/reused by the paginated splice")
            assertCommandMultisetEquivalent(o.full, o.inc)
        }
    }
}
