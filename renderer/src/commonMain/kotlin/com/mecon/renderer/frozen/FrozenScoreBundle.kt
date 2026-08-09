package com.mecon.renderer.frozen

import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.render.RenderElement
import kotlinx.serialization.Serializable

/**
 * A versioned, engine-independent snapshot of a fully engraved score — everything a
 * lightweight viewer needs to **replay** the drawing without running Compute / Layout /
 * Render again.
 *
 * This is the `.mecon` container's `geometry/<scoreId>.json` payload (see
 * `docs/data_model/mecon-container.md` and `docs/multiplatform-porting.md` §4.1). Each
 * [FrozenSurface] carries a list of [RenderElement]s — themselves already `@Serializable`,
 * holding platform-agnostic [com.mecon.renderer.render.RenderCommand]s plus the event /
 * measure / system / staff metadata a viewer uses for click, playback highlight and simple
 * accessibility. A web Canvas2D backend only replays these commands.
 *
 * The bundle deliberately does **not** embed the engine's process-only indices
 * ([com.mecon.renderer.render.RenderResult.spatialIndex] etc.); a viewer rebuilds any
 * lookup it needs from the flat element list.
 *
 * @property schemaVersion  Bumped on any incompatible change to this shape.
 * @property engineVersion  The engine build that produced this geometry (for diagnostics /
 *   cache invalidation). Not read by the viewer's replay path.
 * @property fontFingerprint Exact music-font (Bravura) identity the [DrawGlyph] commands were
 *   laid out against; a viewer must load the *same* font before claiming pixel fidelity.
 * @property paginated       True when [surfaces] are discrete pages; false = one continuous surface.
 * @property bounds          Total content bounds in absolute (pixel) coordinates.
 * @property surfaces        One continuous surface, or one per page in paginated mode.
 * @property timePositions   Playhead anchor per [TimeCode], so playback highlight needs no re-layout.
 */
@Serializable
data class FrozenScoreBundle(
    val schemaVersion: Int = SCHEMA_VERSION,
    val engineVersion: String = "",
    val fontFingerprint: String = "",
    val paginated: Boolean = false,
    val bounds: AbsoluteRect,
    val surfaces: List<FrozenSurface> = emptyList(),
    val timePositions: List<FrozenTimePosition> = emptyList(),
) {
    companion object {
        /** Current on-disk schema for the frozen geometry payload. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * One drawable surface: the single continuous system, or one page of a paginated layout.
 *
 * [elements] are carried in the same coordinate frame the renderer produced them in — for a
 * continuous surface that is the global render space; for a page it is that page's *local*
 * space (Y measured from the page top), with [contentOffsetY] giving the page's origin in the
 * global space so a viewer can map a local point back for cross-page hit testing.
 *
 * @property index          Page index in paginated mode; 0 for the single continuous surface.
 * @property width          Surface width in pixels (paper width for a page).
 * @property height         Surface height in pixels (paper height for a page).
 * @property contentOffsetY Page origin Y in the global render space; 0 for a continuous surface.
 * @property elements       Draw + metadata elements to replay for this surface.
 */
@Serializable
data class FrozenSurface(
    val index: Int = 0,
    val width: Float,
    val height: Float,
    val contentOffsetY: Float = 0f,
    val elements: List<RenderElement> = emptyList(),
)

/**
 * A playhead anchor: where a [TimeCode] sits horizontally and the staff span it covers, so a
 * viewer can draw / animate the playhead purely from stored geometry.
 *
 * Mirrors [com.mecon.renderer.render.TimeCodePosition] but as a stable, serializable record.
 */
@Serializable
data class FrozenTimePosition(
    val timeCode: TimeCode,
    val x: Float,
    val topY: Float,
    val bottomY: Float,
    val leftX: Float,
)
