package com.mecon.api.storage

import com.mecon.api.primitive.*
import com.mecon.api.storage.tracks.InstrumentPlayback
import com.mecon.api.storage.tracks.TranspositionConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NoteRef(val eventId: EventId, val pitchIndex: Int)

@Serializable
data class StorageNoteLink(
    val source: NoteRef,
    val target: NoteRef,
    val octaveShift: Int = 0,
)

@Serializable
enum class ReductionTemplate { MONOPHONIC_VOICES, SATB, FREE }

@Serializable
data class ReductionAnchor(val sourceStart: TimeCode, val sourceEnd: TimeCode)

@Serializable
enum class ReductionLayerKind {
    FORM,
    HARMONY,
    SKELETON,
    NOTATION,
    ORCHESTRATION,
}

@Serializable
data class StorageReductionTimelineItem(
    val id: EventId,
    val range: TimeRange,
    val label: String,
    val detail: String? = null,
)

@Serializable
data class StorageReductionLayer(
    val id: ReductionLayerId,
    val kind: ReductionLayerKind,
    val visible: Boolean = true,
    /** Only SKELETON and NOTATION currently carry editable notation. */
    val score: StorageScore? = null,
    /** FORM and HARMONY use compact TimeCode-aligned regions without forcing staff notation. */
    val timelineItems: List<StorageReductionTimelineItem> = emptyList(),
)

@Serializable
enum class ScoreFragmentKind { NORMAL, MELODIC }

@Serializable
data class StorageScoreFragmentSource(
    val sourceScoreId: ScoreId,
    val sourceReductionId: ReductionId? = null,
    val originalRange: TimeRange? = null,
)

@Serializable
data class StorageScoreFragment(
    val id: ScoreFragmentId,
    val name: String,
    val kind: ScoreFragmentKind = ScoreFragmentKind.NORMAL,
    /** Fragment-local score. Its TimeCode starts at measure 1 and is not a work placement. */
    val score: StorageScore,
    val sourceMetadata: StorageScoreFragmentSource? = null,
)

@Serializable
data class StorageReduction(
    val id: ReductionId,
    val title: String,
    val anchor: ReductionAnchor? = null,
    val scope: Set<TrackId> = emptySet(),
    val template: ReductionTemplate = ReductionTemplate.FREE,
    /**
     * Legacy v1 field. Old files deserialize here and are migrated into the NOTATION layer.
     * New workspaces keep this null so it is omitted by serializers with encodeDefaults=false.
     */
    @SerialName("score")
    val legacyScore: StorageScore? = null,
    val layers: List<StorageReductionLayer> = emptyList(),
    val materialTray: List<StorageScoreFragment> = emptyList(),
    /** Content-line note (source) <-> reduction note (target); never a direct staff binding. */
    val links: List<StorageNoteLink> = emptyList(),
) {
    val notationScore: StorageScore
        get() = scoreFor(ReductionLayerKind.NOTATION)
            ?: error("Reduction $id has no NOTATION score")

    fun scoreFor(kind: ReductionLayerKind): StorageScore? =
        layers.firstOrNull { it.kind == kind }?.score
            ?: legacyScore.takeIf { kind == ReductionLayerKind.NOTATION }

    fun layer(kind: ReductionLayerKind): StorageReductionLayer? =
        migrated().layers.firstOrNull { it.kind == kind }

    fun updateLayerScore(kind: ReductionLayerKind, score: StorageScore): StorageReduction {
        require(kind == ReductionLayerKind.SKELETON || kind == ReductionLayerKind.NOTATION) {
            "$kind is not a notation layer"
        }
        val normalized = migrated()
        return normalized.copy(
            layers = normalized.layers.map { layer ->
                if (layer.kind == kind) layer.copy(score = score) else layer
            },
        )
    }

    /** Convert a legacy single-score reduction and fill any missing semantic layers. */
    fun migrated(): StorageReduction {
        val notation = layers.firstOrNull { it.kind == ReductionLayerKind.NOTATION }?.score ?: legacyScore
        val byKind = layers.associateBy { it.kind }
        val complete = ReductionLayerKind.entries.map { kind ->
            byKind[kind] ?: StorageReductionLayer(
                id = ReductionLayerId("${id.value}-${kind.name.lowercase()}"),
                kind = kind,
                visible = kind != ReductionLayerKind.SKELETON,
                score = notation.takeIf { kind == ReductionLayerKind.NOTATION },
            )
        }
        return if (legacyScore == null && complete == layers) this
        else copy(legacyScore = null, layers = complete)
    }
}

fun StorageScore.migrateReductionWorkspaces(): StorageScore =
    copy(reductions = reductions.map(StorageReduction::migrated))

@Serializable
enum class PlayerKind { SINGLE, SECTION }

@Serializable
data class StoragePlayer(
    val id: PlayerId,
    val name: String,
    val abbreviation: String? = null,
    val kind: PlayerKind = PlayerKind.SINGLE,
    val instruments: List<StoragePlayerInstrument> = emptyList(),
    val holds: List<InstrumentHold> = emptyList(),
)

@Serializable
data class StoragePlayerInstrument(
    val id: InstrumentId,
    val name: String,
    val abbreviation: String? = null,
    val transposition: TranspositionConfig? = null,
    val playback: InstrumentPlayback = InstrumentPlayback(),
)

@Serializable
data class InstrumentHold(val onset: TimeCode, val instrumentId: InstrumentId)

@Serializable
data class StoragePerformance(
    val lineId: TrackId,
    val onset: TimeCode,
    val playerIds: List<PlayerId> = emptyList(),
)

@Serializable
data class StorageStaffAssignment(
    val playerId: PlayerId,
    val lineId: TrackId? = null,
    val onset: TimeCode,
    val staffId: TrackId?,
    val voiceHint: Int? = null,
)

@Serializable
data class StorageOrchestration(
    val players: List<StoragePlayer> = emptyList(),
    val lines: List<TrackId> = emptyList(),
    val performances: List<StoragePerformance> = emptyList(),
    val staffAssignments: List<StorageStaffAssignment> = emptyList(),
    /** Content-line note (source) <-> written score note (target). */
    val links: List<StorageNoteLink> = emptyList(),
)
