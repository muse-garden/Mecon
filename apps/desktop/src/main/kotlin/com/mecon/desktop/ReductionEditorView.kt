package com.mecon.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.ReductionId
import com.mecon.api.primitive.PlayerId
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.ScoreFragmentKind
import com.mecon.api.storage.StorageReduction
import com.mecon.api.storage.StorageReductionLayer
import com.mecon.audio.engine.PlaybackState
import com.mecon.core.engine.computeScore
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.views.*
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun ReductionEditorView(
    session: ScoreSession,
    reductionId: ReductionId,
    noteTool: NoteToolState,
    playback: PlaybackController,
    currentPositionTicks: Long,
    playbackState: PlaybackState,
    refreshKey: Int,
    selection: () -> Set<EventSection>,
    setSelection: (Set<EventSection>) -> Unit,
    activeVoiceId: () -> TrackId?,
    setActiveVoiceId: (TrackId?) -> Unit,
    activePlayerId: () -> PlayerId?,
    setActivePlayerId: (PlayerId?) -> Unit,
) {
    val reduction = session.runtimeScore?.reductions
        ?.firstOrNull { it.id == reductionId }
        ?.migrated()
        ?: return
    var activeLayer by remember(reductionId) { mutableStateOf<ReductionLayerKind?>(null) }
    var trayExpanded by remember(reductionId) { mutableStateOf(false) }
    var targetMeasure by remember(reductionId) { mutableStateOf(1) }
    var eventSelection by MutableLiveValue(selection, setSelection)
    var selectedVoiceId by MutableLiveValue(activeVoiceId, setActiveVoiceId)
    var selectedPlayerId by MutableLiveValue(activePlayerId, setActivePlayerId)
    val rootScore = session.runtimeScore?.toStorage()

    Column(Modifier.fillMaxSize().background(MeconColors.Background)) {
        ReductionLayerBar(
            activeLayer = activeLayer,
            onSelect = {
                activeLayer = it
                eventSelection = emptySet()
            },
        )

        when (activeLayer) {
            null -> {
                ReductionTimelineStrip("曲式", reduction.layer(ReductionLayerKind.FORM))
                ReductionTimelineStrip("和声进行", reduction.layer(ReductionLayerKind.HARMONY))
                if (rootScore != null) {
                    PerformerTargetBar(
                        score = rootScore,
                        selectedPlayerIds = playersForReductionSelection(
                            rootScore,
                            reduction,
                            eventSelection,
                        ),
                        canSync = eventSelection.isNotEmpty(),
                        onPlayerClick = { playerId ->
                            selectedPlayerId = playerId
                            buildReductionToWrittenRequest(
                                rootScore,
                                reduction,
                                eventSelection,
                                playerId,
                            )?.let(session::toggleReductionPlayer)
                        },
                    )
                }
                Box(Modifier.weight(1f)) {
                    ReductionNotationScore(
                        score = reduction.notationScore,
                        session = session,
                        reductionId = reductionId,
                        noteTool = noteTool,
                        playback = playback,
                        currentPositionTicks = currentPositionTicks,
                        playbackState = playbackState,
                        refreshKey = refreshKey,
                        eventSelection = eventSelection,
                        onSelectionChange = { eventSelection = it },
                        activeVoiceId = selectedVoiceId,
                        onActiveVoiceChange = {
                            selectedVoiceId = it
                            eventSelection = emptySet()
                        },
                    )
                }
            }

            ReductionLayerKind.FORM ->
                FocusedTimelineLayer(
                    title = "曲式层",
                    subtitle = "区间与终止位置编辑将在下一阶段接入。",
                    layer = reduction.layer(ReductionLayerKind.FORM),
                    modifier = Modifier.weight(1f),
                )

            ReductionLayerKind.HARMONY ->
                FocusedTimelineLayer(
                    title = "和声进行层",
                    subtitle = "当前展示固定在谱面上方；和弦输入与边界拖动将在下一阶段接入。",
                    layer = reduction.layer(ReductionLayerKind.HARMONY),
                    modifier = Modifier.weight(1f),
                )

            ReductionLayerKind.SKELETON -> {
                val skeleton = reduction.scoreFor(ReductionLayerKind.SKELETON)
                if (skeleton == null) {
                    EmptyScoreLayer(
                        title = "骨架层尚未创建",
                        detail = "先建立与缩谱相同的空谱表结构，再逐步加入结构音。",
                        action = "创建骨架层",
                        modifier = Modifier.weight(1f),
                        onAction = {
                            session.initializeReductionLayer(reductionId, ReductionLayerKind.SKELETON)
                        },
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        LayerNotice("骨架层已建立；本轮先用于对照，编辑仍在缩谱记谱层完成。")
                        Box(Modifier.weight(1f)) {
                            ReadOnlyReductionScore(
                                score = skeleton,
                                session = session,
                                currentPositionTicks = currentPositionTicks,
                                playbackState = playbackState,
                                refreshKey = refreshKey,
                            )
                        }
                    }
                }
            }

            ReductionLayerKind.NOTATION -> Box(Modifier.weight(1f)) {
                ReductionNotationScore(
                    score = reduction.notationScore,
                    session = session,
                    reductionId = reductionId,
                    noteTool = noteTool,
                    playback = playback,
                    currentPositionTicks = currentPositionTicks,
                    playbackState = playbackState,
                    refreshKey = refreshKey,
                    eventSelection = eventSelection,
                    onSelectionChange = { eventSelection = it },
                    activeVoiceId = selectedVoiceId,
                    onActiveVoiceChange = {
                        selectedVoiceId = it
                        eventSelection = emptySet()
                    },
                )
            }

            ReductionLayerKind.ORCHESTRATION -> Column(Modifier.weight(1f)) {
                if (rootScore != null) {
                    PerformerTargetBar(
                        score = rootScore,
                        selectedPlayerIds = emptySet(),
                        canSync = false,
                        onPlayerClick = { selectedPlayerId = it },
                    )
                }
                LayerNotice("配器层以谱面演奏者标签呈现；详细时变路由继续使用“演奏者/谱表”。")
                Box(Modifier.weight(1f)) {
                    ReadOnlyReductionScore(
                        score = reduction.notationScore,
                        session = session,
                        currentPositionTicks = currentPositionTicks,
                        playbackState = playbackState,
                        refreshKey = refreshKey,
                    )
                }
            }
        }

        MaterialTray(
            reduction = reduction,
            expanded = trayExpanded,
            onToggle = { trayExpanded = !trayExpanded },
            selection = eventSelection,
            targetMeasure = targetMeasure,
            onTargetMeasureChange = {
                val lastMeasure = reduction.notationScore.measures.maxOfOrNull { measure -> measure.number } ?: 1
                targetMeasure = it.coerceIn(1, lastMeasure)
            },
            onCapture = {
                session.saveReductionFragment(
                    reductionId,
                    selectedNoteRefs(reduction.notationScore, eventSelection),
                )
            },
            onPlace = { fragmentId ->
                session.placeReductionFragment(reductionId, fragmentId, targetMeasure)
            },
        )
    }
}

