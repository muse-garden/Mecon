package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.Key

/** Early relation pruning for constraints that depend only on the existing prefix and the next target. */
internal fun relationConstraintsAllowTarget(
    program: ConstraintProgram,
    voices: List<FixedVoice>,
    state: FixedVoiceWritingState<ChordTarget>,
    slotIndex: Int,
    target: ChordTarget,
): Boolean {
    val duplicateInAllDifferentWindow = program.allDifferent
        .filter { it.window.contains(slotIndex) }
        .any { requirement ->
            state.frames
                .filter { requirement.window.contains(it.slotIndex) }
                .any {
                    it.target.identityKey(requirement.identityMode) ==
                        target.identityKey(requirement.identityMode)
                }
        }
    if (duplicateInAllDifferentWindow) return false

    if (!chordToneNeighborTargetWindowAllows(program, slotIndex, target, hasPrevious = state.frames.isNotEmpty())) {
        return false
    }
    val previous = state.frames.lastOrNull() ?: return true
    if (!chordToneNeighborTargetRelationAllows(program, previous.target, slotIndex, target)) return false

    val adjacentRequirements = program.adjacentCommonTones.filter { requirement ->
        requirement.window.contains(previous.slotIndex) &&
            requirement.window.contains(slotIndex) &&
            previous.target.identityKey() !in requirement.exemptIdentityKeys &&
            target.identityKey() !in requirement.exemptIdentityKeys
    }
    if (adjacentRequirements.isEmpty()) return true

    val commonPitchClasses = previous.target.sonority.pitchClasses.toSet() intersect target.sonority.pitchClasses.toSet()
    if (commonPitchClasses.isEmpty()) return false
    return adjacentRequirements.all { requirement ->
        !requirement.holdInSameVoice || voices.any { voice ->
            previous.pitchFor(voice).pitchClass in commonPitchClasses
        }
    }
}

internal fun relationConstraintsAllowFrame(
    program: ConstraintProgram,
    voices: List<FixedVoice>,
    state: FixedVoiceWritingState<ChordTarget>,
    frame: FixedVoiceWritingFrame<ChordTarget>,
): Boolean {
    if (!relationConstraintsAllowTarget(program, voices, state, frame.slotIndex, frame.target)) return false
    val previous = state.frames.lastOrNull() ?: return true
    if (!chordToneNeighborFrameRelationAllows(program, voices, previous, frame)) return false

    val adjacentRequirements = program.adjacentCommonTones.filter { requirement ->
        requirement.window.contains(previous.slotIndex) &&
            requirement.window.contains(frame.slotIndex) &&
            previous.target.identityKey() !in requirement.exemptIdentityKeys &&
            frame.target.identityKey() !in requirement.exemptIdentityKeys
    }
    return adjacentRequirements.all { requirement ->
        !requirement.holdInSameVoice || voices.any { voice ->
            previous.pitchFor(voice).midiNumber == frame.pitchFor(voice).midiNumber
        }
    }
}

private fun chordToneNeighborTargetWindowAllows(
    program: ConstraintProgram,
    slotIndex: Int,
    target: ChordTarget,
    hasPrevious: Boolean,
): Boolean =
    program.chordToneNeighbors
        .filter { it.window.contains(slotIndex) }
        .all { requirement ->
            if (requirement.sourceSlot != null && requirement.sourceSlot != slotIndex) return@all true
            if (!requirement.sourceSelector.matches(target)) return@all true
            when (requirement.direction) {
                ChordToneNeighborDirection.PREVIOUS ->
                    hasPrevious && requirement.window.contains(slotIndex - 1)
                ChordToneNeighborDirection.NEXT ->
                    slotIndex < program.length - 1 && requirement.window.contains(slotIndex + 1)
            }
        }

private fun chordToneNeighborTargetRelationAllows(
    program: ConstraintProgram,
    previous: ChordTarget,
    slotIndex: Int,
    target: ChordTarget,
): Boolean =
    program.chordToneNeighbors
        .filter { it.window.contains(slotIndex - 1) && it.window.contains(slotIndex) }
        .all { requirement ->
            when (requirement.direction) {
                ChordToneNeighborDirection.PREVIOUS ->
                    (requirement.sourceSlot != null && requirement.sourceSlot != slotIndex) ||
                        !requirement.sourceSelector.matches(target) ||
                        requirement.neighborTargetAllows(program.key, previous) &&
                        requirement.sourceNeighborPitchClassesCanRelate(program.key, target, previous)
                ChordToneNeighborDirection.NEXT ->
                    (requirement.sourceSlot != null && requirement.sourceSlot != slotIndex - 1) ||
                        !requirement.sourceSelector.matches(previous) ||
                        requirement.neighborTargetAllows(program.key, target) &&
                        requirement.sourceNeighborPitchClassesCanRelate(program.key, previous, target)
            }
        }

