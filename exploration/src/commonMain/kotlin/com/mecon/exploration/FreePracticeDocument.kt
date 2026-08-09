package com.mecon.exploration

import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Persisted settings for free-practice schema v3.
 *
 * Tonal layout, pivot and customary-progression state live in [FreePracticeDocument.workspace].
 * [selectedPatternIds] is retained for v1 compatibility and may be empty in newer documents.
 */
@Serializable
data class FreePracticeWritingSettings(
    val autoWritingEnabled: Boolean = true,
    val backtrackChordCount: Int = 0,
    val replayChordCount: Int = 1,
    val playbackTempoBpm: Int = 120,
) {
    init {
        require(backtrackChordCount in 0..16) { "Auto-writing backtrack must be between 0 and 16 chords" }
        require(replayChordCount in 0..16) { "Replay chord count must be between 0 and 16 chords" }
        require(playbackTempoBpm in 30..240) { "Playback tempo must be between 30 and 240 BPM" }
    }

    companion object {
        fun migrated(playbackTempoBpm: Int = 120): FreePracticeWritingSettings =
            FreePracticeWritingSettings(
                autoWritingEnabled = false,
                playbackTempoBpm = playbackTempoBpm.coerceIn(30, 240),
            )
    }
}

@Serializable
data class FreePracticeSettings(
    val polyphonyLimit: Int,
    val staffVoices: GrandStaffVoiceLayout = GrandStaffVoiceLayout.defaultFor(polyphonyLimit),
    val initialKey: KeySpec = KeySpec(),
    val selectedPatternIds: List<String> = emptyList(),
    val writing: FreePracticeWritingSettings = FreePracticeWritingSettings(),
) {
    init {
        require(polyphonyLimit in 3..6) { "Free practice supports 3-6 simultaneous notes" }
        require(staffVoices.capacity == polyphonyLimit) {
            "Grand-staff voice capacity must match the free-practice polyphony limit"
        }
        require(selectedPatternIds.none { it.isBlank() }) {
            "Free-practice pattern ids cannot be blank"
        }
        require(selectedPatternIds.distinct().size == selectedPatternIds.size) {
            "Free-practice pattern ids must be unique"
        }
    }

    /** Source-compatible alias while desktop state moves from voice count to polyphony capacity. */
    val voiceCount: Int get() = polyphonyLimit

    @Deprecated(
        message = "Use polyphonyLimit and staffVoices",
        replaceWith = ReplaceWith(
            "FreePracticeSettings(voiceCount, GrandStaffVoiceLayout.defaultFor(voiceCount), initialKey, selectedPatternIds)"
        ),
    )
    constructor(
        voiceCount: Int,
        initialKey: KeySpec = KeySpec(),
        selectedPatternIds: List<String> = emptyList(),
    ) : this(
        polyphonyLimit = voiceCount,
        staffVoices = GrandStaffVoiceLayout.defaultFor(voiceCount),
        initialKey = initialKey,
        selectedPatternIds = selectedPatternIds,
    )
}

/**
 * Module-owned payload stored in a `.mecon` [FREE_PRACTICE_MODULE_TYPE] entry.
 *
 * Notes and notation remain in the score referenced by the module envelope's `scoreId`; this
 * payload owns only practice settings and the harmony workspace timeline.
 */
@Serializable
data class FreePracticeDocument(
    val settings: FreePracticeSettings,
    val workspace: HarmonyWorkspaceState,
    val migrationDiagnostics: List<FreePracticeMigrationDiagnostic> = emptyList(),
) {
    init {
        require(settings.polyphonyLimit == workspace.voices.size) {
            "Free-practice polyphony limit must match workspace notation voices"
        }
    }
}

@Serializable
enum class FreePracticeMigrationIssue {
    UNKNOWN_LEGACY_CHORD,
    AMBIGUOUS_LEGACY_CHORD,
    UNRESOLVED_V4_INTERPRETATION,
}

@Serializable
data class FreePracticeMigrationDiagnostic(
    val slotId: WorkspaceSlotId,
    val legacySymbol: String,
    val issue: FreePracticeMigrationIssue,
    val candidateCount: Int,
)

const val FREE_PRACTICE_MODULE_TYPE: String = "exploration.free-practice"
const val FREE_PRACTICE_SCHEMA_VERSION: Int = 8
const val DEFAULT_FREE_PRACTICE_MODULE_ID: String = "free-practice"

@Serializable
private data class LegacyFreePracticeSettings(
    val voiceCount: Int,
    val initialKey: KeySpec = KeySpec(),
    val selectedPatternIds: List<String> = emptyList(),
)

@Serializable
private data class LegacyFreePracticeDocument(
    val settings: LegacyFreePracticeSettings,
    val workspace: HarmonyWorkspaceState,
)

