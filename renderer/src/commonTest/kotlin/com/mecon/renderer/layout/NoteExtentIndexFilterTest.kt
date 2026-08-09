package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [NoteExtentIndex.build] `systemFilter` is a pure pruning optimisation (§2.5 `paginate`): the
 * index is only ever queried on the systems that host a staff attachment, so building it for exactly
 * those systems must be byte-identical to an unfiltered build for every such query, while an empty
 * filter collapses to [NoteExtentIndex.EMPTY]. These tests pin that contract without the font / layout
 * pipeline.
 */
class NoteExtentIndexFilterTest {

    private val staff = TrackId("s0")

    private fun slot(time: TimeCode, x: Float, system: Int) =
        UnifiedTimeSlot(time = time, events = emptyList(), x = StaffSpace(x), systemIndex = system)

    private fun voiceEvent(id: String, time: TimeCode, staffIdx: Int = 0) = VoiceEventLayout(
        eventId = EventId(id),
        trackId = staff,
        time = time,
        staffIndex = staffIdx,
        measureNumber = time.measure,
        primary = NoteheadLayout(
            eventId = EventId(id),
            trackId = staff,
            relativeX = StaffSpace.ZERO,
            relativeY = StaffSpace.ZERO,
            staffIndex = staffIdx,
            noteheadType = NoteheadType.WHOLE,
            staffPosition = 0,
        ),
    )

    private val tA = TimeCode.of(1, Fraction(0, 4))
    private val tB = TimeCode.of(1, Fraction(1, 4))
    private val tC = TimeCode.of(2, Fraction(0, 4))

    // Two systems: system 0 (tA, tB) and system 1 (tC), each note a distinct extent.
    private val map = UnifiedTimeSlotMap(
        listOf(
            slot(tA, x = 10f, system = 0),
            slot(tB, x = 20f, system = 0),
            slot(tC, x = 30f, system = 1),
        )
    )
    private val events = listOf(voiceEvent("a", tA), voiceEvent("b", tB), voiceEvent("c", tC))
    private val extentOf: (VoiceEventLayout) -> Pair<StaffSpace, StaffSpace> = { ve ->
        val depth = when (ve.eventId) {
            EventId("a") -> 4f
            EventId("b") -> 6f
            else -> 9f
        }
        StaffSpace(2f) to StaffSpace(depth)
    }

    private val wide = StaffSpace(1000f)
    private val zero = StaffSpace(0f)

    @Test
    fun filteringToQueriedSystemMatchesUnfilteredForThatSystem() {
        val full = NoteExtentIndex.build(events, map, extentOf = extentOf)
        val filtered = NoteExtentIndex.build(events, map, systemFilter = setOf(0), extentOf = extentOf)

        // Every query on the retained system 0 is identical to the unfiltered index.
        assertEquals(
            full.localExtent(0, 0, zero, wide, zero),
            filtered.localExtent(0, 0, zero, wide, zero),
        )
        assertEquals(
            StaffSpace(2f) to StaffSpace(6f),
            filtered.localExtent(0, 0, zero, wide, zero),
        )
    }

    @Test
    fun localRangeUsesInnerTreeAggregate() {
        val index = NoteExtentIndex.build(events, map, extentOf = extentOf)

        assertEquals(
            StaffSpace(2f) to StaffSpace(4f),
            index.localExtent(0, 0, StaffSpace(9f), StaffSpace(11f), zero),
        )
        assertEquals(
            StaffSpace(2f) to StaffSpace(6f),
            index.localExtent(0, 0, StaffSpace(19f), StaffSpace(21f), zero),
        )
    }

    @Test
    fun displayTransformReusesLocalKeysAfterTranslationAndStretch() {
        val tree = NoteExtentTree.build(events.groupBy { it.measureNumber }, map, extentOf)
        val displayed = UnifiedTimeSlotMap(
            listOf(
                slot(tA, x = 100f, system = 0),
                slot(tB, x = 120f, system = 0),
                slot(tC, x = 200f, system = 1),
            )
        )
        val index = tree.index(map, displayed)

        assertEquals(
            StaffSpace(2f) to StaffSpace(4f),
            index.localExtent(0, 0, StaffSpace(99f), StaffSpace(101f), zero),
        )
        assertEquals(
            StaffSpace(2f) to StaffSpace(6f),
            index.localExtent(0, 0, StaffSpace(119f), StaffSpace(121f), zero),
        )
    }

    @Test
    fun incrementalTreeReplacesOnlyAffectedMeasureChunk() {
        val byMeasure = events.groupBy { it.measureNumber }
        val original = NoteExtentTree.build(byMeasure, map, extentOf)
        val edited = NoteExtentTree.build(
            layoutsByMeasure = byMeasure,
            slots = map,
            extentOf = extentOf,
            cached = original,
            replaceWindow = 1..1,
        )

        assertFalse(original.sharesChunkWith(edited, 1))
        assertTrue(original.sharesChunkWith(edited, 2))
    }

    @Test
    fun prunedSystemIsAbsentFromFilteredIndex() {
        val filtered = NoteExtentIndex.build(events, map, systemFilter = setOf(0), extentOf = extentOf)
        // System 1 was pruned (no attachment anchors there), so it returns the bare-staff default —
        // never observed in practice because no mark ever queries an attachment-free system.
        assertEquals(
            StaffSpace(2f) to StaffSpace(2f),
            filtered.localExtent(1, 0, zero, wide, zero),
        )
    }

    @Test
    fun emptyFilterYieldsEmptyIndex() {
        val filtered = NoteExtentIndex.build(events, map, systemFilter = emptySet(), extentOf = extentOf)
        assertEquals(NoteExtentIndex.EMPTY, filtered)
    }
}
