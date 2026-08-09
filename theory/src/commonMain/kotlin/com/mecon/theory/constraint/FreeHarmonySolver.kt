package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.EventId
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.HarmonicTimeSpan
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.Key
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalPlan
import com.mecon.theory.VoicePlan

data class FreeHarmonySlotSpec(
    val id: HarmonySlotId,
    val time: HarmonicTimeSpan,
    val sourceAnchor: EventId? = null,
)

enum class FreeHarmonyStyle {
    CLASSICAL,
    JAZZ,
}

/**
 * High-priority user intent is represented structurally: pinned pitches, fixed target identities,
 * and explicitly placed progression templates become hard domain/ConstraintProgram restrictions.
 * General writing preferences remain soft and user rule overrides are applied last.
 */
data class FreeHarmonyRequest(
    val key: Key,
    val tonalPlan: TonalPlan,
    val slotCount: Int,
    val slotSpecs: List<FreeHarmonySlotSpec>? = null,
    val vocabulary: List<ChordTarget>,
    val voicePlan: VoicePlan,
    val style: FreeHarmonyStyle = FreeHarmonyStyle.CLASSICAL,
    val patternPlacements: List<PlacedHarmonicPattern> = emptyList(),
    val progressionPlacements: List<ProgressionPlacement> = emptyList(),
    val fixedTargetIdentityBySlot: Map<Int, String> = emptyMap(),
    val allowedTargetIdentityKeysBySlot: Map<Int, Set<String>> = emptyMap(),
    val allowedDefinitionsBySlot: Map<Int, Set<ChordDefinitionId>> = emptyMap(),
    val pitchPins: List<VoicePitchPin> = emptyList(),
    val additionalConstraints: List<Constraint> = emptyList(),
    val ruleOverrides: Map<RuleId, RuleConfig> = emptyMap(),
    val searchConfig: SearchConfig = SearchConfig(),
) {
    init {
        require(slotCount > 0) { "Free harmony request must contain at least one slot" }
        require(vocabulary.isNotEmpty()) { "Free harmony vocabulary must not be empty" }
        require(slotSpecs == null || slotSpecs.size == slotCount) {
            "Free harmony slot specs must align with slot count"
        }
        require(slotSpecs?.map { it.id }?.toSet()?.size == slotSpecs?.size) {
            "Free harmony slot ids must be unique"
        }
        require(fixedTargetIdentityBySlot.keys.all { it in 0 until slotCount })
        require(allowedTargetIdentityKeysBySlot.keys.all { it in 0 until slotCount })
        require(allowedDefinitionsBySlot.keys.all { it in 0 until slotCount })
    }
}

data class PlacedHarmonicPattern(
    val pattern: HarmonicPattern,
    val startSlot: Int,
) {
    init { require(startSlot >= 0) }
}

/** Compatibility adapter. New callers should use [PlacedHarmonicPattern]. */
data class ProgressionPlacement(
    val template: ProgressionTemplate,
    val startSlot: Int,
) {
    init { require(startSlot >= 0) { "Progression placement start must be non-negative" } }
}

data class ProgressionTemplate(
    val id: String,
    val steps: List<ProgressionStep>,
    val directionalStrength: Double = 1.0,
) {
    init {
        require(id.isNotBlank())
        require(steps.isNotEmpty())
        require(steps.map { it.offset }.toSet().size == steps.size)
        require(directionalStrength >= 1.0)
    }

    fun constraintsAt(startSlot: Int): List<Constraint> =
        asPattern().constraintsAt(startSlot, ruleNamespace = "free.progression")

    fun asPattern(): HarmonicPattern =
        HarmonicPattern(
            id = HarmonicPatternId(id),
            steps = steps.sortedBy { it.offset }.map { PatternStep(it.selector) },
            directionalStrength = directionalStrength,
        )
}

data class ProgressionStep(
    val offset: Int,
    val selector: TargetSelector,
) {
    init { require(offset >= 0) }
}

