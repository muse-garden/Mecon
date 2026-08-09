package com.mecon.desktop.ui.dialogs

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mecon.api.storage.tracks.Clef
import com.mecon.desktop.uikit.components.CompactDropdownField
import com.mecon.desktop.uikit.components.CompactNumberInput
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun NewReductionDialog(
    onCreate: (title: String, clefs: List<Clef>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("缩谱") }
    var staffCount by remember { mutableStateOf(2) }
    var clefs by remember { mutableStateOf(listOf(Clef.TREBLE, Clef.BASS)) }

    fun setStaffCount(count: Int) {
        val next = count.coerceIn(1, 8)
        clefs = List(next) { clefs.getOrNull(it) ?: if (it == 0) Clef.TREBLE else Clef.BASS }
        staffCount = next
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(440.dp),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.DialogBackground,
            tonalElevation = 10.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新建缩谱", style = MaterialTheme.typography.titleLarge, color = MeconColors.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = MeconColors.IconDefault)
                    }
                }
                Text(
                    "创建共享主谱时间轴的分层工作区：曲式、和声、骨架、缩谱记谱与配器。当前谱表成为可直接编辑的记谱层。",
                    color = MeconColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().meconTextInputFocus(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("谱表行数", color = MeconColors.TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    CompactNumberInput(
                        value = staffCount,
                        range = 1..8,
                        onValueChange = ::setStaffCount,
                        modifier = Modifier.width(STAFF_COUNT_CONTROL_WIDTH),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clefs.forEachIndexed { index, clef ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${index + 1} 行", color = MeconColors.TextPrimary, modifier = Modifier.width(82.dp))
                            CompactDropdownField(
                                value = clef,
                                label = ::clefLabel,
                                options = Clef.entries,
                                onSelected = { option ->
                                    clefs = clefs.toMutableList().also { it[index] = option }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(title.ifBlank { "缩谱" }, clefs) },
                    ) { Text("创建") }
                }
            }
        }
    }
}

private fun clefLabel(clef: Clef): String = when (clef) {
    Clef.TREBLE -> "高音谱号"
    Clef.BASS -> "低音谱号"
    Clef.ALTO -> "中音谱号"
    Clef.TENOR -> "次中音谱号"
    Clef.PERCUSSION -> "打击乐谱号"
}
