package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.PluginRegistry
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.KeySignatureElement
import com.mecon.renderer.elements.LayoutElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.elements.TimeSignatureElement
import com.mecon.renderer.geometry.StaffSpace

context(com.mecon.renderer.smufl.BravuraFont)
internal class UnifiedHorizontalSlotComputer(
    private val config: RenderLayoutConfig,
) {
    private val horizontalComputer = HorizontalSpacingComputer(config)
    private val proportionalLayoutComputer = ProportionalLayoutComputer(config, horizontalComputer)
    private val multiVoiceCollisionResolver = MultiVoiceSlotCollisionResolver(config)
    private val annotationSpacingMeasurer = AnnotationElementMeasurer()
    private val annotationLabelGap = StaffSpace(0.5f)
    /**
     * Assign each event's negative relativeX within a single time slot (all [slotEvents] share a time).
     * Events are sorted by priority (Barline → Clef → Key Signature → Time Signature → Notes) and each
     * priority group placed sequentially. relativeX values are **negative** — the offset from the slot's
     * right edge (slot.x) to the element's left edge — so slot.x is the rightmost position at that time.
     *
     * relativeX depends **only** on the same-slot events (their priority + minimumWidth), so a slot's
     * layout is independent of every other slot — the property [buildBaseTimeSlotMap] exploits to reuse
     * unchanged slots across an incremental frame.
     */
    private fun assignRelativeXWithinSlot(slotEvents: List<LayoutElement>): List<LayoutElement> {
        val resolvedNotes = multiVoiceCollisionResolver.resolve(slotEvents.filterIsInstance<NoteElement>())
            .associateBy { it.eventId }
        val resolvedEvents = slotEvents.map { event ->
            if (event is NoteElement) resolvedNotes[event.eventId] ?: event else event
        }

        // Group by priority within this time slot
        val byPriority = resolvedEvents.groupBy { it.priority }.entries.sortedBy { it.key }

        // First pass: calculate total width of all priority groups
        var totalWidth = StaffSpace.ZERO
        for ((priority, priorityEvents) in byPriority) {
            val maxWidth = priorityEvents.maxOfOrNull { it.minimumWidth } ?: StaffSpace.ZERO
            val spacing = getSpacingAfterPriority(priority)
            totalWidth += maxWidth + spacing
        }

        // Second pass: assign relativeX starting from -totalWidth
        // This makes relativeX negative, with the last group ending near 0
        var offsetX = -totalWidth
        val result = mutableListOf<LayoutElement>()

        for ((priority, priorityEvents) in byPriority) {
            // Calculate max width for this priority group
            val maxWidth = priorityEvents.maxOfOrNull { it.minimumWidth } ?: StaffSpace.ZERO

            // Create new events with negative relativeX
            for (event in priorityEvents) {
                result.add(event.withRelativeX(offsetX))
            }

            // Move offsetX to the right for the next priority group
            val spacing = getSpacingAfterPriority(priority)
            offsetX += maxWidth + spacing
        }
        // At this point, offsetX ≈ 0 (the rightmost edge)

        return result
    }

    /**
     * Build the pre-proportional slot map (see docs/renderer/incremental-rendering.md `xsolve` steps 2–3): group events by
     * time, assign per-slot relativeX ([assignRelativeXWithinSlot]), and emit priority/staff-sorted
     * [UnifiedTimeSlot]s — the former two group passes (relativeX + `fromEvents`) fused into one. Full
     * (non-incremental) path only; the incremental path uses [spliceBaseTimeSlotMap].
     */
    fun buildBaseTimeSlotMap(allEvents: List<LayoutElement>): UnifiedTimeSlotMap {
        val slots = allEvents.groupBy { it.time }.entries
            .sortedBy { it.key }
            .map { (time, slotEvents) -> UnifiedTimeSlot.fromEvents(time, assignRelativeXWithinSlot(slotEvents)) }
        return UnifiedTimeSlotMap(slots)
    }

    /**
     * Incremental base slot map (see docs/renderer/incremental-rendering.md `xsolve` / `collect`): splice the window's fresh
     * slots into the [cached] map instead of grouping+sorting the whole score. [windowEvents] are the
     * window-only collection ([EventCollector] with `windowOnly`); [cached] is the previous frame's
     * pre-break slot map.
     *
     * A slot belongs to the window iff its `time.measure` is in [window] — grace notes carry their
     * principal's measure in `time.measure`, so this predicate keeps them with their measure and the
     * window block stays contiguous in time order. Out-of-window cached slots are reused verbatim (their
     * content cannot have changed under a window-confined edit — the same invariant as steps ②–④); the
     * window's slots are rebuilt from [windowEvents]. The two already-sorted runs are merged by time
     * (O(N + W)), avoiding the whole-score `groupBy` + `sortedBy`. Slot X is irrelevant here (step 4
     * overwrites every slot's X), so reused slots reset X / systemIndex to the fresh-build defaults.
     */
    fun spliceBaseTimeSlotMap(
        windowEvents: List<LayoutElement>,
        cached: UnifiedTimeSlotMap,
        window: IntRange
    ): UnifiedTimeSlotMap {
        val windowSlots = windowEvents.groupBy { it.time }.entries
            .sortedBy { it.key }
            .map { (time, slotEvents) -> UnifiedTimeSlot.fromEvents(time, assignRelativeXWithinSlot(slotEvents)) }
        val keptSlots = cached.all()
            .filter { it.time.measure !in window }
            .map { it.copy(x = StaffSpace.ZERO, systemIndex = 0) }
        // Merge the two time-sorted runs (window measures and the rest are disjoint by measure).
        val merged = ArrayList<UnifiedTimeSlot>(keptSlots.size + windowSlots.size)
        var i = 0
        var j = 0
        while (i < keptSlots.size && j < windowSlots.size) {
            if (keptSlots[i].time <= windowSlots[j].time) merged.add(keptSlots[i++])
            else merged.add(windowSlots[j++])
        }
        while (i < keptSlots.size) merged.add(keptSlots[i++])
        while (j < windowSlots.size) merged.add(windowSlots[j++])
        return UnifiedTimeSlotMap(merged)
    }

    /**
     * Get spacing to add after a priority group.
     */
    private fun getSpacingAfterPriority(priority: Int): StaffSpace = when (priority) {
        LayoutElement.PRIORITY_BARLINE -> config.spaceAfterBarline
        LayoutElement.PRIORITY_CLEF -> config.spaceAfterClef
        LayoutElement.PRIORITY_KEY_SIGNATURE -> config.spaceAfterKeySignature
        LayoutElement.PRIORITY_TIME_SIGNATURE -> config.spaceAfterTimeSignature
        else -> StaffSpace.ZERO
    }

    /**
     * Create a copy of the layout event with the specified relativeX.
     */
    private fun LayoutElement.withRelativeX(newRelativeX: StaffSpace): LayoutElement = when (this) {
        is NoteElement -> copy(relativeX = newRelativeX + relativeX)
        is BarlineElement -> copy(relativeX = newRelativeX)
        is ClefElement -> copy(relativeX = newRelativeX)
        is KeySignatureElement -> copy(relativeX = newRelativeX)
        is TimeSignatureElement -> copy(relativeX = newRelativeX)
    }

    /**
     * Build annotation label spacing participants (chord symbols etc.) for the horizontal solve.
     *
     * Mirrors [AnnotationStaffLayoutComputer.perLineExtentFn]: pull every applicable
     * [com.mecon.api.plugin.AnnotationStaffProvider] from the registry, invoke it with a width-only
     * context ([AnnotationLayoutContext.xForTime] returns null — providers such as
     * [com.mecon.plugins.chord.ChordAnnotationProvider] don't need X to produce elements), measure each
     * label, and emit one [AnnotationSpacingParticipant] per element carrying `labelWidth + gap`.
     *
     * [windowMeasures] restricts the elements to the incremental re-solve window (null ⇒ whole score);
     * window-external measures translate their cached X, which already reserved the label room.
     * Returns empty (the common no-plugin case) when nothing applies.
     */
    fun buildAnnotationSpacingParticipants(
        computed: ComputedScore,
        windowMeasures: IntRange?
    ): List<AnnotationSpacingParticipant> {
        val providers = PluginRegistry.annotationStaffProviders()
        if (providers.isEmpty()) return emptyList()

        val trackTypesInScore = computed.pluginTracks.values.map { it.type }.toSet()
        val applicable = providers.filter { provider ->
            provider.pluginTrackTypes.isEmpty() ||
                provider.pluginTrackTypes.any { type -> type in trackTypesInScore }
        }
        if (applicable.isEmpty()) return emptyList()

        val ctx = object : AnnotationLayoutContext {
            override val computedScore: ComputedScore = computed
            override fun xForTime(time: TimeCode): Float? = null
        }

        return applicable.flatMap { provider ->
            provider.layout(ctx).mapNotNull { element ->
                when (element) {
                    is AnnotationElement.Text -> {
                        val trackId = element.trackId ?: return@mapNotNull null
                        if (windowMeasures != null && element.time.measure !in windowMeasures) {
                            return@mapNotNull null
                        }
                        val width = annotationSpacingMeasurer.widthStaffSpace(element) + annotationLabelGap
                        AnnotationSpacingParticipant(element.time, trackId, width)
                    }
                }
            }
        }
    }

    /**
     * Calculate X positions for all time slots using proportional layout algorithm.
     *
     * This algorithm processes the score measure by measure:
     * 1. Layout system start elements (initial barline, clef, key signature, time signature)
     * 2. For each measure:
     *    a. Run proportional layout on note events within the measure
     *    b. Align the ending barline across all staves
     *
     * This ensures barlines are vertically aligned while notes within each
     * measure are spaced proportionally.
     */
    fun calculateTimeSlotXPositionsProportional(
        timeSlotMap: UnifiedTimeSlotMap,
        startX: StaffSpace,
        computedScore: ComputedScore? = null,
        cached: UnifiedTimeSlotMap? = null,
        solveWindow: IntRange? = null,
        annotationSpacing: List<AnnotationSpacingParticipant> = emptyList()
    ): UnifiedTimeSlotMap {
        val allSlots = timeSlotMap.all()
        if (allSlots.isEmpty()) return timeSlotMap

        if (allSlots.none { it.events.isNotEmpty() }) return timeSlotMap

        // Find barlines without materialising `allSlots.flatMap { events }` (≈66k entries on Mahler).
        val barlineEvents = buildList {
            for (slot in allSlots) for (event in slot.events) if (event is BarlineElement) add(event)
        }

        // Get all unique measure numbers and their boundaries
        val measureBoundaries = buildMeasureBoundaries(barlineEvents, allSlots)

        // Bucket events into their measure once (O(N log N) + O(N)) instead of re-scanning the whole
        // event list per measure (which was O(measures × events) — the dominant cost on long scores).
        // Boundaries are contiguous and sorted (endTime[i] == startTime[i+1]), so a single merge walk
        // over time-sorted events reproduces the old `time >= startTime && time < endTime` filter exactly.
        val slotsByMeasureIndex = bucketSlotsByMeasure(allSlots, measureBoundaries)

        // Result map: TimeCode -> X position
        val timeToX = mutableMapOf<TimeCode, StaffSpace>()

        var currentX = startX

        // Per-track carry of the previous measure's last chord label, so a label near a barline still
        // constrains the first chord of the next measure (cross-barline overlap). Persists across measures
        // with no labels; only updated for a track when that track has a label in the just-solved measure.
        val annotationCarry = HashMap<TrackId, AnnotationCarry>()

        // Incremental layout (measure-granular): re-solve only measures in [solveWindow], reuse [cached]
        // X for the rest. The proportional solve is a causal left-to-right pass whose tail is an affine
        // (slope-1) function of the start X, so every unchanged measure after the re-solved window
        // translates rigidly by [delta] — the shift the window introduced. [delta] is 0 before the
        // window and constant after it (changeSet.affectedMeasures is contiguous). See
        // docs/renderer/incremental-rendering.md.
        val incremental = cached != null && solveWindow != null
        var delta = StaffSpace.ZERO

        // Grace notes have a negative grace component in their TimeCode and can fall
        // outside the measure boundary of their principal (e.g. grace on beat 0 of
        // measure N+1 sorts below the barline at measure N+1).  Index grace notes
        // by their principal's (measure, beat) so each measure loop can find them
        // regardless of which boundary they fall in. On the incremental path only the
        // re-solve window's graces are needed — an out-of-window measure copies its cached
        // slot X wholesale (grace slots included) via [translateCachedMeasure] and never
        // re-runs grace positioning — so the index is restricted to the window (a grace's
        // `time.measure` is its principal's measure, so this keeps every grace its window
        // measure will look up).
        val gracesForPrincipal: Map<TimeCode, List<NoteElement>> =
            (if (incremental) allSlots.asSequence().filter { it.time.measure in solveWindow!! }
                else allSlots.asSequence())
                .flatMap { it.events.asSequence() }
                .filterIsInstance<NoteElement>()
                .filter { it.time.grace != null && (!incremental || it.time.measure in solveWindow!!) }
                .groupBy { TimeCode.of(it.time.measure, it.time.beat ?: Fraction.ZERO) }

        // Process each measure
        for ((measureIndex, boundary) in measureBoundaries.withIndex()) {
            // Slots for this measure segment (pre-bucketed above). Window-external measures never
            // materialise their event list; they only translate cached slot X values.
            val measureSlots = slotsByMeasureIndex[measureIndex]

            if (measureSlots.isEmpty()) continue

            // Whether this boundary's measure is in the re-solve window. A boundary is keyed to the
            // measure of its own (non-grace) notes — not [boundary.measureNumber], which is the
            // *ending* barline's number and lags the contained notes' onset.measure by one (the
            // initial barline is measureNumber 0). [solveWindow] is onset.measure-based.
            val measureInWindow = !incremental || measureSlots.any { slot ->
                slot.time.measure in solveWindow!! && slot.events.any {
                    it is NoteElement && it.time.grace == null
                }
            }

            // Unchanged measure on the incremental path: translate its cached X by the running [delta]
            // rather than re-running the proportional solve for it.
            if (incremental && !measureInWindow) {
                currentX = translateCachedMeasureSlots(measureSlots, cached!!, delta, timeToX)
                continue
            }

            val measureEvents = measureSlots.flatMap { it.events }

            // Annotation label spacing participants whose onset falls in this measure — they reserve
            // room so notes reflow to fit long chord symbols (and, at measure end, push the barline).
            val measureAnnotations = if (annotationSpacing.isEmpty()) emptyList()
                else annotationSpacing.filter { it.time >= boundary.startTime && it.time < boundary.endTime }

            // Separate system elements (barline, clef, key sig, time sig) from note events.
            // Grace notes are handled via gracesForPrincipal and never enter the proportional
            // layout directly.
            val systemElementsAtMeasureStart = measureEvents.filter { event ->
                event.time == boundary.startTime && event !is NoteElement
            }
            val inlineSystemElements = measureEvents.filter { event ->
                event.time != boundary.startTime && event !is NoteElement
            }
            val noteEventsInMeasure = measureEvents.filterIsInstance<NoteElement>()
                .filter { it.time.grace == null }

            // Add space before barline (if not the first measure and if there's a barline)
            val hasBarlineAtStart = systemElementsAtMeasureStart.any { it is BarlineElement }
            if (measureIndex > 0 && hasBarlineAtStart) {
                currentX += config.spaceBeforeBarline
            }

            // Step 1: Layout system start elements sequentially (barline, clef, key sig, time sig)
            currentX = layoutSystemStartElements(systemElementsAtMeasureStart, currentX, timeToX)
            // Left edge of this measure's note content — where empty-measure padding measures from.
            val measureContentStartX = currentX

            // Step 2: Run proportional layout on notes within the measure.
            // Principals that have associated grace notes receive an inflated leftOverhang
            // (via extraLeftOverhang) so the proportional algorithm reserves the exact space
            // the grace cluster needs to the left of the principal — including any accidentals
            // on the first grace note.
            val augmentedNoteEvents = if (noteEventsInMeasure.isNotEmpty()) {
                val augmentedEvents = noteEventsInMeasure.map { event ->
                    val graces = gracesForPrincipal[event.time]
                    if (!graces.isNullOrEmpty()) {
                        event.copy(extraLeftOverhang = computeGraceClusterOverhang(graces))
                    } else {
                        event
                    }
                }
                augmentedEvents
            } else {
                emptyList()
            }
            val proportionalEvents = augmentedNoteEvents + inlineSystemElements

            if (proportionalEvents.isNotEmpty()) {
                val measureTimeToX = proportionalLayoutComputer.computeXPositions(
                    proportionalEvents,
                    currentX,
                    measureAnnotations,
                    annotationCarry
                )

                // Merge into main result
                timeToX.putAll(measureTimeToX)

                // Update currentX to the rightmost position
                currentX = measureTimeToX.values.maxOrNull() ?: currentX

                // Carry each track's last label into the next measure's solve (cross-barline overlap).
                // A lone label with no following label pushes nothing here — only the *next* label, if any,
                // consults this carry, so the label simply overhangs the following notes on its own band.
                for ((trackId, group) in measureAnnotations.groupBy { it.trackId }) {
                    val last = group.maxByOrNull { it.time } ?: continue
                    val anchorX = timeToX[last.time] ?: continue
                    annotationCarry[trackId] = AnnotationCarry(anchorX, last.width)
                }

                // Step 3: Place each grace cluster at fixed offsets to the left of its principal.
                // Because extraLeftOverhang has already reserved the exact space required, the
                // grace notes will slot in without overlap.
                val graceEventsForMeasure = noteEventsInMeasure
                    .flatMap { gracesForPrincipal[it.time] ?: emptyList() }
                if (graceEventsForMeasure.isNotEmpty()) {
                    positionGraceNoteGroups(graceEventsForMeasure, noteEventsInMeasure, timeToX)
                }
            }

            // Empty-bar padding: a measure whose voices hold only rests (or nothing) is widened to the
            // minimum measure width so there is room to hover/insert at each beat while editing. Opt-in
            // (off for snapshot tests); see RenderLayoutConfig.padEmptyMeasures.
            if (config.padEmptyMeasures && noteEventsInMeasure.all { it.isRest }) {
                val minEnd = measureContentStartX + config.minimumMeasureWidth
                if (currentX < minEnd) currentX = minEnd
            }

            // After re-solving a window measure, refresh the shift for subsequent unchanged measures:
            // how far this measure's right edge moved vs. the cached layout.
            if (incremental) {
                cachedMeasureEndX(
                    measureIndex = measureIndex,
                    boundary = boundary,
                    measureEvents = measureEvents,
                    slotsByMeasureIndex = slotsByMeasureIndex,
                    cached = cached!!,
                )?.let { delta = currentX - it }
            }
        }

        // Layout the final barline (if exists)
        val lastBarline = barlineEvents.lastOrNull()
        if (lastBarline != null && !timeToX.containsKey(lastBarline.time)) {
            // Add spacing before the barline
            currentX += config.spaceBeforeBarline
            // Add the barline width to get the right edge position
            val barlineRightEdge = currentX + lastBarline.minimumWidth + config.spaceAfterBarline
            timeToX[lastBarline.time] = barlineRightEdge
        }

        // Apply X positions to time slots immutably (new slots, same order).
        return timeSlotMap.mapSlots { it.copy(x = timeToX[it.time] ?: startX) }
    }

    /** Slot-level unchanged-measure translation; avoids walking every staff event in the measure. */
    private fun translateCachedMeasureSlots(
        measureSlots: List<UnifiedTimeSlot>,
        cached: UnifiedTimeSlotMap,
        delta: StaffSpace,
        timeToX: MutableMap<TimeCode, StaffSpace>,
    ): StaffSpace {
        var maxX: StaffSpace? = null
        for (slot in measureSlots) {
            val cachedX = cached.atTime(slot.time)?.x ?: continue
            val newX = cachedX + delta
            timeToX[slot.time] = newX
            if (maxX == null || newX > maxX) maxX = newX
        }
        return maxX ?: StaffSpace.ZERO
    }

    /**
     * Cached right edge (max cached X) of a measure's slots — the original currentX after that measure.
     * This is called only for a freshly solved window measure, whose event list is deliberately small.
     */
    private fun cachedMeasureEndX(
        measureIndex: Int,
        boundary: MeasureBoundary,
        measureEvents: List<LayoutElement>,
        slotsByMeasureIndex: List<List<UnifiedTimeSlot>>,
        cached: UnifiedTimeSlotMap
    ): StaffSpace? {
        // Prefer the following boundary slot. Empty-measure padding advances currentX without moving
        // the rest slot, so the last slot inside the measure is not necessarily its old right edge.
        // The next barline *does* start from that padded edge; subtract its deterministic leading
        // spacing and same-slot element width to recover the exact pre-barline currentX.
        val nextBoundaryEvents = slotsByMeasureIndex
            .getOrNull(measureIndex + 1)
            .orEmpty()
            .firstOrNull { it.time == boundary.endTime }
            ?.events
            .orEmpty()
            .filter { it !is NoteElement }
        val cachedBoundaryX = cached.atTime(boundary.endTime)?.x
        if (cachedBoundaryX != null && nextBoundaryEvents.isNotEmpty()) {
            val hasBarline = nextBoundaryEvents.any { it is BarlineElement }
            val leadingSpacing = if (measureIndex + 1 > 0 && hasBarline) {
                config.spaceBeforeBarline
            } else {
                StaffSpace.ZERO
            }
            val sameSlotWidth = nextBoundaryEvents
                .groupBy { it.priority }
                .entries
                .sumOfStaffSpace { (priority, events) ->
                    (events.maxOfOrNull { it.minimumWidth } ?: StaffSpace.ZERO) +
                        getSpacingAfterPriority(priority)
                }
            return cachedBoundaryX - leadingSpacing - sameSlotWidth
        }

        // Scores without a following boundary retain the old slot-based fallback.
        var maxX: StaffSpace? = null
        var prev: TimeCode? = null
        for (event in measureEvents) {
            val time = event.time
            if (time == prev) continue
            prev = time
            val cachedX = cached.atTime(time)?.x ?: continue
            if (maxX == null || cachedX > maxX) maxX = cachedX
        }
        return maxX
    }

    private inline fun <T> Iterable<T>.sumOfStaffSpace(selector: (T) -> StaffSpace): StaffSpace {
        var sum = StaffSpace.ZERO
        for (item in this) sum += selector(item)
        return sum
    }

    /**
     * Compute the extra leftOverhang that a principal note needs to reserve room
     * for its preceding grace-note cluster.
     *
     * The cluster occupies (right-to-left from the principal):
     *   GRACE_NOTE_PRINCIPAL_GAP + sum(graceWidths) + (N−1)×GRACE_NOTE_SPACING
     *
     * plus the leftOverhang of the first (leftmost) grace note so that any
     * accidental on that grace note does not overlap the preceding content.
     */
    private fun computeGraceClusterOverhang(graces: List<NoteElement>): StaffSpace {
        val uniqueTimes = graces.map { it.time }.distinct().sortedBy { it }
        val n = uniqueTimes.size

        val maxWidthByTime = uniqueTimes.associateWith { t ->
            graces.filter { it.time == t }.maxOf { it.minimumWidth }
        }

        var total = config.graceNotePrincipalGap
        for ((i, t) in uniqueTimes.withIndex()) {
            total += maxWidthByTime[t]!!
            if (i < n - 1) total += config.graceNoteSpacing
        }

        // Accidentals on the first grace note extend further left than its slot X
        val firstGraceOverhang = graces
            .filter { it.time == uniqueTimes.first() }
            .maxOf { it.leftOverhang }

        return total + firstGraceOverhang
    }

    /**
     * Write slot X values for each grace TimeCode into [timeToX].
     *
     * The principal's slot X is already known (placed by the proportional algorithm
     * with an inflated leftOverhang).  Walk the grace group right-to-left:
     *
     *   lastGrace slotX = principalLeftEdge − GRACE_NOTE_PRINCIPAL_GAP
     *   prevGrace slotX = nextSlotX − nextGraceWidth − GRACE_NOTE_SPACING
     *
     * Multiple staves at the same TimeCode share one slot X; the maximum width
     * across staves is used so no staff overflows.
     */
    private fun positionGraceNoteGroups(
        graceEvents: List<NoteElement>,
        normalEvents: List<NoteElement>,
        timeToX: MutableMap<TimeCode, StaffSpace>
    ) {
        val graceWidthByTime = graceEvents.groupBy { it.time }
            .mapValues { (_, evs) -> evs.maxOf { it.minimumWidth } }

        val normalWidthByTime = normalEvents.groupBy { it.time }
            .mapValues { (_, evs) -> evs.maxOf { it.minimumWidth } }

        val byPrincipal = graceEvents.groupBy { e ->
            TimeCode.of(e.time.measure, e.time.beat ?: Fraction.ZERO)
        }

        for ((principalTime, graceGroup) in byPrincipal) {
            val principalSlotX = timeToX[principalTime] ?: continue
            val principalWidth = normalWidthByTime[principalTime] ?: StaffSpace.ZERO
            val principalLeftEdge = principalSlotX - principalWidth

            val uniqueTimes = graceGroup.map { it.time }.distinct().sortedBy { it }

            var nextRightEdge = principalLeftEdge - config.graceNotePrincipalGap
            for (k in uniqueTimes.indices.reversed()) {
                val graceTime = uniqueTimes[k]
                val graceWidth = graceWidthByTime[graceTime] ?: StaffSpace.ZERO
                timeToX[graceTime] = nextRightEdge
                nextRightEdge = nextRightEdge - graceWidth - config.graceNoteSpacing
            }
        }
    }

    /**
     * Represents a measure boundary with start and end times.
     */
    private data class MeasureBoundary(
        val measureNumber: Int,
        val startTime: TimeCode,
        val endTime: TimeCode
    )

    /**
     * Bucket the **time-sorted** [sortedEvents] into one list per [boundaries] entry, equivalent to
     * filtering `event.time >= boundary.startTime && event.time < boundary.endTime` for each boundary but
     * in a single O(N + M) merge walk rather than O(M × N) repeated scans.
     *
     * Precondition: [sortedEvents] is non-decreasing by time — the caller passes `allSlots.flatMap { events }`
     * over a time-ordered slot map, so an internal sort would be redundant (removed). Boundaries are
     * contiguous and sorted by startTime (`endTime[i] == startTime[i+1]`), so a left-to-right walk over the
     * events advances the boundary pointer past any boundary the event has reached the end of, then assigns
     * the event to the current boundary when it is at/after its start. Events before the first boundary's
     * start are dropped — exactly as the per-boundary filter did.
     */
    private fun bucketSlotsByMeasure(
        sortedSlots: List<UnifiedTimeSlot>,
        boundaries: List<MeasureBoundary>
    ): List<List<UnifiedTimeSlot>> {
        val buckets = List(boundaries.size) { mutableListOf<UnifiedTimeSlot>() }
        if (boundaries.isEmpty()) return buckets
        var bi = 0
        for (slot in sortedSlots) {
            while (bi < boundaries.size && slot.time >= boundaries[bi].endTime) bi++
            if (bi >= boundaries.size) break
            if (slot.time >= boundaries[bi].startTime) buckets[bi].add(slot)
        }
        return buckets
    }

    /**
     * Build measure boundaries from barline events.
     */
    private fun buildMeasureBoundaries(
        barlineEvents: List<BarlineElement>,
        allSlots: List<UnifiedTimeSlot>
    ): List<MeasureBoundary> {
        if (barlineEvents.isEmpty()) {
            // No barlines - treat entire score as one measure
            val minTime = allSlots.firstOrNull()?.time ?: return emptyList()
            val maxTime = allSlots.lastOrNull()?.time ?: return emptyList()
            // Create an end time that's after all events (next measure)
            val endTime = TimeCode.ofMeasure(maxTime.measure + 1)
            return listOf(MeasureBoundary(0, minTime, endTime))
        }

        val boundaries = mutableListOf<MeasureBoundary>()

        for (i in barlineEvents.indices) {
            val startBarline = barlineEvents[i]
            val endTime = if (i + 1 < barlineEvents.size) {
                barlineEvents[i + 1].time
            } else {
                // Last measure - create an end time after all events
                val maxEventTime = allSlots.lastOrNull()?.time ?: startBarline.time
                TimeCode.ofMeasure(maxEventTime.measure + 1)
            }

            boundaries.add(MeasureBoundary(
                measureNumber = startBarline.measureNumber,
                startTime = startBarline.time,
                endTime = endTime
            ))
        }

        return boundaries
    }

    /**
     * Layout system start elements (barline, clef, key signature, time signature) sequentially.
     *
     * This method calculates the right edge position for all system elements at this time.
     * The returned value is the right edge position, which can be used as the starting
     * point for the next batch of elements.
     *
     * @param events List of system elements to layout
     * @param startX The left edge position (where these elements start)
     * @param timeToX Map to store TimeCode -> right edge X position
     * @return The right edge X position after all elements
     */
    private fun layoutSystemStartElements(
        events: List<LayoutElement>,
        startX: StaffSpace,
        timeToX: MutableMap<TimeCode, StaffSpace>
    ): StaffSpace {
        if (events.isEmpty()) return startX

        // Group by priority
        val byPriority = events.groupBy { it.priority }.entries.sortedBy { it.key }

        // Calculate total width of all priority groups
        var totalWidth = StaffSpace.ZERO
        for ((priority, priorityEvents) in byPriority) {
            val maxWidth = priorityEvents.maxOfOrNull { it.minimumWidth } ?: StaffSpace.ZERO
            val spacing = getSpacingAfterPriority(priority)
            totalWidth += maxWidth + spacing
        }

        // Right edge position
        val rightEdge = startX + totalWidth
        val time = events.first().time

        // Store the right edge position in timeToX
        if (!timeToX.containsKey(time)) {
            timeToX[time] = rightEdge
        }

        return rightEdge
    }

}
