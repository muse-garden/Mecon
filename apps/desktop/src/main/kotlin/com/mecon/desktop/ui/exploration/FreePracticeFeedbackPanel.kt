package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors
internal data class PracticeFeedbackState(
    val findings: List<PracticeFinding>,
)

internal data object PracticeFeedbackActions

@Composable
internal fun PracticeFeedbackPanel(
    state: PracticeFeedbackState,
    actions: PracticeFeedbackActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WorkbenchPanel("Hint 与警告") {
            if (state.findings.isEmpty()) {
                ProgressRow("当前未发现问题", 1f, MeconColors.EmeraldLight)
            } else {
                state.findings.forEach { finding -> FindingCard(finding) }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, color = MeconColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", color = color, fontSize = 10.sp)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(MeconColors.SurfaceDark, RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun FindingCard(finding: PracticeFinding) {
    val color = when (finding.severity) {
        PracticeFindingSeverity.INFO -> MeconColors.PrimaryLight
        PracticeFindingSeverity.WARNING -> MeconColors.OrangeLight
        PracticeFindingSeverity.ERROR -> MeconColors.Red
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.padding(top = 3.dp).size(7.dp).background(color, RoundedCornerShape(4.dp)))
        Column {
            Text(finding.title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(finding.detail, color = MeconColors.TextMuted, fontSize = 10.sp)
        }
    }
}
