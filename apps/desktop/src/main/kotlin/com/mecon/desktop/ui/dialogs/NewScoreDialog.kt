package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.*
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.desktop.ui.components.KeySignaturePicker
import com.mecon.desktop.ui.components.TimeSignaturePicker
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.desktop.uikit.components.MeconTextField
import com.mecon.desktop.uikit.components.MeconNumberField
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

private data class NewScoreCreditsDraft(
    val title: String,
    val subtitle: String,
    val composer: String,
    val arranger: String,
    val lyricist: String,
    val copyright: String,
)

private data class NewScoreNotationDraft(
    val keySignature: KeySignature,
    val timeSignature: TimeSignature,
    val tempo: String,
    val measureCount: String,
)

private data class NewScorePageDraft(
    val paginated: Boolean,
    val paperPreset: PaperPreset?,
)

private data class NewScoreMetadataDraft(
    val credits: NewScoreCreditsDraft,
    val notation: NewScoreNotationDraft,
    val page: NewScorePageDraft,
)

@Composable
fun NewScoreDialog(onCreate: (StorageScore) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("Untitled") }
    var subtitle by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var arranger by remember { mutableStateOf("") }
    var lyricist by remember { mutableStateOf("") }
    var copyright by remember { mutableStateOf("") }
    var keySignature by remember { mutableStateOf(KeySignature.C_MAJOR) }
    var timeSignature by remember { mutableStateOf(TimeSignature.COMMON) }
    var tempo by remember { mutableStateOf("120") }
    var measureCount by remember { mutableStateOf("4") }
    var paginated by remember { mutableStateOf(false) }
    var paperPreset by remember { mutableStateOf(PaperPreset.ALL.firstOrNull()) }

    var selectedPresetId by remember { mutableStateOf("piano") }
    var instruments by remember {
        mutableStateOf(ScorePresetCatalog.all.first { it.id == "piano" }.instruments)
    }
    // Per-instrument multi-staff braces are automatic and therefore not editable here.
    var groups by remember { mutableStateOf(emptyList<EditableGroup>()) }

    fun applyPreset(preset: ScorePreset) {
        selectedPresetId = preset.id
        instruments = preset.instruments
        groups = preset.editableGroups()
    }

    fun groupTemplates(): List<StaffGroupTemplate> {
        val starts = buildList {
            var total = 0
            instruments.forEach { add(total); total += it.staves.size }
        }
        return groups.map { group ->
            StaffGroupTemplate(
                startStaffIndex = starts[group.startInstrument],
                endStaffIndex = starts[group.endInstrument] + instruments[group.endInstrument].staves.size - 1,
                bracket = group.bracket,
                barlineConnect = group.bracket == BracketStyle.BRACE
            )
        }
    }

    fun groupsValid(): Boolean = groups.areLaminar() && groups.all { group ->
        group.startInstrument in instruments.indices &&
            group.endInstrument in instruments.indices &&
            group.startInstrument <= group.endInstrument
    }

    fun buildScore(preview: Boolean = false): StorageScore {
        val page = PageLayoutConfig.DEFAULT.copy(paginated = paginated).let { base ->
            paperPreset?.let { base.withPreset(it) } ?: base
        }
        return StorageScore.create(StorageScore.CreationOptions(
            title = if (preview) "" else title.ifBlank { "Untitled" },
            timeSignature = timeSignature,
            keySignature = keySignature,
            tempo = tempo.toFloatOrNull() ?: 120f,
            subtitle = subtitle.ifBlank { null },
            composer = composer.ifBlank { null },
            arranger = arranger.ifBlank { null },
            lyricist = lyricist.ifBlank { null },
            copyright = copyright.ifBlank { null },
            measureCount = if (preview) 1 else measureCount.toIntOrNull()?.coerceAtLeast(1) ?: 4,
            pageLayout = page,
            instrumentTemplates = instruments,
            groupTemplates = groupTemplates(),
            orchestrationEnabled = true,
        ))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(0.97f),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.DialogBackground,
            tonalElevation = 10.dp
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(i18n("dialog.new.title"), style = MaterialTheme.typography.headlineSmall, color = MeconColors.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = MeconColors.IconDefault)
                    }
                }
                HorizontalDivider(color = MeconColors.BorderLight)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogColumn(i18n("dialog.new.templates"), Modifier.weight(0.50f)) {
                        PresetTree(selectedPresetId, onSelect = ::applyPreset)
                    }
                    DialogColumn(i18n("dialog.new.instrumentEditor"), Modifier.weight(2.05f)) {
                        InstrumentEditor(
                            instruments = instruments,
                            groups = groups,
                            onInstrumentsChange = {
                                instruments = it
                                selectedPresetId = ""
                            },
                            onGroupsChange = {
                                groups = it
                                selectedPresetId = ""
                            }
                        )
                    }
                    DialogColumn(i18n("dialog.new.preview"), Modifier.weight(0.78f)) {
                        val preview = remember(instruments, groups, keySignature, timeSignature) {
                            runCatching { RuntimeScore.fromStorage(buildScore(preview = true)) }.getOrNull()
                        }
                        Box(
                            Modifier.fillMaxSize()
                                .background(MeconColors.ScoreBackground, RoundedCornerShape(5.dp))
                                .border(1.dp, MeconColors.BorderLight, RoundedCornerShape(5.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            SimpleScoreView(
                                score = preview,
                                modifier = Modifier.fillMaxSize(),
                                alignment = Alignment.Center,
                                maxScale = 0.82f,
                                fitScale = 0.94f,
                                foreground = MeconColors.ScoreInk
                            )
                            Text(
                                "固定比例 · 大型编制仅缩小",
                                color = MeconColors.TextMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomEnd)
                                    .background(MeconColors.DialogBackground, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                    DialogColumn(i18n("dialog.new.metadata"), Modifier.weight(1.02f)) {
                        MetadataEditor(
                            draft = NewScoreMetadataDraft(
                                credits = NewScoreCreditsDraft(
                                    title, subtitle, composer, arranger, lyricist, copyright,
                                ),
                                notation = NewScoreNotationDraft(
                                    keySignature, timeSignature, tempo, measureCount,
                                ),
                                page = NewScorePageDraft(paginated, paperPreset),
                            ),
                            onCreditsChange = {
                                title = it.title
                                subtitle = it.subtitle
                                composer = it.composer
                                arranger = it.arranger
                                lyricist = it.lyricist
                                copyright = it.copyright
                            },
                            onNotationChange = {
                                keySignature = it.keySignature
                                timeSignature = it.timeSignature
                                tempo = it.tempo
                                measureCount = it.measureCount
                            },
                            onPageChange = {
                                paginated = it.paginated
                                paperPreset = it.paperPreset
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${instruments.size} 件乐器 · ${instruments.sumOf { it.staves.size }} 个谱表 · ${groups.size} 个分组",
                        color = if (groupsValid()) MeconColors.TextMuted else MeconColors.Danger,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MeconColors.IconDefault)
                    ) { Text(i18n("dialog.page.cancel")) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = instruments.isNotEmpty() && groupsValid(),
                        onClick = { onCreate(buildScore()); onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeconColors.Primary,
                            contentColor = MeconColors.SelectedIconOnSurface
                        )
                    ) { Text(i18n("dialog.new.create")) }
                }
            }
        }
    }
}

@Composable
private fun DialogColumn(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxHeight()
            .border(1.dp, MeconColors.Border, RoundedCornerShape(6.dp))
            .padding(11.dp)
    ) {
        Text(title, color = MeconColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(9.dp))
        content()
    }
}

