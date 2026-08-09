package com.mecon.desktop.ui.components.inspector.tempo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoTransition
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.InspectorDeletePolicy
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.ui.components.inspector.TempoPropertiesActions
import com.mecon.desktop.uikit.components.MeconTextField
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class TempoPropertiesContributor(
    private val actions: TempoPropertiesActions,
) : SelectionPropertyContributor {
    private fun tempo(context: SelectionInspectorContext): ComputedTempoKeyframe? =
        (context.selection.singleOrNull() as? StaffAttachmentSection)
            ?.attachment as? ComputedTempoKeyframe

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        tempo(context) != null

    override fun deletePolicy(context: SelectionInspectorContext): InspectorDeletePolicy {
        val tempo = tempo(context) ?: return InspectorDeletePolicy.DEFAULT
        val opening = tempo.time.measure == 1 && (tempo.time.beat?.numerator ?: 0) == 0
        return if (opening) InspectorDeletePolicy.DENY else InspectorDeletePolicy.DEFAULT
    }

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val tempo = tempo(context) ?: return
        var bpmText by remember(tempo.id, tempo.effectiveBpm) {
            mutableStateOf(tempo.effectiveBpm.toInt().toString())
        }
        Text(i18n("inspector.tempo.effectiveBpm"), fontSize = 10.sp, color = MeconColors.TextMuted)
        MeconTextField(
            value = bpmText,
            onValueChange = { bpmText = it },
            onCommit = { value ->
                value.toFloatOrNull()?.takeIf { it in 10f..600f }?.let {
                    actions.changeBpm(tempo.id, it)
                } ?: run { bpmText = tempo.effectiveBpm.toInt().toString() }
            },
            singleLine = true,
            modifier = Modifier.width(112.dp),
        )
        Text(i18n("inspector.tempo.display"), fontSize = 10.sp, color = MeconColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorChoiceButton(
                i18n("inspector.common.show"),
                tempo.displayStyle != TempoDisplayStyle.HIDDEN,
            ) {
                actions.changeDisplayStyle(tempo.id, TempoDisplayStyle.METRONOME)
            }
            InspectorChoiceButton(
                i18n("inspector.common.hide"),
                tempo.displayStyle == TempoDisplayStyle.HIDDEN,
            ) {
                actions.changeDisplayStyle(tempo.id, TempoDisplayStyle.HIDDEN)
            }
        }
        Text(i18n("inspector.tempo.toNext"), fontSize = 10.sp, color = MeconColors.TextMuted)
        TempoTransition.entries.chunked(3).forEach { transitions ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                transitions.forEach { transition ->
                    InspectorChoiceButton(
                        label = i18n("inspector.tempo.transition.${transition.name.lowercase()}"),
                        selected = tempo.transitionToNext == transition,
                    ) {
                        actions.changeTransition(tempo.id, transition)
                    }
                }
            }
        }
    }
}
