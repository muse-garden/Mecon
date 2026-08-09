package com.mecon.exploration

import kotlin.jvm.JvmInline

import com.mecon.api.primitive.EventId
import com.mecon.api.storage.StorageScore
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.ChordQuality
import com.mecon.theory.SearchConfig
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class CellId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class ExplorationDocument(
    val version: Int = 1,
    val title: String,
    val cells: List<ExplorationCell>,
)

@Serializable
sealed interface ExplorationCell {
    val id: CellId
}

@Serializable
@SerialName("text")
data class TextCell(
    override val id: CellId,
    val text: String,
) : ExplorationCell

@Serializable
@SerialName("score")
data class ScoreCell(
    override val id: CellId,
    val score: StorageScore,
    val caption: String = "",
) : ExplorationCell

@Serializable
@SerialName("request")
data class RequestCell(
    override val id: CellId,
    val request: CellRequest,
    val material: StorageScore? = null,
    val output: CellOutput? = null,
) : ExplorationCell

@Serializable
sealed interface CellRequest

@Serializable
@SerialName("rule-example")
data class RuleExampleRequest(
    val key: KeySpec = KeySpec(),
    val from: DegreeSpec,
    val to: DegreeSpec,
    val selectedRules: List<String> = emptyList(),
    val demonstrate: DemonstrationSpec? = null,
    val search: SearchSpec = SearchSpec(),
) : CellRequest

@Serializable
@SerialName("progression")
data class ProgressionRequest(
    val key: KeySpec = KeySpec(),
    val slots: List<ProgressionSlot>,
    val policyId: String = "introductory-triads",
    val search: SearchSpec = SearchSpec(),
) : CellRequest

@Serializable
@SerialName("schoenberg-exercise")
data class SchoenbergExerciseRequest(
    val key: KeySpec = KeySpec(),
    val exerciseId: String = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
    val continuationChordCount: Int = 1,
    val progression: SymbolicProgression? = null,
    val selections: Map<String, List<String>> = emptyMap(),
    val chordFilters: List<SchoenbergChordFilterSpec> = emptyList(),
    val includeDeceptiveCadence: Boolean = false,
    val includeCadentialSixFour: Boolean = false,
    val search: SearchSpec = SearchSpec(maxResults = 4, beamWidth = 128),
) : CellRequest {
    init {
        require(continuationChordCount >= 1) { "continuationChordCount must be >= 1" }
    }
}

@Serializable
@SerialName("modulation-exercise")
data class ModulationExerciseCellRequest(
    val sourceKey: KeySpec = KeySpec(),
    val targetKey: KeySpec = KeySpec(fifths = 1),
    val pivotRoot: Int,
    val pivotQuality: ChordQuality,
    val sourceChordCount: Int = 2,
    val targetChordCount: Int = 4,
    val solverPreset: ModulationSolverSpec = ModulationSolverSpec.SCHOENBERG,
    val search: SearchSpec = SearchSpec(maxResults = 4, beamWidth = 192),
) : CellRequest {
    init {
        require(pivotRoot in 0..11) { "pivotRoot must be a pitch class value from 0 to 11" }
        require(sourceChordCount >= 1) { "sourceChordCount must be at least 1" }
        require(targetChordCount >= 2) { "targetChordCount must leave room for V-I" }
    }
}

@Serializable
enum class ModulationSolverSpec {
    FREE,
    SCHOENBERG,
}

@Serializable
data class ProgressionSlot(
    val degree: DegreeSpec,
    val spacing: SpacingPreference = SpacingPreference.ANY,
)

@Serializable
data class DemonstrationSpec(
    val ruleId: String,
)

@Serializable
data class DegreeSpec(
    val degree: Int,
) {
    init {
        require(degree in 1..7) { "degree must be in 1..7" }
    }
}

@Serializable
data class KeySpec(
    val fifths: Int = 0,
    val mode: KeyModeSpec = KeyModeSpec.MAJOR,
) {
    init {
        require(fifths in -7..7) { "fifths must be in -7..7" }
    }
}

@Serializable
enum class KeyModeSpec {
    MAJOR,
    MINOR,
}

@Serializable
enum class SpacingPreference {
    ANY,
    CLOSE,
    OPEN,
}

@Serializable
data class SearchSpec(
    val maxResults: Int = 4,
    val beamWidth: Int = 64,
    /** 是否开启多样化重启搜索（diverse-search.md）。关闭时走确定性贪心 DFS。 */
    val diversify: Boolean = false,
    /** 多样化搜索种子；相同 seed 可复现候选与顺序，换 seed 得到「再来一批」。 */
    val seed: Long = 0L,
) {
    init {
        require(maxResults > 0) { "maxResults must be positive" }
        require(beamWidth > 0) { "beamWidth must be positive" }
    }
}

/** 协议级 [SearchSpec] → 引擎级 [SearchConfig]，集中承载多样化搜索开关与种子映射。 */
fun SearchSpec.toSearchConfig(): SearchConfig =
    SearchConfig(
        maxResults = maxResults,
        beamWidth = beamWidth,
        diversity = DiversitySearchConfig(enabled = diversify, seed = seed),
    )

@Serializable
data class CellOutput(
    val fingerprint: String,
    val candidates: List<OutputCandidate>,
    val diagnostics: List<String> = emptyList(),
    /** 结构化诊断（solver-api.md §5）；[diagnostics] 为其中文渲染回退，保持向后兼容。 */
    val structuredDiagnostics: List<SolverDiagnostic> = emptyList(),
    val comparisonGroups: List<CandidateComparison> = emptyList(),
)

@Serializable
data class OutputCandidate(
    val score: StorageScore,
    val totalScore: Double,
    val findings: List<StoredFinding>,
    val breakdownEntries: List<StoredScoreEntry>,
)

@Serializable
data class StoredFinding(
    val ruleId: String,
    val severity: String,
    val kind: String,
    val messageKey: String,
    val messageArgs: List<String> = emptyList(),
    val anchors: List<EventId> = emptyList(),
    val relatedAnchors: List<EventId> = emptyList(),
    val isDemonstrationTarget: Boolean = false,
)

@Serializable
data class CandidateComparison(
    val title: String,
    val correctCandidateIndex: Int,
    val incorrectCandidateIndex: Int,
    val explanation: String,
)

@Serializable
data class StoredScoreEntry(
    val ruleId: String,
    val amount: Double,
    val reason: String,
)
