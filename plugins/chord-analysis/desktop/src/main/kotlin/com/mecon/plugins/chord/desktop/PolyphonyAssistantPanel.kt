package com.mecon.plugins.chord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.pluginTrackOf
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.desktop.uikit.components.CircleOfFifthsPicker
import com.mecon.desktop.uikit.components.FifthsKey
import com.mecon.desktop.uikit.components.FifthsKeyMode
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import com.mecon.desktop.uikit.components.MeconSwitch
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.plugins.chord.PolyphonyDisplaySettings
import com.mecon.plugins.chord.PolyphonyTonalContextResolver
import com.mecon.plugins.chord.PolyphonyTonalKey
import com.mecon.plugins.chord.StorageNonChordToneEvent
import com.mecon.plugins.chord.StorageTonalRegionEvent
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.plugins.chord.TonalRegionEditPolicy
import com.mecon.plugins.chord.TonalRegionKeyCandidate
import com.mecon.plugins.chord.TonalRegionKeyInference
import com.mecon.plugins.chord.TonalRegionRole
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.math.roundToInt

@Composable
internal fun PolyphonyAssistantSection(ctx: PluginPanelContext) {
    var enabled by remember { mutableStateOf(PolyphonyDisplaySettings.isEnabled) }
    var showDegrees by remember { mutableStateOf(PolyphonyDisplaySettings.showDegreeTrack) }
    var showPassingChords by remember {
        mutableStateOf(PolyphonyDisplaySettings.showPassingChords)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground, RoundedCornerShape(6.dp))
            .border(1.dp, MeconColors.Border, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ToggleRow(
            label = i18n("plugin.chord.polyphony.enabled"),
            checked = enabled,
        ) {
            enabled = it
            PolyphonyDisplaySettings.isEnabled = it
            ctx.onRequestRender?.invoke()
        }
        if (!enabled) {
            Text(
                i18n("plugin.chord.polyphony.disabledHint"),
                color = MeconColors.TextMuted,
                fontSize = 9.sp,
            )
            return@Column
        }

        ToggleRow(i18n("plugin.chord.polyphony.degreeTrack"), showDegrees) {
            showDegrees = it
            PolyphonyDisplaySettings.showDegreeTrack = it
            ctx.onRequestRender?.invoke()
        }
        ToggleRow(i18n("plugin.chord.polyphony.passingChords"), showPassingChords) {
            showPassingChords = it
            PolyphonyDisplaySettings.showPassingChords = it
            ctx.onRequestRender?.invoke()
        }

        Spacer(Modifier.height(2.dp))
        NonChordToneEditor(ctx)
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MeconColors.TextSecondary, fontSize = 10.sp)
        MeconSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class SelectedNotehead(
    val event: ComputedVoiceEvent,
    val pitchIndex: Int,
)

private fun selectedScorePitches(selection: Set<EventSection>): List<SelectedNotehead> =
    selection.flatMap { section ->
        when (section) {
            is VoiceNoteSection -> listOf(SelectedNotehead(section.event, section.pitchIndex))
            is VoiceEventSection -> section.event.pitchData.indices.map {
                SelectedNotehead(section.event, it)
            }
            else -> emptyList()
        }
    }.distinctBy { it.event.id to it.pitchIndex }

private fun selectedSingleNotehead(selection: Set<EventSection>): SelectedNotehead? {
    val selected = selectedScorePitches(selection)
    return selected.singleOrNull()
}

@Composable
private fun NonChordToneEditor(ctx: PluginPanelContext) {
    val selected = remember(ctx.eventSelection) { selectedSingleNotehead(ctx.eventSelection) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            i18n("plugin.chord.polyphony.nonChordTone"),
            color = MeconColors.TextSecondary,
            fontSize = 11.sp,
        )
        if (selected == null) {
            Text(
                i18n("plugin.chord.polyphony.nonChordToneHint"),
                color = MeconColors.TextMuted,
                fontSize = 9.sp,
            )
            return@Column
        }

        var slice by remember(selected.event.id, selected.pitchIndex) {
            mutableStateOf(0f..SLICE_STEPS.toFloat())
        }
        val startStep = slice.start.roundToInt().coerceIn(0, SLICE_STEPS - 1)
        val endStep = slice.endInclusive.roundToInt().coerceIn(startStep + 1, SLICE_STEPS)
        val duration = selected.event.duration.toFraction()
        val start = selected.event.onset + duration * Fraction(startStep, SLICE_STEPS)
        val end = selected.event.onset + duration * Fraction(endStep, SLICE_STEPS)
        Text(
            "${selected.event.pitchData[selected.pitchIndex].pitch} · ${start.format()} – ${end.format()}",
            color = MeconColors.TextMuted,
            fontSize = 9.sp,
        )
        RangeSlider(
            value = slice,
            onValueChange = { slice = it },
            valueRange = 0f..SLICE_STEPS.toFloat(),
            steps = SLICE_STEPS - 1,
        )
        SmallAction(
            label = i18n("plugin.chord.polyphony.markSlice"),
            enabled = ctx.onAddPluginEvent != null,
        ) {
            ctx.onAddPluginEvent?.invoke(
                StorageNonChordToneEvent.TRACK_TYPE,
                StorageNonChordToneEvent.create(
                    onset = start,
                    endOnset = end,
                    voiceEventId = selected.event.id,
                    pitchIndex = selected.pitchIndex,
                ),
            )
        }

        val existing = remember(ctx.runtimeScore, selected.event.id, selected.pitchIndex) {
            ctx.runtimeScore
                ?.pluginTrackOf<StorageNonChordToneEvent>(StorageNonChordToneEvent.TRACK_TYPE)
                ?.eventsInRange(selected.event.onset, selected.event.endTime)
                .orEmpty()
                .map { it.storageEvent }
                .filter {
                    it.voiceEventId == selected.event.id && it.pitchIndex == selected.pitchIndex
                }
        }
        existing.forEach { mark ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${mark.onset.format()} – ${mark.endOnset.format()}",
                    color = MeconColors.TextMuted,
                    fontSize = 9.sp,
                )
                SmallAction(
                    label = i18n("plugin.chord.panel.delete"),
                    tint = MeconColors.Red,
                ) {
                    ctx.onDeletePluginEvent?.invoke(StorageNonChordToneEvent.TRACK_TYPE, mark.id)
                }
            }
        }
    }
}

