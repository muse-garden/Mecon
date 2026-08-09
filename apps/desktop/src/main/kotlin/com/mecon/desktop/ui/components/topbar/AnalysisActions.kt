package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.runtime.Composable
import com.mecon.desktop.ui.components.AnalysisToolbarActions
import com.mecon.desktop.ui.components.AnalysisToolbarState

@Composable
internal fun AnalysisActions(state: AnalysisToolbarState, actions: AnalysisToolbarActions) {
    Row {
        ToolbarButton(
            Icons.Default.Add,
            "新建缩谱",
            enabled = state.reductionCount == 0,
            onClick = actions.createReduction,
        )
        ToolbarButton(
            Icons.Default.ViewModule,
            "演奏者/谱表",
            isActive = state.orchestrationEnabled,
            onClick = actions.enableOrchestration,
        )
    }
}
