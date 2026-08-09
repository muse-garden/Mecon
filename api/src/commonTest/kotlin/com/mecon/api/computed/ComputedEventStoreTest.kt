package com.mecon.api.computed

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [ComputedEventStore] (docs/data_model/computed-event-store.md §6): persistence
 * (originals never mutate), structural sharing (unaffected events keep their references), dual-index
 * consistency, content equality, and range/measure boundaries.
 */
class ComputedEventStoreTest {

    private fun ev(
        id: String,
        measure: Int,
        beat: Fraction = Fraction.ZERO,
        dur: Duration = Duration.QUARTER,
        hasTie: Boolean = false,
    ) =
        ComputedVoiceEvent(
            id = EventId(id),
            onset = TimeCode.of(measure, beat),
            duration = dur,
            pitchData = if (!hasTie) emptyList() else listOf(
                ComputedPitchData(
                    pitch = Pitch.C4,
                    midiPitch = 60,
                    staffPosition = 0,
                    effectiveAccidental = null,
                    needsLedgerLine = false,
                    tieTarget = ComputedTieTarget.letRing(),
                )
            ),
            measurePosition = MeasurePosition(measure, beat, beat),
            isRest = true,
            beamInfo = null,
        )

    // --- construction / lookup --------------------------------------------------------------

    @Test
    fun ofExposesLookupSizeAndIds() {
        val a = ev("a", 1)
        val b = ev("b", 2)
        val c = ev("c", 3)
        val store = ComputedEventStore.of(listOf(a, b, c))

        assertEquals(3, store.size)
        assertFalse(store.isEmpty())
        assertEquals(a, store[EventId("a")])
        assertEquals(b, store.getValue(EventId("b")))
        assertTrue(EventId("c") in store)
        assertNull(store[EventId("missing")])
        assertFailsWith<NoSuchElementException> { store.getValue(EventId("missing")) }
        assertEquals(listOf(EventId("a"), EventId("b"), EventId("c")), store.ids)
    }

    @Test
    fun emptyStoreHasNoEvents() {
        assertEquals(0, ComputedEventStore.EMPTY.size)
        assertTrue(ComputedEventStore.EMPTY.isEmpty())
        assertNull(ComputedEventStore.EMPTY[EventId("a")])
    }

    @Test
    fun valuesAreOnsetOrderedAcrossAndWithinMeasures() {
        // Insert deliberately out of order, including two onsets in the same measure.
        val m2 = ev("m2", 2)
        val m1a = ev("m1a", 1, Fraction.ZERO)
        val m1b = ev("m1b", 1, Fraction(1, 4))
        val m3 = ev("m3", 3)
        val store = ComputedEventStore.of(listOf(m2, m3, m1b, m1a))

        assertEquals(listOf("m1a", "m1b", "m2", "m3"), store.values.map { it.id.value })
    }

    // --- persistent put / remove ------------------------------------------------------------

    @Test
    fun putInsertsNewWithoutMutatingOriginal() {
        val base = ComputedEventStore.of(listOf(ev("a", 1)))
        val x = ev("x", 2)
        val grown = base.put(x)

        assertEquals(2, grown.size)
        assertEquals(x, grown[EventId("x")])
        // Original untouched.
        assertEquals(1, base.size)
        assertNull(base[EventId("x")])
    }

    @Test
    fun putOverwritesSameIdSameOnset() {
        val base = ComputedEventStore.of(listOf(ev("a", 1, Fraction.ZERO, Duration.QUARTER)))
        val updated = ev("a", 1, Fraction.ZERO, Duration.HALF)
        val next = base.put(updated)

        assertEquals(1, next.size, "overwrite must not grow")
        assertEquals(Duration.HALF, next.getValue(EventId("a")).duration)
        // Original keeps the old value.
        assertEquals(Duration.QUARTER, base.getValue(EventId("a")).duration)
        assertEquals(listOf("a"), next.values.map { it.id.value })
    }

    @Test
    fun putWithChangedOnsetMovesEventAcrossMeasures() {
        val base = ComputedEventStore.of(listOf(ev("a", 1), ev("b", 2)))
        val moved = ev("a", 3)               // same id, new measure
        val next = base.put(moved)

        assertEquals(2, next.size)
        assertEquals(3, next.getValue(EventId("a")).onset.measure)
        // No stale copy left behind in measure 1.
        assertTrue(next.inMeasure(1).isEmpty())
        assertEquals(listOf("a"), next.inMeasure(3).map { it.id.value })
        assertEquals(listOf("b", "a"), next.values.map { it.id.value }, "onset order after move")
    }

    @Test
    fun removeDropsEventAndIsNoOpWhenAbsent() {
        val base = ComputedEventStore.of(listOf(ev("a", 1), ev("b", 2)))
        val removed = base.remove(EventId("a"))
        assertEquals(1, removed.size)
        assertNull(removed[EventId("a")])
        assertEquals(2, base.size, "original untouched")

        val same = base.remove(EventId("nope"))
        assertEquals(base, same)
    }

