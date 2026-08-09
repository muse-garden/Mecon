package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap

internal data class SystemBreakContent(
    val timeSlotMap: UnifiedTimeSlotMap,
    val barlineLayouts: BarlineLayoutMap,
    val staffTracks: List<StaffInfo>,
    val perMeasureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
    val computed: ComputedScore,
)

internal data class SystemBreakPage(
    val pageGeometry: PageGeometry,
    val systemStartX: StaffSpace,
    val forcedSystemBreaks: Set<Int>,
    val forcedPageBreaks: Set<Int>,
    val titleBlockHeight: StaffSpace = StaffSpace.ZERO,
    val annotationLineExtents: (IntRange) -> AnnotationLineExtents = { AnnotationLineExtents.ZERO },
)

internal data class SystemBreakRequest(
    val content: SystemBreakContent,
    val page: SystemBreakPage,
)

internal data class IncrementalSystemBreakRequest(
    val cached: UnifiedLayoutResult,
    val content: SystemBreakContent,
    val page: SystemBreakPage,
    val window: IntRange,
    val deferVerticalToAttachments: Boolean = false,
)

private data class AssembleSystemsRequest(
    val lines: List<List<SystemBreaker.MeasureSeg>>,
    val content: SystemBreakContent,
    val page: SystemBreakPage,
    val measureWidths: Map<Int, StaffSpace>,
    val cachedPostBreak: UnifiedLayoutResult? = null,
    val recomputeSystems: Set<Int>? = null,
    val deferVerticalToAttachments: Boolean = false,
)

internal data class AttachmentVerticalRequest(
    val systems: List<SystemLayout>,
    val pages: List<PageLayout>,
    val staffTracks: List<StaffInfo>,
    val perMeasureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
    val perSystemAttachmentExtents: Map<Int, Map<Int, AttachmentExtent>>,
    val page: SystemBreakPage,
    val affectedSystems: Set<Int>? = null,
    val cachedFinalSystems: List<SystemLayout>? = null,
)

/**
 * Breaks a continuously-laid-out score into systems (lines) and pages.
 *
 * Runs *after* the proportional X pass and base staff-Y computation. It:
 *  1. derives per-measure widths from the barline grid,
 *  2. greedily packs measures into systems up to [PageGeometry.lineWidth]
 *     (honouring forced breaks),
 *  3. stacks systems into pages up to [PageGeometry.pageContentHeight],
 *  4. justifies each non-last system by stretching its slots' X to fill the line,
 *  5. re-states clef + key signature at the start of every system after the first.
 *
 * Slots are immutable, so it does NOT mutate them in place: it returns a re-stretched,
 * system-tagged snapshot ([Result.timeSlotMap]) alongside the per-system / per-page
 * layout metadata. Attachment tagging/splitting is a follow-up call ([tagAttachments])
 * made after the caller re-runs the attachment computer against the now-stretched slots.
 *
 * Only invoked in paginated mode; continuous mode keeps the single-system path.
 */
