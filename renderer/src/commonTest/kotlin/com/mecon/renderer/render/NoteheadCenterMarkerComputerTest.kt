package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.NoteRef
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteheadCenterMarkerComputerTest {
    @Test
    fun usesContrastingDotsAndOnlyMarksRequestedNoteheads() {
        val black = notehead("black", 0, filled = true)
        val half = notehead("half", 1, filled = false)
        val ignored = notehead("ignored", 0, filled = true)

        val markers = NoteheadCenterMarkerComputer.compute(
            listOf(black, half, ignored),
            setOf(NoteRef(EventId("black"), 0), NoteRef(EventId("half"), 1)),
        )

        assertEquals(2, markers.size)
        assertEquals(RenderColor.WHITE, markers.single { it.eventId == EventId("black") }.color)
        assertEquals(RenderColor.BLACK, markers.single { it.eventId == EventId("half") }.color)
        assertEquals(AbsolutePoint(Pixels(15f), Pixels(24f)), markers.first().center)
    }

    private fun notehead(eventId: String, pitchIndex: Int, filled: Boolean) = RenderElement(
        id = RenderElementId(eventId.hashCode().toLong()),
        type = RenderElementType.NOTEHEAD,
        commands = emptyList(),
        hitBox = AbsoluteRect(
            origin = AbsolutePoint(Pixels(10f), Pixels(20f)),
            width = Pixels(10f),
            height = Pixels(8f),
        ),
        eventId = EventId(eventId),
        metadata = mapOf("pitchIndex" to pitchIndex.toString(), "noteheadFilled" to filled.toString()),
    )
}
