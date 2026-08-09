package com.mecon.renderer.elements

import com.mecon.renderer.geometry.GlyphGeometry
import kotlinx.serialization.Serializable

/**
 * Structured information about a single notehead in a chord/note.
 *
 * Used by [NoteBodyElement] to provide per-pitch rendering info,
 * enabling each notehead to be rendered as an independent [RenderElement]
 * with its own [VoiceNoteSection] for fine-grained selection.
 *
 * @property pitchIndex Index into the event's pitchData list
 * @property staffPosition Staff position of this notehead (0 = center line)
 * @property geometry The glyph geometry for this notehead
 */
@Serializable
data class NoteheadRenderInfo(
    val pitchIndex: Int,
    val staffPosition: Int,
    val geometry: GlyphGeometry
)

/**
 * Structured information about a single accidental.
 *
 * @property pitchIndex Index into the event's pitchData list (links to its notehead)
 * @property geometry The glyph geometry for this accidental
 */
@Serializable
data class AccidentalRenderInfo(
    val pitchIndex: Int,
    val geometry: GlyphGeometry
)

/**
 * Structured information about a single augmentation dot.
 *
 * @property pitchIndex Index into the event's pitchData list (links to its notehead)
 * @property dotIndex Which dot this is (0-based, for double/triple dots)
 * @property geometry The glyph geometry for this dot
 */
@Serializable
data class DotRenderInfo(
    val pitchIndex: Int,
    val dotIndex: Int,
    val geometry: GlyphGeometry
)
