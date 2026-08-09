package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.snapshot.loadFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chord containing two value-equal pitches (a unison — e.g. produced by dragging one chord note
 * onto another) must still render BOTH noteheads, displaced onto opposite sides of the stem. Earlier
 * the builder keyed offsets/indices by [ComputedPitchData] value, collapsing the duplicate.
 */
class NoteBodyUnisonTest {

    private fun pd(
        staffPosition: Int,
        pitch: Pitch = Pitch.B4,
        accidental: Accidental? = null,
    ) = ComputedPitchData(
        pitch = pitch,
        midiPitch = 71,
        staffPosition = staffPosition,
        effectiveAccidental = accidental,
        needsLedgerLine = false,
    )

    @Test
    fun unisonRendersBothNoteheadsOnOppositeSides() {
        val font = loadFont() ?: return
        with(font) {
            val builder = NoteBodyElementBuilder(RenderLayoutConfig.DEFAULT)
            // Two identical pitches at the same staff position (a unison).
            val body = builder.buildNoteGeometry(listOf(pd(0), pd(0)), Duration.QUARTER, StemDirection.UP)

            // Both noteheads are present with distinct pitch indices (not collapsed to one).
            assertEquals(2, body.noteheads.size, "both unison noteheads must render")
            assertEquals(setOf(0, 1), body.noteheads.map { it.pitchIndex }.toSet())

            // They sit on opposite sides of the stem → different X origins (one ~0, one ~notehead width).
            val xs = body.noteheads.map { it.geometry.bounds.origin.x.value }.sorted()
            assertTrue(xs[1] - xs[0] > 0.5f, "unison noteheads must be displaced horizontally, got $xs")
        }
    }

    @Test
    fun distinctChordUnaffected() {
        val font = loadFont() ?: return
        with(font) {
            val builder = NoteBodyElementBuilder(RenderLayoutConfig.DEFAULT)
            // C-E-G (thirds): no clustering, all on the same side, distinct indices preserved.
            val body = builder.buildNoteGeometry(
                listOf(pd(-2), pd(0), pd(2)), Duration.QUARTER, StemDirection.UP
            )
            assertEquals(3, body.noteheads.size)
            assertEquals(setOf(0, 1, 2), body.noteheads.map { it.pitchIndex }.toSet())
        }
    }

    @Test
    fun closeAccidentalsUseSeparateNonOverlappingColumns() {
        val font = loadFont() ?: return
        with(font) {
            val body = NoteBodyElementBuilder(RenderLayoutConfig.DEFAULT).buildNoteGeometry(
                listOf(
                    pd(0, Pitch(0, 1), Accidental.SHARP),
                    pd(1, Pitch(1, -1), Accidental.FLAT),
                ),
                Duration.QUARTER,
                StemDirection.UP,
            )

            assertEquals(2, body.accidentals.size)
            assertFalse(
                body.accidentals[0].geometry.bounds.overlaps(body.accidentals[1].geometry.bounds),
                "adjacent accidentals must be staggered into separate columns",
            )
            assertTrue(
                body.accidentals.map { it.geometry.bounds.left.value }.distinct().size == 2,
                "close accidentals must not share the same X column",
            )
        }
    }

    @Test
    fun differentlyAlteredUnisonUsesSplitHeadsAndAccidentalColumns() {
        val font = loadFont() ?: return
        with(font) {
            val body = NoteBodyElementBuilder(RenderLayoutConfig.DEFAULT).buildNoteGeometry(
                listOf(
                    pd(0, Pitch(0, 0), Accidental.NATURAL),
                    pd(0, Pitch(0, 1), Accidental.SHARP),
                ),
                Duration.QUARTER,
                StemDirection.UP,
            )

            assertEquals(2, body.noteheads.size)
            assertEquals(2, body.noteheads.map { it.geometry.bounds.left }.distinct().size)
            assertEquals(2, body.accidentals.size)
            assertFalse(body.accidentals[0].geometry.bounds.overlaps(body.accidentals[1].geometry.bounds))
        }
    }
}