@Composable
private fun ReductionLayerBar(
    activeLayer: ReductionLayerKind?,
    onSelect: (ReductionLayerKind?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(MeconColors.PanelHeader)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerChip("综合", activeLayer == null) { onSelect(null) }
        ReductionLayerKind.entries.forEach { kind ->
            LayerChip(kind.label(), activeLayer == kind) { onSelect(kind) }
        }
    }
}

@Composable
private fun LayerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) MeconColors.SelectedSurface else MeconColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (active) MeconColors.SelectedIconOnSurface else MeconColors.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ReductionTimelineStrip(title: String, layer: StorageReductionLayer?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MeconColors.SurfaceDark)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = MeconColors.TextMuted, fontSize = 10.sp, modifier = Modifier.width(58.dp))
        if (layer?.timelineItems.isNullOrEmpty()) {
            Text("尚无内容", color = MeconColors.TextDark, fontSize = 10.sp)
        } else {
            layer!!.timelineItems.forEach { item ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MeconColors.SurfaceLight)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(item.label, color = MeconColors.TextPrimary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FocusedTimelineLayer(
    title: String,
    subtitle: String,
    layer: StorageReductionLayer?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ReductionTimelineStrip(title, layer)
        EmptyScoreLayer(title, subtitle)
    }
}

@Composable
private fun ReductionNotationScore(
    score: com.mecon.api.storage.StorageScore,
    session: ScoreSession,
    reductionId: ReductionId,
    noteTool: NoteToolState,
    playback: PlaybackController,
    currentPositionTicks: Long,
    playbackState: PlaybackState,
    refreshKey: Int,
    eventSelection: Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
    activeVoiceId: TrackId?,
    onActiveVoiceChange: (TrackId?) -> Unit,
) {
    val scoreIdentityKey = rememberIdentityKey(score)
    val runtime = remember(scoreIdentityKey) { RuntimeScore.fromStorage(score) }
    val runtimeIdentityKey = rememberIdentityKey(runtime)
    val computed = remember(runtimeIdentityKey) { computeScore(runtime) }
    val currentReduction = session.runtimeScore?.reductions
        ?.firstOrNull { it.id == reductionId }
        ?.migrated()
        ?: return
    val selectorTargets = reductionVoiceTargets(currentReduction)
    val currentOnActiveVoiceChange by rememberUpdatedState(onActiveVoiceChange)
    val voiceFocus = remember(scoreIdentityKey, selectorTargets, activeVoiceId) {
        VoiceFocus.create(
            score = score,
            targets = selectorTargets.map { target ->
                VoiceFocusTarget(
                    staffId = target.staffId,
                    voiceId = target.voiceId,
                    label = target.voiceNumber.toString(),
                )
            },
            activeVoiceId = activeVoiceId,
            allowAllVoices = true,
            onActiveVoiceChange = currentOnActiveVoiceChange,
        )
    }
    val outerScore = session.runtimeScore?.toStorage()
    val synchronizationColors = outerScore?.synchronizationGroupColors().orEmpty()
    val backgroundGroups = currentReduction.links
        .groupBy { link ->
            outerScore?.synchronizationGroupKey(link.source.eventId)
                ?: "event:${link.source.eventId.value}"
        }
        .map { (groupKey, links) ->
            RenderedScoreNoteheadBackgroundGroup(
                notes = links.mapTo(linkedSetOf()) { it.target },
                color = synchronizationColors[groupKey] ?: RenderColor.rgba(58, 166, 157, 96),
            )
        }
    RenderedScoreView(
        config = RenderedScoreViewConfig(
            source = RenderedScoreSource(
                score = runtime,
                computed = computed,
                documentVersion = session.documentVersion,
            ),
            selectionConfig = RenderedScoreSelectionConfig(
                selection = eventSelection,
                onSelectionChange = { onSelectionChange(voiceFocus.filterSelection(it)) },
                localEventStyles = voiceFocus.localEventStyles,
                noteheadBackgroundGroups = backgroundGroups,
                selectableSection = voiceFocus::canSelect,
            ),
            display = RenderedScoreDisplayConfig(
                isReference = true,
                readOnly = false,
                renderRefreshKey = refreshKey,
                currentPositionTicks = currentPositionTicks,
                playbackState = playbackState,
                arrangement = session.pageArrangement,
                showEditorMarkers = true,
            ),
            edit = RenderedScoreEditConfig(
                notation = RenderedScoreNotationInsertion(
                    noteTool = noteTool,
                    onAuditionNote = { event, pitchIndices, transposedPitchIndices, stepDelta ->
                        playback.audition(runtime, event, pitchIndices, transposedPitchIndices, stepDelta)
                    },
                    onInsertNote = { insertion ->
                        session.applyReductionNoteEdit(reductionId, insertion) { inserted, committed ->
                            onSelectionChange(setOf(inserted))
                            val insertedEvent = (inserted as? VoiceEventSection)?.event
                            if (insertedEvent != null) {
                                playback.auditionPiano(committed, insertedEvent)
                            }
                        }
                    },
                ),
                eventMovement = RenderedScoreEventMoveActions(
                    onTranspose = { targets, stepDelta ->
                        session.applyReductionTranspose(reductionId, targets, stepDelta)
                    },
                    onMoveRest = { targets -> session.applyReductionRestMove(reductionId, targets) },
                ),
            ),
            staffSelectors = voiceFocus.staffSelectors,
        ),
    )
}

@Composable
private fun ReadOnlyReductionScore(
    score: com.mecon.api.storage.StorageScore,
    session: ScoreSession,
    currentPositionTicks: Long,
    playbackState: PlaybackState,
    refreshKey: Int,
) {
    val runtime = remember(score) { RuntimeScore.fromStorage(score) }
    val computed = remember(runtime) { computeScore(runtime) }
    RenderedScoreView(
        config = RenderedScoreViewConfig(
            source = RenderedScoreSource(
                score = runtime,
                computed = computed,
                documentVersion = session.documentVersion,
            ),
            display = RenderedScoreDisplayConfig(
                isReference = true,
                readOnly = true,
                renderRefreshKey = refreshKey,
                currentPositionTicks = currentPositionTicks,
                playbackState = playbackState,
                arrangement = session.pageArrangement,
                showEditorMarkers = false,
            ),
        ),
    )
}

@Composable
private fun EmptyScoreLayer(
    title: String,
    detail: String,
    action: String? = null,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = MeconColors.TextPrimary, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = MeconColors.TextMuted, fontSize = 11.sp)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LayerNotice(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(MeconColors.SurfaceDark).padding(8.dp),
        color = MeconColors.TextMuted,
        fontSize = 10.sp,
    )
}

