package com.mecon.theory.freepractice

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.harmony.ChordInterpretationRef

/** Canonical conversion and matching boundary between editable workspace choices and solver targets. */
internal fun InterpretedChordTarget.toWorkspaceChordChoice(): WorkspaceChordChoice =
    WorkspaceChordChoice.of(
        pitchClasses = sonority.pitchClasses.map(PitchClass::value),
        pinnedInterpretationRef = ChordInterpretationRef(entry.sonority.id, interpretation.id),
        bassPitchClass = bassPitchClass.value,
    )

internal fun Iterable<ChordTarget>.matchingWorkspaceChordTargets(
    key: ModulationKey,
    choice: WorkspaceChordChoice,
    interpretationRef: ChordInterpretationRef? = choice.pinnedInterpretationRef,
): List<ChordTarget> = filter { it.matchesWorkspaceChordChoice(key, choice, interpretationRef) }

internal fun ChordTarget.matchesWorkspaceChordChoice(
    key: ModulationKey,
    choice: WorkspaceChordChoice,
    interpretationRef: ChordInterpretationRef? = choice.pinnedInterpretationRef,
): Boolean =
    this.key == key.key &&
        sonority.pitchClasses.map(PitchClass::value).toSet() == choice.pitchClasses.toSet() &&
        (choice.bassPitchClass == null || bassPitchClass.value == choice.bassPitchClass) &&
        interpretationRef.let { ref ->
            ref == null ||
                this is InterpretedChordTarget &&
                entry.sonority.id == ref.sonorityId && interpretation.id == ref.interpretationId
        }

/**
 * The reading that explains an already written voicing: every sounding pitch class belongs to the
 * chord and the lowest voice states its bass. Boundary frames and realization checks both identify
 * observed material this way, so the predicate lives with the other workspace/target conversions.
 */
internal fun Iterable<ChordTarget>.firstExplaining(
    pitchesByVoiceId: Map<TrackId, Pitch>,
): ChordTarget? {
    val bass = pitchesByVoiceId.values.minByOrNull(Pitch::midiNumber)?.pitchClass ?: return null
    return firstOrNull { candidate ->
        candidate.bassPitchClass == bass &&
            pitchesByVoiceId.values.all { it.pitchClass in candidate.sonority.pitchClasses }
    }
}
