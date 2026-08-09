package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgram

/**
 * 无共同音的和弦连接。教材：到本阶段已学完前面全部材料，只要遵循前述进行的一般约定、避免平五平八，便可**自由**
 * 使用无共同音连接。因此本练习**复用综合练习最全阶段的完整词汇**（三和弦 + 六和弦 + 四六和弦 + 七和弦，含导 /
 * 减 / 增和弦及其转位）与全部章节规则，只把全局的「相邻必须有共同音」约束放开（`requireAdjacentCommonTone = false`）。
 *
 * 放开的仅是**全局共同音要求**：具体和弦的不协和音（导 / 减五度、增五度、七音）仍须按各自规则由前一和弦保持预备，
 * 那属于该音的局部要求，不受此练习影响；平五 / 平八交给求解器通用检查。小调下沿用 [SchoenbergMinorChapter] 的旋律
 * 硬要求。大 / 小调各一节点（`NO_COMMON_TONE_MAJOR/MINOR`），调式由传入 [key] 决定。
 */
object SchoenbergNoCommonToneChapter {
    fun program(
        key: Key,
        continuationChordCount: Int,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 160),
    ): ConstraintProgram =
        SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = continuationChordCount,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            progression = progression,
            searchConfig = searchConfig,
            requireAdjacentCommonTone = false,
        )

    fun enumerate(key: Key, continuationChordCount: Int): List<SchoenbergSymbolicProgression> =
        SchoenbergIntegratedTechTree.enumerate(
            key = key,
            options = SchoenbergIntegratedTechTree.EnumerationOptions(
                continuationChordCount = continuationChordCount,
                treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
                requireAdjacentCommonTone = false,
            ),
        )
}