private data class SelectedTimeRange(
    val start: TimeCode,
    val end: TimeCode,
)

private fun selectedTimeRange(selection: Set<EventSection>): SelectedTimeRange? {
    val events = selection.mapNotNull {
        when (it) {
            is VoiceNoteSection -> it.event
            is VoiceEventSection -> it.event
            else -> null
        }
    }.distinctBy(ComputedVoiceEvent::id)
    if (events.isEmpty()) return null
    return SelectedTimeRange(
        start = events.minOf(ComputedVoiceEvent::onset),
        end = events.maxOf(ComputedVoiceEvent::endTime),
    )
}

@Composable
internal fun TonalRegionEditor(ctx: PluginPanelContext) {
    val existingRegions = remember(ctx.runtimeScore) {
        ctx.runtimeScore
            ?.pluginTrackOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
            ?.events
            ?.map { it.storageEvent }
            .orEmpty()
    }
    val selectedRegion = remember(ctx.selectedAnnotationEventId, existingRegions) {
        val id = ctx.selectedAnnotationEventId ?: return@remember null
        existingRegions.firstOrNull { it.id == id }
    }
    val selectedPitches = remember(ctx.eventSelection) {
        selectedScorePitches(ctx.eventSelection).map {
            it.event.pitchData[it.pitchIndex].pitch
        }
    }
    var retainedEditingRegionId by remember { mutableStateOf<EventId?>(null) }
    val retainForNoteSelection =
        ctx.selectedAnnotationEventId == null && selectedPitches.isNotEmpty()
    val editingRegionId = selectedRegion?.id
        ?: retainedEditingRegionId.takeIf { retainForNoteSelection }
    val editingRegion = remember(editingRegionId, existingRegions) {
        existingRegions.firstOrNull { it.id == editingRegionId }
    }
    SideEffect {
        retainedEditingRegionId = when {
            selectedRegion != null -> selectedRegion.id
            retainForNoteSelection -> retainedEditingRegionId
            else -> null
        }
    }
    val selectionRange = remember(ctx.eventSelection, ctx.selectedAnnotationEventId, ctx.runtimeScore) {
        selectedTimeRange(ctx.eventSelection) ?: selectedChordTimeRange(ctx)
    }
    val scoreEnd = remember(ctx.runtimeScore) {
        ctx.runtimeScore?.measures?.maxOfOrNull { it.key }
            ?.let { TimeCode.of(it + 1, Fraction.ZERO) }
    }
    val range = editingRegion?.let {
        SelectedTimeRange(it.onset, it.endOnset)
    } ?: defaultTonalRegionRange(selectionRange, scoreEnd, ctx.runtimeScore)
    val referenceKey = remember(ctx.runtimeScore, range, existingRegions) {
        val score = ctx.runtimeScore
        val at = range?.start
        if (score != null && at != null) {
            PolyphonyTonalContextResolver.keysAt(score, at, existingRegions).firstOrNull()
        } else null
    } ?: ModulationKey(0, KeySignatureMode.MAJOR)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground, RoundedCornerShape(6.dp))
            .border(1.dp, MeconColors.Border, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            i18n("plugin.chord.polyphony.tonalRegion"),
            color = MeconColors.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            range?.let { "${it.start.format()} – ${it.end.format()}" }
                ?: i18n("plugin.chord.polyphony.tonalRegionHint"),
            color = MeconColors.TextMuted,
            fontSize = 9.sp,
        )
        if (editingRegion == null) {
            TonalRegionInsertControl(
                ctx = ctx,
                range = range,
                terminatePreviousAt = selectionRange?.end,
                selectedPitches = selectedPitches,
                referenceKey = referenceKey,
                existingRegions = existingRegions,
            )
        } else {
            ExistingTonalRegionEditor(
                ctx = ctx,
                editingRegion = editingRegion,
                range = range,
                selectedPitches = selectedPitches,
                referenceKey = referenceKey,
            )
        }
    }
}

