package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.collections.immutable.toPersistentMap

/**
 * Additional vertical room a staff must reserve for its attachments, beyond the
 * note content extent. Fed into [StaffLayoutComputer] so systems don't overlap.
 */
data class AttachmentExtent(
    val extraTop: StaffSpace = StaffSpace.ZERO,
    val extraBottom: StaffSpace = StaffSpace.ZERO,
)

/**
 * A staff attachment resolved to drawable geometry.
 *
 * [geometries] are positioned with X in system staff-space and Y **relative to
 * the staff centre** (the renderer adds the staff's `centerY` at draw time, so
 * placement survives later vertical re-spacing). This is the renderer-side unit
 * of the reusable "extra symbol / text" abstraction — any future attachment kind
 * produces a list of [DrawableGeometry] plus a relative bounding box.
 */
data class PlacedStaffAttachment(
    val attachment: ComputedStaffAttachment,
    val staffIndex: Int,
    val measureNumber: Int,
    val geometries: List<DrawableGeometry>,
    /** Merged bounds (X system space, Y relative to staff centre). */
    val relativeBounds: RelativeRect,
    /**
     * System (line) this attachment segment belongs to. 0 in continuous mode.
     * A span crossing a system break is split into one [PlacedStaffAttachment]
     * per system, each tagged with its own [systemIndex].
     */
    val systemIndex: Int = 0,
)

/** Output of [StaffAttachmentLayoutComputer]. */
class StaffAttachmentLayoutResult private constructor(
    private val placedSeed: List<PlacedStaffAttachment>?,
    /**
     * Extra vertical extents keyed by systemIndex → staffIndex. Attachments are packed
     * per system (marks on different lines share the same justified X band), so the room
     * they reserve is a per-line quantity: line-local vertical layout reads only the
     * entry for its own system. In continuous mode every attachment is on system 0.
     */
    val extents: Map<Int, Map<Int, AttachmentExtent>>,
    /** Persistent system chunks used by stable-partition incremental attachment placement. */
    internal val placedBySystem: Map<Int, List<PlacedStaffAttachment>>,
) {
    constructor(
        placed: List<PlacedStaffAttachment>,
        extents: Map<Int, Map<Int, AttachmentExtent>>,
    ) : this(placed, extents, placed.groupBy { it.systemIndex })

    /** Flatten only for the full tag/split fallback; incremental frames consume [placedBySystem]. */
    val placed: List<PlacedStaffAttachment> by lazy(LazyThreadSafetyMode.NONE) {
        placedSeed ?: placedBySystem.entries.sortedBy { it.key }.flatMap { it.value }
    }

    val placedCount: Int
        get() = placedSeed?.size ?: placedBySystem.values.sumOf { it.size }

    /** Per-staff attachment extents for [systemIndex] (empty when that system has none). */
    fun extentsForSystem(systemIndex: Int): Map<Int, AttachmentExtent> =
        extents[systemIndex] ?: emptyMap()

    /** Replace only affected system chunks while structurally sharing the rest. */
    internal fun patchSystems(
        affectedSystems: Set<Int>,
        fresh: StaffAttachmentLayoutResult,
    ): StaffAttachmentLayoutResult {
        val placedBuilder = placedBySystem.toPersistentMap().builder()
        val extentBuilder = extents.toPersistentMap().builder()
        for (system in affectedSystems) {
            placedBuilder.remove(system)
            extentBuilder.remove(system)
        }
        for ((system, chunk) in fresh.placedBySystem) placedBuilder[system] = chunk
        for ((system, extent) in fresh.extents) extentBuilder[system] = extent
        return StaffAttachmentLayoutResult(
            placedSeed = null,
            extents = extentBuilder.build(),
            placedBySystem = placedBuilder.build(),
        )
    }

    companion object {
        val EMPTY = StaffAttachmentLayoutResult(emptyList(), emptyMap())
    }
}
