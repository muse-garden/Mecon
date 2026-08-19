package com.mecon.plugins.chord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.pluginTrackOf
import com.mecon.desktop.uikit.PitchMode
import com.mecon.desktop.uikit.components.MeconLabeledSwitch
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.plugin.PluginPanel
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.plugins.chord.ChordToneStyleProvider
import com.mecon.plugins.chord.ComputedChordEvent
import com.mecon.plugins.chord.RuntimeChordEvent
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.plugins.chord.ChordSymbolDisplaySettings
import com.mecon.plugins.chord.ChordAnalysisScoreDisplayMode
import com.mecon.plugins.chord.PolyphonyDisplaySettings
import com.mecon.plugins.chord.desktop.ChordSymbolParser.Parsed
import com.mecon.theory.Chord
import com.mecon.theory.ChordRecognitionCandidate
import com.mecon.theory.ChordRecognizer
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.ChordSymbolFormatter
import com.mecon.theory.ChordTone
import com.mecon.theory.MissingChordTone
import com.mecon.theory.NonChordToneType

/**
 * Right-panel section for the chord analysis plugin.
 *
 * v1 surfaces:
 *  - Chord symbol text input with live parse preview
 *  - "Add at playhead" button (currently disabled — wiring to [ScoreStateManager]
 *    happens in a follow-up; the form proves the SPI plumbing works today)
 *  - Tonnetz subsection placeholder migrated from the legacy `RightPanel`
 */
internal object ChordAnalysisPanel : PluginPanel {
    override val id: String = "mecon.chord_analysis"
    override val titleKey: String = "plugin.chord.panel.title"
    override val icon: ImageVector? = Icons.Default.Album
    override val wrapContent: Boolean = true

