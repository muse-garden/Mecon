package com.mecon.desktop.ui.dialogs

import com.mecon.desktop.uikit.components.CompactDropdownField
import com.mecon.desktop.uikit.components.CompactNumberInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mecon.api.storage.PlayerKind
import com.mecon.api.storage.defaultPlayerAssignments
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import com.mecon.desktop.uikit.theme.MeconColors
import kotlin.math.roundToInt

internal val STAFF_COUNT_CONTROL_WIDTH = 82.dp
internal val PLAYER_KIND_CONTROL_WIDTH = 86.dp
internal val PLAYER_COUNT_CONTROL_WIDTH = 82.dp
internal val PLAYER_ASSIGNMENT_CONTROL_WIDTH = 112.dp

@Composable
internal fun PlayerSetupControls(
    kind: PlayerKind,
    playerCount: Int,
    staffNames: List<String>,
    assignments: List<List<Int>>,
    interleavedDefault: Boolean,
    onChange: (PlayerKind, Int, List<List<Int>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = assignments.takeIf { it.size == staffNames.size }
        ?: defaultPlayerAssignments(staffNames.size, playerCount, interleavedDefault)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompactDropdownField(
            value = kind,
            label = { if (it == PlayerKind.SECTION) "合奏" else "独奏" },
            options = PlayerKind.entries,
            onSelected = { selected ->
                val count = if (selected == PlayerKind.SECTION) 1 else playerCount.coerceAtLeast(1)
                val next = if (selected == PlayerKind.SECTION) {
                    List(staffNames.size) { listOf(1) }
                } else {
                    defaultPlayerAssignments(staffNames.size, count, interleavedDefault)
                }
                onChange(selected, count, next)
            },
            modifier = Modifier.width(PLAYER_KIND_CONTROL_WIDTH),
        )
        CompactNumberInput(
            value = if (kind == PlayerKind.SECTION) 1 else playerCount,
            range = if (kind == PlayerKind.SECTION) 1..1 else 1..32,
            onValueChange = { count ->
                onChange(
                    kind,
                    count,
                    defaultPlayerAssignments(staffNames.size, count, interleavedDefault),
                )
            },
            modifier = Modifier.width(PLAYER_COUNT_CONTROL_WIDTH),
        )
        PlayerAssignmentButton(
            kind = kind,
            playerCount = playerCount,
            staffNames = staffNames,
            assignments = resolved,
            onAssignmentsChange = { onChange(kind, playerCount, it) },
            modifier = Modifier.width(PLAYER_ASSIGNMENT_CONTROL_WIDTH),
        )
    }
}

@Composable
private fun PlayerAssignmentButton(
    kind: PlayerKind,
    playerCount: Int,
    staffNames: List<String>,
    assignments: List<List<Int>>,
    onAssignmentsChange: (List<List<Int>>) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = if (kind == PlayerKind.SECTION) {
        "整组"
    } else {
        assignments.joinToString(" / ") { it.joinToString(",") }
    }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .border(1.dp, MeconColors.BorderLight, RoundedCornerShape(6.dp))
                .background(MeconColors.Surface, RoundedCornerShape(6.dp))
                .clickable(enabled = kind == PlayerKind.SINGLE) { expanded = true }
                .padding(start = 8.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, color = MeconColors.TextPrimary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (kind == PlayerKind.SINGLE) {
                Icon(Icons.Default.ArrowDropDown, null, tint = MeconColors.IconDefault, modifier = Modifier.size(18.dp))
            }
        }
        MeconDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(380.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("拖动演奏者编号到目标谱表", color = MeconColors.TextSecondary, fontSize = 11.sp)
                PlayerAssignmentLanes(
                    playerCount = playerCount,
                    staffNames = staffNames,
                    assignments = assignments,
                    onAssignmentsChange = onAssignmentsChange,
                )
            }
        }
    }
}

@Composable
private fun PlayerAssignmentLanes(
    playerCount: Int,
    staffNames: List<String>,
    assignments: List<List<Int>>,
    onAssignmentsChange: (List<List<Int>>) -> Unit,
) {
    val rowHeight = 40.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableStateOf<DraggedPlayer?>(null) }
    val normalized = List(staffNames.size) { index ->
        assignments.getOrNull(index).orEmpty().filter { it in 1..playerCount }.distinct()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        staffNames.forEachIndexed { staffIndex, staffName ->
            val target = dragging?.targetStaff == staffIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(
                        if (target) {
                            MeconColors.Primary.copy(alpha = 0.10f)
                        } else {
                            MeconColors.DialogBackground
                        },
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        1.dp,
                        if (target) MeconColors.Primary.copy(alpha = 0.55f) else MeconColors.BorderLight,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(staffName, color = MeconColors.TextPrimary, fontSize = 11.sp, modifier = Modifier.width(135.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    normalized[staffIndex].forEach { player ->
                        val active = dragging?.player == player && dragging?.sourceStaff == staffIndex
                        PlayerChip(
                            number = player,
                            translationY = if (active) dragging?.deltaY ?: 0f else 0f,
                            active = active,
                            onDragStart = {
                                dragging = DraggedPlayer(player, staffIndex, staffIndex, 0f)
                            },
                            onDrag = { deltaPx ->
                                dragging?.let { current ->
                                    val nextDelta = current.deltaY + deltaPx
                                    val targetIndex = (staffIndex + (nextDelta / rowHeightPx).roundToInt())
                                        .coerceIn(staffNames.indices)
                                    dragging = current.copy(targetStaff = targetIndex, deltaY = nextDelta)
                                }
                            },
                            onDragEnd = {
                                dragging?.let { drag ->
                                    onAssignmentsChange(
                                        movePlayer(normalized, drag.player, drag.sourceStaff, drag.targetStaff)
                                    )
                                }
                                dragging = null
                            },
                        )
                    }
                    if (normalized[staffIndex].isEmpty()) {
                        Text("拖到这里", color = MeconColors.TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private data class DraggedPlayer(
    val player: Int,
    val sourceStaff: Int,
    val targetStaff: Int,
    val deltaY: Float,
)

@Composable
private fun PlayerChip(
    number: Int,
    translationY: Float,
    active: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        color = MeconColors.SelectedSurface,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier
            .zIndex(if (active) 2f else 0f)
            .graphicsLayer { this.translationY = translationY }
            .shadow(if (active) 5.dp else 0.dp, RoundedCornerShape(5.dp))
            .pointerInput(number) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                )
            },
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DragIndicator, null, tint = MeconColors.IconDefault, modifier = Modifier.size(13.dp))
            Text(number.toString(), color = MeconColors.TextPrimary, fontSize = 11.sp)
        }
    }
}

private fun movePlayer(
    assignments: List<List<Int>>,
    player: Int,
    source: Int,
    target: Int,
): List<List<Int>> {
    if (source == target || source !in assignments.indices || target !in assignments.indices) return assignments
    val next = assignments.map { it.toMutableList() }.toMutableList()
    if (player !in next[source] || player in next[target]) return assignments
    next[source].remove(player)
    if (next[source].isEmpty()) {
        val swap = next[target].firstOrNull() ?: return assignments
        next[target].remove(swap)
        next[source].add(swap)
    }
    next[target].add(player)
    return next.map { it.distinct().sorted() }
}
