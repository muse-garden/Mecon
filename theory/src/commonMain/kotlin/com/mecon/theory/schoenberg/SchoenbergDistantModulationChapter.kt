package com.mecon.theory.schoenberg

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.theory.Chord
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.ConstraintSlot
import com.mecon.theory.HarmonicTimeline
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationCircleOfFifths
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.HarmonicPatterns
import com.mecon.theory.constraint.HarmonicTexturePlan
import com.mecon.theory.constraint.HarmonicVoiceParticipation
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.SustainedToneRelease
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.VoiceParticipationSpan
import com.mecon.theory.constraint.WritingRulePreset
import com.mecon.theory.textbook.TextbookTriadPosition

enum class TonalConfirmationLevel {
    LIGHT,
    ESTABLISHED,
}

data class DistantModulationPivotRecipe(
    val sourceKey: ModulationKey,
    val targetKey: ModulationKey,
    val pitchClasses: Set<Int>,
    val sourceReading: String,
    val targetReading: String,
    val pathIds: Set<TonalPathId>,
) {
    val definition: String
        get() = "前调 $sourceReading = 后调 $targetReading"
}

data class DistantModulationExerciseRequest(
    val sourceKey: ModulationKey,
    val pathId: TonalPathId,
    val confirmationLevel: TonalConfirmationLevel,
    val voicePlan: VoicePlan = VoicePlan.standardFourPart(),
    val searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
)

data class DistantModulationExerciseProgram(
    val program: ConstraintProgram,
    val path: ResolvedTonalPath,
    val confirmationLevel: TonalConfirmationLevel,
    val sustainedWindow: SlotWindow? = null,
)

object SchoenbergDistantModulationChapter {
    val RULE_ID = RuleId("schoenberg.modulation.distant-three-four")
    const val EXERCISE_ID = "schoenberg.modulation.distant-three-four"

    /**
     * Chapter-owned pivot vocabulary for free placement. Target mode may differ from the exercise's
     * final mode as long as it uses the same destination key signature; this exposes textbook
     * spellings such as C-major I (1–3–5) = A-flat-major III (3–♯5–7).
     */
    fun pivotRecipes(
        sourceKey: ModulationKey,
        targetKey: ModulationKey,
    ): List<DistantModulationPivotRecipe> {
        if (sourceKey.mode != KeySignatureMode.MAJOR) return emptyList()
        val fifthsDistance = ModulationCircleOfFifths.signedDistance(sourceKey, targetKey)
        if (kotlin.math.abs(fifthsDistance) !in 1..4) return emptyList()
        val pathIds = SchoenbergDistantTonalPaths.all.mapNotNullTo(linkedSetOf()) { template ->
            runCatching { SchoenbergTonalPathResolver.resolve(template, sourceKey) }
                .getOrNull()
                ?.takeIf { it.target.key.fifths == targetKey.fifths }
                ?.templateId
        }
        val recommendedChords = when (kotlin.math.abs(fifthsDistance)) {
            1, 2 -> ModulationCommonChordCatalog.commonChords(sourceKey, targetKey)
                .map { it.chord }
            3, 4 -> {
                val referenceKey = if (fifthsDistance > 0) sourceKey else targetKey
                listOf(Chord(referenceKey.key.root.transpose(4), ChordQuality.MAJOR))
            }
            else -> emptyList()
        }
        return recommendedChords
            .distinctBy { it.root to it.quality }
            .map { chord ->
                val sourceDegree = NaturalTriads.matches(sourceKey.key, chord)
                    .firstOrNull()
                    ?.degree
                    ?.let(::roman)
                    ?: chord.pitchClasses.joinToString("–") {
                        ModulationCommonChordCatalog.relativePitchLabel(sourceKey, it)
                    }
                val targetNatural = NaturalTriads.matches(targetKey.key, chord).firstOrNull()
                val targetReading = targetNatural?.degree?.let(::roman)
                    ?: chord.pitchClasses.joinToString("–") {
                        ModulationCommonChordCatalog.relativePitchLabel(targetKey, it)
                    }
                DistantModulationPivotRecipe(
                    sourceKey = sourceKey,
                    targetKey = targetKey,
                    pitchClasses = chord.pitchClasses.mapTo(linkedSetOf()) { it.value },
                    sourceReading = "$sourceDegree（" + chord.pitchClasses.joinToString("–") {
                        ModulationCommonChordCatalog.relativePitchLabel(sourceKey, it)
                    } + "）",
                    targetReading = "$targetReading（" + chord.pitchClasses.joinToString("–") {
                        ModulationCommonChordCatalog.relativePitchLabel(targetKey, it)
                    } + "）",
                    pathIds = pathIds,
                )
            }
    }

