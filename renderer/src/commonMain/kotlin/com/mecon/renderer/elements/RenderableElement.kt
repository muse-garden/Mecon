package com.mecon.renderer.elements

import com.mecon.renderer.smufl.BravuraFont

/**
 * An element that can produce [ElementRenderOutput] (render elements + section registrations).
 *
 * This interface separates rendering responsibility from layout responsibility.
 * [LayoutElement] (formerly BaseElement) defines layout properties (time, priority, width),
 * while [RenderableElement] defines how to produce visual output.
 *
 * Elements that participate in time-slot layout (notes, barlines, clefs, etc.)
 * implement both [LayoutElement] and [RenderableElement].
 *
 * Elements that do NOT participate in time-slot layout (stems, flags, beams)
 * implement only [RenderableElement].
 */
interface RenderableElement {
    /**
     * Produce render elements and section registrations for this element.
     *
     * Implementations should use `context.offset` as the base position and
     * add their own `relativeX` offset internally (if applicable).
     *
     * @param context Rendering context with position, transformer, ID generator, and computed score
     * @return Output containing render elements and section registrations
     */
    context(BravuraFont)
    fun render(context: ElementRenderContext): ElementRenderOutput
}
