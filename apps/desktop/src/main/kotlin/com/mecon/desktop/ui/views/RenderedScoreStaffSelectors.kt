package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult

internal data class RenderedScoreStaffSelectorRegion(
    val choice: RenderedScoreStaffSelectorChoice,
    val bounds: AbsoluteRect,
)

internal fun staffSelectorRegions(
    result: RenderResult,
    score: RuntimeScore?,
    config: RenderedScoreStaffSelectorConfig,
): List<RenderedScoreStaffSelectorRegion> {
    if (score == null || config.choicesByStaffId.isEmpty()) return emptyList()
    val orderedStaffs = score.orderedStaffs()
    return buildList {
        result.spatialIndex.allSystems().forEach { system ->
            val firstMeasure = result.measureBounds
                .filter { it.systemIndex == system.systemIndex }
                .minByOrNull { it.measureNumber }
                ?: return@forEach
            system.staffRegions.forEach { region ->
                val staffId = orderedStaffs.getOrNull(region.staffIndex)?.id ?: return@forEach
                val choices = config.choicesByStaffId[staffId].orEmpty()
                if (choices.isEmpty()) return@forEach
                val anchor = result.transformerSnapshot.toAbsolute(
                    RelativePoint(firstMeasure.leftX, region.centerY),
                )
                val width = 23f
                val gap = 3f
                val height = 22f
                val totalWidth = choices.size * width + (choices.size - 1).coerceAtLeast(0) * gap
                choices.forEachIndexed { index, choice ->
                    add(
                        RenderedScoreStaffSelectorRegion(
                            choice = choice,
                            bounds = AbsoluteRect(
                                origin = AbsolutePoint(
                                    Pixels(anchor.x.value - totalWidth - 5f + index * (width + gap)),
                                    Pixels(anchor.y.value - height / 2f),
                                ),
                                width = Pixels(width),
                                height = Pixels(height),
                            ),
                        )
                    )
                }
            }
        }
    }
}

internal fun DrawScope.drawStaffSelectors(
    regions: List<RenderedScoreStaffSelectorRegion>,
    textMeasurer: TextMeasurer,
    density: Float,
    paginated: Boolean,
    pages: List<RenderPage>,
    pageSlots: List<Offset>,
) {
    regions.forEach { region ->
        val design = if (paginated) {
            globalToDesign(
                region.bounds.origin.x.value,
                region.bounds.origin.y.value,
                pages,
                pageSlots,
            )
        } else {
            Offset(region.bounds.origin.x.value, region.bounds.origin.y.value)
        } ?: return@forEach
        val topLeft = design * density
        val size = Size(region.bounds.width.value * density, region.bounds.height.value * density)
        drawRoundRect(
            color = if (region.choice.selected) MeconColors.PrimaryDark else Color(0xFFF1F3F5),
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(4f * density),
        )
        val layout = textMeasurer.measure(
            region.choice.label,
            style = TextStyle(
                color = if (region.choice.selected) Color.White else Color(0xFF20242A),
                fontSize = 9.sp,
            ),
        )
        drawText(
            layout,
            topLeft = Offset(
                topLeft.x + (size.width - layout.size.width) / 2f,
                topLeft.y + (size.height - layout.size.height) / 2f,
            ),
        )
    }
}
