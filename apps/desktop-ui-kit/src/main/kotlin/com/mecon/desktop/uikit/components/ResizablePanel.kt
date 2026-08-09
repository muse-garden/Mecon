package com.mecon.desktop.uikit.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.util.setGlobalCursor
import java.awt.Cursor

private val MAX_PANEL_HEIGHT = 400.dp

/** Main-inspector panel chrome shared by the application and feature workbenches. */
@Composable
fun CollapsiblePanelItem(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    initiallyCollapsed: Boolean = false,
    collapsed: Boolean? = null,
    onCollapsedChange: ((Boolean) -> Unit)? = null,
    contentModifier: Modifier = Modifier.padding(8.dp),
    content: @Composable () -> Unit,
) {
    var internalCollapsed by remember { mutableStateOf(initiallyCollapsed) }
    val isCollapsed = collapsed ?: internalCollapsed
    val changeCollapsed: (Boolean) -> Unit = { next ->
        if (collapsed == null) internalCollapsed = next
        onCollapsedChange?.invoke(next)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MeconColors.Background)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(MeconColors.Border, Offset(0f, strokeWidth / 2), Offset(size.width, strokeWidth / 2), strokeWidth)
                drawLine(
                    MeconColors.Border,
                    Offset(0f, size.height - strokeWidth / 2),
                    Offset(size.width, size.height - strokeWidth / 2),
                    strokeWidth,
                )
            },
    ) {
        CollapsiblePanelHeader(
            title = title,
            icon = icon,
            collapsed = isCollapsed,
            onToggle = { changeCollapsed(!isCollapsed) },
        )
        AnimatedVisibility(visible = !isCollapsed) {
            Box(Modifier.fillMaxWidth().then(contentModifier)) { content() }
        }
    }
}

@Composable
fun CollapsiblePanelHeader(
    title: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                when {
                    pressed -> MeconColors.HoverBackgroundLight
                    hovered -> MeconColors.HoverBackground.copy(alpha = 0.5f)
                    else -> MeconColors.Background
                },
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand" else "Collapse",
            modifier = Modifier.size(12.dp),
            tint = if (hovered || pressed) MeconColors.TextPrimary else MeconColors.TextMuted,
        )
        Spacer(Modifier.width(8.dp))
        if (icon != null) {
            Icon(icon, null, Modifier.size(12.dp), tint = MeconColors.PrimaryLight)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (hovered || pressed) MeconColors.TextPrimary else MeconColors.TextSecondary,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
fun ResizablePanelItem(
    title: String,
    icon: ImageVector? = null,
    initialHeight: Dp = 120.dp,
    minHeight: Dp = 60.dp,
    noPadding: Boolean = false,
    noScroll: Boolean = false,
    wrapContent: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isCollapsed by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(initialHeight) }

    var dragStartHeight by remember { mutableStateOf(initialHeight) }
    var cumulativeDragPx by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MeconColors.Background)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = MeconColors.Border,
                    start = Offset(0f, strokeWidth / 2),
                    end = Offset(size.width, strokeWidth / 2),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = MeconColors.Border,
                    start = Offset(0f, size.height - strokeWidth / 2),
                    end = Offset(size.width, size.height - strokeWidth / 2),
                    strokeWidth = strokeWidth
                )
            }
    ) {
        CollapsiblePanelHeader(
            title = title,
            icon = icon,
            collapsed = isCollapsed,
            onToggle = { isCollapsed = !isCollapsed },
        )

        AnimatedVisibility(visible = !isCollapsed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrapContent) Modifier else Modifier.height(height))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (wrapContent) Modifier else Modifier.fillMaxHeight())
                        .then(
                            if (noScroll || wrapContent) Modifier
                            else Modifier.verticalScroll(rememberScrollState())
                        )
                        .then(
                            if (noPadding) Modifier
                            else Modifier.padding(8.dp)
                        )
                ) {
                    content()
                }

                // Drag-resize handle — hidden in wrap-content mode
                if (!wrapContent) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .align(Alignment.BottomCenter)
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        setGlobalCursor(Cursor(Cursor.S_RESIZE_CURSOR))
                                        dragStartHeight = height
                                        cumulativeDragPx = 0f
                                    },
                                    onDragEnd = { setGlobalCursor(null) },
                                    onDragCancel = { setGlobalCursor(null) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        cumulativeDragPx += dragAmount.y
                                        val virtualHeight = dragStartHeight + cumulativeDragPx.toDp()
                                        height = virtualHeight.coerceIn(minHeight, MAX_PANEL_HEIGHT)
                                    }
                                )
                            }
                            .background(MeconColors.Primary.copy(alpha = 0f))
                    )
                }
            }
        }
    }
}

