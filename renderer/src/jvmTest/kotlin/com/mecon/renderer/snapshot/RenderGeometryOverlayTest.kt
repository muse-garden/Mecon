package com.mecon.renderer.snapshot

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.Articulation
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 of persisted slur / articulation geometry (docs/data_model/incremental-update.md):
 * capturing the auto layout into the [com.mecon.api.storage.ScoreGeometry] overlay and re-resolving
 * it must reproduce the same picture — i.e. the overlay is a faithful, consumed cache of the auto
 * result. This is the new consumer that keeps the overlay path honest.
 */
class RenderGeometryOverlayTest {

    private fun scoreFile(name: String) = File(testScoreDir(), name)

    /**
     * For a score with slurs / articulations: render auto (no overlay) → capture geometry → attach it
     * and render again. The two renders must be pixel-equivalent, proving capture+resolve is identity.
     */
    private fun assertCaptureReresolveIsIdentity(fileName: String) {
        val font = loadFont() ?: return
        val storage = ScoreSerializer.fromYaml(scoreFile(fileName).readText())

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val auto = engine.render(RuntimeScore.fromStorage(storage))

            val captured = engine.captureGeometry()
            assertNotNull(captured, "expected captured geometry for $fileName")
            assertTrue(
                captured.ties.isNotEmpty() || captured.slurs.isNotEmpty() || captured.articulations.isNotEmpty(),
                "expected non-empty captured geometry for $fileName " +
                    "(ties=${captured.ties.size}, slurs=${captured.slurs.size}, " +
                    "articulations=${captured.articulations.size})"
            )

            // Re-render a fresh engine with the captured overlay folded into storage.
            val withOverlay = RenderEngine(RenderLayoutConfig.DEFAULT)
                .render(RuntimeScore.fromStorage(storage.copy(geometry = captured)))

            // Overlay-driven render reproduces the auto render (the overlay is the source of truth and is
            // re-anchored to identical positions on first capture).
            assertCommandMultisetEquivalent(auto, withOverlay)
        }
    }

    @Test
    fun slurGeometryCaptureReresolveMatchesAuto() = assertCaptureReresolveIsIdentity("14_slurs.mscore.yaml")

    @Test
    fun tieGeometryCaptureReresolveMatchesAuto() = assertCaptureReresolveIsIdentity("07_ties.mscore.yaml")

    @Test
    fun tieFlipsDirectionBeforeIncreasingCurvatureForAnotherVoice() {
        val font = loadFont() ?: return

        fun score(obstacleInsideTie: Boolean): StorageScore {
            val skeleton = StorageScore.create(StorageScore.CreationOptions(
                title = "Tie collision",
                timeSignature = TimeSignature.COMMON,
                keySignature = KeySignature.C_MAJOR,
                measureCount = 2,
            ))
            val firstPitchTrack = skeleton.pitchTracks.values.single()
            val firstVoice = skeleton.voiceTracks.values.single()
            val sourcePitch = StoragePitchEvent(EventId("p-source"), TimeCode.of(1, Fraction.ZERO), listOf(Pitch.C4))
            val targetPitch = StoragePitchEvent(
                EventId("p-target"), TimeCode.of(1, Fraction(1, 2)), listOf(Pitch.C4)
            )
            val source = StorageVoiceEvent(
                EventId("source"), sourcePitch.onset, sourcePitch.id, Duration.HALF,
                ties = listOf(TieInfo(0)),
            )
            val target = StorageVoiceEvent(
                EventId("target"), targetPitch.onset, targetPitch.id, Duration.HALF,
            )
            val secondPitchTrack = StoragePitchTrack(com.mecon.api.primitive.TrackId("pt-lower"))
            val obstacleOnset = if (obstacleInsideTie) {
                TimeCode.of(1, Fraction(1, 4))
            } else {
                TimeCode.of(2, Fraction.ZERO)
            }
            val obstaclePitch = StoragePitchEvent(
                EventId("p-obstacle"), obstacleOnset, listOf(Pitch(-1, 0))
            )
            val secondVoice = StorageVoiceTrack(
                id = com.mecon.api.primitive.TrackId("voice-lower"),
                name = "Lower",
                voiceNumber = 2,
                pitchTrackId = secondPitchTrack.id,
                events = listOf(
                    StorageVoiceEvent(
                        EventId("obstacle"), obstacleOnset, obstaclePitch.id, Duration.QUARTER,
                    )
                ),
            )
            val staff = skeleton.staffTracks.values.single()
            return skeleton.copy(
                pitchTracks = mapOf(
                    firstPitchTrack.id to firstPitchTrack.copy(events = listOf(sourcePitch, targetPitch)),
                    secondPitchTrack.id to secondPitchTrack.copy(events = listOf(obstaclePitch)),
                ),
                voiceTracks = mapOf(
                    firstVoice.id to firstVoice.copy(events = listOf(source, target)),
                    secondVoice.id to secondVoice,
                ),
                staffTracks = mapOf(
                    staff.id to staff.copy(voiceTrackIds = listOf(firstVoice.id, secondVoice.id))
                ),
            )
        }

        fun geometry(storage: StorageScore): com.mecon.api.storage.TieGeometry = with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(RuntimeScore.fromStorage(storage))
            assertNotNull(engine.captureGeometry())
                .ties.getValue(EventId("source")).single()
        }

        val unobstructed = geometry(score(obstacleInsideTie = false))
        val obstructedScore = score(obstacleInsideTie = true)
        val obstructed = geometry(obstructedScore)
        assertTrue(!unobstructed.above, "the stem policy should initially place this tie below")
        assertTrue(obstructed.above, "an obstructed lower side should flip the tie above first")

        val forcedBelow = obstructed.copy(
            above = false,
            minApex = 0f,
            maxApex = 0f,
            directionOnly = true,
            directionLocked = true,
            manuallyAdjusted = false,
            autoEndpoints = false,
        )
        val lockedGeometry = geometry(obstructedScore.copy(
            geometry = com.mecon.api.storage.ScoreGeometry(
                ties = mapOf(EventId("source") to listOf(forcedBelow)),
            ),
        ))
        assertTrue(
            obstructed.minApex + 0.05f < lockedGeometry.minApex,
            "automatic direction avoidance must need less curvature than a forced original side: " +
                "flipped=${obstructed.minApex} forced=${lockedGeometry.minApex}",
        )
    }

    @Test
    fun articulationGeometryCaptureReresolveMatchesAuto() = assertCaptureReresolveIsIdentity("18_articulations.mscore.yaml")

    @Test
    fun staleStoredArticulationXIsRecenteredOnTheCurrentNoteColumn() {
        val font = loadFont() ?: return
        val storage = ScoreSerializer.fromYaml(scoreFile("18_articulations.mscore.yaml").readText())

        with(font) {
            val autoEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val auto = autoEngine.render(RuntimeScore.fromStorage(storage))
            val captured = assertNotNull(autoEngine.captureGeometry())
            val staleX = captured.copy(
                articulations = captured.articulations.mapValues { (_, geometry) ->
                    geometry.copy(marks = geometry.marks.map { it.copy(dx = it.dx + 2f) })
                },
            )

            val resolved = RenderEngine(RenderLayoutConfig.DEFAULT)
                .render(RuntimeScore.fromStorage(storage.copy(geometry = staleX)))

            assertCommandMultisetEquivalent(auto, resolved)
        }
    }

    @Test
    fun staleSingleMarkGeometryDoesNotHideASecondArticulation() {
        val font = loadFont() ?: return
        val storage = ScoreSerializer.fromYaml(scoreFile("18_articulations.mscore.yaml").readText())
        val runtime = RuntimeScore.fromStorage(storage)

        with(font) {
            val initialEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            initialEngine.render(runtime)
            val captured = assertNotNull(initialEngine.captureGeometry())
            val target = runtime.voiceTracks.values.asSequence()
                .flatMap { voice -> voice.events.toList().asSequence().map { voice.id to it } }
                .first { (_, event) ->
                    !event.isRest && event.pitchEvent.articulations.size == 1 && event.id in captured.articulations
                }
            val additional = listOf(
                Articulation.STACCATO, Articulation.TENUTO, Articulation.ACCENT, Articulation.MARCATO,
            ).first { it !in target.second.pitchEvent.articulations }

            val edited = assertNotNull(ExpressionEditEngine.toggleArticulation(
                runtime.copy(geometry = captured),
                listOf(ExpressionEditEngine.NoteTarget(target.first, target.second.id)),
                additional,
            )).score

            val editedEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            editedEngine.render(edited)
            val recaptured = assertNotNull(editedEngine.captureGeometry())
            assertEquals(
                2,
                recaptured.articulations.getValue(target.second.id).marks.size,
                "a stale one-mark cache must fall back to auto layout for the complete stack",
            )
        }
    }
}
