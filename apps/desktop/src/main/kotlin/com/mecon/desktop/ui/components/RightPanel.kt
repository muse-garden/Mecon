package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.plugin.PluginRegistry
import com.mecon.desktop.ui.components.inspector.SelectionInspector
import com.mecon.desktop.ui.components.inspector.SelectionInspectorActions
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.uikit.components.HorizontalResizeHandle
import com.mecon.desktop.uikit.components.ResizablePanelItem
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.uikit.plugin.PluginPanelDescriptor
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.theme.MeconDimensions

internal data class RightPanelUiState(
    val width: Dp,
    val collapsed: Boolean,
)

internal data class RightPanelActions(
    val toggleCollapse: () -> Unit,
    val changeWidth: (Float) -> Unit,
)

@Composable
internal fun RightPanel(
    state: RightPanelUiState,
    actions: RightPanelActions,
    selectionContext: SelectionInspectorContext,
    selectionActions: SelectionInspectorActions,
    pluginContext: PluginPanelContext,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .width(if (state.collapsed) 28.dp else state.width)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MeconColors.Border),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MeconColors.SurfaceDark),
            ) {
                if (state.collapsed) {
                    CollapsedRightPanel(onToggleCollapse = actions.toggleCollapse)
                } else {
                    ExpandedRightPanel(
                        selectionContext = selectionContext,
                        selectionActions = selectionActions,
                        pluginContext = pluginContext,
                        onToggleCollapse = actions.toggleCollapse,
                    )
                }
            }
        }
        if (!state.collapsed) {
            HorizontalResizeHandle(
                onDrag = actions.changeWidth,
                value = state.width,
                minValue = MeconDimensions.MinPanelWidth.dp,
                maxValue = MeconDimensions.MaxPanelWidth.dp,
                inverted = true,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

@Composable
private fun CollapsedRightPanel(onToggleCollapse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val expandInteractionSource = remember { MutableInteractionSource() }
        val expandHovered by expandInteractionSource.collectIsHoveredAsState()
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = i18n("panel.expand"),
            modifier = Modifier
                .size(14.dp)
                .hoverable(expandInteractionSource)
                .clickable(
                    interactionSource = expandInteractionSource,
                    indication = null,
                    onClick = onToggleCollapse,
                ),
            tint = if (expandHovered) MeconColors.SelectedIcon else MeconColors.IconDefault,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(1.dp)
                .background(MeconColors.Border),
        )
        Spacer(Modifier.height(12.dp))
        val panelIcons = buildList {
            add(Icons.Default.Warning to i18n("panel.rangeChecker"))
            PluginRegistry.panelDescriptors()
                .mapNotNull { it as? PluginPanelDescriptor }
                .forEach { descriptor ->
                    descriptor.panel.icon?.let { add(it to i18n(descriptor.panel.titleKey)) }
                }
        }
        panelIcons.forEach { (icon, description) ->
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val pressed by interactionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            pressed -> MeconColors.HoverBackgroundLight
                            hovered -> MeconColors.HoverBackground
                            else -> Color.Transparent
                        }
                    )
                    .hoverable(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null) {}
                    .padding(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    modifier = Modifier.size(12.dp),
                    tint = if (hovered || pressed) MeconColors.SelectedIcon else MeconColors.IconDefault,
                )
            }
        }
    }
}

@Composable
private fun ExpandedRightPanel(
    selectionContext: SelectionInspectorContext,
    selectionActions: SelectionInspectorActions,
    pluginContext: PluginPanelContext,
    onToggleCollapse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(MeconColors.SurfaceDark)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                i18n("panel.inspector"),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MeconColors.TextSecondary,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.weight(1f))
            val collapseInteractionSource = remember { MutableInteractionSource() }
            val collapseHovered by collapseInteractionSource.collectIsHoveredAsState()
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = i18n("panel.collapse"),
                modifier = Modifier
                    .size(12.dp)
                    .hoverable(collapseInteractionSource)
                    .clickable(
                        interactionSource = collapseInteractionSource,
                        indication = null,
                        onClick = onToggleCollapse,
                    ),
                tint = if (collapseHovered) MeconColors.SelectedIcon else MeconColors.IconDefault,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ResizablePanelItem(
                title = i18n("panel.selectionProperties"),
                icon = Icons.Default.Settings,
                initialHeight = 160.dp,
                wrapContent = selectionContext.selection.isNotEmpty(),
            ) {
                if (selectionContext.selection.isNotEmpty()) {
                    SelectionInspector(selectionContext, selectionActions)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            i18n("panel.noSelection"),
                            fontSize = 10.sp,
                            color = MeconColors.TextMuted,
                        )
                    }
                }
            }
            RangeCheckerPanel()
            PluginRegistry.panelDescriptors()
                .mapNotNull { it as? PluginPanelDescriptor }
                .forEach { descriptor ->
                    val panel = descriptor.panel
                    ResizablePanelItem(
                        title = i18n(panel.titleKey),
                        icon = panel.icon,
                        initialHeight = panel.initialHeightDp.dp,
                        noPadding = panel.noPadding,
                        noScroll = panel.noScroll,
                        wrapContent = panel.wrapContent,
                    ) {
                        panel.Content(pluginContext)
                    }
                }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MeconColors.SurfaceDark),
            )
        }
    }
}
