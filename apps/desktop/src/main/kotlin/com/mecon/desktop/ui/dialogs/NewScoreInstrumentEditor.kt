package com.mecon.desktop.ui.dialogs

import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.PlayerKind
import com.mecon.api.storage.defaultPlayerAssignments
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.desktop.uikit.components.CompactNumberInput
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import kotlin.math.roundToInt

internal data class EditableGroup(
    val startInstrument: Int,
    val endInstrument: Int,
    val bracket: BracketStyle
)

internal fun List<EditableGroup>.areLaminar(): Boolean {
    if (distinctBy { it.startInstrument to it.endInstrument }.size != size) return false
    return indices.all { left ->
        (left + 1 until size).all { right -> !this[left].crosses(this[right]) }
    }
}

private fun EditableGroup.crosses(other: EditableGroup): Boolean =
    (startInstrument < other.startInstrument && other.startInstrument <= endInstrument && endInstrument < other.endInstrument) ||
        (other.startInstrument < startInstrument && startInstrument <= other.endInstrument && other.endInstrument < endInstrument)

private fun List<EditableGroup>.depthOf(group: EditableGroup): Int = count { other ->
    other !== group && other.startInstrument <= group.startInstrument && other.endInstrument >= group.endInstrument
}

private val INSTRUMENT_ROW_HEIGHT = 48.dp
private val BRACKET_LANE_WIDTH = 24.dp

