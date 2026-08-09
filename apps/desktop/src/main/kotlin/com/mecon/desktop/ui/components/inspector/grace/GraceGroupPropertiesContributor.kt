package com.mecon.desktop.ui.components.inspector.grace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.desktop.ui.components.inspector.GraceGroupPropertiesActions
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class GraceGroupPropertiesContributor(
    private val actions: GraceGroupPropertiesActions,
) : SelectionPropertyContributor {
    private fun graceGroup(context: SelectionInspectorContext): RuntimeVoiceEvent? {
        val selected = when (val section = context.selection.singleOrNull()) {
            is VoiceNoteSection -> section.event
            is VoiceEventSection -> section.event
            else -> null
        }?.takeIf { it.isGrace } ?: return null
        return context.runtimeScore?.voiceTracks?.values
            ?.firstOrNull { voice -> voice.events.toList().any { it.id == selected.id } }
            ?.events?.toList()
            ?.filter {
                it.isGrace &&
                    it.onset.measure == selected.onset.measure &&
                    it.onset.beat == selected.onset.beat
            }
            ?.sortedBy { it.onset }
            ?.firstOrNull()
    }

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        graceGroup(context)?.graceInfo != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val group = graceGroup(context) ?: return
        val info = group.graceInfo ?: return
        Text(i18n("inspector.grace.totalDuration"), fontSize = 10.sp, color = MeconColors.TextMuted)
        listOf(
            DurationBase.BREVE to "2",
            DurationBase.WHOLE to "1",
            DurationBase.HALF to "1/2",
            DurationBase.QUARTER to "1/4",
            DurationBase.EIGHTH to "1/8",
            DurationBase.SIXTEENTH to "1/16",
            DurationBase.THIRTY_SECOND to "1/32",
            DurationBase.SIXTY_FOURTH to "1/64",
        ).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (base, label) ->
                    InspectorChoiceButton(label, info.totalDuration.base == base) {
                        actions.changeGroup(
                            group.id,
                            Duration(base, info.totalDuration.dots),
                            info.stealFrom,
                        )
                    }
                }
            }
        }
        Text(i18n("inspector.grace.dots"), fontSize = 10.sp, color = MeconColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                0 to i18n("inspector.common.none"),
                1 to "·",
                2 to "··",
            ).forEach { (dots, label) ->
                InspectorChoiceButton(label, info.totalDuration.dots == dots) {
                    actions.changeGroup(
                        group.id,
                        Duration(info.totalDuration.base, dots),
                        info.stealFrom,
                    )
                }
            }
        }
        Text(i18n("inspector.grace.timeSource"), fontSize = 10.sp, color = MeconColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorChoiceButton(
                i18n("inspector.grace.previous"),
                info.stealFrom == GraceTimeSource.PREVIOUS,
            ) {
                actions.changeGroup(group.id, info.totalDuration, GraceTimeSource.PREVIOUS)
            }
            InspectorChoiceButton(
                i18n("inspector.grace.principal"),
                info.stealFrom == GraceTimeSource.PRINCIPAL,
            ) {
                actions.changeGroup(group.id, info.totalDuration, GraceTimeSource.PRINCIPAL)
            }
        }
    }
}
