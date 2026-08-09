@file:OptIn(ExperimentalLayoutApi::class)

package com.mecon.desktop.ui.exploration

import com.mecon.desktop.uikit.components.MeconLabeledSwitch
import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.i18n.ruleLabel
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.FormField
import com.mecon.exploration.FormFieldKind
import com.mecon.exploration.FormSpec
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.SymbolicProgression
import com.mecon.exploration.SchoenbergChordFilterSpec
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleDegreePair
import com.mecon.theory.RuleExampleInputSpec
import com.mecon.theory.RuleId
import com.mecon.theory.SelectionContext
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergExerciseGroup
import com.mecon.theory.schoenberg.SchoenbergExerciseSelectionKeys
import com.mecon.theory.schoenberg.SchoenbergSecondaryDominantChapter
import com.mecon.theory.schoenberg.SchoenbergSecondaryHarmonyChoice
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhChapter
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhChordChoice
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhUsageChoice
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@Composable
internal fun ExplorationInputPanel(
    state: ExplorationInputState,
    actions: ExplorationInputActions,
) {
    Surface(color = MeconColors.Surface, shape = RoundedCornerShape(8.dp)) {
        if (state.mode == ExplorationMode.MODULATION) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeSelector(
                    mode = state.mode,
                    selectedRule = state.selectedRule,
                    isStale = state.run.stale,
                    onModeChange = actions.changeMode,
                    onSelectRule = actions.selectRule,
                )
                ModulationEditor(state.modulation, actions.modulation)
                RunControls(
                    isStale = state.run.stale,
                    running = state.run.running,
                    candidateCount = state.run.candidateCount,
                    diversify = state.run.diversify,
                    enabled = state.run.enabled,
                    onCandidateCount = actions.run.changeCandidateCount,
                    onDiversify = actions.run.changeDiversify,
                    onReroll = actions.run.reroll,
                    onRun = actions.run.run,
                )
            }
            return@Surface
        }
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RuleTreePanel(
                selectedRuleId = state.selectedRule,
                onSelectRule = actions.selectRule,
                modifier = Modifier.width(330.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeSelector(
                    mode = state.mode,
                    selectedRule = state.selectedRule,
                    isStale = state.run.stale,
                    onModeChange = actions.changeMode,
                    onSelectRule = actions.selectRule,
                )

                when (state.mode) {
                    ExplorationMode.RULE_EXAMPLE -> RuleExampleEditor(
                        formSpec = state.activeForm,
                        selectedRule = state.selectedRule,
                        key = state.key,
                        state = state.ruleExample,
                        keyActions = actions.key,
                        actions = actions.ruleExample,
                    )
                    ExplorationMode.PROGRESSION -> ProgressionEditor(
                        formSpec = state.activeForm,
                        text = state.progression.text,
                        onTextChange = actions.progression.changeText,
                        policyId = state.progression.policyId,
                        onPolicyChange = actions.progression.changePolicy,
                        keyFifths = state.key.fifths,
                        keyMode = state.key.mode,
                        onKeyFifths = actions.key.changeFifths,
                        onKeyMode = actions.key.changeMode,
                    )
                    ExplorationMode.SCHOENBERG_EXERCISE -> SchoenbergExerciseEditor(
                        formSpec = state.activeForm,
                        key = state.key,
                        state = state.schoenberg,
                        keyActions = actions.key,
                        actions = actions.schoenberg,
                    )
                    ExplorationMode.MODULATION -> Unit
                }

                RunControls(
                    isStale = state.run.stale,
                    running = state.run.running,
                    candidateCount = state.run.candidateCount,
                    diversify = state.run.diversify,
                    enabled = state.run.enabled,
                    onCandidateCount = actions.run.changeCandidateCount,
                    onDiversify = actions.run.changeDiversify,
                    onReroll = actions.run.reroll,
                    onRun = actions.run.run,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: ExplorationMode,
    selectedRule: RuleId,
    isStale: Boolean,
    onModeChange: (ExplorationMode) -> Unit,
    onSelectRule: (RuleId) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeChip(explorationText("mode.ruleExample"), selected = mode == ExplorationMode.RULE_EXAMPLE) {
            if (RuleCatalog.descriptor(selectedRule) == null) {
                onSelectRule(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE)
            } else {
                onModeChange(ExplorationMode.RULE_EXAMPLE)
            }
        }
        ModeChip(explorationText("mode.progression"), selected = mode == ExplorationMode.PROGRESSION) {
            onModeChange(ExplorationMode.PROGRESSION)
        }
        ModeChip(explorationText("mode.schoenbergExercise"), selected = mode == ExplorationMode.SCHOENBERG_EXERCISE) {
            onSelectRule(SchoenbergCommonToneExercises.ruleIdForExercise(SchoenbergCommonToneExercises.FIRST_EXERCISE_ID))
        }
        ModeChip(explorationText("mode.modulation"), selected = mode == ExplorationMode.MODULATION) {
            onModeChange(ExplorationMode.MODULATION)
        }
        Spacer(Modifier.weight(1f))
        if (isStale) StatusPill(explorationText("status.stale"))
    }
}

@Composable
private fun RunControls(
    isStale: Boolean,
    running: Boolean,
    candidateCount: Int,
    diversify: Boolean,
    enabled: Boolean,
    onCandidateCount: (Int) -> Unit,
    onDiversify: (Boolean) -> Unit,
    onReroll: () -> Unit,
    onRun: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onRun,
            enabled = enabled && !running,
            colors = ButtonDefaults.buttonColors(containerColor = MeconColors.Primary),
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(if (isStale) Icons.Default.Refresh else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
            }
            Text(if (isStale) explorationText("action.rerun") else explorationText("action.run"))
        }

        Text("候选数", color = MeconColors.TextMuted, fontSize = 11.sp)
        StepperControl(
            value = candidateCount,
            onDecrement = { onCandidateCount(candidateCount - 1) },
            onIncrement = { onCandidateCount(candidateCount + 1) },
        )

        ModeChip("多样化", selected = diversify) { onDiversify(!diversify) }
        if (diversify) ModeChip("换一批", selected = false, onClick = onReroll)

        Text(explorationText("run.hint"), color = MeconColors.TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StepperControl(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepperButton("−", onDecrement)
        Text("$value", color = MeconColors.TextPrimary, fontSize = 13.sp)
        StepperButton("+", onIncrement)
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MeconColors.TextSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeconColors.SurfaceLight)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun RuleExampleEditor(
    formSpec: FormSpec,
    selectedRule: RuleId,
    key: ExplorationKeyState,
    state: RuleExampleEditorState,
    keyActions: ExplorationKeyActions,
    actions: RuleExampleEditorActions,
) {
    val schema = state.schema
    val companionRuleId = state.companionRuleId
    val demonstrateRuleId = state.demonstrateRuleId
    val keyFifths = key.fifths
    val keyMode = key.mode
    val fromDegree = state.fromDegree
    val toDegree = state.toDegree
    val onCompanionRule = actions.changeCompanionRule
    val onKeyFifths = keyActions.changeFifths
    val onKeyMode = keyActions.changeMode
    val onFromDegree = actions.changeFromDegree
    val onToDegree = actions.changeToDegree
    val onDemonstrate = actions.changeDemonstration
    val requestParts = remember(schema, companionRuleId, demonstrateRuleId) {
        schema.compile(companionRuleId, demonstrateRuleId)
    }
    val threeChordScene = remember(requestParts) { maxSceneWindow(requestParts.validationRules) >= 3 }
    val progressions = remember(requestParts, keyFifths, keyMode) {
        if (threeChordScene) sceneProgressions(requestParts.validationRules, keyFifths, keyMode) else emptyList()
    }
    val feasibleFrom = remember(requestParts, threeChordScene) {
        if (threeChordScene) emptyList() else feasibleSources(requestParts.validationRules)
    }
    val feasibleTo = remember(requestParts, fromDegree, threeChordScene) {
        if (threeChordScene) emptyList() else feasibleTargets(requestParts.validationRules, fromDegree)
    }
    LaunchedEffect(progressions) {
        if (threeChordScene && progressions.isNotEmpty() &&
            progressions.none { it.fromDegree == fromDegree && it.toDegree == toDegree }
        ) {
            onFromDegree(progressions.first().fromDegree)
            onToDegree(progressions.first().toDegree)
        }
    }
    LaunchedEffect(requestParts, threeChordScene) {
        if (!threeChordScene && feasibleFrom.isNotEmpty() && fromDegree !in feasibleFrom) onFromDegree(feasibleFrom.first())
    }
    LaunchedEffect(requestParts, fromDegree, threeChordScene) {
        if (!threeChordScene && feasibleTo.isNotEmpty() && toDegree !in feasibleTo) onToDegree(feasibleTo.first())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormSpecRenderer(formSpec) { field ->
            when (field.kind) {
                FormFieldKind.RULE_TREE -> RulePathHeader(selectedRule)
                FormFieldKind.KEY_PICKER -> KeyPicker(
                    fifths = keyFifths,
                    mode = keyMode,
                    forcedMode = schema.keyMode?.toKeyModeSpec(),
                    onFifths = onKeyFifths,
                    onMode = onKeyMode,
                )
                FormFieldKind.PROGRESSION_PICKER -> {
                    if (schema.usesDegreeContext && threeChordScene) {
                        SceneProgressionPicker(
                            options = progressions,
                            fromDegree = fromDegree,
                            toDegree = toDegree,
                            onSelect = { option ->
                                onFromDegree(option.fromDegree)
                                onToDegree(option.toDegree)
                            },
                        )
                    }
                }
                FormFieldKind.DEGREE_PAIR -> {
                    if (schema.usesDegreeContext && !threeChordScene) {
                        DegreePairPicker(
                            pairs = schema.degreePairs,
                            keyMode = keyMode,
                            degreeQualities = schema.degreeQualities,
                            fromDegree = fromDegree,
                            toDegree = toDegree,
                            onPair = { pair ->
                                onFromDegree(pair.fromDegree)
                                onToDegree(pair.toDegree)
                            },
                        )
                        DegreePicker(explorationText("editor.degree.previous"), fromDegree, feasibleFrom, onFromDegree)
                        DegreePicker(explorationText("editor.degree.next"), toDegree, feasibleTo, onToDegree)
                        if (feasibleTo.size < DIATONIC_DEGREES) {
                            Text(explorationText("editor.degree.hiddenTargets"), color = MeconColors.TextMuted, fontSize = 10.sp)
                        }
                    }
                }
                FormFieldKind.TOGGLE -> {
                    if (field.id == "demonstrate" && schema.isDemonstrableAsViolation) {
                        DemonstrationToggle(
                            selectedRule = selectedRule,
                            demonstrateRuleId = demonstrateRuleId,
                            onDemonstrate = onDemonstrate,
                        )
                    }
                }
                else -> Unit
            }
        }
        if (schema.companionRuleOptions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(explorationText("editor.companionMode"), color = MeconColors.TextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    schema.companionRuleOptions.forEach { option ->
                        ModeChip(ruleLabel(option), selected = option == companionRuleId) {
                            onCompanionRule(option)
                        }
                    }
                }
            }
        }
        if (schema.degreeQualities.isNotEmpty()) {
            val items = schema.degreeQualities.entries.joinToString {
                explorationText("editor.degreeQuality.item", it.key, chordQualityLabel(it.value))
            }
            Text(
                explorationText("editor.degreeQuality.constraints", items),
                color = MeconColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        val validation = RuleCatalog.validateSelection(
            requestParts.validationRules,
            SelectionContext(fromDegree, toDegree),
        )
        if (!validation.isValid || validation.unavailable.isNotEmpty()) {
            validation.errors.plus(validation.unavailable).forEach {
                Text(it.message, color = MeconColors.Red, fontSize = 11.sp)
            }
        }
        if (schema.usesDegreeContext) {
            Text(ruleInputSummary(schema), color = MeconColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FormSpecRenderer(
    formSpec: FormSpec,
    renderField: @Composable (FormField) -> Unit,
) {
    formSpec.fields.forEach { field -> renderField(field) }
}

@Composable
private fun DemonstrationToggle(
    selectedRule: RuleId,
    demonstrateRuleId: RuleId?,
    onDemonstrate: (RuleId?) -> Unit,
) {
    MeconLabeledSwitch(
        label = explorationText("editor.demonstration"),
        checked = demonstrateRuleId == selectedRule,
        onCheckedChange = { enabled -> onDemonstrate(if (enabled) selectedRule else null) },
        fontSize = 12.sp,
    )
}

@Composable
private fun KeyPicker(
    fifths: Int,
    mode: KeyModeSpec,
    forcedMode: KeyModeSpec?,
    onFifths: (Int) -> Unit,
    onMode: (KeyModeSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.key"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 同一调号在小调下显示其关系小调主音（如 0 个升降号：大调 C、小调 Am）。
            val fifthsLabels = if (mode == KeyModeSpec.MINOR) {
                listOf(-3 to "Cm", -2 to "Gm", -1 to "Dm", 0 to "Am", 1 to "Em", 2 to "Bm", 3 to "F#m")
            } else {
                listOf(-3 to "Eb", -2 to "Bb", -1 to "F", 0 to "C", 1 to "G", 2 to "D", 3 to "A")
            }
            fifthsLabels.forEach { (value, label) ->
                ModeChip(label, selected = fifths == value) { onFifths(value) }
            }
            val modes = forcedMode?.let(::listOf) ?: listOf(KeyModeSpec.MAJOR, KeyModeSpec.MINOR)
            modes.forEach { keyMode ->
                ModeChip(
                    explorationText(if (keyMode == KeyModeSpec.MAJOR) "editor.key.major" else "editor.key.minor"),
                    selected = mode == keyMode,
                ) {
                    if (forcedMode == null) onMode(keyMode)
                }
            }
        }
        forcedMode?.let {
            val modeLabel = explorationText(if (it == KeyModeSpec.MAJOR) "editor.key.major" else "editor.key.minor")
            Text(explorationText("editor.key.forcedMode", modeLabel), color = MeconColors.TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DegreePairPicker(
    pairs: List<RuleDegreePair>,
    keyMode: KeyModeSpec,
    degreeQualities: Map<Int, ChordQuality>,
    fromDegree: Int,
    toDegree: Int,
    onPair: (RuleDegreePair) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.commonPairs"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pairs.forEach { pair ->
                ModeChip(
                    degreePairLabel(pair, keyMode, degreeQualities),
                    selected = pair.fromDegree == fromDegree && pair.toDegree == toDegree,
                ) {
                    onPair(pair)
                }
            }
        }
    }
}

@Composable
private fun ProgressionEditor(
    formSpec: FormSpec,
    text: String,
    onTextChange: (String) -> Unit,
    policyId: String,
    onPolicyChange: (String) -> Unit,
    keyFifths: Int,
    keyMode: KeyModeSpec,
    onKeyFifths: (Int) -> Unit,
    onKeyMode: (KeyModeSpec) -> Unit,
) {
    val degrees = progressionDegrees(text, policyId)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormSpecRenderer(formSpec) { field ->
            when (field.kind) {
                FormFieldKind.KEY_PICKER -> KeyPicker(
                    fifths = keyFifths,
                    mode = keyMode,
                    forcedMode = null,
                    onFifths = onKeyFifths,
                    onMode = onKeyMode,
                )
                FormFieldKind.SELECT -> {
                    if (field.id == "policy") {
                        PolicyPicker(policyId = policyId, onPolicyChange = onPolicyChange)
                    }
                }
                FormFieldKind.SLOT_LIST -> ProgressionSlotList(
                    text = text,
                    onTextChange = onTextChange,
                    policyId = policyId,
                    degrees = degrees,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun SchoenbergExerciseEditor(
    formSpec: FormSpec,
    key: ExplorationKeyState,
    state: SchoenbergExerciseEditorState,
    keyActions: ExplorationKeyActions,
    actions: SchoenbergExerciseEditorActions,
) {
    val exerciseId = state.exerciseId
    val keyFifths = key.fifths
    val keyMode = key.mode
    val continuationChordCount = state.continuationChordCount
    val selectedProgression = state.selectedProgression
    val selections = state.selections
    val secondaryHarmonyId =
        selections[SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY]?.singleOrNull()
    val diminishedSeventhChordId =
        selections[SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD]?.singleOrNull()
    val diminishedSeventhUsageId =
        selections[SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE]?.singleOrNull()
    val distantModulationPathId =
        selections[SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH]?.singleOrNull()
    val tonalConfirmationId =
        selections[SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION]?.singleOrNull()
    val chordFilters = state.chordFilters
    val includeDeceptiveCadence = state.includeDeceptiveCadence
    val includeCadentialSixFour = state.includeCadentialSixFour
    val onExerciseId = actions.changeExercise
    val onKeyFifths = keyActions.changeFifths
    val onKeyMode = keyActions.changeMode
    val onContinuationChordCount = actions.changeContinuationChordCount
    val onProgression = actions.changeProgression
    fun onSelection(key: String, value: String?) {
        actions.changeSelection(key, listOfNotNull(value))
    }
    val onSecondaryHarmony: (String?) -> Unit = {
        onSelection(SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY, it)
    }
    val onDiminishedSeventhChord: (String?) -> Unit = {
        onSelection(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD, it)
    }
    val onDiminishedSeventhUsage: (String?) -> Unit = {
        onSelection(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE, it)
    }
    val onDistantModulationPath: (String?) -> Unit = {
        onSelection(SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH, it)
    }
    val onTonalConfirmation: (String?) -> Unit = {
        onSelection(SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION, it)
    }
    val onChordFilters = actions.changeChordFilters
    val onDeceptiveCadence = actions.changeDeceptiveCadence
    val onCadentialSixFour = actions.changeCadentialSixFour
    val descriptor = remember(exerciseId) { SchoenbergCommonToneExercises.descriptorForExercise(exerciseId) }
    // 大 / 小调专属分支锁定调性；通用分支允许用户自由切换。
    val forcedKeyMode = remember(exerciseId) {
        if (exerciseId == SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID) {
            KeyModeSpec.MAJOR
        } else {
            when (descriptor.parentId) {
                SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID -> KeyModeSpec.MINOR
                SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID -> KeyModeSpec.MAJOR
                else -> null
            }
        }
    }
    val effectiveKeyMode = forcedKeyMode ?: keyMode
    val supportsCadenceOptions = exerciseId == SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID ||
        exerciseId == SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID
    val supportsSecondaryHarmonyChoice =
        exerciseId == SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID
    val supportsDiminishedSeventhChoice =
        exerciseId == SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID
    val supportsDistantModulationChoice =
        exerciseId == SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID
    LaunchedEffect(exerciseId, supportsDistantModulationChoice) {
        if (supportsDistantModulationChoice) {
            if (distantModulationPathId == null) {
                onDistantModulationPath(
                    com.mecon.theory.schoenberg.SchoenbergDistantTonalPaths.THREE_SHARPS.id.value
                )
            }
            if (tonalConfirmationId == null) {
                onTonalConfirmation(com.mecon.theory.schoenberg.TonalConfirmationLevel.LIGHT.name)
            }
        }
    }
    val theoryKey = remember(keyFifths, effectiveKeyMode) {
        Key.fromKeySignatureFifths(
            keyFifths,
            if (effectiveKeyMode == KeyModeSpec.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
        )
    }
    val secondaryHarmonyChoices = remember(theoryKey) {
        SchoenbergSecondaryDominantChapter.harmonyChoices(theoryKey)
    }
    val diminishedSeventhChoices = remember(theoryKey) {
        SchoenbergDiminishedSeventhChapter.chordChoices(theoryKey)
    }
    val diminishedSeventhUsages = remember(theoryKey, diminishedSeventhChordId) {
        diminishedSeventhChordId?.let {
            SchoenbergDiminishedSeventhChapter.usageChoices(theoryKey, it)
        }.orEmpty()
    }
    val validDiminishedSeventhUsageId = diminishedSeventhUsageId?.takeIf { selectedId ->
        diminishedSeventhUsages.any { it.id == selectedId }
    }
    var useAnyProgression by remember(
        exerciseId,
        keyFifths,
        effectiveKeyMode,
        continuationChordCount,
        includeDeceptiveCadence,
        includeCadentialSixFour,
    ) {
        mutableStateOf(supportsCadenceOptions)
    }
    var progressions by remember { mutableStateOf(emptyList<SymbolicProgression>()) }
    var progressionsLoading by remember { mutableStateOf(false) }
    val progressionEnumerationGeneration = remember { intArrayOf(0) }
    LaunchedEffect(
        exerciseId,
        keyFifths,
        effectiveKeyMode,
        continuationChordCount,
        secondaryHarmonyId,
        diminishedSeventhChordId,
        diminishedSeventhUsageId,
        chordFilters,
        includeDeceptiveCadence,
        includeCadentialSixFour,
        descriptor.requiresEnumeratedProgression,
    ) {
        val generation = ++progressionEnumerationGeneration[0]
        progressions = emptyList()
        if (!descriptor.requiresEnumeratedProgression) {
            progressionsLoading = false
            return@LaunchedEffect
        }
        progressionsLoading = true
        // Collapse rapid filter/count changes before entering the symbolic DFS. Once running, the
        // job probe below lets a superseded chapter/filter request leave the serial worker quickly.
        delay(SCHOENBERG_ENUMERATION_DEBOUNCE_MS)
        try {
            val previewSelections = selections.toMutableMap().apply {
                if (validDiminishedSeventhUsageId == null) {
                    remove(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE)
                }
            }
            progressions = withContext(schoenbergEnumerationDispatcher) {
                val enumerationJob = coroutineContext[Job]
                schoenbergExerciseProgressions(
                    exerciseId = exerciseId,
                    keyFifths = keyFifths,
                    continuationChordCount = continuationChordCount,
                    chordFilters = chordFilters,
                    selections = previewSelections,
                    keyMode = effectiveKeyMode,
                    includeDeceptiveCadence = includeDeceptiveCadence,
                    includeCadentialSixFour = includeCadentialSixFour,
                    maxResults = SCHOENBERG_PREVIEW_MAX_RESULTS,
                    maxVisitedNodes = SCHOENBERG_PREVIEW_MAX_VISITED_NODES,
                    shouldContinue = { enumerationJob?.isActive != false },
                )
            }
        } finally {
            if (progressionEnumerationGeneration[0] == generation) {
                progressionsLoading = false
            }
        }
    }
    LaunchedEffect(exerciseId, forcedKeyMode, keyMode) {
        if (forcedKeyMode != null && keyMode != forcedKeyMode) onKeyMode(forcedKeyMode)
    }
    LaunchedEffect(
        supportsSecondaryHarmonyChoice,
        secondaryHarmonyChoices,
        secondaryHarmonyId,
    ) {
        if (supportsSecondaryHarmonyChoice) {
            if (secondaryHarmonyChoices.none { it.id == secondaryHarmonyId }) {
                onSecondaryHarmony(secondaryHarmonyChoices.firstOrNull()?.id)
            }
        } else if (secondaryHarmonyId != null) {
            onSecondaryHarmony(null)
        }
    }
    LaunchedEffect(
        supportsDiminishedSeventhChoice,
        diminishedSeventhChoices,
        diminishedSeventhChordId,
    ) {
        if (supportsDiminishedSeventhChoice) {
            if (diminishedSeventhChoices.none { it.id == diminishedSeventhChordId }) {
                onDiminishedSeventhChord(diminishedSeventhChoices.firstOrNull()?.id)
            }
        } else if (diminishedSeventhChordId != null) {
            onDiminishedSeventhChord(null)
        }
    }
    LaunchedEffect(
        supportsDiminishedSeventhChoice,
        diminishedSeventhUsages,
        diminishedSeventhUsageId,
    ) {
        if (supportsDiminishedSeventhChoice) {
            if (diminishedSeventhUsages.none { it.id == diminishedSeventhUsageId }) {
                onDiminishedSeventhUsage(diminishedSeventhUsages.firstOrNull()?.id)
            }
        } else if (diminishedSeventhUsageId != null) {
            onDiminishedSeventhUsage(null)
        }
    }
    LaunchedEffect(
        exerciseId,
        keyFifths,
        continuationChordCount,
        progressions,
        descriptor.requiresEnumeratedProgression,
        useAnyProgression,
    ) {
        if (continuationChordCount !in descriptor.continuationChordCountRange) {
            onContinuationChordCount(descriptor.continuationChordCountRange.first)
        }
        if (descriptor.requiresEnumeratedProgression) {
            if (useAnyProgression && selectedProgression != null) {
                onProgression(null)
            } else if (!useAnyProgression && progressions.isNotEmpty() && selectedProgression !in progressions) {
                onProgression(progressions.first())
            }
        } else if (selectedProgression != null) {
            onProgression(null)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RulePathHeader(SchoenbergCommonToneExercises.ruleIdForExercise(exerciseId))
        FormSpecRenderer(formSpec) { field ->
            when (field.kind) {
                FormFieldKind.SELECT -> {
                    when (field.id) {
                        "exerciseId" -> SchoenbergExercisePicker(
                                exerciseId = exerciseId,
                                onExerciseId = onExerciseId,
                            )
                        SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY -> if (supportsSecondaryHarmonyChoice) {
                            SchoenbergSecondaryHarmonyPicker(
                                choices = secondaryHarmonyChoices,
                                keyMode = effectiveKeyMode,
                                selectedId = secondaryHarmonyId,
                                onSelected = onSecondaryHarmony,
                            )
                        }
                        SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD -> if (supportsDiminishedSeventhChoice) {
                            SchoenbergDiminishedSeventhChordPicker(
                                choices = diminishedSeventhChoices,
                                keyMode = effectiveKeyMode,
                                selectedId = diminishedSeventhChordId,
                                onSelected = { selectedId ->
                                    onDiminishedSeventhChord(selectedId)
                                    onDiminishedSeventhUsage(null)
                                },
                            )
                        }
                        SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE -> if (supportsDiminishedSeventhChoice) {
                            SchoenbergDiminishedSeventhUsagePicker(
                                choices = diminishedSeventhUsages,
                                keyMode = effectiveKeyMode,
                                selectedId = diminishedSeventhUsageId,
                                onSelected = onDiminishedSeventhUsage,
                            )
                        }
                        SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH -> if (supportsDistantModulationChoice) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("转调路径", color = MeconColors.TextSecondary, fontSize = 12.sp)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    com.mecon.theory.schoenberg.SchoenbergDistantTonalPaths.all.forEach { path ->
                                        ModeChip(
                                            path.id.value,
                                            selected = path.id.value == distantModulationPathId,
                                        ) { onDistantModulationPath(path.id.value) }
                                    }
                                }
                            }
                        }
                        SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION -> if (supportsDistantModulationChoice) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("调性强度", color = MeconColors.TextSecondary, fontSize = 12.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.mecon.theory.schoenberg.TonalConfirmationLevel.entries.forEach { level ->
                                        ModeChip(
                                            level.name,
                                            selected = level.name == tonalConfirmationId,
                                        ) { onTonalConfirmation(level.name) }
                                    }
                                }
                            }
                        }
                    }
                }
                FormFieldKind.KEY_PICKER -> KeyPicker(
                    fifths = keyFifths,
                    mode = keyMode,
                    forcedMode = forcedKeyMode,
                    onFifths = onKeyFifths,
                    onMode = onKeyMode,
                )
                FormFieldKind.NUMBER -> {
                    if (field.id == "continuationChordCount" &&
                        (!descriptor.requiresEnumeratedProgression || descriptor.group == SchoenbergExerciseGroup.INTEGRATED)
                    ) {
                        ContinuationChordCountPicker(
                            value = continuationChordCount,
                            range = descriptor.continuationChordCountRange,
                            onValue = onContinuationChordCount,
                        )
                    }
                }
                FormFieldKind.PROGRESSION_PICKER -> {
                    if (field.id == "progression" && descriptor.requiresEnumeratedProgression) {
                        SchoenbergProgressionPicker(
                            progressions = progressions,
                            loading = progressionsLoading,
                            keyMode = effectiveKeyMode,
                            selectedProgression = selectedProgression,
                            anyProgressionSelected = useAnyProgression,
                            onProgression = { progression ->
                                useAnyProgression = false
                                onProgression(progression)
                            },
                            onAnyProgression = {
                                useAnyProgression = true
                                onProgression(null)
                            },
                        )
                    }
                }
                FormFieldKind.CHORD_FILTERS -> {
                    if (field.id == "chordFilters" && descriptor.group == SchoenbergExerciseGroup.INTEGRATED) {
                        SchoenbergChordFiltersEditor(chordFilters, onChordFilters)
                    }
                }
                FormFieldKind.TOGGLE -> {
                    if (supportsCadenceOptions) {
                        when (field.id) {
                            "includeDeceptiveCadence" -> SchoenbergOptionToggle(
                                checked = includeDeceptiveCadence,
                                labelKey = "editor.schoenbergDeceptiveCadence",
                                onCheckedChange = onDeceptiveCadence,
                            )
                            "includeCadentialSixFour" -> SchoenbergOptionToggle(
                                checked = includeCadentialSixFour,
                                labelKey = "editor.schoenbergCadentialSixFour",
                                onCheckedChange = onCadentialSixFour,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
        Text(
            explorationText("editor.schoenbergHint"),
            color = MeconColors.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SchoenbergOptionToggle(
    checked: Boolean,
    labelKey: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    MeconLabeledSwitch(
        label = explorationText(labelKey),
        checked = checked,
        onCheckedChange = onCheckedChange,
        fontSize = 12.sp,
    )
}

@Composable
private fun SchoenbergExercisePicker(exerciseId: String, onExerciseId: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.schoenbergExercise"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        SchoenbergExerciseGroup.entries.forEach { group ->
            Text(
                explorationText(
                    if (group == SchoenbergExerciseGroup.INDEPENDENT) {
                        "editor.schoenbergGroup.independent"
                    } else {
                        "editor.schoenbergGroup.integrated"
                    }
                ),
                color = MeconColors.TextMuted,
                fontSize = 11.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SchoenbergCommonToneExercises.exerciseDescriptors
                    .filter { it.group == group }
                    .forEach { descriptor ->
                        ModeChip(
                            ruleLabel(descriptor.ruleId),
                            selected = descriptor.exerciseId == exerciseId,
                        ) {
                            onExerciseId(descriptor.exerciseId)
                        }
                    }
            }
        }
    }
}

@Composable
private fun SchoenbergChordFiltersEditor(
    filters: List<SchoenbergChordFilterSpec>,
    onFilters: (List<SchoenbergChordFilterSpec>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("指定和弦性质（同一行取交集）", color = MeconColors.TextSecondary, fontSize = 12.sp)
            ModeChip("添加和弦", selected = false) {
                onFilters(filters + SchoenbergChordFilterSpec(degree = 1))
            }
        }
        filters.forEachIndexed { index, filter ->
            Surface(color = MeconColors.SurfaceLight, shape = RoundedCornerShape(6.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("和弦 ${index + 1}", color = MeconColors.TextPrimary, fontSize = 12.sp)
                        ModeChip("删除", selected = false) { onFilters(filters.filterIndexed { i, _ -> i != index }) }
                    }
                    FilterChipRow(
                        labels = listOf("任意级") + (1..7).map { "$it 级" },
                        selectedIndex = filter.degree ?: 0,
                    ) { selected ->
                        val degree = selected.takeIf { it != 0 }
                        if (degree != null || filter.arity != null || filter.inversion != null) {
                            onFilters(filters.updated(index, filter.copy(degree = degree)))
                        }
                    }
                    FilterChipRow(
                        labels = listOf("任意规模", "三和弦", "七和弦"),
                        selectedIndex = when (filter.arity) { "TRIAD" -> 1; "SEVENTH" -> 2; else -> 0 },
                    ) { selected ->
                        val arity = when (selected) { 1 -> "TRIAD"; 2 -> "SEVENTH"; else -> null }
                        val inversion = if (arity == "TRIAD" && filter.inversion == 3) null else filter.inversion
                        if (arity != null || filter.degree != null || inversion != null) {
                            onFilters(filters.updated(index, filter.copy(arity = arity, inversion = inversion)))
                        }
                    }
                    val inversionLabels = if (filter.arity == "TRIAD") {
                        listOf("任意转位", "原位", "第一转位", "第二转位")
                    } else {
                        listOf("任意转位", "原位", "第一转位", "第二转位", "第三转位")
                    }
                    FilterChipRow(
                        labels = inversionLabels,
                        selectedIndex = filter.inversion?.plus(1) ?: 0,
                    ) { selected ->
                        val inversion = selected.minus(1).takeIf { it >= 0 }
                        if (inversion != null || filter.degree != null || filter.arity != null) {
                            onFilters(filters.updated(index, filter.copy(inversion = inversion)))
                        }
                    }
                }
            }
        }
        if (filters.isNotEmpty()) {
            Text("多行必须分别由不同和弦满足；每行所选级数、规模与转位同时成立。", color = MeconColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FilterChipRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            ModeChip(label, selected = index == selectedIndex) { onSelect(index) }
        }
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) value else item }

@Composable
private fun SchoenbergSecondaryHarmonyPicker(
    choices: List<SchoenbergSecondaryHarmonyChoice>,
    keyMode: KeyModeSpec,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            explorationText("editor.schoenbergSecondaryHarmony"),
            color = MeconColors.TextSecondary,
            fontSize = 12.sp,
        )
        SecondaryHarmonyFamily.entries.forEach { family ->
            val familyChoices = choices.filter { it.chord.secondaryFamily == family }
            if (familyChoices.isEmpty()) return@forEach
            Text(
                explorationText("editor.schoenbergSecondaryHarmony.${family.name.lowercase()}"),
                color = MeconColors.TextMuted,
                fontSize = 11.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                familyChoices.forEach { choice ->
                    ModeChip(
                        "${choice.chord.schoenbergLabel(keyMode)} · ${chordQualityLabel(choice.chord.quality)}",
                        selected = choice.id == selectedId,
                    ) {
                        onSelected(choice.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoenbergDiminishedSeventhChordPicker(
    choices: List<SchoenbergDiminishedSeventhChordChoice>,
    keyMode: KeyModeSpec,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            explorationText("editor.schoenbergDiminishedSeventhChord"),
            color = MeconColors.TextSecondary,
            fontSize = 12.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                ModeChip(
                    choice.chord.copy(
                        seventhPosition = TextbookSeventhPosition.ROOT_POSITION,
                        appliedToDegree = null,
                        secondaryFamily = null,
                    ).schoenbergLabel(keyMode),
                    selected = choice.id == selectedId,
                ) {
                    onSelected(choice.id)
                }
            }
        }
    }
}

@Composable
private fun SchoenbergDiminishedSeventhUsagePicker(
    choices: List<SchoenbergDiminishedSeventhUsageChoice>,
    keyMode: KeyModeSpec,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            explorationText("editor.schoenbergDiminishedSeventhUsage"),
            color = MeconColors.TextSecondary,
            fontSize = 12.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                ModeChip(
                    "${choice.chord.schoenbergLabel(keyMode)} · V/${choice.tonicizedDegree}",
                    selected = choice.id == selectedId,
                ) {
                    onSelected(choice.id)
                }
            }
        }
    }
}

@Composable
private fun SchoenbergProgressionPicker(
    progressions: List<SymbolicProgression>,
    loading: Boolean,
    keyMode: KeyModeSpec,
    selectedProgression: SymbolicProgression?,
    anyProgressionSelected: Boolean,
    onProgression: (SymbolicProgression) -> Unit,
    onAnyProgression: () -> Unit,
) {
    val featured = progressions.take(FEATURED_SCHOENBERG_PROGRESSION_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.schoenbergProgression"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.width(18.dp).height(18.dp),
                strokeWidth = 2.dp,
                color = MeconColors.SelectedBorder,
            )
        } else if (progressions.isEmpty()) {
            Text(explorationText("editor.schoenbergProgression.empty"), color = MeconColors.TextMuted, fontSize = 11.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                featured.forEachIndexed { index, progression ->
                    ModeChip(
                        (index + 1).toString() + ". " + progression.schoenbergLabel(keyMode),
                        selected = !anyProgressionSelected && progression == selectedProgression,
                    ) {
                        onProgression(progression)
                    }
                }
                ModeChip(
                    "其他",
                    selected = anyProgressionSelected,
                    onClick = onAnyProgression,
                )
            }
        }
    }
}

@Composable
private fun ContinuationChordCountPicker(value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.continuationChordCount"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            range.forEach { count ->
                ModeChip(count.toString(), selected = value == count) {
                    onValue(count)
                }
            }
        }
    }
}

private const val FEATURED_SCHOENBERG_PROGRESSION_COUNT = 5
private const val SCHOENBERG_PREVIEW_MAX_RESULTS = 8
private const val SCHOENBERG_PREVIEW_MAX_VISITED_NODES = 5_000
private const val SCHOENBERG_ENUMERATION_DEBOUNCE_MS = 75L
private val schoenbergEnumerationDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
private fun PolicyPicker(policyId: String, onPolicyChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.policy"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            progressionPolicies.forEach { policy ->
                ModeChip(explorationText(policy.labelKey), selected = policy.id == policyId) {
                    onPolicyChange(policy.id)
                }
            }
        }
    }
}

@Composable
private fun ProgressionSlotList(
    text: String,
    onTextChange: (String) -> Unit,
    policyId: String,
    degrees: List<Int>,
) {
    Text(explorationText("editor.progression"), color = MeconColors.TextSecondary, fontSize = 12.sp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (policyId == SECOND_INVERSION_TRIADS_POLICY) {
                StatusPill(explorationText("editor.minThreeChords"))
            }
            Text(progressionPolicyHint(policyId), color = MeconColors.TextMuted, fontSize = 11.sp)
        }
        degrees.forEachIndexed { index, degree ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    explorationText("editor.chordIndex", index + 1),
                    color = MeconColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(64.dp),
                )
                (1..7).forEach { candidate ->
                    ModeChip(candidate.toString(), selected = candidate == degree) {
                        onTextChange(degrees.updated(index, candidate).joinToString(" "))
                    }
                }
                if (degrees.size > minProgressionSlots(policyId)) {
                    ModeChip(explorationText("editor.delete"), selected = false) {
                        onTextChange(degrees.removeAtIndex(index).joinToString(" "))
                    }
                }
            }
        }
        ModeChip(explorationText("editor.addChord"), selected = false) {
            onTextChange((degrees + degrees.lastOrNull().orDefaultDegree()).joinToString(" "))
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        label = { Text(explorationText("editor.progressionExample")) },
        modifier = Modifier.fillMaxWidth().meconTextInputFocus(),
    )
}

@Composable
private fun SceneProgressionPicker(
    options: List<SceneProgressionOption>,
    fromDegree: Int,
    toDegree: Int,
    onSelect: (SceneProgressionOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(explorationText("editor.sceneProgression"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        if (options.isEmpty()) {
            Text(explorationText("editor.sceneProgression.empty"), color = MeconColors.TextMuted, fontSize = 11.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    ModeChip(
                        option.label,
                        selected = option.fromDegree == fromDegree && option.toDegree == toDegree,
                    ) {
                        onSelect(option)
                    }
                }
            }
            Text(explorationText("editor.sceneProgression.hint"), color = MeconColors.TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DegreePicker(label: String, value: Int, allowed: List<Int>, onValue: (Int) -> Unit) {
    val options = allowed.ifEmpty { (1..DIATONIC_DEGREES).toList() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MeconColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        options.forEach { degree ->
            ModeChip(degree.toString(), selected = degree == value) { onValue(degree) }
        }
    }
}