object HabitualProgressions {
    val JAZZ_II_V_I = ProgressionTemplate(
        id = "jazz-ii-v-i",
        steps = listOf(
            ProgressionStep(0, TargetSelector(degrees = setOf(2))),
            ProgressionStep(1, TargetSelector(degrees = setOf(5))),
            ProgressionStep(2, TargetSelector(degrees = setOf(1))),
        ),
    )

    val AUTHENTIC_CADENCE = ProgressionTemplate(
        id = "authentic-cadence",
        directionalStrength = 2.0,
        steps = listOf(
            ProgressionStep(0, TargetSelector(degrees = setOf(5))),
            ProgressionStep(1, TargetSelector(degrees = setOf(1), inversions = setOf(0))),
        ),
    )

    /** I64–V–I: chord type at V remains open, so V7 and altered dominant definitions may participate. */
    val CADENTIAL_SIX_FOUR = ProgressionTemplate(
        id = "cadential-six-four",
        directionalStrength = 2.5,
        steps = listOf(
            ProgressionStep(0, TargetSelector(degrees = setOf(1), inversions = setOf(2))),
            ProgressionStep(1, TargetSelector(degrees = setOf(5))),
            ProgressionStep(2, TargetSelector(degrees = setOf(1), inversions = setOf(0))),
        ),
    )
}

object FreeHarmonySolver {
    fun compile(request: FreeHarmonyRequest): ConstraintProgram {
        val compatibilityConstraints = request.progressionPlacements.flatMap { placement ->
            require(placement.startSlot + placement.template.steps.maxOf { it.offset } < request.slotCount) {
                "Progression ${placement.template.id} extends beyond the requested slots"
            }
            placement.template.constraintsAt(placement.startSlot)
        }
        val patternConstraints = request.patternPlacements.flatMap { placement ->
            require(placement.startSlot + placement.pattern.steps.lastIndex < request.slotCount) {
                "Pattern ${placement.pattern.id} extends beyond the requested slots"
            }
            placement.pattern.constraintsAt(placement.startSlot)
        }
        val templateConstraints = compatibilityConstraints + patternConstraints
        val allPatterns = request.patternPlacements +
            request.progressionPlacements.map {
                PlacedHarmonicPattern(it.template.asPattern(), it.startSlot)
            }
        val slotDomains = List(request.slotCount) { slot ->
            val activeContextIds = request.tonalPlan.contextsAt(slot).mapTo(hashSetOf()) { it.id }
            var targets = request.vocabulary.filter { target ->
                target.tonalContextIds().isEmpty() ||
                    target.tonalContextIds().any { it in activeContextIds }
            }
            request.allowedDefinitionsBySlot[slot]?.let { allowed ->
                targets = targets.filter { it.chordDefinitionId() in allowed }
            }
            request.fixedTargetIdentityBySlot[slot]?.let { identity ->
                targets = targets.filter { it.identityKey() == identity }
            }
            request.allowedTargetIdentityKeysBySlot[slot]?.let { identities ->
                targets = targets.filter { it.identityKey() in identities }
            }
            require(targets.isNotEmpty()) { "No chord targets remain in slot $slot" }
            SlotDomain(targets)
        }
        return ConstraintProgram(
            key = request.key,
            slotDomains = slotDomains,
            slots = request.slotSpecs?.mapIndexed { index, spec ->
                com.mecon.theory.ConstraintSlot(
                    id = spec.id,
                    time = spec.time,
                    domain = slotDomains[index],
                    sourceAnchor = spec.sourceAnchor,
                )
            } ?: com.mecon.theory.defaultConstraintSlots(slotDomains),
            tonalPlan = request.tonalPlan,
            ruleProfile = RuleProfile(
                id = "free-${request.style.name.lowercase()}",
                overrides = request.ruleOverrides,
            ),
            constraints = generalChordMaterialConstraints() +
                generalPreferenceConstraints() +
                templateConstraints +
                request.additionalConstraints,
            voicePlan = request.voicePlan,
            pitchPins = request.pitchPins,
            writingRulePreset = when (request.style) {
                FreeHarmonyStyle.CLASSICAL -> WritingRulePreset.FREE_CLASSICAL
                FreeHarmonyStyle.JAZZ -> WritingRulePreset.FREE_JAZZ
            },
            enforceNoCrossingDuringEnumeration = false,
            directionalWindows = allPatterns.map { placement ->
                DirectionalWindow(
                    window = SlotWindow(
                        placement.startSlot,
                        placement.startSlot + placement.pattern.steps.lastIndex,
                    ),
                    strength = placement.pattern.directionalStrength,
                )
            },
            searchConfig = request.searchConfig,
            ruleModules = emptyList(),
            includeDerivedTextbookConstraints = false,
        )
    }

