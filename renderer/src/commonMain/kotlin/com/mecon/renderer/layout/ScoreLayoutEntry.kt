package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.BeamingInfo
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace

/**
 * Entry point for score layout computation.
 *
 * Uses the unified layout system where all events (notes, barlines, clefs, etc.)
 * are processed together in time code order to ensure proper alignment.
 *
 * ## Usage
 *
 * ```kotlin
 * val score: RuntimeScore = ...
 * val layoutResult = ScoreLayoutEntry.computeLayout(score)
 *
 * // Use with RenderEngine
 * val renderResult = renderEngine.renderUnified(layoutResult)
 * ```
 *
 * ## Coordinate System
 *
 * The unified layout uses a two-level coordinate system:
 * - Time code X positions: Absolute X positions for each time code
 * - Element relative offsets: (relativeX, relativeY) from time code X and staff center Y
 *
 * This design allows:
 * 1. Element selection by filtering time code X first, then checking relative positions
 * 2. Multi-line layout adjustments without recalculating element offsets
 * 3. Clean separation between time mapping and element positioning
 */
object ScoreLayoutEntry {

    /**
     * Result of layout computation, containing both the layout data
     * and the computed score used to generate it.
     */
    data class LayoutWithComputed(
        val layout: UnifiedLayoutResult,
        val computedScore: ComputedScore
    )

    /**
     * Compute layout for a score.
     *
     * This is the primary method for layout computation. It processes
     * all events (notes, barlines, clefs, etc.) together in time code order
     * to ensure proper alignment.
     *
     * @param score The runtime score to layout
     * @param pageWidth Available page width in staff spaces
     * @param pageHeight Available page height in staff spaces
     * @param config Layout configuration (optional)
     * @return UnifiedLayoutResult containing all layout data
     */
    context(com.mecon.renderer.smufl.BravuraFont)
    fun computeLayout(
        score: RuntimeScore,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT,
        pageGeometry: PageGeometry = PageGeometry.continuous(pageWidth)
    ): UnifiedLayoutResult {
        return computeLayoutWithComputed(score, pageWidth, pageHeight, config, pageGeometry).layout
    }

    /**
     * Compute layout and return both the layout result and the computed score.
     *
     * Use this when the caller needs access to the ComputedScore (e.g., for
     * building the [com.mecon.renderer.interaction.SectionIndex]).
     *
     * @param score The runtime score to layout
     * @param pageWidth Available page width in staff spaces
     * @param pageHeight Available page height in staff spaces
     * @param config Layout configuration (optional)
     * @return [LayoutWithComputed] containing layout data and computed score
     */
    context(com.mecon.renderer.smufl.BravuraFont)
    fun computeLayoutWithComputed(
        score: RuntimeScore,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT,
        pageGeometry: PageGeometry = PageGeometry.continuous(pageWidth)
    ): LayoutWithComputed {
        // Create computed score
        val computed = com.mecon.core.engine.computeScore(score)

        // Delegate to unified layout computer
        val layoutComputer = UnifiedLayoutComputer(config)
        val layout = layoutComputer.computeLayout(
            computed = computed,
            runtime = score,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            pageGeometry = pageGeometry
        )

        return LayoutWithComputed(layout, computed)
    }
}