@Composable
internal fun InstrumentEditor(
    instruments: List<InstrumentTemplate>,
    groups: List<EditableGroup>,
    onInstrumentsChange: (List<InstrumentTemplate>) -> Unit,
    onGroupsChange: (List<EditableGroup>) -> Unit
) {
    var selectedGroup by remember { mutableStateOf(groups.indices.firstOrNull()) }
    var bracketMenuExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(groups.size) {
        selectedGroup = selectedGroup?.takeIf { it in groups.indices }
            ?: groups.indices.firstOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分组（拖拽端点调整范围）", color = MeconColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            selectedGroup?.let { index ->
                Box {
                    TextButton(onClick = { bracketMenuExpanded = true }) {
                        Text(groups.getOrNull(index)?.bracket?.displayName().orEmpty(), fontSize = 11.sp)
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                    }
                    MeconDropdownMenu(
                        expanded = bracketMenuExpanded,
                        onDismissRequest = { bracketMenuExpanded = false },
                    ) {
                        listOf(BracketStyle.SQUARE, BracketStyle.BRACE, BracketStyle.SUB_BRACKET).forEach { style ->
                            MeconDropdownItem(
                                label = style.displayName(),
                                onClick = {
                                    onGroupsChange(groups.toMutableList().also { list ->
                                        list[index] = list[index].copy(bracket = style)
                                    })
                                    bracketMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    onGroupsChange(groups.toMutableList().also { it.removeAt(index) })
                    selectedGroup = null
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, "删除括号", tint = MeconColors.DeleteIcon, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(
                enabled = instruments.isNotEmpty(),
                onClick = {
                    val range = nextBracketRange(instruments.size, groups) ?: return@IconButton
                    onGroupsChange(groups + EditableGroup(range.first, range.last, BracketStyle.SQUARE))
                    selectedGroup = groups.size
                },
                modifier = Modifier.size(28.dp)
            ) { Icon(Icons.Default.Add, "添加括号", tint = MeconColors.PrimaryLight, modifier = Modifier.size(17.dp)) }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val lanes = ((groups.maxOfOrNull { groups.depthOf(it) } ?: 0) + 1).coerceAtLeast(1)
            Spacer(Modifier.width((lanes * 24).dp + 8.dp))
            Row(
                Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("乐器", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
                Text("谱表展示名称", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("谱表数", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(STAFF_COUNT_CONTROL_WIDTH))
                Text("类型", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(PLAYER_KIND_CONTROL_WIDTH))
                Text("人数", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(PLAYER_COUNT_CONTROL_WIDTH))
                Text("谱表分配", color = MeconColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(PLAYER_ASSIGNMENT_CONTROL_WIDTH))
                Spacer(Modifier.width(84.dp))
            }
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
            verticalAlignment = Alignment.Top
        ) {
            BracketLanes(
                instruments = instruments,
                groups = groups,
                selectedGroup = selectedGroup,
                onSelect = { selectedGroup = it },
                onGroupsChange = onGroupsChange
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                instruments.forEachIndexed { index, instrument ->
                    InstrumentRow(
                        item = instrument,
                        canDelete = instruments.size > 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < instruments.lastIndex,
                        onChange = { updated ->
                            onInstrumentsChange(instruments.toMutableList().also { it[index] = updated })
                        },
                        onMoveUp = {
                            onInstrumentsChange(instruments.toMutableList().also {
                                val moved = it.removeAt(index); it.add(index - 1, moved)
                            })
                        },
                        onMoveDown = {
                            onInstrumentsChange(instruments.toMutableList().also {
                                val moved = it.removeAt(index); it.add(index + 1, moved)
                            })
                        },
                        onDelete = {
                            onInstrumentsChange(instruments.toMutableList().also { it.removeAt(index) })
                            onGroupsChange(groups.afterInstrumentRemoved(index))
                        }
                    )
                }
            }
        }

        TextButton(onClick = {
            onInstrumentsChange(instruments + ScoreInstrumentCatalog.template("piano", staffCount = 1))
        }) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加乐器", color = MeconColors.PrimaryLight)
        }
        if (!groups.areLaminar()) {
            Text("括号范围可以嵌套，但不能交叉或完全重合", color = MeconColors.Danger, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BracketLanes(
    instruments: List<InstrumentTemplate>,
    groups: List<EditableGroup>,
    selectedGroup: Int?,
    onSelect: (Int) -> Unit,
    onGroupsChange: (List<EditableGroup>) -> Unit
) {
    val maxDepth = groups.maxOfOrNull { groups.depthOf(it) } ?: 0
    Box(
        Modifier
            .width(((maxDepth + 1).coerceAtLeast(1) * 24).dp)
            .height(INSTRUMENT_ROW_HEIGHT * instruments.size)
    ) {
        groups.forEachIndexed { index, group ->
            val depth = groups.depthOf(group)
            val selected = index == selectedGroup
            val color = if (selected) MeconColors.BracketSelected else MeconColors.BracketDefault
            val height = INSTRUMENT_ROW_HEIGHT * (group.endInstrument - group.startInstrument + 1)
            Box(
                Modifier
                    .offset(x = BRACKET_LANE_WIDTH * depth, y = INSTRUMENT_ROW_HEIGHT * group.startInstrument)
                    .width(BRACKET_LANE_WIDTH)
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSelect(index) }
            ) {
                Canvas(Modifier.fillMaxSize().padding(vertical = 8.dp, horizontal = 5.dp)) {
                    val x = size.width * 0.62f
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (selected) 2.5f else 1.6f, cap = StrokeCap.Round)
                    drawLine(color, Offset(x, 0f), Offset(size.width, 0f), strokeWidth = if (selected) 2.5f else 1.6f)
                    drawLine(color, Offset(x, size.height), Offset(size.width, size.height), strokeWidth = if (selected) 2.5f else 1.6f)
                }
                if (selected) {
                    BracketHandle(Alignment.TopCenter) { delta ->
                        updateGroupEndpoint(index, delta, true, instruments.lastIndex, groups, onGroupsChange)
                    }
                    BracketHandle(Alignment.BottomCenter) { delta ->
                        updateGroupEndpoint(index, delta, false, instruments.lastIndex, groups, onGroupsChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.BracketHandle(alignment: Alignment, onRowDelta: (Int) -> Unit) {
    var dragY by remember { mutableStateOf(0f) }
    Box(
        Modifier.align(alignment)
            .size(10.dp)
            .background(MeconColors.BracketHandle, CircleShape)
            .border(1.dp, MeconColors.SurfaceDark, CircleShape)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onDragEnd = { dragY = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragY += amount
                        val rows = (dragY / INSTRUMENT_ROW_HEIGHT.toPx()).roundToInt()
                        if (rows != 0) {
                            onRowDelta(rows)
                            dragY -= rows * INSTRUMENT_ROW_HEIGHT.toPx()
                        }
                    }
                )
            }
    )
}

private fun updateGroupEndpoint(
    index: Int,
    delta: Int,
    start: Boolean,
    lastInstrument: Int,
    groups: List<EditableGroup>,
    onGroupsChange: (List<EditableGroup>) -> Unit
) {
    val group = groups.getOrNull(index) ?: return
    val updated = if (start) {
        group.copy(startInstrument = (group.startInstrument + delta).coerceIn(0, group.endInstrument))
    } else {
        group.copy(endInstrument = (group.endInstrument + delta).coerceIn(group.startInstrument, lastInstrument))
    }
    val candidate = groups.toMutableList().also { it[index] = updated }
    if (candidate.areLaminar()) onGroupsChange(candidate)
}

@Composable
private fun InstrumentRow(
    item: InstrumentTemplate,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (InstrumentTemplate) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(INSTRUMENT_ROW_HEIGHT)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(MeconColors.Border, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        InstrumentDropdown(item, Modifier.weight(0.9f), onChange)
        CompactTextField(
            value = item.name,
            onValueChange = { name ->
                onChange(item.copy(
                    name = name,
                    staves = item.staves.mapIndexed { index, staff ->
                        staff.copy(name = if (item.staves.size == 1) name else "$name ${index + 1}")
                    }
                ))
            },
            modifier = Modifier.weight(1f)
        )
        CompactNumberInput(
            value = item.staves.size,
            range = 1..8,
            onValueChange = { onChange(item.withStaffCount(it)) },
            modifier = Modifier.width(STAFF_COUNT_CONTROL_WIDTH),
        )
        PlayerSetupControls(
            kind = item.playerKind,
            playerCount = item.playerCount,
            staffNames = item.staves.map { it.name },
            assignments = item.playerAssignments,
            interleavedDefault = item.catalogId == "horn",
            onChange = { kind, count, assignments ->
                onChange(item.copy(
                    playerKind = kind,
                    playerCount = count,
                    playerAssignments = assignments,
                ))
            },
        )
        IconButton(enabled = canMoveUp, onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.ArrowUpward, null, tint = if (canMoveUp) MeconColors.IconDefault else MeconColors.TextDark, modifier = Modifier.size(14.dp))
        }
        IconButton(enabled = canMoveDown, onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.ArrowDownward, null, tint = if (canMoveDown) MeconColors.IconDefault else MeconColors.TextDark, modifier = Modifier.size(14.dp))
        }
        IconButton(enabled = canDelete, onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Delete, null, tint = if (canDelete) MeconColors.DeleteIcon else MeconColors.TextDark, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun InstrumentDropdown(
    item: InstrumentTemplate,
    modifier: Modifier,
    onChange: (InstrumentTemplate) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val expandedCategories = remember {
        mutableStateMapOf<InstrumentCategory, Boolean>().apply {
            InstrumentCategory.entries.forEach { put(it, true) }
        }
    }
    val selected = ScoreInstrumentCatalog.byId(item.catalogId)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(34.dp)
                .border(1.dp, MeconColors.BorderLight, RoundedCornerShape(5.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected?.localizedLabel() ?: item.name, color = MeconColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null, tint = MeconColors.IconDefault, modifier = Modifier.size(18.dp))
        }
        MeconDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MeconColors.DialogBackground,
            modifier = Modifier.width(330.dp).heightIn(max = 480.dp)
        ) {
            CompactSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
            val normalized = query.trim()
            InstrumentCategory.entries.forEach { category ->
                val matches = ScoreInstrumentCatalog.all.filter { definition ->
                    definition.category == category && (
                        normalized.isBlank() || definition.name.contains(normalized, ignoreCase = true) ||
                            i18n(definition.nameKey).contains(normalized, ignoreCase = true)
                        )
                }
                if (matches.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(i18n(category.labelKey), color = MeconColors.TextSecondary, fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                if (expandedCategories[category] != false || normalized.isNotBlank()) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                null, tint = MeconColors.IconDefault, modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = { expandedCategories[category] = expandedCategories[category] == false }
                    )
                    if (expandedCategories[category] != false || normalized.isNotBlank()) {
                        matches.forEach { definition ->
                            DropdownMenuItem(
                                text = { Text(definition.localizedLabel(), color = MeconColors.TextPrimary, fontSize = 12.sp) },
                                contentPadding = PaddingValues(start = 38.dp, end = 12.dp),
                                onClick = {
                                    val replacement = definition.template(displayName = item.name, staffCount = item.staves.size)
                                    onChange(replacement)
                                    expanded = false
                                    query = ""
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = MeconColors.TextPrimary, fontSize = 12.sp),
        cursorBrush = SolidColor(MeconColors.Primary),
        modifier = modifier.height(34.dp)
            .border(1.dp, MeconColors.BorderLight, RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
            .meconTextInputFocus()
    )
}

@Composable
private fun CompactSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.height(34.dp).border(1.dp, MeconColors.BorderLight, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = MeconColors.TextMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MeconColors.TextPrimary, fontSize = 12.sp),
            cursorBrush = SolidColor(MeconColors.Primary),
            modifier = Modifier.weight(1f).meconTextInputFocus()
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, null, tint = MeconColors.IconDefault,
                modifier = Modifier.size(15.dp).clickable { onValueChange("") }
            )
        }
    }
}

private fun ScoreInstrumentDefinition.localizedLabel(): String {
    val localized = i18n(nameKey)
    return if (localized.equals(name, ignoreCase = true)) name else "$localized · $name"
}

private fun InstrumentTemplate.withStaffCount(count: Int): InstrumentTemplate {
    val definition = ScoreInstrumentCatalog.byId(catalogId)
    val newStaves = List(count.coerceAtLeast(1)) { index ->
        val clef = staves.getOrNull(index)?.clef
            ?: definition?.defaultStaves?.getOrNull(index)?.clef
            ?: staves.last().clef
        StaffTemplate(if (count == 1) name else "$name ${index + 1}", clef)
    }
    return copy(
        staves = newStaves,
        playerAssignments = if (playerKind == PlayerKind.SINGLE && playerCount > 1) {
            defaultPlayerAssignments(
                newStaves.size,
                playerCount,
                interleaved = catalogId == "horn",
            )
        } else {
            emptyList()
        },
    )
}

private fun List<EditableGroup>.afterInstrumentRemoved(index: Int): List<EditableGroup> = mapNotNull { group ->
    when {
        index < group.startInstrument -> group.copy(
            startInstrument = group.startInstrument - 1,
            endInstrument = group.endInstrument - 1
        )
        index > group.endInstrument -> group
        group.startInstrument == group.endInstrument -> null
        else -> group.copy(endInstrument = group.endInstrument - 1)
    }
}.distinctBy { it.startInstrument to it.endInstrument }

private fun nextBracketRange(instrumentCount: Int, groups: List<EditableGroup>): IntRange? {
    if (instrumentCount <= 0) return null
    val all = 0 until instrumentCount
    if (groups.none { it.startInstrument == all.first && it.endInstrument == all.last }) return all
    return (0 until instrumentCount).firstNotNullOfOrNull { index ->
        EditableGroup(index, index, BracketStyle.SQUARE).takeIf { candidate ->
            groups.none { it.startInstrument == index && it.endInstrument == index } &&
                (groups + candidate).areLaminar()
        }?.let { index..index }
    }
}

private fun BracketStyle.displayName(): String = when (this) {
    BracketStyle.SQUARE -> "方括号"
    BracketStyle.BRACE -> "大括号"
    BracketStyle.SUB_BRACKET -> "子括号"
    BracketStyle.NONE -> "无"
}
