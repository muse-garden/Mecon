package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.Fraction
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.components.ChordToneLabelMode
import com.mecon.desktop.uikit.components.CircleOfFifthsPopupMenu
import com.mecon.desktop.uikit.components.MeconChoiceChip
import com.mecon.desktop.uikit.components.MeconSwitch
import com.mecon.desktop.ui.views.rememberIdentityKey
import com.mecon.features.freepractice.PracticePlanView
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayout
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.writing.GrandStaffVoiceLayout
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceChordTonalityOption
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.tonalityOptions

internal data class PracticePlanState(
    val view: com.mecon.features.freepractice.PracticePlanView,
    val voiceCount: Int,
    val staffVoices: GrandStaffVoiceLayout,
    val initialKey: ModulationKey,
    val workspace: HarmonyWorkspaceState,
    val selectedSlotId: WorkspaceSlotId,
    val selectedIdiomInstanceId: WorkspaceIdiomInstanceId?,
    val chordToneMode: ChordToneLabelMode,
    val showOffKeyIdioms: Boolean,
    val selectedIdiomTargetKey: ModulationKey?,
    val insertionOnset: Fraction,
)

internal data class PracticePlanActions(
    val changeVoiceCount: (Int) -> Unit,
    val changeChordToneMode: (ChordToneLabelMode) -> Unit,
    val changeShowOffKeyIdioms: (Boolean) -> Unit,
    val selectIdiomTargetKey: (ModulationKey?) -> Unit,
    val selectIdiomTonalLayout: (WorkspaceTonalLayoutId) -> Unit,
    val replaceChord: (WorkspaceChordChoice) -> Unit,
    val setChordBass: (Int?) -> Unit,
    val setChordTonality: (WorkspaceChordTonality?) -> Unit,
    val selectChordTonalLayout: (WorkspaceTonalLayoutId) -> Unit,
    val setPivotChord: (Boolean) -> Unit,
    val changeStaffVoices: (GrandStaffVoiceLayout) -> Unit,
    val changeInitialKey: (ModulationKey) -> Unit,
    val changeTonalLayoutKey: (WorkspaceTonalLayoutId, ModulationKey) -> Unit,
    val removeTonalLayout: (WorkspaceTonalLayoutId) -> Unit,
    val insertTonalLayout: (ModulationKey, Boolean) -> Unit,
    val insertIdiom: (String, String) -> Unit,
    val selectIdiom: (WorkspaceIdiomInstanceId) -> Unit,
    val replaceIdiom: (
        WorkspaceIdiomInstance,
        String,
        String,
    ) -> Unit,
    val removeIdiom: (WorkspaceIdiomInstanceId) -> Unit,
    val selectSlot: (WorkspaceSlotId) -> Unit,
    val appendChord: () -> Unit,
    val deleteChord: () -> Unit,
)