    @Composable
    override fun Content(ctx: PluginPanelContext) {
        val selectedPitchClasses = remember(ctx.eventSelection) { selectedPitchClasses(ctx.eventSelection) }
        val selectionChord = remember(ctx.eventSelection) { analyzeSelectedChord(ctx.eventSelection) }
        // Chord to display (annotation click or note onset lookup)
        val displayChord = remember(ctx.selectedAnnotationEventId, ctx.targetTimeCode, ctx.runtimeScore, selectionChord) {
            val annotationId = ctx.selectedAnnotationEventId
            if (annotationId != null) {
                ctx.runtimeScore
                    ?.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
                    ?.findEventById(annotationId)?.storageEvent
            } else if (selectionChord?.uniqueCandidate != null) {
                selectionChord.toPreviewStorageEvent()
            } else {
                val track = ctx.runtimeScore?.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
                    ?: return@remember null
                val onset = ctx.targetTimeCode ?: return@remember null
                track.lastEventAtOrBefore(onset)?.storageEvent
            }
        }
        // Only editable when a chord annotation symbol was explicitly clicked
        val editableChord = remember(ctx.selectedAnnotationEventId, ctx.runtimeScore) {
            val annotationId = ctx.selectedAnnotationEventId ?: return@remember null
            ctx.runtimeScore
                ?.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
                ?.findEventById(annotationId)?.storageEvent
        }
        var coloringEnabled by remember { mutableStateOf(ChordToneStyleProvider.isEnabled) }
        var pitchMode by remember { mutableStateOf(PitchMode.ABSOLUTE) }
        var chordSymbolStyle by remember { mutableStateOf(ChordSymbolDisplaySettings.style) }
        var timelineDisplay by remember {
            mutableStateOf(
                ChordSymbolDisplaySettings.scoreDisplayMode == ChordAnalysisScoreDisplayMode.TIMELINE
            )
        }
        var selectedDegreeOverlay by remember {
            mutableStateOf(PolyphonyDisplaySettings.showSelectedDegrees)
        }

        val keySignature = remember(displayChord, selectionChord, ctx.targetTimeCode, ctx.runtimeScore) {
            val rs = ctx.runtimeScore
            val time = displayChord?.onset ?: selectionChord?.onset ?: ctx.targetTimeCode
            when {
                rs != null && time != null -> rs.getKeySignatureAt(time.measure)
                rs != null -> rs.defaultKeySignature
                else -> KeySignature.C_MAJOR
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            PitchModeToggle(
                pitchMode = pitchMode,
                onToggle = { pitchMode = if (pitchMode == PitchMode.ABSOLUTE) PitchMode.RELATIVE else PitchMode.ABSOLUTE }
            )
            Spacer(Modifier.height(6.dp))
            ChordSymbolStyleToggle(
                style = chordSymbolStyle,
                onToggle = {
                    chordSymbolStyle =
                        if (chordSymbolStyle == ChordSymbolDisplayStyle.LETTER) ChordSymbolDisplayStyle.SCALE_DEGREE
                        else ChordSymbolDisplayStyle.LETTER
                    ChordSymbolDisplaySettings.style = chordSymbolStyle
                    ctx.onRequestRender?.invoke()
                }
            )
            Spacer(Modifier.height(6.dp))
            MeconLabeledSwitch(
                label = i18n("plugin.chord.panel.timelineDisplay"),
                checked = timelineDisplay,
                onCheckedChange = { enabled ->
                    timelineDisplay = enabled
                    ChordSymbolDisplaySettings.scoreDisplayMode = if (enabled) {
                        ChordAnalysisScoreDisplayMode.TIMELINE
                    } else {
                        ChordAnalysisScoreDisplayMode.CLASSIC
                    }
                    ctx.onRequestRender?.invoke()
                },
            )
            Spacer(Modifier.height(6.dp))
            MeconLabeledSwitch(
                label = i18n("plugin.chord.panel.showToneColoring"),
                checked = coloringEnabled,
                onCheckedChange = { enabled ->
                    coloringEnabled = enabled
                    ChordToneStyleProvider.isEnabled = enabled
                    ctx.onRequestNoteStyleRecompute?.invoke()
                }
            )
            Spacer(Modifier.height(6.dp))
            MeconLabeledSwitch(
                label = i18n("plugin.chord.panel.showSelectedDegrees"),
                checked = selectedDegreeOverlay,
                onCheckedChange = { enabled ->
                    selectedDegreeOverlay = enabled
                    PolyphonyDisplaySettings.showSelectedDegrees = enabled
                    ctx.onRequestSelectionOverlayRefresh?.invoke()
                },
            )
            if (coloringEnabled) {
                Spacer(Modifier.height(6.dp))
                NonChordToneLegend()
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                label = i18n("plugin.chord.panel.showPianoRollChords"),
                checked = ctx.pianoRollChordOverlayEnabled,
            ) {
                ctx.onShowPianoRollChords?.invoke(it)
            }
            Spacer(Modifier.height(10.dp))
            TonalRegionEditor(ctx)
            Spacer(Modifier.height(10.dp))
            PolyphonyAssistantSection(ctx)
            Spacer(Modifier.height(12.dp))
            SelectedChordSection(ctx = ctx, analysis = selectionChord, symbolStyle = chordSymbolStyle, keySignature = keySignature)
            Spacer(Modifier.height(12.dp))
            ChordInputSection(ctx = ctx, selectedChord = editableChord, symbolStyle = chordSymbolStyle, keySignature = keySignature)
            Spacer(Modifier.height(12.dp))
            // Chord disk — shown when a chord is in scope, otherwise a minimal placeholder
            if (displayChord != null) {
                ChordDisk(
                    chord = displayChord,
                    pitchMode = pitchMode,
                    chordSymbolStyle = chordSymbolStyle,
                    keySignature = keySignature,
                    selectedPitchClasses = selectedPitchClasses,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                )
            } else {
                ChordDiskPlaceholder()
            }
            Spacer(Modifier.height(12.dp))
            TonnetzSubsection(displayChord = displayChord, runtimeScore = ctx.runtimeScore, pitchMode = pitchMode, keySignature = keySignature)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NonChordToneLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NonChordToneType.entries.forEach { type ->
            val color = ChordToneStyleProvider.typeColors.getValue(type)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        androidx.compose.ui.graphics.Color(color.red, color.green, color.blue, color.alpha)
                    )
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    i18n("plugin.chord.panel.nct.${type.name.lowercase()}"),
                    fontSize = 9.sp,
                    color = MeconColors.TextMuted,
                )
            }
        }
    }
}

private data class SelectedChordAnalysis(
    val onset: TimeCode,
    val pitches: List<Pitch>,
    val pitchClasses: List<Int>,
    val candidates: List<ChordRecognitionCandidate>,
) {
    val uniqueCandidate: ChordRecognitionCandidate? get() = candidates.singleOrNull()

    fun toPreviewStorageEvent(): StorageChordEvent? {
        val chord = uniqueCandidate?.chord ?: return null
        return StorageChordEvent(
            id = EventId("selected-chord-preview"),
            onset = onset,
            root = chord.root.value,
            quality = chord.quality,
            bass = chord.bass?.value,
        )
    }
}

