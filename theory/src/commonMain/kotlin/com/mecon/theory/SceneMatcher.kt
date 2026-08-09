package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhChord
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhWritingSlot
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey

/**
 * 场景匹配引擎（docs/theory/rule-scenes.md §3）。
 *
 * [enumerate] 逐槽 DFS + 增量剪枝：每放一个和弦就对前缀跑「已可判定」的 facet 检查，死前缀立即回溯，
 * 不穷举完再过滤。[appliesInContext] 给 [RuleCatalog.applicability] 做 window-2 快捷投影，
 * [instantiate] 把场景落成写作槽供探索 runner 出谱例。
 *
 * S0 只实现 MAY（符号级必要条件）；CONFIRMED 见 [verify]（占位）。
 */

/** 词汇表：调内三和弦 / 七和弦 × policy 允许的转位集合。 */
data class ChordVocabulary(
    val key: Key,
    val triads: List<NaturalTriad>,
    val positions: Set<TextbookTriadPosition>,
    val seventhPositions: Set<TextbookSeventhPosition>,
) {
    init {
        require(positions.isNotEmpty()) { "vocabulary must allow at least one position" }
        require(seventhPositions.isNotEmpty()) { "vocabulary must allow at least one seventh position" }
    }

    /** 展开为给定场景 / 槽位 arity 下的符号选择。 */
    fun choices(
        sceneArity: ChordArity,
        slotArity: ChordArity,
        allowedTriadPositions: Set<TextbookTriadPosition>? = null,
        allowedSeventhPositions: Set<TextbookSeventhPosition>? = null,
    ): List<SymbolicChordSpec> =
        if (sceneArity == ChordArity.SEVENTH) {
            (1..7).flatMap { degree ->
                val chord = when (slotArity) {
                    ChordArity.TRIAD -> DominantSeventhRules.triadInKey(key, degree)
                    ChordArity.SEVENTH -> DominantSeventhRules.seventhChordInKey(key, degree)
                }
                (allowedSeventhPositions ?: seventhPositions)
                    .filter { it.ordinal < chord.chord.pitchClasses.size }
                    .map { SymbolicChordSpec.seventhSceneChord(chord, it, slotArity) }
            }
        } else {
            triads.flatMap { triad ->
                (allowedTriadPositions ?: positions).map { SymbolicChordSpec.triadSceneChord(triad, it) }
            }
        }

    companion object {
        fun fromKey(
            key: Key,
            positions: Set<TextbookTriadPosition> = setOf(
                TextbookTriadPosition.ROOT_POSITION,
                TextbookTriadPosition.FIRST_INVERSION,
                TextbookTriadPosition.SECOND_INVERSION,
            ),
            seventhPositions: Set<TextbookSeventhPosition> = setOf(
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.FIRST_INVERSION,
                TextbookSeventhPosition.SECOND_INVERSION,
                TextbookSeventhPosition.THIRD_INVERSION,
            ),
        ): ChordVocabulary = ChordVocabulary(key, NaturalTriads.inKey(key), positions, seventhPositions)
    }
}

/** 一个槽的符号级选择（音级 + 和弦规模 + 转位），尚未落成具体声部音高。 */
data class SymbolicChordSpec(
    val degree: Int,
    val quality: ChordQuality,
    val chord: Chord,
    val arity: ChordArity,
    val positionName: String,
    val naturalTriad: NaturalTriad? = null,
    val triadPosition: TextbookTriadPosition? = null,
    val seventhChord: TextbookSeventhChord? = null,
    val seventhPosition: TextbookSeventhPosition? = null,
) {
    val bassPitchClass: PitchClass
        get() {
            val index = triadPosition?.ordinal ?: seventhPosition?.ordinal ?: 0
            return chord.pitchClasses[index]
        }

    val triad: NaturalTriad
        get() = naturalTriad ?: error("Symbolic chord $degree/$quality is not a triad-scene chord")

    val position: TextbookTriadPosition
        get() = triadPosition ?: error("Symbolic chord $degree/$quality does not use triad positions")

    companion object {
        fun triadSceneChord(triad: NaturalTriad, position: TextbookTriadPosition): SymbolicChordSpec =
            SymbolicChordSpec(
                degree = triad.degree,
                quality = triad.quality,
                chord = triad.chord,
                arity = ChordArity.TRIAD,
                positionName = position.name,
                naturalTriad = triad,
                triadPosition = position,
            )

        fun seventhSceneChord(
            chord: TextbookSeventhChord,
            position: TextbookSeventhPosition,
            arity: ChordArity,
        ): SymbolicChordSpec =
            SymbolicChordSpec(
                degree = chord.degree,
                quality = chord.quality,
                chord = chord.chord,
                arity = arity,
                positionName = position.name,
                seventhChord = chord,
                seventhPosition = position,
            )
    }
}