private enum class IdiomCatalogTab {
    CURRENT_CHORD,
    ALL_TEACHING,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PracticePlanPanel(
    state: PracticePlanState,
    actions: PracticePlanActions,
    modifier: Modifier = Modifier,
    showTonality: Boolean = true,
    showHarmony: Boolean = true,
    showChordDetails: Boolean = true,
    showIdioms: Boolean = true,
    chordDetailsInitiallyCollapsed: Boolean = true,
) {
    val view = state.view
    val strings = view.strings
    val selectedSlot = state.workspace.slots.firstOrNull { it.id == state.selectedSlotId }
    val selectedChordLayout = selectedSlot?.let(state.workspace::selectedTonalLayout)
        ?: state.workspace.tonalLayouts.firstOrNull()
    val activeChordLayouts = selectedSlot?.let { state.workspace.activeTonalLayouts(it.onset) }
        .orEmpty()
        .ifEmpty { listOfNotNull(selectedChordLayout) }
    val chordTonalReadings = selectedSlot?.tonality?.readings.orEmpty()
    val selectedChordKey = chordTonalReadings.firstOrNull()?.key ?: selectedChordLayout?.key
    val chordKeys = (chordTonalReadings.map { it.key } + activeChordLayouts.map { it.key }).distinct()
    val chordGroupsByKey = remember(chordKeys) {
        chordKeys.associateWith(ChordSelectionCatalog::groups)
    }
    val selectedChoice = selectedChordKey?.let { key ->
        selectedPlanChordChoice(
            choices = chordGroupsByKey[key].orEmpty().flatMap { it.chords },
            selectedSlot = selectedSlot,
            interpretationRef = chordTonalReadings.firstOrNull()?.interpretationRef,
        )
    }
    val selectedChordReadings = selectedSlot?.let { slot ->
        if (chordTonalReadings.isNotEmpty()) {
            chordTonalReadings.mapNotNull { reading ->
                chordGroupsByKey[reading.key]
                    .orEmpty()
                    .flatMap { it.chords }
                    .matchingChoice(slot, reading.interpretationRef)
                    ?.let { choice -> PlanChordReading(reading.key, choice) }
            }
        } else activeChordLayouts.mapNotNull { layout ->
            chordGroupsByKey[layout.key]
                .orEmpty()
                .flatMap { it.chords }
                .matchingChoice(slot)
                ?.let { choice -> PlanChordReading(layout.key, choice) }
        }
    }.orEmpty()
    val selectedIndex = selectedSlot?.let(state.workspace.slots::indexOf) ?: -1
    val selectedSlotGuidance = selectedSlot?.let { slot ->
        idiomGuidanceForSlot(
            workspace = state.workspace,
            slotId = slot.id,
            definitions = view.idiomCatalog.definitions,
        )
    } ?: IdiomGuidanceState()
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTonality && selectedSlot != null && selectedChordLayout != null) {
            var editingLayoutId by remember(selectedSlot.id) {
                mutableStateOf<WorkspaceTonalLayoutId?>(null)
            }
            var insertKeyMenuExpanded by remember(selectedSlot.id) { mutableStateOf(false) }
            var terminatePrevious by remember(selectedSlot.id) { mutableStateOf(true) }
            val editingLayout = activeChordLayouts.firstOrNull { it.id == editingLayoutId }
            WorkbenchPanel(strings.currentTonalityTitle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            activeChordLayouts.forEach { layout ->
                                val layoutView = view.activeTonalLayouts.firstOrNull { it.id == layout.id }
                                    ?: return@forEach
                                TonalLayoutRow(
                                    layout = layoutView,
                                    selected = layout.id == selectedChordLayout.id,
                                    onSelect = { editingLayoutId = layout.id },
                                    onDelete = layout.takeUnless { it.isBaseline }?.let {
                                        { actions.removeTonalLayout(it.id) }
                                    },
                                )
                            }
                        }
                        CircleOfFifthsPopupMenu(
                            expanded = editingLayout != null,
                            onDismissRequest = { editingLayoutId = null },
                            currentKey = (editingLayout ?: selectedChordLayout).key.toFifthsKey(),
                            title = strings.editTonalLayoutTitle,
                            label = { key -> key.toModulationKey().displayLabel() },
                            onKeyClick = { key ->
                                editingLayout?.let { layout ->
                                    actions.changeTonalLayoutKey(layout.id, key.toModulationKey())
                                }
                                editingLayoutId = null
                            },
                        )
                    }
                    Box {
                        MeconChoiceChip(
                            label = strings.insertTonalLayout,
                            selected = false,
                            modifier = Modifier.height(32.dp),
                            onClick = {
                                terminatePrevious = true
                                insertKeyMenuExpanded = true
                            },
                        )
                        CircleOfFifthsPopupMenu(
                            expanded = insertKeyMenuExpanded,
                            onDismissRequest = { insertKeyMenuExpanded = false },
                            currentKey = selectedChordLayout.key.toFifthsKey(),
                            title = strings.insertTonalLayoutTitle,
                            terminatePrevious = terminatePrevious,
                            onTerminatePreviousChange = { terminatePrevious = it },
                            label = { key -> key.toModulationKey().displayLabel() },
                            onKeyClick = { key ->
                                actions.insertTonalLayout(key.toModulationKey(), terminatePrevious)
                                insertKeyMenuExpanded = false
                            },
                        )
                    }
                }
                Text(
                    strings.tonalLayoutHelp,
                    color = MeconColors.TextDark,
                    fontSize = 10.sp,
                )
            }
        }

        if ((showHarmony || showChordDetails) && selectedSlot != null && selectedChordLayout != null) {
            val effectiveChordLayout = selectedChordKey?.let { key ->
                selectedChordLayout.copy(
                    fifths = key.fifths,
                    mode = WorkspaceKeyMode.fromTheory(key.mode),
                )
            } ?: selectedChordLayout
            val effectiveGroupsByKey = selectedChordKey?.let { key ->
                mapOf(key to chordGroupsByKey[key].orEmpty())
            }.orEmpty()
            val catalogLayouts = if (chordTonalReadings.isEmpty()) {
                activeChordLayouts
            } else {
                listOf(effectiveChordLayout)
            }
            val catalogGroupsByKey = if (chordTonalReadings.isEmpty()) {
                chordGroupsByKey
            } else {
                effectiveGroupsByKey
            }
            if (showHarmony) WorkbenchPanel(strings.harmonySelectionTitle) {
                SelectedChordHeader(
                    readings = view.selectedChordReadings,
                    fallbackSymbol = view.selectedChord?.symbol ?: selectedSlot.chordIdentity,
                    emptySymbol = strings.selectedChordEmpty,
                    emptyTones = strings.chordTonesEmpty,
                    toneMode = state.chordToneMode,
                    canGoPrevious = selectedIndex > 0,
                    canGoNext = selectedIndex in 0 until state.workspace.slots.lastIndex,
                    canDelete = view.selectedSlot?.capabilities?.canRemove == true,
                    onPrevious = {
                        state.workspace.slots.getOrNull(selectedIndex - 1)?.id?.let(actions.selectSlot)
                    },
                    onNext = {
                        state.workspace.slots.getOrNull(selectedIndex + 1)?.id?.let(actions.selectSlot)
                    },
                    onLast = { actions.selectSlot(state.workspace.slots.last().id) },
                    onAppend = actions.appendChord,
                    onDelete = actions.deleteChord,
                    previousLabel = view.navigation.previousLabel,
                    nextLabel = view.navigation.nextLabel,
                    lastLabel = view.navigation.lastLabel,
                    appendLabel = view.navigation.appendLabel,
                    deleteLabel = view.navigation.removeChord,
                )
                CoveredIdiomList(view, actions)
                OffKeyOperations(
                    view = view,
                    toneMode = state.chordToneMode,
                    onSetTonality = actions.setChordTonality,
                )
                ChordToolbar(
                    selectedChordChoice = selectedSlot.chordChoice,
                    selectedInterpretationRef = chordTonalReadings.firstOrNull()?.interpretationRef
                        ?: selectedSlot.chordInterpretationRef,
                    legacyChordSymbol = selectedSlot.chordIdentity,
                    groups = selectedChordKey?.let(chordGroupsByKey::get).orEmpty(),
                    catalogGroups = view.chordCatalogGroups,
                    groupsByKey = catalogGroupsByKey,
                    activeLayouts = catalogLayouts,
                    selectedLayoutId = selectedChordLayout.id,
                    isPivotChord = selectedSlot.isPivotChord,
                    pivotRecipes = view.idiomCatalog.pivotRecipes,
                    chordLocked = state.workspace.isIdiomSlot(selectedSlot.id),
                    inversionLocked = state.workspace.isIdiomInversionLocked(selectedSlot.id),
                    customaryBassGuidance = selectedSlotGuidance.customaryBassPitchClasses,
                    cadentialDominantGuidance = selectedSlotGuidance.cadentialDominant,
                    defaultBassToRoot = selectedSlot.id == state.workspace.slots.first().id,
                    toneMode = state.chordToneMode,
                    onToneModeChange = actions.changeChordToneMode,
                    onChord = actions.replaceChord,
                    onBass = actions.setChordBass,
                    onSelectLayout = actions.selectChordTonalLayout,
                    onSetPivot = actions.setPivotChord,
                    showDetails = false,
                )
            }

            if (showChordDetails) WorkbenchPanel(
                strings.chordDetailTitle,
                initiallyCollapsed = chordDetailsInitiallyCollapsed,
            ) {
                ChordToolbar(
                    selectedChordChoice = selectedSlot.chordChoice,
                    selectedInterpretationRef = chordTonalReadings.firstOrNull()?.interpretationRef
                        ?: selectedSlot.chordInterpretationRef,
                    legacyChordSymbol = selectedSlot.chordIdentity,
                    groups = selectedChordKey?.let(chordGroupsByKey::get).orEmpty(),
                    catalogGroups = view.chordCatalogGroups,
                    groupsByKey = catalogGroupsByKey,
                    activeLayouts = catalogLayouts,
                    selectedLayoutId = selectedChordLayout.id,
                    isPivotChord = selectedSlot.isPivotChord,
                    pivotRecipes = view.idiomCatalog.pivotRecipes,
                    chordLocked = state.workspace.isIdiomSlot(selectedSlot.id),
                    inversionLocked = state.workspace.isIdiomInversionLocked(selectedSlot.id),
                    customaryBassGuidance = selectedSlotGuidance.customaryBassPitchClasses,
                    cadentialDominantGuidance = selectedSlotGuidance.cadentialDominant,
                    defaultBassToRoot = selectedSlot.id == state.workspace.slots.first().id,
                    toneMode = state.chordToneMode,
                    onToneModeChange = actions.changeChordToneMode,
                    onChord = actions.replaceChord,
                    onBass = actions.setChordBass,
                    onSelectLayout = actions.selectChordTonalLayout,
                    onSetPivot = actions.setPivotChord,
                    showSelector = false,
                    sharedDetail = view.chordDetail,
                )
            }
        }

        if (showIdioms) WorkbenchPanel(strings.idiomTitle) {
            val hasConfiguredChord = selectedSlot?.let { slot ->
                slot.chordChoice != null ||
                    slot.chordInterpretationRef != null ||
                    slot.chordIdentity != null
            } == true
            var selectedIdiomTab by remember(selectedSlot?.id, hasConfiguredChord) {
                mutableStateOf(
                    if (hasConfiguredChord) {
                        IdiomCatalogTab.CURRENT_CHORD
                    } else {
                        IdiomCatalogTab.ALL_TEACHING
                    },
                )
            }
            if (view.idiomCatalogFilters.size > 1) {
                Text(strings.idiomCatalogTonality, color = MeconColors.TextMuted, fontSize = 10.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    view.idiomCatalogFilters.forEach { filter ->
                        PracticeChip(filter.label, filter.selected) {
                            actions.selectIdiomTonalLayout(filter.tonalLayoutId)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(strings.showOffKeyIdioms, color = MeconColors.TextPrimary, fontSize = 11.sp)
                }
                MeconSwitch(checked = state.showOffKeyIdioms, onCheckedChange = actions.changeShowOffKeyIdioms)
            }
            if (state.showOffKeyIdioms && view.idiomTargetKeys.isNotEmpty()) {
                Text(strings.filterTargetKey, color = MeconColors.TextMuted, fontSize = 10.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PracticeChip(strings.allTargetKeys, state.selectedIdiomTargetKey == null) {
                        actions.selectIdiomTargetKey(null)
                    }
                    view.idiomTargetKeys.forEach { filter ->
                        val key = filter.key?.let {
                            ModulationKey(it.fifths, it.mode.toTheory())
                        } ?: return@forEach
                        PracticeChip(
                            filter.label,
                            state.selectedIdiomTargetKey == key,
                        ) { actions.selectIdiomTargetKey(key) }
                    }
                }
            }
            fun List<com.mecon.features.freepractice.PracticeIdiomDefinitionView>.forSelectedTarget(
                includeVariant: (com.mecon.features.freepractice.PracticeIdiomVariantView) -> Boolean,
            ) =
                mapNotNull { definition ->
                    definition.variants.filter { variant ->
                        includeVariant(variant) && run {
                            val selected = state.selectedIdiomTargetKey
                            selected == null || variant.suggestedKey?.let {
                                it.fifths == selected.fifths && it.mode.toTheory() == selected.mode
                            } == true
                        }
                    }.takeIf { it.isNotEmpty() }?.let { definition.copy(variants = it) }
                }
            val focusedIdioms = view.idiomCatalog.definitions
                .filter { it.relatedToFocus }
                .forSelectedTarget { it.relatedToFocus }
            val defaultIdioms = view.idiomCatalog.definitions
                .filter { it.availableByDefault }
                .forSelectedTarget { it.availableByDefault }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (hasConfiguredChord) {
                    IdiomCatalogTab(
                        label = strings.relatedIdioms,
                        selected = selectedIdiomTab == IdiomCatalogTab.CURRENT_CHORD,
                        accent = MeconColors.Orange,
                        onClick = { selectedIdiomTab = IdiomCatalogTab.CURRENT_CHORD },
                        modifier = Modifier.weight(1f),
                    )
                }
                IdiomCatalogTab(
                    label = strings.allIdioms,
                    selected = selectedIdiomTab == IdiomCatalogTab.ALL_TEACHING,
                    accent = MeconColors.Primary,
                    onClick = { selectedIdiomTab = IdiomCatalogTab.ALL_TEACHING },
                    modifier = Modifier.weight(1f),
                )
            }
            when (selectedIdiomTab) {
                IdiomCatalogTab.CURRENT_CHORD -> {
                    if (view.idiomCatalog.loading) {
                        TeachingMaterialLoading(strings.loadingRelatedIdioms)
                    }
                    if (focusedIdioms.isNotEmpty()) {
                        IdiomDefinitionList(focusedIdioms, state, actions)
                    } else if (!view.idiomCatalog.loading) {
                        Text(strings.noRelatedIdioms, color = MeconColors.TextDark, fontSize = 11.sp)
                    }
                }

                IdiomCatalogTab.ALL_TEACHING -> {
                    if (view.idiomCatalog.loading) {
                        TeachingMaterialLoading(strings.loadingAllIdioms)
                    }
                    if (defaultIdioms.isNotEmpty()) {
                        IdiomDefinitionList(defaultIdioms, state, actions)
                    } else if (!view.idiomCatalog.loading) {
                        Text(strings.noAllIdioms, color = MeconColors.TextDark, fontSize = 11.sp)
                    }
                }
            }
        }

    }
}

