package com.mecon.desktop.ui.components.inspector.curve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.interaction.VoiceTieSection
import com.mecon.desktop.ui.components.inspector.CurvePropertiesActions
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class CurvePropertiesContributor(
    private val actions: CurvePropertiesActions,
) : SelectionPropertyContributor {
    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        slurAbove(context) != null || tieAbove(context) != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        slurAbove(context)?.let { above ->
            Text(i18n("inspector.curve.slurPosition"), fontSize = 10.sp, color = MeconColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorChoiceButton(i18n("inspector.curve.above"), selected = above) {
                    actions.changeSlurDirection(true)
                }
                InspectorChoiceButton(i18n("inspector.curve.below"), selected = !above) {
                    actions.changeSlurDirection(false)
                }
            }
        }
        tieAbove(context)?.let { above ->
            Text(i18n("inspector.curve.tiePosition"), fontSize = 10.sp, color = MeconColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorChoiceButton(i18n("inspector.curve.above"), selected = above) {
                    actions.changeTieDirection(true)
                }
                InspectorChoiceButton(i18n("inspector.curve.below"), selected = !above) {
                    actions.changeTieDirection(false)
                }
            }
        }
    }

    private fun slurAbove(context: SelectionInspectorContext): Boolean? {
        val section = context.selection.singleOrNull() as? VoiceSlurSection ?: return null
        val slurId = context.computedScore?.slurs?.firstOrNull {
            (section.slurId == null || it.slurId == section.slurId) &&
                it.startEventId == section.startEvent.id &&
                it.endEventId == section.endEvent.id &&
                it.nestingLevel == section.nestingLevel
        }?.slurId ?: return null
        return context.runtimeGeometry?.slurs?.get(slurId)?.above
            ?: context.renderedGeometry?.slurs?.get(slurId)?.above
    }

    private fun tieAbove(context: SelectionInspectorContext): Boolean? {
        val section = context.selection.singleOrNull() as? VoiceTieSection ?: return null
        return context.runtimeGeometry?.ties?.get(section.sourceEvent.id)
            ?.firstOrNull { it.sourcePitchIndex == section.sourcePitchIndex }
            ?.above
            ?: context.renderedGeometry?.ties?.get(section.sourceEvent.id)
                ?.firstOrNull { it.sourcePitchIndex == section.sourcePitchIndex }
                ?.above
    }
}