private fun chordToneNeighborFrameRelationAllows(
    program: ConstraintProgram,
    voices: List<FixedVoice>,
    previous: FixedVoiceWritingFrame<ChordTarget>,
    current: FixedVoiceWritingFrame<ChordTarget>,
): Boolean =
    program.chordToneNeighbors
        .filter { it.window.contains(previous.slotIndex) && it.window.contains(current.slotIndex) }
        .all { requirement ->
            when (requirement.direction) {
                ChordToneNeighborDirection.PREVIOUS ->
                    (requirement.sourceSlot != null && requirement.sourceSlot != current.slotIndex) ||
                        !requirement.sourceSelector.matches(current.target) ||
                        requirement.frameRelationAllows(program.key, voices, current, previous)
                ChordToneNeighborDirection.NEXT ->
                    (requirement.sourceSlot != null && requirement.sourceSlot != previous.slotIndex) ||
                        !requirement.sourceSelector.matches(previous.target) ||
                        requirement.frameRelationAllows(program.key, voices, previous, current)
            }
        }

private fun ChordToneNeighborRequirement.neighborTargetAllows(
    key: Key,
    target: ChordTarget,
): Boolean =
    neighborSelector.matches(target) &&
        candidatePitchClasses(key).any { it in target.sonority.pitchClasses }

private fun ChordToneNeighborRequirement.sourceNeighborPitchClassesCanRelate(
    key: Key,
    source: ChordTarget,
    neighbor: ChordTarget,
): Boolean {
    val sourcePitchClass = source.pitchClassFor(sourceTone) ?: return true
    if (sourcePitchClasses.isNotEmpty() && sourcePitchClass !in sourcePitchClasses) return true
    val neighborPitchClasses = candidatePitchClasses(key)
    return sourcePitchClass in source.sonority.pitchClasses &&
        neighborPitchClasses.any { it in neighbor.sonority.pitchClasses }
}

private fun ChordToneNeighborRequirement.frameRelationAllows(
    key: Key,
    voices: List<FixedVoice>,
    source: FixedVoiceWritingFrame<ChordTarget>,
    neighbor: FixedVoiceWritingFrame<ChordTarget>,
): Boolean {
    if (!neighborTargetAllows(key, neighbor.target)) return false
    val sourcePitchClass = source.target.pitchClassFor(sourceTone) ?: return true
    if (sourcePitchClasses.isNotEmpty() && sourcePitchClass !in sourcePitchClasses) return true
    val candidatePitchClasses = candidatePitchClasses(key)
    val sourceVoices = voices
        .filter { voiceFilter.allows(it) }
        .filter { source.pitchFor(it).pitchClass == sourcePitchClass }
    if (sourceVoices.isEmpty()) return voiceFilter != ChordToneVoiceFilter.ANY
    return sourceVoices.all { voice ->
        val sourcePitch = source.pitchFor(voice)
        val neighborPitch = neighbor.pitchFor(voice)
        neighborPitch.pitchClass in candidatePitchClasses &&
            (allowedDiatonicStepDeltas.isEmpty() ||
                neighborPitch.diatonicSteps - sourcePitch.diatonicSteps in allowedDiatonicStepDeltas)
    }
}

internal fun ChordToneNeighborRequirement.candidatePitchClasses(key: Key): Set<PitchClass> =
    candidateScaleDegrees
        .flatMap { degree ->
            candidateAlterations.map { alteration ->
                key.scale.pitchClasses[(degree - 1).mod(key.scale.pitchClasses.size)].transpose(alteration)
            }
        }
        .toSet()

internal fun ChordToneVoiceFilter.allows(voice: FixedVoice): Boolean =
    when (this) {
        ChordToneVoiceFilter.ANY -> true
        ChordToneVoiceFilter.OUTER -> voice.role in setOf(FixedVoiceRole.SOPRANO, FixedVoiceRole.BASS, FixedVoiceRole.OUTER)
        ChordToneVoiceFilter.INNER -> voice.role in setOf(
            FixedVoiceRole.ALTO,
            FixedVoiceRole.TENOR,
            FixedVoiceRole.BARITONE,
            FixedVoiceRole.INNER,
        )
        ChordToneVoiceFilter.SOPRANO -> voice.role == FixedVoiceRole.SOPRANO
        ChordToneVoiceFilter.ALTO -> voice.role == FixedVoiceRole.ALTO
        ChordToneVoiceFilter.TENOR -> voice.role == FixedVoiceRole.TENOR
        ChordToneVoiceFilter.BASS -> voice.role == FixedVoiceRole.BASS
    }
