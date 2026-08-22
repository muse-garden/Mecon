package com.mecon.renderer.render

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.ComputedSlur
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.ArticulationGeometry
import com.mecon.api.storage.ArticulationPlacement
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.MarkOffset
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.TieGeometry
import com.mecon.api.storage.TupletGeometry
import com.mecon.renderer.geometry.HairpinGeometry
import com.mecon.renderer.geometry.IntervalAttachmentGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.ArticulationLayout
import com.mecon.renderer.layout.PlacedArticulation
import com.mecon.renderer.layout.PlacedStaffAttachment
import com.mecon.renderer.layout.SlurLayout
import com.mecon.renderer.layout.TieLayout
import com.mecon.renderer.layout.TupletLayout
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.collections.immutable.toPersistentMap

/**
 * Converts resolved [SlurLayout] / [ArticulationLayout] to and from the stable
 * anchor-relative [com.mecon.api.storage.ScoreGeometry] storage form.
 *
 *  - [toStored] folds out the volatile anchors (slot X, staff midline, notehead
 *    position), keeping only the offsets + shape that survive edits.
 *  - [resolveSlur] / [resolveArticulation] fold the current anchors back in,
 *    producing a layout that *follows* the notes. When the anchors can't be
 *    resolved (event filtered, cross-system slur, …) they return `null` so the
 *    caller falls back to auto layout.
 *
 * Capturing an auto layout with [toStored] and immediately resolving it again is
 * an identity (sub-pixel) — that is what makes persisted geometry a faithful
 * cache of the auto result on first save.
 */
internal object GeometryProjector {

    /**
     * Fold a whole render pass's resolved layouts into a [ScoreGeometry] overlay.
     * Cross-system slurs (which produce >1 [SlurLayout] stub per slurId) are
     * skipped — they fall back to auto layout in Phase 1.
     */
    fun capture(
        articulationLayouts: Map<EventId, ArticulationLayout>,
        tieLayouts: List<TieLayout>,
        slurLayouts: List<SlurLayout>,
        tupletLayouts: List<TupletLayout>,
        placedAttachments: List<PlacedStaffAttachment>,
        query: LayoutQuery,
        attachmentFilter: ((EventId) -> Boolean)? = null,
        beamGroups: List<BeamGroupProcessor.BeamGroupRenderData> = emptyList(),
    ): ScoreGeometry {
        val artMap = LinkedHashMap<EventId, ArticulationGeometry>()
        for ((id, layout) in articulationLayouts) {
            toStored(layout, query)?.let { artMap[id] = it }
        }
        val tieMap = LinkedHashMap<EventId, List<TieGeometry>>()
        for ((sourceId, layouts) in tieLayouts.groupBy { it.sourceEventId }) {
            val byPitch = layouts.groupBy { it.sourcePitchIndex }
            val stored = byPitch.mapNotNull { (_, pitchLayouts) ->
                if (pitchLayouts.size != 1) null else toStored(pitchLayouts.single(), query)
            }.sortedBy { it.sourcePitchIndex }
            if (stored.isNotEmpty()) tieMap[sourceId] = stored
        }
        val slurMap = LinkedHashMap<EventId, SlurGeometry>()
        for ((slurId, layouts) in slurLayouts.groupBy { it.slurId }) {
            if (layouts.size != 1) continue
            toStored(layouts.single(), query)?.let { slurMap[slurId] = it }
        }
        val tupletMap = tupletLayouts.associateTo(LinkedHashMap()) { layout ->
            layout.startEventId to TupletGeometry(
                above = layout.direction == SlurDirection.ABOVE,
            )
        }
        val attachmentSegments = LinkedHashMap<EventId, MutableList<PlacedStaffAttachment>>()
        for (placed in placedAttachments) {
            if (placed.attachment is ComputedVoltaAttachment) continue
            val id = placed.attachment.id
            if (attachmentFilter != null && !attachmentFilter(id)) continue
            attachmentSegments.getOrPut(id) { ArrayList(1) }.add(placed)
        }
        val attMap = LinkedHashMap<EventId, AttachmentGeometry>()
        for ((id, segments) in attachmentSegments) {
            // A cross-system span is split into one segment per line sharing an id; like
            // cross-system slurs it is captured as "no geometry" (falls back to auto).
            if (segments.size != 1) continue
            toStored(segments.single(), query)?.let { attMap[id] = it }
        }
        val beamMap = LinkedHashMap<String, BeamGeometry>()
        for (group in beamGroups) {
            val id = group.beamNoteInfos.firstOrNull()?.beamInfo?.groupId?.value ?: continue
            group.geometry?.let { beamMap[id] = it }
        }
        return ScoreGeometry(
            articulations = artMap,
            ties = tieMap.toPersistentMap(),
            slurs = slurMap.toPersistentMap(),
            attachments = attMap,
            beams = beamMap,
            tuplets = tupletMap,
        )
    }

