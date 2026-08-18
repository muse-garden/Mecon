package com.mecon.renderer.snapshot

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.PluginRegistry
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimePluginTrack
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.AnnotationElementMeasurer
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.DrawRect
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the continuous element-level incremental splice engages — and matches a full render — when the
 * score carries a plugin **annotation staff** (chord symbols etc.). Annotations were previously a hard
 * splice bail (`pluginTracks` / `annotationElementLayouts`), forcing a full re-render of the whole score on
 * every edit; they are now regenerated wholesale inside the splice while notation is still spliced.
 *
 * See `ContinuousRenderSplicer` and `docs/data_model/incremental-update.md`.
 */
class RenderAnnotationSpliceTest {

    private data class TestStorageEvent(
        override val id: EventId,
        override val onset: TimeCode,
    ) : StoragePluginEvent()

    private data class TestRuntimeEvent(
        override val id: EventId,
        override val onset: TimeCode,
        override val storageEvent: TestStorageEvent,
    ) : RuntimePluginEvent<TestStorageEvent>

    /** Emits one text annotation per plugin event, anchored at the event's onset time. */
    private object TestAnnotationProvider : AnnotationStaffProvider {
        override val staffId = PluginStaffId("test.anno.staff")
        override val anchor: StaffAnchor get() = annotationAnchor
        override val pluginTrackTypes = setOf(TRACK_TYPE)
        override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> =
            ctx.computedScore.pluginEventsOf<TestStorageEvent>(TRACK_TYPE)
                .map { ev ->
                    AnnotationElement.Text.plain(
                        time = ev.onset,
                        sourceEventId = ev.id,
                        trackId = TrackId("anno-track"),
                        text = annotationText,
                        alignment = annotationAlignment,
                        interactive = annotationInteractive,
                    )
                }
    }

    private object GlobalAnnotationProvider : AnnotationStaffProvider {
        override val staffId = PluginStaffId("test.global-anno.staff")
        override val anchor = StaffAnchor.BelowAllStaves
        override val pluginTrackTypes = emptySet<String>()

        override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> =
            listOf(
                AnnotationElement.Text.plain(
                    time = TimeCode.of(1, Fraction.ZERO),
                    sourceEventId = EventId("global-anno"),
                    trackId = TrackId("global-anno-track"),
                    text = "1",
                )
            )
    }

    private object AboveGlobalAnnotationProvider : AnnotationStaffProvider {
        override val staffId = PluginStaffId("test.above-global-anno.staff")
        override val anchor = StaffAnchor.AboveAllStaves
        override val pluginTrackTypes = emptySet<String>()

        override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> =
            listOf(
                AnnotationElement.Text.plain(
                    time = TimeCode.of(1, Fraction.ZERO),
                    sourceEventId = EventId("above-global-anno"),
                    trackId = TrackId("above-global-anno-track"),
                    text = "degree",
                )
            )
    }

    private object RangeAnnotationProvider : AnnotationStaffProvider {
        override val staffId = PluginStaffId("test.range-anno.staff")
        override val anchor = StaffAnchor.AboveAllStaves
        override val pluginTrackTypes = emptySet<String>()

        override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> = listOf(
            AnnotationElement.Range(
                time = TimeCode.of(1, Fraction.ZERO),
                endTime = TimeCode.of(31, Fraction.ZERO),
                relativeY = 0f,
                sourceEventId = EventId("range-anno"),
                trackId = TrackId("range-anno-track"),
                height = 5f,
                lines = listOf(
                    com.mecon.api.plugin.AnnotationTextLine(
                        com.mecon.api.render.FormattedText.plain("I · 1–3–5")
                    )
                ),
                fillColor = com.mecon.api.render.RenderColor.rgba(37, 99, 184, 48),
            )
        )
    }

