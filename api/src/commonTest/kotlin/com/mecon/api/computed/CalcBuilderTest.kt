package com.mecon.api.computed

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.HasOnset
import com.mecon.api.runtime.TimeIndexedList
import kotlin.test.Test
import kotlin.test.assertEquals

data class TestEvent(
    val id: String,
    override val onset: TimeCode
) : HasOnset

class CalcBuilderTest {

    @Test
    fun testAlignBilateral() {
        val trackA = TimeIndexedList.of(
            TestEvent("A1", TimeCode.ofMeasure(1)),
            TestEvent("A2", TimeCode.ofMeasure(2))
        )
        val trackB = TimeIndexedList.of(
            TestEvent("B1", TimeCode.of(1, 1, 2)),
            TestEvent("B2", TimeCode.ofMeasure(3))
        )
        
        val aligned = CalcBuilder.alignBilateral(trackA, trackB).toList()
        
        assertEquals(4, aligned.size)
        // Time 1,0 -> A1, null
        assertEquals(TimeCode.ofMeasure(1), aligned[0].onset)
        assertEquals("A1", aligned[0].events.first?.id)
        assertEquals(null, aligned[0].events.second?.id)
        
        // Time 1,500 -> A1, B1
        assertEquals(TimeCode.of(1, 1, 2), aligned[1].onset)
        assertEquals("A1", aligned[1].events.first?.id)
        assertEquals("B1", aligned[1].events.second?.id)
        
        // Time 2,0 -> A2, B1
        assertEquals(TimeCode.ofMeasure(2), aligned[2].onset)
        assertEquals("A2", aligned[2].events.first?.id)
        assertEquals("B1", aligned[2].events.second?.id)
        
        // Time 3,0 -> A2, B2
        assertEquals(TimeCode.ofMeasure(3), aligned[3].onset)
        assertEquals("A2", aligned[3].events.first?.id)
        assertEquals("B2", aligned[3].events.second?.id)
    }

    @Test
    fun testAlignLe() {
        val trackA = TimeIndexedList.of(
            TestEvent("A1", TimeCode.ofMeasure(1)),
            TestEvent("A2", TimeCode.ofMeasure(2))
        )
        val trackB = TimeIndexedList.of(
            TestEvent("B1", TimeCode.of(1, 1, 2)),
            TestEvent("B2", TimeCode.ofMeasure(3))
        )
        
        val aligned = CalcBuilder.alignLe(trackA, trackB).toList()
        
        assertEquals(2, aligned.size)
        // Time 1,0 -> A1, null
        assertEquals("A1", aligned[0].events.first?.id)
        assertEquals(null, aligned[0].events.second?.id)
        
        // Time 2,0 -> A2, B1
        assertEquals("A2", aligned[1].events.first?.id)
        assertEquals("B1", aligned[1].events.second?.id)
        
        // Test with offset = 1 (lookahead)
        val alignedOffset = CalcBuilder.alignLe(trackA, trackB, offset = 1).toList()
        // Time 1,0 + offset 1 -> A1, B1
        assertEquals("A1", alignedOffset[0].events.first?.id)
        assertEquals("B1", alignedOffset[0].events.second?.id)
        
        // Time 2,0 + offset 1 -> A2, B2
        assertEquals("A2", alignedOffset[1].events.first?.id)
        assertEquals("B2", alignedOffset[1].events.second?.id)
    }
}
