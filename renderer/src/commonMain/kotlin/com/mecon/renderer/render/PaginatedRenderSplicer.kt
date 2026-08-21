package com.mecon.renderer.render

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId
import com.mecon.renderer.elements.ArticulationElement
import com.mecon.renderer.elements.BeamGroupElement
import com.mecon.renderer.elements.ElementRenderContext
import com.mecon.renderer.elements.StaffAttachmentElement
import com.mecon.renderer.elements.SlurElement
import com.mecon.renderer.elements.TieElement
import com.mecon.renderer.elements.TupletElement
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.StaffKind
import com.mecon.renderer.layout.SystemLayout
import com.mecon.renderer.layout.SystemOrigin
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.layout.UnifiedTimeSlot
import com.mecon.renderer.layout.VoiceEventLayout
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter
import com.mecon.renderer.render.spatial.YBandRouting
import com.mecon.renderer.smufl.BravuraFont

/**
 * Line-granular element splice for paginated incremental layout.
 *
 * The incremental layout has already proven the page/system partition is stable. This splicer reuses cached
 * elements on unaffected systems, regenerates affected systems, and returns a [RenderAssembly] so [RenderEngine]
 * remains the single place that mutates cached render state.
 */
context(BravuraFont)
internal class PaginatedRenderSplicer(
    private val config: RenderLayoutConfig,
    private val transformer: CoordinateTransformer,
    private val structuralElementRenderer: StructuralElementRenderer,
    private val notationElementPassRenderer: NotationElementPassRenderer,
    private val beamGroupProcessor: BeamGroupProcessor,
    private val tieLayoutComputer: TieLayoutComputer,
    private val slurLayoutComputer: SlurLayoutComputer,
    private val articulationLayoutComputer: ArticulationLayoutComputer,
    private val tupletLayoutComputer: TupletLayoutComputer,
    private val lineStartHeaderRenderer: LineStartHeaderRenderer,
    private val titleBlockRenderer: TitleBlockRenderer,
    private val annotationStaffRenderer: AnnotationStaffRenderer,
    private val postLayoutMarkerRenderer: PostLayoutMarkerRenderer,
    private val resultAssembler: RenderResultAssembler,
) {
    fun render(
        cachedLayout: UnifiedLayoutResult?,
        cachedRich: List<RichElement>,
        cachedRichRuns: List<PaginatedRichRun>,
        cachedResult: RenderResult?,
        layout: UnifiedLayoutResult,
        computed: ComputedScore,
        changeSet: ComputeChangeSet,
        hasPluginComponents: Boolean,
        nextId: () -> RenderElementId,
        isCancelled: () -> Boolean = { false },
    ): RenderAssembly? {
        fun sbail(reason: String): RenderAssembly? {
            com.mecon.renderer.debug.PerfLog.log("splice.bail") { "paginated splice bailed → full render: reason=$reason" }
            return null
        }
        if (cachedLayout == null || cachedRich.isEmpty() || cachedResult == null) return sbail("noCache")
        if (!changeSet.allowsIncrementalLayout) return sbail("notAllowsInc")
        if (!layout.paginated || !cachedLayout.paginated) return sbail("notPaginated")
        if (hasPluginComponents) return sbail("plugins")
        if (!isPaginatedSpliceable(layout)) return sbail("layoutNotSpliceable")
        if (!isPaginatedSpliceable(cachedLayout)) return sbail("cachedNotSpliceable")

        val _tGuard = kotlin.time.TimeSource.Monotonic.markNow()
        if (!cachedResult.paginatedSpliceSafe) return sbail("cachedRichUnsafe")
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "splice.guard=${_tGuard.elapsedNow().inWholeMicroseconds}us cachedRich=${cachedRich.size}"
        }

        // A reflow-incremental layout moved line breaks (the system count may even stay the same), so the
        // per-systemIndex reuse below does not apply — route to the lineage-aware reflow splice.
        if (layout.systemLineage != null) {
            return renderReflow(cachedLayout, cachedRich, layout, computed, nextId, isCancelled)
        }
        if (layout.systems.size != cachedLayout.systems.size) return sbail("systemCount")

        val window = changeSet.affectedMeasures
        val affectedSystems = layout.systems
            .filter { sys -> sys.measureRange.any { it in window } }
            .map { it.systemIndex }
            .toHashSet()
        if (affectedSystems.isEmpty()) return sbail("affectedSystemsEmpty")
        // A clef change on a line break needs no guard here. Reaching this point implies
        // changeSet.allowsIncrementalLayout (checked above), i.e. notationChanged == false, and the
        // non-reflow branch (systemLineage == null) means the partition is identical too. So every break's
        // courtesy clef is structurally identical between the cached and new layouts: an affected system
        // that carries one either re-registers its cached header or regenerates it via
        // LineStartHeaderRenderer, and an unaffected one keeps its (still-correct) cached glyph.
        // Pitch / duration edits next to a break therefore splice instead of
        // full-rendering — clef / key / time edits never reach this path (they route to the null-hint full
        // render), so the courtesy-clef configuration is constant across every splice.
        if (hasAttachmentSpanStraddlingAffectedSystems(layout, affectedSystems)) return sbail("attachmentStraddle")

        val deltaYBySystem = computeDeltaYBySystem(layout, cachedLayout) ?: return sbail("deltaYNull")
        val scale = ScaleFactor(transformer.toPixels(StaffSpace.ONE).value)
        val cachedBands = notationBands(cachedLayout)
        val _tSplit = kotlin.time.TimeSource.Monotonic.markNow()
        val split = if (cachedRichRuns.isNotEmpty()) splitCachedRuns(
            cachedRich = cachedRich,
            cachedRuns = cachedRichRuns,
            affectedSystems = affectedSystems,
            deltaYBySystem = deltaYBySystem,
            scale = scale,
        ) else splitCachedElements(
            cachedRich = cachedRich,
            cachedBands = cachedBands,
            affectedSystems = affectedSystems,
            deltaYBySystem = deltaYBySystem,
            scale = scale,
        ) ?: return sbail("splitCachedElementsNull")
        val reused = split.reused
        val removedHit = split.removedHit
        val translatedIndexUpdates = split.elementIndexUpdates

        val assembled = ArrayList<RichElement>(cachedRich.size + 16)
        assembled.addAll(structuralElementRenderer.renderStaffLineElements(layout, nextId)
            .map { RichElement(it, emptyList(), null) })
        assembled.addAll(structuralElementRenderer.renderSystemStructuralLines(layout, computed, nextId))
        val structuralPrefixEnd = assembled.size
        assembled.addAll(reused)
        val regeneratedSuffixStart = assembled.size

        val newBarlines = layout.barlineLayouts.all()
        val newMeasureBoundaries = ScoreSpatialAdapter.computeMeasureBoundaries(newBarlines, layout.width)
        val newSystemStartX = newBarlines.firstOrNull()?.x ?: config.firstSystemIndent
        val windowRich = ArrayList<RichElement>()
        val windowCollector = SpliceWindowCollector(
            assembled, windowRich, newMeasureBoundaries, newSystemStartX,
            generation = nextId().localOrdinal + 1,
        )
        val reusableLineStartHeaders = translateReusableLineStartHeaders(
            split.reusableAffectedLineStartHeaders, cachedLayout, layout, scale
        ).orEmpty()

        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "splice.splitCached=${_tSplit.elapsedNow().inWholeMilliseconds}ms cachedRich=${cachedRich.size} " +
                "affectedSystems=${affectedSystems.size} runs=${cachedRichRuns.size}"
        }

        isCancelled.throwIfCancelled() // before regenerating affected systems (spanner / glyph work)
        val _tRegen = kotlin.time.TimeSource.Monotonic.markNow()
        val geometryCapture = regenerateAffectedSystems(
            layout = layout,
            computed = computed,
            affectedSystems = affectedSystems,
            windowCollector = windowCollector,
            assembled = assembled,
            nextId = nextId,
            reusableLineStartHeaders = reusableLineStartHeaders,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "splice.regenerate=${_tRegen.elapsedNow().inWholeMilliseconds}ms windowRich=${windowRich.size}"
        }

        // Annotation staves: reuse cached annotations from unaffected systems (translated by each system's
        // ΔY — a rigid whole-system shift, so the annotation baseline shifts by the same amount as the
        // notation centre). Regenerate only the affected systems' annotations from the fresh layout.
        // splitCachedElements skips hitless TEXT_ANNOTATION elements (val hit ?: continue), so we handle
        // them here separately from the reused/removedHit partition.
        val _tAnnotations = kotlin.time.TimeSource.Monotonic.markNow()
        if (cachedRichRuns.isNotEmpty()) {
            for (run in cachedRichRuns) {
                if (run.kind != PaginatedRichRun.Kind.ANNOTATION) continue
                val sysIdx = run.systemIndex ?: continue
                if (sysIdx in affectedSystems) continue
                val dy = deltaYBySystem[sysIdx] ?: StaffSpace.ZERO
                val slice = cachedRich.subList(run.fromIndex, run.toIndexExclusive)
                if (dy == StaffSpace.ZERO) assembled.addAll(slice)
                else for (ann in slice) assembled.add(ann.translated(StaffSpace.ZERO, dy, scale))
            }
        } else for (ann in cachedRich) {
            if (ann.element.type != RenderElementType.TEXT_ANNOTATION) continue
            if (ann.element.isAlwaysRegeneratedStructure()) continue
            val sysIdx = ann.element.systemIndex ?: continue
            if (sysIdx in affectedSystems) continue
            val dy = deltaYBySystem[sysIdx] ?: StaffSpace.ZERO
            assembled.add(if (dy == StaffSpace.ZERO) ann else ann.translated(StaffSpace.ZERO, dy, scale))
        }
        if (layout.annotationElementLayouts.isNotEmpty()) {
            for (el in annotationStaffRenderer.render(layout, nextId) { placed ->
                placed.systemIndex in affectedSystems
            }) {
                assembled.add(RichElement(el, emptyList(), null))
            }
        }
        postLayoutMarkerRenderer.render(
            layout,
            nextId,
            affectedSystems,
            showMeasureNumbers = computed.runtime.viewPreferences.showMeasureNumbers,
        ).forEach(windowCollector::collectRich)
        postLayoutMarkerRenderer.renderTempoKeyframes(
            layout,
            nextId,
            layout.systems.mapTo(HashSet()) { it.systemIndex } - affectedSystems,
        ).forEach(windowCollector::collectRich)
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "splice.annotations=${_tAnnotations.elapsedNow().inWholeMilliseconds}ms cachedRich=${cachedRich.size}"
        }
        val precomputedRuns: List<PaginatedRichRun>? = if (split.reusedRuns.isNotEmpty()) buildList<PaginatedRichRun> {
            addAll(buildPaginatedRichRunsForRange(assembled, layout, 0, structuralPrefixEnd))
            addAll(split.reusedRuns.map { run ->
                run.copy(
                    fromIndex = run.fromIndex + structuralPrefixEnd,
                    toIndexExclusive = run.toIndexExclusive + structuralPrefixEnd,
                )
            })
            addAll(buildPaginatedRichRunsForRange(
                assembled, layout, regeneratedSuffixStart, assembled.size
            ))
        } else null
        val assembly = resultAssembler.assembleIncrementalPaginated(
            assembled, layout, cachedResult, removedHit, windowRich, affectedSystems, deltaYBySystem,
            translatedIndexUpdates, precomputedRuns, isCancelled
        ) ?: resultAssembler.assemble(assembled, layout, isCancelled)
        return assembly.copy(geometryCapture = geometryCapture)
    }

    /**
     * Reflow splice: an edit moved line breaks, so [UnifiedLayoutResult.systemLineage] describes — per new
     * system — whether it is a [SystemOrigin.Reuse] of a cached system (identical up to a vertical / page
     * shift; the layout was re-solved drift-free, so justified X is identical) or was
     * [SystemOrigin.Regenerate]d by the re-pack. Reuse the cached rendered elements (rigidly translated by
     * ΔY) for the reused prefix / suffix systems and regenerate only the re-packed middle band's glyphs,
     * turning a full re-render of every element into work bounded by the band. The result is assembled
     * whole (index rebuilt from scratch — O(N) ~ tens of ms, negligible next to the glyph generation that
     * reuse avoided). Returns null (caller falls back to a full render) on any unsupported shape.
     */
    private fun renderReflow(
        cachedLayout: UnifiedLayoutResult,
        cachedRich: List<RichElement>,
        layout: UnifiedLayoutResult,
        computed: ComputedScore,
        nextId: () -> RenderElementId,
        isCancelled: () -> Boolean,
    ): RenderAssembly? {
        fun sbail(reason: String): RenderAssembly? {
            com.mecon.renderer.debug.PerfLog.log("splice.bail") { "reflow splice bailed → full render: reason=$reason" }
            return null
        }
        val lineage = layout.systemLineage ?: return sbail("noLineage")
        if (lineage.size != layout.systems.size) return sbail("lineageSizeMismatch")
        // Annotation staves (chord symbols etc.) are not yet handled on the reflow path.
        if (layout.annotationElementLayouts.isNotEmpty() ||
            cachedRich.any { it.element.type == RenderElementType.TEXT_ANNOTATION }) return sbail("reflowAnnotations")

        // New systems to regenerate = the re-packed band, PLUS the reused system just before it: that
        // system's courtesy clef (if any) warns of the *next* system's clef, whose opening measure changed
        // when the band was re-packed, so its cached courtesy clef could be stale — regenerate it fresh.
        val regenerateNew = HashSet<Int>()
        for ((newIdx, origin) in lineage.withIndex()) if (origin is SystemOrigin.Regenerate) regenerateNew.add(newIdx)
        if (regenerateNew.isEmpty()) return sbail("noRegenBand")
        regenerateNew.minOrNull()?.let { if (it - 1 >= 0) regenerateNew.add(it - 1) }

        // Reused new systems ↔ their cached source; any cached system not reused is dropped (regenerated).
        val reusedCachedToNew = HashMap<Int, Int>()
        for ((newIdx, origin) in lineage.withIndex()) {
            if (newIdx in regenerateNew) continue
            if (origin is SystemOrigin.Reuse) reusedCachedToNew[origin.cachedIndex] = newIdx
        }
        val cachedDropped = cachedLayout.systems.map { it.systemIndex }
            .filter { it !in reusedCachedToNew }.toHashSet()

        // An attachment span crossing the reused/regenerated boundary would misalign → full render.
        if (hasAttachmentSpanStraddlingAffectedSystems(layout, regenerateNew)) return sbail("reflowAttachmentStraddle")

        // ΔY per reused cached system = its new system's notation centre − the cached centre. X is identical
        // (both drift-free, same measures / justification), so only Y (and page) shift.
        val newSysByIdx = layout.systems.associateBy { it.systemIndex }
        val cachedSysByIdx = cachedLayout.systems.associateBy { it.systemIndex }
        val deltaYByCached = HashMap<Int, StaffSpace>()
        for ((cachedIdx, newIdx) in reusedCachedToNew) {
            val newCy = notationCenterY(newSysByIdx[newIdx] ?: return sbail("missingNewSys")) ?: return sbail("missingNewCy")
            val cachedCy = notationCenterY(cachedSysByIdx[cachedIdx] ?: return sbail("missingCachedSys")) ?: return sbail("missingCachedCy")
            deltaYByCached[cachedIdx] = newCy - cachedCy
        }

        val scale = ScaleFactor(transformer.toPixels(StaffSpace.ONE).value)
        val cachedBands = notationBands(cachedLayout)
        // Reuse the existing per-band splitter, keyed by CACHED system index: dropped cached systems become
        // "affected" (→ removedHit, regenerated), reused ones are translated by their ΔY.
        val (reused, _) = splitCachedElements(
            cachedRich, cachedBands, cachedDropped,
            deltaYBySystem = deltaYByCached, scale = scale,
        )
            ?: return sbail("splitNull")

        val assembled = ArrayList<RichElement>(cachedRich.size + 16)
        assembled.addAll(structuralElementRenderer.renderStaffLineElements(layout, nextId)
            .map { RichElement(it, emptyList(), null) })
        assembled.addAll(structuralElementRenderer.renderSystemStructuralLines(layout, computed, nextId))
        assembled.addAll(reused)

        val newBarlines = layout.barlineLayouts.all()
        val newMeasureBoundaries = ScoreSpatialAdapter.computeMeasureBoundaries(newBarlines, layout.width)
        val newSystemStartX = newBarlines.firstOrNull()?.x ?: config.firstSystemIndent
        val windowCollector = SpliceWindowCollector(
            assembled, ArrayList(), newMeasureBoundaries, newSystemStartX,
            generation = nextId().localOrdinal + 1,
        )

        isCancelled.throwIfCancelled()
        regenerateAffectedSystems(
            layout = layout,
            computed = computed,
            affectedSystems = regenerateNew,
            windowCollector = windowCollector,
            assembled = assembled,
            nextId = nextId,
        )

        postLayoutMarkerRenderer.render(
            layout,
            nextId,
            regenerateNew,
            showMeasureNumbers = computed.runtime.viewPreferences.showMeasureNumbers,
        ).forEach(windowCollector::collectRich)
        postLayoutMarkerRenderer.renderTempoKeyframes(
            layout,
            nextId,
            layout.systems.mapTo(HashSet()) { it.systemIndex } - regenerateNew,
        ).forEach(windowCollector::collectRich)

        return resultAssembler.assemble(assembled, layout, isCancelled)
    }

    private fun regenerateAffectedSystems(
        layout: UnifiedLayoutResult,
        computed: ComputedScore,
        affectedSystems: Set<Int>,
        windowCollector: SpliceWindowCollector,
        assembled: MutableList<RichElement>,
        nextId: () -> RenderElementId,
        reusableLineStartHeaders: List<RichElement> = emptyList(),
    ): IncrementalGeometryCapture {
        val staffLayoutByIndex = layout.staffLayouts.associateBy { it.staffIndex }
        val systemByIndex = layout.systems.associateBy { it.systemIndex }
        val systemIndexByMeasure = HashMap<Int, Int>()
        for (system in layout.systems) {
            for (measure in system.measureRange) systemIndexByMeasure[measure] = system.systemIndex
        }
        // Collapsed (fully-hidden) staff ⇒ null ⇒ skip; no flat-stack fallback (see FullScoreRenderer).
        fun staffFor(systemIndex: Int, staffIndex: Int) =
            layout.staffForSystem(systemIndex, staffIndex)

        val affectedRanges = layout.systems.filter { it.systemIndex in affectedSystems }.map { it.measureRange }
        fun measureAffected(measure: Int) = affectedRanges.any { measure in it }

        // Union of all affected systems' measure ranges, used as measureFilter for the beam pass and the
        // spanner computers so they scan only the affected window instead of the full O(N) event set.
        val affectedMeasureFilter: IntRange = run {
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (range in affectedRanges) {
                if (range.first < lo) lo = range.first
                if (range.last > hi) hi = range.last
            }
            if (lo <= hi) lo..hi else IntRange.EMPTY
        }

        val slotFilter: (UnifiedTimeSlot) -> Boolean = { it.systemIndex in affectedSystems }
        val multiStaff = layout.systems.any {
            it.staffLayouts.count { staff -> staff.kind == StaffKind.NOTATION } >= 2
        }
        val slotsStart = kotlin.time.TimeSource.Monotonic.markNow()
        notationElementPassRenderer.renderTimeSlotElements(
            layout, computed, systemByIndex, ::staffFor, nextId, windowCollector::collect, slotFilter,
            includeBarlines = !multiStaff,
        )
        if (multiStaff) {
            notationElementPassRenderer.renderTimeSlotElements(
                layout, computed, systemByIndex, ::staffFor, nextId, windowCollector::collect,
                slotFilter = { it.systemIndex in affectedSystems }, includeNonBarlines = false,
            )
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.slots=${slotsStart.elapsedNow().inWholeMilliseconds}ms"
        }

        // Beams only feed elements in their own measure (stems via voiceFilter, beam render data via the
        // measureAffected check, slur/tuplet stem adjustments via the same window), so process only the
        // affected window instead of every group in the score (see BeamGroupProcessor.processBeamGroups).
        val affectedVoiceLayouts = buildList {
            for (measure in affectedMeasureFilter) {
                addAll(layout.voiceLayoutsByMeasure[measure].orEmpty())
            }
        }
        val affectedBeamGroupIds = affectedVoiceLayouts
            .mapNotNullTo(LinkedHashSet()) { it.beamInfo?.groupId }
        val beamStart = kotlin.time.TimeSource.Monotonic.markNow()
        val beamProc = beamGroupProcessor.processBeamGroups(
            layout, staffLayoutByIndex,
            measureFilter = affectedMeasureFilter,
            groupIds = affectedBeamGroupIds,
            geometry = computed.runtime.geometry?.beams ?: emptyMap(),
        )
        val dummyCtx = ElementRenderContext(RelativePoint.ZERO, transformer, nextId, computed)
        val voiceFilter: (VoiceEventLayout) -> Boolean = { measureAffected(it.measureNumber) }
        notationElementPassRenderer.renderStemFlagElements(
            layout, beamProc, ::staffFor, dummyCtx, windowCollector::collect, voiceFilter,
            voiceLayouts = affectedVoiceLayouts,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.beamStem=${beamStart.elapsedNow().inWholeMilliseconds}ms groups=${beamProc.beamGroupRenderData.size}"
        }
        val headerStart = kotlin.time.TimeSource.Monotonic.markNow()
        val lineStartHeaderStart = kotlin.time.TimeSource.Monotonic.markNow()
        val expectedLineStartHeaders = layout.systems.sumOf { system ->
            if (system.systemIndex !in affectedSystems) 0
            else system.lineStartHeaders.sumOf { header ->
                (if (header.clef?.geometryList?.isNotEmpty() == true) 1 else 0) +
                    (if (header.keySignature?.geometryList?.isNotEmpty() == true) 1 else 0)
            } + system.lineEndClefs.size
        }
        if (reusableLineStartHeaders.size == expectedLineStartHeaders) {
            reusableLineStartHeaders.forEach(windowCollector::collectRich)
        } else {
            lineStartHeaderRenderer.render(
                layout, computed, ::staffFor, nextId, windowCollector::collect
            ) { it.systemIndex in affectedSystems }
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.header.lineStart=${lineStartHeaderStart.elapsedNow().inWholeMicroseconds}us " +
                "reused=${reusableLineStartHeaders.size == expectedLineStartHeaders} elements=$expectedLineStartHeaders"
        }

        val headerBandsStart = kotlin.time.TimeSource.Monotonic.markNow()
        val affectedYWindows = affectedSystemYWindows(layout, affectedSystems)
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.header.bands=${headerBandsStart.elapsedNow().inWholeMicroseconds}us " +
                "windows=${affectedYWindows.size}"
        }
        fun affectedByY(y: StaffSpace): Boolean = affectedYWindows.any { it.contains(y.value) }

        // Brackets and labels belong to one system and carry no score-content dependency. Unaffected
        // systems retain their cached elements (translated by deltaY in splitCached*); only the edited
        // systems need fresh geometry. Staff/system lines remain on the stricter whole-score regeneration
        // path because their extent is the structural splice boundary.
        val headerBracketsStart = kotlin.time.TimeSource.Monotonic.markNow()
        val headerBracketsAndLabels = structuralElementRenderer.renderHeaderBracketsAndLabels(layout, nextId) {
            it.systemIndex in affectedSystems
        }
        assembled.addAll(
            headerBracketsAndLabels.map { RichElement(it, emptyList(), null) }
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.header.brackets=${headerBracketsStart.elapsedNow().inWholeMicroseconds}us " +
                "elements=${headerBracketsAndLabels.size}"
        }
        val headerTitleStart = kotlin.time.TimeSource.Monotonic.markNow()
        layout.titleBlock?.let { block ->
            assembled.addAll(titleBlockRenderer.render(block, nextId).map { RichElement(it, emptyList(), null) })
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.header.title=${headerTitleStart.elapsedNow().inWholeMicroseconds}us " +
                "lines=${layout.titleBlock?.lines?.size ?: 0}"
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.headerStructure=${headerStart.elapsedNow().inWholeMilliseconds}ms"
        }

        val layoutQuery = LayoutQuery(layout, staffLayoutByIndex, computed)
        for (groupData in beamProc.beamGroupRenderData) {
            val repY = groupData.beamNoteInfos.firstOrNull()?.stemTipY ?: continue
            if (!affectedByY(repY)) continue
            val beamGroupElement = BeamGroupElement(
                notes = groupData.beamNoteInfos,
                stemDirection = groupData.stemDirection,
                beamThickness = config.engravingDefaults.beamThickness * groupData.noteScale.value,
                beamSpacing = config.engravingDefaults.beamSpacing * groupData.noteScale.value,
                stemThickness = config.engravingDefaults.stemThickness * groupData.noteScale.value,
            )
            windowCollector.collect(beamGroupElement.render(dummyCtx), groupData.staffIndex,
                systemIndexByMeasure[groupData.measureNumber] ?: 0)
        }

        // Pass measureFilter to each computer so only window events are scanned.
        // Post-filters (measureAffected / affectedByY) are kept: for multi-system layouts,
        // cross-system tie/slur stubs from the same source event can land on unaffected systems.
        val articulationStart = kotlin.time.TimeSource.Monotonic.markNow()
        val articulationLayouts = articulationLayoutComputer.computeArticulationLayouts(
            computed, layoutQuery, computed.runtime.geometry, measureFilter = affectedMeasureFilter
        )
        for (articulation in articulationLayouts.values) {
            if (!measureAffected(articulation.measureNumber)) continue
            windowCollector.collect(ArticulationElement(articulation).render(dummyCtx), articulation.staffIndex,
                systemIndexByMeasure[articulation.measureNumber] ?: 0)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.articulation=${articulationStart.elapsedNow().inWholeMilliseconds}ms layouts=${articulationLayouts.size}"
        }
        val tieStart = kotlin.time.TimeSource.Monotonic.markNow()
        val tieLayouts = tieLayoutComputer.computeTieLayouts(
            computed,
            layoutQuery,
            measureFilter = affectedMeasureFilter,
            geometry = computed.runtime.geometry,
        )
        for (tie in tieLayouts) {
            if (!affectedByY(tie.start.y)) continue
            windowCollector.collect(TieElement(tie, config).render(dummyCtx), tie.staffIndex,
                systemIndexByMeasure[tie.measureNumber] ?: 0)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.tie=${tieStart.elapsedNow().inWholeMilliseconds}ms"
        }
        val slurStart = kotlin.time.TimeSource.Monotonic.markNow()
        val slurLayouts = if (computed.slurs.isNotEmpty()) {
            slurLayoutComputer.computeSlurLayouts(
                computed,
                layoutQuery,
                articulationLayouts,
                stemAdjustments = beamProc.stemAdjustments,
                beamGroups = beamProc.beamGroupRenderData,
                geometry = computed.runtime.geometry,
                measureFilter = affectedMeasureFilter,
            )
        } else {
            emptyList()
        }
        for (slur in slurLayouts) {
                if (!affectedByY(slur.start.y)) continue
                windowCollector.collect(SlurElement(slur, config).render(dummyCtx), slur.staffIndex,
                    slur.systemIndex)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.slur=${slurStart.elapsedNow().inWholeMilliseconds}ms layouts=${slurLayouts.size}"
        }
        val tupletStart = kotlin.time.TimeSource.Monotonic.markNow()
        for (tuplet in tupletLayoutComputer.computeTupletLayouts(
            computed, layoutQuery,
            stemAdjustments = beamProc.stemAdjustments,
            measureFilter = affectedMeasureFilter,
        )) {
            if (!measureAffected(tuplet.measureNumber)) continue
            windowCollector.collect(TupletElement(tuplet, config).render(dummyCtx), tuplet.staffIndex,
                systemIndexByMeasure[tuplet.measureNumber] ?: 0)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.tuplet=${tupletStart.elapsedNow().inWholeMilliseconds}ms"
        }

        val attachmentStart = kotlin.time.TimeSource.Monotonic.markNow()
        for (placed in layout.placedAttachments) {
            if (placed.systemIndex !in affectedSystems) continue
            val staffLayout = staffFor(placed.systemIndex, placed.staffIndex) ?: continue
            val attachCtx = ElementRenderContext(
                offset = RelativePoint(StaffSpace.ZERO, staffLayout.centerY),
                transformer = transformer,
                idGenerator = nextId,
                computedScore = computed,
            )
            windowCollector.collect(StaffAttachmentElement(placed).render(attachCtx), placed.staffIndex, placed.systemIndex)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "regenerate.attachment=${attachmentStart.elapsedNow().inWholeMilliseconds}ms"
        }
        return IncrementalGeometryCapture(articulationLayouts, tieLayouts, slurLayouts)
    }

    private fun splitCachedElements(
        cachedRich: List<RichElement>,
        cachedBands: List<SystemBand>,
        affectedSystems: Set<Int>,
        deltaYBySystem: Map<Int, StaffSpace>,
        scale: ScaleFactor,
    ): CachedSplit? {
        val reused = ArrayList<RichElement>(cachedRich.size)
        val removedHit = ArrayList<RichElement>()
        val elementIndexUpdates = ArrayList<RichElement>()
        val reusableAffectedLineStartHeaders = ArrayList<RichElement>()
        val allDeltaZero = deltaYBySystem.values.all { it == StaffSpace.ZERO }
        val affectedIdx = cachedBands.indices.filter { cachedBands[it].systemIndex in affectedSystems }
        val contiguous = affectedIdx.isNotEmpty() &&
            affectedIdx.last() - affectedIdx.first() == affectedIdx.size - 1

        if (allDeltaZero && contiguous) {
            val firstAffected = affectedIdx.first()
            val lastAffected = affectedIdx.last()
            val loBound = if (firstAffected == 0) {
                Float.NEGATIVE_INFINITY
            } else {
                (cachedBands[firstAffected - 1].bottom + cachedBands[firstAffected].top) / 2f
            }
            val hiBound = if (lastAffected == cachedBands.size - 1) {
                Float.POSITIVE_INFINITY
            } else {
                (cachedBands[lastAffected].bottom + cachedBands[lastAffected + 1].top) / 2f
            }
            for (rich in cachedRich) {
                // Staff/system lines are regenerated wholesale above on every paginated splice.
                // Some full-render collectors attach a hit registration to these visual lines, so testing
                // only `hit == null` would accidentally retain the old copy and append a fresh copy each
                // frame (large scores grew by ~1,700 elements per edit). Never admit them to `reused`.
                if (rich.element.isAlwaysRegeneratedStructure()) continue
                val hit = rich.hit
                if (hit == null) {
                    val systemIndex = rich.element.systemIndex
                    if ((rich.element.type == RenderElementType.BARLINE ||
                            rich.element.isReusableSystemStructure()) &&
                        systemIndex != null && systemIndex !in affectedSystems
                    ) reused.add(rich)
                    continue
                }
                val systemIndex = rich.element.systemIndex
                if (systemIndex != null) {
                    if (systemIndex in affectedSystems) {
                        removedHit.add(rich)
                        if (rich.element.isReusableLineStartHeader()) {
                            reusableAffectedLineStartHeaders.add(rich)
                        }
                    } else {
                        reused.add(rich)
                    }
                    continue
                }
                if (hit.relativeHitBox.center.y.value in loBound..hiBound) {
                    removedHit.add(rich)
                } else {
                    reused.add(rich)
                }
            }
            return CachedSplit(
                reused, removedHit, emptyList(), reusableAffectedLineStartHeaders, emptyList(),
            )
        }

        // Everything above the topmost affected system is unmoved (Δy = 0) and unaffected: the height change
        // in an affected line only propagates downward (verticalPass stacks each line from the previous line's
        // bottom). Such elements reuse verbatim without routing. The boundary is the same midpoint nearestSorted
        // would route by (see the fast path's loBound), so this never disagrees with per-element routing.
        val skipAboveBoundary = if (contiguous && affectedIdx.first() > 0) {
            val fa = affectedIdx.first()
            (cachedBands[fa - 1].bottom + cachedBands[fa].top) / 2f
        } else {
            Float.NEGATIVE_INFINITY
        }
        fun reuse(rich: RichElement, dy: StaffSpace) {
            if (dy == StaffSpace.ZERO) {
                reused.add(rich)
            } else {
                val translated = rich.translated(StaffSpace.ZERO, dy, scale)
                reused.add(translated)
                if (translated.sections.isNotEmpty()) elementIndexUpdates.add(translated)
            }
        }
        for (rich in cachedRich) {
            if (rich.element.isAlwaysRegeneratedStructure()) continue
            val hit = rich.hit
            if (hit == null) {
                val systemIndex = rich.element.systemIndex
                if ((rich.element.type == RenderElementType.BARLINE ||
                        rich.element.isReusableSystemStructure()) && systemIndex != null &&
                    systemIndex !in affectedSystems
                ) {
                    val dy = deltaYBySystem[systemIndex] ?: StaffSpace.ZERO
                    reuse(rich, dy)
                }
                continue
            }
            // `relativeHitBox` is score-relative for ordinary notation, but paginated collectors may
            // preserve page-local Y for some element families. On page 1 both coordinate spaces share
            // the same origin; on later pages a geometric band lookup can therefore route cached notes
            // to the wrong system and retain stale NOTEHEAD/STEM/REST elements on every edit. The render
            // element's system identity is authoritative whenever available; Y routing remains only for
            // legacy/global elements that have no system metadata.
            val indexedSystem = rich.element.systemIndex
            if (indexedSystem != null) {
                if (indexedSystem in affectedSystems) {
                    removedHit.add(rich)
                    if (rich.element.isReusableLineStartHeader()) {
                        reusableAffectedLineStartHeaders.add(rich)
                    }
                } else {
                    val dy = deltaYBySystem[indexedSystem] ?: StaffSpace.ZERO
                    reuse(rich, dy)
                }
                continue
            }
            val y = hit.relativeHitBox.center.y.value
            if (y <= skipAboveBoundary) {
                reused.add(rich)
                continue
            }
            val band = YBandRouting.nearestSorted(
                cachedBands, y, { it.top }, { it.bottom }
            ) ?: return null
            if (band.systemIndex in affectedSystems) {
                removedHit.add(rich)
            } else {
                val dy = deltaYBySystem[band.systemIndex] ?: StaffSpace.ZERO
                reuse(rich, dy)
            }
        }
        return CachedSplit(
            reused, removedHit, emptyList(), reusableAffectedLineStartHeaders, elementIndexUpdates,
        )
    }

    private data class CachedSplit(
        val reused: List<RichElement>,
        val removedHit: List<RichElement>,
        /** Runs relative to [reused]. */
        val reusedRuns: List<PaginatedRichRun>,
        val reusableAffectedLineStartHeaders: List<RichElement>,
        val elementIndexUpdates: List<RichElement>,
    )

    private fun splitCachedRuns(
        cachedRich: List<RichElement>,
        cachedRuns: List<PaginatedRichRun>,
        affectedSystems: Set<Int>,
        deltaYBySystem: Map<Int, StaffSpace>,
        scale: ScaleFactor,
    ): CachedSplit {
        val reused = ArrayList<RichElement>(cachedRich.size)
        val removedHit = ArrayList<RichElement>()
        val reusedRuns = ArrayList<PaginatedRichRun>()
        val reusableAffectedLineStartHeaders = ArrayList<RichElement>()
        val elementIndexUpdates = ArrayList<RichElement>()
        for (run in cachedRuns) {
            if (run.kind != PaginatedRichRun.Kind.SYSTEM) continue
            val systemIndex = run.systemIndex ?: continue
            val slice = cachedRich.subList(run.fromIndex, run.toIndexExclusive)
            if (systemIndex in affectedSystems) {
                for (rich in slice) {
                    // Keep the complete removed system slice, not only hittables: the spatial
                    // updater filters `hit` itself, while the O(1) element-id index must evict
                    // hitless structural elements from the replaced systems as well.
                    removedHit.add(rich)
                    if (rich.element.isReusableLineStartHeader()) {
                        reusableAffectedLineStartHeaders.add(rich)
                    }
                }
                continue
            }
            val dy = deltaYBySystem[systemIndex] ?: StaffSpace.ZERO
            val start = reused.size
            if (dy == StaffSpace.ZERO) reused.addAll(slice)
            else for (rich in slice) {
                val translated = rich.translated(StaffSpace.ZERO, dy, scale)
                reused.add(translated)
                if (translated.sections.isNotEmpty()) elementIndexUpdates.add(translated)
            }
            reusedRuns += run.copy(
                fromIndex = start,
                toIndexExclusive = reused.size,
                bounds = if (dy == StaffSpace.ZERO) run.bounds else run.bounds.copy(
                    origin = run.bounds.origin.copy(y = run.bounds.origin.y + scale.toPixels(dy))
                ),
            )
        }
        return CachedSplit(
            reused, removedHit, reusedRuns, reusableAffectedLineStartHeaders, elementIndexUpdates,
        )
    }

    private fun translateReusableLineStartHeaders(
        cached: List<RichElement>,
        cachedLayout: UnifiedLayoutResult,
        layout: UnifiedLayoutResult,
        scale: ScaleFactor,
    ): List<RichElement>? = buildList {
        for (rich in cached) {
            val systemIndex = rich.element.systemIndex ?: return null
            val staffIndex = rich.hit?.staffIndex ?: rich.element.staffIndex ?: return null
            val oldCenter = cachedLayout.staffForSystem(systemIndex, staffIndex)?.centerY ?: return null
            val newCenter = layout.staffForSystem(systemIndex, staffIndex)?.centerY ?: return null
            val dy = newCenter - oldCenter
            add(if (dy == StaffSpace.ZERO) rich else rich.translated(StaffSpace.ZERO, dy, scale))
        }
    }

    private fun computeDeltaYBySystem(
        layout: UnifiedLayoutResult,
        cachedLayout: UnifiedLayoutResult,
    ): Map<Int, StaffSpace>? {
        val cachedSysById = cachedLayout.systems.associateBy { it.systemIndex }
        val deltaYBySystem = HashMap<Int, StaffSpace>()
        for (system in layout.systems) {
            val newCy = notationCenterY(system) ?: return null
            val cachedCy = cachedSysById[system.systemIndex]?.let { notationCenterY(it) } ?: return null
            deltaYBySystem[system.systemIndex] = newCy - cachedCy
        }
        return deltaYBySystem
    }

    private fun hasAttachmentSpanStraddlingAffectedSystems(
        layout: UnifiedLayoutResult,
        affectedSystems: Set<Int>,
    ): Boolean {
        val systemsByAttachment = HashMap<EventId, MutableSet<Int>>()
        for (placed in layout.placedAttachments) {
            systemsByAttachment.getOrPut(placed.attachment.id) { HashSet() }.add(placed.systemIndex)
        }
        return systemsByAttachment.values.any { systems ->
            systems.size > 1 && systems.any { it in affectedSystems } && systems.any { it !in affectedSystems }
        }
    }

    private fun notationCenterY(system: SystemLayout): StaffSpace? =
        system.staffLayouts.firstOrNull { it.kind == StaffKind.NOTATION }?.centerY

    private fun notationBands(layout: UnifiedLayoutResult): List<SystemBand> =
        layout.systems.mapNotNull(::notationBand)

    private fun notationBand(system: SystemLayout): SystemBand? {
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (staff in system.staffLayouts) {
            if (staff.kind != StaffKind.NOTATION) continue
            top = minOf(top, staff.contentTopY.value)
            bottom = maxOf(bottom, staff.contentBottomY.value)
        }
        return if (top == Float.POSITIVE_INFINITY) null else SystemBand(system.systemIndex, top, bottom)
    }

    /**
     * Y regions that [YBandRouting.nearestSorted] would route to an affected system. Only affected systems
     * and their immediate neighbours are inspected, avoiding a whole-score band list for every splice.
     * Gap ties belong to the preceding system, hence the lower boundary is exclusive and the upper one
     * inclusive.
     */
    private fun affectedSystemYWindows(
        layout: UnifiedLayoutResult,
        affectedSystems: Set<Int>,
    ): List<AffectedSystemYWindow> = buildList {
        for (index in layout.systems.indices) {
            val system = layout.systems[index]
            if (system.systemIndex !in affectedSystems) continue
            val band = notationBand(system) ?: continue
            val lowerExclusive = if (index == 0) {
                Float.NEGATIVE_INFINITY
            } else {
                val previous = notationBand(layout.systems[index - 1]) ?: continue
                (previous.bottom + band.top) / 2f
            }
            val upperInclusive = if (index == layout.systems.lastIndex) {
                Float.POSITIVE_INFINITY
            } else {
                val next = notationBand(layout.systems[index + 1]) ?: continue
                (band.bottom + next.top) / 2f
            }
            add(AffectedSystemYWindow(lowerExclusive, upperInclusive))
        }
    }

    private fun isPaginatedSpliceable(layout: UnifiedLayoutResult): Boolean {
        if (!layout.paginated || layout.systems.isEmpty()) return false
        // A clef change on a line break (suppressedClefTimes / lineEndClefs) does not disqualify the score:
        // on the incremental (non-reflow) path notation is unchanged and the partition is identical, so
        // every break's courtesy clef is constant and is regenerated / reused correctly per system in
        // render() (see the note there) — no per-edit bail is needed even for edits on the break line.
        // annotationElementLayouts is allowed: annotations carry no hittable/section registration and are
        // regenerated wholesale from the fresh layout in render() (per-system positions already baked in).
        return layout.systems.all { system ->
            system.staffLayouts.count { it.kind == StaffKind.NOTATION } >= 1
        }
    }

    private fun RenderElement.isAlwaysRegeneratedStructure(): Boolean =
        metadata[ALWAYS_REGENERATED_STRUCTURE] == "true"

    private fun RenderElement.isReusableSystemStructure(): Boolean =
        metadata[REUSABLE_SYSTEM_STRUCTURE] == "true"

    private fun RenderElement.isReusableLineStartHeader(): Boolean =
        metadata[REUSABLE_LINE_START_HEADER] == "true"
}

