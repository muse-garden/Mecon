package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.PolyphonyLimitValidator
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SearchCancellation
import com.mecon.theory.freepractice.FreePracticeWindowVoicer
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.writing.AnalyticalNoteSpan
import com.mecon.theory.writing.AnalyticalVoiceSeparator
import com.mecon.theory.writing.SourceNoteheadId

/** Platform-neutral checking projection. It emits localization keys, never rendered prose. */
object PracticeFindingComputer {
    fun compute(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        fallbackKey: ModulationKey,
        cancellation: SearchCancellation = SearchCancellation.NONE,
    ): List<PracticeFindingView> = buildList {
        if (workspace.slots.any {
                it.chordIdentity == null && it.chordInterpretationRef == null && it.chordChoice == null
            }
        ) {
            add(PracticeFindingView("freePractice.finding.incompleteHarmony", severity = PracticeFindingSeverity.INFO))
        }
        val notes = analyticalNotes(score)
        val separation = AnalyticalVoiceSeparator.separate(notes, workspace.voices.size)
        val voices = workspace.voices.sortedBy { it.order }
        val notesById = notes.associateBy { it.source }
        val outOfRange = separation.voiceByNotehead.entries.firstNotNullOfOrNull { (noteId, voice) ->
            val note = notesById[noteId] ?: return@firstNotNullOfOrNull null
            val range = voices.getOrNull(voice) ?: return@firstNotNullOfOrNull null
            note.pitch.takeIf { it < range.lowest || it > range.highest }
        }
        if (outOfRange != null) {
            add(
                PracticeFindingView(
                    "freePractice.finding.voiceRange",
                    mapOf("pitch" to outOfRange.format()),
                    PracticeFindingSeverity.ERROR,
                )
            )
        }
        val polyphony = PolyphonyLimitValidator.validate(score, workspace.voices.size)
        if (!polyphony.isValid) {
            add(
                PracticeFindingView(
                    "freePractice.finding.polyphonyLimit",
                    mapOf("peak" to polyphony.peak.toString(), "limit" to polyphony.limit.toString()),
                    PracticeFindingSeverity.ERROR,
                )
            )
        }
        if (separation.unassigned.isNotEmpty() && polyphony.isValid) {
            add(PracticeFindingView("freePractice.finding.voiceSeparation", severity = PracticeFindingSeverity.ERROR))
        }
        if (separation.unassigned.isEmpty() && polyphony.isValid) {
            addAll(
                FreePracticeWindowVoicer.check(workspace, score, fallbackKey, cancellation).map { finding ->
                    PracticeFindingView(
                        messageKey = "freePractice.rule.${finding.ruleId.value}",
                        arguments = mapOf("ruleId" to finding.ruleId.value),
                        severity = when (finding.severity) {
                            RuleSeverity.HARD -> PracticeFindingSeverity.ERROR
                            RuleSeverity.SOFT -> PracticeFindingSeverity.WARNING
                            RuleSeverity.HINT -> PracticeFindingSeverity.INFO
                        },
                        ruleId = finding.ruleId.value,
                        anchors = finding.anchors,
                        message = finding.message,
                    )
                }
            )
        }
    }

    private fun analyticalNotes(score: RuntimeScore): List<AnalyticalNoteSpan> =
        score.voiceTracks.values.flatMap { voice ->
            voice.events.toList().filterNot { it.isRest || it.isGrace }.flatMap { event ->
                val onset = absolute(score, event.onset)
                event.pitches.mapIndexed { pitchIndex, pitch ->
                    AnalyticalNoteSpan(SourceNoteheadId(event.id, pitchIndex), onset, event.duration.toFraction(), pitch)
                }
            }
        }

    private fun absolute(score: RuntimeScore, time: com.mecon.api.primitive.TimeCode): Fraction {
        var result = Fraction.ZERO
        for (measure in 1 until time.measure) result += score.getTimeSignatureAt(measure).measureDuration()
        return result + (time.beat ?: Fraction.ZERO)
    }

    fun fallbackKey(document: com.mecon.exploration.FreePracticeDocument): ModulationKey =
        ModulationKey(
            document.settings.initialKey.fifths,
            if (document.settings.initialKey.mode == com.mecon.exploration.KeyModeSpec.MAJOR) {
                KeySignatureMode.MAJOR
            } else {
                KeySignatureMode.MINOR
            },
        )
}
