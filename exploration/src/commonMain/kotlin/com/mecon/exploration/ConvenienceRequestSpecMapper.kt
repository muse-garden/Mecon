package com.mecon.exploration

import com.mecon.theory.ChordArity
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordQuality
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SceneMatcher
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.AvoidScaleDegreeDoublingRequirement
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.DoublingRequirement
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.SpacingRequirement
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.SpacingPreference as TheorySpacingPreference
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import com.mecon.theory.textbook.TextbookTriadConstraintPreset
import com.mecon.theory.textbook.TextbookTriadConstraintRequirements
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.requirementsFor
import com.mecon.theory.textbook.textbookTriadInKey
internal object ConvenienceRequestSpecMapper {
    fun fromConvenience(request: CellRequest): ConstraintProgramSpec =
        when (request) {
            is RuleExampleRequest -> fromRuleExample(request)
            is ProgressionRequest -> fromProgression(request)
            is SchoenbergExerciseRequest ->
                error("SchoenbergExerciseRequest is compiled by theory.schoenberg, not ConstraintProgramSpec")
            is ModulationExerciseCellRequest ->
                error("ModulationExerciseCellRequest is compiled by theory.schoenberg, not ConstraintProgramSpec")
            // The chorale skeleton is only the first of two stages, so it has no single-program
            // spec form; ChoraleExplorationRequestRunner owns its compilation.
            is ChoraleHarmonizationRequest ->
                error("ChoraleHarmonizationRequest is a two-stage task, not a ConstraintProgramSpec")
        }

    private fun fromRuleExample(request: RuleExampleRequest): ConstraintProgramSpec {
        val semantics = request.compileSemantics()
        val ruleConstraints = semantics.requirements.map {
            RuleAtSpec(
                SlotWindowSpec(0, null),
                it.ruleId.value,
                RequirementModeSpec.valueOf(it.mode.name),
            )
        }

        // 七和弦场景：进行完全由场景数据落成（SceneMatcher.instantiateSeventh），三和弦解决和弦以 triadSonority
        // 走七和弦章规则（V7-I 的 I / 终止四六 I⁶₄）。这是七和弦收进 S2 的根路径，取代旧的回退到原位三和弦。
        semantics.seventhSlots?.let { slots ->
            val slotConstraints = slots.flatMapIndexed { index, slot ->
                buildList {
                    add(
                        ChordAtSpec(
                            slot = index,
                            degrees = setOf(slot.chord.degree),
                            positions = slot.allowedPositions.map { it.name }.toSet(),
                            arity = ChordAritySpec.SEVENTH,
                            triadSonority = slot.chord.chord.pitchClasses.size < SEVENTH_CHORD_SIZE,
                        )
                    )
                    slot.fifthConstraint?.let { add(FifthAtSpec(index, it.toSpec())) }
                }
            }
            return ConstraintProgramSpec(
                length = slots.size,
                slotConstraints = slotConstraints + ruleConstraints,
            )
        }

        val slots = requireNotNull(semantics.triadSlots)
        val chordConstraints = slots.mapIndexed { index, slot ->
            ChordAtSpec(
                slot = index,
                degrees = setOf(slot.triad.degree),
                positions = slot.allowedPositions.map { it.name }.toSet(),
            )
        }
        return ConstraintProgramSpec(
            length = slots.size,
            slotConstraints = chordConstraints +
                TextbookTriadConstraintPreset.INTRODUCTORY.requirementsFor(slots).toSpecConstraints() +
                ruleConstraints,
        )
    }

    private fun fromProgression(request: ProgressionRequest): ConstraintProgramSpec {
        val theoryPositions = Policies.get(request.policyId).positions
        val positions = theoryPositions.map { it.name }.toSet()
        val key = request.key.toKey()
        val theorySlots = request.slots.map { slot ->
            TextbookTriadWritingSlot(
                triad = textbookTriadInKey(key, slot.degree.degree),
                allowedPositions = theoryPositions,
            )
        }
        val chordConstraints = request.slots.mapIndexed { index, slot ->
            ChordAtSpec(slot = index, degrees = setOf(slot.degree.degree), positions = positions)
        }
        val spacingConstraints = request.slots.mapIndexedNotNull { index, slot ->
            if (slot.spacing == SpacingPreference.ANY) {
                null
            } else {
                SpacingAtSpec(SlotWindowSpec(index, index), slot.spacing)
            }
        }
        return ConstraintProgramSpec(
            length = request.slots.size,
            domain = DomainSpec(positions.toList()),
            slotConstraints = chordConstraints +
                TextbookTriadConstraintPreset.INTRODUCTORY.requirementsFor(theorySlots).toSpecConstraints() +
                spacingConstraints,
        )
    }

    private const val SEVENTH_CHORD_SIZE = 4

}
