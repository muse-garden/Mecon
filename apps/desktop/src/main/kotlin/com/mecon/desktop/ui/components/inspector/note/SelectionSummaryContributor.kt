package com.mecon.desktop.ui.components.inspector.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.desktop.ui.components.inspector.InspectorPropertyRow
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal object SelectionSummaryContributor : SelectionPropertyContributor {
    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        context.selection.size == 1

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val section = context.selection.single()
        val event = when (section) {
            is VoiceNoteSection -> section.event
            is VoiceEventSection -> section.event
            else -> null
        }
        if (event == null) {
            InspectorPropertyRow(i18n("selection.id"), section.sectionId)
            return
        }
        val typeKey = when {
            event.isRest -> "selection.rest"
            event.pitches.size > 1 -> "selection.chord"
            else -> "selection.note"
        }
        val pitchText = when (section) {
            is VoiceNoteSection -> section.pitchData.pitch.format()
            else -> if (event.isRest) null else event.pitches.joinToString(" ") { it.format() }
        }
        val durationText = buildString {
            append(event.duration.base.displayName)
            repeat(event.duration.dots) { append('.') }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                i18n(typeKey),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MeconColors.PrimaryLight,
            )
            InspectorPropertyRow(i18n("selection.position"), event.onset.format())
            InspectorPropertyRow(i18n("selection.duration"), durationText)
            if (pitchText != null) InspectorPropertyRow(i18n("selection.pitch"), pitchText)
        }
    }
}