private fun selectedChordTimeRange(ctx: PluginPanelContext): SelectedTimeRange? {
    val selectedId = ctx.selectedAnnotationEventId ?: return null
    val score = ctx.runtimeScore ?: return null
    val chords = score.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
        ?.events
        ?.map { it.storageEvent }
        ?.sortedWith(compareBy<StorageChordEvent> { it.onset }.thenBy { it.id.value })
        .orEmpty()
    val selected = chords.firstOrNull { it.id == selectedId } ?: return null
    val end = chords.firstOrNull { it.onset > selected.onset }?.onset
        ?: TimeCode.of((score.measures.map { it.key }.maxOrNull() ?: selected.onset.measure) + 1, Fraction.ZERO)
    return end.takeIf { it > selected.onset }?.let { SelectedTimeRange(selected.onset, it) }
}

private fun defaultTonalRegionRange(
    selected: SelectedTimeRange?,
    scoreEnd: TimeCode?,
    score: RuntimeScore?,
): SelectedTimeRange? {
    selected ?: return null
    val nextKeySignatureChange = score?.globalTrack?.events
        ?.asSequence()
        ?.filterIsInstance<StorageKeySignatureChange>()
        ?.map(StorageKeySignatureChange::onset)
        ?.filter { it > selected.start }
        ?.minOrNull()
    val end = TonalRegionEditPolicy.defaultInsertionEnd(
        start = selected.start,
        selectedEnd = selected.end,
        scoreEnd = scoreEnd,
        nextKeySignatureChange = nextKeySignatureChange,
    )
    return SelectedTimeRange(selected.start, end)
}