private enum class DeferredResizeOrientation { HORIZONTAL, VERTICAL }

internal fun deferredResizeValue(
    value: Dp,
    pointerDelta: Dp,
    minValue: Dp,
    maxValue: Dp,
    inverted: Boolean,
): Dp = (value + if (inverted) -pointerDelta else pointerDelta).coerceIn(minValue, maxValue)

@Composable
private fun DeferredResizeHandle(
    orientation: DeferredResizeOrientation,
    value: Dp,
    minValue: Dp,
    maxValue: Dp,
    inverted: Boolean,
    onResizePreview: (Dp?) -> Unit,
    onResizeEnd: (Dp) -> Unit,
    modifier: Modifier,
) {
    val horizontal = orientation == DeferredResizeOrientation.HORIZONTAL
    val cursorType = if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentValue by rememberUpdatedState(value)
    val currentOnResizePreview by rememberUpdatedState(onResizePreview)
    val currentOnResizeEnd by rememberUpdatedState(onResizeEnd)

    Box(
        modifier = modifier
            .then(
                if (horizontal) Modifier.width(10.dp).fillMaxHeight().offset(x = (-5).dp)
                else Modifier.height(10.dp).fillMaxWidth().offset(y = (-5).dp)
            )
            .pointerHoverIcon(PointerIcon(Cursor(cursorType)))
            .pointerInput(inverted, minValue, maxValue) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startValue = currentValue
                    var cumulativePointerPx = 0f
                    var targetValue = startValue
                    var released = false
                    setGlobalCursor(Cursor(cursorType))
                    currentOnResizePreview(startValue)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val pointerDelta = change.position - change.previousPosition
                            cumulativePointerPx += if (horizontal) pointerDelta.x else pointerDelta.y
                            targetValue = deferredResizeValue(
                                startValue,
                                with(density) { cumulativePointerPx.toDp() },
                                minValue,
                                maxValue,
                                inverted,
                            )
                            currentOnResizePreview(targetValue)
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        currentOnResizePreview(null)
                        setGlobalCursor(null)
                    }
                    if (released) currentOnResizeEnd(targetValue)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (horizontal) Modifier.width(2.dp).height(28.dp) else Modifier.height(2.dp).width(28.dp))
                .clip(RoundedCornerShape(1.dp))
                .background(MeconColors.BorderLight),
        )
    }
}

@Composable
fun DeferredHorizontalResizeHandle(
    value: Dp,
    minValue: Dp,
    maxValue: Dp,
    onResizeEnd: (Dp) -> Unit,
    onResizePreview: (Dp?) -> Unit = {},
    inverted: Boolean = false,
    modifier: Modifier = Modifier,
) = DeferredResizeHandle(
    DeferredResizeOrientation.HORIZONTAL,
    value,
    minValue,
    maxValue,
    inverted,
    onResizePreview,
    onResizeEnd,
    modifier,
)

@Composable
fun DeferredVerticalResizeHandle(
    value: Dp,
    minValue: Dp,
    maxValue: Dp,
    onResizeEnd: (Dp) -> Unit,
    onResizePreview: (Dp?) -> Unit = {},
    inverted: Boolean = false,
    modifier: Modifier = Modifier,
) = DeferredResizeHandle(
    DeferredResizeOrientation.VERTICAL,
    value,
    minValue,
    maxValue,
    inverted,
    onResizePreview,
    onResizeEnd,
    modifier,
)

