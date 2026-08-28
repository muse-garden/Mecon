package com.mecon.theory.voiceleading

/**
 * Ordered multi-step voice leading with cardinality-changing operators.
 *
 * The base adjacency graph ([VoiceLeadingTransformations]) fixes three limits: one move per tone,
 * shortest paths only, constant cardinality. Suspensions, passing chords and non-chord tones all
 * live outside those limits, so this layer parameterises them instead of forking the graph.
 * Design and the musical reading of each knob: `docs/theory/voice-leading-pathways.md`.
 */
object VoiceLeadingPathways {

    /**
     * Every pathway from [sourcePitchClasses] permitted by [options], grouped-free and
     * deterministically ordered.
     *
     * Distinct orderings of the same move multiset are distinct pathways on purpose — the ordering
     * is what turns `I -> iii -> V` into `I -> Gsus4 -> V`.
     */
    fun search(
        sourcePitchClasses: Collection<Int>,
        options: VoiceLeadingPathSearchOptions = VoiceLeadingPathSearchOptions(),
    ): List<VoiceLeadingPathway> {
        val universe = options.universe
        val source = sourcePitchClasses.map { it.mod(12) }.distinct().sorted()
        require(source.size in universe.cardinalities) {
            "Source cardinality ${source.size} is not registered in ${universe.id.value}"
        }
        val sourceReadings = universe.recognize(source)
        require(sourceReadings.isNotEmpty()) {
            "Source pitch classes are not recognized in ${universe.id.value}"
        }
        val sourceMask = pitchClassMask(source)
        val sourceNode = VoiceLeadingPathNode(
            stepIndex = 0,
            pitchClasses = source,
            columns = source.mapIndexed { index, pitchClass ->
                VoiceLeadingPathColumn(
                    id = index,
                    sourceToneIndices = listOf(index),
                    pitchClass = pitchClass,
                    moveCount = 0,
                )
            },
            readings = sourceReadings,
            stability = universe.stabilityOfSet(source) ?: VoiceLeadingStability.TRANSITIONAL,
        )

        val collected = mutableListOf<VoiceLeadingPathway>()
        var budget = options.nodeBudget
        val steps = ArrayDeque<VoiceLeadingStep>()
        val nodes = ArrayDeque<VoiceLeadingPathNode>().apply { addLast(sourceNode) }
        val visitedMasks = mutableSetOf(sourceMask)

        fun expand(current: VoiceLeadingPathNode, nextColumnId: Int) {
            if (steps.size >= options.maxSteps) return
            for (successor in successors(current, options, nextColumnId)) {
                if (budget <= 0) return
                budget--
                val mask = pitchClassMask(successor.columns.map { it.pitchClass })
                if (mask in visitedMasks) continue
                val readings = universe.readingsForMask(mask)
                if (readings.isEmpty()) continue
                val stability = if (
                    readings.any { universe.stabilityOf(it.definitionId) == VoiceLeadingStability.STABLE }
                ) VoiceLeadingStability.STABLE else VoiceLeadingStability.TRANSITIONAL
                val node = VoiceLeadingPathNode(
                    stepIndex = steps.size + 1,
                    pitchClasses = maskToPitchClasses(mask),
                    columns = successor.columns,
                    readings = readings,
                    stability = stability,
                )
                steps.addLast(successor.step.withOrder(steps.size + 1))
                nodes.addLast(node)
                visitedMasks += mask
                val emittable = stability == VoiceLeadingStability.STABLE ||
                    options.includeTransitionalTargets
                if (emittable) {
                    collected += VoiceLeadingPathway(
                        universeId = universe.id,
                        steps = steps.toList(),
                        nodes = nodes.toList(),
                    )
                }
                expand(node, successor.nextColumnId)
                visitedMasks -= mask
                nodes.removeLast()
                steps.removeLast()
            }
        }

        expand(sourceNode, source.size)
        return collected
            .distinctBy { it.identity }
            .sortedWith(
                compareBy(
                    { it.stepCount },
                    { pitchClassMask(it.targetPitchClasses) },
                    { it.identity },
                )
            )
            // Capped per (target, length) so that longer passing pathways are not crowded out by
            // the short direct connections to the same chord.
            .groupBy { it.targetPitchClasses to it.stepCount }
            .flatMap { (_, pathways) -> pathways.take(options.maxPathwaysPerTarget) }
            .sortedWith(
                compareBy(
                    { it.stepCount },
                    { pitchClassMask(it.targetPitchClasses) },
                    { it.identity },
                )
            )
    }

    /**
     * All orderings that connect [sourcePitchClasses] to [targetPitchClasses].
     *
     * This is the "try the same connection in another order" entry point: the returned pathways
     * differ only in step order (and in which intermediate sonorities that order produces).
     */
    fun orderings(
        sourcePitchClasses: Collection<Int>,
        targetPitchClasses: Collection<Int>,
        options: VoiceLeadingPathSearchOptions = VoiceLeadingPathSearchOptions(),
    ): List<VoiceLeadingPathway> {
        val target = targetPitchClasses.map { it.mod(12) }.distinct().sorted()
        return search(sourcePitchClasses, options).filter { it.targetPitchClasses == target }
    }

