package com.mecon.renderer.layout

import com.mecon.api.plugin.AnnotationElement
import com.mecon.renderer.geometry.StaffSpace

internal data class AnnotationElementBounds(
    val widthPx: Float,
    val heightPx: Float
) {
    fun widthStaffSpace(pixelsPerStaffSpace: Float): StaffSpace =
        StaffSpace(widthPx / pixelsPerStaffSpace)

    fun heightStaffSpace(pixelsPerStaffSpace: Float): StaffSpace =
        StaffSpace(heightPx / pixelsPerStaffSpace)
}

internal class AnnotationElementMeasurer(
    private val pixelsPerStaffSpace: Float = DEFAULT_PIXELS_PER_STAFF_SPACE
) {
    private val textBoundsCache = mutableMapOf<TextKey, AnnotationElementBounds>()

    fun bounds(element: AnnotationElement): AnnotationElementBounds =
        when (element) {
            is AnnotationElement.Text -> textBounds(element)
        }

    fun widthStaffSpace(element: AnnotationElement): StaffSpace =
        bounds(element).widthStaffSpace(pixelsPerStaffSpace)

    fun extents(elements: List<AnnotationElement>): Pair<StaffSpace, StaffSpace> {
        var topExtent = StaffSpace.ZERO
        var bottomExtent = StaffSpace.ZERO
        for (element in elements) {
            when (element) {
                is AnnotationElement.Text -> {
                    val height = bounds(element).heightStaffSpace(pixelsPerStaffSpace).value
                    val above = -element.relativeY
                    val below = element.relativeY + height
                    if (above > topExtent.value) topExtent = StaffSpace(above)
                    if (below > bottomExtent.value) bottomExtent = StaffSpace(below)
                }
            }
        }
        return topExtent to bottomExtent
    }

    private fun textBounds(element: AnnotationElement.Text): AnnotationElementBounds {
        val key = TextKey(
            content = element.content,
            fontFamily = DEFAULT_TEXT_FONT_FAMILY,
            fontSizePx = element.fontSize
        )
        return textBoundsCache.getOrPut(key) {
            // Sum run advances (each measured at its scaled size); height is the
            // tallest run. Super-/subscript shift vertically but keep advance width.
            var widthPx = 0f
            var heightPx = 0f
            for (run in key.content.runs) {
                val runSize = key.fontSizePx * run.style.sizeScale
                val measured = PlatformTextMeasurer.measure(run.text, key.fontFamily, runSize)
                widthPx += measured.widthPx
                if (measured.heightPx > heightPx) heightPx = measured.heightPx
            }
            if (heightPx == 0f) heightPx = key.fontSizePx
            AnnotationElementBounds(widthPx = widthPx, heightPx = heightPx)
        }
    }

    private data class TextKey(
        val content: com.mecon.api.render.FormattedText,
        val fontFamily: String,
        val fontSizePx: Float
    )

    companion object {
        const val DEFAULT_TEXT_FONT_FAMILY: String = "Arial"

        /** Matches [com.mecon.renderer.geometry.ScaleFactor.DEFAULT] (8 px/staff-space). */
        const val DEFAULT_PIXELS_PER_STAFF_SPACE: Float = 8f
    }
}