/** 命中说明：哪个 facet 绑定到哪个槽。 */
data class SceneBindingNote(
    val facetIndex: Int,
    val slot: Int,
    val detail: String,
)

/** 一条符号级适用进行 + facet 绑定说明。 */
data class SymbolicMatch(
    val slots: List<SymbolicChordSpec>,
    val bindings: List<SceneBindingNote>,
)

/** 验证级：MAY = 符号级必要条件；CONFIRMED = 跑小规模 voicing 求解确认（S0 未实现）。 */
enum class VerifyLevel {
    MAY,
    CONFIRMED,
}

object SceneMatcher {
    /**
     * 在词汇表上枚举适用于 [rule] 的符号级进行。逐槽 DFS，每放一个和弦即对前缀做增量剪枝。
     */
    fun enumerate(
        rule: RuleScene,
        key: Key,
        vocabulary: ChordVocabulary,
        windowLimit: Int = 3,
    ): List<SymbolicMatch> {
        // KeyContext 与槽无关，进入 DFS 前先判一次。
        if (rule.facets.filterIsInstance<KeyContext>().any { !keyMatches(it, key) }) return emptyList()

        val maxLen = minOf(rule.window.last, windowLimit)
        if (maxLen < rule.window.first) return emptyList()

        val results = mutableListOf<SymbolicMatch>()
        val prefix = ArrayList<SymbolicChordSpec>()
        val pattern = rule.facets.filterIsInstance<ChordPattern>().firstOrNull()

        fun choicesForSlot(index: Int): List<SymbolicChordSpec> {
            val slot = pattern?.slots?.getOrNull(index)
            val slotArity = slot?.arity ?: rule.chordArity
            return vocabulary.choices(
                sceneArity = rule.chordArity,
                slotArity = slotArity,
                allowedTriadPositions = slot?.positions,
                allowedSeventhPositions = slot?.seventhPositions,
            )
        }

        fun dfs() {
            val len = prefix.size
            if (len in rule.window && rule.facets.all { facetSatisfied(it, prefix, key, rule.chordArity) }) {
                results += SymbolicMatch(prefix.toList(), bindingsFor(rule, prefix, key))
            }
            if (len == maxLen) return
            for (choice in choicesForSlot(len)) {
                prefix.add(choice)
                if (rule.facets.all { facetPrefixAlive(it, prefix, key, rule.chordArity) }) dfs()
                prefix.removeAt(prefix.lastIndex)
            }
        }
        dfs()
        return results
    }

    /**
     * CONFIRMED 验证（rule-scenes §4）🚧：对每条符号进行跑小规模 voicing 求解确认规则本体产生目标 finding。
     * S0 未实现，[VerifyLevel.CONFIRMED] 暂等价于 MAY，直接返回符号级结果。
     */
    fun verify(
        rule: RuleScene,
        key: Key,
        vocabulary: ChordVocabulary,
        windowLimit: Int = 3,
        level: VerifyLevel = VerifyLevel.MAY,
    ): List<SymbolicMatch> = enumerate(rule, key, vocabulary, windowLimit)

    /**
     * window-2 投影：把场景的音级相关约束投影到 (fromDegree, toDegree) 二元组，供 applicability 快捷判定。
     * 无音级相关约束的场景（仅转位/调式）视为适用于任意上下文，保持一转位低音线等"总适用"规则语义。
     */
    fun appliesInContext(
        scenes: List<RuleScene>,
        fromDegree: Int,
        toDegree: Int,
    ): Boolean {
        if (scenes.isEmpty()) return true
        return scenes.any { scene -> scene.facets.all { projectionHolds(it, fromDegree, toDegree) } }
    }

