package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.Accidental
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.performanceTimingFor
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpressionEditEngineTest {
    private fun scoreWithTwoNotes(): RuntimeScore {
        var score = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(layout = StaffLayoutPreset.TREBLE, measureCount = 2)))
        val staff = score.staffTracks.values.first()
        val voice = staff.voiceTracks.first()
        for ((onset, pitch) in listOf(
            TimeCode.of(1, Fraction.ZERO) to Pitch.fromMidi(60),
            TimeCode.of(1, Fraction(1, 4)) to Pitch.fromMidi(64),
        )) {
            score = assertNotNull(NoteEditEngine.insert(score, NoteEditEngine.Insertion(
                voiceTrackId = voice.id,
                staffTrackId = staff.id,
                voiceNumber = 1,
                start = onset,
                duration = Duration(DurationBase.QUARTER),
                pitch = pitch,
            ))).score
        }
        return score
    }

    @Test
    fun articulationToggleAppliesToEveryTargetAndRemovesAsAGroup() {
        val original = scoreWithTwoNotes()
        val voice = original.voiceTracks.values.first()
        val targets = voice.events.toList().filterNot { it.isRest }.map {
            ExpressionEditEngine.NoteTarget(voice.id, it.id)
        }
        val added = assertNotNull(ExpressionEditEngine.toggleArticulation(original, targets, Articulation.STACCATO))
        assertTrue(added.score.voiceTracks.getValue(voice.id).events.toList()
            .filterNot { it.isRest }.all { Articulation.STACCATO in it.pitchEvent.articulations })
        val removed = assertNotNull(ExpressionEditEngine.toggleArticulation(added.score, targets, Articulation.STACCATO))
        assertTrue(removed.score.voiceTracks.getValue(voice.id).events.toList()
            .filterNot { it.isRest }.all { Articulation.STACCATO !in it.pitchEvent.articulations })
    }

    @Test
    fun pointDynamicsAreStaffWideAndDeduplicatedByTimeAndStaff() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val onset = TimeCode.of(1, Fraction.ZERO)
        val once = assertNotNull(ExpressionEditEngine.addDynamic(original, staff.id, onset, DynamicLevel.MF))
        val twice = assertNotNull(ExpressionEditEngine.addDynamic(once.score, staff.id, onset, DynamicLevel.MF))
        val marks = twice.score.staffTracks.getValue(staff.id).attachments.filterIsInstance<StorageDynamicMark>()
        assertEquals(1, marks.size)
        assertEquals(null, marks.single().voiceNumber)
    }

    @Test
    fun movingDynamicAboveUpdatesAnchorPlacementAndSelectionIdentity() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val added = assertNotNull(ExpressionEditEngine.addDynamic(
            original, staff.id, TimeCode.of(1, Fraction.ZERO), DynamicLevel.MF,
        ))
        val id = added.selectedAttachmentIds.single()
        val newTime = TimeCode.of(1, Fraction(1, 4))

        val moved = assertNotNull(ExpressionEditEngine.moveAttachment(
            added.score, id, newTime, null, AttachmentGeometry(1f, -4f),
        ))
        val mark = moved.score.staffTracks.getValue(staff.id).attachments
            .filterIsInstance<StorageDynamicMark>().single()

        assertEquals(newTime, mark.onset)
        assertEquals(StaffAttachmentPlacement.ABOVE, mark.placement)
        assertEquals(setOf(id), moved.selectedAttachmentIds)
    }

    @Test
    fun movingWholeHairpinAboveReanchorsBothEndsAndPlacement() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val added = assertNotNull(ExpressionEditEngine.addHairpin(
            original, staff.id,
            TimeCode.of(1, Fraction.ZERO), TimeCode.of(1, Fraction(1, 4)),
            HairpinType.CRESCENDO, HairpinStyle.WEDGE,
        ))
        val id = added.selectedAttachmentIds.single()
        val newStart = TimeCode.of(1, Fraction(1, 4))
        val newEnd = TimeCode.of(1, Fraction(1, 2))

        val moved = assertNotNull(ExpressionEditEngine.moveAttachment(
            added.score, id, newStart, newEnd,
            AttachmentGeometry(1f, -4f, 5f, -3f),
        ))
        val hairpin = moved.score.staffTracks.getValue(staff.id).attachments
            .filterIsInstance<StorageHairpin>().single()

        assertEquals(newStart, hairpin.onset)
        assertEquals(newEnd, hairpin.endOnset)
        assertEquals(StaffAttachmentPlacement.ABOVE, hairpin.placement)
        assertTrue(moved.score.geometry?.attachments?.get(id)?.manuallyAdjustedY == true)
    }

    @Test
    fun octaveShiftChangesSoundingPitchButKeepsWrittenPitchInvariant() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val notesBefore = original.voiceTracks.values.first().events.toList().filterNot { it.isRest }
        val start = TimeCode.of(1, Fraction.ZERO)
        val end = TimeCode.of(1, Fraction(1, 2))
        val added = assertNotNull(ExpressionEditEngine.addOctaveShift(
            original, staff.id, start, end, OctaveShiftType.OTTAVA,
        ))
        val notesAfter = added.score.voiceTracks.values.first().events.toList().filterNot { it.isRest }
        assertEquals(notesBefore.map { it.pitches.single().midiNumber + 12 }, notesAfter.map { it.pitches.single().midiNumber })
        val attachments = added.score.staffTracks.getValue(staff.id).attachments
        assertEquals(1, attachments.filterIsInstance<StorageOctaveShiftStart>().size)
        assertEquals(1, attachments.filterIsInstance<StorageOctaveShiftEnd>().size)

        val removed = assertNotNull(ExpressionEditEngine.deleteAttachments(added.score, added.selectedAttachmentIds))
        val notesRestored = removed.score.voiceTracks.values.first().events.toList().filterNot { it.isRest }
        assertEquals(notesBefore.map { it.pitches }, notesRestored.map { it.pitches })
    }

    @Test
    fun clipboardRequiresCompleteOctaveAndClipsHairpinToSelectedRange() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val octave = assertNotNull(ExpressionEditEngine.addOctaveShift(
            original, staff.id, TimeCode.of(1, Fraction.ZERO), TimeCode.of(1, Fraction(1, 2)),
            OctaveShiftType.OTTAVA,
        ))
        val hairpin = assertNotNull(ExpressionEditEngine.addHairpin(
            octave.score, staff.id, TimeCode.of(1, Fraction.ZERO), TimeCode.of(2, Fraction.ZERO),
            HairpinType.CRESCENDO, HairpinStyle.WEDGE,
        ))
        val octaveId = octave.selectedAttachmentIds.single()
        val hairpinId = hairpin.selectedAttachmentIds.single()
        val withoutCompleteOctave = assertNotNull(ExpressionEditEngine.copyAttachments(
            hairpin.score,
            setOf(octaveId, hairpinId),
            clipRanges = mapOf(staff.id to (TimeCode.of(1, Fraction(1, 4)) to TimeCode.of(1, Fraction(3, 4)))),
        ))
        assertTrue(withoutCompleteOctave.items.none { it.octaveType != null })
        assertEquals(Fraction(1, 2), withoutCompleteOctave.items.single().endOffset)

        val complete = assertNotNull(ExpressionEditEngine.copyAttachments(
            hairpin.score, setOf(octaveId), completeOctaveIds = setOf(octaveId),
        ))
        assertEquals(OctaveShiftType.OTTAVA, complete.items.single().octaveType)
    }

    @Test
    fun movingOctaveShiftReanchorsAndTransfersSoundingTransposition() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val added = assertNotNull(ExpressionEditEngine.addOctaveShift(
            original, staff.id, TimeCode.of(1, Fraction.ZERO), TimeCode.of(1, Fraction(1, 4)),
            OctaveShiftType.OTTAVA,
        ))
        val id = added.selectedAttachmentIds.single()
        val moved = assertNotNull(ExpressionEditEngine.moveAttachment(
            added.score, id,
            TimeCode.of(1, Fraction(1, 4)), TimeCode.of(1, Fraction(1, 2)),
            AttachmentGeometry(0f, -4f, 0f, -4f),
        ))
        val notes = moved.score.voiceTracks.values.first().events.toList().filterNot { it.isRest }
        assertEquals(60, notes.first().pitches.single().midiNumber)
        assertEquals(76, notes.last().pitches.single().midiNumber)
        val shift = moved.score.staffTracks.getValue(staff.id).attachments
            .filterIsInstance<StorageOctaveShiftStart>().single()
        assertEquals(TimeCode.of(1, Fraction(1, 4)), shift.onset)
    }

    @Test
    fun fermataIsGlobalAndComputedOntoThePreviousEventOfEveryVoice() {
        var runtime = RuntimeScore.fromStorage(StorageScore.create(
            StorageScore.CreationOptions(layout = StaffLayoutPreset.PIANO_GRAND, measureCount = 1),
        ))
        val targetIds = mutableMapOf<com.mecon.api.primitive.TrackId, com.mecon.api.primitive.EventId>()
        for (staff in runtime.staffTracks.values) {
            val voice = staff.voiceTracks.first()
            val inserted = assertNotNull(NoteEditEngine.insert(runtime, NoteEditEngine.Insertion(
                voiceTrackId = voice.id,
                staffTrackId = staff.id,
                voiceNumber = voice.voiceNumber,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration(DurationBase.QUARTER),
                pitch = Pitch.fromMidi(if (targetIds.isEmpty()) 60 else 48),
            )))
            runtime = inserted.score
            targetIds[voice.id] = assertNotNull(inserted.insertedEventId)
        }

        val extension = Fraction(3, 8)
        val added = assertNotNull(ExpressionEditEngine.addFermata(
            runtime,
            afterTime = TimeCode.of(1, Fraction.QUARTER),
            extension = extension,
        ))
        assertEquals(1, added.score.globalTrack.events.filterIsInstance<StorageFermata>().size)

        val computed = computeScore(added.score)
        for ((voiceId, eventId) in targetIds) {
            assertEquals(extension, computed.computedEvents.getValue(eventId).fermata?.extension)
            assertEquals(extension, added.score.performanceTimingFor(voiceId, eventId).fermataExtension)
        }

        val markId = added.score.globalTrack.events.filterIsInstance<StorageFermata>().single().id
        val updated = assertNotNull(ExpressionEditEngine.updatePerformanceMark(added.score, markId, Fraction.HALF))
        assertEquals(
            Fraction.HALF,
            updated.score.globalTrack.events.filterIsInstance<StorageFermata>().single().extension,
        )
        val deleted = assertNotNull(ExpressionEditEngine.deleteGlobalPerformanceMarks(updated.score, setOf(markId)))
        assertTrue(deleted.score.globalTrack.events.none { it is StorageFermata })
    }

    @Test
    fun localBreathIsClipboardEligibleButGlobalBreathIsNot() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val afterTime = TimeCode.of(1, Fraction(1, 4))
        val local = assertNotNull(ExpressionEditEngine.addBreathMark(
            original,
            staff.id,
            afterTime,
            scope = BreathMarkScope.VOICE,
            pause = Fraction(1, 16),
            voiceNumber = staff.voiceTracks.first().voiceNumber,
        ))
        val localId = local.selectedAttachmentIds.single()
        assertEquals(1, local.score.staffTracks.getValue(staff.id).attachments.filterIsInstance<StorageBreathMark>().size)
        assertNotNull(ExpressionEditEngine.copyAttachments(local.score, setOf(localId)))

        val global = assertNotNull(ExpressionEditEngine.addBreathMark(
            local.score,
            staff.id,
            afterTime,
            scope = BreathMarkScope.GLOBAL,
            pause = Fraction.EIGHTH,
        ))
        val globalId = global.selectedAttachmentIds.single()
        assertEquals(1, global.score.globalTrack.events.filterIsInstance<StorageGlobalBreathMark>().size)
        assertEquals(null, ExpressionEditEngine.copyAttachments(global.score, setOf(globalId)))
    }

    @Test
    fun globalBreathComputedProjectionCanBeDraggedToAnotherBoundary() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val added = assertNotNull(ExpressionEditEngine.addBreathMark(
            original,
            staff.id,
            TimeCode.of(1, Fraction.QUARTER),
            scope = BreathMarkScope.GLOBAL,
        ))
        val projectionId = added.selectedAttachmentIds.single()
        val target = TimeCode.of(1, Fraction.HALF)

        val moved = assertNotNull(ExpressionEditEngine.moveAttachment(
            added.score,
            projectionId,
            target,
            end = null,
            geometry = AttachmentGeometry(startDx = 0f, startDy = -3f),
        ))

        assertEquals(
            target,
            moved.score.globalTrack.events.filterIsInstance<StorageGlobalBreathMark>().single().onset,
        )
        assertEquals(setOf(projectionId), moved.selectedAttachmentIds)
        assertTrue(projectionId in assertNotNull(moved.score.geometry).attachments)
    }

    @Test
    fun ornamentDefaultsFollowTempoAndTurnCanShareTheBetweenNotesTool() {
        val original = scoreWithTwoNotes()
        fun scoreAtTempo(bpm: Float) = RuntimeScore.fromStorage(StorageScore.create(
            StorageScore.CreationOptions(layout = StaffLayoutPreset.TREBLE, measureCount = 1, tempo = bpm),
        ))
        assertEquals(
            Fraction(1, 8),
            ExpressionEditEngine.defaultOrnamentElementDuration(scoreAtTempo(60f), TimeCode.ofMeasure(1)),
        )
        assertEquals(
            Fraction(1, 4),
            ExpressionEditEngine.defaultOrnamentElementDuration(scoreAtTempo(120f), TimeCode.ofMeasure(1)),
        )
        assertEquals(
            Fraction.HALF,
            ExpressionEditEngine.defaultOrnamentElementDuration(scoreAtTempo(200f), TimeCode.ofMeasure(1)),
        )

        val staff = original.staffTracks.values.first()
        val first = original.voiceTracks.values.first().events.toList().first { !it.isRest }
        val added = assertNotNull(ExpressionEditEngine.addOrnament(
            original,
            staff.id,
            first.id,
            OrnamentKind.TURN,
            OrnamentAnchor.BETWEEN_NOTES,
        ))
        val mark = added.score.staffTracks.getValue(staff.id).attachments
            .filterIsInstance<StorageOrnamentMark>().single()
        assertEquals(first.endTime, mark.onset)
        assertEquals(OrnamentAnchor.BETWEEN_NOTES, mark.anchor)
        assertEquals(Fraction(1, 4), mark.elementDuration)
    }

    @Test
    fun ornamentPropertiesProjectToComputedScoreAndArpeggioLivesOnTheNote() {
        val original = scoreWithTwoNotes()
        val staff = original.staffTracks.values.first()
        val voice = original.voiceTracks.values.first()
        val first = voice.events.toList().first { !it.isRest }
        val added = assertNotNull(ExpressionEditEngine.addOrnament(
            original,
            staff.id,
            first.id,
            OrnamentKind.MORDENT_RELEASE,
        ))
        val ornamentId = added.selectedAttachmentIds.single()
        val updated = assertNotNull(ExpressionEditEngine.updateOrnament(
            added.score,
            ornamentId,
            upperAccidental = Accidental.SHARP,
            lowerAccidental = Accidental.FLAT,
            elementDuration = Fraction(1, 8),
            oscillations = 4,
            trillPlaybackMode = TrillPlaybackMode.EXPANDED,
            updateUpperAccidental = true,
            updateLowerAccidental = true,
        ))
        val computed = computeScore(updated.score).staffAttachments
            .filterIsInstance<ComputedOrnamentMark>().single()
        assertEquals(Accidental.SHARP, computed.upperAccidental)
        assertEquals(Accidental.FLAT, computed.lowerAccidental)
        assertEquals(Fraction(1, 8), computed.elementDuration)
        assertEquals(4, computed.oscillations)
        assertEquals(TrillPlaybackMode.EXPANDED, computed.trillPlaybackMode)

        val arpeggiated = assertNotNull(ExpressionEditEngine.setArpeggio(
            updated.score,
            listOf(ExpressionEditEngine.NoteTarget(voice.id, first.id)),
            ArpeggioType.UP,
        ))
        val note = arpeggiated.score.voiceTracks.getValue(voice.id).events.toList()
            .first { it.id == first.id }
        assertEquals(ArpeggioType.UP, note.rendering?.arpeggio)
    }
}