    @Test
    fun tieSourceIndexTracksPutOverwriteAndRemove() {
        val base = ComputedEventStore.of(listOf(
            ev("plain", 1),
            ev("tied", 2, hasTie = true),
        ))
        assertEquals(setOf(EventId("tied")), base.tieSourceEventIds)

        val added = base.put(ev("new-tie", 3, hasTie = true))
        assertEquals(setOf(EventId("tied"), EventId("new-tie")), added.tieSourceEventIds)

        val cleared = added.put(ev("tied", 2, hasTie = false))
        assertEquals(setOf(EventId("new-tie")), cleared.tieSourceEventIds)

        val removed = cleared.remove(EventId("new-tie"))
        assertTrue(removed.tieSourceEventIds.isEmpty())
        assertEquals(setOf(EventId("tied")), base.tieSourceEventIds, "persistent source remains unchanged")
    }

    // --- index consistency / structural sharing ---------------------------------------------

    @Test
    fun bothIndexesStayConsistent() {
        var store = ComputedEventStore.of(listOf(ev("a", 1), ev("b", 2), ev("c", 2)))
        store = store.put(ev("d", 1)).remove(EventId("b"))
        // For every id, byId lookup and byMeasure iteration agree on the same instance.
        for (e in store.values) {
            assertSame(e, store[e.id])
        }
        assertEquals(setOf("a", "c", "d"), store.values.map { it.id.value }.toSet())
    }

    @Test
    fun putValueEqualIsNoOp() {
        val base = ComputedEventStore.of(listOf(ev("a", 1), ev("b", 2)))
        val same = base.put(ev("a", 1)) // equal content, fresh instance
        assertSame(base, same, "a value-equal put changes nothing → returns the same store")
    }

    @Test
    fun putValueEqualThenChangedDoesNotDuplicate() {
        // Regression: a value-equal re-put used to leave byId on the OLD instance while byMeasure took the
        // NEW one; a later genuine change then did an identity-based byMeasure.remove that missed and
        // duplicated the event in byMeasure (values.size > size, double-rendered note). Chained incremental
        // recomputes with overlapping BACK=1 windows re-put unchanged events repeatedly, so this bit
        // consecutive edits in adjacent measures. See docs/data_model/computed-event-store.md.
        var store = ComputedEventStore.of(listOf(ev("a", 1), ev("b", 2)))
        store = store.put(ev("a", 1))                                  // value-equal re-put
        store = store.put(ev("a", 1, Fraction.ZERO, Duration.HALF))    // now a real change to "a"

        assertEquals(2, store.size)
        assertEquals(store.size, store.values.size, "byMeasure must not duplicate the event")
        assertEquals(listOf("a", "b"), store.values.map { it.id.value })
        assertEquals(Duration.HALF, store.getValue(EventId("a")).duration)
        for (e in store.values) assertSame(e, store[e.id], "byId and byMeasure must agree on one instance")
    }

    @Test
    fun putKeepsUnaffectedEventReferences() {
        val a = ev("a", 1)
        val b = ev("b", 2)
        val base = ComputedEventStore.of(listOf(a, b))
        val next = base.put(ev("c", 3))
        // Unaffected events are shared by reference, not copied.
        assertSame(base[EventId("a")], next[EventId("a")])
        assertSame(base[EventId("b")], next[EventId("b")])
    }

    // --- content equality -------------------------------------------------------------------

    @Test
    fun equalityIsByContentNotHistory() {
        val events = listOf(ev("a", 1), ev("b", 2), ev("c", 3))
        val viaOf = ComputedEventStore.of(events)
        val viaPuts = ComputedEventStore.EMPTY
            .put(events[2]).put(events[0]).put(events[1])   // different insertion order

        assertEquals(viaOf, viaPuts)
        assertEquals(viaOf.hashCode(), viaPuts.hashCode())

        val differing = viaOf.put(ev("a", 1, Fraction.ZERO, Duration.HALF))
        assertFalse(viaOf == differing)
    }

    // --- range / measure --------------------------------------------------------------------

    @Test
    fun rangeAndInMeasureRespectBoundaries() {
        val store = ComputedEventStore.of(listOf(
            ev("m1", 1, Fraction.ZERO),
            ev("m2a", 2, Fraction.ZERO),
            ev("m2b", 2, Fraction(1, 2)),
            ev("m3", 3, Fraction.ZERO),
        ))
        assertEquals(listOf("m2a", "m2b"), store.inMeasure(2).map { it.id.value })
        // range is inclusive of both ends.
        assertEquals(
            listOf("m2a", "m2b", "m3"),
            store.range(TimeCode.of(2, Fraction.ZERO), TimeCode.of(3, Fraction.ZERO)).map { it.id.value }
        )
    }
}
