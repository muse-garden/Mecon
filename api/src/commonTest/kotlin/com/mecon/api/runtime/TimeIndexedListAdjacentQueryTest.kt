package com.mecon.api.runtime

import com.mecon.api.primitive.TimeCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Minimal HasOnset implementation for testing purposes only
private data class E(val name: String, override val onset: TimeCode) : HasOnset

class TimeIndexedListAdjacentQueryTest {

    // Five time codes spanning three measures; t1h/t2h are within measures
    private val t0  = TimeCode.ZERO                       // before measure 1
    private val t1  = TimeCode.ofMeasure(1)
    private val t1h = TimeCode.of(1, 1, 2)               // measure 1, beat ½
    private val t2  = TimeCode.ofMeasure(2)
    private val t2h = TimeCode.of(2, 1, 2)
    private val t3  = TimeCode.ofMeasure(3)
    private val t4  = TimeCode.ofMeasure(4)               // beyond last event

    private val list = TimeIndexedList.of(E("A", t1), E("B", t2), E("C", t3))

    // ─── lastBefore ──────────────────────────────────────────────────────────
    // Strict "<": returns the last event with onset < time

    @Test fun lastBefore_emptyList_returnsNull() {
        assertNull(TimeIndexedList.empty<E>().lastBefore(t2))
    }

    @Test fun lastBefore_queryAtOrBeforeFirst_returnsNull() {
        assertNull(list.lastBefore(t1))   // t1 is not strictly before itself
        assertNull(list.lastBefore(t0))
    }

    @Test fun lastBefore_queryBetweenFirstAndSecond_returnsFirst() {
        assertEquals("A", list.lastBefore(t1h)?.name)
    }

    @Test fun lastBefore_queryAtSecond_returnsFirst() {
        // Strict "<": t2 itself is excluded, so A (at t1) is returned
        assertEquals("A", list.lastBefore(t2)?.name)
    }

    @Test fun lastBefore_queryAfterLast_returnsLast() {
        assertEquals("C", list.lastBefore(t4)?.name)
    }

    // ─── firstAtOrAfter ──────────────────────────────────────────────────────
    // Inclusive ">=": returns the first event with onset >= time

    @Test fun firstAtOrAfter_emptyList_returnsNull() {
        assertNull(TimeIndexedList.empty<E>().firstAtOrAfter(t1))
    }

    @Test fun firstAtOrAfter_queryAtEvent_returnsThatEvent() {
        // Inclusive: querying exactly at t2 returns B
        assertEquals("B", list.firstAtOrAfter(t2)?.name)
    }

    @Test fun firstAtOrAfter_queryBetweenEvents_returnsFollowing() {
        assertEquals("B", list.firstAtOrAfter(t1h)?.name)
    }

    @Test fun firstAtOrAfter_queryAfterLast_returnsNull() {
        assertNull(list.firstAtOrAfter(t4))
    }

    // ─── Boundary contrast: lastBefore vs lastAtOrBefore, firstAtOrAfter vs firstAfter ──

    @Test fun boundaryContrast_atExactEventTime() {
        // At t2: inclusive functions include the event, exclusive ones skip it
        assertEquals("B", list.lastAtOrBefore(t2)?.name)   // inclusive <=
        assertEquals("A", list.lastBefore(t2)?.name)        // exclusive <

        assertEquals("B", list.firstAtOrAfter(t2)?.name)   // inclusive >=
        assertEquals("C", list.firstAfter(t2)?.name)        // exclusive >
    }

    // ─── firstAfter ──────────────────────────────────────────────────────────
    // Strict ">": returns the first event with onset > time

    @Test fun firstAfter_emptyList_returnsNull() {
        assertNull(TimeIndexedList.empty<E>().firstAfter(t2))
    }

    @Test fun firstAfter_queryBeforeFirst_returnsFirst() {
        assertEquals("A", list.firstAfter(t0)?.name)
    }

    @Test fun firstAfter_queryAtFirst_returnsSecond() {
        // t1 itself is not strictly after t1, so B (at t2) is returned
        assertEquals("B", list.firstAfter(t1)?.name)
    }

    @Test fun firstAfter_queryBetweenFirstAndSecond_returnsSecond() {
        assertEquals("B", list.firstAfter(t1h)?.name)
    }

    @Test fun firstAfter_queryAtLast_returnsNull() {
        assertNull(list.firstAfter(t3))
    }

    @Test fun firstAfter_queryAfterLast_returnsNull() {
        assertNull(list.firstAfter(t4))
    }

    @Test fun firstAfter_multipleEventsAtSameOnset_skipsAllAtThatTime() {
        // Two events at t2; firstAfter(t2) must skip both and return C at t3
        val withDup = TimeIndexedList.of(E("B1", t2), E("B2", t2), E("C", t3))
        assertEquals("C", withDup.firstAfter(t2)?.name)
    }

    @Test fun firstAfter_crossMeasure_correctlyIteratesNextMeasure() {
        // Only events in measures 1 and 3; firstAfter(t1) must find C at t3
        // (no event in measure 2, so iteration must continue past empty measure)
        val sparse = TimeIndexedList.of(E("A", t1), E("C", t3))
        assertEquals("C", sparse.firstAfter(t1)?.name)
    }

    // ─── lastAtOrBefore ──────────────────────────────────────────────────────
    // Inclusive "<=": returns the last event with onset <= time

    @Test fun lastAtOrBefore_emptyList_returnsNull() {
        assertNull(TimeIndexedList.empty<E>().lastAtOrBefore(t2))
    }

    @Test fun lastAtOrBefore_queryBeforeFirst_returnsNull() {
        assertNull(list.lastAtOrBefore(t0))
    }

    @Test fun lastAtOrBefore_queryAtFirst_returnsFirst() {
        // Inclusive: t1 <= t1, so A is returned
        assertEquals("A", list.lastAtOrBefore(t1)?.name)
    }

    @Test fun lastAtOrBefore_queryBetweenFirstAndSecond_returnsFirst() {
        assertEquals("A", list.lastAtOrBefore(t1h)?.name)
    }

    @Test fun lastAtOrBefore_queryAtSecond_returnsSecond() {
        assertEquals("B", list.lastAtOrBefore(t2)?.name)
    }

    @Test fun lastAtOrBefore_queryAfterLast_returnsLast() {
        assertEquals("C", list.lastAtOrBefore(t4)?.name)
    }

    @Test fun lastAtOrBefore_multipleEventsAtSameOnset_returnsLastOfGroup() {
        // Two events at t2; lastAtOrBefore(t2) returns the second (last) one
        val withDup = TimeIndexedList.of(E("B1", t2), E("B2", t2))
        assertEquals("B2", withDup.lastAtOrBefore(t2)?.name)
    }

    @Test fun lastAtOrBefore_crossMeasure_fallsThroughToPrefixSumPath() {
        // Events only in measures 1 and 3; query at measure 2 (no events) must
        // fall through to prefixSum + get() path and return A from measure 1
        val sparse = TimeIndexedList.of(E("A", t1), E("C", t3))
        assertEquals("A", sparse.lastAtOrBefore(t2)?.name)
    }

    @Test fun lastAtOrBefore_withinSameMeasure_subBeatPrecision() {
        // Events in measure 2 at beat 0 and beat ½; query at beat ½ returns B2h
        val inMeasure = TimeIndexedList.of(E("B", t2), E("B2h", t2h))
        assertEquals("B2h", inMeasure.lastAtOrBefore(t2h)?.name)
        // Query at beat 0 should not return the beat-½ event
        assertEquals("B", inMeasure.lastAtOrBefore(t2)?.name)
    }
}
