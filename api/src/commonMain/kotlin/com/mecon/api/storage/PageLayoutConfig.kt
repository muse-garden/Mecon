package com.mecon.api.storage

import kotlinx.serialization.Serializable

/**
 * Page layout configuration for line-breaking and pagination.
 *
 * All physical dimensions are stored in millimetres. The renderer works in
 * abstract *staff spaces*; [staffSpaceMm] is the rastral size — how many
 * millimetres one staff space occupies — and bridges the two coordinate
 * systems. The usable line width / page height (in staff spaces) the layout
 * engine consumes are derived via [contentWidthStaffSpaces] /
 * [contentHeightStaffSpaces].
 *
 * Persisted on [StorageScore.pageLayout] so paper choice, margins, scale and
 * the paginate toggle survive save/reload. Adding it with a default keeps old
 * files deserializing cleanly.
 *
 * @property paginated When false (default) the score renders as one continuous
 *   system (current behaviour). When true the layout engine breaks the score
 *   into systems and pages.
 */
@Serializable
data class PageLayoutConfig(
    val paperWidthMm: Float = 210f,
    val paperHeightMm: Float = 297f,
    val marginTopMm: Float = 15f,
    val marginBottomMm: Float = 15f,
    val marginLeftMm: Float = 15f,
    val marginRightMm: Float = 15f,
    /** Rastral: one staff space in millimetres (the score scale). */
    val staffSpaceMm: Float = 1.8f,
    val paginated: Boolean = false,
    /** Display name of the matching [PaperPreset], or null when custom. */
    val presetName: String? = "A4"
) {
    /** Usable content width (paper minus left/right margins) in staff spaces. */
    val contentWidthStaffSpaces: Float
        get() = (paperWidthMm - marginLeftMm - marginRightMm) / staffSpaceMm

    /** Usable content height (paper minus top/bottom margins) in staff spaces. */
    val contentHeightStaffSpaces: Float
        get() = (paperHeightMm - marginTopMm - marginBottomMm) / staffSpaceMm

    /** Full paper width in staff spaces (for drawing page rectangles). */
    val paperWidthStaffSpaces: Float get() = paperWidthMm / staffSpaceMm

    /** Full paper height in staff spaces. */
    val paperHeightStaffSpaces: Float get() = paperHeightMm / staffSpaceMm

    /** Apply a paper preset, keeping margins / scale / paginate as-is. */
    fun withPreset(preset: PaperPreset): PageLayoutConfig = copy(
        paperWidthMm = preset.widthMm,
        paperHeightMm = preset.heightMm,
        presetName = preset.displayName
    )

    companion object {
        val DEFAULT = PageLayoutConfig()
    }
}

/**
 * How paginated pages are arranged on the canvas by the UI.
 *
 * Purely a view-side concern: the renderer always emits page-local element groups and never
 * reads this. It lives in [ScoreViewPreferences] so the user's choice survives save/reload.
 */
@Serializable
enum class PageArrangement {
    /** Pages stacked top-to-bottom in a single column. */
    VERTICAL,

    /** Pages laid left-to-right in a single row. */
    HORIZONTAL
}

/**
 * View / display preferences for a score. These describe how the score is *presented* and are
 * intentionally separate from [PageLayoutConfig] (which controls spacing and pagination). The
 * page arrangement is consumed only by the desktop UI; measure-number visibility is consumed by
 * the renderer's final post-layout overlay pass. Persisted on [StorageScore.viewPreferences] so
 * display choices are restored when the score is reopened.
 *
 * Added with a default so older files deserialize cleanly.
 */
@Serializable
data class ScoreViewPreferences(
    val pageArrangement: PageArrangement = PageArrangement.VERTICAL,
    /** Whether to draw the measure number at the start of every rendered system. */
    val showMeasureNumbers: Boolean = true,
) {
    companion object {
        val DEFAULT = ScoreViewPreferences()
    }
}

/**
 * A named paper size. Dimensions are portrait orientation in millimetres.
 */
@Serializable
data class PaperPreset(
    val displayName: String,
    val widthMm: Float,
    val heightMm: Float
) {
    companion object {
        val A4 = PaperPreset("A4", 210f, 297f)
        val A3 = PaperPreset("A3", 297f, 420f)
        val A5 = PaperPreset("A5", 148f, 210f)
        val LETTER = PaperPreset("Letter", 215.9f, 279.4f)
        val LEGAL = PaperPreset("Legal", 215.9f, 355.6f)

        /** Selectable presets, in display order. */
        val ALL = listOf(A4, A3, A5, LETTER, LEGAL)
    }
}
