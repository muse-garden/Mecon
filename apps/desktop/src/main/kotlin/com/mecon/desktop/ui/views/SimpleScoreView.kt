package com.mecon.desktop.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.mecon.api.render.RenderColor
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.runtime.RuntimeScore
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.interaction.OrderedOverride
import com.mecon.renderer.interaction.StyleSnapshot
import kotlin.math.roundToInt

/** Layout config for compact, continuous previews. */
private val SIMPLE_SCORE_CONFIG = RenderLayoutConfig.DEFAULT

/** Trailing staff (design px) kept to the right of the crop region so it doesn't end abruptly. */
private const val CROP_PADDING_PX = 8f

/**
 * Minimal, non-interactive score renderer.
 *
 * Shares the rendering pipeline ([rememberScoreRenderer] / [rememberRenderResult]) with the
 * full [RenderedScoreView] but drops pagination, hit-testing, panning, zooming, selection and
 * style overrides. The score is rendered as a single continuous system and uniformly scaled to
 * fit the composable's bounds — ideal for thumbnails such as the new-score dialog staff previews.
 *
 * @param maxScale Upper bound on the fit scale so small scores aren't blown up past legibility.
 * @param fitScale Extra multiplier applied after the fit-to-bounds scale (≤1 leaves breathing room
 *   so tall glyphs that overhang the [verticalFitTypes] box aren't clipped).
 * @param background Optional fill painted behind the whole composable before the score.
 * @param foreground Optional single ink colour forced onto every drawn element (monochrome preview).
 *   Null keeps each element's engraved colour.
 * @param noteForegrounds Optional note-section colours. This keeps compact previews on the normal
 *   renderer path while allowing a single member of a chord to be distinguished.
 * @param visibleTypes When non-null, only elements of these types are drawn (e.g. show just the
 *   staff, clef and key signature). Null draws everything.
 * @param cropTypes When non-null, the drawn area is cropped on the right to the bounding box of
 *   elements of these types (plus a little trailing staff) and clipped, instead of fitting the
 *   whole score. Combine with [visibleTypes] for a clean header-only thumbnail.
 * @param cropPaddingPx Staff padding retained on both sides of a cropped preview, in design px.
 * @param verticalFitTypes When non-null, the vertical fit scale and staff placement are derived from
 *   the bounding box of these element types (e.g. the staff) instead of the full drawn content.
 *   Elements taller than that box (e.g. a treble clef) still draw and overhang it — clipped only
 *   horizontally, not vertically — so several previews whose tallest glyph differs still share one
 *   scale and one staff position.
 */
@Composable
fun SimpleScoreView(
    score: RuntimeScore?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    maxScale: Float = Float.MAX_VALUE,
    fitScale: Float = 1f,
    background: Color? = null,
    foreground: Color? = null,
    noteForegrounds: Map<EventSectionId, Color> = emptyMap(),
    visibleTypes: Set<RenderElementType>? = null,
    cropTypes: Set<RenderElementType>? = null,
    cropPaddingPx: Float = CROP_PADDING_PX,
    verticalFitTypes: Set<RenderElementType>? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val renderer = rememberScoreRenderer(SIMPLE_SCORE_CONFIG)
    val result = rememberRenderResult(renderer, score, PageGeometry.continuous())
    val styleSnapshot = remember(noteForegrounds) {
        StyleSnapshot(
            noteForegrounds.mapValues { (_, color) ->
                OrderedOverride(0, StyleOverride(fillColor = color.toRenderColor()))
            }
        )
    }

    Canvas(modifier) {
        background?.let { drawRect(color = it) }

        val composeRenderer = renderer?.composeRenderer ?: return@Canvas
        val r = result ?: return@Canvas

        val elements = if (visibleTypes != null) r.elements.filter { it.type in visibleTypes } else r.elements
        if (elements.isEmpty()) return@Canvas

        // Horizontal extent in design pixels. Defaults to the whole score; with cropTypes the extent
        // is pulled in to the crop elements' bounding box (clef/key signature/time signature) so
        // leading header space and trailing staff, rests and barlines fall outside and are clipped
        // away. Both edges snap to the crop box so a crop element sitting mid-score (e.g. the time
        // signature, which follows the clef) is centred rather than left-padded by the hidden header.
        val base = r.bounds
        var leftDesign = base.origin.x.value
        var rightDesign = leftDesign + base.width.value
        if (cropTypes != null) {
            val cropped = r.elements.filter { it.type in cropTypes }
            cropped.minOfOrNull { it.hitBox.origin.x.value }?.let { leftDesign = it - cropPaddingPx }
            cropped.maxOfOrNull { it.hitBox.origin.x.value + it.hitBox.width.value }?.let { rightDesign = it + cropPaddingPx }
        }

        // Vertical extent in design pixels that drives the fit + placement. Defaults to the whole
        // score; verticalFitTypes narrows it to e.g. the staff so a tall clef overhanging the staff
        // doesn't shrink the fit (keeping the scale/staff position identical across previews).
        var topDesign = base.origin.y.value
        var bottomDesign = topDesign + base.height.value
        if (verticalFitTypes != null) {
            val rows = r.elements.filter { it.type in verticalFitTypes }
            val top = rows.minOfOrNull { it.hitBox.origin.y.value }
            val bottom = rows.maxOfOrNull { it.hitBox.origin.y.value + it.hitBox.height.value }
            if (top != null && bottom != null) {
                topDesign = top
                bottomDesign = bottom
            }
        }

        // The renderer multiplies design px by density internally, so the on-canvas extent is
        // (design * density). Fit that into the canvas and place it per [alignment].
        val contentW = (rightDesign - leftDesign) * density
        val contentH = (bottomDesign - topDesign) * density
        if (contentW <= 0f || contentH <= 0f) return@Canvas

        val fit = minOf(size.width / contentW, size.height / contentH).coerceAtMost(maxScale) * fitScale
        val placed = IntSize((contentW * fit).roundToInt(), (contentH * fit).roundToInt())
        val canvasSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val origin = alignment.align(placed, canvasSize, LayoutDirection.Ltr)

        // Shift so the fitted content's own top-left lands at the aligned origin, then scale to fit.
        val tx = origin.x - leftDesign * density * fit
        val ty = origin.y - topDesign * density * fit

        // Clip horizontally to the placed rect so any element past the crop (the full-width staff
        // lines) is hidden. Vertically the clip is the whole canvas, so a glyph overhanging the
        // vertical-fit box (a treble clef above/below its staff) stays visible.
        clipRect(
            left = origin.x.toFloat(),
            top = 0f,
            right = origin.x + placed.width.toFloat(),
            bottom = size.height
        ) {
            withTransform({
                translate(tx, ty)
                scale(fit, fit, pivot = Offset.Zero)
            }) {
                composeRenderer.render(
                    this, elements, textMeasurer, styleSnapshot, r.sectionIndex,
                    overrideColor = foreground?.toRenderColor()
                )
            }
        }
    }
}

/** Compose [Color] (0..1 channels) → engraver [RenderColor] (0..255 channels). */
private fun Color.toRenderColor(): RenderColor = RenderColor.rgba(
    (red * 255f).roundToInt(),
    (green * 255f).roundToInt(),
    (blue * 255f).roundToInt(),
    (alpha * 255f).roundToInt()
)
