package com.mecon.api.plugin

/**
 * Plugin SPI for contributing an annotation staff. The renderer queries every
 * registered provider during layout and renders the returned elements into a
 * dedicated annotation staff anchored per [anchor].
 *
 * Providers are stateless — all per-score state lives in the [ComputedScore][com.mecon.api.computed.ComputedScore]
 * passed via [AnnotationLayoutContext].
 */
interface AnnotationStaffProvider {
    /** Stable, namespaced identifier for this provider's annotation staff. */
    val staffId: PluginStaffId

    /** Where the annotation staff should sit relative to notation staves. */
    val anchor: StaffAnchor

    /**
     * Plugin track types (i.e. `StoragePluginTrack.type` strings) this provider
     * consumes. The renderer skips this provider entirely when the score has no
     * matching plugin tracks. Return an empty set for full-score computed
     * annotations that do not depend on storage plugin tracks.
     */
    val pluginTrackTypes: Set<String>

    /**
     * Produce the annotation elements to draw on this staff. Coordinates are
     * relative — the renderer resolves x via [AnnotationLayoutContext.xForTime]
     * and y via element bounds computed from [AnnotationElement.relativeY].
     */
    fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement>
}
