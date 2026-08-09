package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeStaffGroupMember
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Persisted geometry for staff attachments (dynamics / hairpin / 8va / 8vb), including editor moves.
 *
 *  1. **Capture** — rendering a score with hairpins folds an [AttachmentGeometry][com.mecon.api.storage.AttachmentGeometry]
 *     per span into the live overlay, recording **both** endpoints (so a later non-horizontal manual
 *     adjustment has a slot to live in) anchored to their own time-slot X.
 *  2. **Incremental fold** — an edit inside one span refreshes it (and any stacked outer sibling);
 *     a span far from the edit is reused **by reference**, no recompute, no drift.
 */
class RenderAttachmentGeometryTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.withAttachments(vararg atts: StorageStaffAttachment): RuntimeScore {
        val sid = staffTracks.keys.first()
        val track = staffTracks.getValue(sid)
        val updated = track.copy(attachments = track.attachments + atts)
        // staffGroups is denormalized — the leaf the layout traverses must be replaced too.
        fun fix(m: RuntimeStaffGroupMember): RuntimeStaffGroupMember = when (m) {
            is RuntimeStaffGroupMember.Staff ->
                if (m.staff.id == sid) RuntimeStaffGroupMember.Staff(updated) else m
            is RuntimeStaffGroupMember.Group ->
                RuntimeStaffGroupMember.Group(m.group.copy(members = m.group.members.map(::fix)))
        }
        return copy(
            staffTracks = staffTracks + (sid to updated),
            staffGroups = staffGroups.map { it.copy(members = it.members.map(::fix)) },
        )
    }

    /** Hairpin A over m1..m2 and hairpin B over m4..m5 — far enough apart for the BACK=1 window. */
    private fun twoHairpinScore(): RuntimeScore = emptyScore()
        .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
        .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
        .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
        .addNote("n4", tc(3, 0), Pitch.C4, Duration.WHOLE)
        .addNote("n5", tc(4, 0), Pitch.C4, Duration.QUARTER)
        .addNote("n6", tc(4, 1, 4), Pitch.E4, Duration.QUARTER)
        .addNote("n7", tc(5, 0), Pitch.G4, Duration.HALF)
        .withAttachments(
            StorageHairpin(
                EventId("hpA"), tc(1, 0), tc(2, 0),
                HairpinType.CRESCENDO, HairpinStyle.WEDGE, StaffAttachmentPlacement.BELOW,
            ),
            StorageHairpin(
                EventId("hpB"), tc(4, 0), tc(5, 0),
                HairpinType.DIMINUENDO, HairpinStyle.WEDGE, StaffAttachmentPlacement.BELOW,
            ),
        )

    @Test
    fun capturingAHairpinRecordsBothEndpoints() {
        val font = loadFont() ?: return
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(twoHairpinScore())
            val overlay = engine.captureGeometry()

            assertNotNull(overlay)
            assertEquals(2, overlay.attachments.size, "both hairpins captured")
            val a = overlay.attachments.getValue(EventId("hpA"))
            assertTrue(a.isSpan, "a hairpin is a span — both endpoints present")
            assertNotNull(a.endDx)
            assertNotNull(a.endDy)
            assertTrue(a.spread > 0f, "a wedge records its opening")
        }
    }

    @Test
    fun anOverlayMatchingAutoLayoutRendersIdentically() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoHairpinScore()
            val auto = RenderEngine(RenderLayoutConfig.DEFAULT).render(base)

            val o0 = RenderEngine(RenderLayoutConfig.DEFAULT)
            o0.render(base)
            val overlay = o0.captureGeometry()
            assertNotNull(overlay)

            // Rendering with an overlay captured from auto must reproduce auto pixel-for-pixel:
            // the stored Y back-solves the same band the auto stack would have chosen.
            val withOverlay = RenderEngine(RenderLayoutConfig.DEFAULT).render(base.copy(geometry = overlay))
            assertCommandMultisetEquivalent(auto, withOverlay)
        }
    }

    @Test
    fun aStoredHairpinYIsAuthoritativeAndLocal() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoHairpinScore()
            val o0 = RenderEngine(RenderLayoutConfig.DEFAULT)
            o0.render(base)
            val overlay = o0.captureGeometry()
            assertNotNull(overlay)

            // Push hairpin A's stored band down by 3 staff-spaces (what a future manual drag would write).
            val delta = 3f
            val mutated = overlay.copy(
                attachments = overlay.attachments.mapValues { (id, g) ->
                    if (id == EventId("hpA")) g.copy(startDy = g.startDy + delta, endDy = (g.endDy ?: g.startDy) + delta)
                    else g
                },
            )

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base.copy(geometry = mutated))
            val recaptured = engine.captureGeometry()
            assertNotNull(recaptured)

            // Hairpin A was laid out at the stored (moved) Y; hairpin B kept its auto Y.
            assertEquals(
                mutated.attachments.getValue(EventId("hpA")).startDy,
                recaptured.attachments.getValue(EventId("hpA")).startDy,
                0.01f, "hairpin A follows its overlay Y",
            )
            assertEquals(
                overlay.attachments.getValue(EventId("hpB")).startDy,
                recaptured.attachments.getValue(EventId("hpB")).startDy,
                0.01f, "hairpin B is untouched by A's move",
            )
        }
    }

    @Test
    fun manuallyAdjustedHairpinKeepsEndpointYButReturnsXToAutoLayout() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoHairpinScore()
            val captureEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            captureEngine.render(base)
            val overlay = assertNotNull(captureEngine.captureGeometry())
            val old = overlay.attachments.getValue(EventId("hpA"))
            val moved = old.copy(
                startDx = old.startDx + 0.75f,
                endDx = assertNotNull(old.endDx) - 0.5f,
                startDy = old.startDy + 3f,
                endDy = assertNotNull(old.endDy) + 4f,
                manuallyAdjustedY = true,
            )
            val renderEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            renderEngine.render(base.copy(geometry = overlay.copy(
                attachments = overlay.attachments + (EventId("hpA") to moved),
            )))
            val recaptured = assertNotNull(renderEngine.captureGeometry()).attachments.getValue(EventId("hpA"))
            assertEquals(old.startDx, recaptured.startDx, 0.01f, "X is auto-laid out from the TimeCode")
            assertEquals(assertNotNull(old.endDx), assertNotNull(recaptured.endDx), 0.01f)
            assertEquals(moved.startDy, recaptured.startDy, 0.01f)
            assertEquals(assertNotNull(moved.endDy), assertNotNull(recaptured.endDy), 0.01f)
        }
    }

    @Test
    fun manuallyAdjustedHairpinYSurvivesIncrementalRelayout() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoHairpinScore()
            val captureEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            captureEngine.render(base)
            val auto = assertNotNull(captureEngine.captureGeometry())
            val old = auto.attachments.getValue(EventId("hpA"))
            val manual = old.copy(
                startDy = old.startDy + 3f,
                endDy = assertNotNull(old.endDy) + 4f,
                manuallyAdjustedY = true,
            )
            val seededScore = base.copy(geometry = auto.copy(
                attachments = auto.attachments + (EventId("hpA") to manual),
            ))
            val edited = seededScore.editPitch("n2", Pitch(6, 1))
            val previous = computeScore(seededScore)
            val incremental = computeScoreIncremental(
                previous, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)),
            )

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(seededScore)
            engine.renderIncremental(incremental.computed, incremental.changeSet)
            val after = assertNotNull(engine.captureGeometry()).attachments.getValue(EventId("hpA"))

            assertEquals(manual.startDy, after.startDy, 0.01f)
            assertEquals(assertNotNull(manual.endDy), assertNotNull(after.endDy), 0.01f)
            assertTrue(after.manuallyAdjustedY)
        }
    }

    @Test
    fun editingInsideOneSpanRefreshesItWhileTheFarSpanIsReusedByReference() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoHairpinScore()
            val o0 = RenderEngine(RenderLayoutConfig.DEFAULT)
            o0.render(base)
            val overlay = o0.captureGeometry()
            assertNotNull(overlay)

            val baseOverlay = base.copy(geometry = overlay)
            // Edit n2 (inside hairpin A's m1..m2 span) → an accidental, widening m1.
            val editedOverlay = baseOverlay.editPitch("n2", Pitch(6, 1)) // F#4

            val prev = computeScore(baseOverlay)
            val inc = computeScoreIncremental(prev, editedOverlay, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(baseOverlay)
            val seeded = engine.captureGeometry()
            assertNotNull(seeded)
            engine.renderIncremental(inc.computed, inc.changeSet)
            val folded = engine.captureGeometry()
            assertNotNull(folded)

            val inv = engine.lastGeometryInvalidation()
            assertNotNull(inv)
            assertTrue(EventId("hpA") in inv.staleAttachments, "hairpin A reshapes around the edit")
            assertTrue(EventId("hpB") in inv.reusableAttachments, "hairpin B follows its anchors")

            // The untouched hairpin B is reused by reference — no recompute, no drift.
            assertSame(seeded.attachments[EventId("hpB")], folded.attachments[EventId("hpB")])
        }
    }
}
