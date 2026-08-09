package com.mecon.renderer.frozen

import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderResult

/**
 * Projects a live [RenderResult] into a portable [FrozenScoreBundle].
 *
 * This is the seam between the in-process renderer and the on-disk / on-CDN geometry the web
 * viewer replays. It keeps only what a viewer needs — the flat [RenderElement] list per surface
 * plus content bounds and playhead anchors — and drops every process-only field of
 * [RenderResult] (spatial index, coordinate transformer, section index): a viewer rebuilds any
 * lookup from the elements themselves.
 *
 * Transient view state ([RenderElement.selected] / [RenderElement.highlighted]) is normalised to
 * `false` so the frozen picture is a clean, selection-free engraving regardless of what happened
 * to be selected when it was captured.
 */
object FrozenScoreProjector {

    fun project(
        result: RenderResult,
        engineVersion: String,
        fontFingerprint: String,
    ): FrozenScoreBundle {
        val surfaces = if (result.paginated && result.pages.isNotEmpty()) {
            result.pages.map { page ->
                FrozenSurface(
                    index = page.pageIndex,
                    width = page.width.value,
                    height = page.height.value,
                    contentOffsetY = page.contentOffsetY.value,
                    elements = page.elements.map { it.frozen() },
                )
            }
        } else {
            listOf(
                FrozenSurface(
                    index = 0,
                    width = result.bounds.width.value,
                    height = result.bounds.height.value,
                    contentOffsetY = 0f,
                    elements = result.elements.map { it.frozen() },
                )
            )
        }
        val timePositions = result.timeCodePositions.values.map {
            FrozenTimePosition(
                timeCode = it.timeCode,
                x = it.x,
                topY = it.topY,
                bottomY = it.bottomY,
                leftX = it.leftX,
            )
        }
        return FrozenScoreBundle(
            engineVersion = engineVersion,
            fontFingerprint = fontFingerprint,
            paginated = result.paginated,
            bounds = result.bounds,
            surfaces = surfaces,
            timePositions = timePositions,
        )
    }

    /** Strip interaction/view state so the frozen element is a stable engraving snapshot. */
    private fun RenderElement.frozen(): RenderElement =
        if (selected || highlighted) copy(selected = false, highlighted = false) else this
}
