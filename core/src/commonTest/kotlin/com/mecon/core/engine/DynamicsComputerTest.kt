package com.mecon.core.engine

import com.mecon.api.computed.ComputedDynamicMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.storage.tracks.ControllerEventType
import com.mecon.api.storage.tracks.ControllerScope
import com.mecon.api.storage.tracks.StorageControllerEvent
import com.mecon.api.storage.tracks.StorageControllerTrack
import com.mecon.core.serializer.ScoreSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicsComputerTest {

    /** Build a single-staff score with one dynamic mark and one hairpin attached. */
    private fun scoreWithAttachments(): StorageScore {
        val base = StorageScore.create(StorageScore.CreationOptions(title = "Dyn"))
        val staffId = base.staffTracks.keys.first()
        val staff = base.staffTracks.getValue(staffId)

        val dyn = StorageDynamicMark(
            id = EventId("dyn-1"),
            onset = TimeCode.of(1, Fraction.ZERO),
            level = DynamicLevel.MF,
            placement = StaffAttachmentPlacement.BELOW,
            controllerEventId = EventId("ctl-1"),
        )
        val hp = StorageHairpin(
            id = EventId("hp-1"),
            onset = TimeCode.of(1, Fraction(1, 4)),
            endOnset = TimeCode.of(1, Fraction(3, 4)),
            direction = HairpinType.CRESCENDO,
            style = HairpinStyle.WEDGE,
            placement = StaffAttachmentPlacement.ABOVE,
            controllerStartId = EventId("ctl-2"),
            controllerEndId = EventId("ctl-3"),
        )
        val staffWithAttachments = staff.copy(attachments = listOf(dyn, hp))

        val controller = StorageControllerTrack(
            id = TrackId("ct-1"),
            name = "Dynamics Controller",
            scope = ControllerScope(staffIds = listOf(staffId)),
            events = listOf(
                StorageControllerEvent(EventId("ctl-1"), TimeCode.of(1, Fraction.ZERO), ControllerEventType.SET_DYNAMIC, level = DynamicLevel.MF),
                StorageControllerEvent(EventId("ctl-2"), TimeCode.of(1, Fraction(1, 4)), ControllerEventType.RAMP_START, hairpin = HairpinType.CRESCENDO),
                StorageControllerEvent(EventId("ctl-3"), TimeCode.of(1, Fraction(3, 4)), ControllerEventType.RAMP_END),
            ),
        )

        return base
            .copy(staffTracks = base.staffTracks + (staffId to staffWithAttachments))
            .addControllerTrack(controller)
    }

    @Test
    fun computesAttachmentsWithResolvedStaffIndex() {
        val runtime = RuntimeScore.fromStorage(scoreWithAttachments())
        val computed = computeScore(runtime)

        assertEquals(2, computed.staffAttachments.size)

        val mark = computed.staffAttachments.filterIsInstance<ComputedDynamicMark>().single()
        assertEquals(DynamicLevel.MF, mark.level)
        assertEquals(0, mark.staffIndex)
        assertEquals(StaffAttachmentPlacement.BELOW, mark.placement)

        val hairpin = computed.staffAttachments.filterIsInstance<ComputedHairpin>().single()
        assertEquals(HairpinType.CRESCENDO, hairpin.type)
        assertEquals(HairpinStyle.WEDGE, hairpin.style)
        assertEquals(0, hairpin.staffIndex)
        assertEquals(StaffAttachmentPlacement.ABOVE, hairpin.placement)
        assertEquals(TimeCode.of(1, Fraction(3, 4)), hairpin.endTime)
    }

    @Test
    fun emptyScoreHasNoAttachments() {
        val runtime = RuntimeScore.fromStorage(StorageScore.createDemo())
        val computed = computeScore(runtime)
        assertTrue(computed.staffAttachments.isEmpty())
    }

    @Test
    fun attachmentsAndControllerTrackSurviveYamlRoundTrip() {
        val original = scoreWithAttachments()
        val yaml = ScoreSerializer.toYaml(original)
        val restored = ScoreSerializer.fromYaml(yaml)

        val staffId = original.staffTracks.keys.first()
        assertEquals(
            original.staffTracks.getValue(staffId).attachments,
            restored.staffTracks.getValue(staffId).attachments,
        )

        val controller = restored.controllerTracks.values.singleOrNull()
        assertNotNull(controller)
        assertEquals(3, controller.events.size)
        assertEquals(listOf(staffId), controller.scope.staffIds)
        assertEquals(DynamicLevel.MF, controller.findEvent(EventId("ctl-1"))?.level)
        assertEquals(HairpinType.CRESCENDO, controller.findEvent(EventId("ctl-2"))?.hairpin)
    }

    @Test
    fun roundTripPreservesRuntimeAttachments() {
        val runtime = RuntimeScore.fromStorage(scoreWithAttachments())
        val backToStorage = runtime.toStorage()
        val staffId = backToStorage.staffTracks.keys.first()
        assertEquals(2, backToStorage.staffTracks.getValue(staffId).attachments.size)
        assertEquals(1, backToStorage.controllerTracks.size)
    }

    @Test
    fun ornamentAttachmentSurvivesYamlRoundTrip() {
        val original = scoreWithAttachments()
        val staffId = original.staffTracks.keys.first()
        val ornament = StorageOrnamentMark(
            id = EventId("ornament-1"),
            onset = TimeCode.of(1, Fraction.ZERO),
            sourceEventId = EventId("note-1"),
            kind = OrnamentKind.TRILL,
            endOnset = TimeCode.of(1, Fraction.HALF),
            upperAccidental = Accidental.SHARP,
            elementDuration = Fraction(1, 8),
            oscillations = 3,
            trillPlaybackMode = TrillPlaybackMode.CONTROL_FLOW,
        )
        val staff = original.staffTracks.getValue(staffId)
        val withOrnament = original.copy(
            staffTracks = original.staffTracks + (staffId to staff.copy(
                attachments = staff.attachments + ornament,
            )),
        )

        val restored = ScoreSerializer.fromYaml(ScoreSerializer.toYaml(withOrnament))
        assertEquals(
            ornament,
            restored.staffTracks.getValue(staffId).attachments
                .filterIsInstance<StorageOrnamentMark>().single(),
        )
    }

    // ── Octave shift combined with dynamics ──────────────────────────────────

    /** Score with 8va + crescendo hairpin on ABOVE side, 8vb + dynamic mark on BELOW side. */
    private fun scoreWithOctaveShiftsAndDynamics(): StorageScore {
        val base    = StorageScore.create(StorageScore.CreationOptions("Combined"))
        val staffId = base.staffTracks.keys.first()
        val staff   = base.staffTracks.getValue(staffId)

        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val m3 = TimeCode.of(3, Fraction.ZERO)
        val m4 = TimeCode.of(4, Fraction.ZERO)

        val attachments = listOf(
            // ABOVE: 8va bracket spanning M1–M2 (priority 1 — outer)
            StorageOctaveShiftStart(
                id = EventId("osa-1"), onset = m1, shiftType = OctaveShiftType.OTTAVA,
                endEventId = EventId("ose-1"), placement = StaffAttachmentPlacement.ABOVE
            ),
            StorageOctaveShiftEnd(
                id = EventId("ose-1"), onset = m3, placement = StaffAttachmentPlacement.ABOVE
            ),
            // ABOVE: crescendo hairpin M1–M2 (priority 0 — inner)
            StorageHairpin(
                id = EventId("hp-1"), onset = m1, endOnset = m2,
                direction = HairpinType.CRESCENDO, style = HairpinStyle.TEXT_DASHED,
                placement = StaffAttachmentPlacement.ABOVE,
            ),
            // BELOW: 8vb bracket spanning M3–M4 (priority 1 — outer)
            StorageOctaveShiftStart(
                id = EventId("osa-2"), onset = m3, shiftType = OctaveShiftType.OTTAVA_BASSA,
                endEventId = EventId("ose-2"), placement = StaffAttachmentPlacement.BELOW
            ),
            StorageOctaveShiftEnd(
                id = EventId("ose-2"), onset = m4, placement = StaffAttachmentPlacement.BELOW
            ),
            // BELOW: dynamic mark mf at M3 (priority 0 — inner)
            StorageDynamicMark(
                id = EventId("dyn-1"), onset = m3, level = DynamicLevel.MF,
                placement = StaffAttachmentPlacement.BELOW,
            ),
        )
        return base.copy(staffTracks = base.staffTracks + (staffId to staff.copy(attachments = attachments)))
    }

    @Test
    fun octaveShiftAndHairpinBothComputed() {
        val runtime  = RuntimeScore.fromStorage(scoreWithOctaveShiftsAndDynamics())
        val computed = computeScore(runtime)

        assertEquals(4, computed.staffAttachments.size)

        val shifts  = computed.staffAttachments.filterIsInstance<ComputedOctaveShift>()
        val hairpin = computed.staffAttachments.filterIsInstance<ComputedHairpin>()
        val dynMark = computed.staffAttachments.filterIsInstance<ComputedDynamicMark>()

        assertEquals(2, shifts.size)
        assertEquals(1, hairpin.single().let { 1 })
        assertEquals(1, dynMark.size)

        val aboveShift = shifts.single { it.placement == StaffAttachmentPlacement.ABOVE }
        assertEquals(OctaveShiftType.OTTAVA, aboveShift.shiftType)
        assertEquals(StaffAttachmentPlacement.ABOVE, hairpin.single().placement)

        val belowShift = shifts.single { it.placement == StaffAttachmentPlacement.BELOW }
        assertEquals(OctaveShiftType.OTTAVA_BASSA, belowShift.shiftType)
        assertEquals(StaffAttachmentPlacement.BELOW, dynMark.single().placement)
    }

    @Test
    fun octaveShiftAndDynamicsYamlRoundTrip() {
        val original = scoreWithOctaveShiftsAndDynamics()
        val yaml     = ScoreSerializer.toYaml(original)
        val restored = ScoreSerializer.fromYaml(yaml)

        val staffId = original.staffTracks.keys.first()
        assertEquals(
            original.staffTracks.getValue(staffId).attachments,
            restored.staffTracks.getValue(staffId).attachments,
        )
    }
}