    /**
     * 把场景落成写作槽（runner 出谱例用）：
     * - 有 [ChordPattern]：逐槽取 degrees 单值 / sameChordAsSlot 引用 / 由 (from,to) 位置填充；
     *   allowedPositions = slot.positions ?: {ROOT_POSITION}；qualities 单值作为 triad 过滤。
     * - 单槽 uniform 模式（无 degrees / 无 sameChordAsSlot，如一转位低音线、减三和弦）按 [from,to] 平铺。
     * - 无 ChordPattern（仅 RootMotion 等根位连接规则）：退化为 [from,to] 原位两槽。
     */
    fun instantiate(
        scene: RuleScene,
        key: Key,
        fromDegree: Int,
        toDegree: Int,
    ): List<TextbookTriadWritingSlot> {
        val pattern = scene.facets.filterIsInstance<ChordPattern>().firstOrNull()
            ?: return listOf(rootSlot(key, fromDegree), rootSlot(key, toDegree))

        val slots = pattern.slots
        val uniform = slots.size == 1 &&
            slots[0].degrees == null &&
            slots[0].sameChordAsSlot == null
        if (uniform) {
            val spec = slots[0]
            return listOf(fromDegree, toDegree).map { degree -> slotFrom(spec, key, degree) }
        }

        val degrees = IntArray(slots.size)
        return slots.mapIndexed { index, spec ->
            val degree = spec.degrees?.singleOrNull()
                ?: spec.sameChordAsSlot?.let { degrees[it] }
                ?: positionalDegree(index, fromDegree, toDegree)
            degrees[index] = degree
            slotFrom(spec, key, degree)
        }
    }

    /**
     * 把七和弦场景（[RuleScene.chordArity] = SEVENTH）落成七和弦写作槽（runner 出谱例用）。
     *
     * 七和弦进行完全由场景数据指定，不依赖 (from,to) 投影：每个 [SlotChordSpec] 给出
     * `degrees` 单值 + `seventhPositions`（默认原位）+ `arity`（默认 SEVENTH，解决和弦声明 TRIAD）；
     * 槽五音完整性由指向该槽的 [DoublingExpectation] facet 提供（完全/省五交替，见 rule-scenes §4）。
     *
     * 这取代了 runner 中 `dominantSeventhSlots` / `circleOfFifthsSlots` 等按规则集合硬编码的槽位扩展
     * （solver-api §1 判据）。
     */
    fun instantiateSeventh(scene: RuleScene, key: Key): List<TextbookSeventhWritingSlot> {
        val pattern = scene.facets.filterIsInstance<ChordPattern>().firstOrNull()
            ?: error("seventh scene ${scene.ruleId.value} must declare a ChordPattern")
        val fifthBySlot = scene.facets.filterIsInstance<DoublingExpectation>().associate { it.slot to it.fifth }
        return pattern.slots.mapIndexed { index, spec ->
            val degree = spec.degrees?.singleOrNull()
                ?: error("seventh scene ${scene.ruleId.value} slot $index must fix a single degree")
            val chord = when (spec.arity ?: scene.chordArity) {
                ChordArity.SEVENTH -> DominantSeventhRules.seventhChordInKey(key, degree)
                ChordArity.TRIAD -> DominantSeventhRules.triadInKey(key, degree)
            }
            TextbookSeventhWritingSlot(
                chord = chord,
                allowedPositions = spec.seventhPositions ?: setOf(TextbookSeventhPosition.ROOT_POSITION),
                fifthConstraint = fifthBySlot[index],
            )
        }
    }

    // ---- facet 增量判定 ----------------------------------------------------

    /** 前缀是否仍可能成活：已完全放好且被约束覆盖的部分不得证伪。 */
    private fun facetPrefixAlive(
        facet: SceneFacet,
        prefix: List<SymbolicChordSpec>,
        key: Key,
        sceneArity: ChordArity,
    ): Boolean =
        when (facet) {
            is ChordPattern -> prefix.indices.all { i ->
                i >= facet.slots.size || slotMatches(facet.slots[i], prefix, i, sceneArity)
            }
            is KeyContext -> keyMatches(facet, key)
            is RootMotion -> {
                val a = facet.fromSlot
                if (prefix.size <= a + 1) true else degreeDistance(prefix[a].degree, prefix[a + 1].degree) in facet.steps
            }
            is VoiceMotion -> {
                val a = facet.fromSlot
                if (prefix.size <= a + 1) true else voiceMotionHolds(facet, prefix, key)
            }
            is BassMotion -> bassMotionPrefixAlive(facet, prefix)
            // 五音完整性是 voicing 级属性，非符号必要条件——MAY 枚举恒真，仅 instantiate 消费（rule-scenes §4）。
            is DoublingExpectation -> true
        }