private enum class TonalRegionChoiceMode { CIRCLE, SINGLE_NOTE, NOTE_CANDIDATES }

@Composable
private fun TonalRegionInsertControl(
    ctx: PluginPanelContext,
    range: SelectedTimeRange?,
    terminatePreviousAt: TimeCode?,
    selectedPitches: List<Pitch>,
    referenceKey: ModulationKey,
    existingRegions: List<StorageTonalRegionEvent>,
) {
    var expanded by remember { mutableStateOf(false) }
    var terminatePrevious by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(TonalRegionChoiceMode.CIRCLE) }
    var selectedKeys by remember { mutableStateOf<List<ModulationKey>>(emptyList()) }
    var resolvedKey by remember { mutableStateOf<ModulationKey?>(null) }
    Box {
        SmallAction(
            label = i18n("plugin.chord.polyphony.insertRegion"),
            enabled = ctx.onReplacePluginEvents != null || ctx.onAddPluginEvent != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!expanded) {
                selectedKeys = emptyList()
                resolvedKey = null
            }
            expanded = !expanded
        }
        MeconDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(390.dp),
            containerColor = MeconColors.DialogBackground,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    i18n("plugin.chord.polyphony.insertRegionTitle"),
                    color = MeconColors.TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    i18n("plugin.chord.polyphony.insertRegionKeyHint"),
                    color = MeconColors.TextMuted,
                    fontSize = 9.sp,
                )
                if (range == null) {
                    Text(
                        i18n("plugin.chord.polyphony.selectTargetHint"),
                        color = MeconColors.OrangeLight,
                        fontSize = 9.sp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            i18n("plugin.chord.polyphony.terminatePrevious"),
                            color = MeconColors.TextPrimary,
                            fontSize = 11.sp,
                        )
                        Text(
                            i18n("plugin.chord.polyphony.terminatePreviousHint"),
                            color = MeconColors.TextMuted,
                            fontSize = 9.sp,
                        )
                    }
                    MeconSwitch(
                        checked = terminatePrevious,
                        onCheckedChange = { terminatePrevious = it },
                    )
                }
                TonalRegionKeyChooser(
                    mode = mode,
                    onModeChange = { mode = it },
                    selectedKeys = selectedKeys,
                    resolvedKey = resolvedKey,
                    onSelectionChange = { keys, center ->
                        selectedKeys = keys
                        resolvedKey = center
                    },
                    selectedPitches = selectedPitches,
                    referenceKey = referenceKey,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                ) {
                    SmallAction(
                        label = i18n("plugin.chord.polyphony.insertRegion"),
                        enabled = range != null && selectedKeys.isNotEmpty() && resolvedKey in selectedKeys,
                    ) {
                        val selectedRange = range ?: return@SmallAction
                        val event = StorageTonalRegionEvent.create(
                            onset = selectedRange.start,
                            endOnset = selectedRange.end,
                            keys = selectedKeys.map(PolyphonyTonalKey::from),
                            resolvedKey = resolvedKey?.let(PolyphonyTonalKey::from),
                        )
                        val replacement = TonalRegionEditPolicy.insert(
                            existing = existingRegions,
                            region = event,
                            terminatePrevious = terminatePrevious,
                            terminatePreviousAt = terminatePreviousAt
                                ?.takeIf { it > event.onset && it <= event.endOnset }
                                ?: event.endOnset,
                            fallbackPrevious = ctx.runtimeScore?.let { score ->
                                scoreKeyBaseline(score, event.onset, event.endOnset)
                            },
                        )
                        val replaceEvents = ctx.onReplacePluginEvents
                        if (replaceEvents != null) {
                            replaceEvents(StorageTonalRegionEvent.TRACK_TYPE, replacement)
                        } else {
                            ctx.onAddPluginEvent?.invoke(StorageTonalRegionEvent.TRACK_TYPE, event)
                        }
                        expanded = false
                    }
                    SmallAction(
                        label = i18n("plugin.chord.polyphony.closeChooser"),
                    ) { expanded = false }
                }
            }
        }
    }
}

