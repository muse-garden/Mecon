package com.mecon.desktop.ui.views

import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.exploration.initialWorkspace
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.theory.freepractice.VoiceNotationPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceFocusTest {
    @Test
    fun newlySelectedInactiveVoiceBecomesActiveAndOwnsSelection() {
        val workspace = initialWorkspace(4)
        var runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val notation = VoiceNotationPlan.from(workspace.voicePlan)
        val targets = notation.bindings.map { binding ->
            VoiceFocusTarget(binding.staffId, binding.voiceId, binding.voiceId.value)
        }
        val firstVoiceId = workspace.voices[0].id
        val secondVoiceId = workspace.voices[1].id
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = firstVoiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = secondVoiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.E4,
            ),
        )!!.score
        val storage = runtime.toStorage()
        val computed = computeScore(runtime)
        val firstEvent = runtime.voiceTracks.getValue(firstVoiceId).events.first()
        val secondEvent = runtime.voiceTracks.getValue(secondVoiceId).events.first()
        val firstSection = VoiceEventSection(computed.getComputedEvent(firstEvent.id)!!)
        val secondSection = VoiceEventSection(computed.getComputedEvent(secondEvent.id)!!)
        val focus = VoiceFocus.create(
            score = storage,
            targets = targets,
            activeVoiceId = firstVoiceId,
            allowAllVoices = false,
            onActiveVoiceChange = {},
        )

        val update = focus.resolveSelection(
            previous = setOf(firstSection),
            candidate = linkedSetOf(firstSection, secondSection),
        )

        assertEquals(secondVoiceId, update.activeVoiceId)
        assertEquals(setOf(secondSection), update.selection)
    }
}
