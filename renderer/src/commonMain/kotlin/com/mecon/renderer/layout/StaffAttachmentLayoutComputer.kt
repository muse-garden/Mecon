package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedDynamicMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.HairpinGeometry
import com.mecon.renderer.geometry.IntervalAttachmentGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.SpanEnd
import com.mecon.renderer.geometry.SpanLineStyle
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.TempoMarkGeometry
import com.mecon.renderer.geometry.TempoMarkPart
import com.mecon.renderer.render.DynamicGlyphs
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Places staff attachments (dynamics, hairpins) into vertical bands above / below
 * each staff, stacking overlapping marks into rows and reporting the extra
 * vertical room each staff needs.
 *
 * Runs after horizontal X positions are known (so anchor X can be read from the
 * time-slot map) but before staff Y positions are fixed, so the [AttachmentExtent]
 * it returns can feed [StaffLayoutComputer].
 */
internal class StaffAttachmentLayoutComputer(
    private val config: RenderLayoutConfig
) {
    /** Approximate offset from a slot's right edge back to the note centre. */
    private val noteCentreLead = StaffSpace(0.6f)

    /**
     * Neighbourhood added on each side of a mark's horizontal span when sampling the
     * local note extent that anchors it vertically. Wide enough to always catch the note
     * the mark sits on plus immediately adjacent notes, narrow enough that a distant
     * high / low note on the same line no longer influences the mark.
     */
    private val localExtentMargin = StaffSpace(1.5f)

    /**
     * Minimum horizontal clearance used by [packRows] between two adjacent
     * attachment intervals on the same row.  Any nudge applied in [buildHairpin]
     * must produce a gap that is **strictly larger** than this value so that the
     * nudged hairpin and the dynamic mark it avoids are assigned to the same row.
     */
    private val rowPackGap = 0.5f

    context(BravuraFont)
    fun compute(
        computed: ComputedScore,
        timeSlotMap: UnifiedTimeSlotMap,
        /**
         * Per-(systemIndex, staffIndex) note vertical extent. Used only to size the staff-spacing
         * RESERVE (so staves never overlap); the *visible* Y of each mark comes from
         * [noteExtentIndex] instead. In continuous mode there is a single system 0. Missing
         * entries default to (2, 2).
         */
        noteExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
        /**
         * X-keyed note extents used to anchor each mark to the notes LOCAL to its span, rather
         * than to the line-global extent. See [NoteExtentIndex].
         */
        noteExtentIndex: NoteExtentIndex,
        /**
         * Persisted geometry overlay (Phase 3). For a span attachment that has an entry, the stored
         * vertical position is **authoritative** — its band Y comes from the overlay instead of the
         * auto local-extent + row stack. X still follows the current slots (so it tracks moved notes);
         * only Y is honoured. When the stored Y equals the captured auto Y this is an exact identity,
         * so an overlay that mirrors auto-layout renders pixel-for-pixel the same. Absent / null → auto.
         */
        geometry: com.mecon.api.storage.ScoreGeometry? = null,
        /** Optional paginated-system window. Null keeps the full-score behaviour. */
        systemFilter: Set<Int>? = null,
        /**
         * Optional pre-indexed subset whose anchor measure can occur in [systemFilter]. Incremental
         * pagination supplies this from the cached measure chunks, avoiding a whole-score attachment
         * scan merely to retain the few marks in the edited system.
         */
        attachmentCandidates: List<ComputedStaffAttachment>? = null,
    ): StaffAttachmentLayoutResult {
        if (computed.staffAttachments.isEmpty()) return StaffAttachmentLayoutResult.EMPTY

        val placed = mutableListOf<PlacedStaffAttachment>()
        // systemIndex → staffIndex → reserved extra room.
        val extents = mutableMapOf<Int, MutableMap<Int, AttachmentExtent>>()
        fun noteExt(system: Int, staff: Int): Pair<StaffSpace, StaffSpace> =
            noteExtents[system]?.get(staff) ?: (StaffSpace(2f) to StaffSpace(2f))
        fun bump(system: Int, staff: Int, top: StaffSpace, bottom: StaffSpace) {
            val perStaff = extents.getOrPut(system) { mutableMapOf() }
            val cur = perStaff[staff] ?: AttachmentExtent()
            perStaff[staff] = AttachmentExtent(
                extraTop = maxOf(cur.extraTop, top),
                extraBottom = maxOf(cur.extraBottom, bottom),
            )
        }

        val candidates = attachmentCandidates ?: computed.staffAttachments
        val attachments = if (systemFilter == null) candidates else
            candidates.filter { attachment ->
                timeSlotMap.atTime(attachment.time)?.systemIndex in systemFilter
            }
        val byStaff = attachments.groupBy { it.staffIndex }
        for ((staffIndex, attachments) in byStaff) {
            // Pass 1: build dynamic marks and record their X extents so that
            // hairpins whose endpoints coincide can be nudged to the same row.
            val dynamicExtents =
                mutableMapOf<Pair<TimeCode, StaffAttachmentPlacement>, Pair<StaffSpace, StaffSpace>>()
            val dynamicRaws = attachments.filterIsInstance<ComputedDynamicMark>().mapNotNull { mark ->
                buildDynamic(mark, timeSlotMap)?.also { raw ->
                    dynamicExtents[mark.time to mark.placement] = raw.xStart to raw.xEnd
                }
            }
            // Pass 2: hairpins, nudged past any co-located dynamic mark.
            val hairpinRaws = attachments.filterIsInstance<ComputedHairpin>()
                .mapNotNull { buildHairpin(it, timeSlotMap, dynamicExtents) }
            // Octave shifts and any future attachment types fall through the generic path.
            val otherRaws = attachments
                .filter { it !is ComputedDynamicMark && it !is ComputedHairpin }
                .mapNotNull { buildRaw(it, timeSlotMap) }
            val raws = dynamicRaws + hairpinRaws + otherRaws
            val bySide = raws.groupBy { it.attachment.placement }

            for ((side, sideRaws) in bySide) {
                val rowHeight = sideRaws.maxOf { it.height }
                // Pack rows independently per system: marks on different lines share the
                // same justified X band, so packing them together would falsely collide
                // (e.g. dynamics in the last measure of two different lines).
                val rowOf = IntArray(sideRaws.size)
                sideRaws.indices.groupBy { sideRaws[it].systemIndex }.forEach { (_, idxs) ->
                    val rows = packRowsWithPriority(idxs.map { sideRaws[it] })
                    idxs.forEachIndexed { k, gi -> rowOf[gi] = rows[k] }
                }

                for ((raw, row) in sideRaws.zip(rowOf.toList())) {
                    // Vertical anchor: the note extent LOCAL to this mark's horizontal span (plus a
                    // small neighbourhood). An isolated high / low note elsewhere on the line no
                    // longer pushes the mark out, and an edit only perturbs marks over its own span.
                    //
                    // A cross-system-break span is special: its normalised [xStart, xEnd] covers the
                    // whole justified band (its end wrapped onto a later line, so end < start before the
                    // per-line split). Sampling over that band would grab the lowest note anywhere on the
                    // start line — including the dead region LEFT of where the wedge actually begins — and
                    // push the mark far below its notes. Anchor it instead to its true drawn region on the
                    // start line: from the real start X (= the normalised xEnd) rightward to the line end.
                    val (sampleStart, sampleEnd) =
                        if (raw.crossesSystemBreak) raw.xEnd to StaffSpace(Float.POSITIVE_INFINITY)
                        else raw.xStart to raw.xEnd
                    val (localTop, localBottom) = noteExtentIndex.localExtent(
                        raw.systemIndex, staffIndex, sampleStart, sampleEnd, localExtentMargin
                    )
                    val step = rowHeight + config.dynamicRowSpacing
                    val stored = if (raw.attachment is ComputedVoltaAttachment) null
                        else geometry?.attachments?.get(raw.attachment.id)
                    // Manual endpoint Y survives re-layout. If new note/staff extents now intersect it,
                    // translate both endpoints by the shortest common amount that clears the collision;
                    // this preserves a wedge's user-authored slope.
                    val manualYShift = if (stored?.manuallyAdjustedY == true) {
                        val startY = stored.startDy
                        val endY = stored.endDy ?: startY
                        val halfHeight = raw.height.value / 2f
                        if (side == StaffAttachmentPlacement.BELOW) {
                            val minimum = localBottom.value + config.dynamicStaffGap.value + halfHeight
                            (minimum - minOf(startY, endY)).coerceAtLeast(0f)
                        } else {
                            val maximum = -(localTop.value + config.dynamicStaffGap.value) - halfHeight
                            (maximum - maxOf(startY, endY)).coerceAtMost(0f)
                        }
                    } else 0f
                    // Persisted geometry wins on Y: the stored band centre (== captured `yCenter`)
                    // back-solves bandTopY = yCenter − height/2, reproducing the auto position exactly
                    // when stored == auto, or honouring a moved one otherwise.
                    val storedYCenter = stored?.startDy?.plus(manualYShift)
                    val bandTopY: StaffSpace = when {
                        storedYCenter != null -> StaffSpace(storedYCenter) - raw.height / 2f
                        side == StaffAttachmentPlacement.BELOW ->
                            localBottom + config.dynamicStaffGap + StaffSpace(step.value * row)
                        else -> {
                            // Stack upward: content bottom sits above the staff, top is further up.
                            val contentBottom = -(localTop + config.dynamicStaffGap + StaffSpace(step.value * row))
                            contentBottom - raw.height
                        }
                    }

                    // Staff-spacing reserve measures against the line-GLOBAL extent: stackStaves adds
                    // this room on top of the global note extent, so staves never overlap even though
                    // each mark sits by its own local notes. (Reach ≤ 0 → the mark already fits within
                    // the global extent, no extra room needed.)
                    val (globalTop, globalBottom) = noteExt(raw.systemIndex, staffIndex)
                    if (side == StaffAttachmentPlacement.BELOW) {
                        val reach = bandTopY + raw.height - globalBottom
                        if (reach > StaffSpace.ZERO) bump(raw.systemIndex, staffIndex, StaffSpace.ZERO, reach)
                    } else {
                        val reach = (-bandTopY) - globalTop
                        if (reach > StaffSpace.ZERO) bump(raw.systemIndex, staffIndex, reach, StaffSpace.ZERO)
                    }

                    val geometries = raw.build(bandTopY).map { drawable ->
                        if (stored == null) return@map drawable
                        val onsetX = timeSlotMap.atTime(raw.attachment.time)?.x ?: return@map drawable
                        val endTime = when (val attachment = raw.attachment) {
                            is ComputedHairpin -> attachment.endTime
                            is ComputedOctaveShift -> attachment.endTime
                            is ComputedTempoKeyframe -> attachment.nextTime.takeIf { attachment.isGradual }
                            is ComputedVoltaAttachment -> attachment.endTime
                            is ComputedOrnamentMark -> attachment.endTime
                            else -> null
                        }
                        val endAnchorX = endTime?.let { timeSlotMap.atTime(it)?.x } ?: onsetX
                        val autoLayoutX = stored.manuallyAdjustedY && raw.attachment is ComputedHairpin
                        when (drawable) {
                            is HairpinGeometry -> {
                                val startX = if (autoLayoutX) drawable.startX else onsetX + StaffSpace(stored.startDx)
                                val endX = if (autoLayoutX) drawable.endX else endAnchorX + StaffSpace(stored.endDx ?: 0f)
                                val startY = StaffSpace(stored.startDy + manualYShift)
                                val endY = StaffSpace((stored.endDy ?: stored.startDy) + manualYShift)
                                drawable.copy(
                                    startX = startX,
                                    endX = endX,
                                    yCenter = startY,
                                    endYCenter = endY,
                                    bounds = RelativeRect(
                                        RelativePoint(minOf(startX, endX), minOf(startY, endY) - drawable.spread / 2f),
                                        kotlin.math.abs(endX.value - startX.value).let(::StaffSpace),
                                        kotlin.math.abs(endY.value - startY.value).let(::StaffSpace) + drawable.spread,
                                    ),
                                )
                            }
                            is IntervalAttachmentGeometry -> {
                                val startX = if (autoLayoutX) drawable.startX else onsetX + StaffSpace(stored.startDx)
                                val endX = if (autoLayoutX) drawable.endX else endAnchorX + StaffSpace(stored.endDx ?: 0f)
                                val y = StaffSpace(stored.startDy + manualYShift) // text/octave spans remain horizontal
                                drawable.copy(
                                    startX = startX,
                                    endX = endX,
                                    yCenter = y,
                                    bounds = RelativeRect(
                                        RelativePoint(minOf(startX, endX), y - drawable.bounds.height / 2f),
                                        StaffSpace(kotlin.math.abs(endX.value - startX.value)),
                                        drawable.bounds.height,
                                    ),
                                )
                            }
                            is GlyphGeometry -> {
                                val targetOrigin = RelativePoint(
                                    onsetX + StaffSpace(stored.startDx),
                                    StaffSpace(stored.startDy + manualYShift) - drawable.bounds.height / 2f,
                                )
                                val dx = targetOrigin.x - drawable.bounds.origin.x
                                val dy = targetOrigin.y - drawable.bounds.origin.y
                                drawable.copy(
                                    position = RelativePoint(drawable.position.x + dx, drawable.position.y + dy),
                                    bounds = drawable.bounds.copy(origin = targetOrigin),
                                )
                            }
                            else -> drawable
                        }
                    }
                    val bounds = geometries.mergedBounds() ?: continue
                    placed.add(
                        PlacedStaffAttachment(
                            attachment = raw.attachment,
                            staffIndex = staffIndex,
                            measureNumber = (raw.attachment as? ComputedVoltaAttachment)
                                ?.ending?.startMeasure ?: raw.attachment.time.measure,
                            geometries = geometries,
                            relativeBounds = bounds,
                            systemIndex = raw.systemIndex,
                        )
                    )
                }
            }
        }

        return StaffAttachmentLayoutResult(placed, extents)
    }

    /** A descriptor that can build its geometry once a band top Y is chosen. */
    private class RawAttachment(
        val attachment: ComputedStaffAttachment,
        val xStart: StaffSpace,
        val xEnd: StaffSpace,
        val height: StaffSpace,
        val build: (bandTopY: StaffSpace) -> List<DrawableGeometry>,
        /**
         * True for a span whose end anchor is left of its start — i.e. it wraps onto a
         * later system. Its [xStart]/[xEnd] span the whole shared X band, so it must be
         * kept out of the row-packing collision test (it lives on Y-disjoint systems and
         * its endpoints were already nudged clear of any co-located dynamic).
         */
        val crossesSystemBreak: Boolean = false,
        /**
         * System (line) of this attachment's anchor (0 in continuous mode / before
         * pagination). Row packing groups by this so marks on different lines — which
         * share the same justified X band — don't falsely collide.
         */
        val systemIndex: Int = 0,
    )

    context(BravuraFont)
    private fun buildRaw(
        attachment: ComputedStaffAttachment,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? = when (attachment) {
        is ComputedBreathMark -> buildBreathMark(attachment, timeSlotMap)
        is ComputedOrnamentMark -> buildOrnament(attachment, timeSlotMap)
        is ComputedDynamicMark -> buildDynamic(attachment, timeSlotMap)
        is ComputedHairpin -> buildHairpin(attachment, timeSlotMap)
        is ComputedOctaveShift -> buildOctaveShift(attachment, timeSlotMap)
        is ComputedTempoKeyframe -> buildTempo(attachment, timeSlotMap)
        is ComputedVoltaAttachment -> buildVolta(attachment, timeSlotMap)
        else -> null
    }

    context(BravuraFont)
    private fun buildOrnament(
        mark: ComputedOrnamentMark,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        val slot = timeSlotMap.atTime(mark.time) ?: return null
        val glyph = when (mark.kind) {
            com.mecon.api.storage.events.OrnamentKind.TRILL -> SmuflGlyphs.ornamentTrill
            com.mecon.api.storage.events.OrnamentKind.MORDENT -> SmuflGlyphs.ornamentMordent
            com.mecon.api.storage.events.OrnamentKind.INVERTED_MORDENT -> SmuflGlyphs.ornamentShortTrill
            com.mecon.api.storage.events.OrnamentKind.TREMBLEMENT -> SmuflGlyphs.ornamentTremblement
            com.mecon.api.storage.events.OrnamentKind.TREMBLEMENT_COUPERIN -> SmuflGlyphs.ornamentTremblementCouperin
            com.mecon.api.storage.events.OrnamentKind.MORDENT_UPPER_PREFIX -> SmuflGlyphs.ornamentPrecompMordentUpperPrefix
            com.mecon.api.storage.events.OrnamentKind.INVERTED_MORDENT_UPPER_PREFIX ->
                SmuflGlyphs.ornamentPrecompInvertedMordentUpperPrefix
            com.mecon.api.storage.events.OrnamentKind.MORDENT_RELEASE -> SmuflGlyphs.ornamentPrecompMordentRelease
            com.mecon.api.storage.events.OrnamentKind.TURN -> SmuflGlyphs.ornamentTurn
            com.mecon.api.storage.events.OrnamentKind.INVERTED_TURN -> SmuflGlyphs.ornamentTurnInverted
            com.mecon.api.storage.events.OrnamentKind.TURN_SLASH -> SmuflGlyphs.ornamentTurnSlash
        }
        val bbox = this@BravuraFont.getBBox(glyph) ?: return null
        fun accidentalGlyph(accidental: com.mecon.api.primitive.Accidental) = when (accidental) {
            com.mecon.api.primitive.Accidental.DOUBLE_FLAT -> SmuflGlyphs.accidentalDoubleFlat
            com.mecon.api.primitive.Accidental.FLAT -> SmuflGlyphs.accidentalFlat
            com.mecon.api.primitive.Accidental.NATURAL -> SmuflGlyphs.accidentalNatural
            com.mecon.api.primitive.Accidental.SHARP -> SmuflGlyphs.accidentalSharp
            com.mecon.api.primitive.Accidental.DOUBLE_SHARP -> SmuflGlyphs.accidentalDoubleSharp
        }
        val upper = mark.upperAccidental?.let(::accidentalGlyph)
        val lower = mark.lowerAccidental?.let(::accidentalGlyph)
        val upperBox = upper?.let(this@BravuraFont::getBBox)
        val lowerBox = lower?.let(this@BravuraFont::getBBox)
        val gap = StaffSpace(0.18f)
        val totalHeight = bbox.height +
            (upperBox?.height?.plus(gap) ?: StaffSpace.ZERO) +
            (lowerBox?.height?.plus(gap) ?: StaffSpace.ZERO)
        val widest = listOfNotNull(bbox.width, upperBox?.width, lowerBox?.width).maxOrNull() ?: bbox.width
        val centerX = if (mark.anchor == com.mecon.api.storage.events.OrnamentAnchor.BETWEEN_NOTES) {
            val previous = timeSlotMap.lastNoteBefore(mark.time)
            if (previous != null && previous.systemIndex == slot.systemIndex) {
                StaffSpace((previous.x.value + slot.x.value) / 2f) - noteCentreLead
            } else slot.x - noteCentreLead - StaffSpace(1.25f)
        } else slot.x - noteCentreLead
        val left = centerX - bbox.width / 2f

        val ornamentEnd = mark.endTime
        if (mark.kind == com.mecon.api.storage.events.OrnamentKind.TRILL && ornamentEnd != null) {
            val endSlot = timeSlotMap.atTime(ornamentEnd) ?: return null
            val endX = endSlot.x - noteCentreLead
            return intervalRaw(
                attachment = mark,
                startX = centerX - widest / 2f,
                endX = endX,
                crossesBreak = endSlot.systemIndex != slot.systemIndex,
                systemIndex = slot.systemIndex,
                height = maxOf(totalHeight, StaffSpace(1.7f)),
                lineStyle = SpanLineStyle.WAVY_TRILL,
                startContent = SpanEnd.Ornament(glyph, upper, lower),
                endContent = SpanEnd.None,
                placement = mark.placement,
            )
        }

        return RawAttachment(
            attachment = mark,
            xStart = centerX - widest / 2f,
            xEnd = centerX + widest / 2f,
            height = totalHeight,
            systemIndex = slot.systemIndex,
            build = { bandTopY ->
                val out = mutableListOf<DrawableGeometry>()
                var top = bandTopY
                if (upper != null && upperBox != null) {
                    out += GlyphGeometry.fromBBox(
                        upper,
                        RelativePoint(centerX - upperBox.width / 2f - upperBox.southWest.x, top + upperBox.northEast.y),
                        upperBox,
                    )
                    top += upperBox.height + gap
                }
                out += GlyphGeometry.fromBBox(
                    glyph,
                    RelativePoint(left - bbox.southWest.x, top + bbox.northEast.y),
                    bbox,
                )
                top += bbox.height + gap
                if (lower != null && lowerBox != null) {
                    out += GlyphGeometry.fromBBox(
                        lower,
                        RelativePoint(centerX - lowerBox.width / 2f - lowerBox.southWest.x, top + lowerBox.northEast.y),
                        lowerBox,
                    )
                }
                out
            },
        )
    }

    context(BravuraFont)
    private fun buildBreathMark(
        mark: ComputedBreathMark,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        val slot = timeSlotMap.atTime(mark.time) ?: return null
        val previous = timeSlotMap.lastNoteBefore(mark.time)
        val glyph = when (mark.shape) {
            com.mecon.api.storage.tracks.BreathMarkShape.COMMA -> SmuflGlyphs.breathMarkComma
            com.mecon.api.storage.tracks.BreathMarkShape.TICK -> SmuflGlyphs.breathMarkTick
            com.mecon.api.storage.tracks.BreathMarkShape.UPBOW -> SmuflGlyphs.breathMarkUpbow
            com.mecon.api.storage.tracks.BreathMarkShape.SALZEDO -> SmuflGlyphs.breathMarkSalzedo
        }
        val bbox = this@BravuraFont.getBBox(glyph) ?: return null
        // The stored time is the boundary *after* the affected note. When the next note starts at
        // that same boundary, place the glyph halfway between the two note columns, not over the
        // following notehead. Across a system break it sits just before the following note on the
        // new system, keeping the attachment in the system selected by its stored TimeCode.
        val centerX = when {
            previous == null -> slot.x - noteCentreLead
            previous.systemIndex == slot.systemIndex ->
                StaffSpace((previous.x.value + slot.x.value) / 2f) - noteCentreLead
            else -> slot.x - noteCentreLead - StaffSpace(1.25f)
        }
        val left = centerX - bbox.width / 2f
        return RawAttachment(
            attachment = mark,
            xStart = left,
            xEnd = left + bbox.width,
            height = bbox.height,
            systemIndex = slot.systemIndex,
            build = { bandTopY ->
                val origin = RelativePoint(
                    left - bbox.southWest.x,
                    bandTopY + bbox.northEast.y,
                )
                listOf(GlyphGeometry.fromBBox(glyph, origin, bbox))
            },
        )
    }

    context(BravuraFont)
    private fun buildTempo(
        tempo: ComputedTempoKeyframe,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        val startSlot = timeSlotMap.atTime(tempo.time) ?: return null
        val startX = startSlot.x - noteCentreLead
        if (tempo.displayStyle == TempoDisplayStyle.GRADUAL_TEXT) {
            val endTime = tempo.nextTime ?: return null
            val endSlot = timeSlotMap.atTime(endTime) ?: return null
            val endX = endSlot.x - noteCentreLead
            val label = tempo.source.text?.takeIf { it.isNotBlank() }
                ?: when (tempo.source.markType) {
                    com.mecon.api.storage.events.TempoMarkType.RITARDANDO -> "rit."
                    else -> "accel."
                }
            return intervalRaw(
                attachment = tempo,
                startX = startX,
                endX = endX,
                crossesBreak = endSlot.systemIndex != startSlot.systemIndex,
                systemIndex = startSlot.systemIndex,
                height = StaffSpace(config.textSize().value * 1.4f),
                lineStyle = SpanLineStyle.DASHED,
                startContent = SpanEnd.Text(label, widthFactor = 0.56f),
                endContent = SpanEnd.None,
                placement = StaffAttachmentPlacement.ABOVE,
            )
        }

        val textSize = config.textSize()
        val gap = StaffSpace(0.35f)
        val parts = mutableListOf<TempoMarkPart>()
        var cursor = StaffSpace.ZERO
        fun addText(value: String, italic: Boolean = false) {
            if (value.isBlank()) return
            val width = StaffSpace(value.length * textSize.value * 0.56f)
            parts += TempoMarkPart.Text(value, cursor, width, italic)
            cursor += width + gap
        }
        fun glyphFor(base: com.mecon.api.primitive.DurationBase) = when (base) {
            com.mecon.api.primitive.DurationBase.WHOLE -> com.mecon.renderer.smufl.SmuflGlyphs.metNoteWhole
            com.mecon.api.primitive.DurationBase.HALF -> com.mecon.renderer.smufl.SmuflGlyphs.metNoteHalfUp
            com.mecon.api.primitive.DurationBase.EIGHTH -> com.mecon.renderer.smufl.SmuflGlyphs.metNote8thUp
            else -> com.mecon.renderer.smufl.SmuflGlyphs.metNoteQuarterUp
        }
        fun addGlyph(base: com.mecon.api.primitive.DurationBase) {
            val glyph = glyphFor(base)
            val bbox = this@BravuraFont.getBBox(glyph)
            val width = bbox?.width ?: StaffSpace(1f)
            parts += TempoMarkPart.Glyph(
                value = glyph,
                x = cursor - (bbox?.southWest?.x ?: StaffSpace.ZERO),
                width = width,
                baselineOffset = bbox?.northEast?.y ?: StaffSpace.ONE,
            )
            cursor += width + gap
        }
        val bpm = tempo.source.displayedBpm(tempo.effectiveBpm)
        val bpmText = if (kotlin.math.abs(bpm - bpm.toInt()) < 0.05f) bpm.toInt().toString()
            else ((bpm * 10f).toInt() / 10f).toString()
        when (tempo.displayStyle) {
            TempoDisplayStyle.TEXT -> addText(tempo.source.text.orEmpty(), italic = true)
            TempoDisplayStyle.TEXT_AND_METRONOME -> {
                addText(tempo.source.text.orEmpty(), italic = true)
                addGlyph(tempo.source.beatUnit)
                addText("= $bpmText")
            }
            TempoDisplayStyle.METRIC_MODULATION -> {
                addGlyph(tempo.source.beatUnit)
                addText("=")
                addGlyph(tempo.source.equivalentBeatUnit ?: com.mecon.api.primitive.DurationBase.HALF)
            }
            else -> {
                addGlyph(tempo.source.beatUnit)
                addText("= $bpmText")
            }
        }
        if (parts.isEmpty()) return null
        val width = maxOf(cursor - gap, StaffSpace.ONE)
        val height = StaffSpace(2.2f)
        return RawAttachment(
            attachment = tempo,
            xStart = startX,
            xEnd = startX + width,
            height = height,
            systemIndex = startSlot.systemIndex,
            build = { bandTopY -> listOf(TempoMarkGeometry(
                parts = parts,
                topLeft = RelativePoint(startX, bandTopY),
                textSize = textSize,
                bounds = RelativeRect(RelativePoint(startX, bandTopY), width, height),
            )) },
        )
    }

    context(BravuraFont)
    private fun buildDynamic(
        mark: ComputedDynamicMark,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        val anchorSlot = timeSlotMap.atTime(mark.time) ?: return null
        val anchorX = anchorSlot.x
        val glyphs = DynamicGlyphs.glyphsFor(mark.level)
        if (glyphs.isEmpty()) return null

        data class Measured(val glyph: com.mecon.renderer.smufl.GlyphInfo, val bbox: com.mecon.renderer.smufl.GlyphBBox)
        val measured = glyphs.mapNotNull { g -> this@BravuraFont.getBBox(g)?.let { Measured(g, it) } }
        if (measured.isEmpty()) return null

        val tracking = config.dynamicLetterTracking
        val totalWidth = measured.fold(StaffSpace.ZERO) { acc, m -> acc + m.bbox.width } +
            StaffSpace(tracking.value * (measured.size - 1))
        val height = measured.maxOf { it.bbox.height }

        val centreX = anchorX - noteCentreLead
        val leftX = centreX - totalWidth / 2f

        return RawAttachment(
            attachment = mark,
            xStart = leftX,
            xEnd = leftX + totalWidth,
            height = height,
            systemIndex = anchorSlot.systemIndex,
            build = { bandTopY ->
                val out = mutableListOf<DrawableGeometry>()
                var cursor = leftX
                for (m in measured) {
                    val baselineY = bandTopY + m.bbox.northEast.y
                    val posX = cursor - m.bbox.southWest.x
                    out.add(GlyphGeometry.fromBBox(m.glyph, RelativePoint(posX, baselineY), m.bbox))
                    cursor = cursor + m.bbox.width + tracking
                }
                out
            },
        )
    }

    /**
     * Builds a hairpin raw attachment, optionally nudging its start/end X past
     * any dynamic mark that falls on the same time + placement.  This keeps the
     * hairpin and the coincident dynamic mark in the same horizontal row rather
     * than stacking them vertically.
     */
    private fun buildHairpin(
        hairpin: ComputedHairpin,
        timeSlotMap: UnifiedTimeSlotMap,
        dynamicExtents: Map<Pair<TimeCode, StaffAttachmentPlacement>, Pair<StaffSpace, StaffSpace>> = emptyMap(),
    ): RawAttachment? {
        val startSlot = timeSlotMap.atTime(hairpin.time) ?: return null
        val startSlotX = startSlot.x
        val endSlotX = timeSlotMap.atTime(hairpin.endTime)?.x
        val rawStartX = startSlotX - noteCentreLead

        // The gap must exceed rowPackGap so the nudged hairpin clears the
        // packRows threshold and lands on the same row as the dynamic mark.
        val nudgeGap = StaffSpace(rowPackGap + 0.1f)
        val key = hairpin.placement

        // Nudge the hairpin's left edge past a dynamic mark that starts at the same time.
        val startX = dynamicExtents[hairpin.time to key]
            ?.let { (_, dynRight) -> maxOf(rawStartX, dynRight + nudgeGap) }
            ?: rawStartX

        // A span whose end slot sits LEFT of its start slot crosses a system break: its
        // end is on a later line that reuses the same X band (X is monotonic within a
        // line, so end < start can only mean a wrap). Keep the true end X — the geometry
        // is split per line in SystemBreaker.clipAttachment — and skip the same-line
        // minimum-width guards, which would otherwise snap the end back beside the start
        // and make the continuation segment overrun its line.
        val xEndFinal: StaffSpace = if (endSlotX != null && endSlotX < startSlotX) {
            val rawEndX = endSlotX - noteCentreLead
            dynamicExtents[hairpin.endTime to key]
                ?.let { (dynLeft, _) -> minOf(rawEndX, dynLeft - nudgeGap) } ?: rawEndX
        } else {
            val rawEndX = endSlotX?.minus(noteCentreLead) ?: (rawStartX + StaffSpace(6f))
            val rawXEnd = if (rawEndX > rawStartX + StaffSpace(1f)) rawEndX else rawStartX + StaffSpace(4f)
            val xEnd = dynamicExtents[hairpin.endTime to key]
                ?.let { (dynLeft, _) -> minOf(rawXEnd, dynLeft - nudgeGap) } ?: rawXEnd
            if (xEnd > startX + StaffSpace(1f)) xEnd else startX + StaffSpace(2f)
        }

        val spread = config.hairpinSpread
        val height = maxOf(spread, StaffSpace(config.textSize().value * 1.2f))
        val crossesBreak = endSlotX != null && endSlotX < startSlotX

        // A cresc./dim. text hairpin is a plain span (text + dashed line), so it goes
        // through the shared interval path. Only the drawn WEDGE needs bespoke geometry.
        if (hairpin.style == HairpinStyle.TEXT_DASHED) {
            val label = if (hairpin.type == HairpinType.CRESCENDO) "cresc." else "dim."
            return intervalRaw(
                attachment = hairpin,
                startX = startX,
                endX = xEndFinal,
                crossesBreak = crossesBreak,
                systemIndex = startSlot.systemIndex,
                height = height,
                lineStyle = SpanLineStyle.DASHED,
                startContent = SpanEnd.Text(label, widthFactor = 0.45f),
                endContent = SpanEnd.None,
                placement = hairpin.placement,
            )
        }

        return RawAttachment(
            attachment = hairpin,
            // Row packing works on the horizontal extent, which must be normalised since
            // a cross-line span has endX < startX before it is split.
            xStart = minOf(startX, xEndFinal),
            xEnd = maxOf(startX, xEndFinal),
            crossesSystemBreak = crossesBreak,
            systemIndex = startSlot.systemIndex,
            height = height,
            build = { bandTopY ->
                val yCenter = bandTopY + height / 2f
                val left = minOf(startX, xEndFinal)
                val right = maxOf(startX, xEndFinal)
                val bounds = RelativeRect(
                    origin = RelativePoint(left, yCenter - spread / 2f),
                    width = right - left,
                    height = spread
                )
                listOf(
                    HairpinGeometry(
                        startX = startX,
                        endX = xEndFinal,
                        type = hairpin.type,
                        yCenter = yCenter,
                        spread = spread,
                        thickness = config.hairpinThickness,
                        bounds = bounds,
                    )
                )
            },
        )
    }

    /**
     * Builds a [RawAttachment] for any span expressible as a horizontal line plus end
     * content — octave brackets, text hairpins, and future symbols (pedalling, accel./
     * rit.). New symbols of that family need no new geometry, only a [SpanLineStyle] and
     * a [SpanEnd] per end.
     */
    private fun intervalRaw(
        attachment: ComputedStaffAttachment,
        startX: StaffSpace,
        endX: StaffSpace,
        crossesBreak: Boolean,
        systemIndex: Int,
        height: StaffSpace,
        lineStyle: SpanLineStyle,
        startContent: SpanEnd,
        endContent: SpanEnd,
        placement: StaffAttachmentPlacement,
    ): RawAttachment = RawAttachment(
        attachment = attachment,
        // Normalised: a cross-line span has endX < startX until it is split per line.
        xStart = minOf(startX, endX),
        xEnd = maxOf(startX, endX),
        crossesSystemBreak = crossesBreak,
        systemIndex = systemIndex,
        height = height,
        build = { bandTopY ->
            val yCenter = bandTopY + height / 2f
            val left = minOf(startX, endX)
            val right = maxOf(startX, endX)
            val bounds = RelativeRect(
                origin = RelativePoint(left, bandTopY),
                width = right - left,
                height = height,
            )
            listOf(
                IntervalAttachmentGeometry(
                    startX = startX,
                    endX = endX,
                    yCenter = yCenter,
                    lineStyle = lineStyle,
                    startContent = startContent,
                    endContent = endContent,
                    placement = placement,
                    thickness = config.hairpinThickness,
                    textSize = config.textSize(),
                    textGap = config.hairpinTextGap,
                    bounds = bounds,
                )
            )
        },
    )

    private fun buildOctaveShift(
        shift: ComputedOctaveShift,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        // Start at left edge of first notehead so the label covers the starting note.
        val startSlot = timeSlotMap.atTime(shift.time) ?: return null
        val startSlotX = startSlot.x
        val startX = startSlotX - noteCentreLead - StaffSpace(0.5f)
        // End at the last NOTE before endOnset (exclusive end). Skip barline-only slots so
        // the dashed line stops at the final affected notehead, not the trailing barline.
        val endNoteX = timeSlotMap.lastNoteBefore(shift.endTime)?.x
        // As with hairpins, an end note left of the start slot means the span crosses a
        // system break — keep its true X (split per line later) instead of clamping.
        val xEndFinal = when {
            endNoteX == null -> startX + StaffSpace(8f)
            endNoteX < startSlotX -> endNoteX
            endNoteX > startX + StaffSpace(1f) -> endNoteX
            else -> startX + StaffSpace(6f)
        }

        val textSize = config.textSize()
        val height = StaffSpace(textSize.value * 1.4f)
        val label = when (shift.shiftType) {
            OctaveShiftType.OTTAVA -> "8va"
            OctaveShiftType.OTTAVA_BASSA -> "8vb"
        }

        return intervalRaw(
            attachment = shift,
            startX = startX,
            endX = xEndFinal,
            crossesBreak = endNoteX != null && endNoteX < startSlotX,
            systemIndex = startSlot.systemIndex,
            height = height,
            lineStyle = SpanLineStyle.DASHED,
            startContent = SpanEnd.Text(label, widthFactor = 0.52f),
            endContent = SpanEnd.Hook(StaffSpace(0.6f)),
            placement = shift.placement,
        )
    }

    context(BravuraFont)
    private fun buildVolta(
        volta: ComputedVoltaAttachment,
        timeSlotMap: UnifiedTimeSlotMap,
    ): RawAttachment? {
        val startSlot = timeSlotMap.atTime(volta.time) ?: return null
        val endSlot = timeSlotMap.atTime(volta.endTime) ?: return null
        val startBoundary = volta.ending.startMeasure - 1
        val endBoundary = volta.ending.endMeasure
        val startBarline = startSlot.barlineEvents().firstOrNull {
            it.measureNumber == startBoundary
        }
        val endBarline = endSlot.barlineEvents().firstOrNull {
            it.measureNumber == endBoundary
        }
        var startX = startSlot.x + (startBarline?.relativeX ?: StaffSpace.ZERO)
        if (volta.ending.numbers == setOf(2)) {
            startX += (startBarline?.minimumWidth ?: StaffSpace.ZERO) + StaffSpace(0.15f)
        }
        val endX = endSlot.x + (endBarline?.relativeX ?: StaffSpace.ZERO)
        val textSize = StaffSpace(config.textSize().value * 0.9f)
        val height = StaffSpace(textSize.value * 1.55f)
        val label = volta.ending.numbers.sorted().joinToString(",") + "."
        return RawAttachment(
            attachment = volta,
            xStart = minOf(startX, endX),
            xEnd = maxOf(startX, endX),
            height = height,
            crossesSystemBreak = endSlot.systemIndex != startSlot.systemIndex,
            systemIndex = startSlot.systemIndex,
            build = { bandTopY ->
                val yCenter = bandTopY + height / 2f
                listOf(
                    IntervalAttachmentGeometry(
                        startX = startX,
                        endX = endX,
                        yCenter = yCenter,
                        lineStyle = SpanLineStyle.SOLID,
                        startContent = SpanEnd.LabeledHook(
                            SpanEnd.Text(
                                text = label,
                                fontStyle = com.mecon.renderer.render.FontStyle.NORMAL,
                                widthFactor = 0.55f,
                            ),
                            StaffSpace(1.1f),
                        ),
                        endContent = SpanEnd.Hook(StaffSpace(1.1f)),
                        placement = StaffAttachmentPlacement.ABOVE,
                        thickness = StaffSpace(0.10f),
                        textSize = textSize,
                        textGap = StaffSpace(0.25f),
                        bounds = RelativeRect(
                            RelativePoint(minOf(startX, endX), bandTopY),
                            StaffSpace(kotlin.math.abs(endX.value - startX.value)),
                            height,
                        ),
                    )
                )
            },
        )
    }

    /**
     * Vertical row priority for staff attachments (lower = closer to the staff).
     *
     * When marks on the same placement side overlap horizontally, higher-priority
     * marks are pushed to outer rows so they never obscure lower-priority marks:
     *
     *   Priority 0 (innermost): dynamic marks, cresc./dim. hairpins
     *   Priority 1 (outermost): octave-shift brackets (8va / 8vb)
     *
     * Add new attachment types to the appropriate level here; future types that
     * should sit between dynamics and octave brackets can use priority 0.5 → 1
     * (reserve integers 2+ for marks that must go further out than 8va/8vb).
     */
    private fun attachmentRowPriority(attachment: ComputedStaffAttachment): Int = when (attachment) {
        is ComputedBreathMark -> 0
        is ComputedOrnamentMark -> 1
        is ComputedDynamicMark -> 0
        is ComputedHairpin     -> 0
        is ComputedOctaveShift -> 2
        is ComputedTempoKeyframe -> 3
        is ComputedVoltaAttachment -> 4
        else                   -> 0
    }

    /** True when the horizontal extents of [a] and [b] overlap (with [rowPackGap] tolerance). */
    private fun overlaps(a: RawAttachment, b: RawAttachment): Boolean =
        a.xStart.value < b.xEnd.value   + rowPackGap &&
        a.xEnd.value   > b.xStart.value - rowPackGap

    /**
     * Priority-aware greedy interval packing.
     *
     * Groups are processed in ascending priority order. Each group's items are packed
     * greedily by [xStart]; the minimum row for an item equals one above the highest
     * row occupied by any overlapping item from a lower-priority group. Items that do
     * not overlap with any lower-priority mark can still land in row 0.
     *
     * @return Row index for each input item, in the same order as [raws].
     */
    private fun packRowsWithPriority(raws: List<RawAttachment>): List<Int> {
        if (raws.isEmpty()) return emptyList()
        // Pin cross-break spans to the innermost row and keep them out of the packing:
        // they sit on Y-disjoint systems, so their full-band extent would falsely collide
        // with (and bump) the very dynamics their endpoints were nudged to share a row with.
        if (raws.any { it.crossesSystemBreak }) {
            val result = IntArray(raws.size)
            val packIdx = raws.indices.filter { !raws[it].crossesSystemBreak }
            val packed = packRowsWithPriority(packIdx.map { raws[it] })
            packIdx.forEachIndexed { k, gi -> result[gi] = packed[k] }
            return result.toList()
        }
        val maxPriority = raws.maxOf { attachmentRowPriority(it.attachment) }
        if (maxPriority == 0) return packRowsGreedy(raws) { 0 }

        val result = IntArray(raws.size)
        val placedSoFar = mutableListOf<Pair<RawAttachment, Int>>() // (raw, row)

        for (priority in 0..maxPriority) {
            val indices = raws.indices.filter { attachmentRowPriority(raws[it].attachment) == priority }
            val group   = indices.map { raws[it] }

            val groupRows = packRowsGreedy(group) { raw ->
                placedSoFar
                    .filter { (placed, _) -> overlaps(raw, placed) }
                    .maxOfOrNull { (_, row) -> row + 1 } ?: 0
            }
            indices.forEachIndexed { localIdx, globalIdx ->
                result[globalIdx] = groupRows[localIdx]
                placedSoFar += group[localIdx] to groupRows[localIdx]
            }
        }
        return result.toList()
    }

    /**
     * Plain greedy interval packing with a per-item minimum row floor.
     *
     * Processes items sorted by [xStart]; each item is placed in the lowest row ≥
     * [minRow] whose right edge clears the item's [xStart] by at least [rowPackGap].
     *
     * @return Row index for each input item, in input order.
     */
    private fun packRowsGreedy(
        raws: List<RawAttachment>,
        minRow: (RawAttachment) -> Int,
    ): List<Int> {
        val order    = raws.indices.sortedBy { raws[it].xStart.value }
        val rowLastX = mutableMapOf<Int, Float>().withDefault { -Float.MAX_VALUE }
        val rowOf    = IntArray(raws.size)
        var maxRow   = -1

        for (i in order) {
            val raw   = raws[i]
            val floor = minRow(raw)

            var assigned = -1
            var r = floor
            while (r <= maxRow) {
                if (rowLastX.getValue(r) <= raw.xStart.value - rowPackGap) { assigned = r; break }
                r++
            }
            if (assigned < 0) assigned = maxOf(floor, maxRow + 1)

            rowLastX[assigned] = raw.xEnd.value
            if (assigned > maxRow) maxRow = assigned
            rowOf[i] = assigned
        }
        return rowOf.toList()
    }

    private fun List<DrawableGeometry>.mergedBounds(): RelativeRect? {
        if (isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (g in this) {
            val b = g.bounds
            if (b.left.value < minX) minX = b.left.value
            if (b.top.value < minY) minY = b.top.value
            if (b.right.value > maxX) maxX = b.right.value
            if (b.bottom.value > maxY) maxY = b.bottom.value
        }
        return RelativeRect(
            origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
            width = StaffSpace(maxX - minX),
            height = StaffSpace(maxY - minY)
        )
    }
}

/** Default dynamics/hairpin text size in staff spaces. */
private fun RenderLayoutConfig.textSize(): StaffSpace = StaffSpace(1.6f)
