package com.mecon.api.computed

import com.mecon.api.primitive.*
import com.mecon.api.storage.StemDirection
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Position within a measure.
 */
@Serializable
data class MeasurePosition(
    val measure: Int,
    val beatPosition: Fraction,
    val absolutePosition: Fraction
)

/**
 * Beam group identifier.
 */
@Serializable
@JvmInline
value class BeamGroupId(val value: String)

/**
 * Beam information for a note in the computed layer.
 *
 * This describes how beams connect this note to adjacent notes.
 * The renderer uses this information to draw:
 * - Through beams (connecting both sides)
 * - Starting beams (connecting to the right only)
 * - Ending beams (connecting to the left only)
 * - Beam hooks (partial beams on one side only, for dotted rhythms etc.)
 * - Flags (no beams, isolated flagged note)
 *
 * ## Beam Levels
 * - Level 0: Primary beam (8th notes)
 * - Level 1: Secondary beam (16th notes)
 * - Level 2: Tertiary beam (32nd notes)
 * - etc.
 *
 * ## Examples
 *
 * ### Four 8th notes beamed together:
 * ```
 * Note 1: beamsLeft=0, beamsRight=1  (start)
 * Note 2: beamsLeft=1, beamsRight=1  (middle)
 * Note 3: beamsLeft=1, beamsRight=1  (middle)
 * Note 4: beamsLeft=1, beamsRight=0  (end)
 * ```
 *
 * ### Dotted 8th + 16th (backward hook on 16th):
 * ```
 * Note 1 (dotted 8th): beamsLeft=0, beamsRight=1
 * Note 2 (16th):       beamsLeft=2, beamsRight=0  <- left hook at level 1
 * ```
 *
 * ### 16th + dotted 8th (forward hook on 16th):
 * ```
 * Note 1 (16th):       beamsLeft=0, beamsRight=2  <- right hook at level 1
 * Note 2 (dotted 8th): beamsLeft=1, beamsRight=0
 * ```
 *
 * ### Mixed 16th + 8th + 16th:
 * ```
 * Note 1 (16th): beamsLeft=0, beamsRight=2
 * Note 2 (8th):  beamsLeft=1, beamsRight=1  <- level 1 ends/starts as hooks
 * Note 3 (16th): beamsLeft=2, beamsRight=0
 * ```
 *
 * @property groupId Unique identifier for the beam group
 * @property totalBeamCount Total beam levels required by this note's duration (8th=1, 16th=2, 32nd=3...)
 * @property beamsLeft Number of beam levels connecting to the left neighbor (0 = no left connection)
 * @property beamsRight Number of beam levels connecting to the right neighbor (0 = no right connection)
 */