@Composable
private fun TonalRegionKeyChooser(
    mode: TonalRegionChoiceMode,
    onModeChange: (TonalRegionChoiceMode) -> Unit,
    selectedKeys: List<ModulationKey>,
    resolvedKey: ModulationKey?,
    onSelectionChange: (List<ModulationKey>, ModulationKey?) -> Unit,
    selectedPitches: List<Pitch>,
    referenceKey: ModulationKey,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceModeAction(
            label = i18n("plugin.chord.polyphony.chooseByCircle"),
            selected = mode == TonalRegionChoiceMode.CIRCLE,
        ) { onModeChange(TonalRegionChoiceMode.CIRCLE) }
        ChoiceModeAction(
            label = i18n("plugin.chord.polyphony.chooseByDegree"),
            selected = mode == TonalRegionChoiceMode.SINGLE_NOTE,
        ) { onModeChange(TonalRegionChoiceMode.SINGLE_NOTE) }
        ChoiceModeAction(
            label = i18n("plugin.chord.polyphony.chooseByCandidates"),
            selected = mode == TonalRegionChoiceMode.NOTE_CANDIDATES,
        ) { onModeChange(TonalRegionChoiceMode.NOTE_CANDIDATES) }
    }

    val toggleKey: (ModulationKey) -> Unit = { key ->
        val next = when {
            key in selectedKeys -> selectedKeys.filterNot { it == key }
            selectedKeys.size < MAX_TONAL_REGION_KEYS -> selectedKeys + key
            else -> selectedKeys
        }
        val center = resolvedKey.takeIf { it in next } ?: next.firstOrNull()
        onSelectionChange(next, center)
    }

    when (mode) {
        TonalRegionChoiceMode.CIRCLE -> CircleOfFifthsPicker(
            currentKey = (resolvedKey ?: referenceKey).toUiKey(),
            selectedKeys = selectedKeys.mapTo(linkedSetOf(), ModulationKey::toUiKey),
            size = 340.dp,
            centerLabel = (resolvedKey ?: referenceKey).displayLabel(),
            label = FifthsKey::displayName,
            onKeyClick = { toggleKey(it.toModulationKey()) },
        )
        TonalRegionChoiceMode.SINGLE_NOTE -> if (selectedPitches.size == 1) {
            CandidateKeyList(
                candidates = TonalRegionKeyInference.singlePitchChoices(
                    selectedPitches.single(),
                    referenceKey,
                ),
                singlePitch = true,
                selectedKeys = selectedKeys.toSet(),
                onChoose = toggleKey,
            )
        } else SelectionAwaitingHint(
            i18n("plugin.chord.polyphony.selectSinglePitchHint")
        )
        TonalRegionChoiceMode.NOTE_CANDIDATES -> if (selectedPitches.size > 1) {
            CandidateKeyList(
                candidates = TonalRegionKeyInference.candidates(
                    selectedPitches,
                    referenceKey,
                    limit = 12,
                ),
                singlePitch = false,
                selectedKeys = selectedKeys.toSet(),
                onChoose = toggleKey,
            )
        } else SelectionAwaitingHint(
            i18n("plugin.chord.polyphony.selectMultiplePitchesHint")
        )
    }
    if (selectedKeys.isNotEmpty()) {
        Text(
            i18n("plugin.chord.polyphony.chooseCenter"),
            color = MeconColors.TextMuted,
            fontSize = 9.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            selectedKeys.forEach { key ->
                SmallAction(
                    label = key.displayLabel(),
                    tint = if (key == resolvedKey) MeconColors.Orange else MeconColors.Primary,
                ) { onSelectionChange(selectedKeys, key) }
            }
        }
    }
}

