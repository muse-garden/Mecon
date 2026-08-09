package com.mecon.core.musicxml.import

import com.mecon.core.musicxml.model.MusicXmlNote
import com.mecon.core.musicxml.model.MusicXmlTimeModification
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Tuplet

/**
 * Converts between MusicXML duration representation and Mecon Duration.
 *
 * MusicXML uses:
 * - divisions: divisions per quarter note (in attributes)
 * - duration: actual duration in divisions
 * - type: note type name (whole, half, quarter, eighth, 16th, 32nd, 64th, 128th)
 * - dot: number of augmentation dots
 * - time-modification: tuplet ratio (actual-notes/normal-notes)
 *
 * Mecon uses:
 * - DurationBase enum: fundamental note value
 * - dots: number of dots (0-3)
 * - Tuplet: actual/normal ratio
 */
object DurationConverter {

    /**
     * Standard divisions per quarter note for export.
     * Using 1024 provides plenty of resolution for complex rhythms.
     */
    const val EXPORT_DIVISIONS = 1024

    /**
     * Map from MusicXML type string to DurationBase.
     */
    private val TYPE_TO_BASE = mapOf(
        "maxima" to DurationBase.MAXIMA,
        "long" to DurationBase.LONGA,
        "breve" to DurationBase.BREVE,
        "whole" to DurationBase.WHOLE,
        "half" to DurationBase.HALF,
        "quarter" to DurationBase.QUARTER,
        "eighth" to DurationBase.EIGHTH,
        "16th" to DurationBase.SIXTEENTH,
        "32nd" to DurationBase.THIRTY_SECOND,
        "64th" to DurationBase.SIXTY_FOURTH,
        "128th" to DurationBase.ONE_TWENTY_EIGHTH
    )

    /**
     * Map from DurationBase to MusicXML type string.
     */
    private val BASE_TO_TYPE = mapOf(
        DurationBase.MAXIMA to "maxima",
        DurationBase.LONGA to "long",
        DurationBase.BREVE to "breve",
        DurationBase.WHOLE to "whole",
        DurationBase.HALF to "half",
        DurationBase.QUARTER to "quarter",
        DurationBase.EIGHTH to "eighth",
        DurationBase.SIXTEENTH to "16th",
        DurationBase.THIRTY_SECOND to "32nd",
        DurationBase.SIXTY_FOURTH to "64th",
        DurationBase.ONE_TWENTY_EIGHTH to "128th"
    )

    /**
     * Convert MusicXML note to Mecon Duration.
     *
     * Prefers using <type> element when available, falls back to
     * calculating from divisions/duration.
     *
     * @param note The MusicXML note
     * @param divisions Current divisions per quarter note
     */
    fun fromMusicXml(note: MusicXmlNote, divisions: Int): Duration {
        // Prefer type element if available
        val base = note.type?.let { TYPE_TO_BASE[it.lowercase()] }
            ?: calculateBaseFromDivisions(note.duration ?: 0, divisions, note.timeModification)

        val tuplet = note.timeModification?.let {
            Tuplet(it.actualNotes, it.normalNotes)
        }

        return Duration(
            base = base ?: DurationBase.QUARTER,
            dots = note.dots,
            tuplet = tuplet
        )
    }

    /**
     * Convert Mecon Duration to MusicXML representation.
     *
     * @return Triple of (type, dots, duration_in_divisions)
     */
    fun toMusicXml(duration: Duration, divisions: Int = EXPORT_DIVISIONS): MusicXmlDurationResult {
        val type = BASE_TO_TYPE[duration.base] ?: "quarter"

        // Calculate duration in divisions
        // Quarter note = divisions
        // Half note = divisions * 2
        // Whole note = divisions * 4
        // Eighth note = divisions / 2, etc.

        val baseDivisions = when (duration.base) {
            DurationBase.MAXIMA -> divisions * 32
            DurationBase.LONGA -> divisions * 16
            DurationBase.BREVE -> divisions * 8
            DurationBase.WHOLE -> divisions * 4
            DurationBase.HALF -> divisions * 2
            DurationBase.QUARTER -> divisions
            DurationBase.EIGHTH -> divisions / 2
            DurationBase.SIXTEENTH -> divisions / 4
            DurationBase.THIRTY_SECOND -> divisions / 8
            DurationBase.SIXTY_FOURTH -> divisions / 16
            DurationBase.ONE_TWENTY_EIGHTH -> divisions / 32
        }

        // Apply dots: each dot adds half of the previous value
        var totalDivisions = baseDivisions
        var dotValue = baseDivisions / 2
        repeat(duration.dots) {
            totalDivisions += dotValue
            dotValue /= 2
        }

        // Apply tuplet ratio
        duration.tuplet?.let {
            totalDivisions = totalDivisions * it.normal / it.actual
        }

        val timeModification = duration.tuplet?.let {
            MusicXmlTimeModification(
                actualNotes = it.actual,
                normalNotes = it.normal,
                normalType = type
            )
        }

        return MusicXmlDurationResult(
            type = type,
            dots = duration.dots,
            duration = totalDivisions,
            timeModification = timeModification
        )
    }

    /**
     * Calculate duration base from divisions when type is not available.
     */
    private fun calculateBaseFromDivisions(
        durationInDivisions: Int,
        divisions: Int,
        timeModification: MusicXmlTimeModification?
    ): DurationBase? {
        if (durationInDivisions <= 0 || divisions <= 0) return null

        // Adjust for tuplet
        var adjustedDuration = durationInDivisions
        timeModification?.let {
            adjustedDuration = adjustedDuration * it.actualNotes / it.normalNotes
        }

        // Calculate ratio to quarter note
        val ratio = adjustedDuration.toFloat() / divisions

        // Find closest base duration (ignoring dots for now)
        return when {
            ratio >= 28f -> DurationBase.MAXIMA      // 32x quarter
            ratio >= 14f -> DurationBase.LONGA       // 16x quarter
            ratio >= 7f -> DurationBase.BREVE        // 8x quarter
            ratio >= 3.5f -> DurationBase.WHOLE      // 4x quarter
            ratio >= 1.75f -> DurationBase.HALF      // 2x quarter
            ratio >= 0.875f -> DurationBase.QUARTER  // 1x quarter
            ratio >= 0.4375f -> DurationBase.EIGHTH  // 0.5x quarter
            ratio >= 0.21875f -> DurationBase.SIXTEENTH
            ratio >= 0.109375f -> DurationBase.THIRTY_SECOND
            ratio >= 0.0546875f -> DurationBase.SIXTY_FOURTH
            else -> DurationBase.ONE_TWENTY_EIGHTH
        }
    }

    /**
     * Convert beat unit string to DurationBase.
     */
    fun beatUnitToBase(beatUnit: String): DurationBase {
        return TYPE_TO_BASE[beatUnit.lowercase()] ?: DurationBase.QUARTER
    }

    /**
     * Convert DurationBase to beat unit string.
     */
    fun baseToBeatUnit(base: DurationBase): String {
        return BASE_TO_TYPE[base] ?: "quarter"
    }
}

/**
 * Result of converting Mecon duration to MusicXML.
 */
data class MusicXmlDurationResult(
    /** Note type string (quarter, eighth, etc.) */
    val type: String,
    /** Number of dots */
    val dots: Int,
    /** Duration in divisions */
    val duration: Int,
    /** Time modification for tuplets, if any */
    val timeModification: MusicXmlTimeModification?
)