    private data class Successor(
        val columns: List<VoiceLeadingPathColumn>,
        val step: VoiceLeadingStep,
        val nextColumnId: Int,
    )

    private fun successors(
        node: VoiceLeadingPathNode,
        options: VoiceLeadingPathSearchOptions,
        nextColumnId: Int,
    ): List<Successor> {
        val columns = node.columns
        val occupied = columns.mapTo(hashSetOf()) { it.pitchClass }
        val cardinalities = options.universe.cardinalities
        return buildList {
            columns.forEach { column ->
                if (column.moveCount >= options.maxMovesPerColumn) return@forEach
                ALLOWED_SEMITONE_MOVES.forEach { semitones ->
                    val to = (column.pitchClass + semitones).mod(12)
                    if (to in occupied) return@forEach
                    add(
                        Successor(
                            columns = columns.map {
                                if (it.id == column.id) {
                                    it.copy(pitchClass = to, moveCount = it.moveCount + 1)
                                } else it
                            },
                            step = VoiceLeadingStep.Shift(
                                order = 0,
                                columnId = column.id,
                                fromPitchClass = column.pitchClass,
                                toPitchClass = to,
                                semitones = semitones,
                            ),
                            nextColumnId = nextColumnId,
                        )
                    )
                }
            }
            if (options.allowSplit && columns.size + 1 in cardinalities) {
                columns.forEach { column ->
                    ALLOWED_SEMITONE_MOVES.forEach { semitones ->
                        val to = (column.pitchClass + semitones).mod(12)
                        if (to in occupied) return@forEach
                        add(
                            Successor(
                                columns = columns + VoiceLeadingPathColumn(
                                    id = nextColumnId,
                                    sourceToneIndices = column.sourceToneIndices,
                                    pitchClass = to,
                                    moveCount = 1,
                                ),
                                step = VoiceLeadingStep.Split(
                                    order = 0,
                                    columnId = column.id,
                                    branchColumnId = nextColumnId,
                                    fromPitchClass = column.pitchClass,
                                    toPitchClass = to,
                                    semitones = semitones,
                                ),
                                nextColumnId = nextColumnId + 1,
                            )
                        )
                    }
                }
            }
            if (options.allowFuse && columns.size - 1 in cardinalities) {
                columns.forEach { from ->
                    if (from.moveCount >= options.maxMovesPerColumn) return@forEach
                    columns.forEach inner@{ into ->
                        if (into.id == from.id) return@inner
                        val semitones = signedStep(from.pitchClass, into.pitchClass) ?: return@inner
                        add(
                            Successor(
                                columns = columns.mapNotNull {
                                    when (it.id) {
                                        from.id -> null
                                        into.id -> it.copy(
                                            sourceToneIndices = (it.sourceToneIndices +
                                                from.sourceToneIndices).distinct().sorted(),
                                            moveCount = maxOf(it.moveCount, from.moveCount + 1),
                                        )
                                        else -> it
                                    }
                                },
                                step = VoiceLeadingStep.Fuse(
                                    order = 0,
                                    columnId = from.id,
                                    intoColumnId = into.id,
                                    fromPitchClass = from.pitchClass,
                                    toPitchClass = into.pitchClass,
                                    semitones = semitones,
                                ),
                                nextColumnId = nextColumnId,
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Signed parsimonious distance from [from] to [to], or null when they are further apart. */
internal fun signedStep(from: Int, to: Int): Int? {
    val delta = (to - from).mod(12)
    return when {
        delta in 1..2 -> delta
        delta in 10..11 -> delta - 12
        else -> null
    }
}

data class VoiceLeadingPathSearchOptions(
    val universe: VoiceLeadingUniverse = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS,
    val maxSteps: Int = 2,
    /** 1 reproduces the base graph; >= 2 is what makes passing and neighbour tones expressible. */
    val maxMovesPerColumn: Int = 1,
    val allowSplit: Boolean = false,
    val allowFuse: Boolean = false,
    /** Emit pathways that end on a transitional sonority (the suspension chord itself). */
    val includeTransitionalTargets: Boolean = false,
    val maxPathwaysPerTarget: Int = 12,
    val nodeBudget: Int = 200_000,
) {
    init {
        require(maxSteps >= 1) { "A pathway needs at least one step" }
        require(maxMovesPerColumn >= 1) { "Every column must be allowed at least one move" }
        require(maxPathwaysPerTarget >= 1) { "At least one pathway per target must be kept" }
        require(nodeBudget >= 1) { "The search needs a positive node budget" }
    }
}

/** One tone lineage. A split shares [sourceToneIndices]; a fuse unions them. */
data class VoiceLeadingPathColumn(
    val id: Int,
    val sourceToneIndices: List<Int>,
    val pitchClass: Int,
    val moveCount: Int,
) {
    init {
        require(id >= 0)
        require(sourceToneIndices.isNotEmpty())
        require(pitchClass in 0..11)
        require(moveCount >= 0)
    }
}

sealed interface VoiceLeadingStep {
    /** 1-based position on the pathway. */
    val order: Int

    /** The column that moves; for [Split] this is the parent that stays put. */
    val columnId: Int
    val fromPitchClass: Int
    val toPitchClass: Int
    val semitones: Int

    fun withOrder(order: Int): VoiceLeadingStep

    data class Shift(
        override val order: Int,
        override val columnId: Int,
        override val fromPitchClass: Int,
        override val toPitchClass: Int,
        override val semitones: Int,
    ) : VoiceLeadingStep {
        override fun withOrder(order: Int) = copy(order = order)
    }

    /** One tone becomes two: the parent holds [fromPitchClass], the branch takes [toPitchClass]. */
    data class Split(
        override val order: Int,
        override val columnId: Int,
        val branchColumnId: Int,
        override val fromPitchClass: Int,
        override val toPitchClass: Int,
        override val semitones: Int,
    ) : VoiceLeadingStep {
        override fun withOrder(order: Int) = copy(order = order)
    }

    /** Two tones a step apart become one: [columnId] moves onto [intoColumnId]. */
    data class Fuse(
        override val order: Int,
        override val columnId: Int,
        val intoColumnId: Int,
        override val fromPitchClass: Int,
        override val toPitchClass: Int,
        override val semitones: Int,
    ) : VoiceLeadingStep {
        override fun withOrder(order: Int) = copy(order = order)
    }
}

data class VoiceLeadingPathNode(
    /** 0 for the source chord. */
    val stepIndex: Int,
    val pitchClasses: List<Int>,
    val columns: List<VoiceLeadingPathColumn>,
    val readings: List<VoiceLeadingChordReading>,
    val stability: VoiceLeadingStability,
) {
    init {
        require(stepIndex >= 0)
        require(pitchClasses == pitchClasses.distinct().sorted())
        require(columns.map { it.pitchClass }.distinct().sorted() == pitchClasses)
        require(readings.isNotEmpty()) { "A pathway node must be nameable in its universe" }
    }
}

data class VoiceLeadingPathway(
    val universeId: VoiceLeadingUniverseId,
    val steps: List<VoiceLeadingStep>,
    val nodes: List<VoiceLeadingPathNode>,
) {
    init {
        require(steps.isNotEmpty()) { "A pathway needs at least one step" }
        require(nodes.size == steps.size + 1) { "A pathway has one more node than steps" }
        require(steps.map { it.order } == (1..steps.size).toList())
    }

    val stepCount: Int get() = steps.size
    val sourceNode: VoiceLeadingPathNode get() = nodes.first()
    val targetNode: VoiceLeadingPathNode get() = nodes.last()
    val sourcePitchClasses: List<Int> get() = sourceNode.pitchClasses
    val targetPitchClasses: List<Int> get() = targetNode.pitchClasses

    /** Nodes strictly between source and target; empty for a single-step connection. */
    val intermediateNodes: List<VoiceLeadingPathNode>
        get() = if (nodes.size <= 2) emptyList() else nodes.subList(1, nodes.size - 1)

    val identity: String
        get() = steps.joinToString("|") { step ->
            val kind = when (step) {
                is VoiceLeadingStep.Shift -> "s"
                is VoiceLeadingStep.Split -> "b"
                is VoiceLeadingStep.Fuse -> "f"
            }
            "$kind${step.columnId}:${step.fromPitchClass}>${step.toPitchClass}"
        }

    /** Signed motion applied to [columnId] by the step at 1-based [order], or null. */
    fun motionOf(columnId: Int, order: Int): Int? {
        val step = steps.getOrNull(order - 1) ?: return null
        return when (step) {
            is VoiceLeadingStep.Shift -> step.semitones.takeIf { step.columnId == columnId }
            is VoiceLeadingStep.Fuse -> step.semitones.takeIf { step.columnId == columnId }
            is VoiceLeadingStep.Split -> step.semitones.takeIf { step.branchColumnId == columnId }
        }
    }

    /** Motion that brought [columnId] to its pitch at [stepIndex], or null when it never moved. */
    fun arrivalMotion(columnId: Int, stepIndex: Int): Int? =
        (stepIndex downTo 1).firstNotNullOfOrNull { motionOf(columnId, it) }

    /** Motion that takes [columnId] away from its pitch at [stepIndex], or null when it stays. */
    fun departureMotion(columnId: Int, stepIndex: Int): Int? =
        (stepIndex + 1..steps.size).firstNotNullOfOrNull { motionOf(columnId, it) }
}