@Composable
private fun SelectionAwaitingHint(text: String) {
    Text(text, color = MeconColors.TextMuted, fontSize = 9.sp)
}

@Composable
private fun ChoiceModeAction(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SmallAction(
        label = label,
        enabled = enabled,
        tint = if (selected) MeconColors.Orange else MeconColors.Primary,
        onClick = onClick,
    )
}

@Composable
private fun CandidateKeyList(
    candidates: List<TonalRegionKeyCandidate>,
    singlePitch: Boolean,
    selectedKeys: Set<ModulationKey>,
    onChoose: (ModulationKey) -> Unit,
) {
    if (candidates.isEmpty()) {
        Text(
            i18n("plugin.chord.polyphony.noKeyCandidates"),
            color = MeconColors.TextMuted,
            fontSize = 9.sp,
        )
        return
    }
    Column(
        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        candidates.forEach { candidate ->
            val selected = candidate.key in selectedKeys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) MeconColors.Primary.copy(alpha = 0.18f)
                        else MeconColors.InputBackground,
                        RoundedCornerShape(4.dp),
                    )
                    .border(
                        1.dp,
                        if (selected) MeconColors.Primary.copy(alpha = 0.65f)
                        else MeconColors.Border,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onChoose(candidate.key) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        candidate.degreeLabels.joinToString("·"),
                        color = MeconColors.OrangeLight,
                        fontSize = if (singlePitch) 14.sp else 11.sp,
                    )
                    Text(
                        candidate.key.displayLabel(),
                        color = MeconColors.TextPrimary,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    if (candidate.alteredToneCount == 0) {
                        i18n("plugin.chord.polyphony.diatonicCandidate")
                    } else {
                        "${candidate.alteredToneCount} ${i18n("plugin.chord.polyphony.alteredCount")}"
                    },
                    color = MeconColors.TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun ExistingTonalRegionEditor(
    ctx: PluginPanelContext,
    editingRegion: StorageTonalRegionEvent,
    range: SelectedTimeRange?,
    selectedPitches: List<Pitch>,
    referenceKey: ModulationKey,
) {
    if (editingRegion.role == TonalRegionRole.SCORE_KEY_BASELINE) {
        Text(
            i18n("plugin.chord.polyphony.scoreBaselineHint"),
            color = MeconColors.TextMuted,
            fontSize = 9.sp,
        )
        return
    }
    var expanded by remember(editingRegion.id) { mutableStateOf(false) }
    var mode by remember(editingRegion.id) { mutableStateOf(TonalRegionChoiceMode.CIRCLE) }
    var selectedKeys by remember(editingRegion) {
        mutableStateOf(editingRegion.keys.map(PolyphonyTonalKey::toModulationKey))
    }
    var resolvedKey by remember(editingRegion) {
        mutableStateOf(editingRegion.resolvedKey?.toModulationKey())
    }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    editingRegion.keys.joinToString(" / ") { it.toModulationKey().displayLabel() },
                    color = MeconColors.TextPrimary,
                    fontSize = 12.sp,
                )
                editingRegion.resolvedKey?.let { center ->
                    Text(
                        "${i18n("plugin.chord.polyphony.resolvedCenter")}: ${center.toModulationKey().displayLabel()}",
                        color = MeconColors.TextMuted,
                        fontSize = 9.sp,
                    )
                }
            }
            SmallAction(label = i18n("plugin.chord.polyphony.editRegion")) {
                expanded = true
            }
        }
        MeconDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(390.dp),
            containerColor = MeconColors.DialogBackground,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    i18n("plugin.chord.polyphony.editRegionTitle"),
                    color = MeconColors.TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    i18n("plugin.chord.polyphony.insertRegionKeyHint"),
                    color = MeconColors.TextMuted,
                    fontSize = 9.sp,
                )
                TonalRegionKeyChooser(
                    mode = mode,
                    onModeChange = { mode = it },
                    selectedKeys = selectedKeys,
                    resolvedKey = resolvedKey,
                    onSelectionChange = { keys, center ->
                        selectedKeys = keys
                        resolvedKey = center
                    },
                    selectedPitches = selectedPitches,
                    referenceKey = referenceKey,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    SmallAction(
                        label = i18n("plugin.chord.panel.update"),
                        enabled = range != null && selectedKeys.isNotEmpty() && resolvedKey in selectedKeys,
                        modifier = Modifier.weight(1f),
                    ) {
                        val selectedRange = range ?: return@SmallAction
                        val event = editingRegion.copy(
                            onset = selectedRange.start,
                            endOnset = selectedRange.end,
                            keys = selectedKeys.map(PolyphonyTonalKey::from),
                            resolvedKey = resolvedKey?.let(PolyphonyTonalKey::from),
                        )
                        ctx.onUpdatePluginEvent?.invoke(
                            StorageTonalRegionEvent.TRACK_TYPE,
                            editingRegion.id,
                            event,
                        )
                        expanded = false
                    }
                    SmallAction(
                        label = i18n("plugin.chord.panel.delete"),
                        tint = MeconColors.Red,
                        modifier = Modifier.weight(1f),
                    ) {
                        ctx.onDeletePluginEvent?.invoke(StorageTonalRegionEvent.TRACK_TYPE, editingRegion.id)
                        expanded = false
                    }
                    SmallAction(
                        label = i18n("plugin.chord.polyphony.closeChooser"),
                        modifier = Modifier.weight(1f),
                    ) { expanded = false }
                }
            }
        }
    }
}

