package com.mecon.renderer.layout

import com.mecon.api.storage.ScoreMetadata
import com.mecon.renderer.geometry.StaffSpace

/** Horizontal anchoring of a title-block line relative to its [TitleBlockLine.anchorX]. */
enum class TitleAlignment { LEFT, CENTER, RIGHT }

/**
 * One line of the score title block (title / subtitle / composer), resolved to
 * page-local geometry (page 0 coordinates, in staff spaces). Render-layer
 * independent so this lives alongside the rest of the layout result.
 *
 * [topY] is the top edge of the text (matching how [com.mecon.renderer.render.DrawText]
 * `position.y` is consumed as a top-left Y). [anchorX] is interpreted per [alignment]:
 * the left edge for [TitleAlignment.LEFT], the centre for [TitleAlignment.CENTER], the
 * right edge for [TitleAlignment.RIGHT].
 */
data class TitleBlockLine(
    val text: String,
    val fontSize: StaffSpace,
    val alignment: TitleAlignment,
    val anchorX: StaffSpace,
    val topY: StaffSpace,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * The score title block placed above the first system on the first page.
 *
 * [height] is the vertical room (from the page top margin down) the block reserves;
 * the layout pushes the first system down by this much so the music never overlaps
 * the title. Only produced in paginated mode (see [TitleBlockComputer]).
 */
data class TitleBlockLayout(
    val lines: List<TitleBlockLine>,
    val height: StaffSpace,
)

/**
 * Builds the score title block (title, subtitle, composer) from [ScoreMetadata].
 *
 * The block is centred over the page content width (title / subtitle) with the
 * composer right-aligned to the content right edge. Only the metadata fields that
 * carry text contribute a line; an empty metadata set yields `null` (no block, no
 * reserved space). This mirrors how staff-header labels are produced from computed
 * data in the layout layer — it is typesetting, not score-element generation.
 *
 * Coordinates are in page-0 staff space: [TitleBlockLine.topY] is measured from the
 * top of the page, so the rendered elements fall inside page 0's Y band.
 */
internal class TitleBlockComputer {

    fun compute(
        metadata: ScoreMetadata,
        pageGeometry: PageGeometry,
    ): TitleBlockLayout? {
        val title = metadata.title.takeIf { it.isNotBlank() }
        val subtitle = metadata.subtitle?.takeIf { it.isNotBlank() }
        val composer = metadata.composer?.takeIf { it.isNotBlank() }
        if (title == null && subtitle == null && composer == null) return null

        val contentLeftX = pageGeometry.leftMargin
        val contentRightX = pageGeometry.leftMargin + pageGeometry.lineWidth
        val centerX = StaffSpace((contentLeftX.value + contentRightX.value) / 2f)

        val lines = mutableListOf<TitleBlockLine>()
        var cursorTop = pageGeometry.topMargin + TOP_PAD

        fun addLine(text: String, fontSize: StaffSpace, alignment: TitleAlignment, anchorX: StaffSpace, bold: Boolean, italic: Boolean) {
            lines.add(TitleBlockLine(text, fontSize, alignment, anchorX, cursorTop, bold, italic))
            cursorTop += fontSize + LINE_GAP
        }

        if (title != null) {
            addLine(title, TITLE_FONT_SIZE, TitleAlignment.CENTER, centerX, bold = true, italic = false)
        }
        if (subtitle != null) {
            addLine(subtitle, SUBTITLE_FONT_SIZE, TitleAlignment.CENTER, centerX, bold = false, italic = true)
        }
        if (composer != null) {
            addLine(composer, COMPOSER_FONT_SIZE, TitleAlignment.RIGHT, contentRightX, bold = false, italic = false)
        }

        val height = (cursorTop - pageGeometry.topMargin) - LINE_GAP + BOTTOM_PAD
        return TitleBlockLayout(lines = lines, height = height)
    }

    companion object {
        /** Title font size (staff spaces). */
        val TITLE_FONT_SIZE = StaffSpace(2.4f)
        /** Subtitle font size (staff spaces). */
        val SUBTITLE_FONT_SIZE = StaffSpace(1.5f)
        /** Composer font size (staff spaces). */
        val COMPOSER_FONT_SIZE = StaffSpace(1.5f)

        /** Gap from the page top margin to the title's top edge. */
        private val TOP_PAD = StaffSpace(1.5f)
        /** Vertical gap between successive title-block lines. */
        private val LINE_GAP = StaffSpace(0.8f)
        /** Gap below the last line before the first system begins. */
        private val BOTTOM_PAD = StaffSpace(2.5f)
    }
}