    /** The dominant-pedal harmony cut from the established sharp-side confirmation. */
    fun dominantSustainedProgression(key: ModulationKey): List<SchoenbergSymbolicChord> =
        DOMINANT_SUSTAINED_DEGREES.map { degree -> symbolicChord(key, degree) }

    fun dominantSustainedDurations(): List<Fraction> =
        listOf(Fraction.QUARTER, Fraction.QUARTER, Fraction.HALF)

    fun compile(request: DistantModulationExerciseRequest): DistantModulationExerciseProgram {
        val template = SchoenbergDistantTonalPaths.all.firstOrNull { it.id == request.pathId }
            ?: error("Unknown distant modulation path ${request.pathId}")
        val path = SchoenbergTonalPathResolver.resolve(template, request.sourceKey)
        val planned = mutableListOf<PlannedTarget>()

        path.nodes.dropLast(1).forEach { node ->
            planned += PlannedTarget(node, target(node.key, degree = 1))
        }
        if (
            request.confirmationLevel == TonalConfirmationLevel.ESTABLISHED &&
            path.fifthsDelta > 0 &&
            planned.size % 2 != 0
        ) {
            val approachNode = path.nodes[path.nodes.lastIndex - 1]
            planned += PlannedTarget(approachNode, target(approachNode.key, degree = 5))
        }

        val targetNode = path.target
        val targetEntrySlot = planned.size
        when {
            request.confirmationLevel == TonalConfirmationLevel.LIGHT -> {
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 5))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 1))
            }
            path.fifthsDelta > 0 -> {
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 5))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 6))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 5))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 1))
            }
            else -> {
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 5))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 6))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 2))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 5))
                planned += PlannedTarget(targetNode, target(targetNode.key, degree = 1))
            }
        }

        val sustainedWindow = if (
            request.confirmationLevel == TonalConfirmationLevel.ESTABLISHED &&
            path.fifthsDelta > 0
        ) {
            SlotWindow(targetEntrySlot, targetEntrySlot + 2)
        } else {
            null
        }
        val durations = List(planned.size) { Fraction.QUARTER }.toMutableList()
        if (targetEntrySlot % 2 != 0) {
            durations[targetEntrySlot - 1] = Fraction.HALF
        }
        sustainedWindow?.end?.let { durations[it] = Fraction.HALF }
        val timeline = HarmonicTimeline.twoFour(durations)
        val domains = planned.map { SlotDomain(listOf(it.target)) }
        val slots = domains.mapIndexed { index, domain ->
            ConstraintSlot(
                id = HarmonySlotId("distant-${request.pathId.value}-$index"),
                time = timeline.spans[index],
                domain = domain,
            )
        }
        val tonalPlan = TonalPlan(
            planned.mapIndexed { index, target ->
                TonalSpan(SlotWindow(index, index), target.node.context)
            }
        )
        val texturePlan = sustainedWindow?.let { window ->
            val voice = sustainedVoice(request.voicePlan)
            val pitch = sustainedPitch(path.target, voice.range.lowest, voice.range.highest)
            val finalDominantSlot = window.end ?: error("Sustained window must be closed")
            val release = slots[finalDominantSlot].time.onset + Fraction.QUARTER
            HarmonicTexturePlan(
                participations = listOf(
                    VoiceParticipationSpan(
                        window = window,
                        voiceId = voice.id,
                        participation = HarmonicVoiceParticipation.Sustained(pitch),
                    )
                ),
                sustainedToneReleases = listOf(
                    SustainedToneRelease(finalDominantSlot, voice.id, release)
                ),
            )
        } ?: HarmonicTexturePlan.allChordVoices()

        val constraints = completenessConstraints(planned) +
            HarmonicPatterns.AUTHENTIC_CADENCE.constraintsAt(planned.lastIndex - 1) +
            when {
                sustainedWindow != null ->
                    HarmonicPatterns.DOMINANT_SUSTAINED_WINDOW.constraintsAt(sustainedWindow.start)
                request.confirmationLevel == TonalConfirmationLevel.ESTABLISHED ->
                    HarmonicPatterns.DECEPTIVE_CADENCE.constraintsAt(targetEntrySlot)
                else -> emptyList()
            } + confirmationAnnotations(planned.lastIndex, sustainedWindow, request.confirmationLevel)
        val program = ConstraintProgram(
            key = request.sourceKey.key,
            slotDomains = domains,
            tonalPlan = tonalPlan,
            slots = slots,
            meterPlan = timeline.meterPlan,
            keySignatureChangesByMeasure = mapOf(
                slots[targetEntrySlot].time.onset.measure to path.target.key.keySignature
            ),
            texturePlan = texturePlan,
            constraints = constraints,
            voicePlan = request.voicePlan,
            writingRulePreset = WritingRulePreset.SCHOENBERG_GENERAL,
            searchConfig = request.searchConfig,
            ruleModules = emptyList(),
            includeDerivedTextbookConstraints = false,
        )
        return DistantModulationExerciseProgram(
            program = program,
            path = path,
            confirmationLevel = request.confirmationLevel,
            sustainedWindow = sustainedWindow,
        )
    }

    private data class PlannedTarget(
        val node: TonalPathNode,
        val target: ChordTarget,
    )

    private fun target(key: ModulationKey, degree: Int): ChordTarget =
        SchoenbergChordCatalog.targets(key.key, listOf(symbolicChord(key, degree))).single()

    private fun symbolicChord(key: ModulationKey, degree: Int): SchoenbergSymbolicChord {
        val quality = when (degree) {
            1 -> if (key.mode == KeySignatureMode.MAJOR) ChordQuality.MAJOR else ChordQuality.MINOR
            2 -> if (key.mode == KeySignatureMode.MAJOR) ChordQuality.MINOR else ChordQuality.DIMINISHED
            4 -> if (key.mode == KeySignatureMode.MAJOR) ChordQuality.MAJOR else ChordQuality.MINOR
            5 -> ChordQuality.MAJOR
            6 -> if (key.mode == KeySignatureMode.MAJOR) ChordQuality.MINOR else ChordQuality.MAJOR
            else -> error("Unsupported distant-modulation degree $degree")
        }
        return SchoenbergSymbolicChord(
            degree = degree,
            quality = quality,
            position = TextbookTriadPosition.ROOT_POSITION,
            arity = ChordArity.TRIAD,
        )
    }

    private fun completenessConstraints(planned: List<PlannedTarget>): List<Constraint> =
        planned.indices.map { slot ->
            val ruleId = RuleId("${RULE_ID.value}.complete.$slot")
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.ToneCompleteness(
                        ToneCompletenessRequirement(
                            window = SlotWindow(slot, slot),
                            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
                            ruleId = ruleId,
                        )
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = ruleId,
                explanation = ConstraintExplanation(
                    satisfied = "三和弦音完整。",
                    violated = "三和弦必须包含根音、三音与五音。",
                ),
            )
        }

    private fun confirmationAnnotations(
        finalSlot: Int,
        sustainedWindow: SlotWindow?,
        level: TonalConfirmationLevel,
    ): List<Constraint> = buildList {
        val confirmationRuleId = RuleId("${RULE_ID.value}.confirmation.${level.name.lowercase()}")
        add(
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.TargetMatches(
                        TargetFeatureBonusRequirement(
                            window = SlotWindow(finalSlot, finalSlot),
                            selector = TargetSelector(degrees = setOf(1), inversions = setOf(0)),
                            ruleId = confirmationRuleId,
                            message = "目标调已按 ${level.name} 强度确认。",
                            bonus = 0.0,
                        )
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = confirmationRuleId,
                explanation = ConstraintExplanation("目标调确认完成。", "目标调尚未确认。"),
            )
        )
        sustainedWindow?.end?.let { finalDominant ->
            val sustainedRuleId = RuleId("${RULE_ID.value}.sustained-dominant")
            add(
                Constraint(
                    expr = ConstraintExpr.Atom(
                        ConstraintPredicate.TargetMatches(
                            TargetFeatureBonusRequirement(
                                window = SlotWindow(finalDominant, finalDominant),
                                selector = TargetSelector(degrees = setOf(5)),
                                ruleId = sustainedRuleId,
                                message = "目标调属音持续到最终属和弦的强拍末。",
                                bonus = 0.0,
                            )
                        )
                    ),
                    modality = ConstraintModality.Annotate,
                    ruleId = sustainedRuleId,
                )
            )
        }
    }

    private fun sustainedVoice(voicePlan: VoicePlan) =
        voicePlan.orderedHighToLow.firstOrNull { it.boundary == VoiceBoundary.UPPER_OUTER }
            ?: voicePlan.orderedHighToLow.first()

    private fun sustainedPitch(
        target: TonalPathNode,
        lowest: Pitch,
        highest: Pitch,
    ): Pitch {
        val spelling = target.context.spellDegree(5)
        return ((lowest.octave - 1)..(highest.octave + 1))
            .map(spelling::pitchAt)
            .filter { it.midiNumber in lowest.midiNumber..highest.midiNumber }
            .minByOrNull { kotlin.math.abs(it.midiNumber - (lowest.midiNumber + highest.midiNumber) / 2) }
            ?: error("Target dominant cannot fit sustained voice range")
    }

    private fun roman(degree: Int): String =
        listOf("I", "II", "III", "IV", "V", "VI", "VII").getOrElse(degree - 1) {
            degree.toString()
        }

    private val DOMINANT_SUSTAINED_DEGREES = listOf(5, 6, 5)
}
