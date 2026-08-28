package com.mecon.theory.chorale

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.theory.BeamSearchSolver
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.NonChordToneType
import com.mecon.theory.VoiceRange
import com.mecon.theory.WritingTask
import com.mecon.theory.WritingTexture
import com.mecon.theory.WritingTimeline
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.voiceleading.VoiceLeadingSuspensionInsertion

/**
 * The two-stage chorale writing entry point.
 *
 * Stage one is the existing constraint-program solver: the user's complete progression, all the
 * general four-part rules and any pinned melody. Stage two decides the rhythm and the figuration.
 * Design: `docs/theory/chorale-harmonization.md`.
 */
object ChoraleHarmonizer {

    fun harmonize(task: ChoraleTask): ChoraleResult {
        val program = task.skeleton.withSuspensionProjections(task)
        val skeletons = ConstraintProgramSolver.solve(program)
            .filterNot { it.breakdown.hasHardViolation }
            .filter { candidate -> candidate.voicings.preparesEverySuspension(task) }
            // Contour is a skeleton property, so it is what picks between skeletons.
            .sortedWith(
                compareBy(
                    { contourPenalty(it.voicings, task) },
                    { it.breakdown.total },
                )
            )
        val skeleton = skeletons.firstOrNull()?.voicings
            ?: return ChoraleResult(
                realizations = emptyList(),
                diagnostics = listOf(
                    ChoraleDiagnostic(
                        code = "chorale.skeleton-unrealizable",
                        message = "没有骨架能同时满足给定进行与被要求的冲突位。",
                    )
                ),
            )

        val space = ChoraleRealizationSpace(task, skeleton, task.voiceRanges())
        val writingTask = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(
                    start = task.skeleton.slots.first().time.onset,
                    end = task.skeleton.slots.last().time.onset,
                ),
                slots = task.skeleton.slots.map { it.time.onset },
            ),
            searchConfig = task.search,
        )
        val results = BeamSearchSolver.solve(writingTask, space)
        if (results.isEmpty()) {
            return ChoraleResult(
                realizations = emptyList(),
                diagnostics = listOf(
                    ChoraleDiagnostic(
                        code = "chorale.figuration-unrealizable",
                        message = "骨架成立，但在给定节奏型与密度下写不出要求的装饰。",
                    )
                ),
            )
        }
        return ChoraleResult(results.map { space.realization(it.state, it.breakdown) })
    }

    /**
     * Compiles the suspensions the user placed into stage-one constraints.
     *
     * Only the step motion is expressible as a predicate; that the held tone is actually foreign to
     * the new chord is checked on the returned skeletons, the same way the textbook figuration
     * solver does it. See `docs/theory/chorale-harmonization.md` §2.1.
     */
    private fun ConstraintProgram.withSuspensionProjections(task: ChoraleTask): ConstraintProgram {
        val projections = task.figuration.filter { it.requiresSuspension }.map { request ->
            val role = requireNotNull(request.role)
            val downward = NonChordToneType.SUSPENSION in request.types
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.VoiceDiatonicSteps(
                        voiceFilter = role.toVoiceFilter(),
                        slots = listOf(request.slot - 1, request.slot),
                        allowedDeltas = listOf(if (downward) setOf(-1) else setOf(1)),
                    )
                ),
                explanation = ConstraintExplanation(
                    satisfied = "${role.name} 为第 ${request.slot} 槽的延留保留了级进解决。",
                    violated = "${role.name} 没有按延留音要求级进解决。",
                ),
            )
        }
        return if (projections.isEmpty()) this else copy(constraints = constraints + projections)
    }

    /** The held tone must not already belong to the chord it is suspended over. */
    private fun List<ChordVoicing>.preparesEverySuspension(task: ChoraleTask): Boolean =
        task.figuration.filter { it.requiresSuspension }.all { request ->
            val role = requireNotNull(request.role)
            val held = getOrNull(request.slot - 1)?.pitchOf(role) ?: return@all false
            val resolution = getOrNull(request.slot) ?: return@all false
            val chordPitchClasses = resolution.target.sonority.pitchClasses.map { it.value }
            held.pitchClass.value !in chordPitchClasses &&
                VoiceLeadingSuspensionInsertion.between(
                    previousPitchClasses = this[request.slot - 1].target.sonority
                        .pitchClasses.map { it.value },
                    resolutionPitchClasses = chordPitchClasses,
                    includeUpward = NonChordToneType.RETARDATION in request.types,
                ).any { it.suspendedPitchClass == held.pitchClass.value }
        }

    private fun ChoraleTask.voiceRanges(): Map<FixedVoiceRole, VoiceRange> {
        val plan = skeleton.resolvedVoicePlan
        return voices.associate { voice ->
            voice.role to (
                plan.voices.firstOrNull { it.legacyRole == voice.role }?.range
                    ?: skeleton.rangeProfile.rangeFor(voice.role)
                    ?: error("No range configured for ${voice.role}")
                )
        }
    }

    private fun FixedVoiceRole.toVoiceFilter(): ChordToneVoiceFilter = when (this) {
        FixedVoiceRole.SOPRANO -> ChordToneVoiceFilter.SOPRANO
        FixedVoiceRole.ALTO -> ChordToneVoiceFilter.ALTO
        FixedVoiceRole.TENOR -> ChordToneVoiceFilter.TENOR
        FixedVoiceRole.BASS -> ChordToneVoiceFilter.BASS
        else -> ChordToneVoiceFilter.ANY
    }
}