    // ----- Staff attachment (hairpin / 8va / 8vb) -----

    /**
     * Fold a placed span attachment into anchor-relative form. Both endpoints are
     * stored relative to **their own onset time-slot X** (so the span follows the
     * notes it brackets) with Y already relative to the staff midline. The end Y is
     * recorded independently from the start Y even though auto-layout draws it
     * horizontal today — reserving a non-horizontal manual adjustment for later.
     *
     * Point dynamics record their merged glyph-run bounds as a point geometry; span attachments
     * record both independently movable endpoints.
     */
    fun toStored(placed: PlacedStaffAttachment, query: LayoutQuery): AttachmentGeometry? {
        val att = placed.attachment
        val onsetX = query.layoutResult.timeSlotMap.atTime(att.time)?.x ?: return null
        val endTime = when (att) {
            is ComputedHairpin -> att.endTime
            is ComputedOctaveShift -> att.endTime
            is com.mecon.api.computed.ComputedOrnamentMark -> att.endTime
            is ComputedTempoKeyframe -> att.nextTime.takeIf { att.isGradual }
            else -> null
        }
        val endX = endTime?.let { query.layoutResult.timeSlotMap.atTime(it)?.x } ?: onsetX
        return when (val g = placed.geometries.firstOrNull()) {
            is HairpinGeometry -> AttachmentGeometry(
                startDx = g.startX.value - onsetX.value,
                startDy = g.yCenter.value,
                endDx = g.endX.value - endX.value,
                endDy = g.endYCenter.value,
                spread = g.spread.value,
            )
            is IntervalAttachmentGeometry -> AttachmentGeometry(
                startDx = g.startX.value - onsetX.value,
                startDy = g.yCenter.value,
                endDx = g.endX.value - endX.value,
                endDy = g.yCenter.value,
            )
            else -> if (att is com.mecon.api.computed.ComputedDynamicMark && placed.geometries.isNotEmpty()) {
                val boxes = placed.geometries.map { it.bounds }
                val left = boxes.minOf { it.left.value }
                val right = boxes.maxOf { it.right.value }
                val top = boxes.minOf { it.top.value }
                val bottom = boxes.maxOf { it.bottom.value }
                AttachmentGeometry(
                    startDx = left - onsetX.value,
                    startDy = (top + bottom) / 2f,
                )
            } else null
        }
    }

    // ----- Articulation -----

    /** Fold a resolved articulation layout into anchor-relative offsets. */
    fun toStored(layout: ArticulationLayout, query: LayoutQuery): ArticulationGeometry? {
        val env = query.environment(layout.eventId) ?: return null
        val slotX = env.slotX.value
        val centerY = env.staffLayout.centerY.value
        return ArticulationGeometry(
            marks = layout.marks.map { mark ->
                MarkOffset(
                    index = mark.index,
                    above = mark.above,
                    dx = mark.origin.x.value - slotX,
                    dy = mark.origin.y.value - centerY,
                )
            }
        )
    }

