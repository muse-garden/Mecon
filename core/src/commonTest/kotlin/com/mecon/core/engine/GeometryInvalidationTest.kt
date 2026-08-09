package com.mecon.core.engine

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.analyzeGeometryInvalidation
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeStaffGroupMember
import com.mecon.api.computed.ComputedDynamicMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.storage.ArticulationGeometry
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.TieGeometry
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageStaffAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 of persisted slur / articulation geometry (`docs/data_model/incremental-update.md` §10):
 * which overlay entries an edit invalidates (must recompute) vs which merely follow their anchors
 * (reuse by reference). Drives the renderer's incremental capture.
 */
class GeometryInvalidationTest {

    private fun emptyScore(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId(): TrackId = pitchTracks.keys.first()
    private fun RuntimeScore.vtId(): TrackId = voiceTracks.keys.first()
    private fun tc(measure: Int, beatNum: Int = 0, beatDen: Int = 1) =
        TimeCode.of(measure, Fraction(beatNum, beatDen))

    private fun RuntimeScore.addNote(
        idTag: String,
        onset: TimeCode,
        pitch: Pitch?,
        duration: Duration,
        slurStarts: Int = 0,
        slurEnds: Int = 0,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeScore {
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"),
            onset = onset,
            pitches = if (pitch == null) emptyList() else listOf(pitch),
        )
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag),
            onset = onset,
            pitchEvent = pe,
            duration = duration,
            slurStarts = slurStarts,
            slurEnds = slurEnds,
            ties = ties,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.editPitch(eventId: EventId, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == eventId }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), eventId)
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.editDuration(eventId: EventId, duration: Duration): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == eventId }
        return removeVoiceEvent(vtId(), eventId).addVoiceEvent(vtId(), ve.copy(duration = duration))
    }

    /** Placeholder geometry — analysis only inspects keys, never values. */
    private fun art() = ArticulationGeometry(marks = emptyList())
    private fun slurGeom() = SlurGeometry(0, 0, 0f, 0f, 0f, 0f, true, 1f, 2f, 1f, 0f)
    private fun tieGeom(manual: Boolean = false) = TieGeometry(
        sourcePitchIndex = 0,
        targetPitchIndex = 0,
        startDx = 0f,
        startDy = 0f,
        endDx = 0f,
        endDy = 0f,
        above = true,
        minApex = 0.5f,
        maxApex = 1.4f,
        manuallyAdjusted = manual,
    )

    /**
     * Base score: a slur from n1 (m1) to n3 (m2), an intervening note n2 (m1), plus a far
     * note n5 in m4 to keep the measure count fixed across edits. Returns the computed score
     * and an overlay covering the slur + articulations on n1 and n5.
     */
    private fun fixture(): Triple<RuntimeScore, ComputedScore, ScoreGeometry> {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER, slurStarts = 1)
            .addNote("n2", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.E4, Duration.QUARTER, slurEnds = 1)
            .addNote("n5", tc(4, 0), Pitch.G4, Duration.WHOLE)
        val computed = computeScore(base)
        val slurId = computed.slurs.single().slurId
        val overlay = ScoreGeometry(
            articulations = mapOf(EventId("n1") to art(), EventId("n5") to art()),
            slurs = mapOf(slurId to slurGeom()),
        )
        return Triple(base, computed, overlay)
    }

    private fun changeSet(previous: ComputedScore, edited: RuntimeScore, from: TimeCode, to: TimeCode) =
        computeScoreIncremental(previous, edited, TimeRange(from, to))

    @Test
    fun editingSlurEndpointMarksTheSlurStaleAndFarArticulationReusable() {
        val (base, computed, overlay) = fixture()
        val slurId = computed.slurs.single().slurId
        // Edit n1 (the slur's start, also an articulation anchor) within m1.
        val edited = base.editPitch(EventId("n1"), Pitch(0, 1))
        val cs = changeSet(computed, edited, tc(1, 0), tc(1, 1, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(slurId in inv.staleSlurs, "endpoint edit reshapes the slur")
        assertEquals(setOf(EventId("n1")), inv.staleArticulations, "only n1's articulation is stale")
        assertEquals(setOf(EventId("n5")), inv.reusableArticulations, "the far articulation auto-adjusts")
        assertTrue(inv.reusableSlurs.isEmpty())
    }

    @Test
    fun editingNoteInsideSlurSpanReshapesItButLeavesFarOverlayAlone() {
        val (base, computed, overlay) = fixture()
        val slurId = computed.slurs.single().slurId
        // Edit n2 — an *intervening* note (not an endpoint) inside the slur's span.
        val edited = base.editPitch(EventId("n2"), Pitch(11, 1)) // B#4: forces an accidental → width change
        val cs = changeSet(computed, edited, tc(1, 1, 4), tc(1, 2, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(slurId in inv.staleSlurs, "an intervening change reshapes the slur")
        // n2 carries no overlay entry; n1 / n5 are untouched and reuse by reference.
        assertTrue(inv.staleArticulations.isEmpty())
        assertEquals(setOf(EventId("n1"), EventId("n5")), inv.reusableArticulations)
    }

    @Test
    fun editingOutsideTheSpanKeepsTheSlurReusable() {
        val (base, computed, overlay) = fixture()
        val slurId = computed.slurs.single().slurId
        // Edit n5 in m4 — well outside the slur's m1..m2 span.
        val edited = base.editPitch(EventId("n5"), Pitch.A4)
        val cs = changeSet(computed, edited, tc(4, 0), tc(4, 1, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(slurId in inv.reusableSlurs, "an edit outside the span only translates the slur rigidly")
        assertEquals(setOf(EventId("n5")), inv.staleArticulations)
        assertEquals(setOf(EventId("n1")), inv.reusableArticulations)
    }

    @Test
    fun structuralEditForcesFullRecapture() {
        val (base, computed, overlay) = fixture()
        // Append a note past the current end → measure count / final barline move (structureReflow).
        val edited = base.addNote("n9", tc(5, 0), Pitch.C5, Duration.WHOLE)
        val cs = changeSet(computed, edited, tc(5, 0), tc(5, 1, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(inv.full, "structural / notation change can't be patched incrementally")
    }

    @Test
    fun emptyOverlayYieldsEmptyInvalidation() {
        val (base, computed, _) = fixture()
        val edited = base.editPitch(EventId("n1"), Pitch(0, 1))
        val cs = changeSet(computed, edited, tc(1, 0), tc(1, 1, 4))

        val inv = analyzeGeometryInvalidation(ScoreGeometry.EMPTY, cs.computed, cs.changeSet)

        assertTrue(inv.isEmpty)
    }

    @Test
    fun tieInvalidationIsWindowedAndManualShapeSurvivesTargetEdit() {
        val base = emptyScore()
            .addNote(
                "t1", tc(1, 3, 4), Pitch.C4, Duration.QUARTER,
                ties = listOf(RuntimeTieInfo(0, false)),
            )
            .addNote("t2", tc(2, 0), Pitch.C4, Duration.QUARTER)
            .addNote("far", tc(8, 0), Pitch.G4, Duration.WHOLE)
        val computed = computeScore(base)
        val automatic = ScoreGeometry(ties = mapOf(EventId("t1") to listOf(tieGeom())))
        val editedTarget = base.editPitch(EventId("t2"), Pitch.D4)
        val targetChange = changeSet(computed, editedTarget, tc(2, 0), tc(2, 1, 4))

        val automaticInvalidation = analyzeGeometryInvalidation(
            automatic, targetChange.computed, targetChange.changeSet, computed,
        )
        assertEquals(setOf(EventId("t1")), automaticInvalidation.staleTies)

        val manual = automatic.copy(
            ties = mapOf(EventId("t1") to listOf(tieGeom(manual = true))),
        )
        val manualInvalidation = analyzeGeometryInvalidation(
            manual, targetChange.computed, targetChange.changeSet, computed,
        )
        assertTrue(manualInvalidation.staleTies.isEmpty(), "manual tie curvature remains authoritative")

        val farEdit = base.editPitch(EventId("far"), Pitch.A4)
        val farChange = changeSet(computed, farEdit, tc(8, 0), tc(8, 1, 4))
        val farInvalidation = analyzeGeometryInvalidation(
            automatic, farChange.computed, farChange.changeSet, computed,
        )
        assertTrue(farInvalidation.staleTies.isEmpty(), "far edits never enumerate unrelated tie sources")
    }

    @Test
    fun pitchEditKeepsManualBeamGeometryWhenMembershipIsUnchanged() {
        val base = emptyScore()
            .addNote("b1", tc(1, 0), Pitch.C4, Duration.EIGHTH)
            .addNote("b2", tc(1, 1, 8), Pitch.D4, Duration.EIGHTH)
            .addNote("f1", tc(2, 0), Pitch.E4, Duration.EIGHTH)
            .addNote("f2", tc(2, 1, 8), Pitch.F4, Duration.EIGHTH)
        val computed = computeScore(base)
        val touchedGroup = computed.getComputedEvent(EventId("b1"))!!.beamInfo!!.groupId.value
        val farGroup = computed.getComputedEvent(EventId("f1"))!!.beamInfo!!.groupId.value
        val overlay = ScoreGeometry(beams = mapOf(
            touchedGroup to BeamGeometry(0f, 0f, manuallyAdjusted = true),
            farGroup to BeamGeometry(1f, 1f, manuallyAdjusted = true),
        ))
        val edited = base.editPitch(EventId("b1"), Pitch.G4)
        val cs = changeSet(computed, edited, tc(1, 0), tc(1, 1, 8))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet, computed)

        assertTrue(inv.staleBeams.isEmpty())
        assertEquals(setOf(touchedGroup, farGroup), inv.reusableBeams)
    }

    @Test
    fun removedBeamGroupIsDroppedInsteadOfReused() {
        val base = emptyScore()
            .addNote("b1", tc(1, 0), Pitch.C4, Duration.EIGHTH)
            .addNote("b2", tc(1, 1, 8), Pitch.D4, Duration.EIGHTH)
        val computed = computeScore(base)
        val oldGroup = computed.getComputedEvent(EventId("b1"))!!.beamInfo!!.groupId.value
        val overlay = ScoreGeometry(beams = mapOf(
            oldGroup to BeamGeometry(0f, 0f, manuallyAdjusted = true)
        ))
        val edited = base.editDuration(EventId("b1"), Duration.QUARTER)
        val cs = changeSet(computed, edited, tc(1, 0), tc(1, 1, 8))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet, computed)

        assertTrue(inv.staleBeams.isEmpty())
        assertTrue(inv.reusableBeams.isEmpty())
    }

    // ── Staff attachments (hairpin / 8va / dynamics) ─────────────────────────

    private fun RuntimeScore.withAttachments(vararg atts: StorageStaffAttachment): RuntimeScore {
        val sid = staffTracks.keys.first()
        val track = staffTracks.getValue(sid)
        val updated = track.copy(attachments = track.attachments + atts)
        // staffGroups is denormalized: orderedStaffs() (which DynamicsComputer reads) walks the
        // group tree, so the leaf must be replaced too — updating staffTracks alone is invisible.
        fun fixMember(m: RuntimeStaffGroupMember): RuntimeStaffGroupMember = when (m) {
            is RuntimeStaffGroupMember.Staff ->
                if (m.staff.id == sid) RuntimeStaffGroupMember.Staff(updated) else m
            is RuntimeStaffGroupMember.Group ->
                RuntimeStaffGroupMember.Group(m.group.copy(members = m.group.members.map(::fixMember)))
        }
        return copy(
            staffTracks = staffTracks + (sid to updated),
            staffGroups = staffGroups.map { it.copy(members = it.members.map(::fixMember)) },
        )
    }

    /** Placeholder geometry — analysis only inspects keys, never values. */
    private fun attGeom(a: ComputedStaffAttachment): AttachmentGeometry =
        if (a is ComputedDynamicMark) AttachmentGeometry(0f, 0f)
        else AttachmentGeometry(0f, 0f, 0f, 0f)

    /**
     * A crescendo hairpin and an 8va bracket, both ABOVE over m1..m2 (the 8va stacked
     * *outside* the hairpin), plus an `mf` dynamic far away at m4 (BELOW). Notes fill
     * m1..m4 so the measure count stays fixed across edits.
     */
    private fun attachmentFixture(): Triple<RuntimeScore, ComputedScore, ScoreGeometry> {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.E4, Duration.WHOLE)
            .addNote("n4", tc(3, 0), Pitch.F4, Duration.WHOLE)
            .addNote("n5", tc(4, 0), Pitch.G4, Duration.WHOLE)
            .withAttachments(
                StorageHairpin(
                    EventId("hp"), tc(1, 0), tc(2, 0),
                    HairpinType.CRESCENDO, HairpinStyle.WEDGE, StaffAttachmentPlacement.ABOVE,
                ),
                StorageOctaveShiftStart(
                    EventId("osa"), tc(1, 0), OctaveShiftType.OTTAVA,
                    EventId("ose"), StaffAttachmentPlacement.ABOVE,
                ),
                StorageOctaveShiftEnd(EventId("ose"), tc(2, 0), StaffAttachmentPlacement.ABOVE),
                StorageDynamicMark(EventId("dyn"), tc(4, 0), DynamicLevel.MF, StaffAttachmentPlacement.BELOW),
            )
        val computed = computeScore(base)
        val overlay = ScoreGeometry(
            attachments = computed.staffAttachments.associate { it.id to attGeom(it) },
        )
        return Triple(base, computed, overlay)
    }

    private fun ComputedScore.hairpinId() = staffAttachments.filterIsInstance<ComputedHairpin>().single().id
    private fun ComputedScore.octaveId() = staffAttachments.filterIsInstance<ComputedOctaveShift>().single().id
    private fun ComputedScore.dynamicId() = staffAttachments.filterIsInstance<ComputedDynamicMark>().single().id

    @Test
    fun editingInsideAHairpinSpanCascadesToTheOuterOctaveBracket() {
        val (base, computed, overlay) = attachmentFixture()
        // Edit n2 — an intervening note inside the hairpin's m1..m2 span.
        val edited = base.editPitch(EventId("n2"), Pitch(11, 1)) // B#4: accidental → width change
        val cs = changeSet(computed, edited, tc(1, 1, 4), tc(1, 2, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(computed.hairpinId() in inv.staleAttachments, "hairpin reshapes around the edited note")
        assertTrue(
            computed.octaveId() in inv.staleAttachments,
            "the 8va stacked outside the hairpin is bumped with it",
        )
        assertEquals(setOf(computed.dynamicId()), inv.reusableAttachments, "the far dynamic auto-adjusts")
    }

    @Test
    fun editingFarFromTheSpansLeavesThemReusable() {
        val (base, computed, overlay) = attachmentFixture()
        // Edit n5 in m4 — the dynamic's own measure, well outside the m1..m2 spans.
        val edited = base.editPitch(EventId("n5"), Pitch.A4)
        val cs = changeSet(computed, edited, tc(4, 0), tc(4, 1, 4))

        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        assertTrue(computed.dynamicId() in inv.staleAttachments, "the dynamic over the edited note recomputes")
        assertEquals(
            setOf(computed.hairpinId(), computed.octaveId()),
            inv.reusableAttachments,
            "spans far from the edit only translate rigidly",
        )
    }

    @Test
    fun pruningDropsOnlyStaleAttachments() {
        val (base, computed, overlay) = attachmentFixture()
        val edited = base.editPitch(EventId("n2"), Pitch(11, 1))
        val cs = changeSet(computed, edited, tc(1, 1, 4), tc(1, 2, 4))
        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        val pruned = overlay.without(inv.staleArticulations, inv.staleSlurs, inv.staleAttachments)

        assertTrue(computed.hairpinId() !in pruned.attachments, "stale hairpin dropped → auto reshape")
        assertTrue(computed.octaveId() !in pruned.attachments, "cascaded 8va dropped")
        assertTrue(computed.dynamicId() in pruned.attachments, "reusable dynamic kept")
    }

    @Test
    fun pruningDropsOnlyStaleEntries() {
        val (base, computed, overlay) = fixture()
        val slurId = computed.slurs.single().slurId
        val edited = base.editPitch(EventId("n1"), Pitch(0, 1))
        val cs = changeSet(computed, edited, tc(1, 0), tc(1, 1, 4))
        val inv = analyzeGeometryInvalidation(overlay, cs.computed, cs.changeSet)

        val pruned = overlay.without(inv.staleArticulations, inv.staleSlurs)

        assertTrue(slurId !in pruned.slurs, "stale slur dropped → falls back to auto")
        assertTrue(EventId("n1") !in pruned.articulations, "stale articulation dropped")
        assertTrue(EventId("n5") in pruned.articulations, "reusable articulation kept")
        // Reusable entry kept by reference (same value instance).
        assertEquals(overlay.articulations.getValue(EventId("n5")), pruned.articulations.getValue(EventId("n5")))
    }
}
