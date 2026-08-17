package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.PracticeHarmonicRole
import com.mecon.exploration.PracticeNoteheadRef

@Composable
internal fun PracticeNotePropertiesPanel(
    host: HarmonyPracticeScoreHost,
    selection: Set<EventSection>,
    modifier: Modifier = Modifier,
) {
    val selectedEvents = selection.mapNotNull { section ->
        when (section) {
            is VoiceNoteSection -> section.event
            is VoiceEventSection -> section.event
            else -> null
        }
    }
    val selectedNoteheads = selection.flatMapTo(linkedSetOf()) { section ->
        when (section) {
            is VoiceNoteSection -> listOf(PracticeNoteheadRef(section.event.id, section.pitchIndex))
            is VoiceEventSection -> section.event.pitchData.indices.map { index ->
                PracticeNoteheadRef(section.event.id, index)
            }
            else -> emptyList()
        }
    }
    val selectedVoiceIds = selectedEvents.mapNotNullTo(linkedSetOf()) { event ->
        event.originVoiceTrackId ?: host.runtimeScore.voiceTracks.entries
            .firstOrNull { (_, voice) -> voice.events.any { it.id == event.id } }?.key
    }
    val staffByVoice = host.runtimeScore.staffTracks.entries
        .flatMap { (staffId, staff) -> staff.voiceTracks.map { it.id to staffId } }
        .toMap()
    val selectedStaffIds = selectedVoiceIds.mapNotNullTo(linkedSetOf()) { staffByVoice[it] }
    val constraints = host.practiceNoteConstraints
    val selectedViewsByRef = constraints.noteheads
        .filter { it.notehead in selectedNoteheads }
        .associateBy { it.notehead }
    val selectedViews = selectedViewsByRef.values
    val explicitRoles = selectedViews.map { it.explicitRole }
    val selectedExplicitRole = when {
        selectedNoteheads.isEmpty() || selectedViews.size != selectedNoteheads.size -> null
        explicitRoles.all { it == null } -> ExplicitRoleSelection.Unmarked
        explicitRoles.all { it == PracticeHarmonicRole.CHORD_TONE } -> ExplicitRoleSelection.ChordTone
        explicitRoles.all { it == PracticeHarmonicRole.NON_CHORD_TONE } -> ExplicitRoleSelection.NonChordTone
        else -> null
    }
    val noteLockState = uniformLockState(
        selectedNoteheads.map { ref -> selectedViewsByRef[ref]?.locked == true },
    )
    val voiceLockState = uniformLockState(selectedVoiceIds.map { it in constraints.lockedVoiceTrackIds })
    val staffLockState = uniformLockState(selectedStaffIds.map { it in constraints.lockedStaffTrackIds })

    WorkbenchPanel("音符属性", modifier) {
        Text(
            if (selectedNoteheads.isEmpty()) "选择一个或多个符头以编辑属性"
            else "已选择 ${selectedNoteheads.size} 个符头",
            color = MeconColors.TextMuted,
            fontSize = 10.sp,
        )
        PropertyGroup("和弦内外音") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RoleButton(
                    label = "无标记",
                    selected = selectedExplicitRole == ExplicitRoleSelection.Unmarked,
                    enabled = selectedNoteheads.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { host.setHarmonicRole(selectedNoteheads, null) }
                RoleButton(
                    label = "和弦内音",
                    selected = selectedExplicitRole == ExplicitRoleSelection.ChordTone,
                    enabled = selectedNoteheads.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.CHORD_TONE) }
                RoleButton(
                    label = "和弦外音",
                    selected = selectedExplicitRole == ExplicitRoleSelection.NonChordTone,
                    enabled = selectedNoteheads.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.NON_CHORD_TONE) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactToggleButton(
                    label = "筛选和弦",
                    selected = constraints.chordCatalogFilterEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    host.setHarmonicRoleFilters(
                        !constraints.chordCatalogFilterEnabled,
                        constraints.idiomCatalogFilterEnabled,
                    )
                }
                CompactToggleButton(
                    label = "筛选惯用进行",
                    selected = constraints.idiomCatalogFilterEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    host.setHarmonicRoleFilters(
                        constraints.chordCatalogFilterEnabled,
                        !constraints.idiomCatalogFilterEnabled,
                    )
                }
            }
        }
        HorizontalDivider(color = MeconColors.Border)
        PropertyGroup("锁定情况") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LockScopeCard(
                    label = "音符",
                    state = noteLockState,
                    enabled = selectedNoteheads.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onLock = { host.setNoteheadLock(selectedNoteheads, true) },
                    onUnlock = { host.setNoteheadLock(selectedNoteheads, false) },
                )
                LockScopeCard(
                    label = "声部",
                    state = voiceLockState,
                    enabled = selectedVoiceIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onLock = { selectedVoiceIds.forEach { host.setVoiceLock(it, true) } },
                    onUnlock = { selectedVoiceIds.forEach { host.setVoiceLock(it, false) } },
                )
                LockScopeCard(
                    label = "谱表",
                    state = staffLockState,
                    enabled = selectedStaffIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onLock = { selectedStaffIds.forEach { host.setStaffLock(it, true) } },
                    onUnlock = { selectedStaffIds.forEach { host.setStaffLock(it, false) } },
                )
            }
            Text("锁定音符以符头中央圆点标记", color = MeconColors.TextMuted, fontSize = 10.sp)
        }
    }
}

