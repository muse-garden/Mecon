package com.mecon.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.PlayerId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.StorageReduction
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.core.analysis.OrchestrationFlowEngine
import com.mecon.desktop.uikit.theme.MeconColors

internal data class PlayerSyncTarget(
    val id: PlayerId,
    val number: Int,
    val label: String,
)

internal data class InstrumentPlayerGroup(
    val instrumentName: String,
    val players: List<PlayerSyncTarget>,
)

internal data class ReductionVoiceSyncTarget(
    val staffId: TrackId,
    val voiceId: TrackId,
    val staffName: String,
    val voiceNumber: Int,
)

/**
 * Soft but distinguishable synchronization-group colors. The translucent fill remains behind the
 * black notation, so the palette identifies structure without competing with pitch or selection
 * colors.
 */
private val SYNCHRONIZATION_GROUP_PALETTE = listOf(
    RenderColor.rgba(58, 166, 157, 96),  // teal
    RenderColor.rgba(78, 132, 196, 96),  // blue
    RenderColor.rgba(211, 151, 55, 96),  // amber
    RenderColor.rgba(143, 111, 190, 96), // violet
    RenderColor.rgba(205, 105, 101, 96), // coral
    RenderColor.rgba(96, 157, 91, 96),   // leaf
    RenderColor.rgba(65, 153, 188, 96),  // cyan
    RenderColor.rgba(188, 113, 157, 96), // rose
)

internal fun StorageScore.synchronizationGroupKey(sourceEventId: EventId): String =
    voiceIdForEvent(sourceEventId)?.value ?: "event:${sourceEventId.value}"

/**
 * Assign colors by the global temporal order of content lines, not by the current view's link
 * order. Main-score and reduction views therefore show the same group with the same color, while
 * neighboring groups rotate to different palette entries.
 */
internal fun StorageScore.synchronizationGroupColors(): Map<String, RenderColor> {
    val sourceEventIds = buildList {
        orchestration?.links.orEmpty().forEach { add(it.source.eventId) }
        reductions.forEach { reduction ->
            reduction.links.forEach { add(it.source.eventId) }
        }
    }
    return sourceEventIds
        .distinct()
        .groupBy(::synchronizationGroupKey)
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<EventId>>>(
                { entry -> entry.value.mapNotNull(::findVoiceEvent).minOfOrNull { it.onset } },
                { it.key },
            )
        )
        .mapIndexed { index, entry ->
            entry.key to SYNCHRONIZATION_GROUP_PALETTE[index % SYNCHRONIZATION_GROUP_PALETTE.size]
        }
        .toMap()
}

internal fun performerGroups(score: StorageScore): List<InstrumentPlayerGroup> {
    val orchestration = score.orchestration ?: return emptyList()
    val used = mutableSetOf<PlayerId>()
    val groups = score.instruments.mapNotNull { instrument ->
        val players = orchestration.players.filter { player ->
            player.instruments.any { it.id == instrument.id }
        }
        if (players.isEmpty()) return@mapNotNull null
        used += players.map { it.id }
        InstrumentPlayerGroup(
            instrumentName = instrument.name,
            players = players.mapIndexed { index, player ->
                PlayerSyncTarget(player.id, index + 1, player.abbreviation ?: player.name)
            },
        )
    }.toMutableList()
    orchestration.players.filter { it.id !in used }.forEach { player ->
        val instrumentName = player.instruments.firstOrNull()?.name ?: "演奏者"
        groups += InstrumentPlayerGroup(
            instrumentName,
            listOf(PlayerSyncTarget(player.id, 1, player.abbreviation ?: player.name)),
        )
    }
    return groups
}

internal fun reductionVoiceTargets(reduction: StorageReduction): List<ReductionVoiceSyncTarget> =
    reduction.notationScore.staffTracks.values.flatMap { staff ->
        staff.voiceTrackIds.mapNotNull { voiceId ->
            reduction.notationScore.voiceTracks[voiceId]?.let { voice ->
                ReductionVoiceSyncTarget(staff.id, voice.id, staff.name, voice.voiceNumber)
            }
        }
    }

