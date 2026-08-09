package com.mecon.plugins.chord

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.StoragePluginTrack
import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for chord event CRUD operations on [StoragePluginTrack].
 *
 * The chord panel keeps one user chord per onset on top of the generic plugin
 * event callbacks. These tests exercise that chord-specific policy in isolation.
 */
class ChordEventCrudTest {

    // ---------------------------------------------------------------------------
    // Helpers — mirror the chord panel's add-or-replace policy
    // ---------------------------------------------------------------------------

    private fun emptyTrack() = StoragePluginTrack.create(StorageChordEvent.TRACK_TYPE)

    private fun chord(
        onset: TimeCode,
        root: Int = 0,
        quality: ChordQuality = ChordQuality.MAJOR,
        bass: Int? = null
    ) = StorageChordEvent.create(onset, root, quality, bass)

    /** Chord-panel policy: remove an existing chord at the same onset, then append. */
    private fun addChord(track: StoragePluginTrack, event: StorageChordEvent): StoragePluginTrack =
        track.copy(events = track.events.filter { it.onset != event.onset } + event)

    /** App.kt onUpdatePluginEvent: swap event by ID, everything else intact. */
    private fun updateChord(
        track: StoragePluginTrack,
        oldId: EventId,
        newEvent: StorageChordEvent
    ): StoragePluginTrack =
        track.copy(events = track.events.map { if (it.id == oldId) newEvent else it })

    /** App.kt onDeletePluginEvent: filter out the event with matching ID. */
    private fun deleteChord(track: StoragePluginTrack, eventId: EventId): StoragePluginTrack =
        track.copy(events = track.events.filter { it.id != eventId })

    // ---------------------------------------------------------------------------
    // Add
    // ---------------------------------------------------------------------------

    @Test
    fun add_toEmptyTrack_createsSingleEvent() {
        val track = addChord(emptyTrack(), chord(TimeCode.ofMeasure(1)))
        assertEquals(1, track.events.size)
    }

    @Test
    fun add_atDistinctOnsets_appendsWithoutReplacing() {
        var track = emptyTrack()
        track = addChord(track, chord(TimeCode.ofMeasure(1)))
        track = addChord(track, chord(TimeCode.ofMeasure(2)))
        track = addChord(track, chord(TimeCode.ofMeasure(3)))
        assertEquals(3, track.events.size)
    }

    @Test
    fun add_atSameOnset_replacesOldEvent() {
        val onset = TimeCode.ofMeasure(1)
        val original    = chord(onset, root = 0, quality = ChordQuality.MAJOR)  // C
        val replacement = chord(onset, root = 2, quality = ChordQuality.MINOR)  // Dm

        var track = addChord(emptyTrack(), original)
        track = addChord(track, replacement)

        assertEquals(1, track.events.size)
        val stored = track.events.single() as StorageChordEvent
        assertEquals(2, stored.root)
        assertEquals(ChordQuality.MINOR, stored.quality)
    }

    @Test
    fun add_atSameOnset_resultUsesNewEventId() {
        val onset = TimeCode.ofMeasure(1)
        val original    = chord(onset, root = 0)
        val replacement = chord(onset, root = 7)  // different call → different EventId.generate()

        var track = addChord(emptyTrack(), original)
        track = addChord(track, replacement)

        val stored = track.events.single() as StorageChordEvent
        assertEquals(replacement.id, stored.id)
        assertNotEquals(original.id, stored.id)
    }

    @Test
    fun add_replacesOnlyTargetOnset_otherEventsUntouched() {
        val t1 = TimeCode.ofMeasure(1)
        val t2 = TimeCode.ofMeasure(2)
        val t3 = TimeCode.ofMeasure(3)
        val c1 = chord(t1, root = 0)
        val c2 = chord(t2, root = 2)
        val c3 = chord(t3, root = 4)
        var track = emptyTrack()
        track = addChord(track, c1)
        track = addChord(track, c2)
        track = addChord(track, c3)

        val replacement = chord(t2, root = 5)  // F replaces the measure-2 chord
        track = addChord(track, replacement)

        assertEquals(3, track.events.size)
        val ids = track.events.map { it.id }.toSet()
        assertTrue(c1.id in ids,          "measure-1 chord must survive")
        assertTrue(c3.id in ids,          "measure-3 chord must survive")
        assertTrue(replacement.id in ids, "replacement chord must be present")
        assertTrue(c2.id !in ids,         "original measure-2 chord must be gone")
    }

    // ---------------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------------

    @Test
    fun update_changesRootAndQuality_preservesId() {
        val onset = TimeCode.ofMeasure(1)
        val original = chord(onset, root = 0, quality = ChordQuality.MAJOR)
        val updated  = original.copy(root = 9, quality = ChordQuality.MINOR7)  // Am7, same id

        var track = addChord(emptyTrack(), original)
        track = updateChord(track, original.id, updated)

        val stored = track.events.single() as StorageChordEvent
        assertEquals(9, stored.root)
        assertEquals(ChordQuality.MINOR7, stored.quality)
        assertEquals(original.id, stored.id)
    }

    @Test
    fun update_preservesOnset() {
        val onset = TimeCode.ofMeasure(2)
        val original = chord(onset, root = 0)
        val updated  = original.copy(root = 4)

        var track = addChord(emptyTrack(), original)
        track = updateChord(track, original.id, updated)

        assertEquals(onset, (track.events.single() as StorageChordEvent).onset)
    }

