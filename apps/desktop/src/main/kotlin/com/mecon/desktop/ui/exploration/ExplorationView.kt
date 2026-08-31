package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.service.FreePracticeFileSnapshot
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.CellOutput
import com.mecon.exploration.DegreeSpec
import com.mecon.exploration.DemonstrationSpec
import com.mecon.exploration.ExplorationRequestRunner
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.KeySpec
import com.mecon.exploration.ModulationExerciseCellRequest
import com.mecon.exploration.ModulationSolverSpec
import com.mecon.exploration.ChoraleContourDirectionSpec
import com.mecon.exploration.ChoraleContourSpec
import com.mecon.exploration.ChoraleFigurationSpec
import com.mecon.exploration.ChoraleFigurationTypeSpec
import com.mecon.exploration.ChoraleHarmonizationRequest
import com.mecon.exploration.ChoraleRhythmSpec
import com.mecon.exploration.ChoraleSlotSpec
import com.mecon.exploration.ChoraleVoiceRoleSpec
import com.mecon.exploration.ChoraleVoiceSpec
import com.mecon.exploration.ProgressionRequest
import com.mecon.exploration.ProgressionSlot
import com.mecon.exploration.RuleExampleRequest
import com.mecon.exploration.SearchSpec
import com.mecon.exploration.SchoenbergExerciseRequest
import com.mecon.exploration.SchoenbergChordFilterSpec
import com.mecon.exploration.SolveRequest
import com.mecon.exploration.SolverEngine
import com.mecon.exploration.SymbolicProgression
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleId
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationChordId
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationPitchDisplayMode
import com.mecon.theory.NaturalTriads
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergExerciseSelectionKeys
import com.mecon.theory.textbook.RootPositionTriadRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExplorationView(
    playback: PlaybackController,
    onEditableScoreHostChange: (EditableScoreHost?) -> Unit,
    initialFreePractice: FreePracticeFileSnapshot? = null,
    freePracticeOpenGeneration: Long = 0L,
    onFreePracticeSnapshotChange: (FreePracticeFileSnapshot) -> Unit = {},
    onFreePracticeModeChange: (Boolean) -> Unit = {},
    onExplorationToolbarControllerChange: (ExplorationToolbarController?) -> Unit = {},
    onFreePracticeToolbarControllerChange: (FreePracticeToolbarController?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ExplorationMode.RULE_EXAMPLE) }
    var choraleProgression by remember { mutableStateOf("1 4 5 1") }
    var choraleOpenVoices by remember { mutableStateOf(setOf(ChoraleVoiceRoleSpec.SOPRANO)) }
    var choraleConflicts by remember { mutableStateOf(emptySet<ChoraleConflictMark>()) }
    var choraleContour by remember { mutableStateOf<ChoraleContourDirectionSpec?>(null) }
    var choraleAllowFirstInversion by remember { mutableStateOf(true) }
    var keyFifths by remember { mutableIntStateOf(0) }
    var keyMode by remember { mutableStateOf(KeyModeSpec.MAJOR) }
    var fromDegree by remember { mutableIntStateOf(5) }
    var toDegree by remember { mutableIntStateOf(1) }
    var progressionText by remember { mutableStateOf("1 5 6 4 2 5 1") }
    var progressionPolicyId by remember { mutableStateOf(INTRODUCTORY_TRIADS_POLICY) }
    var schoenbergContinuationChordCount by remember { mutableIntStateOf(1) }
    var schoenbergExerciseId by remember { mutableStateOf(SchoenbergCommonToneExercises.FIRST_EXERCISE_ID) }
    var schoenbergProgression by remember { mutableStateOf<SymbolicProgression?>(null) }
    var schoenbergSelections by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var schoenbergChordFilters by remember { mutableStateOf<List<SchoenbergChordFilterSpec>>(emptyList()) }
    var includeDeceptiveCadence by remember { mutableStateOf(false) }
    var includeCadentialSixFour by remember { mutableStateOf(false) }
    var selectedRuleId by remember { mutableStateOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value) }
    var companionRuleId by remember { mutableStateOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value) }
    var demonstrateRuleId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<CellOutput?>(null) }
    var selectedCandidateIndex by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var candidateCount by remember { mutableIntStateOf(4) }
    var diversify by remember { mutableStateOf(false) }
    var diversitySeed by remember { mutableStateOf(0L) }
    var modulationTargetKeys by remember {
        mutableStateOf(setOf(ModulationKey(1, KeySignatureMode.MAJOR)))
    }
    var modulationChordIds by remember {
        mutableStateOf(setOf(ModulationChordId(com.mecon.api.primitive.PitchClass.E, ChordQuality.MINOR)))
    }
    var modulationPitchMode by remember { mutableStateOf(ModulationPitchDisplayMode.ABSOLUTE) }
    var modulationSolver by remember { mutableStateOf(ModulationSolverSpec.SCHOENBERG) }
    var modulationSourceChordCount by remember { mutableIntStateOf(2) }
    var modulationTargetChordCount by remember { mutableIntStateOf(4) }
    var freePracticeMode by remember { mutableStateOf(false) }

    LaunchedEffect(freePracticeOpenGeneration) {
        if (freePracticeOpenGeneration > 0L) freePracticeMode = true
    }
    LaunchedEffect(freePracticeMode) {
        onFreePracticeModeChange(freePracticeMode)
        onExplorationToolbarControllerChange(
            ExplorationToolbarController(
                freePracticeMode = freePracticeMode,
                changeMode = { freePracticeMode = it },
            ),
        )
        if (!freePracticeMode) onFreePracticeToolbarControllerChange(null)
        if (!freePracticeMode) onEditableScoreHostChange(null)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onExplorationToolbarControllerChange(null) }
    }

    val manifest = remember { SolverEngine.describe() }
    val activeForm = remember(manifest, mode) { manifest.formFor(mode.requestType) }
    val keySpec = KeySpec(fifths = keyFifths, mode = keyMode)
    val modulationSourceKey = ModulationKey(
        fifths = keyFifths,
        mode = if (keyMode == KeyModeSpec.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
    )
    val modulationQueryKeys = remember(modulationSourceKey, modulationTargetKeys) {
        listOf(modulationSourceKey) + modulationTargetKeys.filterNot { it == modulationSourceKey }
    }
    val modulationCommonChords = remember(modulationQueryKeys) {
        ModulationCommonChordCatalog.commonChords(modulationQueryKeys)
    }
    val validModulationPivot = modulationChordIds.singleOrNull()
        ?.takeIf { id -> modulationCommonChords.any { it.id == id } }
    val validModulationTarget = modulationTargetKeys.singleOrNull()
        ?.takeIf { it != modulationSourceKey }
    val modulationCanRun = validModulationTarget != null && validModulationPivot != null
    val selectedRule = remember(selectedRuleId) { RuleId(selectedRuleId) }
    val selectedSchema = remember(selectedRuleId) {
        if (RuleCatalog.descriptor(selectedRule) != null) {
            RuleCatalog.exampleInputSpec(selectedRule)
        } else {
            RuleCatalog.exampleInputSpec(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE)
        }
    }

    fun selectRule(ruleId: RuleId) {
        val schoenbergExercise = SchoenbergCommonToneExercises.exerciseIdForRule(ruleId)
        if (schoenbergExercise != null) {
            selectedRuleId = ruleId.value
            schoenbergExerciseId = schoenbergExercise
            schoenbergProgression = null
            schoenbergSelections = emptyMap()
            // 大 / 小调专属分支锁定调性；通用分支保留用户当前选择。
            when (SchoenbergCommonToneExercises.descriptorForExercise(schoenbergExercise).parentId) {
                SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID -> keyMode = KeyModeSpec.MINOR
                SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID -> keyMode = KeyModeSpec.MAJOR
            }
            schoenbergContinuationChordCount = maxOf(
                schoenbergContinuationChordCount,
                SchoenbergCommonToneExercises.minContinuationChordCount(schoenbergExercise),
            )
            mode = ExplorationMode.SCHOENBERG_EXERCISE
            demonstrateRuleId = null
            return
        }
        val schema = RuleCatalog.exampleInputSpec(ruleId)
        selectedRuleId = ruleId.value
        mode = ExplorationMode.RULE_EXAMPLE
        fromDegree = schema.defaultPair.fromDegree
        toDegree = schema.defaultPair.toDegree
        schema.keyMode?.toKeyModeSpec()?.let { keyMode = it }
        companionRuleId = schema.defaultCompanionRuleId?.value ?: schema.ruleId.value
        demonstrateRuleId = schema.defaultDemonstrationRuleId?.value
    }

    val ruleExampleRequestParts = remember(selectedSchema, companionRuleId, demonstrateRuleId) {
        selectedSchema.compile(companionRuleId = RuleId(companionRuleId), demonstrateRuleId = demonstrateRuleId?.let(::RuleId))
    }
    val request = remember(
        mode,
        keySpec,
        fromDegree,
        toDegree,
        progressionText,
        progressionPolicyId,
        schoenbergContinuationChordCount,
        schoenbergExerciseId,
        schoenbergProgression,
        schoenbergSelections,
        schoenbergChordFilters,
        includeDeceptiveCadence,
        includeCadentialSixFour,
        ruleExampleRequestParts,
        candidateCount,
        diversify,
        diversitySeed,
        modulationTargetKeys,
        modulationChordIds,
        modulationSolver,
        modulationSourceChordCount,
        modulationTargetChordCount,
        choraleProgression,
        choraleOpenVoices,
        choraleConflicts,
        choraleContour,
        choraleAllowFirstInversion,
    ) {
        fun searchSpec(beamWidth: Int) = SearchSpec(
            maxResults = candidateCount,
            beamWidth = beamWidth,
            diversify = diversify,
            seed = diversitySeed,
        )
        when (mode) {
            ExplorationMode.RULE_EXAMPLE -> RuleExampleRequest(
                key = keySpec,
                from = DegreeSpec(fromDegree),
                to = DegreeSpec(toDegree),
                selectedRules = ruleExampleRequestParts.selectedRules.map { it.value },
                demonstrate = ruleExampleRequestParts.demonstrationRuleId?.value?.let(::DemonstrationSpec),
                search = searchSpec(beamWidth = 96),
            )
            ExplorationMode.PROGRESSION -> ProgressionRequest(
                key = keySpec,
                slots = progressionDegrees(progressionText, progressionPolicyId).map { ProgressionSlot(DegreeSpec(it)) },
                policyId = progressionPolicyId,
                search = searchSpec(beamWidth = 96),
            )
            ExplorationMode.SCHOENBERG_EXERCISE -> SchoenbergExerciseRequest(
                key = KeySpec(fifths = keyFifths, mode = keyMode),
                exerciseId = schoenbergExerciseId,
                continuationChordCount = schoenbergContinuationChordCount,
                progression = schoenbergProgression,
                selections = schoenbergSelections,
                chordFilters = schoenbergChordFilters,
                includeDeceptiveCadence = includeDeceptiveCadence,
                includeCadentialSixFour = includeCadentialSixFour,
                search = searchSpec(beamWidth = 128),
            )
            ExplorationMode.MODULATION -> {
                val target = validModulationTarget
                    ?: modulationTargetKeys.firstOrNull { it != modulationSourceKey }
                    ?: ModulationKey(
                        fifths = if (modulationSourceKey.fifths == 1) 0 else 1,
                        mode = KeySignatureMode.MAJOR,
                    )
                val fallbackPivot = modulationCommonChords.firstOrNull()?.id
                    ?: NaturalTriads.inKey(modulationSourceKey.key)
                        .first { it.degree == 1 }
                        .let { ModulationChordId(it.root, it.quality) }
                val pivot = validModulationPivot ?: fallbackPivot
                ModulationExerciseCellRequest(
                    sourceKey = keySpec,
                    targetKey = KeySpec(
                        fifths = target.fifths,
                        mode = if (target.mode == KeySignatureMode.MAJOR) {
                            KeyModeSpec.MAJOR
                        } else {
                            KeyModeSpec.MINOR
                        },
                    ),
                    pivotRoot = pivot.root.value,
                    pivotQuality = pivot.quality,
                    sourceChordCount = modulationSourceChordCount,
                    targetChordCount = modulationTargetChordCount,
                    solverPreset = modulationSolver,
                    search = searchSpec(beamWidth = 192),
                )
            }
            ExplorationMode.CHORALE -> {
                val degrees = parseDegrees(choraleProgression)
                ChoraleHarmonizationRequest(
                    key = keySpec,
                    slots = degrees.map { degree ->
                        ChoraleSlotSpec(
                            degree = degree,
                            inversion = if (choraleAllowFirstInversion) null else 0,
                        )
                    },
                    voices = ChoraleVoiceRoleSpec.entries.map { role ->
                        ChoraleVoiceSpec(
                            role = role,
                            patterns = if (role in choraleOpenVoices) {
                                listOf(
                                    ChoraleRhythmSpec.SUSTAINED,
                                    ChoraleRhythmSpec.HALVES,
                                    ChoraleRhythmSpec.QUARTERS,
                                )
                            } else listOf(ChoraleRhythmSpec.SUSTAINED),
                        )
                    },
                    figuration = choraleConflicts
                        .filter { it.slot in degrees.indices }
                        .sortedWith(compareBy({ it.slot }, { it.role.ordinal }))
                        .map { mark ->
                            ChoraleFigurationSpec(
                                slot = mark.slot,
                                type = ChoraleFigurationTypeSpec.SUSPENSION,
                                role = mark.role,
                            )
                        },
                    contour = choraleContour?.let { direction ->
                        listOf(
                            ChoraleContourSpec(
                                role = ChoraleVoiceRoleSpec.SOPRANO,
                                startSlot = 0,
                                endSlot = degrees.lastIndex,
                                direction = direction,
                                weight = 2.0,
                            )
                        )
                    }.orEmpty(),
                    search = searchSpec(beamWidth = 48),
                )
            }
        }
    }
    val currentFingerprint = ExplorationRequestRunner.fingerprint(request)
    val isStale = output != null && output?.fingerprint != currentFingerprint

    fun run() {
        if (mode == ExplorationMode.MODULATION && !modulationCanRun) return
        running = true
        selectedCandidateIndex = 0
        scope.launch {
            output = withContext(Dispatchers.Default) {
                SolverEngine.solve(SolveRequest(request)).output
            }
            running = false
        }
    }

    LaunchedEffect(Unit) { run() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MeconColors.Background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (freePracticeMode) {
            key(freePracticeOpenGeneration) {
                FreePracticeWorkbench(
                    playback = playback,
                    initialSnapshot = initialFreePractice,
                    onSnapshotChange = onFreePracticeSnapshotChange,
                    onEditableScoreHostChange = onEditableScoreHostChange,
                    onToolbarControllerChange = onFreePracticeToolbarControllerChange,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExplorationInputPanel(
            state = ExplorationInputState(
                mode = mode,
                activeForm = activeForm,
                selectedRule = selectedRule,
                key = ExplorationKeyState(keyFifths, keyMode),
                ruleExample = RuleExampleEditorState(
                    schema = selectedSchema,
                    companionRuleId = RuleId(companionRuleId),
                    demonstrateRuleId = demonstrateRuleId?.let(::RuleId),
                    fromDegree = fromDegree,
                    toDegree = toDegree,
                ),
                progression = ProgressionEditorState(
                    text = progressionText,
                    policyId = progressionPolicyId,
                ),
                schoenberg = SchoenbergExerciseEditorState(
                    exerciseId = schoenbergExerciseId,
                    continuationChordCount = schoenbergContinuationChordCount,
                    selectedProgression = schoenbergProgression,
                    selections = schoenbergSelections,
                    chordFilters = schoenbergChordFilters,
                    includeDeceptiveCadence = includeDeceptiveCadence,
                    includeCadentialSixFour = includeCadentialSixFour,
                ),
                modulation = ModulationEditorState(
                    sourceKey = modulationSourceKey,
                    selectedTargetKeys = modulationTargetKeys,
                    selectedChordIds = modulationChordIds,
                    pitchDisplayMode = modulationPitchMode,
                    solverPreset = modulationSolver,
                    sourceChordCount = modulationSourceChordCount,
                    targetChordCount = modulationTargetChordCount,
                ),
                chorale = ChoraleEditorState(
                    progression = choraleProgression,
                    openVoices = choraleOpenVoices,
                    conflicts = choraleConflicts,
                    sopranoContour = choraleContour,
                    allowFirstInversion = choraleAllowFirstInversion,
                ),
                run = ExplorationRunState(
                    stale = isStale,
                    running = running,
                    candidateCount = candidateCount,
                    diversify = diversify,
                    enabled = mode != ExplorationMode.MODULATION || modulationCanRun,
                ),
            ),
            actions = ExplorationInputActions(
                changeMode = {
                    mode = it
                    if (it == ExplorationMode.SCHOENBERG_EXERCISE) {
                        keyMode = KeyModeSpec.MAJOR
                    }
                },
                selectRule = ::selectRule,
                key = ExplorationKeyActions(
                    changeFifths = {
                        keyFifths = it
                        if (mode == ExplorationMode.SCHOENBERG_EXERCISE) {
                            schoenbergProgression = null
                        }
                    },
                    changeMode = { keyMode = it },
                ),
                ruleExample = RuleExampleEditorActions(
                    changeCompanionRule = { companionRuleId = it.value },
                    changeFromDegree = { fromDegree = it },
                    changeToDegree = { toDegree = it },
                    changeDemonstration = { demonstrateRuleId = it?.value },
                ),
                progression = ProgressionEditorActions(
                    changeText = { progressionText = it },
                    changePolicy = { policyId ->
                        progressionPolicyId = policyId
                        progressionText =
                            progressionDegrees(progressionText, policyId).joinToString(" ")
                    },
                ),
                schoenberg = SchoenbergExerciseEditorActions(
                    changeExercise = { exerciseId ->
                        schoenbergExerciseId = exerciseId
                        schoenbergProgression = null
                        schoenbergSelections = emptyMap()
                        keyMode = KeyModeSpec.MAJOR
                        selectedRuleId =
                            SchoenbergCommonToneExercises.ruleIdForExercise(exerciseId).value
                        schoenbergContinuationChordCount = maxOf(
                            schoenbergContinuationChordCount,
                            SchoenbergCommonToneExercises
                                .minContinuationChordCount(exerciseId),
                        )
                    },
                    changeContinuationChordCount = {
                        schoenbergContinuationChordCount = it.coerceAtLeast(
                            SchoenbergCommonToneExercises
                                .minContinuationChordCount(schoenbergExerciseId),
                        )
                        schoenbergProgression = null
                    },
                    changeProgression = { schoenbergProgression = it },
                    changeSelection = { key, values ->
                        schoenbergSelections = schoenbergSelections
                            .toMutableMap()
                            .apply {
                                if (values.isEmpty()) remove(key) else put(key, values)
                                if (key == SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD) {
                                    remove(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE)
                                }
                            }
                        schoenbergProgression = null
                    },
                    changeChordFilters = {
                        schoenbergChordFilters = it
                        schoenbergProgression = null
                    },
                    changeDeceptiveCadence = {
                        includeDeceptiveCadence = it
                        schoenbergProgression = null
                    },
                    changeCadentialSixFour = {
                        includeCadentialSixFour = it
                        schoenbergProgression = null
                    },
                ),
                modulation = ModulationEditorActions(
                    changeSourceFifths = { keyFifths = it },
                    changeSourceMode = { keyMode = it },
                    toggleTargetKey = { key ->
                        modulationTargetKeys = if (key in modulationTargetKeys) {
                            modulationTargetKeys - key
                        } else {
                            modulationTargetKeys + key
                        }
                    },
                    toggleChord = { chordId ->
                        modulationChordIds = if (chordId in modulationChordIds) {
                            modulationChordIds - chordId
                        } else {
                            modulationChordIds + chordId
                        }
                    },
                    changePitchDisplayMode = { modulationPitchMode = it },
                    changeSolverPreset = { modulationSolver = it },
                    changeSourceChordCount = { modulationSourceChordCount = it.coerceIn(1, 6) },
                    changeTargetChordCount = { modulationTargetChordCount = it.coerceIn(2, 8) },
                ),
                chorale = ChoraleEditorActions(
                    changeProgression = { choraleProgression = it },
                    toggleOpenVoice = { role ->
                        choraleOpenVoices = if (role in choraleOpenVoices) {
                            choraleOpenVoices - role
                        } else {
                            choraleOpenVoices + role
                        }
                    },
                    toggleConflict = { mark ->
                        choraleConflicts = if (mark in choraleConflicts) {
                            choraleConflicts - mark
                        } else {
                            // A suspension needs a voice free to split the chord it lands on.
                            choraleOpenVoices = choraleOpenVoices + mark.role
                            choraleConflicts + mark
                        }
                    },
                    changeSopranoContour = { choraleContour = it },
                    changeAllowFirstInversion = { choraleAllowFirstInversion = it },
                ),
                run = ExplorationRunActions(
                    changeCandidateCount = { candidateCount = it.coerceIn(1, 12) },
                    changeDiversify = { enabled ->
                        diversify = enabled
                        if (enabled && diversitySeed == 0L) diversitySeed = 1L
                    },
                    reroll = { diversitySeed += 1L },
                    run = ::run,
                ),
            ),
                )

                OutputPanel(
                    output = output,
                    selectedIndex = selectedCandidateIndex,
                    onSelectIndex = { selectedCandidateIndex = it },
                    running = running,
                    playback = playback,
                )
            }
        }
    }
}