@Composable
internal fun PerformerTargetBar(
    score: StorageScore,
    selectedPlayerIds: Set<PlayerId>,
    canSync: Boolean,
    onPlayerClick: (PlayerId) -> Unit,
) {
    TargetStrip(
        leadingText = if (canSync) "同步到总谱" else "先框选缩谱音符",
        groups = performerGroups(score).map { group ->
            TargetGroup(
                title = group.instrumentName,
                choices = group.players.map { player ->
                    TargetChoice(
                        label = player.number.toString(),
                        selected = player.id in selectedPlayerIds,
                        enabled = canSync,
                        onClick = { onPlayerClick(player.id) },
                    )
                },
            )
        },
    )
}

internal fun playersForReductionSelection(
    score: StorageScore,
    reduction: StorageReduction,
    selection: Set<EventSection>,
): Set<PlayerId> {
    val notes = selectedNoteRefs(reduction.notationScore, selection)
    val lineId = OrchestrationFlowEngine.lineIdForReductionNotes(score, reduction, notes)
        ?: return emptySet()
    return score.orchestration?.performances
        .orEmpty()
        .filter { it.lineId == lineId }
        .flatMapTo(linkedSetOf()) { it.playerIds }
}

@Composable
internal fun ReductionVoiceTargetBar(
    reduction: StorageReduction,
    selectedVoiceId: TrackId?,
    canSync: Boolean,
    onVoiceClick: (ReductionVoiceSyncTarget) -> Unit,
) {
    val targets = reductionVoiceTargets(reduction)
    TargetStrip(
        leadingText = if (canSync) "同步到缩谱" else "先框选总谱音符",
        groups = targets.groupBy { it.staffId }.values.map { staffTargets ->
            TargetGroup(
                title = staffTargets.first().staffName,
                choices = staffTargets.map { target ->
                    TargetChoice(
                        label = target.voiceNumber.toString(),
                        selected = target.voiceId == selectedVoiceId,
                        enabled = canSync,
                        onClick = { onVoiceClick(target) },
                    )
                },
            )
        },
    )
}

private data class TargetGroup(val title: String, val choices: List<TargetChoice>)
private data class TargetChoice(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun TargetStrip(leadingText: String, groups: List<TargetGroup>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(MeconColors.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            leadingText,
            modifier = Modifier.width(72.dp),
            color = MeconColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        if (groups.isEmpty()) {
            Text("未配置演奏者或缩谱声部", color = MeconColors.TextDark, fontSize = 10.sp)
        }
        groups.forEach { group ->
            Column(
                modifier = Modifier
                    .height(52.dp)
                    .requiredWidthIn(min = 112.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, MeconColors.Border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(group.title, color = MeconColors.TextPrimary, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.choices.forEach { choice -> TargetNumber(choice) }
                }
            }
        }
    }
}

@Composable
private fun TargetNumber(choice: TargetChoice) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    choice.selected -> MeconColors.PrimaryDark
                    else -> MeconColors.SurfaceLight
                }
            )
            .clickable(enabled = choice.enabled, onClick = choice.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            choice.label,
            color = when {
                !choice.enabled -> MeconColors.TextDark
                choice.selected -> MeconColors.SelectedIconOnSurface
                else -> MeconColors.TextPrimary
            },
            fontSize = 10.sp,
        )
    }
}

internal fun selectedNoteRefs(
    score: StorageScore,
    selection: Set<EventSection>,
): Set<NoteRef> = buildSet {
    selection.forEach { section ->
        when (section) {
            is VoiceNoteSection -> add(NoteRef(section.event.id, section.pitchIndex))
            else -> section.voiceEventIds().forEach { eventId ->
                val event = score.findVoiceEvent(eventId)
                val pitchCount = event?.let { score.findPitchEvent(it.pitchEventId)?.pitches?.size } ?: 0
                repeat(pitchCount) { add(NoteRef(eventId, it)) }
            }
        }
    }
}