    /** 该 facet 是否被给定完整进行（当前 prefix 长度）满足。 */
    private fun facetSatisfied(
        facet: SceneFacet,
        slots: List<SymbolicChordSpec>,
        key: Key,
        sceneArity: ChordArity,
    ): Boolean =
        when (facet) {
            is ChordPattern -> slots.size >= facet.slots.size &&
                facet.slots.indices.all { slotMatches(facet.slots[it], slots, it, sceneArity) }
            is KeyContext -> keyMatches(facet, key)
            is RootMotion -> slots.size > facet.fromSlot + 1 &&
                degreeDistance(slots[facet.fromSlot].degree, slots[facet.fromSlot + 1].degree) in facet.steps
            is VoiceMotion -> slots.size > facet.fromSlot + 1 && voiceMotionHolds(facet, slots, key)
            is BassMotion -> bassMotionSatisfied(facet, slots, key)
            is DoublingExpectation -> true
        }

    private fun slotMatches(
        spec: SlotChordSpec,
        slots: List<SymbolicChordSpec>,
        index: Int,
        sceneArity: ChordArity,
    ): Boolean {
        val slot = slots[index]
        val slotArity = spec.arity ?: sceneArity
        if (slot.arity != slotArity) return false
        if (spec.degrees != null && slot.degree !in spec.degrees) return false
        if (spec.qualities != null && slot.quality !in spec.qualities) return false
        if (spec.positions != null && slot.triadPosition !in spec.positions) return false
        if (spec.seventhPositions != null && slot.seventhPosition !in spec.seventhPositions) return false
        val ref = spec.sameChordAsSlot
        if (ref != null && slots[ref].chord.pitchClasses.toSet() != slot.chord.pitchClasses.toSet()) return false
        return true
    }

    private fun keyMatches(facet: KeyContext, key: Key): Boolean =
        when (facet.mode) {
            RuleKeyModeConstraint.MAJOR -> key.mode == Mode.IONIAN
            RuleKeyModeConstraint.MINOR -> key.mode == Mode.AEOLIAN
        }

    private fun voiceMotionHolds(facet: VoiceMotion, slots: List<SymbolicChordSpec>, key: Key): Boolean {
        val a = facet.fromSlot
        val fromPc = degreePitchClass(key, facet.from)
        val toPc = degreePitchClass(key, facet.to)
        val fromTones = slots[a].chord.pitchClasses.toSet()
        val toTones = slots[a + 1].chord.pitchClasses.toSet()
        return fromPc in fromTones && toPc in toTones
    }

    private fun bassMotionPrefixAlive(facet: BassMotion, prefix: List<SymbolicChordSpec>): Boolean =
        when (facet.kind) {
            BassMotionKind.PEDAL_HELD -> {
                val a = facet.fromSlot
                if (prefix.size <= a) true
                else prefix.drop(a).all { it.bassPitchClass == prefix[a].bassPitchClass }
            }
            // 经过需要三槽才能判方向；未满三槽不剪。
            BassMotionKind.PASSING_STEP -> true
        }

    private fun bassMotionSatisfied(facet: BassMotion, slots: List<SymbolicChordSpec>, key: Key): Boolean {
        val a = facet.fromSlot
        return when (facet.kind) {
            BassMotionKind.PEDAL_HELD ->
                slots.size > a && slots.drop(a).all { it.bassPitchClass == slots[a].bassPitchClass }
            BassMotionKind.PASSING_STEP -> {
                if (slots.size < a + 3) return false
                // 自然音阶级进：三槽低音同向、相邻各差一个音阶度（不要求等半音差，故大/小三度跨度的经过都成立）。
                val s1 = scaleStep(key, slots[a].bassPitchClass, slots[a + 1].bassPitchClass)
                val s2 = scaleStep(key, slots[a + 1].bassPitchClass, slots[a + 2].bassPitchClass)
                s1 != null && s1 == s2
            }
        }
    }

    // ---- applicability 投影 -----------------------------------------------

