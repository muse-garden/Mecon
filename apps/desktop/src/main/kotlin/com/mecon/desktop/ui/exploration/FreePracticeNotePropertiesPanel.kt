package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val selectedEventId = selectedEvents.firstOrNull()?.id
    val selectedVoiceId = selectedEvents.firstOrNull()?.originVoiceTrackId
        ?: host.runtimeScore.voiceTracks.entries
            .firstOrNull { (_, voice) -> voice.events.any { it.id == selectedEventId } }?.key
    val selectedStaffId = host.runtimeScore.staffTracks.entries
        .firstOrNull { (_, staff) -> staff.voiceTracks.any { it.id == selectedVoiceId } }?.key
    val constraints = host.practiceNoteConstraints
    val selectedViews = constraints.noteheads.filter { it.notehead in selectedNoteheads }
    val resolvedRoles = selectedViews.mapNotNull { it.explicitRole ?: it.inferredRole }.toSet()
    val explicitRoles = selectedViews.mapNotNull { it.explicitRole }.toSet()
    val lockedCount = selectedViews.count { it.locked }
    val selectedNotesLocked = selectedNoteheads.isNotEmpty() && lockedCount == selectedNoteheads.size
    val roleStatus = when {
        selectedNoteheads.isEmpty() -> "未选择音符"
        selectedViews.any { it.conflict } -> "当前状态：存在冲突"
        explicitRoles.size == 1 -> when (explicitRoles.single()) {
            PracticeHarmonicRole.CHORD_TONE -> "当前状态：已标记为和弦内音"
            PracticeHarmonicRole.NON_CHORD_TONE -> "当前状态：已标记为和弦外音"
        }
        explicitRoles.size > 1 -> "当前状态：显式标记混合"
        resolvedRoles.size == 1 -> when (resolvedRoles.single()) {
            PracticeHarmonicRole.CHORD_TONE -> "当前状态：推断为和弦内音"
            PracticeHarmonicRole.NON_CHORD_TONE -> "当前状态：推断为和弦外音"
        }
        resolvedRoles.isEmpty() -> "当前状态：未判定"
        else -> "当前状态：内外音混合"
    }
    val lockStatus = when {
        selectedNoteheads.isEmpty() -> "当前音符：—"
        lockedCount == 0 -> "当前音符：未锁定"
        selectedNotesLocked -> "当前音符：已锁定"
        else -> "当前音符：已锁定 $lockedCount / ${selectedNoteheads.size}"
    }

    WorkbenchPanel("音符属性", modifier) {
        Text(
            if (selectedNoteheads.isEmpty()) "选择一个或多个符头以编辑属性"
            else "已选择 ${selectedNoteheads.size} 个符头",
            color = MeconColors.TextMuted,
            fontSize = 10.sp,
        )
        PropertyGroup("和弦内外音") {
            Text(roleStatus, color = MeconColors.TextDark, fontSize = 11.sp)
            PropertyButton("标记为和弦内音", selectedNoteheads.isNotEmpty()) {
                host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.CHORD_TONE)
            }
            PropertyButton("标记为和弦外音", selectedNoteheads.isNotEmpty()) {
                host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.NON_CHORD_TONE)
            }
            PropertyButton("清除内外音标记", selectedNoteheads.isNotEmpty()) {
                host.setHarmonicRole(selectedNoteheads, null)
            }
            PropertyButton(if (constraints.chordCatalogFilterEnabled) "和弦筛选：开" else "和弦筛选：关") {
                host.setHarmonicRoleFilters(
                    !constraints.chordCatalogFilterEnabled,
                    constraints.idiomCatalogFilterEnabled,
                )
            }
            PropertyButton(if (constraints.idiomCatalogFilterEnabled) "进行筛选：开" else "进行筛选：关") {
                host.setHarmonicRoleFilters(
                    constraints.chordCatalogFilterEnabled,
                    !constraints.idiomCatalogFilterEnabled,
                )
            }
        }
        HorizontalDivider(color = MeconColors.Border)
        PropertyGroup("锁定情况") {
            Text(lockStatus, color = MeconColors.TextDark, fontSize = 11.sp)
            Text("锁定音符以符头中央圆点标记", color = MeconColors.TextMuted, fontSize = 10.sp)
            PropertyButton(
                if (selectedNotesLocked) "解锁音符" else "锁定音符",
                selectedNoteheads.isNotEmpty(),
            ) {
                host.setNoteheadLock(selectedNoteheads, !selectedNotesLocked)
            }
            PropertyButton(
                if (selectedVoiceId in constraints.lockedVoiceTrackIds) "解锁声部" else "锁定声部",
                selectedVoiceId != null,
            ) {
                selectedVoiceId?.let { host.setVoiceLock(it, it !in constraints.lockedVoiceTrackIds) }
            }
            PropertyButton(
                if (selectedStaffId in constraints.lockedStaffTrackIds) "解锁谱表" else "锁定谱表",
                selectedStaffId != null,
            ) {
                selectedStaffId?.let { host.setStaffLock(it, it !in constraints.lockedStaffTrackIds) }
            }
        }
    }
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
private fun PropertyButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(label)
    }
}