@Serializable
data class BeamInfo(
    val groupId: BeamGroupId,
    val totalBeamCount: Int,
    val beamsLeft: Int,
    val beamsRight: Int
) {
    /**
     * Number of through beams (connecting both left and right).
     */
    val throughBeamCount: Int get() = minOf(beamsLeft, beamsRight)

    /**
     * Number of beam hooks pointing left (backward hooks).
     *
     * These are partial beams drawn only to the left, used when:
     * - This note needs more beams than continue to the right
     * - And this note is in the middle of a group (not at start or end)
     *
     * Example: In "8th + 16th + 8th" inside a group, the 16th needs 2 beams
     * but only 1 connects on each side. The extra beam is a left hook.
     *
     * At group start/end, extra beams are drawn as starting/ending beams, not hooks.
     */
    val leftHookCount: Int get() = when {
        beamsRight == 0 -> 0  // At group end, extra beams are ending beams, not hooks
        beamsLeft == 0 -> 0   // At group start, no left hooks
        else -> maxOf(0, totalBeamCount - beamsRight)
    }

    /**
     * Number of beam hooks pointing right (forward hooks).
     *
     * These are partial beams drawn only to the right, used when:
     * - This note needs more beams than continue from the left
     * - And this note is in the middle of a group (not at start or end)
     *
     * Example: In "8th + 16th + 8th" inside a group, the 16th needs 2 beams
     * but only 1 connects on each side. The extra beam is a right hook.
     *
     * At group start/end, extra beams are drawn as starting/ending beams, not hooks.
     */
    val rightHookCount: Int get() = when {
        beamsLeft == 0 -> 0   // At group start, extra beams are starting beams, not hooks
        beamsRight == 0 -> 0  // At group end, no right hooks
        else -> maxOf(0, totalBeamCount - beamsLeft)
    }

    /**
     * Number of beams starting at this note (at group start, going right).
     * This is the total beams this note requires when at group start.
     */
    val startingBeamCount: Int get() = if (beamsLeft == 0 && beamsRight > 0) totalBeamCount else 0

    /**
     * Number of beams ending at this note (at group end, from left).
     * This is the total beams this note requires when at group end.
     */
    val endingBeamCount: Int get() = if (beamsRight == 0 && beamsLeft > 0) totalBeamCount else 0

    /**
     * Number of beams that actually connect to the left neighbor.
     * At group start this is 0, otherwise it's beamsLeft.
     */
    val connectingBeamsLeft: Int get() = beamsLeft

    /**
     * Number of beams that actually connect to the right neighbor.
     * At group end this is 0, otherwise it's beamsRight.
     */
    val connectingBeamsRight: Int get() = beamsRight

    /**
     * True if this note is at the start of the beam group (no left connection).
     */
    val isGroupStart: Boolean get() = beamsLeft == 0 && beamsRight > 0

    /**
     * True if this note is at the end of the beam group (no right connection).
     */
    val isGroupEnd: Boolean get() = beamsLeft > 0 && beamsRight == 0

    /**
     * True if this note is in the middle of the beam group (both connections).
     */
    val isGroupMiddle: Boolean get() = beamsLeft > 0 && beamsRight > 0

    /**
     * True if this note has left hooks (backward partial beams).
     */
    val hasLeftHook: Boolean get() = leftHookCount > 0

    /**
     * True if this note has right hooks (forward partial beams).
     */
    val hasRightHook: Boolean get() = rightHookCount > 0

    /**
     * True if beam count increases from left to right at this note.
     */
    val beamsIncrease: Boolean get() = beamsRight > beamsLeft

    /**
     * True if beam count decreases from left to right at this note.
     */
    val beamsDecrease: Boolean get() = beamsLeft > beamsRight

    companion object {
        /**
         * Create BeamInfo for the start of a beam group.
         */
        fun groupStart(groupId: BeamGroupId, totalBeamCount: Int, beamsRight: Int) = BeamInfo(
            groupId = groupId,
            totalBeamCount = totalBeamCount,
            beamsLeft = 0,
            beamsRight = beamsRight
        )

        /**
         * Create BeamInfo for the end of a beam group.
         */
        fun groupEnd(groupId: BeamGroupId, totalBeamCount: Int, beamsLeft: Int) = BeamInfo(
            groupId = groupId,
            totalBeamCount = totalBeamCount,
            beamsLeft = beamsLeft,
            beamsRight = 0
        )

        /**
         * Create BeamInfo for the middle of a beam group.
         */
        fun groupMiddle(groupId: BeamGroupId, totalBeamCount: Int, beamsLeft: Int, beamsRight: Int) = BeamInfo(
            groupId = groupId,
            totalBeamCount = totalBeamCount,
            beamsLeft = beamsLeft,
            beamsRight = beamsRight
        )

        /**
         * Calculate total beam count from duration.
         * 8th = 1, 16th = 2, 32nd = 3, 64th = 4, 128th = 5.
         * Returns 0 for quarter notes and longer (no beams needed).
         */
        fun beamCountFromDuration(duration: Duration): Int {
            return when (duration.base) {
                DurationBase.EIGHTH -> 1
                DurationBase.SIXTEENTH -> 2
                DurationBase.THIRTY_SECOND -> 3
                DurationBase.SIXTY_FOURTH -> 4
                DurationBase.ONE_TWENTY_EIGHTH -> 5
                else -> 0  // Quarter note and longer have no beams
            }
        }
    }
}

/**
 * Computed stem direction (resolved from AUTO).
 */
@Serializable
enum class ResolvedStemDirection {
    UP,
    DOWN;

    companion object {
        fun from(direction: StemDirection, defaultUp: Boolean): ResolvedStemDirection {
            return when (direction) {
                StemDirection.UP -> UP
                StemDirection.DOWN -> DOWN
                StemDirection.AUTO -> if (defaultUp) UP else DOWN
            }
        }
    }
}