    private fun projectionHolds(facet: SceneFacet, fromDegree: Int, toDegree: Int): Boolean =
        when (facet) {
            is ChordPattern -> {
                val s0 = facet.slots.getOrNull(0)
                val s1 = facet.slots.getOrNull(1)
                val ok0 = s0?.degrees?.contains(fromDegree) ?: true
                val ok1 = s1?.degrees?.contains(toDegree) ?: true
                val sameOk = if (s1?.sameChordAsSlot == 0) fromDegree == toDegree else true
                ok0 && ok1 && sameOk
            }
            is RootMotion -> if (facet.fromSlot == 0) degreeDistance(fromDegree, toDegree) in facet.steps else true
            // 调式/声部/低音/重复音 facet 不参与音级投影（由 keyMode/degreeQualities 处理）。
            is KeyContext, is VoiceMotion, is BassMotion, is DoublingExpectation -> true
        }

    // ---- instantiate 辅助 -------------------------------------------------

    private fun slotFrom(spec: SlotChordSpec, key: Key, degree: Int): TextbookTriadWritingSlot {
        val quality = spec.qualities?.singleOrNull()
        val triad = textbookTriadInKey(key, degree, quality)
        val positions = spec.positions ?: setOf(TextbookTriadPosition.ROOT_POSITION)
        return TextbookTriadWritingSlot(triad, positions)
    }

    private fun rootSlot(key: Key, degree: Int): TextbookTriadWritingSlot =
        TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, degree))

    private fun positionalDegree(index: Int, fromDegree: Int, toDegree: Int): Int =
        if (index == 0) fromDegree else toDegree

    // ---- 绑定说明 ----------------------------------------------------------

    private fun bindingsFor(rule: RuleScene, slots: List<SymbolicChordSpec>, key: Key): List<SceneBindingNote> =
        rule.facets.mapIndexedNotNull { facetIndex, facet ->
            when (facet) {
                is ChordPattern -> SceneBindingNote(facetIndex, 0, "和弦序列命中：${slots.joinToString(" ") { degreeLabel(it) }}")
                is RootMotion -> SceneBindingNote(
                    facetIndex,
                    facet.fromSlot,
                    "根音音级距离 ${degreeDistance(slots[facet.fromSlot].degree, slots[facet.fromSlot + 1].degree)}",
                )
                is VoiceMotion -> SceneBindingNote(facetIndex, facet.fromSlot, "声部走向 ${facet.from.degree}→${facet.to.degree}")
                is BassMotion -> SceneBindingNote(facetIndex, facet.fromSlot, "低音形态 ${facet.kind}")
                is DoublingExpectation -> SceneBindingNote(facetIndex, facet.slot, "五音完整性 ${facet.fifth}")
                is KeyContext -> null
            }
        }

    private fun degreeLabel(slot: SymbolicChordSpec): String =
        "${slot.degree}(${slot.positionName.take(4)})"

    // ---- 数值工具 ----------------------------------------------------------

    /** 与 SelectionContext.degreeDistance 同式：两音级的最小七音级距离。 */
    internal fun degreeDistance(a: Int, b: Int): Int {
        val up = (b - a).mod(DIATONIC_STEPS)
        return minOf(up, DIATONIC_STEPS - up)
    }

    private fun degreePitchClass(key: Key, spec: ScaleDegreeSpec): PitchClass {
        val base = key.scale.pitchClasses[spec.degree - 1]
        val offset = when (spec.alteration) {
            DegreeAlteration.NATURAL -> 0
            DegreeAlteration.RAISED -> 1
            DegreeAlteration.LOWERED -> -1
        }
        return base.transpose(offset)
    }

    /**
     * 两低音在当前调自然音阶上的有符号级进（相邻音阶度 = ±1），否则 null。
     * 经过四六用自然音阶级进而非半音差判定，故 D-E-F（全音+半音）这类小三度跨度的级进也成立。
     * 保守起见只认自然音阶成员；小调升音（导音等）低音的经过判定待引入音阶度低音模型后放开（rule-scenes §7）。
     */
    private fun scaleStep(key: Key, from: PitchClass, to: PitchClass): Int? {
        val degrees = key.scale.pitchClasses
        val i = degrees.indexOf(from)
        val j = degrees.indexOf(to)
        if (i < 0 || j < 0) return null
        return when ((j - i).mod(degrees.size)) {
            1 -> 1
            degrees.size - 1 -> -1
            else -> null
        }
    }

    private const val DIATONIC_STEPS = 7
}