    /**
     * Rebuild an articulation layout from stored offsets, re-folding the current
     * slot X / staff midline and re-deriving glyph + bounds from font metrics.
     * Mirrors the bounds math in [ArticulationLayoutComputer.buildLayout].
     */
    context(BravuraFont)
    fun resolveArticulation(
        geometry: ArticulationGeometry,
        event: ComputedVoiceEvent,
        query: LayoutQuery,
    ): ArticulationLayout? {
        val env = query.environment(event.id) ?: return null
        val centerY = env.staffLayout.centerY

        // Geometry is a cache of the complete mark stack. If the musical articulation
        // list changed, resolving a partial/stale cache would silently hide newly added
        // marks (or associate an old mark with a new list index), so force auto layout.
        val expectedIndices = event.articulations.indices.filterTo(LinkedHashSet()) { index ->
            ArticulationGlyphs.glyphFor(event.articulations[index], above = true) != null
        }
        val storedIndices = geometry.marks.mapTo(LinkedHashSet()) { it.index }
        if (storedIndices.size != geometry.marks.size || storedIndices != expectedIndices) return null

        val onStemSide = event.articulationPlacement == ArticulationPlacement.STEM && env.voiceLayout.stem != null
        val columnCenterX = articulationColumnCenterX(env, onStemSide)

        val marks = ArrayList<PlacedArticulation>(geometry.marks.size)
        for (m in geometry.marks) {
            val articulation = event.articulations.getOrNull(m.index) ?: continue
            val glyph = ArticulationGlyphs.glyphFor(articulation, m.above) ?: continue
            val bbox = this@BravuraFont.getBBox(glyph) ?: continue

            // X is constrained by engraving semantics: articulation glyphs stay centred
            // over the current note/stem column. Stored dx remains the serialized cache
            // representation, but must not preserve a stale time-slot-relative position.
            val originX = columnCenterX - bbox.southWest.x - StaffSpace(bbox.width.value / 2f)
            val originY = StaffSpace(centerY.value + m.dy)

            // Reconstruct the bounding box exactly as buildLayout would:
            //   originYRel = dy ; centerYRel = originYRel - (ne.y + sw.y)/2
            //   topScreenY = centerY + centerYRel - halfH
            val halfH = bbox.height.value / 2f
            val centerYRel = m.dy - (bbox.northEast.y.value + bbox.southWest.y.value) / 2f
            val topScreenY = centerY.value + centerYRel - halfH
            val bounds = RelativeRect(
                origin = RelativePoint(originX + bbox.southWest.x, StaffSpace(topScreenY)),
                width = bbox.width,
                height = bbox.height,
            )

            marks.add(
                PlacedArticulation(
                    articulation = articulation,
                    glyph = glyph,
                    index = m.index,
                    origin = RelativePoint(originX, originY),
                    above = m.above,
                    bounds = bounds,
                )
            )
        }
        if (marks.isEmpty()) return null
        return ArticulationLayout(
            eventId = event.id,
            trackId = env.voiceLayout.trackId,
            staffIndex = env.staffLayout.staffIndex,
            measureNumber = env.voiceLayout.measureNumber,
            marks = marks,
        )
    }

    // ----- Slur -----

    // ----- Tie -----

    fun toStored(layout: TieLayout, query: LayoutQuery): TieGeometry? {
        val start = anchorPoint(layout.sourceEventId, layout.sourcePitchIndex, query) ?: return null
        val targetPitchIndex = layout.targetEventId?.let {
            query.event(layout.sourceEventId)?.pitchData?.getOrNull(layout.sourcePitchIndex)
                ?.tieTarget?.targetPitchIndex
        }
        val end = if (layout.targetEventId != null && targetPitchIndex != null) {
            anchorPoint(layout.targetEventId, targetPitchIndex, query) ?: return null
        } else {
            start
        }
        return TieGeometry(
            sourcePitchIndex = layout.sourcePitchIndex,
            targetPitchIndex = targetPitchIndex,
            startDx = layout.start.x.value - start.x.value,
            startDy = layout.start.y.value - start.y.value,
            endDx = layout.end.x.value - end.x.value,
            endDy = layout.end.y.value - end.y.value,
            above = layout.direction == SlurDirection.ABOVE,
            minApex = layout.minApexHeight.value,
            maxApex = layout.maxApexHeight.value,
            slopeDamping = layout.slopeDamping,
            middleStraightening = layout.middleStraightening,
        )
    }

    fun resolveTie(
        geometry: TieGeometry,
        sourceEvent: ComputedVoiceEvent,
        sourcePitch: ComputedPitchData,
        query: LayoutQuery,
    ): TieLayout? {
        val sourceEnv = query.environment(sourceEvent.id) ?: return null
        val tieTarget = sourcePitch.tieTarget ?: return null
        val startAnchor = anchorPoint(sourceEvent.id, geometry.sourcePitchIndex, query) ?: return null
        val targetId = tieTarget.targetEventId
        val targetPitchIndex = tieTarget.targetPitchIndex ?: geometry.targetPitchIndex
        val endAnchor = if (targetId != null && targetPitchIndex != null) {
            val targetEnv = query.environment(targetId) ?: return null
            if (sourceEnv.systemIndex != targetEnv.systemIndex) return null
            anchorPoint(targetId, targetPitchIndex, query) ?: return null
        } else {
            startAnchor
        }
        return TieLayout(
            sourceEventId = sourceEvent.id,
            sourcePitchIndex = geometry.sourcePitchIndex,
            targetEventId = targetId,
            start = RelativePoint(
                StaffSpace(startAnchor.x.value + geometry.startDx),
                StaffSpace(startAnchor.y.value + geometry.startDy),
            ),
            end = RelativePoint(
                StaffSpace(endAnchor.x.value + geometry.endDx),
                StaffSpace(endAnchor.y.value + geometry.endDy),
            ),
            minApexHeight = StaffSpace(geometry.minApex),
            maxApexHeight = StaffSpace(geometry.maxApex),
            slopeDamping = geometry.slopeDamping,
            middleStraightening = geometry.middleStraightening,
            direction = if (geometry.above) SlurDirection.ABOVE else SlurDirection.BELOW,
            isLetRing = tieTarget.isLetRing,
            staffIndex = sourceEnv.staffLayout.staffIndex,
            trackId = sourceEnv.voiceLayout.trackId,
            measureNumber = sourceEnv.voiceLayout.measureNumber,
        )
    }