private enum class ExplicitRoleSelection { Unmarked, ChordTone, NonChordTone }
private enum class UniformLockState { Locked, Unlocked, Mixed }

private fun uniformLockState(values: Collection<Boolean>): UniformLockState = when {
    values.isEmpty() -> UniformLockState.Mixed
    values.all { it } -> UniformLockState.Locked
    values.none { it } -> UniformLockState.Unlocked
    else -> UniformLockState.Mixed
}

@Composable
private fun PropertyGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            title,
            color = MeconColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun RoleButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = modifier.height(32.dp),
        enabled = enabled,
        onClick = onClick,
        border = BorderStroke(1.dp, if (selected) MeconColors.PrimaryLight else MeconColors.BorderLight),
        colors = compactButtonColors(selected),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun CompactToggleButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = modifier.height(32.dp),
        onClick = onClick,
        border = BorderStroke(1.dp, if (selected) MeconColors.PrimaryLight else MeconColors.BorderLight),
        colors = compactButtonColors(selected),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun compactButtonColors(selected: Boolean) = ButtonDefaults.outlinedButtonColors(
    containerColor = if (selected) MeconColors.Selection else Color.Transparent,
    contentColor = if (selected) MeconColors.PrimaryLight else MeconColors.TextPrimary,
    disabledContentColor = MeconColors.TextMuted,
)

@Composable
private fun LockScopeCard(
    label: String,
    state: UniformLockState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MeconColors.InputBackground,
        border = BorderStroke(1.dp, MeconColors.Border),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    ) {
        BoxWithConstraints {
            if (maxWidth >= 96.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LockScopeLabel(label)
                    LockScopeActions(label, state, enabled, onLock, onUnlock)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LockScopeLabel(label)
                    LockScopeActions(label, state, enabled, onLock, onUnlock)
                }
            }
        }
    }
}

@Composable
private fun LockScopeLabel(label: String) {
    Text(label, color = MeconColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun LockScopeActions(
    label: String,
    state: UniformLockState,
    enabled: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        LockIconButton(
            locked = true,
            highlighted = state == UniformLockState.Locked,
            enabled = enabled,
            contentDescription = "锁定$label",
            onClick = onLock,
        )
        LockIconButton(
            locked = false,
            highlighted = state == UniformLockState.Unlocked,
            enabled = enabled,
            contentDescription = "解锁$label",
            onClick = onUnlock,
        )
    }
}

@Composable
private fun LockIconButton(
    locked: Boolean,
    highlighted: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = when {
                !enabled -> MeconColors.TextDark
                highlighted -> MeconColors.PrimaryLight
                else -> MeconColors.IconDefault
            },
        )
    }
}