context(BravuraFont)
internal class SystemBreaker(
    private val config: RenderLayoutConfig,
    private val measureVerticalExtentTree: MeasureVerticalExtentTree,
) {

    /** Pure vertical-stacking primitive (no font context needed). */
    private val staffLayoutComputer = StaffLayoutComputer(config)
    private val verticalComputer = SystemVerticalLayoutComputer(config, measureVerticalExtentTree)
    private val headerComputer = SystemHeaderComputer(config)
    var lastAttachmentVerticalReused: Boolean = false
        private set
    var lastAttachmentVerticalSystemsVisited: Int = 0
        private set

    data class Result(
        val systems: List<SystemLayout>,
        val pages: List<PageLayout>,
        val suppressedBarlineTimes: Set<TimeCode>,
        /** Re-stretched, per-system-tagged snapshot of the input slots (new instances). */
        val timeSlotMap: UnifiedTimeSlotMap,
        /** Pre-break (intrinsic) width of each measure, keyed by measure number. Drives incremental reuse. */
        val measureWidths: Map<Int, StaffSpace> = emptyMap(),
        /** Body-clef times suppressed because they open a system (>0); the header restates them instead. */
        val suppressedClefTimes: Set<TimeCode> = emptySet(),
        /**
         * Per-system lineage, aligned with [systems] by index — set only on the **reflow** incremental
         * path ([breakIntoSystemsIncremental] re-packing after a line-break move). Null on the full path
         * and the non-reflow incremental path (whole cached partition reused verbatim).
         */
        val systemLineage: List<SystemOrigin>? = null,
        /** Stable-partition fast path reused cached page assignment and unaffected vertical geometry. */
        val verticalPaginationReused: Boolean = false,
        /** Number of systems visited by the incremental vertical propagation (0 on a full pass). */
        val verticalSystemsVisited: Int = 0,
    )

    /** A single measure segment between two barlines. */
    data class MeasureSeg(
        val measure: Int,
        val startX: StaffSpace,
        val endX: StaffSpace,
        val openingTime: TimeCode,
        val closingType: com.mecon.api.primitive.BarlineType,
    )

    /** Justification parameters for one system. */
    private data class LineParams(
        val origStart: StaffSpace,
        val origEnd: StaffSpace,
        val targetStart: StaffSpace,
        val targetEnd: StaffSpace,
        val justified: Boolean,
        val lineStartX: StaffSpace,
        val lineEndX: StaffSpace,
    )

    // Populated by breakIntoSystems, consumed by tagAttachments.
    private var measureToLine: Map<Int, Int> = emptyMap()
    private var maxMeasure: Int = 1
    private var lineParams: List<LineParams> = emptyList()
    /** First note-slot X on each line (after stretch), for aligning span continuations. */
    private var lineFirstNoteX: Map<Int, StaffSpace> = emptyMap()
    /** Lead-in from a note slot's right-edge X back to its centre (matches noteCentreLead). */
    private val continuationLead = StaffSpace(0.6f)

    fun breakIntoSystems(request: SystemBreakRequest): Result {
        val (timeSlotMap, barlineLayouts, staffTracks, perMeasureExtents, computed) = request.content
        val (
            pageGeometry,
            systemStartX,
            forcedSystemBreaks,
            forcedPageBreaks,
            titleBlockHeight,
            annotationLineExtents,
        ) = request.page
        val slots = timeSlotMap.all()
        if (slots.isEmpty() || staffTracks.isEmpty()) {
            return singleSystem(staffTracks, perMeasureExtents, systemStartX, timeSlotMap, pageGeometry, titleBlockHeight)
        }

        // 1. Measure grid from the sorted barline positions.
        val barlines = barlineLayouts.all().sortedBy { it.x.value }
        if (barlines.size < 2) {
            return singleSystem(staffTracks, perMeasureExtents, systemStartX, timeSlotMap, pageGeometry, titleBlockHeight)
        }
        val segs = (0 until barlines.size - 1).map { i ->
            val a = barlines[i]
            val b = barlines[i + 1]
            MeasureSeg(
                // Barlines sit at the END of their measure (measureNumber = N, time = (N, end)).
                // The region between barline a and barline b is therefore the measure that b
                // closes — use the RIGHT barline's number so it matches each note's time.measure.
                measure = b.measureNumber.coerceAtLeast(1),
                startX = a.x,
                endX = b.x,
                openingTime = a.time,
                closingType = b.type,
            )
        }

        // Pre-break (intrinsic) measure widths — the drift-free reference the incremental breaker reuses.
        val measureWidths = segs
            .associate { it.measure to StaffSpace(it.endX.value - it.startX.value) }
            .toPersistentMap()

        // 2. Greedy line breaking into systems, then assemble system layouts from that partition.
        val contentRightX = pageGeometry.leftMargin + pageGeometry.lineWidth
        val availWidth = (contentRightX - systemStartX).value
        val lines = greedyPack(segs, availWidth, forcedSystemBreaks, staffTracks, computed)
        return assembleSystems(
            AssembleSystemsRequest(lines, request.content, request.page, measureWidths),
        )
    }

    /**
     * Greedy left-to-right packing of measure segments into lines: a measure joins the current line while
     * it (plus the restated header on lines after the first) fits in [availWidth]; forced breaks start a
     * new line unconditionally. Staves right-align to the page content right edge, so the usable width is
     * from [systemStartX][breakIntoSystems] to that edge — not the full content width.
     */
    private fun greedyPack(
        segs: List<MeasureSeg>,
        availWidth: Float,
        forcedSystemBreaks: Set<Int>,
        staffTracks: List<StaffInfo>,
        computed: ComputedScore,
    ): List<MutableList<MeasureSeg>> {
        val lines = mutableListOf<MutableList<MeasureSeg>>()
        var current = mutableListOf<MeasureSeg>()
        var running = 0f
        var currentHeaderW = 0f
        for (seg in segs) {
            val w = (seg.endX - seg.startX).value
            if (current.isEmpty()) {
                current.add(seg)
                running = w
                currentHeaderW = 0f // first line ever uses the in-stream initial clef/key
                continue
            }
            val forced = seg.measure in forcedSystemBreaks
            val capacity = availWidth - currentHeaderW
            if (forced || running + w > capacity) {
                lines.add(current)
                current = mutableListOf(seg)
                running = w
                currentHeaderW = headerComputer.compute(seg.measure, staffTracks, computed).width.value
            } else {
                current.add(seg)
                running += w
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    /**
     * Build the per-system layouts, pagination, justification and re-stretched slots from a fixed line
     * partition [lines]. Shared by the full breaker (which feeds it [greedyPack]'s output) and the
     * incremental breaker (which feeds it the validated cached partition). Everything except the discrete
     * partition decision is recomputed here from the supplied inputs, so vertical-extent changes (e.g. a
     * higher note's longer stem) correctly re-stack and re-paginate even on the incremental path.
     */
    private fun assembleSystems(request: AssembleSystemsRequest): Result {
        val lines = request.lines
        val (timeSlotMap, _, staffTracks, perMeasureExtents, computed) = request.content
        val (
            pageGeometry,
            systemStartX,
            _,
            forcedPageBreaks,
            titleBlockHeight,
            annotationLineExtents,
        ) = request.page
        val measureWidths = request.measureWidths
        val cachedPostBreak = request.cachedPostBreak
        val recomputeSystems = request.recomputeSystems
        val deferVerticalToAttachments = request.deferVerticalToAttachments
        val prepareStart = kotlin.time.TimeSource.Monotonic.markNow()
        val slots = timeSlotMap.all()
        val contentRightX = pageGeometry.leftMargin + pageGeometry.lineWidth

        // Map every measure to its line, then tag each slot, before measuring the
        // per-line slot extents (needed for justification).
        val measureLine = mutableMapOf<Int, Int>()
        for ((lineIdx, line) in lines.withIndex()) {
            for (m in line.first().measure..line.last().measure) measureLine[m] = lineIdx
        }
        measureToLine = measureLine
        maxMeasure = lines.maxOf { it.last().measure }

        val cachedSlotsForReuse = cachedPostBreak?.timeSlotMap?.all()
        // Stable-partition incremental frames have the same ordered slot keys. Locate the affected
        // systems by binary search in the cached post-break list and touch only those contiguous runs.
        // A slot-count/key mismatch retains the conservative whole-list path below.
        var incrementalSlotRanges: List<IntRange>? = null
        if (recomputeSystems != null && cachedSlotsForReuse?.size == slots.size) {
            fun lowerBoundSystem(target: Int): Int {
                var lo = 0
                var hi = cachedSlotsForReuse.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (cachedSlotsForReuse[mid].systemIndex < target) lo = mid + 1 else hi = mid
                }
                return lo
            }
            val ranges = ArrayList<IntRange>(recomputeSystems.size)
            var valid = true
            for (system in recomputeSystems.sorted()) {
                val start = lowerBoundSystem(system)
                val endExclusive = lowerBoundSystem(system + 1)
                if (start >= endExclusive) { valid = false; break }
                for (i in start until endExclusive) {
                    if (cachedSlotsForReuse[i].time != slots[i].time) { valid = false; break }
                }
                if (!valid) break
                ranges.add(start until endExclusive)
            }
            if (valid) incrementalSlotRanges = ranges
        }

        // Full/fallback path keeps the parallel whole-score arrays. The incremental path only fills X
        // entries in [incrementalSlotRanges]; system ownership comes from the cached stable partition.
        val sysOf = if (incrementalSlotRanges == null)
            IntArray(slots.size) { lineOf(slots[it].time.measure) } else null
        val xOf = FloatArray(slots.size)
        if (incrementalSlotRanges != null) {
            for (range in incrementalSlotRanges) for (i in range) xOf[i] = slots[i].x.value
        } else {
            for (i in slots.indices) xOf[i] = slots[i].x.value
        }
        if (incrementalSlotRanges == null && recomputeSystems != null && cachedSlotsForReuse?.size == slots.size) {
            for (i in slots.indices) {
                val cachedSlot = cachedSlotsForReuse[i]
                if (sysOf!![i] !in recomputeSystems && cachedSlot.time == slots[i].time &&
                    cachedSlot.systemIndex == sysOf[i]
                ) xOf[i] = cachedSlot.x.value
            }
        }
        fun systemAt(index: Int): Int = if (incrementalSlotRanges != null)
            cachedSlotsForReuse!![index].systemIndex else sysOf!![index]
        val prepareTime = prepareStart.elapsedNow()

        // Per-line slot-X extent. Slots store the *right edge* of their cluster, so
        // `lo` is the right edge of the leftmost cluster on the line. For line 0 that
        // is the in-stream opening cluster (initial barline + clef + key + time),
        // which must keep its natural position flush with the staff start.
        val lineLo = HashMap<Int, Float>()
        val lineHi = HashMap<Int, Float>()
        // Left content inset of each line's leftmost slot: how far that slot's leftmost
        // drawn edge sits to the left of its slot.x (right-edge) anchor. A non-first
        // line's note region is pushed right by this much so the first note's *left*
        // edge clears the restated header instead of overlapping the clef / key.
        val lineHeadInset = HashMap<Int, Float>()
        val extentStart = kotlin.time.TimeSource.Monotonic.markNow()
        fun collectLineExtent(i: Int) {
            val li = systemAt(i)
            if (recomputeSystems != null && li !in recomputeSystems) return
            val x = xOf[i]
            val prevLo = lineLo[li]
            if (prevLo == null || x < prevLo) {
                lineLo[li] = x
                lineHeadInset[li] = -(slots[i].events.minOfOrNull { it.relativeX.value } ?: 0f)
            }
            lineHi[li] = maxOf(lineHi[li] ?: x, x)
        }
        if (incrementalSlotRanges != null) {
            for (range in incrementalSlotRanges) for (i in range) collectLineExtent(i)
        } else for (i in slots.indices) collectLineExtent(i)
        val extentTime = extentStart.elapsedNow()

        // ----- Horizontal pass: justification params, headers, closing barlines per line. -----
        // No Y here — the greedy partition (already decided) and justification are independent of
        // vertical extent. Vertical stacking + pagination is the separate [verticalPass] below.
        val paramsList = mutableListOf<LineParams>()
        val suppressed = cachedPostBreak?.suppressedBarlineTimes?.toMutableSet() ?: mutableSetOf()
        val suppressedClef = cachedPostBreak?.suppressedClefTimes?.toMutableSet() ?: mutableSetOf()
        val lineHeaders = arrayOfNulls<SystemLineHeader>(lines.size)
        val lineClosing = arrayOfNulls<ClosingBarline>(lines.size)
        val lineEndClefs = arrayOfNulls<List<LineEndClef>>(lines.size)
        val lineMeasureRange = ArrayList<IntRange>(lines.size)

        val linesStart = kotlin.time.TimeSource.Monotonic.markNow()
        for ((lineIdx, line) in lines.withIndex()) {
            val m0 = line.first().measure
            val m1 = line.last().measure
            lineMeasureRange.add(m0..m1)

            val cachedSystem = cachedPostBreak?.systems?.getOrNull(lineIdx)
            val reuseHorizontal = recomputeSystems != null && lineIdx !in recomputeSystems && cachedSystem != null
            if (reuseHorizontal) {
                // Only lineStartX/lineEndX are consumed later by attachment clipping. The actual cached
                // headers/closing/courtesy objects are copied into SystemLayout below.
                paramsList.add(
                    LineParams(
                        StaffSpace.ZERO, StaffSpace.ZERO, StaffSpace.ZERO, StaffSpace.ZERO,
                        justified = false,
                        lineStartX = cachedSystem!!.lineStartX,
                        lineEndX = cachedSystem.lineEndX,
                    )
                )
                continue
            }

            // Header (re-stated clef + key) for non-first systems.
            val header = if (lineIdx == 0) null else headerComputer.compute(m0, staffTracks, computed)
            lineHeaders[lineIdx] = header
            val headerW = header?.width ?: StaffSpace.ZERO

            val isLast = lineIdx == lines.size - 1
            val lo = StaffSpace(lineLo[lineIdx] ?: systemStartX.value)
            val hi = StaffSpace(lineHi[lineIdx] ?: lo.value)

            // If the NEXT system opens with a clef change, this line restates it as a courtesy clef at
            // its right end. Reserve a strip for it (content justifies to before the strip; the courtesy
            // clef + closing barline occupy the strip). The next line's in-stream body clef is suppressed
            // so the changed clef shows once as that line's header — never twice at the break.
            val courtesyParts = if (isLast) emptyList()
                else courtesyClefsFor(lines[lineIdx + 1].first().measure, staffTracks, computed)
            val gap = config.spaceAfterClef
            val courtesyWidth = courtesyParts.maxOfOrNull { it.second.minimumWidth } ?: StaffSpace.ZERO
            val courtesyReserve = if (courtesyParts.isEmpty()) StaffSpace.ZERO else courtesyWidth + gap + gap
            // Suppress the next line's in-stream body clef (keyed by the clef's own time, not the barline's).
            for ((_, clefElem) in courtesyParts) suppressedClef.add(clefElem.time)

            // Justification params (left anchor / right edge / stretch ratio) for this line.
            val params = lineParamsFor(
                lineIdx, lo, hi, lineHeadInset[lineIdx] ?: 0f, headerW, isLast, systemStartX, contentRightX,
                contentEndInset = courtesyReserve,
            )
            paramsList.add(params)

            if (courtesyParts.isNotEmpty()) {
                val courtesyBaseX = params.lineEndX - courtesyWidth - gap
                lineEndClefs[lineIdx] = courtesyParts.map { (staff, clefElem) ->
                    LineEndClef(lineIdx, staff.staffIndex, clefElem, courtesyBaseX)
                }
            }

            // Suppress the opening barline of non-first systems; draw a closing
            // barline at the previous system's right edge instead.
            lineClosing[lineIdx] = if (isLast) null else ClosingBarline(line.last().closingType, params.lineEndX)
            if (lineIdx > 0) suppressed.add(line.first().openingTime)
        }
        val linesTime = linesStart.elapsedNow()

        lineParams = paramsList

        // Stretch every slot into its line using slot-X (right-edge) coordinates so
        // the leftmost cluster's left edge stays put after justification.
        val stretchStart = kotlin.time.TimeSource.Monotonic.markNow()
        fun stretchSlot(i: Int) {
            val system = systemAt(i)
            if (recomputeSystems == null || system in recomputeSystems) {
                xOf[i] = stretchX(xOf[i], paramsList[system])
            }
        }
        if (incrementalSlotRanges != null) {
            for (range in incrementalSlotRanges) for (i in range) stretchSlot(i)
        } else for (i in slots.indices) stretchSlot(i)
        val stretchTime = stretchStart.elapsedNow()

        // Leftmost note-slot X on each line (post-stretch). Span continuations that wrap
        // onto a later line begin here (aligned with the first note) rather than at the
        // bare staff start under the re-stated clef.
        val firstNoteX = HashMap<Int, Float>()
        if (cachedPostBreak != null && recomputeSystems != null) {
            for (system in cachedPostBreak.systems) {
                if (system.systemIndex !in recomputeSystems) {
                    system.lineFirstNoteX?.let { firstNoteX[system.systemIndex] = it.value }
                }
            }
        }
        val firstNoteStart = kotlin.time.TimeSource.Monotonic.markNow()
        fun collectFirstNote(i: Int) {
            if (!slots[i].hasNotes()) return
            val system = systemAt(i)
            firstNoteX[system] = minOf(firstNoteX[system] ?: xOf[i], xOf[i])
        }
        if (incrementalSlotRanges != null) {
            for (range in incrementalSlotRanges) for (i in range) collectFirstNote(i)
        } else for (i in slots.indices) collectFirstNote(i)
        lineFirstNoteX = firstNoteX.mapValues { StaffSpace(it.value) }
        val firstNoteTime = firstNoteStart.elapsedNow()

        // ----- Vertical pass: per-line staff stacking + pagination (note extents only here;
        // attachment room is folded in later by [applyAttachmentExtents] once attachments are placed). -----
        val verticalStart = kotlin.time.TimeSource.Monotonic.markNow()
        val reusedVertical = if (cachedPostBreak != null && recomputeSystems != null) {
            val cachedVerticalSystems = if (deferVerticalToAttachments)
                cachedPostBreak.preAttachmentSystems else cachedPostBreak.systems
            verticalComputer.incremental(
                lineMeasureRange, staffTracks, emptyMap(), pageGeometry, forcedPageBreaks,
                titleBlockHeight, annotationLineExtents, cachedVerticalSystems, recomputeSystems,
            )
        } else null
        val (verticals, pages) = reusedVertical?.let { it.verticals to it.pages } ?: verticalComputer.full(
            lineMeasureRange, staffTracks, perMeasureExtents, emptyMap(),
            pageGeometry, forcedPageBreaks, titleBlockHeight, annotationLineExtents
        )
        val verticalTime = verticalStart.elapsedNow()

        val systemsStart = kotlin.time.TimeSource.Monotonic.markNow()
        val systems = lines.indices.map { lineIdx ->
            val cachedSystem = cachedPostBreak?.systems?.getOrNull(lineIdx)
            if (recomputeSystems != null && lineIdx !in recomputeSystems && cachedSystem != null) {
                cachedSystem.copy(
                    pageIndex = verticals[lineIdx].pageIndex,
                    yOffset = verticals[lineIdx].yOffset,
                    staffLayouts = verticals[lineIdx].staffLayouts,
                )
            } else SystemLayout(
                systemIndex = lineIdx,
                pageIndex = verticals[lineIdx].pageIndex,
                measureRange = lineMeasureRange[lineIdx],
                yOffset = verticals[lineIdx].yOffset,
                    lineStartX = systemStartX,
                    lineEndX = paramsList[lineIdx].lineEndX,
                    lineFirstNoteX = lineFirstNoteX[lineIdx],
                    staffLayouts = verticals[lineIdx].staffLayouts,
                lineStartHeaders = lineHeaders[lineIdx]?.toLineStartHeaders(lineIdx, systemStartX) ?: emptyList(),
                closingBarline = lineClosing[lineIdx],
                lineEndClefs = lineEndClefs[lineIdx] ?: emptyList(),
            )
        }
        val systemsTime = systemsStart.elapsedNow()

        // Emit fresh, system-tagged + stretched slots (input slots are never mutated).
        val slotsStart = kotlin.time.TimeSource.Monotonic.markNow()
        val newTimeSlotMap = if (incrementalSlotRanges != null && cachedPostBreak != null) {
            val replacements = ArrayList<IndexedValue<UnifiedTimeSlot>>()
            for (range in incrementalSlotRanges) for (i in range) {
                val system = cachedSlotsForReuse!![i].systemIndex
                replacements.add(IndexedValue(i, slots[i].copy(x = StaffSpace(xOf[i]), systemIndex = system)))
            }
            cachedPostBreak.timeSlotMap.patchSlots(replacements)
        } else {
            val newSlots = slots.mapIndexed { i, s ->
                if (recomputeSystems != null && sysOf!![i] !in recomputeSystems) {
                    cachedSlotsForReuse?.getOrNull(i)?.takeIf {
                        it.time == s.time && it.systemIndex == sysOf[i]
                    } ?: s.copy(x = StaffSpace(xOf[i]), systemIndex = sysOf[i])
                } else s.copy(x = StaffSpace(xOf[i]), systemIndex = sysOf!![i])
            }
            timeSlotMap.withSlots(newSlots)
        }
        val slotsTime = slotsStart.elapsedNow()
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "layout.paginate.break.parts prepare=$prepareTime extent=$extentTime lines=$linesTime " +
                "stretch=$stretchTime firstNote=$firstNoteTime vertical=$verticalTime " +
                "systems=$systemsTime slots=$slotsTime"
        }
        return Result(
            systems, pages, suppressed, newTimeSlotMap, measureWidths,
            suppressedClefTimes = suppressedClef,
            verticalPaginationReused = reusedVertical != null,
            verticalSystemsVisited = reusedVertical?.visitedSystems ?: 0,
        )
    }

    fun perSystemNoteExtents(
        systems: List<SystemLayout>,
    ): Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>> =
        verticalComputer.perSystemNoteExtents(systems)

    /**
     * Re-stack staves and re-paginate with attachment room folded in.
     *
     * [breakIntoSystems] lays out vertical positions from note extents only (attachments aren't placed
     * yet). Once the caller has the placed attachments and their per-(system, staff) extents, this re-runs
     * [verticalPass] with that extra room — so dynamics / hairpins push staves apart and can shift page
     * breaks — and folds the new Y back into each system. Everything horizontal (X, headers, closing
     * barlines, measure ranges) is preserved. No-op (returns the inputs) when there is no attachment room.
     */
    fun applyAttachmentExtents(request: AttachmentVerticalRequest): Pair<List<SystemLayout>, List<PageLayout>> {
        val systems = request.systems
        val pages = request.pages
        val staffTracks = request.staffTracks
        val perMeasureExtents = request.perMeasureExtents
        val perSystemAttachmentExtents = request.perSystemAttachmentExtents
        val (pageGeometry, _, _, forcedPageBreaks, titleBlockHeight, annotationLineExtents) = request.page
        val affectedSystems = request.affectedSystems
        val cachedFinalSystems = request.cachedFinalSystems
        lastAttachmentVerticalReused = false
        lastAttachmentVerticalSystemsVisited = 0
        // No attachments → the systems already carry annotation room from the first vertical pass
        // (assembleSystems ran verticalPass with the same annotationLineExtent), so return them unchanged.
        if (perSystemAttachmentExtents.isEmpty() || systems.isEmpty()) return systems to pages
        val reusedVerticals = if (affectedSystems != null && cachedFinalSystems != null) {
            verticalComputer.incremental(
                systems.map { system -> system.measureRange }, staffTracks,
                perSystemAttachmentExtents, pageGeometry, forcedPageBreaks, titleBlockHeight,
                annotationLineExtents, cachedFinalSystems, affectedSystems,
            )
        } else null
        val (verticals, newPages) = if (reusedVerticals != null) {
            lastAttachmentVerticalReused = true
            lastAttachmentVerticalSystemsVisited = reusedVerticals.visitedSystems
            reusedVerticals.verticals to reusedVerticals.pages
        } else verticalComputer.full(
            systems.map { it.measureRange }, staffTracks, perMeasureExtents,
            perSystemAttachmentExtents, pageGeometry, forcedPageBreaks, titleBlockHeight, annotationLineExtents
        )
        val newSystems = systems.mapIndexed { i, s ->
            s.copy(
                pageIndex = verticals[i].pageIndex,
                yOffset = verticals[i].yOffset,
                staffLayouts = verticals[i].staffLayouts,
            )
        }
        return newSystems to newPages
    }

    /** Line index for a measure number, clamped to the valid range. */
    private fun lineOf(measure: Int): Int =
        measureToLine[measure]
            ?: if (measure > maxMeasure) lineParams.lastIndex.coerceAtLeast(0) else 0

    // ----- Shared per-line geometry (used by both the full and incremental paths) -----

    /**
     * Justification parameters for one line. [lo]/[hi] are the right-edge X extent of the line's slots
     * (pre-break); [headInset] is how far the leftmost slot's drawn content sits left of its right-edge
     * anchor; [headerW] is the restated clef/key header width (0 on line 0). Line 0 pins [lo] in place;
     * later lines anchor just after the header; justified (non-last) lines fill to [contentRightX].
     */
    private fun lineParamsFor(
        lineIdx: Int, lo: StaffSpace, hi: StaffSpace, headInset: Float, headerW: StaffSpace,
        isLast: Boolean, systemStartX: StaffSpace, contentRightX: StaffSpace,
        contentEndInset: StaffSpace = StaffSpace.ZERO,
    ): LineParams {
        val pinTarget = if (lineIdx == 0) lo else systemStartX + headerW + StaffSpace(headInset)
        val lineEndX = if (isLast) pinTarget + (hi - lo) else contentRightX
        // Content justifies to [targetEnd]; the strip [targetEnd, lineEndX] is reserved for a courtesy
        // clef + closing barline. lineEndX (staff lines / closing barline) still spans the full width.
        val targetEnd = lineEndX - contentEndInset
        return LineParams(lo, hi, pinTarget, targetEnd, !isLast, systemStartX, lineEndX)
    }

    /**
     * Courtesy clefs for the staves whose clef changes exactly at [nextMeasure]'s opening (the first
     * measure of the *following* system). Each is a small-scale [ClefElement] whose section maps back to
     * that clef change, so selecting the courtesy or the next line's header edits the same clef.
     */
    private fun courtesyClefsFor(
        nextMeasure: Int,
        staffTracks: List<StaffInfo>,
        computed: ComputedScore,
    ): List<Pair<StaffInfo, ClefElement>> {
        val out = mutableListOf<Pair<StaffInfo, ClefElement>>()
        for (staff in staffTracks) {
            // A clef change that opens [nextMeasure]: same measure, beat at the barline (0 or absent).
            // Match by measure/beat rather than an exact TimeCode literal so both [M] and [M,0] forms hit.
            val change = computed.clefs.firstOrNull {
                it.staffTrackId == staff.trackId && !it.isInitial &&
                    it.time.measure == nextMeasure && (it.time.beat?.numerator ?: 0) == 0
            } ?: continue
            out.add(
                staff to ClefElement.create(
                    time = change.time,
                    staffIndex = staff.staffIndex,
                    clef = change.clef,
                    isInitial = false,
                    staffTrackId = staff.trackId,
                    sectionTime = change.time,
                    scale = RenderConstants.INLINE_CLEF_CHANGE_SCALE,
                )
            )
        }
        return out
    }

    /** Map one slot's right-edge X into its line's justified coordinates. */
    private fun stretchX(x: Float, p: LineParams): Float =
        if (p.justified && p.origEnd.value > p.origStart.value) {
            val t = (x - p.origStart.value) / (p.origEnd.value - p.origStart.value)
            p.targetStart.value + (p.targetEnd.value - p.targetStart.value) * t
        } else {
            x + (p.targetStart.value - p.origStart.value)
        }

    // ----- Incremental line-breaking -----

    /**
     * Incremental line-breaking ("增量分行分页", docs/renderer/incremental-rendering.md). When an edit
     * confined to [window] measures leaves the [cached] line partition intact, reuse that partition and
     * re-run only the (cheap, O(lines)) layout assembly — pagination, justification and per-system staff Y
     * — from the new inputs via [assembleSystems]. Returns null — caller falls back to a full
     * [breakIntoSystems] — when an unsupported feature is present. If the partition changes, the live
     * reflow path preserves the full solve's discrete partition while allowing harmless sub-pixel X
     * differences in regenerated systems (the engraving-quality pass may still perform a cold solve).
     *
     * **Drift safety** — the reason a plain "translate + re-run the full breaker" is *incorrect*: the
     * greedy `running + w > capacity` is a discrete branch, and re-deriving widths from translated
     * (≈1e-4px-drifting) barline coordinates can flip a measure onto a different line. Here the partition
     * is validated with the cached intrinsic widths ([UnifiedLayoutResult.preBreakMeasureWidths]) for
     * unchanged measures and fresh widths for [window] measures — never recomputing breakpoints from
     * drifting coordinates — so the discrete decision is reproduced exactly. The reused partition is then
     * fed to [assembleSystems], which recomputes everything else (so e.g. a higher note's longer stem
     * re-stacks and re-paginates the staves correctly).
     */
    fun breakIntoSystemsIncremental(request: IncrementalSystemBreakRequest): Result? {
        val cached = request.cached
        val (newPreBreak, newBarlines, staffTracks, perMeasureExtents, computed) = request.content
        val (
            pageGeometry,
            systemStartX,
            forcedSystemBreaks,
            forcedPageBreaks,
            titleBlockHeight,
            annotationLineExtents,
        ) = request.page
        val window = request.window
        val deferVerticalToAttachments = request.deferVerticalToAttachments
        // ---- Gates: anything the fast path does not model → fall back to a full solve. ----
        // Each gate returns null (caller does a full breaker solve); the reason string documents why.
        fun bail(@Suppress("UNUSED_PARAMETER") reason: String): Result? = null
        val cachedSystems = cached.systems
        if (cachedSystems.size < 2) return bail("singleSystem")                 // small / single-system: full is cheap
        // Staff attachments do NOT gate the break: the partition decision is purely horizontal, while
        // attachment placement / re-tag / split / extent-folding all run AFTER the break (in
        // UnifiedLayoutComputer) against the assembled lines, identically in both the incremental and
        // full paths. Vertical room and pagination are recomputed fresh from the new inputs either way.
        val cachedWidths = cached.preBreakMeasureWidths
        if (cachedWidths.isEmpty() || window.isEmpty() || staffTracks.isEmpty()) return bail("noWidths/emptyWindow")

        val maxMeasureLocal = cachedSystems.maxOf { it.measureRange.last }
        // Systems are contiguous and ordered by measure. Binary-searching their ranges avoids rebuilding
        // an O(measures) measure→line table for every single-measure edit.
        fun lineOfLocal(measure: Int): Int {
            if (measure <= cachedSystems.first().measureRange.first) return 0
            if (measure > maxMeasureLocal) return cachedSystems.lastIndex
            var lo = 0
            var hi = cachedSystems.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val range = cachedSystems[mid].measureRange
                when {
                    measure < range.first -> hi = mid - 1
                    measure > range.last -> lo = mid + 1
                    else -> return mid
                }
            }
            return lo.coerceIn(0, cachedSystems.lastIndex)
        }

        // Fresh measure segments from the new barline grid. Their intrinsic widths are drift-free within
        // this one solve and are used (for window measures) to validate the partition; their X is not used
        // for justification (assembleSystems justifies from the slot X), only the measure/openingTime/type.
        val barlines = newBarlines.all().sortedBy { it.x.value }
        if (barlines.size < 2) return bail("fewBarlines")
        val newSegs = (0 until barlines.size - 1).map { i ->
            val a = barlines[i]
            val b = barlines[i + 1]
            MeasureSeg(b.measureNumber.coerceAtLeast(1), a.x, b.x, a.time, b.type)
        }
        // Patch only the edited measures into the persistent cache. Unchanged widths keep their exact
        // cached Float bits (no translated-coordinate drift), while the map shares all untouched nodes.
        val widthBuilder = ((cachedWidths as? PersistentMap<Int, StaffSpace>)
            ?: cachedWidths.toPersistentMap()).builder()
        for (seg in newSegs) {
            if (seg.measure in window) {
                widthBuilder[seg.measure] = StaffSpace(seg.endX.value - seg.startX.value)
            }
        }
        val effectiveWidths = widthBuilder.build()
        for (m in window) if (effectiveWidths[m] == null) return bail("widthMissing(m=$m)")

        val contentRightX = pageGeometry.leftMargin + pageGeometry.lineWidth
        val availWidth = (contentRightX - systemStartX).value

        val firstAffected = lineOfLocal(window.first)
        val lastAffected = lineOfLocal(window.last)
        val loCheck = (firstAffected - 1).coerceAtLeast(0)
        val hiCheck = (lastAffected + 1).coerceAtMost(cachedSystems.lastIndex)

        // A measure carrying a forced system / page break starts a new line regardless of fit. Such a
        // boundary is a firewall: greedy packing can neither pull the next measure up over it nor push the
        // first measure of the line below back across it, so the two pull-up checks below are vacuous (and
        // would wrongly fire) there. We do NOT bail on forced breaks; we simply skip the pull-up validation
        // at a forced boundary while keeping the line-fit check (a forced line must still fit on its own).
        fun isForcedBoundary(m: Int): Boolean = m in forcedSystemBreaks || m in forcedPageBreaks

        fun headerWidthOf(li: Int, firstMeasure: Int): Float =
            if (li == 0) 0f else headerComputer.compute(firstMeasure, staffTracks, computed).width.value
        fun lineSum(li: Int): Float {
            var s = 0f
            for (m in cachedSystems[li].measureRange) s += effectiveWidths[m]?.value ?: return Float.NaN
            return s
        }

        // Reflow: the cached partition no longer greedy-packs identically around the window. Instead of
        // bailing to a full solve, re-pack from the line *before* the first affected line (to absorb a
        // pull-up of the affected line's first measure), reuse the untouched prefix verbatim, and converge
        // back onto the cached tail — the first re-packed line that starts on a cached line start *past the
        // window* re-enters the cached phase, so the whole tail from there is identical. Emits a per-system
        // lineage so the renderer can reuse prefix / suffix elements across the line-count change. Returns
        // null on any structural surprise (caller then does a full, drift-free solve).
        fun reflow(): Result? {
            val segByMeasure = newSegs.associateBy { it.measure }
            val cachedLineStart = HashSet<Int>()
            for (sys in cachedSystems) cachedLineStart.add(sys.measureRange.first)

            val startRepackLine = (firstAffected - 1).coerceAtLeast(0)
            val repackStartMeasure = cachedSystems[startRepackLine].measureRange.first
            val repackSegs = newSegs.filter { it.measure >= repackStartMeasure }.sortedBy { it.measure }
            if (repackSegs.isEmpty()) return null

            // Greedy re-pack (mirrors [greedyPack]) using drift-free [effectiveWidths]. The first produced line
            // sits at global index [startRepackLine], so it only uses the in-stream initial clef when
            // startRepackLine == 0; every other line restates its header.
            val middle = ArrayList<MutableList<MeasureSeg>>()
            var current = ArrayList<MeasureSeg>()
            var running = 0f
            var currentHeaderW = 0f
            var convergedCachedLine = -1
            for (seg in repackSegs) {
                val w = effectiveWidths[seg.measure]?.value ?: return null
                if (current.isEmpty()) {
                    current.add(seg)
                    running = w
                    currentHeaderW = if (startRepackLine == 0) 0f
                        else headerComputer.compute(seg.measure, staffTracks, computed).width.value
                    continue
                }
                val forced = seg.measure in forcedSystemBreaks
                val capacity = availWidth - currentHeaderW
                if (forced || running + w > capacity) {
                    // seg.measure begins a new line. If that break coincides with a cached line start past
                    // the edit window, the fold has re-entered the cached phase → reuse the cached tail.
                    if (seg.measure > window.last && seg.measure in cachedLineStart) {
                        middle.add(current)
                        convergedCachedLine = lineOfLocal(seg.measure)
                        current = ArrayList()
                        break
                    }
                    middle.add(current)
                    current = ArrayList<MeasureSeg>().apply { add(seg) }
                    running = w
                    currentHeaderW = headerComputer.compute(seg.measure, staffTracks, computed).width.value
                } else {
                    current.add(seg)
                    running += w
                }
            }
            if (current.isNotEmpty()) middle.add(current)

            // Stitch: cached prefix + re-packed middle + cached suffix (from the convergence line).
            val stitched = ArrayList<List<MeasureSeg>>()
            val lineage = ArrayList<SystemOrigin>()
            for (li in 0 until startRepackLine) {
                stitched.add(cachedSystems[li].measureRange.mapNotNull { segByMeasure[it] })
                lineage.add(SystemOrigin.Reuse(li))
            }
            for (line in middle) {
                stitched.add(line)
                lineage.add(SystemOrigin.Regenerate)
            }
            if (convergedCachedLine >= 0) {
                for (li in convergedCachedLine..cachedSystems.lastIndex) {
                    stitched.add(cachedSystems[li].measureRange.mapNotNull { segByMeasure[it] })
                    lineage.add(SystemOrigin.Reuse(li))
                }
            }
            if (stitched.isEmpty() || stitched.any { it.isEmpty() }) return null

            return assembleSystems(
                AssembleSystemsRequest(stitched, request.content, request.page, effectiveWidths),
            ).copy(systemLineage = lineage)
        }

        // ---- Validate the cached partition still greedy-packs identically near the window. ----
        // A partition-change condition (line no longer fits / a measure pulls up) triggers a reflow rather
        // than a full solve; only structural surprises (missing widths) still bail to null.
        var needReflow = false
        for (li in loCheck..hiCheck) {
            val r = cachedSystems[li].measureRange
            val cap = availWidth - headerWidthOf(li, r.first)
            val sum = lineSum(li)
            if (sum.isNaN()) return bail("lineSumNaN(li=$li)")
            if (sum > cap) { needReflow = true; break }                       // (1) line no longer fits
            // (2) next measure still overflows — unless a forced break already ends this line.
            if (r.last < maxMeasureLocal && !isForcedBoundary(r.last + 1)) {
                val wNext = effectiveWidths[r.last + 1]?.value ?: return bail("nextWidthMissing")
                if (sum + wNext <= cap) { needReflow = true; break }          // (2) next measure pulls up
            }
            if (li > 0 && !isForcedBoundary(r.first)) {                       // (3) first measure can't pull up
                val pr = cachedSystems[li - 1].measureRange
                val capPrev = availWidth - headerWidthOf(li - 1, pr.first)
                val sumPrev = lineSum(li - 1)
                val wFirst = effectiveWidths[r.first]?.value ?: return bail("firstWidthMissing")
                if (sumPrev.isNaN()) return bail("prevSumNaN(li=$li)")
                if (sumPrev + wFirst <= capPrev) { needReflow = true; break }  // (3) first measure pulls up
            }
        }
        if (needReflow) return reflow()

        // ---- Partition holds: rebuild the line grouping over the new segments, then assemble. ----
        // Group the new segments by the cached measure→line partition. assembleSystems recomputes the rest
        // (Y stacking, pagination, justification) from the new inputs, so vertical-extent changes are
        // handled correctly while the (drift-prone) break decision is reused.
        val segByMeasure = newSegs.associateBy { it.measure }
        val lines = cachedSystems.map { sys -> sys.measureRange.mapNotNull { segByMeasure[it] } }
        if (lines.any { it.isEmpty() }) return bail("emptyLine")

        // Store the drift-free intrinsic widths (not the new segments' possibly-translated widths) so the
        // next incremental call validates against a stable reference.
        val recomputeLines = cachedSystems
            .filter { system -> system.measureRange.any { it in window } }
            .mapTo(HashSet()) { it.systemIndex }
        return assembleSystems(
            AssembleSystemsRequest(
                lines = lines,
                content = request.content,
                page = request.page,
                measureWidths = effectiveWidths,
                cachedPostBreak = cached,
                recomputeSystems = recomputeLines,
                deferVerticalToAttachments = deferVerticalToAttachments,
            ),
        )
    }

    // ----- Attachments -----

    /**
     * Tag (and split, for spans crossing a break) placed attachments by system.
     * Call after re-running the attachment computer against the stretched slots so
     * the geometry X is already in final line coordinates.
     */
    fun tagAttachments(placed: List<PlacedStaffAttachment>): List<PlacedStaffAttachment> {
        val bounds = lineParams.map { AttachmentLineBounds(it.lineStartX, it.lineEndX) }
        return AttachmentSystemTagger(
            measureToLine = measureToLine,
            maxMeasure = maxMeasure,
            lineBounds = bounds,
            lineFirstNoteX = lineFirstNoteX,
            continuationLead = continuationLead,
        ).tag(placed)
    }


    // ----- Line-start headers -----

    // ----- Single-system fallback -----

    private fun singleSystem(
        staffTracks: List<StaffInfo>,
        perMeasureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
        systemStartX: StaffSpace,
        timeSlotMap: UnifiedTimeSlotMap,
        pageGeometry: PageGeometry,
        titleBlockHeight: StaffSpace = StaffSpace.ZERO,
    ): Result {
        val slots = timeSlotMap.all()
        val maxX = slots.maxOfOrNull { it.x } ?: systemStartX
        measureToLine = emptyMap()
        maxMeasure = 1
        lineParams = listOf(
            LineParams(systemStartX, maxX, systemStartX, maxX, false, systemStartX, maxX)
        )
        // One system over the whole score: stack from the extent of all its measures, pushed
        // below the title block (page 0 only path here).
        val sortedStaffs = staffTracks.sortedWith(compareBy({ it.partIndex }, { it.staffIndex }))
        val allRange = if (perMeasureExtents.isEmpty()) 1..1
            else perMeasureExtents.keys.min()..perMeasureExtents.keys.max()
        val noteExtents = verticalComputer.extents(allRange)
        val sysStaves = staffLayoutComputer.stackStaves(
            verticalComputer.visibleStaves(sortedStaffs, allRange),
            noteExtents,
            config.topMargin + titleBlockHeight,
        )
        val sys = SystemLayout(
            systemIndex = 0,
            pageIndex = 0,
            measureRange = 1..1,
            yOffset = titleBlockHeight,
            lineStartX = systemStartX,
            lineEndX = maxX,
            staffLayouts = sysStaves,
        )
        val page = PageLayout(0, StaffSpace.ZERO, pageGeometry.paperWidth, pageGeometry.paperHeight)
        // Single-system slots already carry their X and systemIndex 0 — pass the map through unchanged.
        return Result(listOf(sys), listOf(page), emptySet(), timeSlotMap)
    }
}


/** Shift all Y coordinates of a staff layout by [dy] (for vertical system stacking). */
internal fun StaffLayoutInfo.shiftedBy(dy: StaffSpace): StaffLayoutInfo = copy(
    centerY = centerY + dy,
    topY = topY + dy,
    bottomY = bottomY + dy,
    contentTopY = contentTopY + dy,
    contentBottomY = contentBottomY + dy,
)