private fun analyzeSelectedChord(selection: Set<EventSection>): SelectedChordAnalysis? {
    val selected = selectedPitches(selection)
    if (selected.size < 2) return null

    val onset = selected.minBy { it.first }.first
    val pitches = selected.map { it.second }.distinctBy { it.midiNumber }.sortedBy { it.midiNumber }
    val pitchClasses = pitches.map { it.pitchClass.value }.distinct()
    if (pitchClasses.size < 2) return null

    return SelectedChordAnalysis(
        onset = onset,
        pitches = pitches,
        pitchClasses = pitchClasses.sorted(),
        candidates = ChordRecognizer.recognize(pitches),
    )
}

private fun selectedPitchClasses(selection: Set<EventSection>): Set<Int> =
    selectedPitches(selection).map { it.second.pitchClass.value }.toSet()

private fun selectedPitches(selection: Set<EventSection>): List<Pair<TimeCode, Pitch>> =
    selection.flatMap { section ->
        when (section) {
            is VoiceNoteSection -> listOf(section.event.onset to section.pitchData.pitch)
            is VoiceEventSection -> if (section.event.isRest) emptyList()
                else section.event.pitches.map { section.event.onset to it }
            else -> emptyList()
        }
    }

@Composable
private fun SelectedChordSection(
    ctx: PluginPanelContext,
    analysis: SelectedChordAnalysis?,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
) {
    if (analysis == null) {
        Text(
            i18n("plugin.chord.panel.selectedNotesHint"),
            fontSize = 10.sp,
            color = MeconColors.TextDark
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground)
            .border(1.dp, MeconColors.Border)
            .padding(8.dp)
    ) {
        Text(
            i18n("plugin.chord.panel.selectedNotesChord"),
            fontSize = 10.sp,
            color = MeconColors.TextMuted
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                analysis.candidates.takeIf { it.isNotEmpty() }
                    ?.let { formatChordAlternatives(it.map { candidate -> candidate.chord }, symbolStyle, keySignature) }
                    ?: AnnotatedString(i18n("plugin.chord.panel.unrecognizedSelection")),
                fontSize = 13.sp,
                color = if (analysis.candidates.isNotEmpty()) MeconColors.PrimaryLight else MeconColors.TextSecondary,
            )
            Text(
                analysis.onset.format(),
                fontSize = 10.sp,
                color = MeconColors.TextDark
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            analysis.pitches.joinToString(" ") { it.format() },
            fontSize = 10.sp,
            color = MeconColors.TextSecondary
        )
        if (analysis.candidates.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            val toneLabels = mapOf(
                ChordTone.ROOT to i18n("plugin.chord.panel.tone.root"),
                ChordTone.THIRD to i18n("plugin.chord.panel.tone.third"),
                ChordTone.FIFTH to i18n("plugin.chord.panel.tone.fifth"),
                ChordTone.SEVENTH to i18n("plugin.chord.panel.tone.seventh"),
                ChordTone.SECOND to i18n("plugin.chord.panel.tone.second"),
                ChordTone.FOURTH to i18n("plugin.chord.panel.tone.fourth"),
            )
            val missingLabel = i18n("plugin.chord.panel.missingTones")
            val completeLabel = i18n("plugin.chord.panel.chordComplete")
            val substitutionsLabel = i18n("plugin.chord.panel.enharmonicSubstitutions")
            Text(
                formatCandidateMissingLines(
                    candidates = analysis.candidates,
                    missingLabel = missingLabel,
                    completeLabel = completeLabel,
                    substitutionsLabel = substitutionsLabel,
                    toneLabels = toneLabels,
                    symbolStyle = symbolStyle,
                    keySignature = keySignature,
                ),
                fontSize = 10.sp,
                color = MeconColors.TextMuted
            )
        }
        Spacer(Modifier.height(8.dp))
        ActionButton(
            label = i18n("plugin.chord.panel.addSelectedChord"),
            enabled = analysis.uniqueCandidate != null && ctx.onAddPluginEvent != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val chord = analysis.uniqueCandidate?.chord ?: return@ActionButton
                ctx.addOrReplaceChord(
                    StorageChordEvent.create(
                        onset = analysis.onset,
                        root = chord.root.value,
                        quality = chord.quality,
                        bass = chord.bass?.value,
                    ),
                )
            }
        )
    }
}

@Composable
private fun ChordDiskPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(MeconColors.InputBackground)
            .border(1.dp, MeconColors.Border),
        contentAlignment = Alignment.Center
    ) {
        Text(
            i18n("plugin.chord.panel.noSelection"),
            fontSize = 10.sp,
            color = MeconColors.TextDark
        )
    }
}

