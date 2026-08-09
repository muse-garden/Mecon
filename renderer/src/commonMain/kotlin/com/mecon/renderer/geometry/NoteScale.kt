package com.mecon.renderer.geometry

import com.mecon.renderer.layout.RenderConstants
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Visual scale factor for a note relative to normal size.
 *
 * A value of 1.0 means full-size; values below 1.0 produce smaller glyphs
 * (e.g. grace notes, cue notes). Stores a single Float and serialises as one.
 *
 * All stem/beam/flag dimensions in the renderer are multiplied by [value] when
 * rendering scaled notes, keeping stem thickness, beam thickness, beam spacing,
 * flag size, and stem length proportional to the notehead size.
 */
@Serializable
@JvmInline
value class NoteScale(val value: Float) {
    companion object {
        /** Full-size note (default). */
        val NORMAL = NoteScale(1f)

        /** Grace-note scale from the current render constants. */
        val GRACE = NoteScale(RenderConstants.GRACE_NOTE_SCALE)
    }
}
