package com.mecon.api.runtime.tracks

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.storage.events.StoragePluginEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ─── Test doubles ────────────────────────────────────────────────────────────

private data class TestStorageEvent(
    override val id: EventId,
    override val onset: TimeCode
) : StoragePluginEvent()

private data class TestRuntimeEvent(
    override val id: EventId,
    override val onset: TimeCode,
    override val storageEvent: TestStorageEvent
) : RuntimePluginEvent<TestStorageEvent>

private fun ev(label: String, time: TimeCode): TestRuntimeEvent {
    val id = EventId(label)
    return TestRuntimeEvent(id, time, TestStorageEvent(id, time))
}

private fun trackOf(vararg events: TestRuntimeEvent): RuntimePluginTrack<TestStorageEvent> =
    RuntimePluginTrack(
        id = TrackId("test"),
        name = "test",
        type = "test.event",
        events = TimeIndexedList.of(events.toList())
    )

// ─── Tests ───────────────────────────────────────────────────────────────────

class RuntimePluginTrackTest {

    private val t1 = TimeCode.ofMeasure(1)
    private val t2 = TimeCode.ofMeasure(2)
    private val t3 = TimeCode.ofMeasure(3)

    private val eA = ev("A", t1)
    private val eB = ev("B", t2)
    private val eC = ev("C", t3)

    private val track = trackOf(eA, eB, eC)

    @Test fun scoreSerializationPreservesPluginEvents() {
        val storage = RuntimeScore.create().copy(pluginTracks = mapOf(track.id to track)).toStorage()

        assertEquals(listOf("A", "B", "C"), storage.pluginTracks.getValue(track.id).events.map { it.id.value })
    }

    // ─── findEventById ───────────────────────────────────────────────────────

    @Test fun findEventById_knownId_returnsEvent() {
        val found = track.findEventById(EventId("B"))
        assertNotNull(found)
        assertEquals(t2, found.onset)
        assertEquals("B", found.storageEvent.id.value)
    }

    @Test fun findEventById_unknownId_returnsNull() {
        assertNull(track.findEventById(EventId("Z")))
    }

    @Test fun findEventById_emptyTrack_returnsNull() {
        assertNull(trackOf().findEventById(EventId("A")))
    }

    // ─── prevEvent ───────────────────────────────────────────────────────────
    // Strict "<": last event whose onset < time

    @Test fun prevEvent_queryAtFirst_returnsNull() {
        assertNull(track.prevEvent(t1))
    }

    @Test fun prevEvent_queryAtSecond_returnsFirst() {
        val prev = track.prevEvent(t2)
        assertNotNull(prev)
        assertEquals(t1, prev.onset)
    }

    @Test fun prevEvent_queryAfterLast_returnsLast() {
        val prev = track.prevEvent(TimeCode.ofMeasure(4))
        assertNotNull(prev)
        assertEquals(t3, prev.onset)
    }

    @Test fun prevEvent_storageEventTypedCorrectly() {
        // storageEvent is typed as TestStorageEvent (no cast needed via pluginTrackOf)
        val storage: TestStorageEvent? = track.prevEvent(t2)?.storageEvent
        assertEquals("A", storage?.id?.value)
    }

    // ─── nextEvent ───────────────────────────────────────────────────────────
    // Strict ">": first event whose onset > time

    @Test fun nextEvent_queryAtLast_returnsNull() {
        assertNull(track.nextEvent(t3))
    }

    @Test fun nextEvent_queryAtFirst_returnsSecond() {
        val next = track.nextEvent(t1)
        assertNotNull(next)
        assertEquals(t2, next.onset)
    }

    @Test fun nextEvent_queryBeforeFirst_returnsFirst() {
        val next = track.nextEvent(TimeCode.ZERO)
        assertNotNull(next)
        assertEquals(t1, next.onset)
    }

    @Test fun nextEvent_storageEventTypedCorrectly() {
        val storage: TestStorageEvent? = track.nextEvent(t2)?.storageEvent
        assertEquals("C", storage?.id?.value)
    }

    // ─── lastEventAtOrBefore ─────────────────────────────────────────────────
    // Inclusive "<=": last event whose onset <= time

    @Test fun lastEventAtOrBefore_queryBeforeFirst_returnsNull() {
        assertNull(track.lastEventAtOrBefore(TimeCode.ZERO))
    }

    @Test fun lastEventAtOrBefore_queryAtEvent_returnsThatEvent() {
        val result = track.lastEventAtOrBefore(t2)
        assertNotNull(result)
        assertEquals(t2, result.onset)
    }

    @Test fun lastEventAtOrBefore_queryAfterLast_returnsLast() {
        val result = track.lastEventAtOrBefore(TimeCode.ofMeasure(4))
        assertNotNull(result)
        assertEquals(t3, result.onset)
    }

    @Test fun lastEventAtOrBefore_queryBetweenEvents_returnsPreceding() {
        val between = TimeCode.of(1, 1, 2)   // measure 1, beat ½ — between A and B
        val result = track.lastEventAtOrBefore(between)
        assertNotNull(result)
        assertEquals(t1, result.onset)
    }

    @Test fun lastEventAtOrBefore_storageEventTypedCorrectly() {
        val storage: TestStorageEvent? = track.lastEventAtOrBefore(t2)?.storageEvent
        assertEquals("B", storage?.id?.value)
    }
}