    fun solve(request: FreeHarmonyRequest): List<PolyphonicConstraintSolution> =
        ConstraintProgramSolver.solvePolyphonic(compile(request))

    private fun generalChordMaterialConstraints(): List<Constraint> =
        desugarRequirements(
            ruleRequirements = emptyList(),
            toneCompleteness = generalChordToneCompletenessRequirements(
                window = SlotWindow(0, null),
                ruleIds = ChordToneCompletenessRuleIds(
                    triad = TRIAD_COMPLETE,
                    seventh = SEVENTH_COMPLETE,
                ),
            ),
            doublings = emptyList(),
            avoidDoublings = emptyList(),
            avoidScaleDegreeDoublings = emptyList(),
            spacings = emptyList(),
            allDifferent = emptyList(),
            adjacentCommonTones = emptyList(),
            chordToneNeighbors = emptyList(),
            targetFeatureBonuses = emptyList(),
        )

    /**
     * Existing Schoenberg programs are a stricter preset over the same runtime. User overrides
     * are merged last, so a requested motive or deliberately relaxed preparation can dominate.
     */
    fun fromSchoenberg(
        program: ConstraintProgram,
        voicePlan: VoicePlan = program.resolvedVoicePlan,
        pitchPins: List<VoicePitchPin> = emptyList(),
        ruleOverrides: Map<RuleId, RuleConfig> = emptyMap(),
    ): ConstraintProgram =
        program.copy(
            voicePlan = voicePlan,
            pitchPins = program.pitchPins + pitchPins,
            ruleProfile = program.ruleProfile.copy(
                overrides = program.ruleProfile.overrides + ruleOverrides,
            ),
        )

    private fun generalPreferenceConstraints(): List<Constraint> =
        listOf(
            preference(
                RuleId("free.harmony.similar-chord-distance"),
                ConstraintPredicate.MinimumSimilarChordDistance(3),
                28.0,
            ),
            preference(
                RuleId("free.harmony.distinct-progressions"),
                ConstraintPredicate.DistinctSimilarChordProgressions,
                20.0,
            ),
            preference(
                RuleId("free.melody.no-repeated-pattern"),
                ConstraintPredicate.NoRepeatedVoicePattern(
                    voiceFilter = ChordToneVoiceFilter.OUTER,
                    minPatternNotes = 2,
                    maxPatternNotes = 4,
                    penaltyScale = 12.0,
                ),
                32.0,
            ),
            preference(
                RuleId("free.melody.unique-high"),
                ConstraintPredicate.UniqueVoiceExtreme(
                    voiceFilter = ChordToneVoiceFilter.OUTER,
                    extreme = VoiceExtreme.HIGHEST,
                ),
                24.0,
            ),
            preference(
                RuleId("free.melody.unique-low"),
                ConstraintPredicate.UniqueVoiceExtreme(
                    voiceFilter = ChordToneVoiceFilter.OUTER,
                    extreme = VoiceExtreme.LOWEST,
                ),
                12.0,
            ),
        )

    private fun preference(
        id: RuleId,
        predicate: ConstraintPredicate,
        weight: Double,
    ): Constraint =
        Constraint(
            expr = ConstraintExpr.Atom(predicate),
            modality = ConstraintModality.Prefer(weight),
            ruleId = id,
        )

    val TRIAD_COMPLETE = RuleId("free.harmony.triad-complete")
    val SEVENTH_COMPLETE = RuleId("free.harmony.seventh-complete")
}