internal fun StorageScore.voiceIdForEvent(eventId: EventId): TrackId? =
    voiceTracks.entries.firstOrNull { (_, voice) -> voice.events.any { it.id == eventId } }?.key

internal fun StorageScore.staffForVoice(voiceId: TrackId): StorageStaffTrack? =
    staffTracks.values.firstOrNull { voiceId in it.voiceTrackIds }

internal fun Set<EventSection>.onlyVoice(score: StorageScore, voiceId: TrackId?): Set<EventSection> {
    if (voiceId == null) {
        return filterTo(linkedSetOf()) { it.voiceEventIds().isNotEmpty() }
    }
    return filterTo(linkedSetOf()) { it.belongsToVoice(score, voiceId) }
}

internal fun EventSection.belongsToVoice(score: StorageScore, voiceId: TrackId): Boolean {
    val eventIds = voiceEventIds()
    return eventIds.isNotEmpty() && eventIds.all { score.voiceIdForEvent(it) == voiceId }
}

internal fun EventSection.voiceEventIds(): List<EventId> = when (this) {
    is VoiceNoteSection -> listOf(event.id)
    is VoiceStemSection -> listOf(event.id)
    is VoiceFlagSection -> listOf(event.id)
    is VoiceBeamSection -> events.map { it.id }
    is VoiceEventSection -> listOf(event.id)
    is VoiceArticulationSection -> listOf(event.id)
    is VoiceTupletSection -> listOf(startEvent.id)
    is VoiceTieSection -> listOf(sourceEvent.id)
    is VoiceSlurSection -> listOf(startEvent.id, endEvent.id)
    else -> emptyList()
}

internal fun mainEventIdsForPlayer(score: StorageScore, playerId: PlayerId?): Set<EventId> {
    if (playerId == null) return emptySet()
    val orchestration = score.orchestration ?: return emptySet()
    val assignedStaffIds = orchestration.staffAssignments
        .filter { it.playerId == playerId }
        .mapNotNullTo(hashSetOf()) { it.staffId }
        .ifEmpty {
            val player = orchestration.players.firstOrNull { it.id == playerId }
            player?.instruments.orEmpty().flatMapTo(hashSetOf()) { held ->
                score.instruments.firstOrNull { it.id == held.id }?.staffIds.orEmpty()
            }
        }
    val assignedVoiceIds = assignedStaffIds
        .flatMapTo(hashSetOf()) { staffId -> score.staffTracks[staffId]?.voiceTrackIds.orEmpty() }
    val linkedTargetIds = orchestration.links.mapTo(hashSetOf()) { it.target.eventId }
    val result = score.voiceTracks
        .filterKeys { it in assignedVoiceIds }
        .values
        .flatMapTo(linkedSetOf()) { voice ->
            voice.events.filter { it.id !in linkedTargetIds }.map { it.id }
        }
    orchestration.links.forEach { link ->
        val lineId = score.voiceIdForEvent(link.source.eventId) ?: return@forEach
        val lineEvent = score.findVoiceEvent(link.source.eventId) ?: return@forEach
        val performance = OrchestrationFlowEngine.effectivePerformance(
            orchestration,
            lineId,
            lineEvent.onset,
        )
        if (playerId in performance?.playerIds.orEmpty()) result += link.target.eventId
    }
    return result
}

internal fun Set<EventSection>.onlyEvents(eventIds: Set<EventId>): Set<EventSection> =
    filterTo(linkedSetOf()) { section ->
        val sectionEventIds = section.voiceEventIds()
        sectionEventIds.isNotEmpty() && sectionEventIds.all { it in eventIds }
    }