private fun scoreKeyBaseline(
    score: RuntimeScore,
    at: TimeCode,
    scoreEnd: TimeCode,
): StorageTonalRegionEvent {
    val signatureStart = score.globalTrack.events
        .asSequence()
        .filterIsInstance<StorageKeySignatureChange>()
        .filter { it.onset <= at }
        .maxByOrNull(StorageKeySignatureChange::onset)
        ?.onset
        ?: TimeCode.of(1, Fraction.ZERO)
    val signature = score.getKeySignatureAt(at.measure)
    val key = PolyphonyTonalKey(
        fifths = signature.fifths,
        mode = KeySignatureMode.fromApiMode(signature.mode),
    )
    return StorageTonalRegionEvent.create(
        onset = signatureStart,
        endOnset = scoreEnd,
        keys = listOf(key),
        resolvedKey = null,
        role = TonalRegionRole.SCORE_KEY_BASELINE,
    )
}

private const val MAX_TONAL_REGION_KEYS = 2

@Composable
private fun SmallAction(
    label: String,
    enabled: Boolean = true,
    tint: Color = MeconColors.Primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .background(
                if (enabled) tint.copy(alpha = 0.18f) else MeconColors.SurfaceDark,
                RoundedCornerShape(4.dp),
            )
            .border(
                1.dp,
                if (enabled) tint.copy(alpha = 0.65f) else MeconColors.Border,
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tint else MeconColors.TextDark,
            fontSize = 9.sp,
        )
    }
}

private fun ModulationKey.toUiKey(): FifthsKey = FifthsKey(
    fifths = fifths,
    mode = if (mode == KeySignatureMode.MAJOR) FifthsKeyMode.MAJOR else FifthsKeyMode.MINOR,
)

private fun ModulationKey.displayLabel(): String =
    displayName + if (mode == KeySignatureMode.MINOR) "m" else ""

private fun FifthsKey.displayName(): String =
    toModulationKey().let { it.displayName + if (it.mode == KeySignatureMode.MINOR) "m" else "" }

private fun FifthsKey.toModulationKey(): ModulationKey =
    ModulationKey(
        fifths = fifths,
        mode = if (mode == FifthsKeyMode.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
    )

private const val SLICE_STEPS = 16
