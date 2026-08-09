package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.KeySignatureElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.elements.TimeSignatureElement
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.stem.StemDirectionResolver

/**
 * Computes unified layout for a score.
 *
 * This computer processes all events (notes, barlines, clefs, etc.) together
 * in time code order to ensure proper alignment. The process:
 *
 * 1. Collect all events from the computed score
 * 2. Create barline events at measure boundaries
 * 3. Group events by time code into UnifiedTimeSlots
 * 4. Process slots in order, calculating X coordinates
 * 5. Build element layouts with relative coordinates
 * 6. Determine staff Y positions to avoid overlap
 */
context(com.mecon.renderer.smufl.BravuraFont)
class UnifiedLayoutComputer(
    private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT
) {
    private val voiceEventLayoutBuilder = VoiceEventLayoutBuilder(config)
    private val horizontalSlotComputer = UnifiedHorizontalSlotComputer(config)
    private val stemDirectionResolver = StemDirectionResolver(
        voiceConfig = config.voiceStemConfig,
        beamConfig = config.beamLayoutConfig
    )
    private val eventCollector = EventCollector(stemDirectionResolver, config)
    private val staffLayoutComputer = StaffLayoutComputer(config)
    private val staffAttachmentLayoutComputer = StaffAttachmentLayoutComputer(config)
    private val annotationStaffLayoutComputer = AnnotationStaffLayoutComputer(config)
    private val staffHeaderLayoutComputer = StaffHeaderLayoutComputer(config)

    /**
     * Diagnostic: whether the most recent [computeLayout] call ran the incremental measure-granular
     * proportional layout (re-solve the affected-measure window, translate the rest) instead of a full
     * solve. Read by the incremental render path for instrumentation / tests. Not thread-safe — like
     * the rest of this single-use computer, one instance serves one layout call on the render thread.
     */
    var incrementalLayoutUsed: Boolean = false
        private set
    /** Whether the latest incremental paginated solve reused cached page assignment/vertical tail. */
    var incrementalVerticalPaginationReused: Boolean = false
        private set
    var incrementalVerticalSystemsVisited: Int = 0
        private set

    /**
     * Compute layout for a score.
     *
     * @param computed The computed score with resolved properties
     * @param runtime The runtime score for track relationships
     * @param pageWidth Available page width
     * @param pageHeight Available page height
     * @param reuseXFrom Previous layout to reuse X from (incremental layout). When non-null together
     *   with [reuseWindow] and in continuous (non-paginated) mode, only the measures in [reuseWindow]
     *   are re-solved; every other measure's slot X is taken from this layout and translated rigidly
     *   by the shift the window introduced. This is the measure-granular "复用水平 X" incremental seam
     *   (docs/renderer/incremental-rendering.md). Stable partitions remain pixel-identical; a live reflow preserves the full solve's
     *   greedy partition but may retain sub-pixel X differences until a later cold engraving solve.
     * @param reuseWindow Measures (`affectedMeasures` from the compute change set) to re-solve; the
     *   rest are reused. Required for incremental layout; null ⇒ full solve.
     * @return UnifiedLayoutResult containing all layout data
     */
    fun computeLayout(
        computed: ComputedScore,
        runtime: RuntimeScore,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: PageGeometry = PageGeometry.continuous(pageWidth),
        reuseXFrom: UnifiedLayoutResult? = null,
        reuseWindow: IntRange? = null,
        /**
         * Persisted geometry whose attachment entries drive each span's vertical position (Phase 3).
         * Defaults to the runtime overlay — full render honours the loaded geometry; the incremental
         * path passes the **pruned** overlay so stale spans fall back to auto (reshape). See
         * [StaffAttachmentLayoutComputer.compute].
         */
        attachmentGeometry: com.mecon.api.storage.ScoreGeometry? = null,
    ): UnifiedLayoutResult {
        incrementalLayoutUsed = false
        incrementalVerticalPaginationReused = false
        incrementalVerticalSystemsVisited = 0
        val attachOverlay = attachmentGeometry ?: computed.runtime.geometry
        // Build track mappings. Staff display order comes from depth-first traversal of staffGroups.
        val voiceToStaff = TrackMappingUtils.buildVoiceToStaffMapping(runtime)
        val staffTracks = runtime.orderedStaffs().mapIndexed { index, staff ->
            staff.id to StaffInfo(
                trackId = staff.id,
                staffIndex = index,
                partIndex = 0,
                clef = staff.clef,
                keySignature = staff.keySignature,
                hiddenRanges = staff.hiddenRanges
            )
        }.toMap()

        // Incremental layout: given a previous layout + an affected-measure window, re-solve only those
        // measures and translate/reuse the rest, reusing the previous layout's PRE-break proportional X
        // ([UnifiedLayoutResult.preBreakTimeSlotMap] — in continuous mode just its timeSlotMap; in
        // paginated mode the pre-justification snapshot). In paginated mode the line-breaking is also done
        // incrementally (reuse the cached partition, re-justify only the affected line) via
        // [SystemBreaker.breakIntoSystemsIncremental], which re-packs from the affected band on reflow.
        val canReuse = reuseXFrom != null && reuseWindow != null
        incrementalLayoutUsed = canReuse

        // Step 1: Collect layout events with voice context and pre-resolved stem directions. On the
        // incremental path collection is **window-only** — only the re-solve window's events are built
        // fresh; everything outside is reconstructed below by reusing the cached slot map (§2.5 `collect`).
        // This removes both the whole-score glyph work and the two whole-score event scans from collection.
        // Sub-probes attribute the incremental computeLayout cost per pass; see docs/renderer/incremental-rendering.md.
        // each is a cheap monotonic read, only formatted/printed when PerfLog is enabled.
        val _tCollect = kotlin.time.TimeSource.Monotonic.markNow()
        val collectedEvents = eventCollector.collectAllEventsWithResolvedStems(
            computed, runtime, voiceToStaff, staffTracks,
            windowOnly = if (canReuse) reuseWindow else null
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.collect=${_tCollect.elapsedNow()}" }

        // Full collect with no events ⇒ empty score. (Incremental window-only collect may legitimately be
        // empty — e.g. clearing a measure — while the cached remainder is not, so its check is post-splice.)
        if (!canReuse && collectedEvents.isEmpty()) {
            return UnifiedLayoutResult.EMPTY
        }

        // Steps 2–4: relative-X within slots → time-slot map → proportional X (window-solved).
        val _tXsolve = kotlin.time.TimeSource.Monotonic.markNow()
        // Steps 2–3: build the pre-proportional slot map. Full path groups all events by time and assigns
        // per-slot relative X. Incremental path splices: fresh window slots + the cached map's out-of-window
        // slots reused verbatim (§2.5 `xsolve`), avoiding the whole-score group + sort — relativeX is a
        // pure function of the same-slot events, so an untouched slot is byte-identical to a fresh build.
        val baseTimeSlotMap = if (canReuse)
            horizontalSlotComputer.spliceBaseTimeSlotMap(
                collectedEvents,
                reuseXFrom!!.preBreakTimeSlotMap,
                reuseWindow!!,
            )
        else horizontalSlotComputer.buildBaseTimeSlotMap(collectedEvents)

        if (baseTimeSlotMap.isEmpty()) {
            return UnifiedLayoutResult.EMPTY
        }

        // Step 4: Calculate X positions for each time slot. Reserve room on the
        // left for the staff header (brackets + labels) before the staves begin.
        // Slots are immutable, so the proportional pass returns a new, X-positioned map.
        val headerOriginX = config.firstSystemIndent
        val headerWidth = staffHeaderLayoutComputer.computeWidth(computed.staffHeader)
        val systemStartX = headerOriginX + headerWidth
        // Annotation (chord-symbol) label widths feed the horizontal solve so notes reflow to fit long
        // labels. On the incremental path only the re-solve window's labels are needed — window-external
        // measures translate their cached X (which already baked the label room in). Empty ⇒ no-op.
        val annotationSpacing = horizontalSlotComputer.buildAnnotationSpacingParticipants(
            computed,
            windowMeasures = if (canReuse) reuseWindow else null
        )
        var timeSlotMap = horizontalSlotComputer.calculateTimeSlotXPositionsProportional(
            baseTimeSlotMap,
            systemStartX,
            computed,
            cached = if (canReuse) reuseXFrom!!.preBreakTimeSlotMap else null,
            solveWindow = if (canReuse) reuseWindow else null,
            annotationSpacing = annotationSpacing
        )
        // Snapshot the proportional (pre-break) X before the breaker re-stretches it for pagination, so
        // the next incremental call can reuse it. In continuous mode this is the same map (never broken).
        val preBreakTimeSlotMap = timeSlotMap
        val resolvedTimeAxis = config.alignedTimeAxisRequest
            ?.takeUnless { pageGeometry.paginated }
            ?.let { request ->
                AlignedTimeAxisResolver.resolve(
                    runtime = runtime,
                    intrinsic = timeSlotMap,
                    request = request,
                    systemStartX = systemStartX,
                ).also { projection ->
                    timeSlotMap = projection.slots
                }.axis
            }
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.xsolve=${_tXsolve.elapsedNow()}" }

        // Step 5: Build element layouts. On the incremental path, notes outside the re-solve window
        // reuse their cached VoiceEventLayout verbatim (§2.5 ②): the layout is fully relative (X to the
        // slot's time-code X, Y to the staff center line), so a windowed edit — which cannot change an
        // outside-window note's pitch / duration / beam / stem — leaves it byte-identical, and the slot's
        // X translation is applied downstream at render time. This mirrors the NoteElement geometry reuse
        // in [EventCollector]; without it, buildVoiceEventLayout (glyph-metric work per note) would run
        // over the whole score and undo that reuse.
        val _tStep5 = kotlin.time.TimeSource.Monotonic.markNow()
        val cachedVoiceLayouts = if (canReuse) reuseXFrom!!.voiceEventLayouts else null
        val canReuseVoiceChunks = canReuse && reuseXFrom!!.voiceLayoutsByMeasure.isNotEmpty()
        val collectedVoiceLayouts = mutableListOf<VoiceEventLayout>()
        val _tStep5Collect = kotlin.time.TimeSource.Monotonic.markNow()

        val layoutSlots = if (canReuseVoiceChunks) {
            timeSlotMap.all().asSequence().filter { it.time.measure in reuseWindow!! }
        } else timeSlotMap.all().asSequence()
        for (slot in layoutSlots) {
            for (event in slot.events) {
                when (event) {
                    is NoteElement -> {
                        val reused = if (cachedVoiceLayouts != null && event.measureNumber !in reuseWindow!!)
                            cachedVoiceLayouts[event.eventId] else null
                        val layout = reused ?: buildVoiceEventLayout(
                            event, staffTracks,
                            event.resolvedStemDirection ?: StemDirection.UP // Fallback should not happen
                        )
                        collectedVoiceLayouts.add(layout)
                    }
                    is BarlineElement -> Unit
                    is ClefElement -> {
                        // Clef layout is handled during rendering using relativeX
                    }
                    is KeySignatureElement -> {
                        // Key signature layout is handled during rendering using relativeX
                    }
                    is TimeSignatureElement -> {
                        // Time signature layout is handled during rendering using relativeX
                    }
                }
            }
        }
        val voiceLayoutsByMeasure = if (canReuseVoiceChunks) {
            HashMap(reuseXFrom!!.voiceLayoutsByMeasure).apply {
                for (measure in reuseWindow!!) remove(measure)
                collectedVoiceLayouts.groupBy { it.measureNumber }.forEach { (measure, layouts) ->
                    this[measure] = layouts
                }
            }
        } else collectedVoiceLayouts.groupBy { it.measureNumber }
        val voiceEventLayouts = voiceLayoutsByMeasure.entries.sortedBy { it.key }.flatMap { it.value }
        val barlineLayouts = computed.barlines.mapNotNull { barline ->
            val slot = timeSlotMap.atTime(barline.time) ?: return@mapNotNull null
            val element = slot.events.firstOrNull {
                it is BarlineElement && it.measureNumber == barline.measureNumber
            } as? BarlineElement ?: return@mapNotNull null
            BarlineLayout(
                time = element.time,
                x = slot.x + element.relativeX,
                type = element.type,
                measureNumber = element.measureNumber,
            )
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "layout.step5.collect=${_tStep5Collect.elapsedNow()} layouts=${voiceEventLayouts.size}"
        }

        // Per-(measure, staff) note vertical extents — the cache that drives line-local vertical
        // layout in paginated mode (a line's extent = per-staff max over its measures). Stored in the
        // result (mirrors preBreakMeasureWidths) for future incremental / B+ tree reuse. On the
        // incremental path, out-of-window measures reuse the cached extent verbatim (their layouts are
        // byte-identical), skipping the per-measure calculateExtents scan (§2.5 step5/extent bucket).
        val _tStep5Extent = kotlin.time.TimeSource.Monotonic.markNow()
        val measureExtents = if (canReuse)
            staffLayoutComputer.extentsByMeasureStaffIncremental(
                if (canReuseVoiceChunks) collectedVoiceLayouts else voiceEventLayouts,
                reuseXFrom!!.preBreakMeasureExtents,
                reuseWindow!!
            )
        else staffLayoutComputer.extentsByMeasureStaff(voiceEventLayouts)
        val measureVerticalExtentTree = MeasureVerticalExtentTree.build(
            extents = measureExtents,
            cached = if (canReuse) reuseXFrom!!.measureVerticalExtentTree else null,
            replaceWindow = if (canReuse) reuseWindow else null,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "layout.step5.measureExtents=${_tStep5Extent.elapsedNow()}"
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.step5=${_tStep5.elapsedNow()}" }
        val _tVoiceMap = kotlin.time.TimeSource.Monotonic.markNow()
        val voiceEventLayoutMap = if (canReuseVoiceChunks) {
            reuseXFrom!!.voiceEventLayouts.patchMeasures(
                measures = reuseWindow!!,
                oldByMeasure = reuseXFrom.voiceLayoutsByMeasure,
                replacements = collectedVoiceLayouts,
            )
        } else VoiceEventLayoutMap.fromList(voiceEventLayouts)
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "layout.step5.voiceMap=${_tVoiceMap.elapsedNow()} layouts=${voiceEventLayoutMap.size}"
        }

        // Step 5.5: Lay out staff attachments (dynamics, hairpins). Needs X positions
        // (already computed) and each staff's note extents; produces the extra vertical
        // room each staff must reserve so Step 6 can space staves without overlap.
        // (Continuous mode is one system, so a single system-0 bucket of global extents.)
        // Per-staff global note extents + the X-keyed local index BOTH feed only attachment placement,
        // which is a no-op for a score with no dynamics / hairpins / octave-shifts. Skip both whole-score
        // scans (the `groupBy{staffIndex}` + per-staff `calculateExtents` and the `NoteExtentIndex.build`)
        // entirely in that case (§2.5 ⑨): `calculateStaffYPositions` derives its own per-staff extents from
        // `measureExtents`, so nothing else needs `noteExtents`. All §11 perf baselines take this branch.
        val hasAttachments = computed.staffAttachments.isNotEmpty()
        // Attachment ownership is stable for ordinary note edits. Retain the measure chunks so the
        // paginated pass can fetch only affected-system candidates instead of filtering thousands of
        // score-wide marks through timeSlotMap on every frame.
        val canReuseAttachmentMeasureIndex = canReuse &&
            (reuseXFrom!!.staffAttachmentsSnapshot === computed.staffAttachments ||
                reuseXFrom.staffAttachmentsSnapshot == computed.staffAttachments)
        val attachmentsByMeasure = if (canReuseAttachmentMeasureIndex) {
            reuseXFrom!!.staffAttachmentsByMeasure
        } else {
            // Interval attachments are fetched later by each system's 1-based measureRange.
            // A first ending anchored to the score-opening boundary has time.measure == 0,
            // but semantically belongs to ending.startMeasure == 1. Indexing it under zero
            // makes an incremental refresh of system 0 omit the first ending, after which
            // patchSystems replaces the cached pair with only the second ending.
            computed.staffAttachments.groupBy(AttachmentLayoutIndex::anchorMeasure)
        }
        val _tExtent = kotlin.time.TimeSource.Monotonic.markNow()
        val _tExtentTree = kotlin.time.TimeSource.Monotonic.markNow()
        // Persistent two-level extent tree: measure chunks are patched only inside the edit window;
        // each chunk indexes staff/local-X with a max(top,bottom) aggregate. The same immutable tree
        // feeds both the proportional and justified attachment passes through different X transforms.
        val noteExtentTree = if (hasAttachments) NoteExtentTree.build(
            layoutsByMeasure = voiceLayoutsByMeasure,
            slots = preBreakTimeSlotMap,
            extentOf = staffLayoutComputer::calculateExtent,
            cached = if (canReuse) reuseXFrom!!.noteExtentTree else null,
            replaceWindow = if (canReuse) reuseWindow else null,
        ) else NoteExtentTree.EMPTY
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "layout.extent.tree=${_tExtentTree.elapsedNow()}"
        }
        val noteExtents: Map<Int, Pair<StaffSpace, StaffSpace>>
        val noteExtentIndex: NoteExtentIndex
        // Paginated layout places attachments only after line breaking, against justified per-system X.
        // Running the continuous/system-0 placement here as well computes the same 2,698 marks twice and
        // its result is overwritten below. Retain the tree (the post-break pass consumes it), but skip
        // the global extent/index/placement preparation unless continuous mode actually needs it.
        val needsPreBreakAttachmentPlacement = hasAttachments && !pageGeometry.paginated
        if (needsPreBreakAttachmentPlacement) {
            val _tExtentGlobal = kotlin.time.TimeSource.Monotonic.markNow()
            // Same max-reduction already cached per measure; avoid grouping every voice layout by staff.
            noteExtents = staffLayoutComputer.perStaffExtents(measureExtents)
            com.mecon.renderer.debug.PerfLog.log("render.stage") {
                "layout.extent.global=${_tExtentGlobal.elapsedNow()} staffs=${noteExtents.size}"
            }
            // X-keyed per-event extents so each mark anchors to its LOCAL notes; only systems that host an
            // attachment are ever queried, so build the index for those alone (continuous = system 0).
            val _tExtentIndex = kotlin.time.TimeSource.Monotonic.markNow()
            noteExtentIndex = noteExtentTree.index(
                baseSlots = preBreakTimeSlotMap,
                displaySlots = timeSlotMap,
                systemFilter = AttachmentLayoutIndex.hostingSystems(computed.staffAttachments, timeSlotMap),
            )
            com.mecon.renderer.debug.PerfLog.log("render.stage") {
                "layout.extent.index=${_tExtentIndex.elapsedNow()}"
            }
        } else {
            noteExtents = emptyMap()
            noteExtentIndex = NoteExtentIndex.EMPTY
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.extent=${_tExtent.elapsedNow()}" }
        val _tAttach = kotlin.time.TimeSource.Monotonic.markNow()
        val attachmentResult = if (needsPreBreakAttachmentPlacement)
            staffAttachmentLayoutComputer.compute(
                computed, timeSlotMap, mapOf(0 to noteExtents), noteExtentIndex, attachOverlay
            )
        else StaffAttachmentLayoutResult.EMPTY
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.attachment=${_tAttach.elapsedNow()}" }

        // Steps 6–7: staff Y stack + continuous-mode annotation baseline.
        val _tStaffY = kotlin.time.TimeSource.Monotonic.markNow()
        // Step 6: Calculate staff Y positions (reserving room for attachments). This global stack is
        // the continuous-mode layout and the paginated-mode flat fallback / annotation baseline; the
        // paginated per-line stacks are computed inside the SystemBreaker from [measureExtents].
        val annotationLineExtents = annotationStaffLayoutComputer.perLineExtentsFn(computed)
        val annotationWholeScoreRange = 1..(
            computed.barlines.maxOfOrNull { it.measureNumber }?.coerceAtLeast(1) ?: 1
        )
        val continuousAnnotationExtents = annotationLineExtents(annotationWholeScoreRange)
        val notationStaffLayouts = staffLayoutComputer.calculateStaffYPositions(
            staffTracks = staffTracks.values.toList(),
            voiceEventLayouts = voiceEventLayouts,
            startY = config.topMargin + continuousAnnotationExtents.above,
            extraExtents = attachmentResult.extentsForSystem(0),
            // Derive per-staff global extents from the per-measure cache (max over measures) instead of a
            // full-score voiceEventLayouts scan — byte-identical, and reuses the incrementally-built cache.
            measureExtents = measureExtents
        )

        // Step 7: Compute annotation staves contributed by plugins
        val annotationResult = annotationStaffLayoutComputer.compute(
            computedScore = computed,
            timeSlotMap = timeSlotMap,
            notationStaffLayouts = notationStaffLayouts
        )
        val staffLayouts = notationStaffLayouts + annotationResult.staffLayouts
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.staffY=${_tStaffY.elapsedNow()}" }

        // Step 8: staff-header brackets / labels are computed PER SYSTEM in step 9 against
        // each system's Y-offset staves, so they repeat at the top of every system and line
        // up with the (possibly paginated) staves. startX = headerOriginX keeps bracket
        // x-coords in page space, flush with systemStartX (the staff left edge).
        fun headersFor(system: SystemLayout): SystemLayout {
            val notation = system.staffLayouts.filter { it.kind == StaffKind.NOTATION }
            val hr = staffHeaderLayoutComputer.compute(
                computed.staffHeader, notation, startX = headerOriginX
            )
            return system.copy(headerLabels = hr.labels, headerBrackets = hr.brackets)
        }

        // Step 9: Line-breaking / pagination (paginated mode only). In continuous
        // mode a single system covers the whole score with no Y offset, so the
        // layout is identical to the pre-pagination behaviour.
        var finalAttachments = attachmentResult.placed
        var paginatedAttachmentLayout: StaffAttachmentLayoutResult? = null
        var finalBarlines = barlineLayouts.toList()
        val systems: List<SystemLayout>
        val pages: List<PageLayout>
        var preAttachmentSystems: List<SystemLayout> = emptyList()
        var preAttachmentPages: List<PageLayout> = emptyList()
        val suppressed: Set<TimeCode>
        var suppressedClef: Set<TimeCode> = emptySet()
        // Pre-break intrinsic measure widths the breaker derived — stored for the next incremental call.
        var measureWidths: Map<Int, StaffSpace> = emptyMap()
        var systemLineage: List<SystemOrigin>? = null
        var attachmentSystemSpans: List<Pair<Int, Int>> = emptyList()

        // Title block (title / subtitle / composer) above the first system — paginated only.
        val titleBlock = if (pageGeometry.paginated)
            TitleBlockComputer().compute(runtime.metadata, pageGeometry) else null

        val _tPaginate = kotlin.time.TimeSource.Monotonic.markNow()
        if (pageGeometry.paginated && notationStaffLayouts.isNotEmpty()) {
            val breaker = SystemBreaker(config, measureVerticalExtentTree)
            val staffTrackList = staffTracks.values.toList()
            val titleH = titleBlock?.height ?: StaffSpace.ZERO
            // Per-line annotation band height — the breaker reserves this below each line's notation so
            // paginated chord symbols etc. sit in real space, not the inter-system gap (matches the
            // placement in computePaginated). ZERO-returning no-op when no annotation plugin applies.
            val breakContent = SystemBreakContent(
                timeSlotMap = timeSlotMap,
                barlineLayouts = BarlineLayoutMap.fromList(barlineLayouts),
                staffTracks = staffTrackList,
                perMeasureExtents = measureExtents,
                computed = computed,
            )
            val breakPage = SystemBreakPage(
                pageGeometry = pageGeometry,
                systemStartX = systemStartX,
                forcedSystemBreaks = runtime.forcedSystemBreaks,
                forcedPageBreaks = runtime.forcedPageBreaks,
                titleBlockHeight = titleH,
                annotationLineExtents = annotationLineExtents,
            )
            // Incremental line-breaking: reuse the cached partition and re-justify only the affected
            // line(s). Returns null on reflow (or an unsupported feature), in which case we must redo a
            // full, drift-free solve — the incremental pre-break X cannot be trusted for a fresh greedy
            // pass (that is exactly the sub-pixel-drift bug that flips line breaks).
            val _tBreak = kotlin.time.TimeSource.Monotonic.markNow()
            val incBreak = if (canReuse && reuseXFrom!!.paginated)
                breaker.breakIntoSystemsIncremental(
                    IncrementalSystemBreakRequest(
                    cached = reuseXFrom,
                    content = breakContent,
                    page = breakPage,
                    window = reuseWindow!!,
                    deferVerticalToAttachments = hasAttachments,
                    ),
                ) else null
            if (canReuse && incBreak == null) {
                return computeLayout(
                    computed, runtime, pageWidth, pageHeight, pageGeometry,
                    reuseXFrom = null, reuseWindow = null
                )
            }
            if (incBreak?.systemLineage != null && hasAttachments) {
                // Spans derive clipped endpoints from both pre-break and justified X. After a line break
                // moves, the translated live-edit X is close enough for notation but is not yet a safe
                // source for cross-system attachment clipping. Keep the strict solve for this narrower
                // case until attachment geometry consumes the width cache directly; ordinary notation
                // reflow avoids the second whole-score solve below.
                val freshFull = computeLayout(
                    computed, runtime, pageWidth, pageHeight, pageGeometry,
                    reuseXFrom = null, reuseWindow = null
                )
                return if (freshFull.systems.size == incBreak.systemLineage!!.size) {
                    incrementalLayoutUsed = true
                    freshFull.copy(systemLineage = incBreak.systemLineage)
                } else freshFull
            }
            // Reflow no longer performs a second, strict full X solve. The breaker made its discrete
            // partition decision from the persistent per-measure width cache (fresh only in the edit
            // window), so translated-coordinate noise cannot change the chosen breaks. For live editing,
            // the already-computed proportional slots are sufficient input to justification; downstream
            // systems/pages are then handled by the existing reflow splicer / progressive renderer.
            val breakResult = incBreak ?: breaker.breakIntoSystems(
                SystemBreakRequest(breakContent, breakPage),
            )
            suppressed = breakResult.suppressedBarlineTimes
            suppressedClef = breakResult.suppressedClefTimes
            measureWidths = breakResult.measureWidths
            systemLineage = breakResult.systemLineage
            incrementalVerticalPaginationReused = breakResult.verticalPaginationReused
            incrementalVerticalSystemsVisited = breakResult.verticalSystemsVisited
            com.mecon.renderer.debug.PerfLog.log("render.stage") {
                "layout.paginate.break=${_tBreak.elapsedNow()} " +
                    "verticalReused=${breakResult.verticalPaginationReused} " +
                    "verticalVisited=${breakResult.verticalSystemsVisited}/${breakResult.systems.size}"
            }

            // The breaker returned a re-stretched, system-tagged snapshot of the slots; adopt it.
            // Place attachments against the stretched slots using each LINE's own note extents (so a
            // dynamic sits relative to its line's notes), tag/split per system, then fold the
            // attachment room back into the per-line vertical layout (which may re-paginate). A score with
            // no attachments skips the whole re-stretched extent scan + placement (§2.5 ⑨); the vertical
            // pass already ran with the annotation extents, so systems/pages pass through unchanged.
            val _tPagAttach = kotlin.time.TimeSource.Monotonic.markNow()
            timeSlotMap = breakResult.timeSlotMap
            preAttachmentSystems = breakResult.systems
            preAttachmentPages = breakResult.pages
            if (hasAttachments) {
                val affectedAttachmentSystems = if (canReuse && incBreak != null) breakResult.systems
                    .filter { system -> system.measureRange.any { it in reuseWindow!! } }
                    .mapTo(HashSet()) { it.systemIndex }
                else emptySet()
                val cachedAttachmentLayout = reuseXFrom?.paginatedAttachmentLayout
                val attachmentGuardStart = kotlin.time.TimeSource.Monotonic.markNow()
                attachmentSystemSpans = if (canReuseAttachmentMeasureIndex && incBreak != null) {
                    reuseXFrom!!.attachmentSystemSpans
                } else AttachmentLayoutIndex.crossSystemSpans(computed.staffAttachments, timeSlotMap)
                val canReuseAttachments = affectedAttachmentSystems.isNotEmpty() &&
                    cachedAttachmentLayout != null &&
                    attachmentSystemSpans.none { (start, end) ->
                        (start in affectedAttachmentSystems) != (end in affectedAttachmentSystems)
                    }
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentGuard=${attachmentGuardStart.elapsedNow()} " +
                        "spans=${attachmentSystemSpans.size} reused=${canReuseAttachmentMeasureIndex && incBreak != null}"
                }
                val perSystemExtentStart = kotlin.time.TimeSource.Monotonic.markNow()
                val extentSystems = if (canReuseAttachments) {
                    breakResult.systems.filter { it.systemIndex in affectedAttachmentSystems }
                } else breakResult.systems
                val perSysNoteExtents = breaker.perSystemNoteExtents(extentSystems)
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentNoteExtents=${perSystemExtentStart.elapsedNow()} " +
                        "systems=${extentSystems.size}/${breakResult.systems.size}"
                }
                // The local-extent index is consumed only by attachments we recompute. On the reuse path
                // that is exactly the edited system set; building it for every attachment-bearing system
                // would retain the largest O(N) half of paginate.attach even after placement was spliced.
                val postBreakIndexStart = kotlin.time.TimeSource.Monotonic.markNow()
                val restretchedIndex = noteExtentTree.index(
                    baseSlots = preBreakTimeSlotMap,
                    displaySlots = timeSlotMap,
                    systemFilter = if (canReuseAttachments) affectedAttachmentSystems
                        else AttachmentLayoutIndex.hostingSystems(computed.staffAttachments, timeSlotMap),
                )
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentIndex=${postBreakIndexStart.elapsedNow()}"
                }
                var freshAttachmentLayout: StaffAttachmentLayoutResult? = null
                val placementStart = kotlin.time.TimeSource.Monotonic.markNow()
                val restretched = if (canReuseAttachments) {
                    val candidateStart = kotlin.time.TimeSource.Monotonic.markNow()
                    val attachmentCandidates = breakResult.systems.asSequence()
                        .filter { it.systemIndex in affectedAttachmentSystems }
                        .flatMap { it.measureRange.asSequence() }
                        .flatMap { attachmentsByMeasure[it].orEmpty().asSequence() }
                        .toList()
                    com.mecon.renderer.debug.PerfLog.log("render.stage") {
                        "layout.paginate.attachmentCandidates=${candidateStart.elapsedNow()} " +
                            "candidates=${attachmentCandidates.size} total=${computed.staffAttachments.size}"
                    }
                    val freshPlacementStart = kotlin.time.TimeSource.Monotonic.markNow()
                    val fresh = staffAttachmentLayoutComputer.compute(
                        computed, timeSlotMap, perSysNoteExtents, restretchedIndex, attachOverlay,
                        systemFilter = affectedAttachmentSystems,
                        attachmentCandidates = attachmentCandidates,
                    )
                    com.mecon.renderer.debug.PerfLog.log("render.stage") {
                        "layout.paginate.attachmentPlace=${freshPlacementStart.elapsedNow()} " +
                            "placed=${fresh.placedCount}"
                    }
                    freshAttachmentLayout = fresh
                    val mergeStart = kotlin.time.TimeSource.Monotonic.markNow()
                    cachedAttachmentLayout!!.patchSystems(affectedAttachmentSystems, fresh).also {
                        com.mecon.renderer.debug.PerfLog.log("render.stage") {
                            "layout.paginate.attachmentMerge=${mergeStart.elapsedNow()}"
                        }
                    }
                } else staffAttachmentLayoutComputer.compute(
                    computed, timeSlotMap, perSysNoteExtents, restretchedIndex, attachOverlay
                )
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentPlaceMerge=${placementStart.elapsedNow()} placed=${restretched.placedCount}"
                }
                paginatedAttachmentLayout = restretched
                val tagStart = kotlin.time.TimeSource.Monotonic.markNow()
                finalAttachments = if (canReuseAttachments) {
                    reuseXFrom!!.placedAttachments.filter { it.systemIndex !in affectedAttachmentSystems } +
                        breaker.tagAttachments(freshAttachmentLayout!!.placed)
                } else {
                    breaker.tagAttachments(restretched.placed)
                }
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentTag=${tagStart.elapsedNow()} tagged=${finalAttachments.size}"
                }
                val verticalFoldStart = kotlin.time.TimeSource.Monotonic.markNow()
                val (sysWithAttach, pagesWithAttach) = breaker.applyAttachmentExtents(
                    AttachmentVerticalRequest(
                        systems = breakResult.systems,
                        pages = breakResult.pages,
                        staffTracks = staffTrackList,
                        perMeasureExtents = measureExtents,
                        perSystemAttachmentExtents = restretched.extents,
                        page = breakPage,
                        affectedSystems = if (canReuseAttachments) affectedAttachmentSystems else null,
                        cachedFinalSystems = if (canReuseAttachments) reuseXFrom?.systems else null,
                    ),
                )
                systems = sysWithAttach
                pages = pagesWithAttach
                incrementalVerticalPaginationReused = incrementalVerticalPaginationReused &&
                    breaker.lastAttachmentVerticalReused
                incrementalVerticalSystemsVisited += breaker.lastAttachmentVerticalSystemsVisited
                com.mecon.renderer.debug.PerfLog.log("render.stage") {
                    "layout.paginate.attachmentVertical=${verticalFoldStart.elapsedNow()} " +
                        "systems=${systems.size} reused=${breaker.lastAttachmentVerticalReused} " +
                        "visited=${breaker.lastAttachmentVerticalSystemsVisited}"
                }
            } else {
                systems = breakResult.systems
                pages = breakResult.pages
            }
            com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.paginate.attach=${_tPagAttach.elapsedNow()}" }

            // Refresh barline X from the stretched slots (keeps measure boundaries /
            // hit-test enrichment roughly aligned with what is drawn).
            val _tPagBarline = kotlin.time.TimeSource.Monotonic.markNow()
            finalBarlines = barlineLayouts.map { bl ->
                timeSlotMap.atTime(bl.time)?.let { bl.copy(x = it.x) } ?: bl
            }
            com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.paginate.barline=${_tPagBarline.elapsedNow()}" }
        } else {
            val maxX = timeSlotMap.all().maxOfOrNull { it.x } ?: systemStartX
            val maxMeasure = computed.barlines.maxOfOrNull { it.measureNumber }?.coerceAtLeast(1) ?: 1
            // Collapse staves hidden across the whole (single-system) score, re-stacking the survivors so
            // the vertical space closes up; a merged dashed marker fills the gap. When nothing is hidden
            // (or everything is), reuse the full stack unchanged.
            val visibleNotationTracks = staffTracks.values
                .filter { !it.isFullyHiddenOver(1..maxMeasure) }
                .ifEmpty { staffTracks.values.toList() }
            val continuousStaves = if (visibleNotationTracks.size == staffTracks.size) staffLayouts
            else staffLayoutComputer.calculateStaffYPositions(
                staffTracks = visibleNotationTracks,
                voiceEventLayouts = voiceEventLayouts,
                startY = config.topMargin + continuousAnnotationExtents.above,
                extraExtents = attachmentResult.extentsForSystem(0),
                measureExtents = measureExtents,
            ) + annotationResult.staffLayouts
            systems = listOf(
                SystemLayout(
                    systemIndex = 0,
                    pageIndex = 0,
                    measureRange = 1..maxMeasure,
                    yOffset = StaffSpace.ZERO,
                    lineStartX = systemStartX,
                    // Matches the pre-pagination staff-line width (lastSlot.x + rightMargin).
                    lineEndX = maxX + config.rightMargin,
                    staffLayouts = continuousStaves
                )
            )
            pages = emptyList()
            suppressed = emptySet()
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.paginate=${_tPaginate.elapsedNow()}" }

        // Post-break assembly: paginated annotation placement (all-system, §2.5 ④), total dims, header
        // brackets per system, and the result maps (VoiceEventLayoutMap / BarlineLayoutMap — each an
        // associateBy over the whole score).
        val _tAssemble = kotlin.time.TimeSource.Monotonic.markNow()
        // Annotation placement. Continuous mode used the global baseline (step 7). Paginated mode
        // re-resolves per system against the post-break slots (justified X + systemIndex) so chord
        // symbols etc. sit under the line they belong to rather than all under system 0.
        val annotationPlacements = if (pageGeometry.paginated)
            annotationStaffLayoutComputer.computePaginated(computed, timeSlotMap, systems)
        else annotationResult.placedElements

        // Calculate total dimensions
        val lastSlot = timeSlotMap.all().lastOrNull()
        val totalWidth = if (pageGeometry.paginated && pages.isNotEmpty()) {
            pages.maxOf { it.width }
        } else if (lastSlot != null) {
            lastSlot.x + config.rightMargin
        } else {
            pageWidth
        }

        val totalHeight = if (pageGeometry.paginated && pages.isNotEmpty()) {
            pages.last().let { it.originY + it.height }
        } else if (staffLayouts.isNotEmpty()) {
            staffLayouts.maxOf { it.bottomY } + config.bottomMargin
        } else {
            pageHeight
        }

        val finalSystems = systems.map(::headersFor)
        val allNotationStaffInfos = staffTracks.values.sortedBy { it.staffIndex }
        val postLayoutMarkers = buildList<PostLayoutMarker> {
            val pageBreaks = runtime.forcedPageBreaks
            for (beforeMeasure in runtime.forcedSystemBreaks.sorted()) {
                val system = finalSystems.firstOrNull { it.measureRange.last == beforeMeasure - 1 } ?: continue
                add(LayoutBreakMarker(
                    systemIndex = system.systemIndex,
                    beforeMeasure = beforeMeasure,
                    kind = if (beforeMeasure in pageBreaks) {
                        com.mecon.api.interaction.LayoutBreakKind.PAGE
                    } else {
                        com.mecon.api.interaction.LayoutBreakKind.SYSTEM
                    },
                ))
            }
            addAll(hiddenStaffMarkers(finalSystems, allNotationStaffInfos))
            val storedTempoIds = computed.runtime.globalTrack.tempoEvents.mapTo(HashSet()) { it.id }
            for (keyframe in computed.tempoKeyframes.filter { it.isEditorOnly && it.id in storedTempoIds }) {
                val slot = timeSlotMap.atTime(keyframe.time) ?: continue
                add(TempoKeyframeMarker(
                    systemIndex = slot.systemIndex,
                    anchorMeasure = keyframe.time.measure,
                    x = slot.x,
                    keyframe = keyframe,
                ))
            }
        }
        val result = UnifiedLayoutResult(
            timeSlotMap = timeSlotMap,
            voiceEventLayouts = voiceEventLayoutMap,
            barlineLayouts = BarlineLayoutMap.fromList(finalBarlines),
            staffLayouts = staffLayouts,
            width = totalWidth,
            height = totalHeight,
            annotationElementLayouts = annotationPlacements,
            headerOriginX = headerOriginX,
            systemStartX = systemStartX,
            placedAttachments = finalAttachments,
            paginatedAttachmentLayout = paginatedAttachmentLayout,
            systems = finalSystems,
            pages = pages,
            preAttachmentSystems = preAttachmentSystems.ifEmpty { systems },
            preAttachmentPages = preAttachmentPages.ifEmpty { pages },
            suppressedBarlineTimes = suppressed,
            suppressedClefTimes = suppressedClef,
            paginated = pageGeometry.paginated,
            titleBlock = titleBlock,
            postLayoutMarkers = postLayoutMarkers,
            resolvedTimeAxis = resolvedTimeAxis,
            preBreakTimeSlotMap = preBreakTimeSlotMap,
            preBreakMeasureWidths = measureWidths,
            preBreakMeasureExtents = measureExtents,
            measureVerticalExtentTree = measureVerticalExtentTree,
            voiceLayoutsByMeasure = voiceLayoutsByMeasure,
            noteExtentTree = noteExtentTree,
            staffAttachmentsSnapshot = computed.staffAttachments,
            staffAttachmentsByMeasure = attachmentsByMeasure,
            attachmentSystemSpans = attachmentSystemSpans,
            systemLineage = systemLineage,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "layout.assemble=${_tAssemble.elapsedNow()}" }
        return result
    }

    /**
     * One [HiddenStaffMarker] per maximal run of consecutive notation staves that collapsed out of a
     * system (fully hidden over that line and therefore absent from its [SystemLayout.staffLayouts]).
     * Consecutive hidden staves merge into a single dashed line; a present staff between two hidden ones
     * splits them into separate markers. A line whose every staff is hidden keeps its staves (see
     * [SystemBreaker.visibleStaves]) so nothing collapses there — no marker, the desktop greys it instead.
     */
    private fun hiddenStaffMarkers(
        systems: List<SystemLayout>,
        allNotationStaffInfos: List<StaffInfo>,
    ): List<HiddenStaffMarker> = buildList {
        for (system in systems) {
            val presentIdx = system.staffLayouts
                .filter { it.kind == StaffKind.NOTATION }
                .mapTo(HashSet()) { it.staffIndex }
            val hidden = allNotationStaffInfos
                .filter { it.staffIndex !in presentIdx && it.isFullyHiddenOver(system.measureRange) }
                .sortedBy { it.staffIndex }
            var i = 0
            while (i < hidden.size) {
                var j = i
                while (j + 1 < hidden.size && hidden[j + 1].staffIndex == hidden[j].staffIndex + 1) j++
                val run = hidden.subList(i, j + 1)
                add(HiddenStaffMarker(
                    systemIndex = system.systemIndex,
                    staffIndices = run.map { it.staffIndex },
                    staffTrackIds = run.map { it.trackId },
                    fromMeasure = system.measureRange.first,
                    toMeasure = system.measureRange.last,
                ))
                i = j + 1
            }
        }
    }


    /**
     * Build a VoiceEventLayout from a NoteElement.
     *
     * @param event The note layout event
     * @param staffTracks Staff track information
     * @param stemDirection The resolved stem direction for this event
     */
    private fun buildVoiceEventLayout(
        event: NoteElement,
        staffTracks: Map<TrackId, StaffInfo>,
        stemDirection: StemDirection
    ): VoiceEventLayout {
        return voiceEventLayoutBuilder.buildLayout(
            event = event,
            staffIndex = event.staffIndex,
            trackId = event.trackId,
            measureNumber = event.measureNumber,
            stemDirection = stemDirection
        )
    }

    companion object {
        /**
         * Create a computer with default configuration.
         */
        context(com.mecon.renderer.smufl.BravuraFont)
        fun default(): UnifiedLayoutComputer = UnifiedLayoutComputer()
    }
}
