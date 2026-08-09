package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mecon.api.primitive.InstrumentId
import com.mecon.api.storage.*
import com.mecon.api.storage.tracks.StorageInstrument
import com.mecon.desktop.service.OrchestrationInstrumentDraft
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun OrchestrationSettingsDialog(
    score: StorageScore,
    onApply: (List<OrchestrationInstrumentDraft>) -> Unit,
    onDismiss: () -> Unit,
) {
    val orchestration = score.orchestration ?: OrchestrationEngineSnapshot.fromScore(score)
    var drafts by remember(score) {
        mutableStateOf(score.instruments.map { instrument -> draftFor(score, orchestration, instrument) })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(680.dp).heightIn(max = 620.dp),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.DialogBackground,
            tonalElevation = 10.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("配器与演奏者", style = MaterialTheme.typography.titleLarge, color = MeconColors.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = MeconColors.IconDefault) }
                }
                Text(
                    "这里是总谱演奏者与谱表的长期调整入口。设置独奏/合奏与人数后，展开谱表分配并拖动演奏者编号换行。",
                    color = MeconColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("乐器", color = MeconColors.TextMuted, modifier = Modifier.width(150.dp))
                    Text("类型", color = MeconColors.TextMuted, modifier = Modifier.width(PLAYER_KIND_CONTROL_WIDTH + 6.dp))
                    Text("人数", color = MeconColors.TextMuted, modifier = Modifier.width(PLAYER_COUNT_CONTROL_WIDTH + 6.dp))
                    Text("谱表分配", color = MeconColors.TextMuted)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    drafts.forEachIndexed { index, draft ->
                        OrchestrationRow(draft) { next ->
                            drafts = drafts.toMutableList().also { it[index] = next }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onApply(drafts.map { draft ->
                                OrchestrationInstrumentDraft(
                                    instrumentId = draft.instrumentId,
                                    kind = draft.kind,
                                    playerCount = draft.playerCount,
                                    playerAssignments = draft.playerAssignments,
                                )
                            })
                            onDismiss()
                        }
                    ) { Text("应用") }
                }
            }
        }
    }
}

@Composable
private fun OrchestrationRow(
    draft: OrchestrationInstrumentDraftUi,
    onChange: (OrchestrationInstrumentDraftUi) -> Unit,
) {
    Surface(
        color = MeconColors.Surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(draft.name, color = MeconColors.TextPrimary, modifier = Modifier.width(132.dp).padding(top = 7.dp))
            PlayerSetupControls(
                kind = draft.kind,
                playerCount = draft.playerCount,
                staffNames = draft.staffs.map { it.second },
                assignments = draft.playerAssignments,
                interleavedDefault = draft.catalogId == "horn",
                onChange = { kind, count, assignments ->
                    onChange(draft.copy(
                        kind = kind,
                        playerCount = count,
                        playerAssignments = assignments,
                    ))
                },
            )
        }
    }
}

private data class OrchestrationInstrumentDraftUi(
    val instrumentId: InstrumentId,
    val catalogId: String?,
    val name: String,
    val staffs: List<Pair<com.mecon.api.primitive.TrackId, String>>,
    val kind: PlayerKind,
    val playerCount: Int,
    val playerAssignments: List<List<Int>>,
)

private fun draftFor(
    score: StorageScore,
    orchestration: StorageOrchestration,
    instrument: StorageInstrument,
): OrchestrationInstrumentDraftUi {
    val players = orchestration.players.filter { player ->
        player.instruments.any { it.id == instrument.id }
    }
    val kind = players.firstOrNull()?.kind ?: PlayerKind.SINGLE
    val count = if (kind == PlayerKind.SECTION) 1 else players.size.coerceAtLeast(1)
    val numberById = players.mapIndexed { index, player -> player.id to index + 1 }.toMap()
    val fallbackGroups = if (kind == PlayerKind.SECTION) {
        List(instrument.staffIds.size) { listOf(1) }
    } else {
        defaultPlayerAssignments(
            instrument.staffIds.size,
            count,
            interleaved = instrument.catalogId == "horn",
        )
    }
    val groups = instrument.staffIds.mapIndexed { staffIndex, staffId ->
        orchestration.staffAssignments
            .filter {
                it.staffId == staffId &&
                    it.lineId == null &&
                    it.onset == com.mecon.api.primitive.TimeCode.ofMeasure(1)
            }
            .mapNotNull { numberById[it.playerId] }
            .ifEmpty { fallbackGroups[staffIndex] }
    }
    val staffs = instrument.staffIds.map { staffId -> staffId to (score.staffTracks[staffId]?.name ?: "谱表") }
    return OrchestrationInstrumentDraftUi(
        instrument.id,
        instrument.catalogId,
        instrument.name,
        staffs,
        kind,
        count,
        groups,
    )
}

/** Tiny local snapshot adapter to avoid making the dialog responsible for the migration policy. */
private object OrchestrationEngineSnapshot {
    fun fromScore(score: StorageScore): StorageOrchestration = StorageOrchestration(
        players = score.instruments.map { instrument ->
            StoragePlayer(
                id = com.mecon.api.primitive.PlayerId.generate(),
                name = instrument.name,
                instruments = listOf(StoragePlayerInstrument(instrument.id, instrument.name, instrument.abbreviation, playback = instrument.playback)),
            )
        },
    )
}