    /**
     * Fold a resolved (same-system) slur layout into anchor-relative form, or
     * `null` if its anchors can't be resolved. Cross-system slurs are captured
     * by the caller as "no geometry" (multiple stubs share one slurId).
     */
    fun toStored(layout: SlurLayout, query: LayoutQuery): SlurGeometry? {
        val start = anchorPoint(layout.startEventId, layout.startPitchIndex, query) ?: return null
        val end = anchorPoint(layout.endEventId, layout.endPitchIndex, query) ?: return null
        return SlurGeometry(
            startPitchIndex = layout.startPitchIndex,
            endPitchIndex = layout.endPitchIndex,
            startDx = layout.start.x.value - start.x.value,
            startDy = layout.start.y.value - start.y.value,
            endDx = layout.end.x.value - end.x.value,
            endDy = layout.end.y.value - end.y.value,
            above = layout.direction == SlurDirection.ABOVE,
            minApex = layout.minApexHeight.value,
            maxApex = layout.maxApexHeight.value,
            slopeDamping = layout.slopeDamping,
            middleStraightening = layout.middleStraightening,
        )
    }

    /**
     * Rebuild a slur layout from stored offsets + the slur's current notehead
     * anchors. Returns `null` (→ auto fallback) when an endpoint can't be
     * resolved or the slur now spans two systems (Phase 1 only persists
     * single-system slurs).
     */
    fun resolveSlur(
        geometry: SlurGeometry,
        slur: ComputedSlur,
        query: LayoutQuery,
    ): SlurLayout? {
        val startEnv = query.environment(slur.startEventId) ?: return null
        val endEnv = query.environment(slur.endEventId) ?: return null
        if (startEnv.systemIndex != endEnv.systemIndex) return null

        val startAnchor = anchorPoint(slur.startEventId, geometry.startPitchIndex, query) ?: return null
        val endAnchor = anchorPoint(slur.endEventId, geometry.endPitchIndex, query) ?: return null

        val direction = if (geometry.above) SlurDirection.ABOVE else SlurDirection.BELOW
        return SlurLayout(
            slurId = slur.slurId,
            startEventId = slur.startEventId,
            startPitchIndex = geometry.startPitchIndex,
            endEventId = slur.endEventId,
            endPitchIndex = geometry.endPitchIndex,
            start = RelativePoint(StaffSpace(startAnchor.x.value + geometry.startDx), StaffSpace(startAnchor.y.value + geometry.startDy)),
            end = RelativePoint(StaffSpace(endAnchor.x.value + geometry.endDx), StaffSpace(endAnchor.y.value + geometry.endDy)),
            minApexHeight = StaffSpace(geometry.minApex),
            maxApexHeight = StaffSpace(geometry.maxApex),
            slopeDamping = geometry.slopeDamping,
            middleStraightening = geometry.middleStraightening,
            direction = direction,
            nestingLevel = slur.nestingLevel,
            staffIndex = startEnv.staffLayout.staffIndex,
            systemIndex = startEnv.systemIndex,
            trackId = startEnv.voiceLayout.trackId,
            measureNumber = startEnv.voiceLayout.measureNumber,
        )
    }

    /**
     * Score-relative centre of the [pitchIndex] notehead of [eventId] — the
     * stable anchor a slur endpoint is stored relative to. Notehead anchors are
     * event-local (slot X + relativeX for X, staff centre for Y), so fold those in.
     */
    private fun anchorPoint(eventId: com.mecon.api.primitive.EventId, pitchIndex: Int, query: LayoutQuery): RelativePoint? {
        val env = query.environment(eventId) ?: return null
        val notehead = env.notehead(pitchIndex) ?: return null
        val centerX = notehead.leftEdge + (notehead.rightEdge - notehead.leftEdge) * 0.5f
        val anchorX = env.slotX + env.noteElement.relativeX + centerX
        val anchorY = env.staffLayout.centerY + notehead.centerY
        return RelativePoint(anchorX, anchorY)
    }
}