@Composable
private fun ChordInputSection(
    ctx: PluginPanelContext,
    selectedChord: StorageChordEvent?,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
) {
    val initialText = remember(selectedChord?.id) {
        selectedChord?.let { ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(it)).symbol } ?: ""
    }
    var text by remember(selectedChord?.id) { mutableStateOf(initialText) }
    val parsed: Parsed? = remember(text) { ChordSymbolParser.parse(text) }

    val canAdd = parsed != null && ctx.targetTimeCode != null
    val canUpdate = parsed != null && selectedChord != null
    val canDelete = selectedChord != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            i18n("plugin.chord.panel.inputHint"),
            fontSize = 10.sp,
            color = MeconColors.TextMuted
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeconColors.InputBackground)
                .border(1.dp, MeconColors.Border)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MeconColors.TextPrimary,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(MeconColors.PrimaryLight),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(6.dp))

        when {
            text.isBlank() -> Spacer(Modifier.height(0.dp))
            parsed == null -> Text(
                i18n("plugin.chord.panel.invalidChord"),
                fontSize = 10.sp,
                color = MeconColors.Red
            )
            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    i18n("plugin.chord.panel.parsedPreview"),
                    fontSize = 10.sp,
                    color = MeconColors.TextMuted
                )
                Text(
                    formatParsed(parsed, symbolStyle, keySignature),
                    fontSize = 11.sp,
                    color = MeconColors.PrimaryLight,
                    fontWeight = if (symbolStyle == ChordSymbolDisplayStyle.LETTER) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (selectedChord != null) {
            // Editing mode: Update and Delete buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionButton(
                    label = i18n("plugin.chord.panel.update"),
                    enabled = canUpdate,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val chord = parsed
                        if (chord != null) {
                            val newEvent = StorageChordEvent.create(
                                onset = selectedChord.onset,
                                root = chord.root,
                                quality = chord.quality,
                                bass = chord.bass
                            )
                            ctx.onUpdatePluginEvent?.invoke(StorageChordEvent.TRACK_TYPE, selectedChord.id, newEvent)
                        }
                    }
                )
                ActionButton(
                    label = i18n("plugin.chord.panel.delete"),
                    enabled = canDelete,
                    modifier = Modifier.weight(1f),
                    tint = MeconColors.Red,
                    onClick = {
                        ctx.onDeletePluginEvent?.invoke(StorageChordEvent.TRACK_TYPE, selectedChord.id)
                    }
                )
            }
        } else {
            // Add mode
            ActionButton(
                label = i18n("plugin.chord.panel.addAtSelection"),
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val tc = ctx.targetTimeCode
                    val chord = parsed
                    if (chord != null && tc != null) {
                        val newEvent = StorageChordEvent.create(
                            onset = tc,
                            root = chord.root,
                            quality = chord.quality,
                            bass = chord.bass
                        )
                        ctx.addOrReplaceChord(newEvent)
                        text = ""
                    }
                }
            )
            if (ctx.targetTimeCode == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    i18n("plugin.chord.panel.noSelectionHint"),
                    fontSize = 9.sp,
                    color = MeconColors.TextDark
                )
            }
        }
    }
}

