package com.mecon.renderer.render.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.interaction.ClefSection
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.edit.ClefEditEngine
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
     * A clef inserted mid-measure uses the same visual boundary as a breath mark: the midpoint
     * between the note columns on either side, addressing the note on its right.
     */
    @Test
    fun ghostClefSnapsToMidpointBetweenNotes() {
        val font = loadFont() ?: return
        val runtime = scoreWithNotes()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val result = engine.render(runtime)
            val left = result.timeCodePositions[TimeCode.of(1, Fraction.ZERO)]
            val right = result.timeCodePositions[TimeCode.of(1, Fraction(1, 4))]
            assertNotNull(left, "measure downbeat should have a laid-out slot")
            assertNotNull(right, "beat 1 should have a laid-out slot")
            val midpoint = (left.x + right.x) / 2f
            val cy = result.elements.first { it.type == RenderElementType.STAFF }.hitBox.center.y.value
            val ghost = engine.computeClefGhost(
                result, runtime, AbsolutePoint(Pixels(midpoint), Pixels(cy)), Clef.BASS
            )
            assertNotNull(ghost, "clef ghost should resolve over beat 1")
            assertTrue(
                ghost.onset == right.timeCode,
                "boundary should address the note on its right, was ${ghost.onset}",
            )
            val x = lineX(ghost.commands)
            assertNotNull(x, "clef ghost should draw an insertion line")
            assertTrue(abs(x - midpoint) < 0.5f, "line should sit at the visual midpoint ($midpoint), was $x")
        }
    }

    /**
     * A clef inserted on a barline previews at that exact boundary and the committed clef is laid
     * out on the barline's left, rather than after it.
     */
    @Test
    fun clefAtMeasureBoundarySitsLeftOfBarline() {
        val font = loadFont() ?: return
        val runtime = scoreWithNotes()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val result = engine.render(runtime)
            val downbeat = result.timeCodePositions[TimeCode.of(2, Fraction.ZERO)]
            assertNotNull(downbeat, "bar 2 downbeat should have a laid-out slot")
            val barline = result.elements
                .filter { it.type == RenderElementType.BARLINE }
                .minByOrNull { abs(it.center.x.value - downbeat.leftX) }
            assertNotNull(barline, "measure-opening barline should be rendered")
            val onset = result.sectionIndex.sectionsFor(barline.id)
                .filterIsInstance<BarlineSection>()
                .single()
                .barline
                .time
            val cy = result.elements.first { it.type == RenderElementType.STAFF }.hitBox.center.y.value
            val ghost = engine.computeClefGhost(
                result, runtime, AbsolutePoint(Pixels(barline.center.x.value), Pixels(cy)), Clef.BASS
            )
            assertNotNull(ghost, "clef ghost should resolve over the bar-2 downbeat")
            assertTrue(ghost.onset == onset, "clef ghost should snap to the barline, was ${ghost.onset}")
            val x = lineX(ghost.commands)
            assertNotNull(x, "clef ghost should draw an insertion line")
            val barlineX = barline.center.x.value
            assertTrue(abs(x - barlineX) < 0.75f, "line should coincide with the barline ($barlineX), was $x")

            val editedRuntime = ClefEditEngine.setClef(
                runtime,
                ClefEditEngine.Target(ghost.staffTrackId, ghost.onset),
                Clef.BASS,
            )?.score
            assertNotNull(editedRuntime, "clef edit should apply")
            val edited = engine.render(editedRuntime)
            val committedClef = edited.elements.firstOrNull { element ->
                element.type == RenderElementType.CLEF &&
                    edited.sectionIndex.sectionsFor(element.id)
                        .filterIsInstance<ClefSection>()
                        .any { it.clef.time.compareTo(onset) == 0 && !it.clef.isInitial }
            }
            assertNotNull(
                committedClef,
                "rendered clef change missing; computed=${editedRuntime.staffTracks[ghost.staffTrackId]?.clefChanges}",
            )
            val committedBarline = edited.elements.firstOrNull { element ->
                element.type == RenderElementType.BARLINE &&
                    edited.sectionIndex.sectionsFor(element.id)
                        .filterIsInstance<BarlineSection>()
                        .any { it.barline.time.compareTo(onset) == 0 }
            }
            assertNotNull(committedBarline, "rendered boundary barline missing at $onset")
            val clefRight = committedClef.hitBox.origin.x.value + committedClef.hitBox.width.value
            val barlineLeft = committedBarline.hitBox.origin.x.value
            assertTrue(
                clefRight < barlineLeft,
                "committed clef must be left of the barline (clefRight=$clefRight, barlineLeft=$barlineLeft)",
            )
        }
    }
}
