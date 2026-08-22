package com.mecon.desktop.ui.components.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.ui.components.inspector.barline.BarlinePropertiesContributor
import com.mecon.desktop.ui.components.inspector.curve.CurvePropertiesContributor
import com.mecon.desktop.ui.components.inspector.grace.GraceGroupPropertiesContributor
import com.mecon.desktop.ui.components.inspector.note.SelectionSummaryContributor
import com.mecon.desktop.ui.components.inspector.ornament.OrnamentPropertiesContributor
import com.mecon.desktop.ui.components.inspector.performance.PerformanceMarkPropertiesContributor
import com.mecon.desktop.ui.components.inspector.tempo.TempoPropertiesContributor
import com.mecon.desktop.ui.components.inspector.tuplet.TupletPropertiesContributor
import com.mecon.desktop.ui.components.inspector.visibility.HiddenStaffPropertiesContributor
import com.mecon.desktop.ui.components.inspector.visibility.HiddenStaffRegionPropertiesContributor
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun SelectionInspector(
    context: SelectionInspectorContext,
    actions: SelectionInspectorActions,
) {
    val contributors = listOf(
        HiddenStaffPropertiesContributor(actions.staffVisibility),
        SelectionSummaryContributor,
        TempoPropertiesContributor(actions.tempo),
        PerformanceMarkPropertiesContributor(actions.performance),
        OrnamentPropertiesContributor(actions.ornaments),
        GraceGroupPropertiesContributor(actions.graceGroup),
        BarlinePropertiesContributor(actions.barline),
        HiddenStaffRegionPropertiesContributor(actions.staffVisibility),
        CurvePropertiesContributor(actions.curves),
        TupletPropertiesContributor(actions.tuplets),
    )
    val applicable = contributors.filter { it.isApplicable(context) }
    val visible = applicable.firstOrNull { it.exclusive }?.let(::listOf) ?: applicable

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (context.selection.size > 1) {
            Text(
                i18n("panel.selectionCount").format(context.selection.size),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MeconColors.TextSecondary,
            )
        }
        visible.forEach { contributor -> contributor.Content(context) }
        if (
            context.selection.isNotEmpty() &&
            visible.none { it.deletePolicy(context) == InspectorDeletePolicy.DENY }
        ) {
            InspectorDeleteButton(onClick = actions.delete)
        }
    }
}
