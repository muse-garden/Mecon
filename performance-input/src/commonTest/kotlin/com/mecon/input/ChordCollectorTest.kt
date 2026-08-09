package com.mecon.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChordCollectorTest {
    @Test
    fun `fixed window groups notes and does not slide`() {
        val collector = ChordCollector(windowNanos = 60)
        assertNull(collector.noteOn(on("kbd", 0, 60)))
        assertNull(collector.noteOn(on("kbd", 59, 64)))

        val chord = collector.flushExpired(60)
        assertEquals(listOf(60, 64), chord?.notes?.map { it.key })
        assertEquals(0, chord?.startedAtNanos)
    }

    @Test
    fun `later note flushes expired chord and begins another`() {
        val collector = ChordCollector(windowNanos = 60)
        collector.noteOn(on("kbd", 0, 60))
        val first = collector.noteOn(on("kbd", 61, 64))
        assertEquals(listOf(60), first?.notes?.map { it.key })
        assertEquals(listOf(64), collector.flush()?.notes?.map { it.key })
    }

    @Test
    fun `held duplicate is ignored until note off`() {
        val collector = ChordCollector(windowNanos = 60)
        collector.noteOn(on("kbd", 0, 60))
        collector.noteOn(on("kbd", 10, 60))
        assertEquals(1, collector.flush()?.notes?.size)

        collector.noteOff(PerformanceInputEvent.NoteOff("kbd", 70, 60))
        collector.noteOn(on("kbd", 80, 60))
        assertEquals(1, collector.flush()?.notes?.size)
    }

    private fun on(source: String, time: Long, key: Int) =
        PerformanceInputEvent.NoteOn(source, time, key)
}
