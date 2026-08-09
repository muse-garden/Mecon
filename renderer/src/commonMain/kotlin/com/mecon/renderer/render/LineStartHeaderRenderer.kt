package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.renderer.elements.ElementRenderContext
import com.mecon.renderer.elements.ElementRenderOutput
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.layout.StaffLayoutInfo
import com.mecon.renderer.layout.SystemLayout
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.smufl.BravuraFont

internal const val REUSABLE_LINE_START_HEADER = "mecon.splice.reusableLineStartHeader"

/** Renders the hit-ful clef/key headers re-stated at paginated line starts. */
context(BravuraFont)
internal class LineStartHeaderRenderer(
    private val transformer: CoordinateTransformer,
) {
    fun render(
        layoutResult: UnifiedLayoutResult,
        computedScore: ComputedScore,
        staffFor: (Int, Int) -> StaffLayoutInfo?,
        nextId: () -> RenderElementId,
        collect: (ElementRenderOutput, Int, Int) -> Unit,
        systemFilter: (SystemLayout) -> Boolean = { true },
    ) {
        for (system in layoutResult.systems) {
            if (!systemFilter(system)) continue
            for (header in system.lineStartHeaders) {
                val staffLayout = staffFor(system.systemIndex, header.staffIndex) ?: continue
                val ctx = ElementRenderContext(
                    offset = RelativePoint(header.baseX, staffLayout.centerY),
                    transformer = transformer,
                    idGenerator = nextId,
                    computedScore = computedScore
                )
                header.clef?.let { collect(it.render(ctx).asReusableLineStartHeader(), header.staffIndex, system.systemIndex) }
                header.keySignature?.let {
                    collect(it.render(ctx).asReusableLineStartHeader(), header.staffIndex, system.systemIndex)
                }
            }
            // Courtesy clef at this system's right end, warning of the next system's clef change.
            for (courtesy in system.lineEndClefs) {
                val staffLayout = staffFor(system.systemIndex, courtesy.staffIndex) ?: continue
                val ctx = ElementRenderContext(
                    offset = RelativePoint(courtesy.baseX, staffLayout.centerY),
                    transformer = transformer,
                    idGenerator = nextId,
                    computedScore = computedScore
                )
                collect(courtesy.clef.render(ctx).asReusableLineStartHeader(), courtesy.staffIndex, system.systemIndex)
            }
        }
    }

    private fun ElementRenderOutput.asReusableLineStartHeader(): ElementRenderOutput = copy(
        renderElements = renderElements.map { element ->
            element.copy(metadata = element.metadata + (REUSABLE_LINE_START_HEADER to "true"))
        }
    )
}
