package com.mecon.desktop.ui.components.inspector.performance

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
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.storage.Articulation
import com.mecon.desktop.ui.components.inspector.PerformancePropertiesActions
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.uikit.components.MeconTextField
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

private data class PerformanceProperty(
    val id: EventId,
    val amount: Fraction,
    val labelKey: String,
)

internal class PerformanceMarkPropertiesContributor(
    private val actions: PerformancePropertiesActions,
) : SelectionPropertyContributor {
    private fun property(context: SelectionInspectorContext): PerformanceProperty? {
        val section = context.selection.singleOrNull()
        val articulation = section as? VoiceArticulationSection
        val fermata = articulation?.event?.fermata?.takeIf {
            articulation.index == articulation.event.articulations.count { art -> art != Articulation.FERMATA }
        }
        if (fermata != null) {
            return PerformanceProperty(
                id = fermata.id,
                amount = fermata.extension,
                labelKey = "inspector.performance.fermataAmount",
            )
        }
        val breath = (section as? StaffAttachmentSection)?.attachment as? ComputedBreathMark
            ?: return null
        return PerformanceProperty(
            id = breath.globalEventId ?: breath.id,
            amount = breath.pause,
            labelKey = "inspector.performance.breathAmount",
        )
    }

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        property(context) != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val property = property(context) ?: return
        var amountText by remember(property.id, property.amount) {
            mutableStateOf("${property.amount.numerator}/${property.amount.denominator}")
        }
        Text(i18n(property.labelKey), fontSize = 10.sp, color = MeconColors.TextMuted)
        MeconTextField(
            value = amountText,
            onValueChange = { amountText = it },
            onCommit = { value ->
                parsePositiveBeatFraction(value)?.let { amount ->
                    actions.changeAmount(property.id, amount)
                } ?: run { amountText = property.amount.toString() }
            },
            singleLine = true,
            modifier = Modifier.width(112.dp),
        )
    }
}

/** Accepts either an integer beat count (`1`) or a positive fraction (`3/2`). */
internal fun parsePositiveBeatFraction(value: String): Fraction? {
    val parts = value.trim().split('/')
    val numerator = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val denominator = when (parts.size) {
        1 -> 1
        2 -> parts[1].trim().toIntOrNull() ?: return null
        else -> return null
    }
    return if (numerator > 0 && denominator > 0) {
        Fraction(numerator, denominator).simplified()
    } else {
        null
    }
}
