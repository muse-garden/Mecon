package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.KeySpec
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.VoicePlan
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspacePatternChoice
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayout
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.WorkspaceVoiceBoundary
import com.mecon.theory.freepractice.WorkspaceVoiceSpec
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.writing.GrandStaffVoiceLayout

object FreePracticePreset {
    fun document(
        voiceCount: Int = 4,
        initialKey: ModulationKey = ModulationKey(0, KeySignatureMode.MAJOR),
    ): FreePracticeDocument {
        val workspace = workspace(voiceCount, initialKey)
        return FreePracticeDocument(
            settings = FreePracticeSettings(
                polyphonyLimit = voiceCount,
                staffVoices = GrandStaffVoiceLayout.defaultFor(voiceCount),
                initialKey = KeySpec(
                    fifths = initialKey.fifths,
                    mode = if (initialKey.mode == KeySignatureMode.MAJOR) {
                        KeyModeSpec.MAJOR
                    } else {
                        KeyModeSpec.MINOR
                    },
                ),
            ),
            workspace = workspace,
        )
    }

    fun workspace(voiceCount: Int, initialKey: ModulationKey): HarmonyWorkspaceState {
        require(voiceCount in 3..6)
        val baselineId = WorkspaceTonalLayoutId("tonal-layout-0")
        val satbRanges = VoicePlan.standardFourPart().orderedHighToLow.map { it.range }
            .takeIf { voiceCount == 4 }
        val voices = (0 until voiceCount).map { index ->
            WorkspaceVoiceSpec(
                id = TrackId("free-practice-voice-${index + 1}"),
                order = index,
                boundary = when (index) {
                    0 -> WorkspaceVoiceBoundary.UPPER_OUTER
                    voiceCount - 1 -> WorkspaceVoiceBoundary.LOWER_OUTER
                    else -> WorkspaceVoiceBoundary.INNER
                },
                lowest = satbRanges?.get(index)?.lowest
                    ?: Pitch.fromMidi(48 + (voiceCount - index - 1) * 3),
                highest = satbRanges?.get(index)?.highest
                    ?: Pitch.fromMidi(67 + (voiceCount - index - 1) * 4),
                label = if (voiceCount == 4) listOf("S", "A", "T", "B")[index] else "V${index + 1}",
            )
        }
        val tonicSymbol = if (initialKey.mode == KeySignatureMode.MAJOR) "I" else "i"
        val tonic = ChordSelectionCatalog.choices(initialKey).first { it.functionalSymbol == tonicSymbol }
        val tonicRef = requireNotNull(tonic.confirmedInterpretationRef)
        return HarmonyWorkspaceState(
            voices = voices,
            slots = listOf(
                WorkspaceHarmonySlot(
                    id = WorkspaceSlotId("slot-0"),
                    onset = Fraction.ZERO,
                    duration = Fraction.QUARTER,
                    chordChoice = WorkspaceChordChoice.of(
                        tonic.pitchClasses,
                        tonic.origin,
                        tonicRef,
                        tonic.rootPitchClass,
                    ),
                    tonalLayoutId = baselineId,
                )
            ),
            patternChoices = listOf(
                WorkspacePatternChoice("sustain", "sustain-v-x-v", 0),
                WorkspacePatternChoice("cadence", "cadence", 1),
            ),
            tonalLayouts = listOf(
                WorkspaceTonalLayout(
                    id = baselineId,
                    fifths = initialKey.fifths,
                    mode = WorkspaceKeyMode.fromTheory(initialKey.mode),
                    start = Fraction.ZERO,
                    isBaseline = true,
                )
            ),
        )
    }
}
