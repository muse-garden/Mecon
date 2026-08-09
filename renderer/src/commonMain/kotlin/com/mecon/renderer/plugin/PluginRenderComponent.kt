package com.mecon.renderer.plugin

import com.mecon.api.computed.ComputedScore
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId

/**
 * Context provided to plugins during the rendering phase.
 */
class PluginRenderContext(
    val transformer: CoordinateTransformer,
    val computedScore: ComputedScore,
    val layoutResult: UnifiedLayoutResult,
    val idGenerator: () -> RenderElementId
)

/**
 * Interface for injecting custom rendering logic into the RenderEngine.
 * Can be used to draw chord text annotations, lyrics, or custom analysis symbols.
 */
interface PluginRenderComponent {
    /**
     * Generate custom render elements based on the score and layout result.
     * The resulting elements will be appended to the engine's final output.
     */
    fun render(context: PluginRenderContext): List<RenderElement>
}
