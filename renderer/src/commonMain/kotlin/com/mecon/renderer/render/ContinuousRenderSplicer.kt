package com.mecon.renderer.render

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.primitive.EventId
import com.mecon.renderer.elements.ArticulationElement
import com.mecon.renderer.elements.BeamGroupElement
import com.mecon.renderer.elements.ElementRenderContext
import com.mecon.renderer.elements.SlurElement
import com.mecon.renderer.elements.StaffAttachmentElement
import com.mecon.renderer.elements.TieElement
import com.mecon.renderer.elements.TupletElement
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.StaffKind
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.layout.UnifiedTimeSlot
import com.mecon.renderer.layout.VoiceEventLayout
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter
import com.mecon.renderer.smufl.BravuraFont

/**
 * Measure-granular element splice for the continuous, single-system fast path.
 *
 * The splicer owns the equivalence guards and prefix/window/tail classification for continuous layout.
 * It returns a [RenderAssembly] so [RenderEngine] remains the single place that mutates cached render state.
 */
context(BravuraFont)
internal class ContinuousRenderSplicer(
    private val config: RenderLayoutConfig,
    private val transformer: CoordinateTransformer,
    private val structuralElementRenderer: StructuralElementRenderer,
    private val notationElementPassRenderer: NotationElementPassRenderer,
    private val beamGroupProcessor: BeamGroupProcessor,
    private val articulationLayoutComputer: ArticulationLayoutComputer,
    private val tupletLayoutComputer: TupletLayoutComputer,
    private val tieLayoutComputer: TieLayoutComputer,
    private val slurLayoutComputer: SlurLayoutComputer,
    private val annotationStaffRenderer: AnnotationStaffRenderer,
    private val postLayoutMarkerRenderer: PostLayoutMarkerRenderer,
    private val resultAssembler: RenderResultAssembler,
) {
    fun render(
        cachedLayout: UnifiedLayoutResult?,
        cachedRich: List<RichElement>,
        cachedResult: RenderResult?,
        layout: UnifiedLayoutResult,
        computed: ComputedScore,
        changeSet: ComputeChangeSet,
        hasPluginComponents: Boolean,
        nextId: () -> RenderElementId,
        isCancelled: () -> Boolean = { false },
    ): RenderAssembly? {
        if (cachedLayout == null || cachedRich.isEmpty()) return null
        if (!changeSet.allowsIncrementalLayout) return null
        if (layout.paginated || cachedLayout.paginated) return null
        if (hasPluginComponents) return null
        if (!isSpliceableLayout(layout) || !isSpliceableLayout(cachedLayout)) return null
        // Annotation staves (plugin chord symbols etc.) are spliceable: they carry no hittable/section
        // registration (see FullScoreRenderer's addElements path), so they never touch the spatial/section
        // index splice — they are dropped from the cached frame and regenerated wholesale from `layout`.
        if (cachedRich.any {
                (!isSpliceableType(it.element.type) &&
                    it.element.type != RenderElementType.TEXT_ANNOTATION &&
                    it.element.type != RenderElementType.EDITOR_MARKER) ||
                    it.hit?.customIntersect != null
            }) return null

        val window = changeSet.affectedMeasures

        // Beams / tuplets / articulations are barline-local, so they are regenerated per-window below and
        // reused (translated) elsewhere. Ties and slurs are the only spanners that can straddle the window
        // boundary — one endpoint in a reused prefix/tail measure, the other in the regenerated window —
        // where the rigid prefix/tail translation (prefix Δx=0, tail Δx=δ) would move the two ends by
        // different amounts and skew the curve. A spanner wholly inside one zone is safe (reused or
        // regenerated as a unit); only a boundary-straddling one forces a full render.
        if (anySpannerStraddlesWindow(computed, window) ||
            anyAttachmentStraddlesWindow(computed, window)
        ) return null

        val cachedSlots = cachedLayout.timeSlotMap.all()
        val newSlots = layout.timeSlotMap.all()

        val startThr = boundaryThreshold(cachedSlots, window.first, before = true)
        val endThrCached = boundaryThreshold(cachedSlots, window.last, before = false)
        if (startThr.value.isInfinite() && startThr.value > 0f) return null

        val tailMinCached = cachedSlots.filter { it.time.measure > window.last }.minOfOrNull { it.x }
        val tailMinNew = newSlots.filter { it.time.measure > window.last }.minOfOrNull { it.x }
        val deltaX = if (tailMinCached != null && tailMinNew != null) tailMinNew - tailMinCached else StaffSpace.ZERO

        val cachedCenterY = cachedLayout.systems.first().staffLayouts
            .first { it.kind == StaffKind.NOTATION }.centerY
        val newCenterY = layout.systems.first().staffLayouts
            .first { it.kind == StaffKind.NOTATION }.centerY
        val deltaY = newCenterY - cachedCenterY

        val scale = ScaleFactor(transformer.toPixels(StaffSpace.ONE).value)

        val prefix = ArrayList<RichElement>()
        val tail = ArrayList<RichElement>()
        val removedWindow = ArrayList<RichElement>()
        for (rich in cachedRich) {
            // All post-layout editor markers are regenerated from the fresh layout below.
            if (rich.element.type == RenderElementType.EDITOR_MARKER) continue
            if (rich.element.type == RenderElementType.TEXT_ANNOTATION) {
                // Reuse prefix/tail annotations from the cache when the staff Y hasn't shifted (deltaY=0).
                // When deltaY≠0 the staff bottom (annotation baseline) may shift by a different amount than
                // the staff centre — regenerate all annotations from the new layout in that case.
                if (deltaY == StaffSpace.ZERO) {
                    val measure = rich.element.measureNumber
                    when {
                        measure == null -> {} // unknown: drop, regenerated wholesale below
                        measure < window.first -> prefix.add(rich)
                        measure > window.last -> tail.add(rich.translated(deltaX, StaffSpace.ZERO, scale))
                        // in window → drop; regenerated from new layout below
                    }
                }
                continue
            }
            val scoreX = rich.hit?.relativeHitBox?.center?.x
            if (scoreX == null) {
                if (!isStructuralType(rich.element.type)) return null
                continue
            }
            val measure = rich.element.measureNumber
            when {
                measure != null && measure < window.first -> prefix.add(rich.translated(StaffSpace.ZERO, deltaY, scale))
                measure != null && measure > window.last -> tail.add(rich.translated(deltaX, deltaY, scale))
                measure != null -> removedWindow.add(rich)
                scoreX < startThr -> prefix.add(rich.translated(StaffSpace.ZERO, deltaY, scale))
                scoreX >= endThrCached -> tail.add(rich.translated(deltaX, deltaY, scale))
                else -> removedWindow.add(rich)
            }
        }

        val assembled = ArrayList<RichElement>(cachedRich.size + 8)
        assembled.addAll(structuralElementRenderer.renderStaffLineElements(layout, nextId)
            .map { RichElement(it, emptyList(), null) })
        assembled.addAll(prefix)

        val newBarlines = layout.barlineLayouts.all()
        val newMeasureBoundaries = ScoreSpatialAdapter.computeMeasureBoundaries(newBarlines, layout.width)
        val newSystemStartX = newBarlines.firstOrNull()?.x ?: config.firstSystemIndent
        val windowRich = ArrayList<RichElement>()
        val windowCollector = SpliceWindowCollector(
            assembled, windowRich, newMeasureBoundaries, newSystemStartX,
            generation = nextId().localOrdinal + 1,
        )

        val staffLayoutByIndex = layout.staffLayouts.associateBy { it.staffIndex }
        val systemByIndex = layout.systems.associateBy { it.systemIndex }
        // Collapsed (fully-hidden) staff ⇒ null ⇒ skip; no flat-stack fallback (see FullScoreRenderer).
        fun staffFor(systemIndex: Int, staffIndex: Int) =
            layout.staffForSystem(systemIndex, staffIndex)

        isCancelled.throwIfCancelled() // before regenerating the window (slot / stem / spanner glyph work)
        val slotFilter: (UnifiedTimeSlot) -> Boolean = { it.time.measure in window }
        notationElementPassRenderer.renderTimeSlotElements(
            layout, computed, systemByIndex, ::staffFor, nextId, windowCollector::collect, slotFilter
        )

        val beamProc = beamGroupProcessor.processBeamGroups(
            layout, staffLayoutByIndex, geometry = computed.runtime.geometry?.beams ?: emptyMap()
        )
        val dummyCtx = ElementRenderContext(RelativePoint.ZERO, transformer, nextId, computed)
        val voiceFilter: (VoiceEventLayout) -> Boolean = { it.measureNumber in window }
        notationElementPassRenderer.renderStemFlagElements(
            layout, beamProc, ::staffFor, dummyCtx, windowCollector::collect, voiceFilter
        )

        // Regenerate the window's beams. Beams never cross a barline, so a per-measure filter selects
        // exactly the groups that were dropped from the cached frame above; groups outside the window are
        // reused (translated) via prefix/tail. Mirrors FullScoreRenderer's beam emission.
        for (groupData in beamProc.beamGroupRenderData) {
            if (groupData.measureNumber !in window) continue
            val beamGroupElement = BeamGroupElement(
                notes = groupData.beamNoteInfos,
                stemDirection = groupData.stemDirection,
                beamThickness = config.engravingDefaults.beamThickness * groupData.noteScale.value,
                beamSpacing = config.engravingDefaults.beamSpacing * groupData.noteScale.value,
                stemThickness = config.engravingDefaults.stemThickness * groupData.noteScale.value,
            )
            windowCollector.collect(beamGroupElement.render(dummyCtx), groupData.staffIndex, 0)
        }

        // Regenerate the window's articulations and tuplets — both are measure-local, so the same
        // per-measure filter applies.
        // Pass `window` as measureFilter so each computer scans only the affected window, avoiding
        // the full O(N) sweep followed by post-hoc filtering.
        val layoutQuery = LayoutQuery(layout, staffLayoutByIndex, computed)
        val geometry = computed.runtime.geometry
        val articulationLayouts = articulationLayoutComputer.computeArticulationLayouts(
            computed, layoutQuery, geometry, measureFilter = window
        )
        for (articulation in articulationLayouts.values) {
            windowCollector.collect(ArticulationElement(articulation).render(dummyCtx), articulation.staffIndex, 0)
        }
        for (tuplet in tupletLayoutComputer.computeTupletLayouts(
            computed, layoutQuery, stemAdjustments = beamProc.stemAdjustments, measureFilter = window
        )) {
            windowCollector.collect(TupletElement(tuplet, config).render(dummyCtx), tuplet.staffIndex, 0)
        }

        // Regenerate the window's ties and slurs. None straddle the window (guarded above), so each is
        // wholly inside the window (regenerated here, keyed by its start measure) or wholly outside
        // (reused/translated via prefix/tail). Mirrors FullScoreRenderer's tie/slur emission.
        if (computed.getTiedChains().isNotEmpty()) {
            for (tie in tieLayoutComputer.computeTieLayouts(computed, layoutQuery, measureFilter = window)) {
                windowCollector.collect(TieElement(tie, config).render(dummyCtx), tie.staffIndex, 0)
            }
        }
        if (computed.slurs.isNotEmpty()) {
            for (slur in slurLayoutComputer.computeSlurLayouts(
                computed, layoutQuery, articulationLayouts,
                stemAdjustments = beamProc.stemAdjustments,
                beamGroups = beamProc.beamGroupRenderData,
                geometry = geometry,
                measureFilter = window,
            )) {
                windowCollector.collect(SlurElement(slur, config).render(dummyCtx), slur.staffIndex, 0)
            }
        }

        // Staff attachments inside the edit window are rebuilt from the fresh unified attachment
        // layout. Attachments outside it are reused above; a span crossing zones was rejected by
        // [anyAttachmentStraddlesWindow], so no interval can be split across differently shifted runs.
        for (placed in layout.placedAttachments) {
            if (placed.measureNumber !in window) continue
            val staffLayout = staffFor(placed.systemIndex, placed.staffIndex) ?: continue
            val attachmentContext = ElementRenderContext(
                offset = RelativePoint(StaffSpace.ZERO, staffLayout.centerY),
                transformer = transformer,
                idGenerator = nextId,
                computedScore = computed,
            )
            windowCollector.collect(
                StaffAttachmentElement(placed).render(attachmentContext),
                placed.staffIndex,
                placed.systemIndex,
            )
        }

        assembled.addAll(tail)

        // Regenerate annotation elements for the affected window (prefix/tail were reused from cache above
        // when deltaY=0; when deltaY≠0 the annotation baseline may have shifted by a different amount than
        // the notation centre, so all annotations are regenerated from the fresh layout in that case).
        if (layout.annotationElementLayouts.isNotEmpty()) {
            val regenerateAll = deltaY != StaffSpace.ZERO
            for (el in annotationStaffRenderer.render(layout, nextId) { placed ->
                regenerateAll || placed.element.time.measure in window
            }) {
                assembled.add(RichElement(el, emptyList(), null))
            }
        }

        postLayoutMarkerRenderer.render(
            layout,
            nextId,
            showMeasureNumbers = computed.runtime.viewPreferences.showMeasureNumbers,
        ).forEach(windowCollector::collectRich)

        return resultAssembler.assembleIncremental(assembled, layout, cachedResult, removedWindow, windowRich, isCancelled)
            ?: resultAssembler.assemble(assembled, layout, isCancelled)
    }

    /**
     * True if any tie or slur has its two endpoints in different splice zones (prefix / window / tail),
     * i.e. it crosses the [window] boundary. Such a spanner can't be rigidly translated with the
     * prefix/tail (whose ends shift by different deltas) nor regenerated as a unit, so the caller bails
     * to a full render. A spanner wholly within one zone is safe and handled normally.
     */
    private fun anySpannerStraddlesWindow(computed: ComputedScore, window: IntRange): Boolean {
        fun zone(measure: Int): Int = when {
            measure < window.first -> -1
            measure > window.last -> 1
            else -> 0
        }
        fun measureOf(id: EventId): Int? = computed.getComputedEvent(id)?.measurePosition?.measure

        for (slur in computed.slurs) {
            val s = measureOf(slur.startEventId) ?: continue
            val e = measureOf(slur.endEventId) ?: continue
            if (zone(s) != zone(e)) return true
        }
        for (chain in computed.getTiedChains()) {
            // A chain is a sequence of tied notes; check each segment (consecutive pair) for a crossing.
            val measures = chain.entries.map { it.first.measurePosition.measure }
            for (i in 1 until measures.size) {
                if (zone(measures[i - 1]) != zone(measures[i])) return true
            }
        }
        return false
    }

    private fun anyAttachmentStraddlesWindow(computed: ComputedScore, window: IntRange): Boolean {
        fun zone(measure: Int): Int = when {
            measure < window.first -> -1
            measure > window.last -> 1
            else -> 0
        }
        for (attachment in computed.staffAttachments) {
            val endMeasure = when (attachment) {
                is ComputedHairpin -> attachment.endTime.measure
                is ComputedOctaveShift -> attachment.endTime.measure
                is ComputedOrnamentMark -> attachment.endTime?.measure
                is ComputedTempoKeyframe -> attachment.nextTime?.measure.takeIf { attachment.isGradual }
                is ComputedVoltaAttachment -> attachment.endTime.measure
                else -> null
            } ?: continue
            if (zone(attachment.time.measure) != zone(endMeasure)) return true
        }
        return false
    }

    private fun isSpliceableLayout(l: UnifiedLayoutResult): Boolean {
        if (l.paginated || l.systems.size != 1) return false
        // annotationElementLayouts is allowed: annotations are regenerated wholesale in the splice.
        if (l.titleBlock != null) return false
        if (l.suppressedBarlineTimes.isNotEmpty()) return false
        if (l.suppressedClefTimes.isNotEmpty()) return false
        val sys = l.systems.first()
        if (sys.staffLayouts.count { it.kind == StaffKind.NOTATION } != 1) return false
        return sys.headerBrackets.isEmpty() && sys.headerLabels.isEmpty() &&
            sys.lineStartHeaders.isEmpty() && sys.closingBarline == null && sys.lineEndClefs.isEmpty()
    }

    private fun boundaryThreshold(
        slots: List<UnifiedTimeSlot>,
        measure: Int,
        before: Boolean
    ): StaffSpace {
        val below = slots.filter { if (before) it.time.measure < measure else it.time.measure <= measure }
            .maxOfOrNull { it.x }
        val above = slots.filter { if (before) it.time.measure >= measure else it.time.measure > measure }
            .minOfOrNull { it.x }
        return when {
            above == null -> StaffSpace(Float.POSITIVE_INFINITY)
            below == null -> StaffSpace(Float.NEGATIVE_INFINITY)
            else -> StaffSpace((below.value + above.value) / 2f)
        }
    }

    private fun isStructuralType(type: RenderElementType): Boolean =
        type == RenderElementType.STAFF || type == RenderElementType.STAFF_LINE ||
            type == RenderElementType.MEASURE
}

internal fun isSpliceableType(type: RenderElementType): Boolean = when (type) {
    RenderElementType.STAFF, RenderElementType.STAFF_LINE, RenderElementType.NOTE,
    RenderElementType.CHORD, RenderElementType.GRACE_NOTE, RenderElementType.NOTEHEAD,
    RenderElementType.STEM, RenderElementType.FLAG, RenderElementType.DOT,
    RenderElementType.ACCIDENTAL, RenderElementType.LEDGER_LINE, RenderElementType.REST,
    RenderElementType.CLEF, RenderElementType.KEY_SIGNATURE, RenderElementType.TIME_SIGNATURE,
    RenderElementType.BARLINE, RenderElementType.MEASURE,
    RenderElementType.DYNAMIC, RenderElementType.HAIRPIN, RenderElementType.OCTAVE_SHIFT,
    RenderElementType.VOLTA_ENDING,
    RenderElementType.BEAM, RenderElementType.TIE, RenderElementType.SLUR,
    RenderElementType.TUPLET_BRACKET, RenderElementType.ARTICULATION,
    RenderElementType.ORNAMENT -> true
    else -> false
}