internal fun buildReductionToWrittenRequest(
    score: StorageScore,
    reduction: StorageReduction,
    selection: Set<EventSection>,
    playerId: PlayerId,
): OrchestrationFlowEngine.BindRequest? {
    val notes = selectedNoteRefs(reduction.notationScore, selection)
    if (notes.isEmpty()) return null
    val existingLineId = reduction.links
        .firstOrNull { it.target in notes }
        ?.source?.eventId
        ?.let(score::voiceIdForEvent)
    val onset = notes.mapNotNull { reduction.notationScore.findVoiceEvent(it.eventId)?.onset }.minOrNull()
        ?: TimeCode.ofMeasure(1)
    val route = routeForPlayer(score, playerId, existingLineId, onset) ?: return null
    val selectedVoice = notes.firstNotNullOfOrNull { reduction.notationScore.voiceIdForEvent(it.eventId) }
    val lineName = selectedVoice
        ?.let(reduction.notationScore.voiceTracks::get)
        ?.let { "${reduction.title} V${it.voiceNumber}" }
        ?: reduction.title
    return OrchestrationFlowEngine.BindRequest(
        reductionId = reduction.id,
        reductionNotes = notes,
        existingLineId = existingLineId,
        lineName = lineName,
        onset = onset,
        routes = listOf(route),
        realizeNow = true,
    )
}

internal fun buildWrittenToReductionRequest(
    score: StorageScore,
    reduction: StorageReduction,
    selection: Set<EventSection>,
    playerId: PlayerId,
    target: ReductionVoiceSyncTarget,
): OrchestrationFlowEngine.BindRequest? {
    val notes = selectedNoteRefs(score, selection)
    if (notes.isEmpty()) return null
    val orchestration = score.orchestration ?: return null
    val existingLineId = orchestration.links
        .firstOrNull { it.target in notes }
        ?.source?.eventId
        ?.let(score::voiceIdForEvent)
        ?: reduction.links.firstOrNull { link ->
            reduction.notationScore.voiceIdForEvent(link.target.eventId) == target.voiceId
        }?.source?.eventId?.let(score::voiceIdForEvent)
    val onset = notes.mapNotNull { score.findVoiceEvent(it.eventId)?.onset }.minOrNull()
        ?: TimeCode.ofMeasure(1)
    val selectedStaff = notes.firstNotNullOfOrNull { note ->
        score.voiceIdForEvent(note.eventId)?.let(score::staffForVoice)?.id
    }
    val route = routeForPlayer(score, playerId, existingLineId, onset, selectedStaff) ?: return null
    return OrchestrationFlowEngine.BindRequest(
        reductionId = reduction.id,
        writtenNotes = notes,
        targetReductionStaffId = target.staffId,
        targetReductionVoiceId = target.voiceId,
        existingLineId = existingLineId,
        lineName = "${target.staffName} V${target.voiceNumber}",
        onset = onset,
        routes = listOf(route),
        realizeNow = false,
    )
}

private fun routeForPlayer(
    score: StorageScore,
    playerId: PlayerId,
    lineId: TrackId?,
    onset: TimeCode,
    preferredStaffId: TrackId? = null,
): OrchestrationFlowEngine.PlayerRoute? {
    val orchestration = score.orchestration ?: return null
    val assignment = orchestration.staffAssignments
        .filter { assignment ->
            assignment.playerId == playerId &&
                assignment.onset <= onset &&
                (assignment.lineId == null || assignment.lineId == lineId)
        }
        .maxWithOrNull(
            compareBy<com.mecon.api.storage.StorageStaffAssignment> { it.onset }
                .thenBy { if (it.lineId == lineId && lineId != null) 1 else 0 },
        )
    val player = orchestration.players.firstOrNull { it.id == playerId }
    val fallbackStaff = player?.instruments
        ?.firstNotNullOfOrNull { held -> score.instruments.firstOrNull { it.id == held.id } }
        ?.staffIds
        ?.firstOrNull()
    val staffId = preferredStaffId
        ?.takeIf { it in score.staffTracks }
        ?: assignment?.staffId
        ?: fallbackStaff
        ?: return null
    return OrchestrationFlowEngine.PlayerRoute(
        playerId = playerId,
        staffId = staffId,
        voiceNumber = assignment?.voiceHint ?: 1,
    )
}
