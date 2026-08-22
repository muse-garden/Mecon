package com.mecon.desktop.ui.components.inspector.tuplet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.VoiceTupletSection
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.ui.components.inspector.TupletPropertiesActions
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class TupletPropertiesContributor(
    private val actions: TupletPropertiesActions,
) : SelectionPropertyContributor {
    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        direction(context) != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val above = direction(context) ?: return
        Text(i18n("inspector.tuplet.position"), fontSize = 10.sp, color = MeconColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorChoiceButton(i18n("inspector.curve.above"), selected = above) {
                actions.changeDirection(true)
            }
            InspectorChoiceButton(i18n("inspector.curve.below"), selected = !above) {
                actions.changeDirection(false)
            }
        }
    }

    private fun direction(context: SelectionInspectorContext): Boolean? {
        val section = context.selection.singleOrNull() as? VoiceTupletSection ?: return null
        val id = section.startEvent.id
        return context.runtimeGeometry?.tuplets?.get(id)?.above
            ?: context.renderedGeometry?.tuplets?.get(id)?.above
    }
}
