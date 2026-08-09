package com.mecon.desktop.ui.components.inspector.ornament

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
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.primitive.Accidental
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.desktop.ui.components.inspector.InspectorChoiceButton
import com.mecon.desktop.ui.components.inspector.OrnamentPropertiesActions
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.ui.components.inspector.performance.parsePositiveBeatFraction
import com.mecon.desktop.uikit.components.MeconTextField
import com.mecon.desktop.uikit.theme.MeconColors

internal class OrnamentPropertiesContributor(
    private val actions: OrnamentPropertiesActions,
) : SelectionPropertyContributor {
    private fun mark(context: SelectionInspectorContext): ComputedOrnamentMark? =
        (context.selection.singleOrNull() as? StaffAttachmentSection)
            ?.attachment as? ComputedOrnamentMark

    override fun isApplicable(context: SelectionInspectorContext): Boolean = mark(context) != null

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val mark = mark(context) ?: return
        Text("装饰音", fontSize = 10.sp, color = MeconColors.TextMuted)
        AccidentalRow("上辅助音", mark.upperAccidental) {
            actions.changeUpperAccidental(mark.id, it)
        }
        AccidentalRow("下辅助音", mark.lowerAccidental) {
            actions.changeLowerAccidental(mark.id, it)
        }

        var durationText by remember(mark.id, mark.elementDuration) {
            mutableStateOf("${mark.elementDuration.numerator}/${mark.elementDuration.denominator}")
        }
        Text("元素时值（四分音符拍）", fontSize = 10.sp, color = MeconColors.TextMuted)
        MeconTextField(
            value = durationText,
            onValueChange = { durationText = it },
            onCommit = { value ->
                parsePositiveBeatFraction(value)?.let { actions.changeElementDuration(mark.id, it) }
                    ?: run {
                        durationText = "${mark.elementDuration.numerator}/${mark.elementDuration.denominator}"
                    }
            },
            singleLine = true,
            modifier = Modifier.width(112.dp),
        )

        if (mark.kind in MORDENT_KINDS) {
            var countText by remember(mark.id, mark.oscillations) {
                mutableStateOf(mark.oscillations.toString())
            }
            Text("mordent 波动次数", fontSize = 10.sp, color = MeconColors.TextMuted)
            MeconTextField(
                value = countText,
                onValueChange = { countText = it },
                onCommit = { value ->
                    value.toIntOrNull()?.takeIf { it in 1..16 }
                        ?.let { actions.changeOscillations(mark.id, it) }
                        ?: run { countText = mark.oscillations.toString() }
                },
                singleLine = true,
                modifier = Modifier.width(112.dp),
            )
        }

        if (mark.kind == OrnamentKind.TRILL) {
            Text("trill 播放", fontSize = 10.sp, color = MeconColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    TrillPlaybackMode.AUTO to "自动",
                    TrillPlaybackMode.EXPANDED to "短音",
                    TrillPlaybackMode.CONTROL_FLOW to "控制流",
                ).forEach { (mode, label) ->
                    InspectorChoiceButton(label, mark.trillPlaybackMode == mode) {
                        actions.changeTrillPlaybackMode(mark.id, mode)
                    }
                }
            }
        }
    }

    @Composable
    private fun AccidentalRow(
        label: String,
        selected: Accidental?,
        onChange: (Accidental?) -> Unit,
    ) {
        Text(label, fontSize = 10.sp, color = MeconColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                null to "调内",
                Accidental.FLAT to "♭",
                Accidental.NATURAL to "♮",
                Accidental.SHARP to "♯",
            ).forEach { (value, text) ->
                InspectorChoiceButton(text, selected == value) { onChange(value) }
            }
        }
    }

    private companion object {
        val MORDENT_KINDS = setOf(
            OrnamentKind.MORDENT,
            OrnamentKind.INVERTED_MORDENT,
            OrnamentKind.TREMBLEMENT,
            OrnamentKind.TREMBLEMENT_COUPERIN,
            OrnamentKind.MORDENT_UPPER_PREFIX,
            OrnamentKind.INVERTED_MORDENT_UPPER_PREFIX,
            OrnamentKind.MORDENT_RELEASE,
        )
    }
}
