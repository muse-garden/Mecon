package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [RenderElement.translate], the (Δx, Δy) translate the incremental render path uses to
 * reuse cached elements. The pixel command/hitBox translation itself is covered by
 * [RenderElementTransformTest]; here we pin the two things specific to this helper: (1) pixel geometry
 * shifts by the *scaled* delta, and (2) the staff-space [RenderElement.relativeHitBox] shifts by the raw
 * delta so the rebuilt spatial index stays aligned with the drawn element.
 */
class RenderTranslateTest {

    private fun pt(x: Float, y: Float) = AbsolutePoint(Pixels(x), Pixels(y))
    private fun rect(x: Float, y: Float, w: Float, h: Float) =
        AbsoluteRect(pt(x, y), Pixels(w), Pixels(h))

    private fun noteElement() = RenderElement(
        id = RenderElementId.global(0),
        type = RenderElementType.NOTEHEAD,
        commands = listOf(DrawGlyph(
            position = pt(16f, 20f), glyph = SmuflGlyphs.noteheadBlack,
            fontSize = Pixels(32f), bounds = rect(16f, 18f, 8f, 8f)
        )),
        hitBox = rect(16f, 18f, 8f, 8f),
        eventId = EventId("n1"),
        measureNumber = 2,
        relativeHitBox = RelativeRect(RelativePoint(StaffSpace(2f), StaffSpace(0f)), StaffSpace(1f), StaffSpace(1f))
    )

    @Test
    fun translatesPixelGeometryByScaledDeltaAndRelativeHitBoxByStaffSpace() {
        val scale = ScaleFactor(8f) // 8 px / staff space
        val moved = noteElement().translate(StaffSpace(3f), StaffSpace(1f), scale) // 3 sp → 24 px, 1 sp → 8 px

        val movedGlyph = moved.commands[0] as DrawGlyph
        assertEquals(Pixels(40f), movedGlyph.position.x) // 16 + 24
        assertEquals(Pixels(28f), movedGlyph.position.y) // 20 + 8
        assertEquals(Pixels(40f), moved.hitBox.origin.x)
        assertEquals(StaffSpace(5f), moved.relativeHitBox!!.origin.x) // 2 + 3 (staff spaces, not pixels)
        assertEquals(StaffSpace(1f), moved.relativeHitBox!!.origin.y) // 0 + 1
    }

    @Test
    fun preservesIdentityAndMetadata() {
        val src = noteElement()
        val moved = src.translate(StaffSpace(3f), StaffSpace.ZERO, ScaleFactor(8f))
        assertEquals(src.id, moved.id)
        assertEquals(src.eventId, moved.eventId)
        assertEquals(src.measureNumber, moved.measureNumber)
        assertEquals(src.type, moved.type)
    }

    @Test
    fun zeroDeltaIsIdentity() {
        val src = noteElement()
        // translatedBy short-circuits at dx==0,dy==0; relativeHitBox + 0 is also a no-op, so the
        // same instance comes back (no allocation for an unchanged element).
        assertSame(src, src.translate(StaffSpace.ZERO, StaffSpace.ZERO, ScaleFactor(8f)))
    }
}
