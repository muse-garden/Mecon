package com.mecon.features.scoreediting

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.ArticulationGeometry
import com.mecon.api.storage.MarkOffset
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.TieGeometry
import com.mecon.api.storage.TupletGeometry
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreEditingSessionTest {
    @Test
    fun chordInsertionIsOneAtomicRevisionAndWireRoundTrip() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val intent = ScoreEditIntent.InsertChord(
            expectedRevision = 0,
            voiceTrackId = voiceId,
            start = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER,
            pitches = listOf(Pitch.C4, Pitch.E4, Pitch.G4),
        )
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val inserted = session.dispatch(intent)
        assertEquals(1, inserted.frame.revision)
        assertTrue(inserted.frame.canUndo)
        val eventId = assertNotNull(inserted.frame.selection.single().eventIdOrNull)
        val stored = inserted.toWireUpdate().score
        val voice = stored.voiceTracks.getValue(voiceId)
        val event = voice.events.single { it.id == eventId }
        assertEquals(
            listOf(Pitch.C4, Pitch.E4, Pitch.G4),
            stored.pitchTracks.getValue(voice.pitchTrackId).events
                .single { it.id == event.pitchEventId }.pitches,
        )
    }

    @Test
    fun crossMeasureInsertionSplitsInsideOneAtomicRevision() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val inserted = session.dispatch(
            ScoreEditIntent.InsertNote(
                0, voiceId, TimeCode.of(1, Fraction(3, 4)), Duration.HALF, Pitch.C4,
            ),
        )
        val stored = inserted.toWireUpdate().score
        val voice = RuntimeScore.fromStorage(stored).voiceTracks.getValue(voiceId)
        val notes = voice.events.toList().filterNot { it.isRest }
        assertEquals(1, inserted.frame.revision)
        assertEquals(listOf(1, 2), notes.map { it.onset.measure })
        assertTrue(notes.first().ties.isNotEmpty())
        assertTrue(notes.last().ties.isEmpty())
        assertTrue(inserted.frame.canUndo)
        assertTrue(session.dispatch(ScoreEditIntent.Undo(1)).toWireUpdate().score.voiceTracks.getValue(voiceId).events.isEmpty())
    }

    @Test
    fun tieCurveGeometryIsSharedSerializableAndUndoable() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val first = session.dispatch(
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4),
        )
        val firstId = assertNotNull(first.frame.selection.single().eventIdOrNull)
        session.dispatch(
            ScoreEditIntent.InsertNote(1, voiceId, TimeCode.of(1, Fraction.QUARTER), Duration.QUARTER, Pitch.C4),
        )
        session.dispatch(
            ScoreEditIntent.SetTies(
                2,
                listOf(ScoreEditIntent.TieTarget(voiceId, firstId, tieOut = true, pitchIndices = setOf(0))),
            ),
        )
        val geometry = TieGeometry(
            sourcePitchIndex = 0,
            targetPitchIndex = 0,
            startDx = 0.25f,
            startDy = -0.5f,
            endDx = -0.25f,
            endDy = -0.5f,
            above = true,
            minApex = 1.25f,
            maxApex = 2.75f,
            directionOnly = false,
        )
        val intent = ScoreEditIntent.SetTieGeometry(3, firstId, geometry)
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val shaped = session.dispatch(intent)
        val stored = shaped.toWireUpdate().score.geometry!!.ties.getValue(firstId).single()
        assertEquals(4, shaped.frame.revision)
        assertEquals(
            0,
            assertIs<ScoreSelectionTarget.Tie>(shaped.frame.selection.single()).sourcePitchIndex,
        )
        assertTrue(stored.directionLocked)
        assertTrue(stored.manuallyAdjusted)

        val undone = session.dispatch(ScoreEditIntent.Undo(4))
        assertTrue(undone.toWireUpdate().score.geometry?.ties?.get(firstId).isNullOrEmpty())
    }

    @Test
    fun tupletSideGeometryIsSharedSerializableAndUndoable() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val created = session.dispatch(ScoreEditIntent.CreateTupletRegion(
            expectedRevision = 0,
            voiceTrackId = voiceId,
            start = TimeCode.of(1, Fraction.ZERO),
            totalDuration = Duration.QUARTER,
            count = 3,
        ))
        val startId = created.toWireUpdate().score.voiceTracks.getValue(voiceId).events
            .single { it.tupletSpan != null }.id
        val intent = ScoreEditIntent.SetTupletGeometry(
            expectedRevision = 1,
            startEventId = startId,
            geometry = TupletGeometry(above = false),
        )
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val placed = session.dispatch(intent)
        assertEquals(2, placed.frame.revision)
        assertEquals(
            TupletGeometry(above = false, directionLocked = true),
            placed.toWireUpdate().score.geometry?.tuplets?.get(startId),
        )
        assertFalse(placed.frame.renderHint?.changeSet?.structureReflow == true)

        val undone = session.dispatch(ScoreEditIntent.Undo(2))
        assertTrue(undone.toWireUpdate().score.geometry?.tuplets?.get(startId) == null)
    }

    @Test
    fun beamGeometryIsSharedSerializableAndIncremental() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        session.dispatch(
            ScoreEditIntent.InsertNote(
                0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.EIGHTH, Pitch.C4,
                beaming = BeamingInfo.start(),
            ),
        )
        val second = session.dispatch(
            ScoreEditIntent.InsertNote(
                1, voiceId, TimeCode.of(1, Fraction.EIGHTH), Duration.EIGHTH, Pitch.D4,
                beaming = BeamingInfo.end(),
            ),
        )
        val before = second.toWireUpdate().score
        val groupId = computeScore(RuntimeScore.fromStorage(before)).getBeamGroups().keys.single().value
        val intent = ScoreEditIntent.SetBeamGeometry(2, groupId, BeamGeometry(startDy = 1f, endDy = 2f))
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val moved = session.dispatch(intent)
        assertEquals(
            groupId,
            assertIs<ScoreSelectionTarget.Beam>(moved.frame.selection.single()).groupId,
        )
        assertTrue(moved.toWireUpdate().score.geometry!!.beams.getValue(groupId).manuallyAdjusted)
        assertFalse(moved.frame.renderHint?.changeSet?.structureReflow == true)

        val undone = session.dispatch(ScoreEditIntent.Undo(3))
        assertTrue(undone.toWireUpdate().score.geometry?.beams?.get(groupId) == null)
    }

    @Test
    fun articulationGeometryTargetsOneStableMarkIndex() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val inserted = session.dispatch(
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4),
        )
        val eventId = assertNotNull(inserted.frame.selection.single().eventIdOrNull)
        session.dispatch(
            ScoreEditIntent.ToggleArticulation(
                1, listOf(ScoreEditIntent.EventTarget(voiceId, eventId)), com.mecon.api.storage.Articulation.STACCATO,
            ),
        )
        val geometry = ArticulationGeometry(listOf(MarkOffset(0, above = true, dx = 1f, dy = -2f)))
        val intent = ScoreEditIntent.SetArticulationGeometry(2, eventId, geometry, selectedIndex = 0)
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val moved = session.dispatch(intent)
        assertEquals(
            0,
            assertIs<ScoreSelectionTarget.Articulation>(moved.frame.selection.single())
                .articulationIndex,
        )
        assertEquals(geometry, moved.toWireUpdate().score.geometry!!.articulations[eventId])
        assertFalse(moved.frame.renderHint?.changeSet?.structureReflow == true)
    }

    @Test
    fun commonIntentTracePreservesRevisionHistoryAndSelection() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)

        val inserted = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
        )
        assertEquals(1, inserted.frame.revision)
        assertEquals(ScoreEditEffectKind.APPLIED, inserted.effect.kind)
        assertTrue(inserted.frame.canUndo)
        assertFalse(inserted.frame.canRedo)
        val target = assertIs<ScoreSelectionTarget.Event>(inserted.frame.selection.single())
        val targetId = target.eventId
        assertEquals(voiceId, target.voiceTrackId)
        assertEquals(Pitch.C4, inserted.toWireUpdate().score.pitchOf(targetId))

        val transposed = session.dispatch(
            ScoreEditIntent.TransposeNotes(
                expectedRevision = 1,
                targets = listOf(
                    ScoreEditIntent.EventTarget(voiceId, targetId),
                ),
                stepDelta = 1,
            ),
        )
        assertEquals(2, transposed.frame.revision)
        assertEquals(Pitch.D4, transposed.toWireUpdate().score.pitchOf(targetId))

        val stale = session.dispatch(
            ScoreEditIntent.DeleteNotes(
                expectedRevision = 1,
                targets = listOf(ScoreEditIntent.EventTarget(voiceId, targetId)),
            ),
        )
        assertEquals(ScoreEditEffectKind.STALE_REVISION, stale.effect.kind)
        assertEquals(2, stale.frame.revision)
        assertEquals(Pitch.D4, stale.toWireUpdate().score.pitchOf(targetId))

        val undone = session.dispatch(ScoreEditIntent.Undo(expectedRevision = 2))
        assertEquals(3, undone.frame.revision)
        assertEquals(ScoreEditEffectKind.UNDONE, undone.effect.kind)
        assertEquals(Pitch.C4, undone.toWireUpdate().score.pitchOf(targetId))
        assertEquals(targetId, undone.frame.selection.single().eventIdOrNull)
        assertTrue(undone.frame.canRedo)

        val redone = session.dispatch(ScoreEditIntent.Redo(expectedRevision = 3))
        assertEquals(4, redone.frame.revision)
        assertEquals(Pitch.D4, redone.toWireUpdate().score.pitchOf(targetId))
        assertFalse(redone.frame.canRedo)
    }

    @Test
    fun propertyEditAndWireCodecRoundTrip() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val insertion = ScoreEditIntent.InsertNote(
            expectedRevision = 0,
            voiceTrackId = voiceId,
            start = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER,
            pitch = Pitch.E4,
        )
        assertEquals(insertion, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(insertion)))

        val inserted = session.dispatch(insertion)
        val eventId = assertNotNull(inserted.frame.selection.single().eventIdOrNull)
        val durationEdit = ScoreEditIntent.SetDurations(
            expectedRevision = 1,
            targets = listOf(ScoreEditIntent.DurationTarget(voiceId, eventId, Duration.HALF)),
        )
        val changed = session.dispatch(
            ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(durationEdit)),
        )

        val update = ScoreEditCodec.decodeUpdate(ScoreEditCodec.encodeUpdate(changed.toWireUpdate()))
        assertEquals(2, update.revision)
        assertEquals(1, update.baseRevision)
        assertEquals(ScoreEditEffectKind.APPLIED, update.effect.kind)
        val replacementId = assertNotNull(update.selection.single().eventIdOrNull)
        assertEquals(Duration.HALF, update.score.voiceEvent(replacementId).duration)
    }

    @Test
    fun tupletInsertionReturnsOneSharedContinuationForEveryPlatform() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)

        val first = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )
        val transition = assertNotNull(first.noteInputTransition)
        assertEquals(Duration.EIGHTH, transition.duration)
        assertNull(transition.tupletCount)
        assertEquals(transition, first.toWireUpdate().noteInputTransition)

        val second = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 1,
                voiceTrackId = voiceId,
                start = assertNotNull(first.frame.nextInputPosition),
                duration = transition.duration,
                pitch = Pitch.D4,
            ),
        )
        assertNull(second.noteInputTransition)
        val pitched = second.frame.runtimeScore.getVoiceTrack(voiceId)?.events?.toList()
            .orEmpty().filterNot { it.isRest }
        assertEquals(2, pitched.size)
        assertEquals(1, pitched.count { it.tupletSpan?.count == 3 })
        assertTrue(pitched.all { it.duration.tuplet?.actual == 3 })

        val stale = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.E4,
                tupletCount = 3,
            ),
        )
        assertNull(stale.noteInputTransition)
        assertNull(stale.toWireUpdate().noteInputTransition)
    }

    @Test
    fun emptyTupletRegionIsCreatedBeforeMobileNoteEntry() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val start = TimeCode.of(1, Fraction.ZERO)
        val intent = ScoreEditIntent.CreateTupletRegion(
            expectedRevision = 0,
            voiceTrackId = voiceId,
            start = start,
            totalDuration = Duration.QUARTER,
            count = 3,
        )
        assertEquals(intent, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(intent)))

        val created = session.dispatch(intent)
        assertEquals(start, created.frame.nextInputPosition)
        assertEquals(Duration.EIGHTH, assertNotNull(created.noteInputTransition).duration)
        val placeholders = created.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.toList()
            .filter { it.duration.tuplet?.actual == 3 }
        assertEquals(3, placeholders.size)
        assertTrue(placeholders.all { it.isRest && it.duration.tuplet?.actual == 3 })
        assertEquals(3, placeholders.first().tupletSpan?.count)

        val entered = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 1,
                voiceTrackId = voiceId,
                start = start,
                duration = Duration.EIGHTH,
                pitch = Pitch.C4,
            ),
        )
        val events = entered.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.toList()
            .filter { it.duration.tuplet?.actual == 3 }
        assertEquals(3, events.size)
        assertFalse(events.first().isRest)
        assertEquals(TimeCode.of(1, Fraction(1, 12)), entered.frame.nextInputPosition)
    }

    @Test
    fun smallNoteRegionReturnsStableAppendAnchorAndSwitchesToEntry() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val first = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.EIGHTH,
                pitch = null,
                isRest = true,
            ),
        )
        val firstId = assertNotNull(first.frame.selection.single().eventIdOrNull)
        val second = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 1,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.EIGHTH),
                duration = Duration.EIGHTH,
                pitch = null,
                isRest = true,
            ),
        )
        val secondId = assertNotNull(second.frame.selection.single().eventIdOrNull)

        val grouped = session.dispatch(
            ScoreEditIntent.CreateSmallNoteRegions(
                expectedRevision = 2,
                targets = listOf(
                    ScoreEditIntent.EventGroupTarget(voiceId, setOf(firstId, secondId)),
                ),
            ),
        )
        val transition = assertNotNull(grouped.noteInputTransition)
        val appendId = assertNotNull(transition.smallNoteAppendStartEventId)
        assertEquals(TimeCode.of(1, Fraction.ZERO), grouped.frame.nextInputPosition)
        assertEquals(appendId, grouped.frame.selection.first().eventIdOrNull)

        val appended = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 3,
                voiceTrackId = voiceId,
                start = assertNotNull(grouped.frame.nextInputPosition),
                duration = transition.duration,
                pitch = Pitch.D4,
                smallNoteAppendStartEventId = appendId,
            ),
        )
        val entered = appended.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.toList()
            .filterNot { it.isRest }
        assertEquals(1, entered.size)
        assertTrue(entered.single().tupletSpan?.smallNotes == true)
    }

    /**
     * Remote clients reuse their previous layout whenever `scoreChanged` is false, so an update that
     * claims nothing changed while the score actually moved would silently render a stale document.
     */
    @Test
    fun scoreChangedIsTrueExactlyWhenTheStoredScoreDiffers() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        var previous = session.initialUpdate().score

        fun check(label: String, intent: ScoreEditIntent) {
            val update = session.dispatch(intent).toWireUpdate()
            assertEquals(
                previous != update.score,
                update.scoreChanged,
                "$label reported scoreChanged=${update.scoreChanged}",
            )
            previous = update.score
        }

        check(
            "insert",
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4),
        )
        val eventId = assertNotNull(session.frame().selection.single().eventIdOrNull)
        check("selection", ScoreEditIntent.SetSelection(1, session.frame().selection))
        check("copy", ScoreEditIntent.CopyNotes(2, listOf(ScoreEditIntent.CopyTarget(voiceId, eventId))))
        check(
            "stale",
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(2, Fraction.ZERO), Duration.QUARTER, Pitch.D4),
        )
        check(
            "no-op delete",
            ScoreEditIntent.DeleteNotes(3, listOf(ScoreEditIntent.EventTarget(voiceId, EventId("missing")))),
        )
        check("paste", ScoreEditIntent.PasteNotes(3, voiceId, TimeCode.of(2, Fraction.ZERO)))
        check("undo", ScoreEditIntent.Undo(4))
        check("redo", ScoreEditIntent.Redo(5))
    }

    @Test
    fun copyPasteAndCutShareClipboardWithoutAddingCopyToUndoHistory() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val inserted = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
        )
        val sourceId = assertNotNull(inserted.frame.selection.single().eventIdOrNull)
        val copy = ScoreEditIntent.CopyNotes(
            expectedRevision = 1,
            targets = listOf(ScoreEditIntent.CopyTarget(voiceId, sourceId)),
        )
        assertEquals(copy, ScoreEditCodec.decodeIntent(ScoreEditCodec.encodeIntent(copy)))

        val copied = session.dispatch(copy)
        assertEquals(2, copied.frame.revision)
        assertEquals(ScoreEditEffectKind.COPIED, copied.effect.kind)
        assertTrue(copied.frame.canPaste)
        assertTrue(copied.frame.canUndo)

        val pasted = session.dispatch(
            ScoreEditIntent.PasteNotes(
                expectedRevision = 2,
                voiceTrackId = voiceId,
                start = TimeCode.of(2, Fraction.ZERO),
            ),
        )
        assertEquals(3, pasted.frame.revision)
        assertEquals(ScoreEditEffectKind.PASTED, pasted.effect.kind)
        assertEquals(Pitch.C4, pasted.toWireUpdate().score.pitchOf(assertNotNull(pasted.frame.selection.single().eventIdOrNull)))

        val cut = session.dispatch(
            ScoreEditIntent.CutNotes(
                expectedRevision = 3,
                targets = listOf(ScoreEditIntent.CopyTarget(voiceId, sourceId)),
            ),
        )
        assertEquals(4, cut.frame.revision)
        assertEquals(ScoreEditEffectKind.CUT, cut.effect.kind)
        assertTrue(cut.frame.canPaste)
        assertTrue(cut.toWireUpdate().score.isRest(assertNotNull(cut.frame.selection.single().eventIdOrNull)))

        val undone = session.dispatch(ScoreEditIntent.Undo(expectedRevision = 4))
        assertEquals(5, undone.frame.revision)
        assertEquals(Pitch.C4, undone.toWireUpdate().score.pitchOf(sourceId))
        assertTrue(undone.frame.canPaste)
    }

    @Test
    fun moveVoiceAcrossStaffUsesOneSharedIntentAndPreservesSelection() {
        val score = StorageScore.create(
            StorageScore.CreationOptions(
                layout = StaffLayoutPreset.PIANO_GRAND,
                measureCount = 1,
            ),
        )
        val runtime = RuntimeScore.fromStorage(score)
        val (upper, lower) = runtime.orderedStaffs()
        val upperVoice = upper.voiceTracks.single()
        val lowerVoice = lower.voiceTracks.single()
        val session = ScoreEditingSession.open(score)
        val inserted = session.dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = 0,
                voiceTrackId = upperVoice.id,
                start = TimeCode.ofMeasure(1),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
        )
        val sourceId = assertNotNull(inserted.frame.selection.single().eventIdOrNull)
        val moved = session.dispatch(
            ScoreEditIntent.MoveVoices(
                expectedRevision = 1,
                targets = listOf(
                    ScoreEditIntent.VoiceMoveTarget(
                        voiceTrackId = upperVoice.id,
                        eventId = sourceId,
                        targetVoiceNumber = lowerVoice.voiceNumber,
                        targetStaffId = lower.id,
                    ),
                ),
            ),
        )

        assertEquals(2, moved.frame.revision)
        assertEquals(ScoreEditEffectKind.APPLIED, moved.effect.kind)
        assertEquals(lowerVoice.id, moved.frame.selection.single().voiceTrackIdOrNull)
        val movedRuntime = RuntimeScore.fromStorage(moved.toWireUpdate().score)
        assertTrue(movedRuntime.getVoiceTrack(upperVoice.id)?.events?.toList().orEmpty().none { !it.isRest })
        assertEquals(
            listOf(Pitch.C4),
            movedRuntime.getVoiceTrack(lowerVoice.id)?.events?.toList().orEmpty()
                .filterNot { it.isRest }
                .flatMap { it.pitches },
        )

        val undone = session.dispatch(ScoreEditIntent.Undo(expectedRevision = 2))
        assertEquals(Pitch.C4, undone.toWireUpdate().score.pitchOf(sourceId))
    }

    @Test
    fun structuralIntentTraceSetsSignaturesAndEditsMeasuresWithReflowHints() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val staffId = score.staffTracks.keys.single()
        val session = ScoreEditingSession.open(score)

        val clef = session.dispatch(
            ScoreEditIntent.SetClef(0, staffId, TimeCode.ZERO, Clef.BASS),
        )
        assertIs<ScoreSelectionTarget.Clef>(clef.frame.selection.single())
        assertEquals(Clef.BASS, clef.toWireUpdate().score.staffTracks.getValue(staffId).clef)
        assertTrue(clef.frame.renderHint?.changeSet?.structureReflow == true)

        val key = session.dispatch(
            ScoreEditIntent.SetKeySignature(1, TimeCode.ZERO, KeySignature.D_MAJOR),
        )
        assertIs<ScoreSelectionTarget.KeySignature>(key.frame.selection.single())
        assertEquals(KeySignature.D_MAJOR, key.toWireUpdate().score.defaultKeySignature)

        val meter = session.dispatch(
            ScoreEditIntent.SetTimeSignature(2, 1, TimeSignature(3, 4)),
        )
        assertIs<ScoreSelectionTarget.TimeSignature>(meter.frame.selection.single())
        assertEquals(TimeSignature(3, 4), meter.toWireUpdate().score.measures.first().timeSignature)

        val inserted = session.dispatch(
            ScoreEditIntent.InsertMeasures(3, afterMeasure = 1, count = 2),
        )
        assertEquals(4, inserted.toWireUpdate().score.measures.size)
        assertTrue(inserted.frame.selection.isEmpty())

        val deleted = session.dispatch(
            ScoreEditIntent.DeleteMeasures(4, setOf(2, 3)),
        )
        assertEquals(2, deleted.toWireUpdate().score.measures.size)
        assertTrue(deleted.frame.renderHint?.changeSet?.structureReflow == true)

        val undone = session.dispatch(ScoreEditIntent.Undo(5))
        assertEquals(4, undone.toWireUpdate().score.measures.size)
    }

    @Test
    fun repeatStructureIntentTraceEditsBarlinesVoltasAndNavigationMarks() {
        val session = ScoreEditingSession.open(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 5)),
        )

        val repeat = session.dispatch(
            ScoreEditIntent.SetBarline(0, 2, BarlineType.REPEAT_RIGHT),
        )
        assertTrue(repeat.toWireUpdate().score.measures.first { it.number == 2 }.repeatEnd)
        assertIs<ScoreSelectionTarget.Barline>(repeat.frame.selection.single())

        val counted = session.dispatch(ScoreEditIntent.SetBarlineRepeatCount(1, 2, 3))
        assertEquals(3, counted.toWireUpdate().score.measures.first { it.number == 2 }.repeatCount)
        assertFalse(counted.frame.renderHint?.changeSet?.structureReflow ?: true)

        val voltas = session.dispatch(ScoreEditIntent.ToggleVoltaPair(2, boundaryMeasure = 0))
        assertEquals(setOf(1), voltas.toWireUpdate().score.measures.first { it.number == 1 }.voltaNumbers)
        assertEquals(setOf(2), voltas.toWireUpdate().score.measures.first { it.number == 3 }.voltaNumbers)
        assertIs<ScoreSelectionTarget.VoltaEnding>(voltas.frame.selection.single())

        val resized = session.dispatch(ScoreEditIntent.ResizeSecondVolta(3, startMeasure = 3, endMeasure = 4))
        assertEquals(setOf(2), resized.toWireUpdate().score.measures.first { it.number == 4 }.voltaNumbers)

        val navigation = session.dispatch(
            ScoreEditIntent.ToggleNavigationMark(4, boundaryMeasure = 4, mark = NavigationMark.CODA),
        )
        assertIs<ScoreSelectionTarget.NavigationMark>(navigation.frame.selection.single())
        assertTrue(NavigationMark.CODA in navigation.toWireUpdate().score.measures.first { it.number == 4 }.navigationMarks)

        val moved = session.dispatch(
            ScoreEditIntent.MoveNavigationMark(5, 4, 3, NavigationMark.CODA),
        )
        assertTrue(NavigationMark.CODA in moved.toWireUpdate().score.measures.first { it.number == 3 }.navigationMarks)
        assertFalse(NavigationMark.CODA in moved.toWireUpdate().score.measures.first { it.number == 4 }.navigationMarks)

        val deleted = session.dispatch(
            ScoreEditIntent.DeleteNavigationMark(6, 3, NavigationMark.CODA),
        )
        assertTrue(deleted.frame.selection.isEmpty())
        assertFalse(NavigationMark.CODA in deleted.toWireUpdate().score.measures.first { it.number == 3 }.navigationMarks)

        val undone = session.dispatch(ScoreEditIntent.Undo(7))
        assertTrue(NavigationMark.CODA in undone.toWireUpdate().score.measures.first { it.number == 3 }.navigationMarks)
    }

    @Test
    fun slurIntentTraceCreatesEditsAndDeletesOneFirstClassSlur() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val first = session.dispatch(
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4),
        )
        val firstId = assertNotNull(first.frame.selection.single().eventIdOrNull)
        val second = session.dispatch(
            ScoreEditIntent.InsertNote(1, voiceId, TimeCode.of(1, Fraction.QUARTER), Duration.QUARTER, Pitch.D4),
        )
        val secondId = assertNotNull(second.frame.selection.single().eventIdOrNull)

        val added = session.dispatch(
            ScoreEditIntent.AddSlurs(2, listOf(ScoreEditIntent.SlurTarget(voiceId, firstId, secondId))),
        )
        val slurId = assertNotNull(added.frame.selection.single().eventIdOrNull)
        assertIs<ScoreSelectionTarget.Slur>(added.frame.selection.single())
        assertEquals(1, added.toWireUpdate().score.voiceTracks.getValue(voiceId).slurs.size)

        val geometry = SlurGeometry(
            startPitchIndex = 0,
            endPitchIndex = 0,
            startDx = 0.5f,
            startDy = -0.25f,
            endDx = -0.5f,
            endDy = -0.25f,
            above = true,
            minApex = 1.2f,
            maxApex = 3f,
            slopeDamping = 1f,
            middleStraightening = 0f,
            directionOnly = true,
        )
        val shaped = session.dispatch(ScoreEditIntent.SetSlurGeometry(3, slurId, geometry))
        assertTrue(shaped.toWireUpdate().score.geometry!!.slurs.getValue(slurId).directionLocked)
        assertFalse(shaped.toWireUpdate().score.geometry!!.slurs.getValue(slurId).manuallyAdjusted)

        val deleted = session.dispatch(ScoreEditIntent.DeleteSlurs(4, setOf(slurId)))
        assertTrue(deleted.frame.selection.isEmpty())
        assertTrue(deleted.toWireUpdate().score.voiceTracks.getValue(voiceId).slurs.isEmpty())

        val undone = session.dispatch(ScoreEditIntent.Undo(5))
        assertEquals(1, undone.toWireUpdate().score.voiceTracks.getValue(voiceId).slurs.size)
    }

    @Test
    fun expressionIntentTraceAddsPointSpanOctaveAndTempoAttachments() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val staffId = score.staffTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val start = TimeCode.of(1, Fraction.ZERO)
        val end = TimeCode.of(2, Fraction.ZERO)

        val dynamic = session.dispatch(ScoreEditIntent.AddDynamic(0, staffId, start, DynamicLevel.MF))
        val dynamicId = assertNotNull(dynamic.frame.selection.single().eventIdOrNull)
        assertIs<ScoreSelectionTarget.Attachment>(dynamic.frame.selection.single())

        val hairpin = session.dispatch(
            ScoreEditIntent.AddHairpin(1, staffId, start, end, HairpinType.CRESCENDO, HairpinStyle.WEDGE),
        )
        assertEquals(2, hairpin.toWireUpdate().score.staffTracks.getValue(staffId).attachments.size)

        val octave = session.dispatch(
            ScoreEditIntent.AddOctaveShift(2, staffId, start, end, OctaveShiftType.OTTAVA),
        )
        assertEquals(4, octave.toWireUpdate().score.staffTracks.getValue(staffId).attachments.size)

        val tempo = session.dispatch(
            ScoreEditIntent.AddTempoMark(3, end, TempoMarkType.METRONOME, bpm = 96f),
        )
        assertEquals(96f, tempo.toWireUpdate().score.globalTrack.tempoEvents.last().bpm)

        val deleted = session.dispatch(ScoreEditIntent.DeleteExpressions(4, setOf(dynamicId)))
        assertTrue(deleted.frame.selection.isEmpty())
        assertEquals(3, deleted.toWireUpdate().score.staffTracks.getValue(staffId).attachments.size)

        val undone = session.dispatch(ScoreEditIntent.Undo(5))
        assertEquals(4, undone.toWireUpdate().score.staffTracks.getValue(staffId).attachments.size)
    }

    @Test
    fun layoutIntentTraceSetsPageBreakAndStaffVisibilityWithReflow() {
        val score = StorageScore.create(
            StorageScore.CreationOptions(layout = StaffLayoutPreset.PIANO_GRAND, measureCount = 3),
        )
        val lowerStaffId = RuntimeScore.fromStorage(score).orderedStaffs().last().id
        val session = ScoreEditingSession.open(score)

        val page = session.dispatch(ScoreEditIntent.SetLayoutBreak(0, 2, LayoutBreakKind.PAGE))
        assertEquals(
            listOf(2),
            page.toWireUpdate().score.globalTrack.events.filterIsInstance<StoragePageBreak>().map { it.onset.measure },
        )
        assertTrue(page.frame.renderHint?.changeSet?.structureReflow == true)

        val hidden = session.dispatch(
            ScoreEditIntent.SetStaffVisibility(1, setOf(lowerStaffId), 1, 2, hidden = true),
        )
        assertIs<ScoreSelectionTarget.StaffVisibility>(hidden.frame.selection.single())
        assertEquals(1, hidden.toWireUpdate().score.staffTracks.getValue(lowerStaffId).hiddenRanges.size)

        val shown = session.dispatch(
            ScoreEditIntent.SetStaffVisibility(2, setOf(lowerStaffId), 1, 2, hidden = false),
        )
        assertTrue(shown.toWireUpdate().score.staffTracks.getValue(lowerStaffId).hiddenRanges.isEmpty())

        val undone = session.dispatch(ScoreEditIntent.Undo(3))
        assertEquals(1, undone.toWireUpdate().score.staffTracks.getValue(lowerStaffId).hiddenRanges.size)
    }

    @Test
    fun graceIntentTraceInsertsAndEditsAZeroMeterTimeGroup() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val onset = TimeCode.of(1, Fraction.ZERO)
        session.dispatch(ScoreEditIntent.InsertNote(0, voiceId, onset, Duration.QUARTER, Pitch.C4))
        val grace = session.dispatch(
            ScoreEditIntent.InsertNote(
                1,
                voiceId,
                onset,
                Duration.EIGHTH,
                Pitch.D4,
                grace = ScoreEditIntent.GraceInsertion(
                    totalDuration = Duration.EIGHTH,
                    stealFrom = GraceTimeSource.PRINCIPAL,
                    noteType = GraceNoteType.ACCIACCATURA,
                ),
            ),
        )
        val graceId = assertNotNull(grace.frame.selection.single().eventIdOrNull)
        assertNotNull(grace.toWireUpdate().score.voiceEvent(graceId).graceInfo)

        val edited = session.dispatch(
            ScoreEditIntent.SetGraceGroups(
                2,
                listOf(
                    ScoreEditIntent.GraceGroupTarget(
                        voiceId,
                        graceId,
                        Duration.SIXTEENTH,
                        GraceTimeSource.PREVIOUS,
                    ),
                ),
            ),
        )
        val info = assertNotNull(edited.toWireUpdate().score.voiceEvent(graceId).graceInfo)
        assertEquals(Duration.SIXTEENTH, info.totalDuration)
        assertEquals(GraceTimeSource.PREVIOUS, info.stealFrom)
    }

    @Test
    fun inspectorIntentTraceUpdatesNoteAttachmentTempoAndPerformanceProperties() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val staffId = score.staffTracks.keys.single()
        val session = ScoreEditingSession.open(score)
        val note = session.dispatch(
            ScoreEditIntent.InsertNote(0, voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4),
        )
        val noteId = assertNotNull(note.frame.selection.single().eventIdOrNull)

        val arpeggio = session.dispatch(
            ScoreEditIntent.SetArpeggio(
                1,
                listOf(ScoreEditIntent.EventTarget(voiceId, noteId)),
                ArpeggioType.UP,
            ),
        )
        assertEquals(ArpeggioType.UP, arpeggio.toWireUpdate().score.voiceEvent(noteId).rendering?.arpeggio)

        val ornament = session.dispatch(
            ScoreEditIntent.AddOrnament(2, staffId, noteId, OrnamentKind.TRILL),
        )
        val ornamentId = assertNotNull(ornament.frame.selection.single().eventIdOrNull)
        val updatedOrnament = session.dispatch(
            ScoreEditIntent.UpdateOrnament(3, ornamentId, oscillations = 7),
        )
        assertEquals(
            7,
            updatedOrnament.toWireUpdate().score.staffTracks.getValue(staffId).attachments
                .filterIsInstance<StorageOrnamentMark>().single().oscillations,
        )

        val tempo = session.dispatch(
            ScoreEditIntent.AddTempoMark(
                4,
                TimeCode.of(1, Fraction(1, 4)),
                TempoMarkType.METRONOME,
                bpm = 96f,
            ),
        )
        val tempoId = assertNotNull(tempo.frame.selection.single().eventIdOrNull)
        val updatedTempo = session.dispatch(
            ScoreEditIntent.UpdateTempo(5, tempoId, effectiveBpm = 108f, displayStyle = TempoDisplayStyle.HIDDEN),
        )
        val storedTempo = updatedTempo.toWireUpdate().score.globalTrack.tempoEvents.first { it.id == tempoId }
        assertEquals(108f, storedTempo.bpm)
        assertEquals(TempoDisplayStyle.HIDDEN, storedTempo.displayStyle)

        val rest = session.dispatch(
            ScoreEditIntent.InsertNote(
                6,
                voiceId,
                TimeCode.of(1, Fraction(1, 2)),
                Duration.QUARTER,
                isRest = true,
            ),
        )
        val restId = assertNotNull(rest.frame.selection.single().eventIdOrNull)
        val movedRest = session.dispatch(
            ScoreEditIntent.MoveRests(
                7,
                listOf(ScoreEditIntent.RestPositionTarget(voiceId, restId, staffPosition = 2)),
            ),
        )
        assertEquals(2, movedRest.toWireUpdate().score.voiceEvent(restId).rendering?.restStaffPosition)

        val fermata = session.dispatch(
            ScoreEditIntent.AddFermata(8, TimeCode.of(1, Fraction(3, 4))),
        )
        assertIs<ScoreSelectionTarget.Attachment>(fermata.frame.selection.single())
        val fermataId = assertNotNull(fermata.frame.selection.single().eventIdOrNull)
        val performance = session.dispatch(
            ScoreEditIntent.UpdatePerformanceMark(9, fermataId, Fraction(3, 2)),
        )
        assertEquals(
            Fraction(3, 2),
            performance.toWireUpdate().score.globalTrack.events
                .filterIsInstance<StorageFermata>().single().extension,
        )
    }

    private fun StorageScore.voiceEvent(eventId: com.mecon.api.primitive.EventId): StorageVoiceEvent =
        voiceTracks.values.asSequence()
            .flatMap { it.events.asSequence() }
            .firstOrNull { it.id == eventId }
            .let(::assertNotNull)

    private fun StorageScore.pitchOf(eventId: com.mecon.api.primitive.EventId): Pitch {
        val voice = voiceTracks.values.first { track -> track.events.any { it.id == eventId } }
        val event = voice.events.first { it.id == eventId }
        return pitchTracks.getValue(voice.pitchTrackId).events
            .first { it.id == event.pitchEventId }
            .pitches.single()
    }

    private fun StorageScore.isRest(eventId: com.mecon.api.primitive.EventId): Boolean {
        val voice = voiceTracks.values.first { track -> track.events.any { it.id == eventId } }
        val event = voice.events.first { it.id == eventId }
        return pitchTracks.getValue(voice.pitchTrackId).events
            .first { it.id == event.pitchEventId }
            .pitches.isEmpty()
    }
}
