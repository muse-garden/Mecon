package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.theme.MeconDimensions
import com.mecon.desktop.uikit.components.HorizontalResizeHandle
import com.mecon.desktop.uikit.components.VerticalResizeHandle
import com.mecon.desktop.ui.views.PianoRollView
import com.mecon.desktop.uikit.i18n.i18n

enum class BottomPlugin {
    PIANO_ROLL;

    val displayName: String
        @Composable
        get() = when (this) {
            PIANO_ROLL -> i18n("bottom.pianoRoll")
        }
}

enum class PianoRollDock {
    BOTTOM,
    RIGHT,
}

@Composable
fun BottomPanel(
    size: Dp,
    dock: PianoRollDock,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onSizeChange: (Float) -> Unit,
    onDockChange: (PianoRollDock) -> Unit,
    runtimeScore: RuntimeScore?,
    computedScore: ComputedScore?,
    selection: Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
    showChordOverlay: Boolean,
    showScaleDegrees: Boolean,
    analysisRefreshKey: Any?,
    currentPositionTicks: Long,
    playbackState: PlaybackState,
    activePlugin: BottomPlugin,
    onPluginSelected: (BottomPlugin) -> Unit,
    modifier: Modifier = Modifier
) {
    val panelModifier = if (dock == PianoRollDock.BOTTOM) {
        modifier.fillMaxWidth().height(if (isCollapsed) 28.dp else size)
    } else {
        modifier.fillMaxHeight().width(if (isCollapsed) 28.dp else size)
    }
    Box(modifier = panelModifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (dock == PianoRollDock.BOTTOM) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MeconColors.Border)
                )
            }

            // Panel content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MeconColors.Background)
            ) {
                // Tab bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(MeconColors.Surface)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Collapse button
                    val collapseInteractionSource = remember { MutableInteractionSource() }
                    val isCollapseHovered by collapseInteractionSource.collectIsHoveredAsState()
                    Icon(
                        imageVector = when {
                            dock == PianoRollDock.RIGHT && isCollapsed ->
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            dock == PianoRollDock.RIGHT ->
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            isCollapsed -> Icons.Default.KeyboardArrowUp
                            else -> Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                        modifier = Modifier
                            .size(12.dp)
                            .hoverable(collapseInteractionSource)
                            .clickable(interactionSource = collapseInteractionSource, indication = null, onClick = onToggleCollapse),
                        tint = if (isCollapseHovered) MeconColors.SelectedIcon else MeconColors.IconDefault
                    )
                    Spacer(Modifier.width(8.dp))

                    if (!isCollapsed || dock == PianoRollDock.BOTTOM) {
                        BottomPlugin.entries.forEach { plugin ->
                            val isActive = plugin == activePlugin && !isCollapsed
                            val interactionSource = remember { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            val isPressed by interactionSource.collectIsPressedAsState()
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        when {
                                            isActive -> MeconColors.SelectedSurface
                                            isPressed -> MeconColors.HoverBackgroundLight
                                            isHovered -> MeconColors.HoverBackground.copy(alpha = 0.5f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .hoverable(interactionSource)
                                    .clickable(interactionSource = interactionSource, indication = null) {
                                        onPluginSelected(plugin)
                                        if (isCollapsed) onToggleCollapse()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isActive) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    plugin.displayName,
                                    fontSize = 10.sp,
                                    color = if (isActive) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
                                )
                            }
                        }
                    }
                    if (!isCollapsed) {
                        Spacer(Modifier.weight(1f))
                        val targetDock =
                            if (dock == PianoRollDock.BOTTOM) PianoRollDock.RIGHT else PianoRollDock.BOTTOM
                        val dockInteractionSource = remember { MutableInteractionSource() }
                        val isDockHovered by dockInteractionSource.collectIsHoveredAsState()
                        Icon(
                            imageVector = if (targetDock == PianoRollDock.RIGHT) {
                                Icons.Default.SwapHoriz
                            } else {
                                Icons.Default.SwapVert
                            },
                            contentDescription = if (targetDock == PianoRollDock.RIGHT) {
                                i18n("bottom.pianoRoll.dockRight")
                            } else {
                                i18n("bottom.pianoRoll.dockBottom")
                            },
                            modifier = Modifier
                                .size(14.dp)
                                .hoverable(dockInteractionSource)
                                .clickable(
                                    interactionSource = dockInteractionSource,
                                    indication = null,
                                ) { onDockChange(targetDock) },
                            tint = if (isDockHovered) MeconColors.SelectedIcon else MeconColors.IconDefault,
                        )
                    }
                }

                // Content
                if (!isCollapsed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MeconColors.SurfaceDark)
                    ) {
                        when (activePlugin) {
                            BottomPlugin.PIANO_ROLL -> PianoRollView(
                                runtimeScore = runtimeScore,
                                computedScore = computedScore,
                                selection = selection,
                                interaction = com.mecon.desktop.ui.views.PianoRollInteractionConfig(
                                    onSelectionChange = onSelectionChange,
                                ),
                                showChordOverlay = showChordOverlay,
                                showScaleDegrees = showScaleDegrees,
                                analysisRefreshKey = analysisRefreshKey,
                                currentPositionTicks = currentPositionTicks,
                                playbackState = playbackState,
                            )
                        }
                    }
                }
            }
        }

        if (dock == PianoRollDock.RIGHT) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MeconColors.Border)
            )
        }
        if (!isCollapsed) {
            if (dock == PianoRollDock.BOTTOM) {
                VerticalResizeHandle(
                    onDrag = { delta -> onSizeChange(-delta) },
                    value = size,
                    minValue = MeconDimensions.MinPanelHeight.dp,
                    maxValue = MeconDimensions.MaxPanelHeight.dp,
                    inverted = true,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            } else {
                HorizontalResizeHandle(
                    onDrag = { delta -> onSizeChange(-delta) },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}