    @BeforeTest
    fun setUp() {
        annotationText = "Cmaj"
        annotationAlignment = AnnotationAlignment.CENTER
        annotationAnchor = StaffAnchor.BelowAllStaves
        annotationInteractive = true
        PluginRegistry.resetForTesting()
        PluginRegistry.installAll(listOf(object : com.mecon.api.plugin.MeconPlugin {
            override val id = "test.anno.plugin"
            override fun install(ctx: com.mecon.api.plugin.PluginInstallContext) {
                ctx.registerAnnotationStaffProvider(TestAnnotationProvider)
            }
        }))
    }

    @AfterTest
    fun tearDown() {
        PluginRegistry.resetForTesting()
    }

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.withAnnotationsAt(vararg onsets: TimeCode): RuntimeScore {
        val events = onsets.mapIndexed { i, t ->
            TestRuntimeEvent(EventId("anno-$i"), t, TestStorageEvent(EventId("anno-$i"), t))
        }
        val track = RuntimePluginTrack(
            id = TrackId("anno-track"),
            name = "Annotations",
            type = TRACK_TYPE,
            events = TimeIndexedList.of(events),
        )
        return copy(pluginTracks = pluginTracks + (track.id to track))
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    /** Several measures per system, multiple systems — mirrors PaginatedIncrementalLayoutTest. */
    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(400f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(420f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private fun buildPaginatedBase(measures: Int, annoMeasures: List<Int>): RuntimeScore {
        // Blank title ⇒ no title block, so the only TEXT_ANNOTATION elements are the annotation staff's.
        var base = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))
        for (m in 1..measures) {
            base = base
                .addNote("n_${m}_0", tc(m, 0), Pitch.C4, Duration.HALF)
                .addNote("n_${m}_1", tc(m, 2, 4), Pitch.E4, Duration.HALF)
        }
        return base.withAnnotationsAt(*annoMeasures.map { tc(it, 0) }.toTypedArray())
    }

    private fun com.mecon.renderer.render.RenderElement.textY(): Float =
        commands.filterIsInstance<DrawText>().first().position.y.value

    private fun com.mecon.renderer.render.RenderElement.textX(): Float =
        commands.filterIsInstance<DrawText>().first().position.x.value

    @Test
    fun annotationHitBoxUsesMeasuredTextBounds() {
        val font = loadFont() ?: return
        with(font) {
            annotationText = "III"
            val time = tc(1, 0)
            val base = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))
                .addNote("n1", time, Pitch.C4, Duration.QUARTER)
                .withAnnotationsAt(time)

            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(base)
            val annotation = result.elements.single { it.type == RenderElementType.TEXT_ANNOTATION }
            val measured = AnnotationElementMeasurer().bounds(AnnotationElement.Text.plain(time = time, text = annotationText))

            assertClose(measured.widthPx, annotation.hitBox.width.value, "annotation hitBox width")
            assertClose(measured.heightPx, annotation.hitBox.height.value, "annotation hitBox height")
        }
    }

    @Test
    fun annotationProviderWithNoPluginTrackTypesRunsWithoutPluginTracks() {
        val font = loadFont() ?: return
        PluginRegistry.resetForTesting()
        PluginRegistry.installAll(listOf(object : com.mecon.api.plugin.MeconPlugin {
            override val id = "test.global-anno.plugin"
            override fun install(ctx: com.mecon.api.plugin.PluginInstallContext) {
                ctx.registerAnnotationStaffProvider(GlobalAnnotationProvider)
            }
        }))

        with(font) {
            val score = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))
                .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(score)

            assertTrue(
                result.elements.any {
                    it.type == RenderElementType.TEXT_ANNOTATION && it.eventId == EventId("global-anno")
                },
                "annotation providers with empty pluginTrackTypes must run without plugin tracks",
            )
        }
    }

    @Test
    fun aboveAllStavesAnchorPlacesAndReservesBandAboveSystem() {
        val font = loadFont() ?: return
        PluginRegistry.resetForTesting()
        PluginRegistry.installAll(listOf(object : com.mecon.api.plugin.MeconPlugin {
            override val id = "test.above-global-anno.plugin"
            override fun install(ctx: com.mecon.api.plugin.PluginInstallContext) {
                ctx.registerAnnotationStaffProvider(AboveGlobalAnnotationProvider)
            }
        }))

        with(font) {
            val time = tc(1, 0)
            val score = RuntimeScore.fromStorage(
                StorageScore.create(
                    StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)
                )
            ).addNote("n1", time, Pitch.C4, Duration.QUARTER)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(score)
            val annotation = result.elements.single {
                it.type == RenderElementType.TEXT_ANNOTATION &&
                    it.eventId == EventId("above-global-anno")
            }
            val notationTopY = result.elements
                .filter {
                    it.type == RenderElementType.NOTEHEAD || it.type == RenderElementType.CLEF
                }
                .minOf { it.hitBox.origin.y.value }
            val annotationBottom =
                annotation.hitBox.origin.y.value + annotation.hitBox.height.value

            assertTrue(
                annotationBottom < notationTopY,
                "above annotation must clear notation " +
                    "(annotationBottom=$annotationBottom, notationTop=$notationTopY)",
            )
        }
    }

    @Test
    fun annotationsReflowNotesToPreventLabelOverlap() {
        val font = loadFont() ?: return
        with(font) {
            annotationText = "Cmaj7add13"
            annotationAlignment = AnnotationAlignment.LEFT
            val annotatedTime = tc(1, 0)
            val nextTime = tc(1, 1, 8)
            val withoutAnnotations = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))
                .addNote("n1", annotatedTime, Pitch.G4, Duration.EIGHTH)
                .addNote("n2", nextTime, Pitch.A4, Duration.EIGHTH)
            val withAnnotations = withoutAnnotations
                .withAnnotationsAt(annotatedTime, nextTime)

            val plain = RenderEngine(RenderLayoutConfig.DEFAULT).render(withoutAnnotations)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(withAnnotations)
            val plainNextNoteheads = plain.elements.filter {
                it.type == RenderElementType.NOTEHEAD && it.eventId == EventId("n2")
            }
            val nextNoteheads = result.elements.filter {
                it.type == RenderElementType.NOTEHEAD && it.eventId == EventId("n2")
            }
            val plainNextLeft = plainNextNoteheads.minOfOrNull { it.hitBox.origin.x.value }
                ?: error("plain n2 notehead missing")
            val nextLeft = nextNoteheads.minOfOrNull { it.hitBox.origin.x.value }
                ?: error("annotated n2 notehead missing")
            // Annotation tracks now participate in the horizontal solve: a wide chord label reserves room
            // to its right, so the following note reflows right instead of the label overflowing past it.
            assertTrue(
                nextLeft > plainNextLeft + 1f,
                "wide annotation must reflow the next note right (plain=$plainNextLeft, annotated=$nextLeft)",
            )

            val annotations = result.elements
                .filter { it.type == RenderElementType.TEXT_ANNOTATION }
                .sortedBy { it.eventId?.value }
            assertTrue(annotations.size == 2, "expected two annotation elements")
            val firstRight = annotations[0].hitBox.origin.x.value + annotations[0].hitBox.width.value
            val visibleGapPx = annotations[1].hitBox.origin.x.value - firstRight
            val visibleGapStaffSpace = visibleGapPx / AnnotationElementMeasurer.DEFAULT_PIXELS_PER_STAFF_SPACE

            // The reflow keeps at least the trailing gap between consecutive labels (no overlap).
            assertTrue(
                visibleGapStaffSpace >= 0.5f - 0.08f,
                "adjacent labels must not overlap after reflow (gap=$visibleGapStaffSpace staff spaces)",
            )
        }
    }

    @Test
    fun paginatedAnnotationsArePlacedUnderTheirOwnSystem() {
        val font = loadFont() ?: return
        with(font) {
            val annoMeasures = listOf(1, 10, 20, 30)
            val base = buildPaginatedBase(40, annoMeasures)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(base, pageGeometry = multiSystem)

            assertTrue(result.lastSystem > 0, "score must break into multiple systems")

            val annoEls = result.elements.filter { it.type == RenderElementType.TEXT_ANNOTATION }
            assertTrue(annoEls.size == annoMeasures.size, "expected one annotation per annotated measure, got ${annoEls.size}")

            // Map each annotation back to its time via sourceEventId (== plugin event id "anno-<i>").
            val timeByAnnoIndex = annoMeasures.mapIndexed { i, m -> i to tc(m, 0) }.toMap()
            val distinctBands = mutableSetOf<Float>()
            for (el in annoEls) {
                val annoIndex = el.eventId?.value?.removePrefix("anno-")?.toIntOrNull()
                    ?: error("annotation element missing/!malformed eventId: ${el.eventId}")
                val time = timeByAnnoIndex.getValue(annoIndex)
                val tcp = result.timeCodePositions[time] ?: error("no time-code position for $time")
                val y = el.textY()
                // The annotation must sit at/below its OWN system's notation band — not all clustered under
                // system 0 (the old global-baseline bug would put a later-system annotation far ABOVE its band).
                assertTrue(
                    y >= tcp.topY - 1f,
                    "annotation for measure ${annoIndex} drawn above its own system band (y=$y, systemTopY=${tcp.topY})",
                )
                assertTrue(
                    y <= tcp.bottomY + 80f,
                    "annotation for measure ${annoIndex} drawn too far below its system band (y=$y, systemBottomY=${tcp.bottomY})",
                )
                // X anchored to the slot's per-system justified position.
                assertTrue(
                    kotlin.math.abs(el.textX() - tcp.x) <= 40f,
                    "annotation X not aligned to its slot (x=${el.textX()}, slotX=${tcp.x})",
                )
                distinctBands.add(tcp.bottomY)
            }
            assertTrue(distinctBands.size >= 2, "annotations must span at least two different system bands, got $distinctBands")
        }
    }

    @Test
    fun durationAnnotationSplitsAcrossSystemsAndReservesEveryLine() {
        val font = loadFont() ?: return
        PluginRegistry.resetForTesting()
        PluginRegistry.installAll(listOf(object : com.mecon.api.plugin.MeconPlugin {
            override val id = "test.range-anno.plugin"
            override fun install(ctx: com.mecon.api.plugin.PluginInstallContext) {
                ctx.registerAnnotationStaffProvider(RangeAnnotationProvider)
            }
        }))

        with(font) {
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(
                buildPaginatedBase(measures = 40, annoMeasures = emptyList()),
                pageGeometry = multiSystem,
            )
            val segments = result.elements.filter { element ->
                element.type == RenderElementType.TEXT_ANNOTATION &&
                    element.eventId == EventId("range-anno")
            }

            assertTrue(segments.mapNotNull { it.systemIndex }.distinct().size > 1)
            assertTrue(segments.all { segment -> segment.commands.any { it is DrawRect } })
            assertTrue(segments.all { segment -> segment.hitBox.width.value > 0f })
        }
    }

    @Test
    fun paginatedAboveAnnotationsArePlacedOverTheirOwnSystem() {
        val font = loadFont() ?: return
        with(font) {
            annotationAnchor = StaffAnchor.AboveAllStaves
            val annoMeasures = listOf(1, 10, 20, 30)
            val base = buildPaginatedBase(40, annoMeasures)
            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(base, pageGeometry = multiSystem)
            val timeByAnnoIndex = annoMeasures.mapIndexed { index, measure ->
                index to tc(measure, 0)
            }.toMap()
            val annotations = result.elements.filter { it.type == RenderElementType.TEXT_ANNOTATION }

            assertTrue(result.lastSystem > 0)
            assertTrue(annotations.size == annoMeasures.size)
            annotations.forEach { annotation ->
                val index = annotation.eventId?.value?.removePrefix("anno-")?.toIntOrNull()
                    ?: error("malformed annotation id")
                val band = result.timeCodePositions.getValue(timeByAnnoIndex.getValue(index))
                val bottom = annotation.hitBox.origin.y.value + annotation.hitBox.height.value
                assertTrue(
                    bottom < band.topY,
                    "above annotation must clear its own paginated system " +
                        "(bottom=$bottom, systemTop=${band.topY})",
                )
            }
        }
    }

    @Test
    fun nonInteractiveAboveAnnotationsAreRoutedBeyondFirstPage() {
        val font = loadFont() ?: return
        with(font) {
            annotationAnchor = StaffAnchor.AboveAllStaves
            annotationInteractive = false
            val annoMeasures = listOf(1, 10, 20, 30, 40)
            val base = buildPaginatedBase(40, annoMeasures)
            val shortPages = multiSystem.copy(
                pageContentHeight = StaffSpace(38f),
                paperHeight = StaffSpace(44f),
            )

            val result = RenderEngine(RenderLayoutConfig.DEFAULT).render(
                base,
                pageGeometry = shortPages,
            )
            val pagedAnnotations = result.pages.flatMap { page ->
                page.elements.filter {
                    it.type == RenderElementType.TEXT_ANNOTATION &&
                        it.eventId?.value?.startsWith("anno-") == true
                }.map { page.pageIndex to it.eventId }
            }

            assertTrue(result.pages.size > 1, "test score must span multiple pages")
            assertTrue(
                pagedAnnotations.size == annoMeasures.size,
                "every non-interactive annotation must be present in page buckets",
            )
            assertTrue(
                pagedAnnotations.any { (pageIndex, _) -> pageIndex > 0 },
                "annotations after page 1 must be routed by systemIndex, not their zero hitBox",
            )
        }
    }

    /** Smallest gap between consecutive system bands (in px), from the annotated downbeats' band positions. */
    private fun firstInterSystemGap(result: RenderResult, times: List<TimeCode>): Float {
        val bands = times.mapNotNull { result.timeCodePositions[it] }
            .map { it.topY to it.bottomY }
            .distinct()
            .sortedBy { it.first }
        require(bands.size >= 2) { "expected at least two system bands, got $bands" }
        return bands[1].first - bands[0].second
    }

    @Test
    fun paginatedAnnotationsReserveVerticalSpaceBelowEachSystem() {
        val font = loadFont() ?: return
        with(font) {
            val annoMeasures = (1..40).toList() // a band on every line
            val base = buildPaginatedBase(40, annoMeasures)
            val times = annoMeasures.map { tc(it, 0) }

            // With the annotation provider registered (setUp): each line reserves its band.
            val withAnno = RenderEngine(RenderLayoutConfig.DEFAULT).render(base, pageGeometry = multiSystem)
            val gapWith = firstInterSystemGap(withAnno, times)

            // Drop the provider (the plugin track stays, but now yields no annotations / no reserved room).
            PluginRegistry.resetForTesting()
            val withoutAnno = RenderEngine(RenderLayoutConfig.DEFAULT).render(base, pageGeometry = multiSystem)
            val gapWithout = firstInterSystemGap(withoutAnno, times)

            assertTrue(
                gapWith > gapWithout + 8f,
                "annotations must widen the inter-system gap to reserve their band (with=$gapWith, without=$gapWithout)",
            )
        }
    }

    @Test
    fun paginatedPitchEditWithAnnotationsSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val base = buildPaginatedBase(40, listOf(1, 10, 20, 30))
            val edited = base.editPitch("n_20_1", Pitch.G4) // same width, mid-line

            val previous = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, TimeRange(tc(20, 2, 4), tc(20, 3, 4)))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base, pageGeometry = multiSystem)
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiSystem)

            assertTrue(incRender.lastSystem > 0, "score must break into multiple systems")
            assertTrue(engine.lastRenderWasSpliced(), "paginated edit with an annotation staff must engage the splice")
            assertTrue(
                incRender.elements.any { it.type == RenderElementType.TEXT_ANNOTATION },
                "incremental render must keep the annotation elements",
            )

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiSystem)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    @Test
    fun pitchEditWithAnnotationStaffSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val base = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))
                .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
                .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
                .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
                .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
                .withAnnotationsAt(tc(1, 0), tc(3, 0))
            val edited = base.editPitch("n2", Pitch.A4)

            val previous: ComputedScore = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))

            // Sanity: the annotation staff is actually present in the rendered frames.
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val baseRender = engine.render(base)
            assertTrue(
                baseRender.elements.any { it.type.name == "TEXT_ANNOTATION" },
                "expected annotation elements in the baseline render",
            )

            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)
            assertTrue(engine.lastRenderWasSpliced(), "expected the element-level splice to engage with an annotation staff")
            assertTrue(
                incRender.elements.any { it.type.name == "TEXT_ANNOTATION" },
                "expected annotation elements in the incremental render",
            )

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    /**
     * The chord-edit path: no voice event changes, only the annotation **symbol text** (and its width).
     * `ScoreSession.applyPluginEdit` drives this via [RenderEngine.renderRange] with a `forRange` change
     * set over the edited chord's measure. The splice must engage, show the new symbol, and match a full
     * render (the wider symbol re-spaces its own measure; the tail translates rigidly).
     */
    @Test
    fun annotationTextEditViaRenderRangeSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            annotationText = "C"
            val base = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))
                .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
                .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
                .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
                .withAnnotationsAt(tc(1, 0))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base) // warm the cache with symbol "C"

            // Simulate editing the chord so its symbol changes (and widens) — full recompute, bounded render.
            annotationText = "Cmaj7"
            val edited = computeScore(base)
            val incRender = engine.renderRange(edited, 1..1)

            assertTrue(engine.lastRenderWasSpliced(), "chord annotation edit via renderRange must engage the splice")
            val annos = incRender.elements.filter { it.type == RenderElementType.TEXT_ANNOTATION }
            assertTrue(annos.size == 1, "expected the regenerated annotation element, got ${annos.size}")
            assertTrue(
                annos.single().commands.filterIsInstance<DrawText>().any { it.text == "Cmaj7" },
                "incremental render must show the edited chord symbol",
            )

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(base) // base now lays out "Cmaj7"
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    /**
     * Paginated counterpart: a chord symbol edit mid-score re-renders and matches a full render.
     *
     * Now that annotation tracks participate in the horizontal solve, widening a chord symbol is a genuine
     * width-changing edit. In paginated mode a width change either re-justifies its line in place (splice)
     * or, if it flips a line break, reflows to a full solve — the same reason the pitch-edit sibling uses a
     * deliberately same-width edit. Either outcome must match a full render, which is the contract asserted
     * here; the window still covers exactly the measure whose annotation width changed.
     */
    @Test
    fun paginatedAnnotationTextEditViaRenderRangeMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            annotationText = "C"
            val base = buildPaginatedBase(40, listOf(20))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base, pageGeometry = multiSystem) // warm cache with symbol "C"

            annotationText = "Cmaj7" // edit the sole chord symbol (at measure 20)
            val edited = computeScore(base)
            val incRender = engine.renderRange(edited, 20..20, pageGeometry = multiSystem)

            assertTrue(incRender.lastSystem > 0, "score must break into multiple systems")
            assertTrue(
                incRender.elements.any { el ->
                    el.type == RenderElementType.TEXT_ANNOTATION &&
                        el.commands.filterIsInstance<DrawText>().any { it.text == "Cmaj7" }
                },
                "incremental render must show the edited chord symbol",
            )

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(base, pageGeometry = multiSystem)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    companion object {
        private const val TRACK_TYPE = "test.anno"
        private var annotationText = "Cmaj"
        private var annotationAlignment = AnnotationAlignment.CENTER
        private var annotationAnchor: StaffAnchor = StaffAnchor.BelowAllStaves
        private var annotationInteractive: Boolean = true

        private fun assertClose(expected: Float, actual: Float, label: String, eps: Float = 0.02f) {
            assertTrue(
                kotlin.math.abs(expected - actual) <= eps,
                "$label: expected $expected, got $actual",
            )
        }
    }
}