/** JSON boundary for the module's otherwise opaque [JsonElement] payload. */
object FreePracticeDocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(document: FreePracticeDocument): JsonElement =
        json.encodeToJsonElement(
            FreePracticeDocument.serializer(),
            migrateV4ChordChoices(document),
        )

    fun decode(payload: JsonElement, schemaVersion: Int): FreePracticeDocument {
        require(schemaVersion in 1..FREE_PRACTICE_SCHEMA_VERSION) {
            "Unsupported free-practice schema version $schemaVersion"
        }
        if (schemaVersion >= 6) {
            return json.decodeFromJsonElement(FreePracticeDocument.serializer(), payload)
        }
        val decoded = if (schemaVersion >= 3) {
            json.decodeFromJsonElement(FreePracticeDocument.serializer(), payload)
        } else {
            val legacy = json.decodeFromJsonElement(LegacyFreePracticeDocument.serializer(), payload)
            FreePracticeDocument(
                settings = FreePracticeSettings(
                    polyphonyLimit = legacy.settings.voiceCount,
                    staffVoices = GrandStaffVoiceLayout.defaultFor(legacy.settings.voiceCount),
                    initialKey = legacy.settings.initialKey,
                    selectedPatternIds = legacy.settings.selectedPatternIds,
                ),
                workspace = legacy.workspace,
            )
        }
        val v4Document = if (schemaVersion >= 4) decoded else migrateLegacyChordSymbols(decoded)
        return migrateV4ChordChoices(v4Document).copy(
            settings = v4Document.settings.copy(
                writing = FreePracticeWritingSettings.migrated(),
            ),
        )
    }

    private fun migrateV4ChordChoices(document: FreePracticeDocument): FreePracticeDocument {
        val diagnostics = mutableListOf<FreePracticeMigrationDiagnostic>()
        val fallbackKey = document.settings.initialKey.toTheoryKey()
        val slots = document.workspace.slots.map { slot ->
            if (slot.chordChoice != null) return@map slot
            val ref = slot.chordInterpretationRef ?: return@map slot
            val key = document.workspace.selectedTonalLayout(slot)?.key ?: fallbackKey
            val matches = ChordSelectionCatalog.choices(key).filter { ref in it.interpretationRefs }
            if (matches.isEmpty()) {
                diagnostics += FreePracticeMigrationDiagnostic(
                    slotId = slot.id,
                    legacySymbol = ref.interpretationId.value,
                    issue = FreePracticeMigrationIssue.UNRESOLVED_V4_INTERPRETATION,
                    candidateCount = 0,
                )
                slot
            } else {
                val audibleKeys = matches.map { it.audibleKey }.distinct()
                if (audibleKeys.size != 1) {
                    diagnostics += FreePracticeMigrationDiagnostic(
                        slotId = slot.id,
                        legacySymbol = ref.interpretationId.value,
                        issue = FreePracticeMigrationIssue.UNRESOLVED_V4_INTERPRETATION,
                        candidateCount = matches.size,
                    )
                    slot
                } else {
                    slot.copy(
                        chordInterpretationRef = null,
                        chordChoice = WorkspaceChordChoice.of(
                            pitchClasses = matches.first().pitchClasses,
                            origin = matches.map { it.origin }.distinct().singleOrNull(),
                            pinnedInterpretationRef = ref,
                        ),
                    )
                }
            }
        }
        return document.copy(
            workspace = document.workspace.copy(slots = slots),
            migrationDiagnostics = document.migrationDiagnostics + diagnostics,
        )
    }

    private fun migrateLegacyChordSymbols(
        document: FreePracticeDocument,
    ): FreePracticeDocument {
        val diagnostics = mutableListOf<FreePracticeMigrationDiagnostic>()
        val fallbackKey = document.settings.initialKey.toTheoryKey()
        val migratedSlots = document.workspace.slots.map { slot ->
            val symbol = slot.chordIdentity ?: return@map slot
            val key = document.workspace.selectedTonalLayout(slot)?.key ?: fallbackKey
            val refs = ChordSelectionCatalog.choices(key)
                .asSequence()
                .flatMap { choice ->
                    choice.interpretationRefs
                        .zip(choice.interpretationSymbols)
                        .asSequence()
                }
                .filter { (_, routeSymbol) -> routeSymbol == symbol }
                .map { it.first }
                .distinct()
                .toList()
            if (refs.size == 1) {
                slot.copy(
                    chordIdentity = null,
                    chordInterpretationRef = refs.single(),
                )
            } else {
                diagnostics += FreePracticeMigrationDiagnostic(
                    slotId = slot.id,
                    legacySymbol = symbol,
                    issue = if (refs.isEmpty()) {
                        FreePracticeMigrationIssue.UNKNOWN_LEGACY_CHORD
                    } else {
                        FreePracticeMigrationIssue.AMBIGUOUS_LEGACY_CHORD
                    },
                    candidateCount = refs.size,
                )
                slot
            }
        }
        return document.copy(
            workspace = document.workspace.copy(slots = migratedSlots),
            migrationDiagnostics = document.migrationDiagnostics + diagnostics,
        )
    }

    private fun KeySpec.toTheoryKey(): ModulationKey =
        ModulationKey(
            fifths = fifths,
            mode = when (mode) {
                KeyModeSpec.MAJOR -> KeySignatureMode.MAJOR
                KeyModeSpec.MINOR -> KeySignatureMode.MINOR
            },
        )
}
