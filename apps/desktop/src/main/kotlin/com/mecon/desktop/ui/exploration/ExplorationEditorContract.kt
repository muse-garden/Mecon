package com.mecon.desktop.ui.exploration

import com.mecon.exploration.FormSpec
import com.mecon.exploration.ChoraleContourDirectionSpec
import com.mecon.exploration.ChoraleVoiceRoleSpec
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.SchoenbergChordFilterSpec
import com.mecon.exploration.SymbolicProgression
import com.mecon.theory.RuleExampleInputSpec
import com.mecon.theory.RuleId

internal data class ExplorationKeyState(
    val fifths: Int,
    val mode: KeyModeSpec,
)

internal data class RuleExampleEditorState(
    val schema: RuleExampleInputSpec,
    val companionRuleId: RuleId,
    val demonstrateRuleId: RuleId?,
    val fromDegree: Int,
    val toDegree: Int,
)

internal data class ProgressionEditorState(
    val text: String,
    val policyId: String,
)

internal data class SchoenbergExerciseEditorState(
    val exerciseId: String,
    val continuationChordCount: Int,
    val selectedProgression: SymbolicProgression?,
    val selections: Map<String, List<String>>,
    val chordFilters: List<SchoenbergChordFilterSpec>,
    val includeDeceptiveCadence: Boolean,
    val includeCadentialSixFour: Boolean,
)

internal data class ChoraleEditorState(
    /** Scale degrees, one per chord, e.g. "1 4 5 1". */
    val progression: String,
    /** Voices allowed to subdivide a chord; the rest simply hold. */
    val openVoices: Set<ChoraleVoiceRoleSpec>,
    /** Chord index plus the voice asked to suspend into it. */
    val conflicts: Set<ChoraleConflictMark>,
    val sopranoContour: ChoraleContourDirectionSpec?,
    val allowFirstInversion: Boolean,
)

/** One user-placed conflict: "this voice suspends into this chord". */
internal data class ChoraleConflictMark(
    val slot: Int,
    val role: ChoraleVoiceRoleSpec,
)

internal data class ChoraleEditorActions(
    val changeProgression: (String) -> Unit,
    val toggleOpenVoice: (ChoraleVoiceRoleSpec) -> Unit,
    val toggleConflict: (ChoraleConflictMark) -> Unit,
    val changeSopranoContour: (ChoraleContourDirectionSpec?) -> Unit,
    val changeAllowFirstInversion: (Boolean) -> Unit,
)

internal data class ExplorationRunState(
    val stale: Boolean,
    val running: Boolean,
    val candidateCount: Int,
    val diversify: Boolean,
    val enabled: Boolean = true,
)

internal data class ExplorationInputState(
    val mode: ExplorationMode,
    val activeForm: FormSpec,
    val selectedRule: RuleId,
    val key: ExplorationKeyState,
    val ruleExample: RuleExampleEditorState,
    val progression: ProgressionEditorState,
    val schoenberg: SchoenbergExerciseEditorState,
    val modulation: ModulationEditorState,
    val chorale: ChoraleEditorState,
    val run: ExplorationRunState,
)

internal data class ExplorationKeyActions(
    val changeFifths: (Int) -> Unit,
    val changeMode: (KeyModeSpec) -> Unit,
)

internal data class RuleExampleEditorActions(
    val changeCompanionRule: (RuleId) -> Unit,
    val changeFromDegree: (Int) -> Unit,
    val changeToDegree: (Int) -> Unit,
    val changeDemonstration: (RuleId?) -> Unit,
)

internal data class ProgressionEditorActions(
    val changeText: (String) -> Unit,
    val changePolicy: (String) -> Unit,
)

internal data class SchoenbergExerciseEditorActions(
    val changeExercise: (String) -> Unit,
    val changeContinuationChordCount: (Int) -> Unit,
    val changeProgression: (SymbolicProgression?) -> Unit,
    val changeSelection: (String, List<String>) -> Unit,
    val changeChordFilters: (List<SchoenbergChordFilterSpec>) -> Unit,
    val changeDeceptiveCadence: (Boolean) -> Unit,
    val changeCadentialSixFour: (Boolean) -> Unit,
)

internal data class ExplorationRunActions(
    val changeCandidateCount: (Int) -> Unit,
    val changeDiversify: (Boolean) -> Unit,
    val reroll: () -> Unit,
    val run: () -> Unit,
)

internal data class ExplorationInputActions(
    val changeMode: (ExplorationMode) -> Unit,
    val selectRule: (RuleId) -> Unit,
    val key: ExplorationKeyActions,
    val ruleExample: RuleExampleEditorActions,
    val progression: ProgressionEditorActions,
    val schoenberg: SchoenbergExerciseEditorActions,
    val modulation: ModulationEditorActions,
    val chorale: ChoraleEditorActions,
    val run: ExplorationRunActions,
)