    @Test
    fun update_withBassNote_storesBass() {
        val onset = TimeCode.ofMeasure(1)
        val original = chord(onset, root = 5, quality = ChordQuality.MAJOR)            // F
        val updated  = original.copy(bass = 9)                                         // F/A

        var track = addChord(emptyTrack(), original)
        track = updateChord(track, original.id, updated)

        val stored = track.events.single() as StorageChordEvent
        assertEquals(9, stored.bass)
    }

    @Test
    fun update_nonExistentId_isNoop() {
        val c = chord(TimeCode.ofMeasure(1), root = 0)
        var track = addChord(emptyTrack(), c)
        val bogus = StorageChordEvent.create(TimeCode.ofMeasure(1), root = 5, quality = ChordQuality.MAJOR)

        track = updateChord(track, bogus.id, bogus)

        assertEquals(1, track.events.size)
        assertEquals(c.root, (track.events.single() as StorageChordEvent).root)
    }

    @Test
    fun update_onlyUpdatesMatchingId_leavesOthersUntouched() {
        val c1 = chord(TimeCode.ofMeasure(1), root = 0)
        val c2 = chord(TimeCode.ofMeasure(2), root = 2)
        val c3 = chord(TimeCode.ofMeasure(3), root = 4)
        var track = emptyTrack()
        track = addChord(track, c1); track = addChord(track, c2); track = addChord(track, c3)

        val c2v2 = c2.copy(quality = ChordQuality.DOMINANT7)
        track = updateChord(track, c2.id, c2v2)

        val events = track.events.associateBy { it.id }
        assertEquals(ChordQuality.MAJOR,     (events[c1.id] as StorageChordEvent).quality)
        assertEquals(ChordQuality.DOMINANT7, (events[c2.id] as StorageChordEvent).quality)
        assertEquals(ChordQuality.MAJOR,     (events[c3.id] as StorageChordEvent).quality)
    }

    // ---------------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------------

    @Test
    fun delete_removesMatchingEvent() {
        val c = chord(TimeCode.ofMeasure(1))
        val track = deleteChord(addChord(emptyTrack(), c), c.id)
        assertEquals(0, track.events.size)
    }

    @Test
    fun delete_nonExistentId_isNoop() {
        val c = chord(TimeCode.ofMeasure(1))
        val track = deleteChord(addChord(emptyTrack(), c), EventId.generate())
        assertEquals(1, track.events.size)
    }

    @Test
    fun delete_removesOnlyTarget_leavesOthers() {
        val c1 = chord(TimeCode.ofMeasure(1), root = 0)
        val c2 = chord(TimeCode.ofMeasure(2), root = 2)
        val c3 = chord(TimeCode.ofMeasure(3), root = 4)
        var track = emptyTrack()
        track = addChord(track, c1); track = addChord(track, c2); track = addChord(track, c3)

        track = deleteChord(track, c2.id)

        assertEquals(2, track.events.size)
        val ids = track.events.map { it.id }
        assertTrue(c1.id in ids)
        assertTrue(c3.id in ids)
        assertTrue(c2.id !in ids)
    }

    @Test
    fun delete_allEvents_leavesEmptyList() {
        val c1 = chord(TimeCode.ofMeasure(1))
        val c2 = chord(TimeCode.ofMeasure(2))
        var track = emptyTrack()
        track = addChord(track, c1); track = addChord(track, c2)

        track = deleteChord(track, c1.id)
        track = deleteChord(track, c2.id)

        assertEquals(0, track.events.size)
    }

    // ---------------------------------------------------------------------------
    // StorageChordEvent validation
    // ---------------------------------------------------------------------------

    @Test
    fun create_validRootBoundaries_succeed() {
        chord(TimeCode.ofMeasure(1), root = 0)   // lower bound
        chord(TimeCode.ofMeasure(1), root = 11)  // upper bound
    }

    @Test
    fun create_rootOutOfRange_throwsIllegalArgumentException() {
        assertFailsWith<IllegalArgumentException> {
            StorageChordEvent(EventId.generate(), TimeCode.ofMeasure(1), root = 12, quality = ChordQuality.MAJOR)
        }
        assertFailsWith<IllegalArgumentException> {
            StorageChordEvent(EventId.generate(), TimeCode.ofMeasure(1), root = -1, quality = ChordQuality.MAJOR)
        }
    }

    @Test
    fun create_bassOutOfRange_throwsIllegalArgumentException() {
        assertFailsWith<IllegalArgumentException> {
            StorageChordEvent(EventId.generate(), TimeCode.ofMeasure(1), root = 0, quality = ChordQuality.MAJOR, bass = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            StorageChordEvent(EventId.generate(), TimeCode.ofMeasure(1), root = 0, quality = ChordQuality.MAJOR, bass = 12)
        }
    }

    @Test
    fun create_nullBass_isValid() {
        val c = chord(TimeCode.ofMeasure(1), root = 0)
        assertEquals(null, c.bass)
    }

    @Test
    fun create_generateUniqueIds() {
        val c1 = chord(TimeCode.ofMeasure(1))
        val c2 = chord(TimeCode.ofMeasure(2))
        assertNotEquals(c1.id, c2.id)
    }
}
