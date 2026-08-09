package com.mecon.renderer.render

import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace

/**
 * Translate a reused [RenderElement] by (Δx, Δy) staff spaces for the incremental render path.
 *
 * The splice reuses cached elements for measures outside an edit's window. Two rigid shifts can apply:
 * **Δx** — the width change the window introduced (the proportional solve's tail is a slope-1 affine
 * function of the start X, so every tail element moves by the same Δx; prefix Δx is 0); and **Δy** — a
 * global vertical shift when the edit changes the score's pitch extent (a higher/lower note grows the
 * top/bottom overhang, moving the single staff's origin, hence *every* element, by a constant). Both are
 * rigid translations of already-rendered geometry, so reusing the cached commands reproduces the full
 * render exactly, without re-running glyph layout. (Window elements are regenerated from the new
 * `ComputedScore`, so their pitch-driven geometry is already correct.)
 *
 * Pixel geometry (commands + [RenderElement.hitBox]) is translated by reusing [translatedBy] — the same
 * helper that projects elements into page-local coordinates — so there is one translation implementation.
 * This adds the one thing [translatedBy] omits: shifting the staff-space [RenderElement.relativeHitBox],
 * from which the spatial index is rebuilt. [scale] bridges the two spaces.
 */
fun RenderElement.translate(deltaX: StaffSpace, deltaY: StaffSpace, scale: ScaleFactor): RenderElement {
    if (deltaX == StaffSpace.ZERO && deltaY == StaffSpace.ZERO) return this // unchanged → reuse, no alloc
    val moved = translatedBy(scale.toPixels(deltaX).value, scale.toPixels(deltaY).value)
    val relBox = relativeHitBox ?: return moved
    return moved.copy(relativeHitBox = relBox.copy(
        origin = relBox.origin.copy(x = relBox.origin.x + deltaX, y = relBox.origin.y + deltaY)
    ))
}
