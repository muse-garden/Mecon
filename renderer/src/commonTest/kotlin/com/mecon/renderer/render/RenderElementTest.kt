package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RenderElementTest {
    private fun id(value: Long) = RenderElementId.global(value)

    @Test
    fun testContainsPoint() {
        val element = RenderElement(
            id = id(0),
            type = RenderElementType.NOTE,
            commands = emptyList(),
            hitBox = AbsoluteRect(
                AbsolutePoint(Pixels(10f), Pixels(10f)),
                Pixels(20f),
                Pixels(20f)
            )
        )

        // Point inside
        assertTrue(element.containsPoint(AbsolutePoint(Pixels(15f), Pixels(15f))))

        // Point outside
        assertTrue(!element.containsPoint(AbsolutePoint(Pixels(5f), Pixels(5f))))
        assertTrue(!element.containsPoint(AbsolutePoint(Pixels(35f), Pixels(35f))))
    }

    @Test
    fun testCenter() {
        val element = RenderElement(
            id = id(0),
            type = RenderElementType.NOTE,
            commands = emptyList(),
            hitBox = AbsoluteRect(
                AbsolutePoint(Pixels(10f), Pixels(20f)),
                Pixels(30f),
                Pixels(40f)
            )
        )

        val center = element.center
        assertEquals(25f, center.x.value)  // 10 + 30/2
        assertEquals(40f, center.y.value)  // 20 + 40/2
    }

    @Test
    fun testBuilderBasic() {
        val element = renderElement(id(1), RenderElementType.NOTE)
            .eventId(EventId("event1"))
            .trackId(TrackId("track1"))
            .measureNumber(1)
            .systemIndex(0)
            .staffIndex(0)
            .build()

        assertEquals(id(1), element.id)
        assertEquals(RenderElementType.NOTE, element.type)
        assertEquals(EventId("event1"), element.eventId)
        assertEquals(TrackId("track1"), element.trackId)
        assertEquals(1, element.measureNumber)
        assertEquals(0, element.systemIndex)
        assertEquals(0, element.staffIndex)
    }

    @Test
    fun testBuilderWithMetadata() {
        val element = renderElement(id(1), RenderElementType.NOTE)
            .metadata("pitch", "C4")
            .metadata("duration", "quarter")
            .build()

        assertEquals("C4", element.metadata["pitch"])
        assertEquals("quarter", element.metadata["duration"])
    }

    @Test
    fun testRenderResultFilterByType() {
        val elements = listOf(
            RenderElement(id(1), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f))),
            RenderElement(id(2), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f))),
            RenderElement(id(3), RenderElementType.REST, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)))
        )

        val result = RenderResult(
            elements = elements,
            bounds = AbsoluteRect(AbsolutePoint.ZERO, Pixels(100f), Pixels(100f)),
            firstSystem = 0,
            lastSystem = 0,
            firstMeasure = 1,
            lastMeasure = 1
        )

        val notes = result.elementsOfType(RenderElementType.NOTE)
        assertEquals(2, notes.size)

        val rests = result.elementsOfType(RenderElementType.REST)
        assertEquals(1, rests.size)
    }

    @Test
    fun testRenderResultFilterByEvent() {
        val eventId = EventId("event1")
        val elements = listOf(
            RenderElement(id(1), RenderElementType.NOTEHEAD, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), eventId = eventId),
            RenderElement(id(2), RenderElementType.STEM, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), eventId = eventId),
            RenderElement(id(3), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), eventId = EventId("other"))
        )

        val result = RenderResult(
            elements = elements,
            bounds = AbsoluteRect(AbsolutePoint.ZERO, Pixels(100f), Pixels(100f)),
            firstSystem = 0,
            lastSystem = 0,
            firstMeasure = 1,
            lastMeasure = 1
        )

        val eventElements = result.elementsForEvent(eventId)
        assertEquals(2, eventElements.size)
        assertTrue(eventElements.all { it.eventId == eventId })
    }

    @Test
    fun testRenderResultFilterByMeasure() {
        val elements = listOf(
            RenderElement(id(1), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), measureNumber = 1),
            RenderElement(id(2), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), measureNumber = 2),
            RenderElement(id(3), RenderElementType.NOTE, emptyList(),
                AbsoluteRect(AbsolutePoint.ZERO, Pixels(10f), Pixels(10f)), measureNumber = 1)
        )

        val result = RenderResult(
            elements = elements,
            bounds = AbsoluteRect(AbsolutePoint.ZERO, Pixels(100f), Pixels(100f)),
            firstSystem = 0,
            lastSystem = 0,
            firstMeasure = 1,
            lastMeasure = 2
        )

        val measure1Elements = result.elementsInMeasure(1)
        assertEquals(2, measure1Elements.size)

        val measure2Elements = result.elementsInMeasure(2)
        assertEquals(1, measure2Elements.size)
    }
}
