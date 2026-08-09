package com.mecon.renderer.render.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.snapshot.loadFont
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GhostClefComputerTest {

    private fun scoreWithNotes(): RuntimeScore {
        var runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR))
        )
        val voiceId = runtime.orderedStaffs().first().voiceTracks.first().id
        val b4 = Pitch.fromName("B4")
        // Two quarters in bar 1 (gives a mid-measure onset) and one on the bar-2 downbeat
        // (gives a slot whose leftmost element is the measure-opening barline).
        listOf(
            TimeCode.of(1, Fraction.ZERO),
            TimeCode.of(1, Fraction(1, 4)),
            TimeCode.of(2, Fraction.ZERO),
        ).forEach { start ->
            runtime = NoteEditEngine.insert(
                runtime,
                NoteEditEngine.Insertion(
                    voiceTrackId = voiceId,
                    start = start,
                    duration = Duration.QUARTER,
                    pitch = b4,
                )
            )!!.score
        }
        return runtime
    }

    /** The vertical insertion line of a ghost clef (the sole [DrawLine] in its commands). */
    private fun lineX(commands: List<com.mecon.renderer.render.RenderCommand>): Float? =
        commands.filterIsInstance<DrawLine>().firstOrNull()?.start?.x?.value

    /**
     * A clef inserted mid-measure previews its boundary line at the LEFT edge of the note it will
     * govern — i.e. the slot's [com.mecon.renderer.render.TimeCodePosition.leftX], strictly left of the
     * slot's right edge (`x`), not on the notehead as before.
     */
    @Test
    fun ghostClefLineSitsAtNoteLeftEdge() {
        val font = loadFont() ?: return
        val runtime = scoreWithNotes()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val result = engine.render(runtime)
            val onset = TimeCode.of(1, Fraction(1, 4))
            val pos = result.timeCodePositions[onset]
            assertNotNull(pos, "beat 1 should have a laid-out slot")
            val cy = result.elements.first { it.type == RenderElementType.STAFF }.hitBox.center.y.value
            val ghost = engine.computeClefGhost(
                result, runtime, AbsolutePoint(Pixels(pos.x), Pixels(cy)), Clef.BASS
            )
            assertNotNull(ghost, "clef ghost should resolve over beat 1")
            assertTrue(ghost.onset == onset, "clef ghost should snap to beat 1, was ${ghost.onset}")
            val x = lineX(ghost.commands)
            assertNotNull(x, "clef ghost should draw an insertion line")
            assertTrue(abs(x - pos.leftX) < 0.5f, "line should sit at the note's left edge (leftX=${pos.leftX}), was $x")
            assertTrue(x < pos.x - 0.5f, "line should be strictly left of the slot right edge (x=${pos.x}), was $x")
        }
    }

    /**
     * A clef inserted on a measure downbeat previews its boundary line coincident with the
     * measure-opening barline (the slot's leftmost element).
     */
    @Test
    fun ghostClefLineCoincidesWithBarlineAtDownbeat() {
        val font = loadFont() ?: return
        val runtime = scoreWithNotes()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val result = engine.render(runtime)
            val onset = TimeCode.of(2, Fraction.ZERO)
            val pos = result.timeCodePositions[onset]
            assertNotNull(pos, "bar 2 downbeat should have a laid-out slot")
            val cy = result.elements.first { it.type == RenderElementType.STAFF }.hitBox.center.y.value
            val ghost = engine.computeClefGhost(
                result, runtime, AbsolutePoint(Pixels(pos.x), Pixels(cy)), Clef.BASS
            )
            assertNotNull(ghost, "clef ghost should resolve over the bar-2 downbeat")
            assertTrue(ghost.onset == onset, "clef ghost should snap to bar-2 downbeat, was ${ghost.onset}")
            val x = lineX(ghost.commands)
            assertNotNull(x, "clef ghost should draw an insertion line")
            // The barline whose X the line should coincide with: the one nearest the insertion line.
            val barlineX = result.elements
                .filter { it.type == RenderElementType.BARLINE }
                .map { it.hitBox.center.x.value }
                .minByOrNull { abs(it - x) }
            assertNotNull(barlineX, "the score should have a barline")
            assertTrue(abs(x - barlineX) < 0.75f, "line should coincide with the barline ($barlineX), was $x")
        }
    }
}
