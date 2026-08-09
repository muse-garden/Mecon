package com.mecon.api.storage.events

import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.RenderingProps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a tempo keyframe is engraved. AUTO preserves the legacy text/metronome behaviour. */
@Serializable
enum class TempoDisplayStyle {
    AUTO,
    METRONOME,
    TEXT,
    TEXT_AND_METRONOME,
    METRIC_MODULATION,
    GRADUAL_TEXT,
    HIDDEN,
}

/** Semantic preset used by the palette and property panel; playback remains data-driven. */
@Serializable
enum class TempoMarkType {
    CUSTOM,
    METRONOME,
    PIU_MOSSO,
    MENO_MOSSO,
    A_TEMPO,
    TEMPO_I,
    ACCELERANDO,
    RITARDANDO,
    METRIC_MODULATION,
    KEYFRAME,
}

/** Interpolation from this keyframe to the next onset-ordered keyframe. */
@Serializable
enum class TempoTransition {
    STEP,
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
}

/**
 * A global tempo keyframe.
 *
 * [bpm] is always quarter-note BPM. When [referenceEventId] is present, the effective value is the
 * referenced keyframe's effective BPM multiplied by [referenceRatio]; [bpm] remains a safe fallback
 * for broken/cyclic references and old readers. [transitionToNext] describes playback until the next
 * onset-ordered keyframe, while [displayStyle] independently controls engraving.
 */
@Serializable
@SerialName("tempo")
data class StorageTempoEvent(
    override val id: EventId,
    val onset: TimeCode,
    val bpm: Float,
    val beatUnit: DurationBase = DurationBase.QUARTER,
    val text: String? = null,
    val rendering: RenderingProps? = null,
    val markType: TempoMarkType = TempoMarkType.CUSTOM,
    val displayStyle: TempoDisplayStyle = TempoDisplayStyle.AUTO,
    val equivalentBeatUnit: DurationBase? = null,
    val referenceEventId: EventId? = null,
    val referenceRatio: Float = 1f,
    val transitionToNext: TempoTransition = TempoTransition.STEP,
) : StorageEvent {
    init {
        require(bpm > 0f && bpm.isFinite()) { "Tempo BPM must be finite and positive, got $bpm" }
        require(referenceRatio > 0f && referenceRatio.isFinite()) {
            "Tempo reference ratio must be finite and positive, got $referenceRatio"
        }
    }

    /** Beat-unit rate printed by a metronome mark. */
    fun displayedBpm(effectiveQuarterBpm: Float = bpm): Float =
        effectiveQuarterBpm / beatUnit.quarterNoteFactor()

    companion object {
        fun create(
            onset: TimeCode,
            bpm: Float,
            beatUnit: DurationBase = DurationBase.QUARTER,
            text: String? = null,
            markType: TempoMarkType = TempoMarkType.METRONOME,
            displayStyle: TempoDisplayStyle = TempoDisplayStyle.METRONOME,
            equivalentBeatUnit: DurationBase? = null,
            referenceEventId: EventId? = null,
            referenceRatio: Float = 1f,
            transitionToNext: TempoTransition = TempoTransition.STEP,
        ) = StorageTempoEvent(
            id = EventId.generate(),
            onset = onset,
            bpm = bpm,
            beatUnit = beatUnit,
            text = text,
            markType = markType,
            displayStyle = displayStyle,
            equivalentBeatUnit = equivalentBeatUnit,
            referenceEventId = referenceEventId,
            referenceRatio = referenceRatio,
            transitionToNext = transitionToNext,
        )
    }
}

/** Duration relative to a quarter note (quarter=1, half=2, eighth=0.5). */
fun DurationBase.quarterNoteFactor(): Float = ticks.toFloat() / DurationBase.QUARTER.ticks.toFloat()

