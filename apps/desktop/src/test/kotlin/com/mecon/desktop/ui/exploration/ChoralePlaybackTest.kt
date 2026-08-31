package com.mecon.desktop.ui.exploration

import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.model.MidiNoteOnEvent
import com.mecon.exploration.ChoraleFigurationSpec
import com.mecon.exploration.ChoraleFigurationTypeSpec
import com.mecon.exploration.ChoraleHarmonizationRequest
import com.mecon.exploration.ChoraleRhythmSpec
import com.mecon.exploration.ChoraleSlotSpec
import com.mecon.exploration.ChoraleVoiceRoleSpec
import com.mecon.exploration.ChoraleVoiceSpec
import com.mecon.exploration.SolveRequest
import com.mecon.exploration.SolverEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the path a user actually takes: pick a progression in the exploration page,
 * press play. It stops at MIDI rather than at audio hardware, which is the last deterministic
 * point before the synth.
 */
class ChoralePlaybackTest {

    private fun request(
        figuration: List<ChoraleFigurationSpec> = emptyList(),
        openVoices: Set<ChoraleVoiceRoleSpec> = emptySet(),
    ) = ChoraleHarmonizationRequest(
        slots = listOf(1, 4, 5, 1).map { ChoraleSlotSpec(degree = it) },
        voices = ChoraleVoiceRoleSpec.entries.map { role ->
            ChoraleVoiceSpec(
                role = role,
                patterns = if (role in openVoices) {
                    listOf(ChoraleRhythmSpec.SUSTAINED, ChoraleRhythmSpec.HALVES)
                } else listOf(ChoraleRhythmSpec.SUSTAINED),
            )
        },
        figuration = figuration,
    )

    @Test
    fun theExplorationPageProducesAChoraleThatPlaysBack() {
        val output = SolverEngine.solve(SolveRequest(convenience = request())).output
        assertTrue(output.candidates.isNotEmpty(), "${output.diagnostics}")

        val runtime = RuntimeScore.fromStorage(output.candidates.first().score)
        val midi = ScoreToMidiConverter.convert(runtime)
        val noteOns = midi.tracks.flatMap { it.getNoteOnEvents() }

        assertEquals(16, noteOns.size, "four voices across four chords")
        assertTrue(noteOns.all { it.velocity.value > 0 }, "a silent note cannot be heard")
        // Four simultaneous attacks per chord: the chorale must sound as blocks, not arpeggios.
        assertEquals(
            listOf(4, 4, 4, 4),
            noteOns.groupBy { it.absoluteTicks }.entries.sortedBy { it.key }.map { it.value.size },
        )
        val ticksPerBar = midi.ticksPerQuarter * 4L
        assertEquals(
            listOf(0L, ticksPerBar, ticksPerBar * 2, ticksPerBar * 3),
            noteOns.map { it.absoluteTicks }.distinct().sorted(),
        )
    }

    @Test
    fun aSuspensionSoundsAsADelayedResolutionRatherThanASimultaneousAttack() {
        val output = SolverEngine.solve(
            SolveRequest(
                convenience = request(
                    openVoices = setOf(ChoraleVoiceRoleSpec.SOPRANO),
                    figuration = listOf(
                        ChoraleFigurationSpec(
                            slot = 2,
                            type = ChoraleFigurationTypeSpec.SUSPENSION,
                            role = ChoraleVoiceRoleSpec.SOPRANO,
                        )
                    ),
                )
            )
        ).output
        assertTrue(output.candidates.isNotEmpty(), "${output.diagnostics}")

        val midi = ScoreToMidiConverter.convert(
            RuntimeScore.fromStorage(output.candidates.first().score)
        )
        val noteOns: List<MidiNoteOnEvent> = midi.tracks.flatMap { it.getNoteOnEvents() }
            .sortedBy { it.absoluteTicks }
        val ticksPerBar = midi.ticksPerQuarter * 4L

        // The soprano is tied over the bar line, so the dominant downbeat has only three attacks
        // and a fourth arrives half a bar later: that offset is the suspension you hear.
        val dominantDownbeat = noteOns.filter { it.absoluteTicks == ticksPerBar * 2 }
        val halfwayThrough = noteOns.filter { it.absoluteTicks == ticksPerBar * 2 + ticksPerBar / 2 }
        assertEquals(3, dominantDownbeat.size, "the suspended voice does not re-attack")
        assertEquals(1, halfwayThrough.size, "the resolution attacks on its own")
        assertTrue(
            halfwayThrough.single().midiNumber > dominantDownbeat.maxOf { it.midiNumber },
            "the resolving soprano is still the top voice",
        )
    }
}
