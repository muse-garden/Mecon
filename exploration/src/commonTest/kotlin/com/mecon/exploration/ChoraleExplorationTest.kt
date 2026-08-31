package com.mecon.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.chorale.ChoraleEventIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val CADENCE = listOf(
    ChoraleSlotSpec(degree = 1),
    ChoraleSlotSpec(degree = 4),
    ChoraleSlotSpec(degree = 5),
    ChoraleSlotSpec(degree = 1),
)

/** The soprano may split a bar; everyone else holds. */
private fun openSoprano(): List<ChoraleVoiceSpec> = ChoraleVoiceRoleSpec.entries.map { role ->
    if (role == ChoraleVoiceRoleSpec.SOPRANO) {
        ChoraleVoiceSpec(role, listOf(ChoraleRhythmSpec.SUSTAINED, ChoraleRhythmSpec.HALVES))
    } else ChoraleVoiceSpec(role)
}

private fun StorageScore.voiceEvents(role: FixedVoiceRole) =
    voiceTracks.getValue(com.mecon.api.primitive.TrackId("chorale-${role.name.lowercase()}-voice")).events

class ChoraleExplorationTest {

    @Test
    fun aPlainChoraleAssemblesIntoAFourVoiceGrandStaffScore() {
        val output = ExplorationRequestRunner.run(
            ChoraleHarmonizationRequest(slots = CADENCE)
        )
        assertTrue(output.candidates.isNotEmpty(), "${output.diagnostics}")
        val score = output.candidates.first().score

        assertEquals(4, score.voiceTracks.size)
        assertEquals(4, score.pitchTracks.size)
        assertEquals(2, score.staffTracks.size)
        assertEquals(4, score.measures.size, "one chord per bar")
        FixedVoiceRole.entries.filter { it in SATB }.forEach { role ->
            assertEquals(4, score.voiceEvents(role).size, "${role.name} should hold one note per bar")
        }
        // Every voice event points at a real pitch event, or nothing can be rendered or played.
        score.voiceTracks.values.forEach { voice ->
            val pitches = score.pitchTracks.getValue(voice.pitchTrackId).events.associateBy { it.id }
            voice.events.forEach { event ->
                assertTrue(event.pitchEventId in pitches, "dangling pitch reference in ${voice.id}")
            }
        }
        // The score must survive the runtime conversion the renderer and playback both go through.
        assertTrue(RuntimeScore.fromStorage(score).voiceTracks.isNotEmpty())
    }

    @Test
    fun theHarmonyIsAnnotatedSoTheProgressionIsVisibleAboveTheStaff() {
        val output = ExplorationRequestRunner.run(
            ChoraleHarmonizationRequest(slots = CADENCE)
        )
        val chordTrack = output.candidates.first().score.pluginTracks.values.single()
        assertEquals(4, chordTrack.events.size)
        assertTrue(output.candidates.first().score.metadata.title.contains("I–IV–V–I"))
    }

    @Test
    fun aRequestedSuspensionIsNotatedAsATiedDissonanceThatResolves() {
        val output = ExplorationRequestRunner.run(
            ChoraleHarmonizationRequest(
                slots = CADENCE,
                voices = openSoprano(),
                figuration = listOf(
                    ChoraleFigurationSpec(
                        slot = 2,
                        type = ChoraleFigurationTypeSpec.SUSPENSION,
                        role = ChoraleVoiceRoleSpec.SOPRANO,
                    )
                ),
            )
        )
        assertTrue(output.candidates.isNotEmpty(), "${output.diagnostics}")
        val score = output.candidates.first().score
        val soprano = score.voiceEvents(FixedVoiceRole.SOPRANO)
        assertEquals(5, soprano.size, "the dominant bar splits into dissonance and resolution")

        val pitches = score.pitchTracks
            .getValue(com.mecon.api.primitive.TrackId("chorale-soprano-pitch"))
            .events.associateBy { it.id }
        val preparation = soprano[1]
        val dissonance = soprano[2]
        val resolution = soprano[3]
        assertEquals(Fraction.HALF, dissonance.duration.toFraction())
        assertEquals(
            pitches.getValue(preparation.pitchEventId).pitches,
            pitches.getValue(dissonance.pitchEventId).pitches,
            "the suspension holds the preparation's pitch",
        )
        // Held over, not re-struck: the preparation ties into it.
        assertTrue(preparation.ties.isNotEmpty(), "the preparation must tie into the suspension")
        assertTrue(dissonance.ties.isEmpty(), "the suspension must not tie into its resolution")
        assertTrue(
            pitches.getValue(resolution.pitchEventId).pitches.single().midiNumber <
                pitches.getValue(dissonance.pitchEventId).pitches.single().midiNumber,
            "a suspension resolves downward",
        )
    }

    @Test
    fun findingsAnchorToTheNotesTheEngineReasonedAbout() {
        val output = ExplorationRequestRunner.run(
            ChoraleHarmonizationRequest(
                slots = CADENCE,
                voices = openSoprano(),
                figuration = listOf(
                    ChoraleFigurationSpec(
                        slot = 2,
                        type = ChoraleFigurationTypeSpec.SUSPENSION,
                        role = ChoraleVoiceRoleSpec.SOPRANO,
                    )
                ),
            )
        )
        val candidate = output.candidates.first()
        val figuration = candidate.findings.single { it.ruleId == "chorale.figuration" }
        val eventIds = candidate.score.voiceTracks.values.flatMap { it.events }.map { it.id }.toSet()
        assertTrue(
            figuration.anchors.all { it in eventIds },
            "finding anchors must resolve to real events: ${figuration.anchors}",
        )
        assertTrue(figuration.relatedAnchors.all { it in eventIds })
        assertEquals(
            ChoraleEventIds.note(FixedVoiceRole.SOPRANO, com.mecon.api.primitive.TimeCode.of(3, Fraction.ZERO)),
            figuration.anchors.single(),
            "the suspension sounds on the downbeat of the dominant bar",
        )
        assertTrue(
            candidate.breakdownEntries.any { it.ruleId == "chorale.tension-arc" && it.amount > 0.0 },
            "the tension arc must be reported so the user can compare candidates",
        )
    }

    @Test
    fun anUnrealizableRequestReportsWhyInsteadOfReturningNothing() {
        val output = ExplorationRequestRunner.run(
            ChoraleHarmonizationRequest(
                slots = listOf(ChoraleSlotSpec(degree = 1), ChoraleSlotSpec(degree = 1)),
                voices = openSoprano(),
                figuration = listOf(
                    ChoraleFigurationSpec(
                        slot = 1,
                        type = ChoraleFigurationTypeSpec.SUSPENSION,
                        role = ChoraleVoiceRoleSpec.SOPRANO,
                    )
                ),
            )
        )
        assertTrue(output.candidates.isEmpty())
        assertTrue(
            output.diagnostics.any { it.contains("冲突位") || it.contains("骨架") },
            "expected the engine's own reason, got ${output.diagnostics}",
        )
    }

    private companion object {
        val SATB = setOf(
            FixedVoiceRole.SOPRANO,
            FixedVoiceRole.ALTO,
            FixedVoiceRole.TENOR,
            FixedVoiceRole.BASS,
        )
    }
}