private fun PluginPanelContext.addOrReplaceChord(event: StorageChordEvent) {
    val existing = runtimeScore
        ?.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
        ?.eventsAt(event.onset)
        ?.firstOrNull()
        ?.storageEvent
    if (existing != null) {
        onUpdatePluginEvent?.invoke(StorageChordEvent.TRACK_TYPE, existing.id, event)
    } else {
        onAddPluginEvent?.invoke(StorageChordEvent.TRACK_TYPE, event)
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MeconColors.PrimaryLight,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .background(
                if (enabled && isHovered) MeconColors.HoverBackground else MeconColors.InputBackground
            )
            .border(1.dp, if (enabled) MeconColors.SelectedBorder else MeconColors.Border)
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (enabled) tint else MeconColors.TextDark,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatParsed(
    p: Parsed,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString =
    formatChord(
        Chord(
            root = PitchClass(p.root),
            quality = p.quality,
            bass = p.bass?.let { PitchClass(it) },
        ),
        symbolStyle,
        keySignature,
    )

private fun formatChord(
    chord: Chord,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString =
    chordSymbolAnnotatedText(
        ChordSymbolFormatter.formatSymbol(chord, symbolStyle, keySignature),
        symbolStyle,
    )

private fun formatChordAlternatives(
    chords: List<Chord>,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString = buildAnnotatedString {
    chords.forEachIndexed { index, chord ->
        if (index > 0) append(" / ")
        append(formatChord(chord, symbolStyle, keySignature))
    }
}

private fun formatCandidateMissingLines(
    candidates: List<ChordRecognitionCandidate>,
    missingLabel: String,
    completeLabel: String,
    substitutionsLabel: String,
    toneLabels: Map<ChordTone, String>,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString = buildAnnotatedString {
    candidates.forEachIndexed { index, candidate ->
        if (index > 0) append("\n")
        append(formatCandidateDetails(candidate, missingLabel, completeLabel, substitutionsLabel, toneLabels, symbolStyle, keySignature))
    }
}

private fun formatCandidateDetails(
    candidate: ChordRecognitionCandidate,
    missingLabel: String,
    completeLabel: String,
    substitutionsLabel: String,
    toneLabels: Map<ChordTone, String>,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString = buildAnnotatedString {
    val missing = candidate.missingTones
    append(formatChord(candidate.chord, symbolStyle, keySignature))
    append(": ")
    if (missing.isEmpty()) {
        append(completeLabel)
    } else {
        append(missingLabel)
        append(" ")
        missing.forEachIndexed { index, tone ->
            if (index > 0) append(", ")
            append(formatMissingTone(tone, toneLabels, symbolStyle, keySignature))
        }
    }
    if (candidate.enharmonicSubstitutions.isNotEmpty()) {
        append("; ")
        append(substitutionsLabel)
        append(" ")
        candidate.enharmonicSubstitutions.forEachIndexed { index, substitution ->
            if (index > 0) append(", ")
            append(toneLabels.getValue(substitution.tone))
            append(" ")
            append(substitution.written.format())
            append(" -> ")
            append(substitution.expected.format())
        }
    }
}

private fun formatMissingTone(
    missing: MissingChordTone,
    toneLabels: Map<ChordTone, String>,
    symbolStyle: ChordSymbolDisplayStyle,
    keySignature: KeySignature,
): AnnotatedString = buildAnnotatedString {
    append(toneLabels.getValue(missing.tone))
    append(" ")
    val part = ChordSymbolFormatter.formatPitchClass(missing.pitchClass, symbolStyle, keySignature)
    if (symbolStyle == ChordSymbolDisplayStyle.SCALE_DEGREE) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
    } else {
        append(part)
    }
}

@Composable
private fun PitchModeToggle(pitchMode: PitchMode, onToggle: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            i18n("toolbar.pitchDisplay"),
            fontSize = 10.sp,
            color = if (isHovered) MeconColors.TextPrimary else MeconColors.TextSecondary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                i18n("toolbar.pitchMode.absolute"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (pitchMode == PitchMode.ABSOLUTE) MeconColors.PrimaryLight else MeconColors.TextDark
            )
            Text(" / ", fontSize = 11.sp, color = MeconColors.TextDark)
            Text(
                i18n("toolbar.pitchMode.solfege"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (pitchMode == PitchMode.RELATIVE) MeconColors.PrimaryLight else MeconColors.TextDark
            )
        }
    }
}

@Composable
private fun ChordSymbolStyleToggle(style: ChordSymbolDisplayStyle, onToggle: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            i18n("plugin.chord.panel.symbolDisplay"),
            fontSize = 10.sp,
            color = if (isHovered) MeconColors.TextPrimary else MeconColors.TextSecondary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                i18n("plugin.chord.panel.symbolDisplay.letter"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (style == ChordSymbolDisplayStyle.LETTER) MeconColors.PrimaryLight else MeconColors.TextDark
            )
            Text(" / ", fontSize = 11.sp, color = MeconColors.TextDark)
            Text(
                i18n("plugin.chord.panel.symbolDisplay.degree"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (style == ChordSymbolDisplayStyle.SCALE_DEGREE) MeconColors.PrimaryLight else MeconColors.TextDark
            )
        }
    }
}

@Composable
private fun TonnetzSubsection(
    displayChord: StorageChordEvent?,
    runtimeScore: com.mecon.api.runtime.RuntimeScore?,
    pitchMode: PitchMode = PitchMode.ABSOLUTE,
    keySignature: KeySignature = KeySignature.C_MAJOR,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Grain,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MeconColors.TextSecondary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                i18n("plugin.chord.panel.tonnetz"),
                fontSize = 11.sp,
                color = MeconColors.TextSecondary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(192.dp)
                .background(MeconColors.InputBackground)
                .border(1.dp, MeconColors.Border)
        ) {
            TonnetzCanvas(
                displayChord = displayChord,
                runtimeScore = runtimeScore,
                pitchMode = pitchMode,
                keySignature = keySignature,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
