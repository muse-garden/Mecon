package com.mecon.desktop.ui.harmony

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.desktop.uikit.components.ChordDetailUiConstruction
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.render.RenderElementType

internal data class ConstructionPreviewScore(
    val score: RuntimeScore,
    val mutedSections: Set<EventSectionId>,
)

/** Compact structural chord illustration rendered by the normal score engraver. */
@Composable
internal fun ChordConstructionScorePreview(
    construction: ChordDetailUiConstruction,
    modifier: Modifier = Modifier,
) {
    val preview = remember(construction) { construction.toPreviewScore() }
    SimpleScoreView(
        score = preview.score,
        modifier = modifier.fillMaxWidth().height(104.dp),
        fitScale = 0.8f,
        background = MeconColors.ScoreBackground,
        foreground = MeconColors.ScoreInk,
        noteForegrounds = preview.mutedSections.associateWith { MeconColors.ScoreMutedInk },
        visibleTypes = setOf(
            RenderElementType.STAFF,
            RenderElementType.STAFF_LINE,
            RenderElementType.CLEF,
            RenderElementType.KEY_SIGNATURE,
            RenderElementType.NOTEHEAD,
            RenderElementType.STEM,
            RenderElementType.ACCIDENTAL,
            RenderElementType.LEDGER_LINE,
        ),
        cropTypes = setOf(
            RenderElementType.CLEF,
            RenderElementType.KEY_SIGNATURE,
            RenderElementType.NOTEHEAD,
            RenderElementType.ACCIDENTAL,
        ),
        cropPaddingPx = 28f,
        verticalFitTypes = setOf(
            RenderElementType.STAFF,
            RenderElementType.STAFF_LINE,
            RenderElementType.CLEF,
            RenderElementType.KEY_SIGNATURE,
            RenderElementType.NOTEHEAD,
            RenderElementType.ACCIDENTAL,
            RenderElementType.LEDGER_LINE,
        ),
    )
}

internal fun ChordDetailUiConstruction.toPreviewScore(): ConstructionPreviewScore {
    require(events.isNotEmpty()) { "Construction preview requires at least one event" }
    val empty = StorageScore.create(
        StorageScore.CreationOptions(
            timeSignature = TimeSignature(4, 4),
            keySignature = keySignatureFifths?.let(KeySignature::majorByFifths)
                ?: KeySignature.C_MAJOR,
            // Each event is an independent harmonic example. Invisible measure boundaries
            // restart accidental state so every chord prints its own alterations.
            measureCount = events.size,
        )
    )
    val pitchTrackId = empty.pitchTracks.keys.first()
    val voiceTrackId = empty.voiceTracks.keys.first()
    val mutedSections = linkedSetOf<EventSectionId>()
    var storage = empty
    events.forEachIndexed { eventIndex, event ->
        val onset = TimeCode.of(eventIndex + 1, 0, 4)
        val pitchEvent = StoragePitchEvent.create(onset, event.tones.map { it.pitch })
        val voiceEvent = StorageVoiceEvent.create(onset, pitchEvent.id, Duration.WHOLE)
        storage = storage
            .addPitchEvent(pitchTrackId, pitchEvent)
            .addVoiceEvent(voiceTrackId, voiceEvent)
        event.tones.forEachIndexed { toneIndex, tone ->
            if (tone.muted) {
                mutedSections += EventSectionId.voiceNote(voiceEvent.id, toneIndex)
            }
        }
    }
    return ConstructionPreviewScore(
        score = RuntimeScore.fromStorage(storage),
        mutedSections = mutedSections,
    )
}
