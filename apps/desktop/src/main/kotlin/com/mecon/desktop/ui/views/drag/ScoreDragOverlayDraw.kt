package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.primitive.TimeCode
import com.mecon.api.render.RenderColor
import com.mecon.desktop.ui.views.ComposeScoreRenderer
import com.mecon.desktop.ui.views.RenderedScoreCanvasDrawRequest
import com.mecon.desktop.ui.views.breathBoundaryAnchor
import com.mecon.desktop.ui.views.globalToDesign
import com.mecon.desktop.ui.views.nearestNoteheadAnchor
import com.mecon.desktop.ui.views.voiceNumber
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.translatedBy

/**
 * Canvas overlays for every in-progress drag: the transient shapes that follow the pointer while
 * the settled elements stay hidden by the view's local style snapshot.
 *
 * These are editor chrome, not renderer output. Nothing here becomes a `RenderElement`, so none of
 * it affects engraving bounds, pagination, hit indices or renderer snapshots — which is exactly why
 * a handle can be dragged at pointer speed without running layout until release.
 *
 * Each preview shares the same paginated mapping as the ghost and playhead: geometry is produced in
 * global render coordinates, then translated onto the page that owns its anchor.
 */
internal fun DrawScope.drawScoreDragOverlays(
    request: RenderedScoreCanvasDrawRequest,
    result: RenderResult,
    composeRenderer: ComposeScoreRenderer,
) {
    val density = request.render.density
    val textMeasurer = request.render.textMeasurer
    val scale = request.scale
    val pages = request.page.pages
    val pageSlots = request.page.pageSlots
    val paginated = request.page.paginated
    val score = request.selection.score
    val selectedBeamSection = request.selection.selectedBeamSection
    val selectedBeamControls = request.selection.selectedBeamControls
    val selectedVoltaSection = request.selection.selectedVoltaSection
    val selectedVoltaElements = request.selection.selectedVoltaElements
    val selectedNavigationElements = request.selection.selectedNavigationElements
    val selectedAttachmentSection = request.selection.selectedAttachmentSection
    val selectedAttachmentElements = request.selection.selectedAttachmentElements
    val transposeDrag = request.drags.transpose
    val committedFrameDisplayed = request.drags.transposeCommitted
    val beamDrag = request.drags.beam
    val beamCommittedFrameDisplayed = request.drags.beamCommitted
    val attachmentDrag = request.drags.attachment
    val attachmentCommittedFrameDisplayed = request.drags.attachmentCommitted
    val voltaDrag = request.drags.volta
    val navigationDrag = request.drags.navigation
    val navigationCommittedFrameDisplayed = request.drags.navigationCommitted
    val curveDrag = request.drags.curve
    val curveCommittedFrameDisplayed = request.drags.curveCommitted
    val selectionFillColor = request.drags.selectionColor

    val beamPreview = beamDrag?.takeUnless { beamCommittedFrameDisplayed }?.let { drag ->
        selectedBeamControls
            ?.takeIf { it.section.groupId.value == drag.groupId }
            ?.let { controls -> createBeamDragPreview(result, controls, drag) }
    }
    val curvePreview = curveDrag
        ?.takeUnless { curveCommittedFrameDisplayed }
        ?.let { createCurveDragPreview(result, it) }

    // Drag-to-transpose preview: the moved notes re-engraved at their new pitch (the originals
    // are hidden by the style track). Two layers — the unmoved parts of a partially-moved chord in
    // black (base), the dragged notes in the selection colour on top (moved).
    transposeDrag?.takeUnless { committedFrameDisplayed }?.preview?.let { p ->
        fun paint() {
            composeRenderer.renderCommandsTinted(this, p.baseCommands, textMeasurer, RenderColor.BLACK)
            composeRenderer.renderCommandsTinted(this, p.movedCommands, textMeasurer, selectionFillColor)
        }
        if (paginated) {
            val designAnchor = globalToDesign(
                p.anchor.x.value, p.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - p.anchor.x.value
            val dy = designAnchor.y - p.anchor.y.value
            translate(left = dx * density, top = dy * density) { paint() }
        } else {
            paint()
        }
    }

    curvePreview?.segments?.forEach { segment ->
        fun paint() {
            composeRenderer.renderCommandsTinted(
                this,
                segment.commands,
                textMeasurer,
                selectionFillColor,
            )
        }
        if (paginated) {
            val designAnchor = globalToDesign(
                segment.anchor.x.value,
                segment.anchor.y.value,
                pages,
                pageSlots,
            ) ?: return@forEach
            translate(
                left = (designAnchor.x - segment.anchor.x.value) * density,
                top = (designAnchor.y - segment.anchor.y.value) * density,
            ) { paint() }
        } else {
            paint()
        }
    }

    beamPreview?.let { preview ->
        fun paint() {
            composeRenderer.renderCommandsTinted(
                this,
                preview.commands,
                textMeasurer,
                selectionFillColor,
            )
        }
        if (paginated) {
            val designAnchor = globalToDesign(
                preview.start.x.value,
                preview.start.y.value,
                pages,
                pageSlots,
            ) ?: return@let
            val dx = designAnchor.x - preview.start.x.value
            val dy = designAnchor.y - preview.start.y.value
            translate(left = dx * density, top = dy * density) { paint() }
        } else {
            paint()
        }
    }

    // Beam endpoint controls are editor-only overlays. They are deliberately not
    // RenderElements, so they do not affect engraving bounds, pagination, hit indices,
    // or renderer snapshots. Only the currently selected beam exposes them.
    selectedBeamControls?.let { controls ->
        val controlSize = BEAM_CONTROL_SIZE_DP.dp.toPx() / scale
        val strokeWidth = BEAM_CONTROL_STROKE_DP.dp.toPx() / scale
        val displayedControls = beamPreview?.takeIf {
            controls.section.groupId.value == beamDrag?.groupId
        }
        for (point in listOf(
            displayedControls?.start ?: controls.start,
            displayedControls?.end ?: controls.end,
        )) {
            val designPoint = if (paginated) {
                globalToDesign(point.x.value, point.y.value, pages, pageSlots)
            } else {
                Offset(point.x.value, point.y.value)
            } ?: continue
            val center = Offset(designPoint.x * density, designPoint.y * density)
            drawRect(
                color = MeconColors.voiceSelectionColor(
                    selectedBeamSection?.voiceNumber(score) ?: 1
                ),
                topLeft = center - Offset(controlSize / 2f, controlSize / 2f),
                size = Size(controlSize, controlSize),
                style = Stroke(width = strokeWidth),
            )
        }
    }

    // A first ending exposes its left edge; a second ending exposes its right edge.
    val selectedVoltaEndpoint = when (selectedVoltaSection?.ending?.numbers) {
        setOf(1) -> VoltaEndpoint.START
        setOf(2) -> VoltaEndpoint.END
        else -> null
    }
    if (selectedVoltaEndpoint != null && selectedVoltaElements.isNotEmpty()) {
        val element = when (selectedVoltaEndpoint) {
            VoltaEndpoint.START -> selectedVoltaElements.first()
            VoltaEndpoint.END -> selectedVoltaElements.last()
        }
        val targetMeasure = voltaDrag?.let {
            when (selectedVoltaEndpoint) {
                VoltaEndpoint.START -> it.currentStartMeasure
                VoltaEndpoint.END -> it.currentEndMeasure
            }
        }
        val targetBounds = targetMeasure?.let { measure ->
            result.measureBounds.firstOrNull { it.measureNumber == measure }
        }
        val horizontal = element.commands.filterIsInstance<DrawLine>()
            .maxByOrNull { kotlin.math.abs(it.end.x.value - it.start.x.value) }
        val settled = when (selectedVoltaEndpoint) {
            VoltaEndpoint.START -> horizontal?.start ?: element.hitBox.origin
            VoltaEndpoint.END -> horizontal?.end ?: element.hitBox.bottomRight
        }
        val point = if (targetBounds == null) settled else {
            val targetX = when (selectedVoltaEndpoint) {
                VoltaEndpoint.START -> targetBounds.leftX
                VoltaEndpoint.END -> targetBounds.rightX
            }
            result.transformerSnapshot.toAbsolute(
                RelativePoint(targetX, result.transformerSnapshot.toRelative(settled).y)
            )
        }
        val designPoint = if (paginated) {
            globalToDesign(point.x.value, point.y.value, pages, pageSlots)
        } else Offset(point.x.value, point.y.value)
        if (designPoint != null) {
            val size = VOLTA_CONTROL_SIZE_DP.dp.toPx() / scale
            drawRect(
                color = MeconColors.PrimaryLight,
                topLeft = designPoint * density - Offset(size / 2f, size / 2f),
                size = Size(size, size),
                style = Stroke(width = 1.5.dp.toPx() / scale),
            )
        }
    }

    // Navigation marks move immediately under the pointer while their settled
    // RenderElement is hidden by [displayStyleSnapshot].
    val navDrag = navigationDrag?.takeUnless { navigationCommittedFrameDisplayed }
    val navElement = navDrag?.elementId?.let(result::elementById)
        ?: selectedNavigationElements.firstOrNull()
    if (navDrag != null && navElement != null) {
        val dx = result.transformerSnapshot.toPixels(
            StaffSpace(navDrag.previewDx)
        ).value
        val dy = result.transformerSnapshot.toPixels(
            StaffSpace(navDrag.previewDy)
        ).value
        val preview = navElement.translatedBy(dx, dy)
        fun paintNavigationPreview() {
            composeRenderer.renderCommandsTinted(
                this,
                preview.commands,
                textMeasurer,
                selectionFillColor,
            )
        }
        if (paginated) {
            val anchor = globalToDesign(
                preview.center.x.value,
                preview.center.y.value,
                pages,
                pageSlots,
            )
            if (anchor != null) {
                translate(
                    left = (anchor.x - preview.center.x.value) * density,
                    top = (anchor.y - preview.center.y.value) * density,
                ) { paintNavigationPreview() }
            }
        } else {
            paintNavigationPreview()
        }
    }

    // During an attachment drag the settled element is hidden by displayStyleSnapshot;
    // warp its commands into the transient endpoint geometry so pointer movement is
    // visible immediately without running layout until release.
    val attachmentSection = selectedAttachmentSection
    val drag = attachmentDrag
        ?.takeUnless { attachmentCommittedFrameDisplayed }
        ?.takeIf { it.id == attachmentSection?.attachment?.id }
    var dragStartPoint: AbsolutePoint? = null
    var dragEndPoint: AbsolutePoint? = null
    if (drag != null) {
        fun movedPoint(
            original: AbsolutePoint,
            oldTime: TimeCode,
            newTime: TimeCode,
            oldDx: Float,
            newDx: Float,
            oldDy: Float,
            newDy: Float,
        ): AbsolutePoint {
            val oldAnchorX = result.timeCodePositions[oldTime]?.x ?: original.x.value
            val newAnchorX = result.timeCodePositions[newTime]?.x ?: oldAnchorX
            val dxPx = newAnchorX - oldAnchorX + result.transformerSnapshot
                .toPixels(StaffSpace(newDx - oldDx)).value
            val dyPx = result.transformerSnapshot.toPixels(StaffSpace(newDy - oldDy)).value
            return AbsolutePoint(
                Pixels(original.x.value + dxPx),
                Pixels(original.y.value + dyPx),
            )
        }
        dragStartPoint = movedPoint(
            drag.originalStartPoint,
            drag.originalStartTime,
            drag.startTime,
            drag.start.startDx,
            drag.current.startDx,
            drag.start.startDy,
            drag.current.startDy,
        )
        dragEndPoint = drag.originalEndPoint?.let { original ->
            movedPoint(
                original,
                drag.originalEndTime ?: drag.originalStartTime,
                drag.endTime ?: drag.startTime,
                drag.start.endDx ?: drag.start.startDx,
                drag.current.endDx ?: drag.current.startDx,
                drag.start.endDy ?: drag.start.startDy,
                drag.current.endDy ?: drag.current.startDy,
            )
        }
        result.elementById(drag.elementId)?.let { element ->
            val commands = warpAttachmentCommands(
                element.commands,
                drag.originalStartPoint,
                drag.originalEndPoint,
                dragStartPoint!!,
                dragEndPoint,
            )
            fun paint() = composeRenderer.renderCommandsTinted(
                this, commands, textMeasurer, selectionFillColor,
            )
            if (paginated) {
                val designAnchor = globalToDesign(
                    dragStartPoint!!.x.value,
                    dragStartPoint!!.y.value,
                    pages,
                    pageSlots,
                ) ?: return@let
                val dx = designAnchor.x - dragStartPoint!!.x.value
                val dy = designAnchor.y - dragStartPoint!!.y.value
                translate(left = dx * density, top = dy * density) { paint() }
            } else paint()
        }
    }

    // Selected expressions expose their musical anchors without polluting engraving.
    // Each guide terminates at the nearest notehead at that TimeCode on this staff;
    // simultaneous voices/chord tones therefore resolve to the visually nearest head.
    if (attachmentSection != null && selectedAttachmentElements.isNotEmpty()) {
        val attachment = attachmentSection.attachment
        val times = buildList {
            add(drag?.startTime ?: attachment.time)
            when (attachment) {
                is ComputedHairpin -> add(drag?.endTime ?: attachment.endTime)
                is ComputedOctaveShift -> add(drag?.endTime ?: attachment.endTime)
                is ComputedOrnamentMark -> attachment.endTime?.let {
                    add(drag?.endTime ?: it)
                }
                is ComputedTempoKeyframe -> if (attachment.isGradual) {
                    attachment.nextTime?.let { add(drag?.endTime ?: it) }
                }
                else -> {}
            }
        }
        val sortedElements = selectedAttachmentElements.sortedWith(
            compareBy<RenderElement> { it.systemIndex ?: Int.MAX_VALUE }
                .thenBy { it.hitBox.origin.x.value }
        )
        val symbolPoints = if (times.size == 1) {
            listOf(dragStartPoint ?: sortedElements.first().center)
        } else listOf(
            dragStartPoint ?: AbsolutePoint(sortedElements.first().hitBox.origin.x, sortedElements.first().center.y),
            dragEndPoint ?: AbsolutePoint(sortedElements.last().hitBox.bottomRight.x, sortedElements.last().center.y),
        )
        for ((index, time) in times.withIndex()) {
            val symbol = symbolPoints[index]
            val owningElement = if (index == 0) sortedElements.first() else sortedElements.last()
            val owningSystemIndex = drag?.systemIndex ?: owningElement.systemIndex
            val anchor = if (attachment is ComputedBreathMark) {
                breathBoundaryAnchor(
                    result, time, attachment.staffIndex, owningSystemIndex, symbol,
                )
            } else {
                nearestNoteheadAnchor(
                    result, time, attachment.staffIndex, owningSystemIndex, symbol,
                )
            } ?: continue
            fun design(point: AbsolutePoint): Offset? = if (paginated) {
                globalToDesign(point.x.value, point.y.value, pages, pageSlots)
            } else Offset(point.x.value, point.y.value)
            val a = design(symbol) ?: continue
            val b = design(anchor) ?: continue
            drawLine(
                color = MeconColors.voiceSelectionColor(
                    attachment.voiceNumber ?: 1
                ).copy(alpha = 0.75f),
                start = a * density,
                end = b * density,
                strokeWidth = 1.dp.toPx() / scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
            )
        }
    }
}
