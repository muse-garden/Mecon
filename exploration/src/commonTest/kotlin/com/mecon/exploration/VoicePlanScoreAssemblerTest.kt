package com.mecon.exploration

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.theory.VoicePlan
import com.mecon.theory.freepractice.HarmonyWorkspaceCommand
import com.mecon.theory.freepractice.HarmonyWorkspaceEditor
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceVoiceSpec
import com.mecon.theory.freepractice.VoiceNotationPlan
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VoicePlanScoreAssemblerTest {
    private fun workspace(): HarmonyWorkspaceState =
        HarmonyWorkspaceState(
            voices = VoicePlan.standardFourPart().voices.map(WorkspaceVoiceSpec::fromTheory),
            slots = listOf(
                WorkspaceHarmonySlot(
                    id = WorkspaceSlotId("slot-0"),
                    onset = Fraction.ZERO,
                    duration = Fraction.QUARTER,
                    chordIdentity = "I",
                )
            ),
        )

    @Test
    fun createsExactlyTwoStavesWithConfiguredVoiceDistribution() {
        val score = VoicePlanScoreAssembler.emptyPracticeScore(
            workspace = workspace(),
            keySignature = KeySignature.C_MAJOR,
            staffVoices = GrandStaffVoiceLayout(upperVoiceCount = 3, lowerVoiceCount = 1),
        )

        assertEquals(
            setOf(VoiceNotationPlan.UPPER_STAFF_ID, VoiceNotationPlan.LOWER_STAFF_ID),
            score.staffTracks.keys,
        )
        assertEquals(
            listOf(1, 2, 3),
            score.staffTracks.getValue(VoiceNotationPlan.UPPER_STAFF_ID)
                .voiceTrackIds.map { score.voiceTracks.getValue(it).voiceNumber },
        )
        assertEquals(
            listOf(1),
            score.staffTracks.getValue(VoiceNotationPlan.LOWER_STAFF_ID)
                .voiceTrackIds.map { score.voiceTracks.getValue(it).voiceNumber },
        )
    }

    @Test
    fun extendsExistingScoreWhenHarmonyTimelineCrossesMeasureBoundary() {
        val initial = workspace()
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val extended = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.HALF,
                duration = Fraction.QUARTER,
                chordIdentity = "V",
            ),
        )

        val synchronized = VoicePlanScoreAssembler.ensureTimelineMeasures(runtime, extended)

        assertEquals(listOf(1, 2), synchronized.measures.map { it.key })
        assertEquals(runtime.voiceTracks.keys, synchronized.voiceTracks.keys)
    }

    @Test
    fun dropsTrailingMeasuresAndTheirMaterialWhenTheTimelineShrinks() {
        val initial = workspace()
        val extended = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.HALF,
                duration = Fraction.QUARTER,
                chordIdentity = "V",
            ),
        )
        val storage = VoicePlanScoreAssembler.emptyPracticeScore(extended, KeySignature.C_MAJOR)
        val voiceId = storage.voiceTracks.keys.first()
        val pitchTrackId = storage.voiceTracks.getValue(voiceId).pitchTrackId
        val pitchEvent = StoragePitchEvent.create(TimeCode.of(2, Fraction.ZERO), listOf(Pitch.C5))
        val orphaned = storage.copy(
            pitchTracks = storage.pitchTracks.mapValues { (id, track) ->
                if (id == pitchTrackId) track.addEvent(pitchEvent) else track
            },
            voiceTracks = storage.voiceTracks.mapValues { (id, track) ->
                if (id == voiceId) {
                    track.addEvent(
                        StorageVoiceEvent(
                            id = EventId.generate(),
                            onset = TimeCode.of(2, Fraction.ZERO),
                            pitchEventId = pitchEvent.id,
                            duration = Duration.QUARTER,
                        )
                    )
                } else {
                    track
                }
            },
        )
        val runtime = RuntimeScore.fromStorage(orphaned)
        assertEquals(listOf(1, 2), runtime.measures.map { it.key })

        // Undoing the second chord leaves the timeline one measure long again.
        val shrunk = VoicePlanScoreAssembler.ensureTimelineMeasures(runtime, initial)

        assertEquals(listOf(1), shrunk.measures.map { it.key })
        assertEquals(runtime.voiceTracks.keys, shrunk.voiceTracks.keys)
        assertTrue(shrunk.getAllVoiceEvents().none { it.onset.measure > 1 })
        assertTrue(shrunk.getAllPitchEvents().none { it.onset.measure > 1 })
    }

    @Test
    fun keepsRuntimeIdentityWhenTimelineAlreadyFits() {
        val initial = workspace()
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )

        assertSame(runtime, VoicePlanScoreAssembler.ensureTimelineMeasures(runtime, initial))
    }

    @Test
    fun keepsVoiceScopedAttachmentWithItsVoiceWhenDistributionChanges() {
        val workspace = workspace()
        val initial = VoicePlanScoreAssembler.emptyPracticeScore(
            workspace = workspace,
            keySignature = KeySignature.C_MAJOR,
            staffVoices = GrandStaffVoiceLayout(upperVoiceCount = 2, lowerVoiceCount = 2),
        )
        val lower = initial.staffTracks.getValue(VoiceNotationPlan.LOWER_STAFF_ID)
        val dynamic = StorageDynamicMark.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            level = DynamicLevel.MF,
            voiceNumber = 1,
        )
        val withAttachment = initial.copy(
            staffTracks = initial.staffTracks + (
                lower.id to lower.copy(attachments = listOf(dynamic))
                ),
        )

        val migrated = VoicePlanScoreAssembler.migrateToGrandStaff(
            score = withAttachment,
            workspace = workspace,
            staffVoices = GrandStaffVoiceLayout(upperVoiceCount = 3, lowerVoiceCount = 1),
        )

        val migratedDynamic = migrated.staffTracks
            .getValue(VoiceNotationPlan.UPPER_STAFF_ID)
            .attachments.single()
        assertEquals(dynamic.id, migratedDynamic.id)
        assertEquals(3, migratedDynamic.voiceNumber)
        assertEquals(
            emptyList(),
            migrated.staffTracks.getValue(VoiceNotationPlan.LOWER_STAFF_ID).attachments,
        )
    }
}
