package com.mecon.desktop.ui.components.inspector.barline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.primitive.BarlineType
import com.mecon.core.engine.edit.BarlineEditEngine
import com.mecon.desktop.ui.components.inspector.BarlinePropertiesActions
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.InspectorPropertyRow
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class BarlinePropertiesContributor(
    private val actions: BarlinePropertiesActions,
) : SelectionPropertyContributor {
    private fun section(context: SelectionInspectorContext): BarlineSection? =
        context.selection.singleOrNull() as? BarlineSection

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        section(context) != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val selected = section(context) ?: return
        val runtime = context.runtimeScore
        val typeKey = when (selected.barline.type) {
            BarlineType.REPEAT_LEFT -> "inspector.barline.repeatLeft"
            BarlineType.REPEAT_RIGHT -> "inspector.barline.repeatRight"
            BarlineType.REPEAT_BOTH -> "inspector.barline.repeatBoth"
            else -> "inspector.barline.regular"
        }
        Text(
            i18n(typeKey),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MeconColors.PrimaryLight,
        )
        InspectorPropertyRow(
            i18n("selection.position"),
            i18n("inspector.barline.afterMeasure").format(selected.barline.measureNumber),
        )
        runtime?.getMeasure(selected.barline.measureNumber + 1)
            ?.voltaNumbers
            ?.takeIf { it.isNotEmpty() }
            ?.let { numbers ->
                InspectorPropertyRow(
                    i18n("inspector.barline.voltaStart"),
                    numbers.sorted().joinToString(",") { "$it." },
                )
            }
        runtime?.getMeasure(selected.barline.measureNumber)
            ?.navigationMarks
            ?.takeIf { it.isNotEmpty() }
            ?.let { marks ->
                InspectorPropertyRow(
                    i18n("inspector.barline.navigation"),
                    marks.joinToString(", ") { it.name },
                )
            }
        val repeatBoundary = runtime?.let {
            BarlineEditEngine.repeatCountBoundaryAt(it, selected.barline.measureNumber)
        }
        if (repeatBoundary != null) {
            val count = BarlineEditEngine.repeatCountAt(runtime, selected.barline.measureNumber)
            Text(i18n("inspector.barline.repeatCount"), fontSize = 10.sp, color = MeconColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (2..4).forEach { candidate ->
                    InspectorChoiceButton("×$candidate", selected = count == candidate) {
                        actions.changeRepeatCount(selected.barline.measureNumber, candidate)
                    }
                }
            }
        }
    }
}
