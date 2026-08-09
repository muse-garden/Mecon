package com.mecon.api.primitive

import kotlinx.serialization.Serializable

/**
 * Types of barlines.
 */
@Serializable
enum class BarlineType {
    /** Standard single barline */
    SINGLE,

    /** Two thin lines */
    DOUBLE,

    /** Thin-thick (end of piece) */
    FINAL,

    /** Thick-thin */
    REVERSE_FINAL,

    /** Dashed line */
    DASHED,

    /** Dotted line */
    DOTTED,

    /** Short barline (between staves) */
    SHORT,

    /** Tick mark at top of staff */
    TICK,

    /** Left repeat sign (thick-thin-dots) */
    REPEAT_LEFT,

    /** Right repeat sign (dots-thin-thick) */
    REPEAT_RIGHT,

    /** Both repeats (dots-thin-thick-thin-dots) */
    REPEAT_BOTH
}
