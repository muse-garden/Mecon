package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecon.api.storage.PageArrangement
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.toggleArrangement
import com.mecon.desktop.uikit.i18n.i18n

/**
 * Page group: page settings and — in paginated mode — the vertical/horizontal
 * arrangement toggle (driven off the [ScoreSession]). The toggle's icon and label
 * describe the arrangement it switches to.
 */
@Composable
internal fun PageControls(
    session: ScoreSession,
    onOpenScoreMetadata: () -> Unit,
    onOpenPageSettings: () -> Unit,
    onReflow: () -> Unit
) {
    ToolbarButton(
        icon = Icons.Default.Edit,
        label = i18n("toolbar.scoreInfo"),
        onClick = onOpenScoreMetadata
    )

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.Description,
        label = i18n("toolbar.pageSettings"),
        onClick = onOpenPageSettings
    )

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.FormatClear,
        label = i18n("toolbar.reflow"),
        onClick = onReflow
    )

    if (session.paginated) {
        Spacer(Modifier.width(4.dp))
        ToolbarButton(
            icon = if (session.pageArrangement == PageArrangement.VERTICAL)
                Icons.Default.ViewColumn else Icons.Default.ViewStream,
            label = if (session.pageArrangement == PageArrangement.VERTICAL)
                i18n("score.arrangement.toHorizontal") else i18n("score.arrangement.toVertical"),
            onClick = session::toggleArrangement
        )
    }
}
