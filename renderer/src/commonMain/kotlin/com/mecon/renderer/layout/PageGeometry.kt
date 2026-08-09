package com.mecon.renderer.layout

import com.mecon.api.storage.PageLayoutConfig
import com.mecon.renderer.geometry.StaffSpace

/**
 * Renderer-side page geometry, in staff spaces.
 *
 * The storage-layer [PageLayoutConfig] is expressed in physical millimetres; this
 * is its projection into the abstract staff-space coordinate system the layout
 * engine works in (via `staffSpaceMm`). It is the single value that tells the
 * layout engine whether and how to break the score into systems and pages.
 *
 * In **continuous** mode (the default, [continuous]) the score is laid out as one
 * uninterrupted system exactly as before pagination existed.
 *
 * @property lineWidth Usable width of one system (page width minus L/R margins).
 * @property pageContentHeight Usable height of one page (page height minus T/B margins).
 * @property paperWidth / paperHeight Full paper extent (for drawing page rectangles).
 * @property leftMargin / topMargin Page margins; systems start at these offsets.
 * @property systemGap Vertical gap between two systems stacked on the same page.
 * @property pageGap Vertical gap between consecutive pages on the canvas.
 */
data class PageGeometry(
    val paginated: Boolean,
    val lineWidth: StaffSpace,
    val pageContentHeight: StaffSpace,
    val paperWidth: StaffSpace,
    val paperHeight: StaffSpace,
    val leftMargin: StaffSpace,
    val topMargin: StaffSpace,
    val systemGap: StaffSpace = StaffSpace(8f),
    val pageGap: StaffSpace = StaffSpace(6f),
) {
    companion object {
        /**
         * Continuous (single-system) geometry. [pageWidth] is only a nominal
         * canvas width; nothing is ever broken. Equivalent to pre-pagination
         * behaviour.
         */
        fun continuous(pageWidth: StaffSpace = StaffSpace(100f)): PageGeometry = PageGeometry(
            paginated = false,
            lineWidth = pageWidth,
            pageContentHeight = StaffSpace(Float.MAX_VALUE),
            paperWidth = pageWidth,
            paperHeight = StaffSpace(Float.MAX_VALUE),
            leftMargin = StaffSpace.ZERO,
            topMargin = StaffSpace.ZERO,
        )

        /** Project a storage [PageLayoutConfig] into staff-space page geometry. */
        fun from(config: PageLayoutConfig): PageGeometry {
            if (!config.paginated) {
                // Honour paper width as the line width even when not paginating, so the
                // single system width tracks the chosen paper. Height stays unbounded.
                return continuous(StaffSpace(config.contentWidthStaffSpaces))
            }
            return PageGeometry(
                paginated = true,
                lineWidth = StaffSpace(config.contentWidthStaffSpaces),
                pageContentHeight = StaffSpace(config.contentHeightStaffSpaces),
                paperWidth = StaffSpace(config.paperWidthStaffSpaces),
                paperHeight = StaffSpace(config.paperHeightStaffSpaces),
                leftMargin = StaffSpace(config.marginLeftMm / config.staffSpaceMm),
                topMargin = StaffSpace(config.marginTopMm / config.staffSpaceMm),
            )
        }
    }
}
