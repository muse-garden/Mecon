package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.harmony.ChordSelectionCatalog

/** Pure projection of score/workspace structure for both Desktop and Web session frames. */
internal object PracticeStructureProjector {
    fun project(
        selection: List<ScoreSelectionTarget>,
        runtime: RuntimeScore,
        workspace: HarmonyWorkspaceState,
        settings: FreePracticeSettings,
    ): PracticeStructureView {
        val lastMeasure = runtime.measures.maxOfOrNull { it.value.number } ?: 1
        val eventTarget = selection.singleOrNull() as? ScoreSelectionTarget.Event
        val selectedNoteMeasure = eventTarget?.eventId?.let { eventId ->
            runtime.voiceTracks.values.firstNotNullOfOrNull { track ->
                track.events.firstOrNull { it.id == eventId }?.onset?.measure
            }
        }
        val selectedBarlineMeasure = (selection.singleOrNull() as? ScoreSelectionTarget.Barline)
            ?.boundaryMeasure
        val selectedStructuralMeasure = when (val target = selection.singleOrNull()) {
            is ScoreSelectionTarget.Clef -> target.onset.measure.coerceAtLeast(1)
            is ScoreSelectionTarget.KeySignature -> target.onset.measure.coerceAtLeast(1)
            is ScoreSelectionTarget.TimeSignature -> target.onset.measure.coerceAtLeast(1)
            else -> null
        }
        val targetMeasure = selectedNoteMeasure
            ?: selectedStructuralMeasure
            ?: selectedBarlineMeasure?.let { boundary ->
                (boundary + 1).coerceIn(1, lastMeasure)
            }
            ?: 1
        return PracticeStructureView(
            pristine = isPristine(runtime, workspace, settings),
            effectiveTimeSignature = runtime.getTimeSignatureAt(targetMeasure),
            timeSignatureMeasure = targetMeasure,
            lastMeasure = lastMeasure,
            selectedNoteMeasure = selectedNoteMeasure,
            selectedBarlineMeasure = selectedBarlineMeasure,
            rewriteSelectionAvailable = PracticeSelectionScopeResolver.slotIds(
                selection,
                runtime,
                workspace,
            ).isNotEmpty(),
        )
    }

    fun isPristine(
        runtime: RuntimeScore,
        workspace: HarmonyWorkspaceState,
        settings: FreePracticeSettings,
    ): Boolean {
        val slot = workspace.slots.singleOrNull() ?: return false
        if (slot.onset != Fraction.ZERO) return false
        if (runtime.voiceTracks.values.any { track -> track.events.any { !it.isRest } }) return false
        val key = ModulationKey(
            settings.initialKey.fifths,
            if (settings.initialKey.mode == KeyModeSpec.MAJOR) {
                KeySignatureMode.MAJOR
            } else {
                KeySignatureMode.MINOR
            },
        )
        val tonicSymbol = if (key.mode == KeySignatureMode.MAJOR) "I" else "i"
        val tonic = ChordSelectionCatalog.choices(key).firstOrNull {
            it.functionalSymbol == tonicSymbol
        } ?: return false
        return slot.chordChoice?.pitchClasses?.toSet() == tonic.pitchClasses.toSet()
    }
}

/** Keeps timeline duration and score measures synchronized without owning session history. */
internal object PracticeTimelineScoreSynchronizer {
    fun synchronize(
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
        trimEmptyTail: Boolean,
    ): RuntimeScore {
        val base = if (trimEmptyTail) trimTrailingEmptyMeasures(score, workspace) else score
        return VoicePlanScoreAssembler.ensureTimelineMeasures(base, workspace)
    }

    fun measureBoundary(score: RuntimeScore, afterMeasure: Int): Fraction {
        var result = Fraction.ZERO
        for (measure in 1..afterMeasure) {
            result += score.getTimeSignatureAt(measure).measureDuration()
        }
        return result
    }

    fun measureBoundaries(score: RuntimeScore): List<Fraction> {
        val lastMeasure = score.measures.maxOfOrNull { it.value.number } ?: 1
        return (1..lastMeasure).map { measure -> measureBoundary(score, measure) }
    }

    private fun trimTrailingEmptyMeasures(
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
    ): RuntimeScore {
        val lastMeasure = score.measures.maxOfOrNull { it.value.number } ?: return score
        if (lastMeasure <= 1) return score
        val workspaceEnd = workspace.slots.maxOf { it.onset + it.duration }
        val timeMap = ScoreTimeMap.from(score)
        val lastNoteEnd = score.getAllVoiceEvents()
            .asSequence()
            .filter { it.pitchEvent.pitches.isNotEmpty() }
            .maxOfOrNull { event -> timeMap.absolute(event.onset) + event.duration.toFraction() }
        val protectedEnd = maxOf(workspaceEnd, lastNoteEnd ?: Fraction.ZERO)
        val removable = linkedSetOf<Int>()
        var measureStart = Fraction.ZERO
        for (measure in 1..lastMeasure) {
            if (measureStart >= protectedEnd) removable += measure
            measureStart += score.getTimeSignatureAt(measure).measureDuration()
        }
        if (removable.isEmpty() || removable.size >= lastMeasure) return score
        return MeasureEditEngine.delete(score, removable) ?: score
    }
}
