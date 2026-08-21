package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.desktop.ui.views.selectByPriority
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import kotlin.math.max
import kotlin.math.min

/**
 * Family N — rubber-band selection. Overlap testing lives in the renderer's spatial index; this
 * handler only maps the marquee rectangle from design space into the global score coordinates the
 * index uses, then unions the hits. Shift keeps the existing selection.
 *
 * Selection is not a score edit, so nothing here enters history.
 */
internal class MarqueeSelectDragHandler : ScoreDragHandler {
    private var startRaw = Offset.Zero
    private var lastRaw = Offset.Zero

    fun start(context: ScoreDragContext, raw: Offset): ScoreDragHandler {
        startRaw = raw
        lastRaw = raw
        context.selection.marquee = Rect(raw.x, raw.y, raw.x, raw.y)
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        lastRaw = change.position
        context.selection.marquee = Rect(
            min(startRaw.x, lastRaw.x), min(startRaw.y, lastRaw.y),
            max(startRaw.x, lastRaw.x), max(startRaw.y, lastRaw.y),
        )
        change.consume()
    }

    override fun end(context: ScoreDragContext) {
        val a = context.toDesign(startRaw)
        val b = context.toDesign(lastRaw)
        val minX = min(a.x, b.x)
        val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxY = max(a.y, b.y)
        val collected = LinkedHashSet<EventSection>()
        if (context.frame.paginated) {
            // A marquee may straddle several page sheets; clip it to each
            // page slot and map that slice into the page's global Y band.
            for (i in context.frame.pages.indices) {
                val slot = context.frame.pageSlots[i]
                val page = context.frame.pages[i]
                val x0 = max(minX, slot.x)
                val x1 = min(maxX, slot.x + page.width.value)
                val y0 = max(minY, slot.y)
                val y1 = min(maxY, slot.y + page.height.value)
                if (x0 >= x1 || y0 >= y1) continue
                collectInto(
                    collected,
                    context,
                    AbsoluteRect(
                        AbsolutePoint(
                            Pixels(x0 - slot.x),
                            Pixels((y0 - slot.y) + page.contentOffsetY.value),
                        ),
                        Pixels(x1 - x0), Pixels(y1 - y0),
                    ),
                )
            }
        } else {
            // Continuous mode: design space already is the global space.
            collectInto(
                collected,
                context,
                AbsoluteRect(
                    AbsolutePoint(Pixels(minX), Pixels(minY)),
                    Pixels(maxX - minX), Pixels(maxY - minY),
                ),
            )
        }
        context.selection.marquee = null
        // Shift unions with the existing selection; otherwise it replaces.
        context.actions.selection.selectionChange(
            if (context.viewport.shiftHeld) context.selection.current + collected else collected
        )
    }

    private fun collectInto(
        target: MutableSet<EventSection>,
        context: ScoreDragContext,
        globalRect: AbsoluteRect,
    ) {
        val result = context.result
        val cornerA = context.toRelative(globalRect.origin)
        val cornerB = context.toRelative(globalRect.bottomRight)
        for (hit in result.hitTestRegion(globalRect, context.mode.marqueeSelectableTypes)) {
            val section = hit.sections.selectByPriority() ?: continue
            // A long span is easy to clip by accident, so it only joins the selection when the
            // marquee contains all of it. Ornament spans are deliberately not treated this way:
            // they read as point marks with a tail.
            val isClippableSpan = (section as? StaffAttachmentSection)?.attachment?.let {
                it is ComputedHairpin ||
                    it is ComputedOctaveShift ||
                    (it is ComputedTempoKeyframe && it.isGradual)
            } == true
            if (isClippableSpan) {
                val box = hit.boundingBox()
                val fullyContained =
                    box.origin.x.value >= min(cornerA.x.value, cornerB.x.value) &&
                        box.bottomRight.x.value <= max(cornerA.x.value, cornerB.x.value) &&
                        box.origin.y.value >= min(cornerA.y.value, cornerB.y.value) &&
                        box.bottomRight.y.value <= max(cornerA.y.value, cornerB.y.value)
                if (!fullyContained) continue
            }
            target.add(section)
        }
    }
}
