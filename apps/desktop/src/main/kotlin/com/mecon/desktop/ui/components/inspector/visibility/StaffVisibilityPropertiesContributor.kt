package com.mecon.desktop.ui.components.inspector.visibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.HiddenStaffSection
import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.desktop.ui.components.inspector.InspectorActionButton
import com.mecon.desktop.ui.components.inspector.InspectorDeletePolicy
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.SelectionPropertyContributor
import com.mecon.desktop.ui.components.inspector.StaffVisibilityPropertiesActions
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

internal class HiddenStaffPropertiesContributor(
    private val actions: StaffVisibilityPropertiesActions,
) : SelectionPropertyContributor {
    override val exclusive: Boolean = true

    private fun section(context: SelectionInspectorContext): HiddenStaffSection? =
        context.selection.singleOrNull() as? HiddenStaffSection

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        section(context) != null

    override fun deletePolicy(context: SelectionInspectorContext): InspectorDeletePolicy =
        InspectorDeletePolicy.DENY

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val hidden = section(context) ?: return
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                i18n("panel.hiddenStaff"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MeconColors.PrimaryLight,
            )
            InspectorActionButton(i18n("menu.showStaffThisLine")) {
                actions.revealStaff(hidden.staffTrackIds, hidden.range)
            }
            InspectorActionButton(i18n("menu.showStaffFollowing")) {
                actions.revealStaff(
                    hidden.staffTrackIds,
                    MeasureRange(
                        hidden.range.from,
                        context.maxMeasure.coerceAtLeast(hidden.range.from),
                    ),
                )
            }
        }
    }
}

internal class HiddenStaffRegionPropertiesContributor(
    private val actions: StaffVisibilityPropertiesActions,
) : SelectionPropertyContributor {
    private fun hiddenCells(context: SelectionInspectorContext): List<MeasureStaffSection> =
        context.selection.filterIsInstance<MeasureStaffSection>().filter {
            context.runtimeScore?.staffTracks?.get(it.staffTrackId)?.isHidden(it.measureNumber) == true
        }

    override fun isApplicable(context: SelectionInspectorContext): Boolean =
        hiddenCells(context).isNotEmpty()

    @Composable
    override fun Content(context: SelectionInspectorContext) {
        val cells = hiddenCells(context).groupBy({ it.staffTrackId }, { it.measureNumber })
        InspectorActionButton(i18n("menu.revealRegion")) {
            cells.forEach { (staffId, measures) ->
                actions.revealStaff(
                    listOf(staffId),
                    MeasureRange(measures.min(), measures.max()),
                )
            }
        }
        InspectorActionButton(i18n("menu.showStaffFollowing")) {
            cells.forEach { (staffId, measures) ->
                actions.revealStaff(
                    listOf(staffId),
                    MeasureRange(
                        measures.min(),
                        context.maxMeasure.coerceAtLeast(measures.min()),
                    ),
                )
            }
        }
    }
}