private enum class ResizeOrientation {
    HORIZONTAL,
    VERTICAL
}

@Composable
private fun ResizeHandle(
    orientation: ResizeOrientation,
    onDrag: (Float) -> Unit,
    value: Dp?,
    minValue: Dp?,
    maxValue: Dp?,
    inverted: Boolean,
    modifier: Modifier
) {
    val isBounded = value != null && minValue != null && maxValue != null
    val isHorizontal = orientation == ResizeOrientation.HORIZONTAL

    val cursorType = if (isHorizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR

    val currentValue by rememberUpdatedState(value)
    val currentMinValue by rememberUpdatedState(minValue)
    val currentMaxValue by rememberUpdatedState(maxValue)
    val currentOnDrag by rememberUpdatedState(onDrag)

    var dragStartValue by remember { mutableStateOf(0.dp) }
    var cumulativeDragPx by remember { mutableFloatStateOf(0f) }
    var lastReportedValue by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier
            .then(
                if (isHorizontal) Modifier.width(6.dp).fillMaxHeight().offset(x = (-4).dp)
                else Modifier.height(6.dp).fillMaxWidth().offset(y = (-4).dp)
            )
            .pointerHoverIcon(PointerIcon(Cursor(cursorType)))
            .pointerInput(isBounded, inverted) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    setGlobalCursor(Cursor(cursorType))
                    if (isBounded && currentValue != null) {
                        dragStartValue = currentValue!!
                        lastReportedValue = currentValue!!
                        cumulativeDragPx = 0f
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            val pointerDelta = change.position - change.previousPosition
                            val rawDelta = if (isHorizontal) pointerDelta.x else pointerDelta.y
                            if (rawDelta != 0f) {
                                change.consume()
                                if (isBounded && currentMinValue != null && currentMaxValue != null) {
                                    val dragDelta = if (inverted) -rawDelta else rawDelta
                                    cumulativeDragPx += dragDelta
                                    val virtualValue = dragStartValue + cumulativeDragPx.toDp()
                                    val clampedValue = virtualValue.coerceIn(
                                        currentMinValue!!,
                                        currentMaxValue!!,
                                    )
                                    if (clampedValue != lastReportedValue) {
                                        val effectiveDeltaDp = (clampedValue - lastReportedValue).value
                                        lastReportedValue = clampedValue
                                        currentOnDrag(
                                            if (inverted) -effectiveDeltaDp else effectiveDeltaDp,
                                        )
                                    }
                                } else {
                                    currentOnDrag(rawDelta)
                                }
                            }
                            if (!change.pressed) break
                        }
                    } finally {
                        setGlobalCursor(null)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isHorizontal) Modifier.width(2.dp).height(24.dp).offset(x = (-2).dp)
                    else Modifier.height(2.dp).width(24.dp).offset(y = (-2).dp)
                )
                .clip(RoundedCornerShape(1.dp))
                .background(MeconColors.BorderLight)
        )
    }
}

@Composable
fun HorizontalResizeHandle(
    onDrag: (Float) -> Unit,
    value: Dp? = null,
    minValue: Dp? = null,
    maxValue: Dp? = null,
    inverted: Boolean = false,
    modifier: Modifier = Modifier
) {
    ResizeHandle(
        orientation = ResizeOrientation.HORIZONTAL,
        onDrag = onDrag,
        value = value,
        minValue = minValue,
        maxValue = maxValue,
        inverted = inverted,
        modifier = modifier
    )
}

@Composable
fun VerticalResizeHandle(
    onDrag: (Float) -> Unit,
    value: Dp? = null,
    minValue: Dp? = null,
    maxValue: Dp? = null,
    inverted: Boolean = false,
    modifier: Modifier = Modifier
) {
    ResizeHandle(
        orientation = ResizeOrientation.VERTICAL,
        onDrag = onDrag,
        value = value,
        minValue = minValue,
        maxValue = maxValue,
        inverted = inverted,
        modifier = modifier
    )
}
