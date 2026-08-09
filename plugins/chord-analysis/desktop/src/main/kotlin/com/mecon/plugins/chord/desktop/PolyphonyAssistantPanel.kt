package com.mecon.plugins.chord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.pluginTrackOf
import com.mecon.desktop.uikit.components.CircleOfFifthsPicker
import com.mecon.desktop.uikit.components.FifthsKey
import com.mecon.desktop.uikit.components.FifthsKeyMode
import com.mecon.desktop.uikit.components.MeconSwitch
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.plugins.chord.PolyphonyDisplaySettings
import com.mecon.plugins.chord.PolyphonyTonalKey
import com.mecon.plugins.chord.StorageNonChordToneEvent
import com.mecon.plugins.chord.StorageTonalRegionEvent
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.math.roundToInt

@Composable
internal fun PolyphonyAssistantSection(ctx: PluginPanelContext) {
    val selectedNoteheads = remember(ctx.eventSelection) {
        ctx.eventSelection.flatMapTo(linkedSetOf()) { section ->
            when (section) {
                is VoiceNoteSection -> listOf(section.event.id to section.pitchIndex)
                is VoiceEventSection -> section.event.pitchData.indices.map { section.event.id to it }
                else -> emptyList()
            }
        }
    }
    LaunchedEffect(selectedNoteheads) {
        if (PolyphonyDisplaySettings.selectedNoteheads != selectedNoteheads) {
            PolyphonyDisplaySettings.selectedNoteheads = selectedNoteheads
            if (PolyphonyDisplaySettings.isEnabled) ctx.onRequestRender?.invoke()
        }
    }

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
        TonalRegionEditor(ctx)
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

private fun selectedSingleNotehead(selection: Set<EventSection>): SelectedNotehead? {
    val selected = selection.flatMap { section ->
        when (section) {
            is VoiceNoteSection -> listOf(SelectedNotehead(section.event, section.pitchIndex))
            is VoiceEventSection ->
                if (section.event.pitchData.size == 1) listOf(SelectedNotehead(section.event, 0))
                else emptyList()
            else -> emptyList()
        }
    }.distinctBy { it.event.id to it.pitchIndex }
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
private fun TonalRegionEditor(ctx: PluginPanelContext) {
    val editingRegion = remember(ctx.selectedAnnotationEventId, ctx.runtimeScore) {
        val id = ctx.selectedAnnotationEventId ?: return@remember null
        ctx.runtimeScore
            ?.pluginTrackOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
            ?.findEventById(id)
            ?.storageEvent
    }
    val selectionRange = remember(ctx.eventSelection) { selectedTimeRange(ctx.eventSelection) }
    val range = editingRegion?.let { SelectedTimeRange(it.onset, it.endOnset) } ?: selectionRange
    var selectedKeys by remember(editingRegion?.id) {
        mutableStateOf<Set<FifthsKey>>(
            editingRegion?.keys?.mapTo(linkedSetOf()) { it.toUiKey() } ?: linkedSetOf()
        )
    }
    var resolvedKey by remember(editingRegion?.id) {
        mutableStateOf(editingRegion?.resolvedKey?.toUiKey())
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            CircleOfFifthsPicker(
                selectedKeys = selectedKeys,
                currentKey = resolvedKey,
                size = 320.dp,
                centerLabel = resolvedKey?.displayName(),
                centerCaption = i18n("plugin.chord.polyphony.resolvedCenter"),
                label = FifthsKey::displayName,
                onKeyClick = { key ->
                    selectedKeys = selectedKeys.toMutableSet().apply {
                        if (!add(key)) remove(key)
                    }
                    resolvedKey = when {
                        key !in selectedKeys && resolvedKey == key -> selectedKeys.firstOrNull()
                        resolvedKey == null -> selectedKeys.firstOrNull()
                        else -> resolvedKey
                    }
                },
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
                        label = key.displayName(),
                        tint = if (key == resolvedKey) MeconColors.Orange else MeconColors.Primary,
                    ) { resolvedKey = key }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SmallAction(
                label = i18n(
                    if (editingRegion == null) "plugin.chord.polyphony.addRegion"
                    else "plugin.chord.panel.update"
                ),
                enabled = range != null && selectedKeys.isNotEmpty() && resolvedKey in selectedKeys,
                modifier = Modifier.weight(1f),
            ) {
                val selectedRange = range ?: return@SmallAction
                val keys = selectedKeys.map(FifthsKey::toStorageKey)
                val resolved = resolvedKey?.toStorageKey()
                val event = editingRegion?.copy(
                    onset = selectedRange.start,
                    endOnset = selectedRange.end,
                    keys = keys,
                    resolvedKey = resolved,
                ) ?: StorageTonalRegionEvent.create(
                    onset = selectedRange.start,
                    endOnset = selectedRange.end,
                    keys = keys,
                    resolvedKey = resolved,
                )
                if (editingRegion == null) {
                    ctx.onAddPluginEvent?.invoke(StorageTonalRegionEvent.TRACK_TYPE, event)
                } else {
                    ctx.onUpdatePluginEvent?.invoke(
                        StorageTonalRegionEvent.TRACK_TYPE,
                        editingRegion.id,
                        event,
                    )
                }
            }
            if (editingRegion != null) {
                SmallAction(
                    label = i18n("plugin.chord.panel.delete"),
                    tint = MeconColors.Red,
                    modifier = Modifier.weight(1f),
                ) {
                    ctx.onDeletePluginEvent?.invoke(StorageTonalRegionEvent.TRACK_TYPE, editingRegion.id)
                }
            }
        }
    }
}

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

private fun PolyphonyTonalKey.toUiKey(): FifthsKey = FifthsKey(
    fifths = fifths,
    mode = if (mode == KeySignatureMode.MAJOR) FifthsKeyMode.MAJOR else FifthsKeyMode.MINOR,
)

private fun FifthsKey.toStorageKey(): PolyphonyTonalKey = PolyphonyTonalKey(
    fifths = fifths,
    mode = if (mode == FifthsKeyMode.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
)

private fun FifthsKey.displayName(): String =
    toModulationKey().let { it.displayName + if (it.mode == KeySignatureMode.MINOR) "m" else "" }

private fun FifthsKey.toModulationKey(): ModulationKey =
    ModulationKey(
        fifths = fifths,
        mode = if (mode == FifthsKeyMode.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
    )

private const val SLICE_STEPS = 16
