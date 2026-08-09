package com.mecon.renderer.layout.stem

import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlin.math.abs

/**
 * Input for beam layout calculation.
 */
data class BeamNoteInput(
    val id: Any,                // Identifier (e.g. EventId)
    val x: StaffSpace,          // Absolute X position
    val defaultStemTipY: StaffSpace, // Default Y position of stem tip (absolute)
    /** Y of the notehead nearest to the beam (top notehead for UP stems, bottom for DOWN). */
    val nearNoteheadY: StaffSpace,
    val isRest: Boolean = false
)

/**
 * Result of beam layout calculation.
 */
data class BeamLayoutResult(
    val startX: StaffSpace,
    val startY: StaffSpace,     // Absolute Y position at start
    val endX: StaffSpace,
    val endY: StaffSpace,       // Absolute Y position at end
    val slope: Float            // Slope (dy/dx)
) {
    /**
     * Calculate Y position at a given X.
     */
    fun yAt(x: StaffSpace): StaffSpace {
        return StaffSpace(startY.value + slope * (x.value - startX.value))
    }
}

/**
 * Computes beam placement (slope and position).
 */
class BeamLayoutComputer(
    private val config: BeamLayoutConfig = BeamLayoutConfig.DEFAULT
) {
    fun compute(
        inputs: List<BeamNoteInput>,
        direction: StemDirection,
        minStemLength: Float = config.minimumFreeLength(1)
    ): BeamLayoutResult? {
        if (inputs.size < 2) return null

        // Sort by X position
        val sorted = inputs.sortedBy { it.x.value }
        val first = sorted.first()
        val last = sorted.last()

        val totalDx = last.x.value - first.x.value
        if (totalDx < 0.001f) return null // Avoid division by zero

        // 1. Calculate raw slope from first to last note
        val rawDy = last.defaultStemTipY.value - first.defaultStemTipY.value
        val rawSlope = rawDy / totalDx

        // 2. Apply damping (flatten the slope)
        // Damping 1.0 = full slope, 0.0 = horizontal
        var slope = rawSlope * config.damping

        // 3. Round small slopes to zero (horizontal)
        if (abs(slope) < config.roundToZeroSlope) {
            slope = 0f
        }

        // Limit maximum slope (prevent extreme angles)
        // E.g. max 0.5 (1 staff space per 2 units width)
        val maxSlope = 0.5f
        if (slope > maxSlope) slope = maxSlope
        if (slope < -maxSlope) slope = -maxSlope

        // 4. Determine vertical position (shift)
        // We want the beam to pass through the "center of gravity" of the stem tips.
        // Calculate the "intercept" (Y at x=first.x) implied by each note's default stem tip
        // impliedY = note.y - slope * (note.x - first.x)
        val impliedYs = sorted.map { note ->
            note.defaultStemTipY.value - slope * (note.x.value - first.x.value)
        }

        // Use the average intercept to center the beam relative to natural stem lengths
        var startY = impliedYs.average().toFloat()

        // 5. Enforce minimum stem length at every note.
        // After slope capping + averaging, extreme noteheads in a wide-range beam group
        // can end up too close to (or on the wrong side of) the beam, causing overlap.
        // Push the beam outward so every stem stays at least minStemLength long.
        val constrainingNotes = sorted.filter { !it.isRest }
        if (constrainingNotes.isNotEmpty()) {
            when (direction) {
                StemDirection.UP -> {
                    // Beam must be above noteheads: startY <= nearNotehead - minStem - slope*dx
                    val maxAllowedStartY = constrainingNotes.minOf { note ->
                        note.nearNoteheadY.value - minStemLength - slope * (note.x.value - first.x.value)
                    }
                    if (startY > maxAllowedStartY) startY = maxAllowedStartY
                }
                StemDirection.DOWN -> {
                    // Beam must be below noteheads: startY >= nearNotehead + minStem - slope*dx
                    val minAllowedStartY = constrainingNotes.maxOf { note ->
                        note.nearNoteheadY.value + minStemLength - slope * (note.x.value - first.x.value)
                    }
                    if (startY < minAllowedStartY) startY = minAllowedStartY
                }
            }
        }

        val endY = startY + slope * totalDx

        return BeamLayoutResult(
            startX = first.x,
            startY = StaffSpace(startY),
            endX = last.x,
            endY = StaffSpace(endY),
            slope = slope
        )
    }
}