@Composable
private fun PresetTree(selectedId: String, onSelect: (ScorePreset) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScorePresetCatalog.categories.forEach { category ->
            Text(
                i18n(category), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            ScorePresetCatalog.all.filter { it.category == category }.forEach { preset ->
                val selected = preset.id == selectedId
                Text(
                    i18n(preset.label),
                    color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (selected) MeconColors.SelectedSurface else MeconColors.Transparent, RoundedCornerShape(5.dp))
                        .clickable { onSelect(preset) }
                        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MetadataEditor(
    draft: NewScoreMetadataDraft,
    onCreditsChange: (NewScoreCreditsDraft) -> Unit,
    onNotationChange: (NewScoreNotationDraft) -> Unit,
    onPageChange: (NewScorePageDraft) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NewScoreTextField(i18n("dialog.new.titleField"), draft.credits.title) {
            onCreditsChange(draft.credits.copy(title = it))
        }
        NewScoreTextField(i18n("dialog.new.subtitle"), draft.credits.subtitle) {
            onCreditsChange(draft.credits.copy(subtitle = it))
        }
        NewScoreTextField(i18n("dialog.new.composer"), draft.credits.composer) {
            onCreditsChange(draft.credits.copy(composer = it))
        }
        NewScoreTextField(i18n("dialog.new.arranger"), draft.credits.arranger) {
            onCreditsChange(draft.credits.copy(arranger = it))
        }
        NewScoreTextField(i18n("dialog.new.lyricist"), draft.credits.lyricist) {
            onCreditsChange(draft.credits.copy(lyricist = it))
        }
        NewScoreTextField(i18n("dialog.new.copyright"), draft.credits.copyright) {
            onCreditsChange(draft.credits.copy(copyright = it))
        }
        SectionLabel(i18n("dialog.new.key"))
        KeySignaturePicker(
            draft.notation.keySignature,
            Clef.TREBLE,
            { onNotationChange(draft.notation.copy(keySignature = it)) },
            Modifier.fillMaxWidth(),
        )
        SectionLabel(i18n("dialog.new.time"))
        TimeSignaturePicker(
            draft.notation.timeSignature,
            { onNotationChange(draft.notation.copy(timeSignature = it)) },
            Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeconNumberField(
                value = draft.notation.tempo,
                modifier = Modifier.weight(1f),
                label = "BPM",
                onValueChange = {
                onNotationChange(draft.notation.copy(tempo = it))
                },
            )
            MeconNumberField(
                value = draft.notation.measureCount,
                modifier = Modifier.weight(1f),
                label = i18n("dialog.new.measures"),
                onValueChange = {
                onNotationChange(draft.notation.copy(measureCount = it))
                },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(i18n("dialog.page.paginate"), color = MeconColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                draft.page.paginated,
                { onPageChange(draft.page.copy(paginated = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MeconColors.SelectedIconOnSurface,
                    checkedTrackColor = MeconColors.Primary,
                    uncheckedThumbColor = MeconColors.TextMuted,
                    uncheckedTrackColor = MeconColors.InputBackground,
                    uncheckedBorderColor = MeconColors.BorderLight
                )
            )
        }
        if (draft.page.paginated) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperPreset.ALL.forEach { preset ->
                    FilterChip(
                        selected = draft.page.paperPreset?.displayName == preset.displayName,
                        onClick = { onPageChange(draft.page.copy(paperPreset = preset)) },
                        label = { Text(preset.displayName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MeconColors.InputBackground,
                            labelColor = MeconColors.TextSecondary,
                            selectedContainerColor = MeconColors.SelectedSurface,
                            selectedLabelColor = MeconColors.SelectedIconOnSurface
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MeconColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun NewScoreTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    MeconTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = true,
        modifier = modifier.padding(bottom = 6.dp),
    )
}
