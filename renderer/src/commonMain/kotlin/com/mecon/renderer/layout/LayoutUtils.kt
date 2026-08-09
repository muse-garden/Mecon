package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.enums.RestType
import com.mecon.renderer.geometry.StaffSpace

/**
 * Shared utility functions for layout computation.
 *
 * These functions are used by both [NoteBodyElementBuilder] and [VoiceEventLayoutBuilder].
 */

/**
 * Convert staff position to relative Y.
 * Staff position 0 = middle line = relativeY 0
 * Positive staff positions go down (positive Y, since Y increases downward)
 */
internal fun staffPositionToRelativeY(staffPosition: Int): StaffSpace =
    StaffSpace(-staffPosition * 0.5f)

/**
 * Convert duration to notehead type.
 */
internal fun noteheadTypeFromDuration(duration: Duration): NoteheadType = when (duration.base) {
    DurationBase.MAXIMA, DurationBase.LONGA, DurationBase.BREVE -> NoteheadType.DOUBLE_WHOLE
    DurationBase.WHOLE -> NoteheadType.WHOLE
    DurationBase.HALF -> NoteheadType.HALF
    else -> NoteheadType.BLACK
}
/**
 * Convert duration to rest type.
 */
internal fun restTypeFromDuration(duration: Duration): RestType = when (duration.base) {
    DurationBase.MAXIMA -> RestType.MAXIMA
    DurationBase.LONGA -> RestType.LONGA
    DurationBase.BREVE -> RestType.DOUBLE_WHOLE
    DurationBase.WHOLE -> RestType.WHOLE
    DurationBase.HALF -> RestType.HALF
    DurationBase.QUARTER -> RestType.QUARTER
    DurationBase.EIGHTH -> RestType.EIGHTH
    DurationBase.SIXTEENTH -> RestType.SIXTEENTH
    DurationBase.THIRTY_SECOND -> RestType.THIRTY_SECOND
    DurationBase.SIXTY_FOURTH -> RestType.SIXTY_FOURTH
    DurationBase.ONE_TWENTY_EIGHTH -> RestType.ONE_TWENTY_EIGHTH
}