/** Cached full-render invariant consumed by [PaginatedRenderSplicer] in O(1). */
internal fun RichElement.isPaginatedSpliceSafe(): Boolean {
    val type = element.type
    return if (hit == null) {
        type == RenderElementType.STAFF || type == RenderElementType.STAFF_LINE ||
            type == RenderElementType.BARLINE ||
            type == RenderElementType.SYSTEM_BRACKET || type == RenderElementType.SYSTEM_BRACE ||
            type == RenderElementType.TEXT_ANNOTATION || type == RenderElementType.EDITOR_MARKER ||
            type == RenderElementType.MEASURE
    } else {
        // RelativeHitShape has an explicit translation contract and follows reused system runs.
        isPaginatedSpliceableType(type)
    }
}

/**
 * Built-in element families regenerated by [PaginatedRenderSplicer.regenerateAffectedSystems] or
 * [PostLayoutMarkerRenderer]. This list is intentionally wider than the continuous splicer's list:
 * the paginated path has system ownership metadata and a dedicated attachment/marker regeneration
 * pass, so navigation, tempo, rehearsal, pedal and fermata elements do not require a full-score bail.
 */
private fun isPaginatedSpliceableType(type: RenderElementType): Boolean =
    isSpliceableType(type) || when (type) {
        RenderElementType.ORNAMENT,
        RenderElementType.FERMATA,
        RenderElementType.NAVIGATION_MARK,
        RenderElementType.LYRIC,
        RenderElementType.TEMPO_MARKING,
        RenderElementType.REHEARSAL_MARK,
        RenderElementType.PEDAL,
        RenderElementType.EDITOR_MARKER -> true
        else -> false
    }

private data class SystemBand(
    val systemIndex: Int,
    val top: Float,
    val bottom: Float,
)

private data class AffectedSystemYWindow(
    val lowerExclusive: Float,
    val upperInclusive: Float,
) {
    fun contains(y: Float): Boolean = y > lowerExclusive && y <= upperInclusive
}