@Composable
private fun MaterialTray(
    reduction: StorageReduction,
    expanded: Boolean,
    onToggle: () -> Unit,
    selection: Set<EventSection>,
    targetMeasure: Int,
    onTargetMeasureChange: (Int) -> Unit,
    onCapture: () -> Unit,
    onPlace: (com.mecon.api.primitive.ScoreFragmentId) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MeconColors.PanelHeader)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾ 素材台" else "▸ 素材台", color = MeconColors.TextPrimary, fontSize = 11.sp)
            Text("  ${reduction.materialTray.size} 项", color = MeconColors.TextMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (expanded) {
                OutlinedButton(
                    onClick = onCapture,
                    enabled = selectedNoteRefs(reduction.notationScore, selection).isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text("保存当前选区", fontSize = 10.sp)
                }
            }
        }
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("写入目标：第 $targetMeasure 小节", color = MeconColors.TextSecondary, fontSize = 10.sp)
                TextButton(onClick = { onTargetMeasureChange(targetMeasure - 1) }) { Text("−") }
                TextButton(onClick = { onTargetMeasureChange(targetMeasure + 1) }) { Text("+") }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reduction.materialTray.isEmpty()) {
                    Text(
                        "在记谱层选择音符后点击“保存当前选区”。",
                        color = MeconColors.TextMuted,
                        fontSize = 10.sp,
                    )
                }
                reduction.materialTray.forEach { fragment ->
                    Column(
                        Modifier
                            .width(150.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MeconColors.Surface)
                            .padding(8.dp),
                    ) {
                        Text(fragment.name, color = MeconColors.TextPrimary, fontSize = 11.sp)
                        Text(
                            if (fragment.kind == ScoreFragmentKind.MELODIC) "旋律片段" else "普通片段",
                            color = MeconColors.TextMuted,
                            fontSize = 9.sp,
                        )
                        fragment.sourceMetadata?.originalRange?.let { range ->
                            Text(
                                "来源 ${range.start.measure}–${range.end.measure} 小节",
                                color = MeconColors.TextDark,
                                fontSize = 9.sp,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { onPlace(fragment.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp),
                        ) {
                            Text("写入缩谱", fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun ReductionLayerKind.label(): String = when (this) {
    ReductionLayerKind.FORM -> "曲式"
    ReductionLayerKind.HARMONY -> "和声进行"
    ReductionLayerKind.SKELETON -> "骨架"
    ReductionLayerKind.NOTATION -> "缩谱记谱"
    ReductionLayerKind.ORCHESTRATION -> "配器"
}