@Composable
private fun RowScope.IdiomCatalogTab(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) accent else MeconColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun selectedPlanChordChoice(
    choices: Iterable<ChordSelectionChoice>,
    selectedSlot: WorkspaceHarmonySlot?,
    interpretationRef: com.mecon.theory.harmony.ChordInterpretationRef? = null,
): ChordSelectionChoice? = selectedSlot?.let { slot ->
    choices.matchingChoice(slot, interpretationRef)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OffKeyOperations(
    view: PracticePlanView,
    toneMode: ChordToneLabelMode,
    onSetTonality: (WorkspaceChordTonality?) -> Unit,
) {
    if (view.continuationTonalityChoices.isEmpty() && view.doubleTonalityChoices.isEmpty()
    ) return
    val strings = view.strings
    var showCandidates by remember(view.selectedSlotId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.offKey, color = MeconColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        if (view.continuationTonalityChoices.isNotEmpty()) {
            Text(strings.continueTemporaryTonality, color = MeconColors.TextPrimary, fontSize = 11.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                view.continuationTonalityChoices.forEach { option ->
                    MeconChoiceChip(
                        label = option.displayLabel,
                        selected = option.selected,
                        accent = if (option.tonality == null) MeconColors.Emerald else MeconColors.Orange,
                        onClick = { if (!view.chordLocked) onSetTonality(option.tonality) },
                    )
                }
            }
            Text(
                strings.temporaryTonalityHelp,
                color = MeconColors.TextDark,
                fontSize = 10.sp,
            )
        }

        if (!view.chordLocked && view.doubleTonalityChoices.isNotEmpty()) {
            MeconChoiceChip(
                label = if (showCandidates) strings.collapseDoubleTonality else strings.createDoubleTonality,
                selected = showCandidates,
                accent = MeconColors.Orange,
                onClick = { showCandidates = !showCandidates },
            )
        }
        if (showCandidates && !view.chordLocked) {
            view.doubleTonalityChoices.forEach { option ->
                PracticeSelectionRow(
                    selected = false,
                    accent = MeconColors.Orange,
                    onSelect = {
                        onSetTonality(option.tonality)
                        showCandidates = false
                    },
                    onDelete = null,
                    deleteDescription = "",
                ) {
                    Text(
                        option.keyLabel,
                        color = MeconColors.OrangeLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (toneMode == ChordToneLabelMode.RELATIVE) option.relativeTonesLabel
                        else option.absoluteTonesLabel,
                        color = MeconColors.TextPrimary,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        option.directionLabel,
                        color = MeconColors.TextDark,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        if (view.chordLocked) {
            Text(strings.lockedTonalityHelp, color = MeconColors.TextDark, fontSize = 10.sp)
        }
    }
}

private data class PlanChordReading(
    val key: ModulationKey,
    val choice: ChordSelectionChoice,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SelectedChordHeader(
    readings: List<com.mecon.features.freepractice.PracticeTimelineChordReadingView>,
    fallbackSymbol: String?,
    emptySymbol: String,
    emptyTones: String,
    toneMode: ChordToneLabelMode,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    canDelete: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onAppend: () -> Unit,
    onDelete: () -> Unit,
    previousLabel: String,
    nextLabel: String,
    lastLabel: String,
    appendLabel: String,
    deleteLabel: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (readings.isEmpty()) {
                Text(
                    fallbackSymbol ?: emptySymbol,
                    color = MeconColors.OrangeLight,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(emptyTones, color = MeconColors.TextPrimary, fontSize = 11.sp)
            } else {
                readings.forEach { reading ->
                    CurrentChordReading(
                        reading = reading,
                        toneMode = toneMode,
                    )
                }
            }
        }
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = previousLabel,
                tint = if (canGoPrevious) MeconColors.IconDefault else MeconColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = nextLabel,
                tint = if (canGoNext) MeconColors.IconDefault else MeconColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = if (canGoNext) onLast else onAppend,
            modifier = Modifier.size(28.dp),
        ) {
            if (canGoNext) {
                Icon(
                    Icons.AutoMirrored.Filled.LastPage,
                    contentDescription = lastLabel,
                    tint = MeconColors.IconDefault,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Icon(
                    Icons.Default.Add,
                    contentDescription = appendLabel,
                    tint = MeconColors.PrimaryLight,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (canDelete) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = deleteLabel,
                    tint = MeconColors.OrangeLight,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun CurrentChordReading(
    reading: com.mecon.features.freepractice.PracticeTimelineChordReadingView,
    toneMode: ChordToneLabelMode,
) {
    val tonesLabel = when (toneMode) {
        ChordToneLabelMode.RELATIVE -> reading.relativeTonesLabel
        ChordToneLabelMode.ABSOLUTE -> reading.absoluteTonesLabel
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            reading.symbolLabel,
            color = MeconColors.OrangeLight,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            tonesLabel,
            color = MeconColors.TextPrimary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun CoveredIdiomList(
    view: com.mecon.features.freepractice.PracticePlanView,
    actions: PracticePlanActions,
) {
    if (view.coveredIdiomRows.isEmpty()) return
    Text(view.strings.coveredIdioms, color = MeconColors.TextMuted, fontSize = 10.sp)
    view.coveredIdiomRows.forEach { row ->
        PracticeSelectionRow(
            selected = row.startsHere,
            accent = MeconColors.Orange,
            onSelect = { actions.selectIdiom(row.id) },
            onDelete = { actions.removeIdiom(row.id) },
            deleteDescription = view.strings.removeIdiom,
        ) {
                Text(
                    row.displayLabel,
                    modifier = Modifier.weight(1f),
                    color = if (row.startsHere) MeconColors.OrangeLight else MeconColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (row.startsHere) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}

@Composable
private fun TeachingMaterialLoading(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MeconColors.PrimaryLight,
        )
        Text(label, color = MeconColors.PrimaryLight, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdiomDefinitionList(
    definitions: List<com.mecon.features.freepractice.PracticeIdiomDefinitionView>,
    state: PracticePlanState,
    actions: PracticePlanActions,
) {
    definitions.forEach { definition ->
        Text(
            definition.title,
            color = MeconColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            definition.variants.forEach { variant ->
                PracticeChip(
                    variant.displayLabel,
                    state.selectedIdiomInstanceId?.let { selectedId ->
                        state.workspace.idiomInstances.firstOrNull { it.id == selectedId }
                    }?.let { selected ->
                        selected.definitionId == definition.id && selected.variantId == variant.id
                    } == true,
                    if (variant.enabled) MeconColors.Primary else MeconColors.TextDark,
                ) {
                    if (variant.enabled) {
                        val selected = state.selectedIdiomInstanceId?.let { selectedId ->
                            state.workspace.idiomInstances.firstOrNull { it.id == selectedId }
                        }
                        if (selected == null) {
                            actions.insertIdiom(definition.id, variant.id)
                        } else {
                            actions.replaceIdiom(selected, definition.id, variant.id)
                        }
                    }
                }
            }
        }
    }
}

private data class IdiomGuidanceState(
    val customaryBassPitchClasses: Set<Int> = emptySet(),
    val cadentialDominant: Boolean = false,
)

private fun idiomGuidanceForSlot(
    workspace: HarmonyWorkspaceState,
    slotId: WorkspaceSlotId,
    definitions: List<com.mecon.features.freepractice.PracticeIdiomDefinitionView>,
): IdiomGuidanceState {
    val selectedSlot = workspace.slots.firstOrNull { it.id == slotId } ?: return IdiomGuidanceState()
    val customaryBassPitchClasses = linkedSetOf<Int>()
    var cadentialDominant = false
    workspace.idiomInstancesForSlot(slotId).forEach { instance ->
        val variant = definitions.asSequence()
            .filter { it.id == instance.definitionId }
            .flatMap { it.variants.asSequence() }
            .firstOrNull { it.id == instance.variantId }
            ?: return@forEach
        val instanceStart = instance.slotIds.mapNotNull { memberId ->
            workspace.slots.firstOrNull { it.id == memberId }?.onset
        }.minOrNull() ?: return@forEach
        val relativeOnset = selectedSlot.onset - instanceStart
        var stepStart = Fraction.ZERO
        val stepIndex = variant.durations.indexOfFirst { duration ->
            val contains = relativeOnset >= stepStart && relativeOnset < stepStart + duration
            stepStart += duration
            contains
        }
        if (stepIndex in variant.customaryBassStepIndices) {
            variant.chordChoices.getOrNull(stepIndex)?.bassPitchClass?.let {
                customaryBassPitchClasses += it
            }
        }
        if (stepIndex in variant.avoidSecondInversionStepIndices) cadentialDominant = true
    }
    return IdiomGuidanceState(customaryBassPitchClasses, cadentialDominant)
}

@Composable
private fun TonalLayoutRow(
    layout: com.mecon.features.freepractice.PracticeTonalLayoutView,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    PracticeSelectionRow(
        selected = selected,
        accent = MeconColors.Primary,
        onSelect = onSelect,
        onDelete = onDelete,
        deleteDescription = "删除调性线",
    ) {
        Text(layout.keyLabel, color = MeconColors.TextPrimary, fontSize = 11.sp)
        Spacer(Modifier.width(7.dp))
        Text(
            layout.rangeLabel,
            color = MeconColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
        )
        layout.baselineLabel?.let { Text(it, color = MeconColors.PrimaryLight, fontSize = 10.sp) }
    }
}

/** Shared compact row used by current tonal layouts and inserted customary progressions. */
@Composable
private fun PracticeSelectionRow(
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    deleteDescription: String,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable(onClick = onSelect),
        color = if (selected) accent.copy(alpha = 0.18f) else MeconColors.SurfaceDark,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (selected) accent else MeconColors.Border),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = deleteDescription,
                        tint = if (selected) accent else MeconColors.TextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

private fun Fraction.timelineLabel(): String =
    "${(toDouble() * 4.0).let { value ->
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }} 拍"

private fun ModulationKey.displayLabel(): String =
    displayName + if (mode == KeySignatureMode.MINOR) "m" else ""
