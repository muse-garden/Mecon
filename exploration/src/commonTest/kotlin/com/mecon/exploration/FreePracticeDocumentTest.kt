package com.mecon.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceTonalLayout
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.WorkspaceVoiceBoundary
import com.mecon.theory.freepractice.WorkspaceVoiceSpec
import com.mecon.theory.writing.GrandStaffVoiceLayout
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceChordTonality
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreePracticeDocumentTest {
    private fun document(): FreePracticeDocument {
        val tonalLayoutId = WorkspaceTonalLayoutId("tonal-layout-0")
        val voices = (0 until 4).map { index ->
            WorkspaceVoiceSpec(
                id = TrackId("voice-$index"),
                order = index,
                boundary = when (index) {
                    0 -> WorkspaceVoiceBoundary.UPPER_OUTER
                    3 -> WorkspaceVoiceBoundary.LOWER_OUTER
                    else -> WorkspaceVoiceBoundary.INNER
                },
                lowest = Pitch.fromMidi(48 + index),
                highest = Pitch.fromMidi(72 + index),
            )
        }
        return FreePracticeDocument(
            settings = FreePracticeSettings(
                polyphonyLimit = 4,
                staffVoices = GrandStaffVoiceLayout(upperVoiceCount = 3, lowerVoiceCount = 1),
                initialKey = KeySpec(fifths = -2, mode = KeyModeSpec.MINOR),
                selectedPatternIds = listOf("deceptive", "cadence"),
            ),
            workspace = HarmonyWorkspaceState(
                voices = voices,
                slots = listOf(
                    WorkspaceHarmonySlot(
                        id = WorkspaceSlotId("slot-0"),
                        onset = Fraction.ZERO,
                        duration = Fraction.QUARTER,
                        chordIdentity = "i",
                        tonalLayoutId = tonalLayoutId,
                    )
                ),
                tonalLayouts = listOf(
                    WorkspaceTonalLayout(
                        id = tonalLayoutId,
                        fifths = -2,
                        mode = WorkspaceKeyMode.MINOR,
                        start = Fraction.ZERO,
                        isBaseline = true,
                    )
                ),
            ),
        )
    }

    @Test
    fun currentPayloadRoundTrips() {
        val key = ModulationKey(-2, KeySignatureMode.MINOR)
        val choice = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "i" }
        val expected = document().let { current ->
            current.copy(
                noteConstraints = PracticeNoteConstraintState(
                    harmonicRoles = listOf(
                        PracticeHarmonicRoleMark(
                            PracticeNoteheadRef(EventId("marked-note"), 1),
                            PracticeHarmonicRole.NON_CHORD_TONE,
                        ),
                    ),
                    lockedVoiceTrackIds = setOf(TrackId("voice-0")),
                    lockedStaffTrackIds = setOf(TrackId("staff-upper")),
                ),
                workspace = current.workspace.copy(
                    slots = current.workspace.slots.map {
                        it.copy(
                            chordIdentity = null,
                            chordChoice = WorkspaceChordChoice.of(
                                choice.pitchClasses,
                                choice.origin,
                                bassPitchClass = choice.rootPitchClass,
                            ),
                            tonality = WorkspaceChordTonality(
                                primary = WorkspaceChordTonalReading.of(
                                    key,
                                    choice.confirmedInterpretationRef,
                                ),
                                alternates = listOf(
                                    WorkspaceChordTonalReading.of(
                                        ModulationKey(0, KeySignatureMode.MAJOR),
                                    )
                                ),
                            ),
                        )
                    }
                )
            )
        }
        val restored = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(expected),
            FREE_PRACTICE_SCHEMA_VERSION,
        )
        assertEquals(expected, restored)
    }

    @Test
    fun versionFiveMigrationKeepsAutoWritingOff() {
        val migrated = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(document()),
            schemaVersion = 5,
        )

        assertEquals(false, migrated.settings.writing.autoWritingEnabled)
        assertEquals(0, migrated.settings.writing.backtrackChordCount)
        assertEquals(1, migrated.settings.writing.replayChordCount)
        assertEquals(120, migrated.settings.writing.playbackTempoBpm)
    }

    @Test
    fun versionSixWritingSettingsRoundTrip() {
        val expected = document().copy(
            settings = document().settings.copy(
                writing = FreePracticeWritingSettings(
                    autoWritingEnabled = false,
                    backtrackChordCount = 4,
                    replayChordCount = 3,
                    playbackTempoBpm = 84,
                ),
            ),
        )

        assertEquals(
            expected,
            FreePracticeDocumentCodec.decode(
                FreePracticeDocumentCodec.encode(expected),
                schemaVersion = 6,
            ),
        )
    }

    @Test
    fun versionEightRoundTripsOverlappingIdiomMemberships() {
        val expected = documentWithOverlappingIdioms()

        val restored = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(expected),
            schemaVersion = FREE_PRACTICE_SCHEMA_VERSION,
        )

        assertEquals(expected, restored)
        assertEquals(2, restored.workspace.idiomInstancesForSlot(WorkspaceSlotId("slot-1")).size)
    }

    @Test
    fun versionSixSlotOwnerFieldIsIgnoredInFavorOfInstanceMemberships() {
        val expected = documentWithOverlappingIdioms()
        val encoded = FreePracticeDocumentCodec.encode(expected) as JsonObject
        val workspace = encoded.getValue("workspace") as JsonObject
        val legacySlots = JsonArray(
            (workspace.getValue("slots") as JsonArray).mapIndexed { index, element ->
                JsonObject(
                    (element as JsonObject) +
                        ("idiomInstanceId" to JsonPrimitive(if (index < 2) "idiom-a" else "idiom-b"))
                )
            }
        )
        val legacy = JsonObject(
            encoded + ("workspace" to JsonObject(workspace + ("slots" to legacySlots)))
        )

        val restored = FreePracticeDocumentCodec.decode(legacy, schemaVersion = 6)

        assertEquals(expected.workspace.idiomInstances, restored.workspace.idiomInstances)
        assertEquals(2, restored.workspace.idiomInstancesForSlot(WorkspaceSlotId("slot-1")).size)
        assertTrue("idiomInstanceId" !in FreePracticeDocumentCodec.encode(restored).toString())
    }

    @Test
    fun versionFourExactReferenceMigratesToPinnedVersionFiveChoice() {
        val key = ModulationKey(-2, KeySignatureMode.MINOR)
        val choice = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "i" }
        val ref = requireNotNull(choice.confirmedInterpretationRef)
        val v4 = document().let { current ->
            current.copy(
                workspace = current.workspace.copy(
                    slots = current.workspace.slots.map {
                        it.copy(chordIdentity = null, chordInterpretationRef = ref)
                    }
                )
            )
        }

        val migrated = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(v4),
            schemaVersion = 4,
        )

        val stored = assertNotNull(migrated.workspace.slots.single().chordChoice)
        assertEquals(choice.pitchClasses.sorted(), stored.pitchClasses)
        assertEquals(ref, stored.pinnedInterpretationRef)
        assertNull(stored.bassPitchClass)
        assertNull(migrated.workspace.slots.single().chordInterpretationRef)
    }

    @Test
    fun versionOnePayloadRemainsReadable() {
        val expected = document().let { current ->
            current.copy(
                settings = current.settings.copy(
                    staffVoices = GrandStaffVoiceLayout.defaultFor(4),
                    writing = FreePracticeWritingSettings.migrated(),
                ),
                workspace = current.workspace.copy(
                    slots = current.workspace.slots.map { it.copy(tonalLayoutId = null) },
                    tonalLayouts = emptyList(),
                )
            )
        }
        val encoded = FreePracticeDocumentCodec.encode(expected) as JsonObject
        val currentSettings = encoded.getValue("settings") as JsonObject
        val legacySettings = JsonObject(
            mapOf(
                "voiceCount" to JsonPrimitive(expected.settings.polyphonyLimit),
                "initialKey" to currentSettings.getValue("initialKey"),
                "selectedPatternIds" to currentSettings.getValue("selectedPatternIds"),
            )
        )
        val legacyPayload = JsonObject(encoded + ("settings" to legacySettings))
        listOf(1, 2).forEach { schemaVersion ->
            val migrated = FreePracticeDocumentCodec.decode(
                legacyPayload,
                schemaVersion = schemaVersion,
            )
            assertEquals(expected.settings, migrated.settings)
            assertNotNull(migrated.workspace.slots.single().chordChoice?.pinnedInterpretationRef)
            assertEquals(null, migrated.workspace.slots.single().chordIdentity)
            assertEquals(null, migrated.workspace.slots.single().chordInterpretationRef)
            assertTrue(migrated.migrationDiagnostics.isEmpty())
        }
    }

    @Test
    fun versionThreeUnknownSymbolIsPreservedWithVisibleDiagnostic() {
        val legacy = document().let { current ->
            current.copy(
                workspace = current.workspace.copy(
                    slots = current.workspace.slots.map {
                        it.copy(chordIdentity = "not-a-chord", chordInterpretationRef = null)
                    }
                )
            )
        }
        val migrated = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(legacy),
            schemaVersion = 3,
        )

        assertEquals("not-a-chord", migrated.workspace.slots.single().chordIdentity)
        assertEquals(null, migrated.workspace.slots.single().chordInterpretationRef)
        assertEquals(FreePracticeMigrationIssue.UNKNOWN_LEGACY_CHORD, migrated.migrationDiagnostics.single().issue)
    }

    @Test
    fun versionThreeRouteSymbolMigratesToItsExactInterpretation() {
        val key = ModulationKey(-2, KeySignatureMode.MINOR)
        val choice = ChordSelectionCatalog.groups(key)
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first { it.interpretationSymbols.distinct().size > 1 }
        val (expectedRef, legacySymbol) = choice.interpretationRefs
            .zip(choice.interpretationSymbols)
            .first { (_, symbol) -> symbol != choice.functionalSymbol }
        val legacy = document().let { current ->
            current.copy(
                workspace = current.workspace.copy(
                    slots = current.workspace.slots.map {
                        it.copy(chordIdentity = legacySymbol, chordInterpretationRef = null)
                    }
                )
            )
        }

        val migrated = FreePracticeDocumentCodec.decode(
            FreePracticeDocumentCodec.encode(legacy),
            schemaVersion = 3,
        )

        assertEquals(expectedRef, migrated.workspace.slots.single().chordChoice?.pinnedInterpretationRef)
        assertEquals(choice.pitchClasses.sorted(), migrated.workspace.slots.single().chordChoice?.pitchClasses)
        assertNull(migrated.workspace.slots.single().chordInterpretationRef)
        assertNull(migrated.workspace.slots.single().chordIdentity)
        assertTrue(migrated.migrationDiagnostics.isEmpty())
    }

    @Test
    fun unknownPayloadFieldsAreIgnored() {
        val expected = document()
        val encoded = FreePracticeDocumentCodec.encode(expected) as JsonObject
        val withFutureField = JsonObject(encoded + ("future" to JsonPrimitive(true)))
        assertEquals(
            expected,
            FreePracticeDocumentCodec.decode(withFutureField, FREE_PRACTICE_SCHEMA_VERSION),
        )
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            FreePracticeDocumentCodec.decode(
                FreePracticeDocumentCodec.encode(document()),
                schemaVersion = FREE_PRACTICE_SCHEMA_VERSION + 1,
            )
        }
    }

    private fun documentWithOverlappingIdioms(): FreePracticeDocument {
        val base = document()
        val layoutId = base.workspace.tonalLayouts.single().id
        val slots = (0..2).map { index ->
            base.workspace.slots.single().copy(
                id = WorkspaceSlotId("slot-$index"),
                onset = Fraction.QUARTER * index,
            )
        }
        return base.copy(
            workspace = base.workspace.copy(
                slots = slots,
                idiomInstances = listOf(
                    WorkspaceIdiomInstance(
                        id = WorkspaceIdiomInstanceId("idiom-a"),
                        definitionId = "secondary",
                        variantId = "v-of-v",
                        sourceExerciseId = "exercise-a",
                        sourceChapterId = "chapter-a",
                        tonalLayoutId = layoutId,
                        slotIds = listOf(slots[0].id, slots[1].id),
                    ),
                    WorkspaceIdiomInstance(
                        id = WorkspaceIdiomInstanceId("idiom-b"),
                        definitionId = "cadence",
                        variantId = "authentic",
                        sourceExerciseId = "exercise-b",
                        sourceChapterId = "chapter-b",
                        tonalLayoutId = layoutId,
                        slotIds = listOf(slots[1].id, slots[2].id),
                    ),
                ),
            )
        )
    }
}
