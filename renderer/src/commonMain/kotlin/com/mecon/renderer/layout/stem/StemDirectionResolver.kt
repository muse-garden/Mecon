package com.mecon.renderer.layout.stem

import com.mecon.api.computed.BeamGroupId
import com.mecon.api.computed.BeamInfo
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.StemDirection as StorageStemDirection
import com.mecon.renderer.enums.StemDirection

/**
 * Represents the context for a voice event when resolving stem directions.
 */
data class VoiceContext(
    val voiceNumber: Int,
    val measureNumber: Int,
    val hasMultipleVoices: Boolean,
    /** Staff (display order) the note actually renders on, after any cross-staff offset. */
    val staffIndex: Int = 0,
    /** Cross-staff offset (0 = home staff; <0 = borrowed up; >0 = borrowed down). */
    val crossStaffOffset: Int = 0
)

/**
 * Input data for stem direction resolution.
 */
data class StemResolutionInput(
    val eventId: EventId,
    val pitchData: List<ComputedPitchData>,
    val beamInfo: BeamInfo?,
    val userStemDirection: StorageStemDirection?,
    val voiceContext: VoiceContext
)

/**
 * Result of stem direction resolution.
 */
data class StemResolutionResult(
    val eventId: EventId,
    val direction: StemDirection
)

/**
 * Resolves stem directions for voice events in the renderer layer.
 *
 * This resolver implements the stem direction rules:
 * 1. User-specified direction takes precedence
 * 2. Multi-voice context: use voice-based defaults (odd voices up, even down)
 * 3. Beam groups: all notes share the same direction based on first/last notes
 * 4. Single notes: based on average staff position relative to middle line
 */
class StemDirectionResolver(
    private val voiceConfig: VoiceStemConfig = VoiceStemConfig.DEFAULT,
    private val beamConfig: BeamLayoutConfig = BeamLayoutConfig.DEFAULT
) {
    companion object {
        /**
         * Staff position of the middle line.
         * With the new coordinate system: staffPosition = 0 is the middle line.
         */
        private const val MIDDLE_LINE_POSITION = 0
    }

    /**
     * Resolve stem directions for all events.
     *
     * @param inputs List of events to resolve
     * @return Map of EventId to resolved StemDirection
     */
    fun resolve(inputs: List<StemResolutionInput>): Map<EventId, StemDirection> {
        if (inputs.isEmpty()) return emptyMap()

        val results = mutableMapOf<EventId, StemDirection>()

        // Group by beam group for beam-aware resolution
        val beamGroups = inputs.filter { it.beamInfo != null }
            .groupBy { it.beamInfo!!.groupId }

        val nonBeamedEvents = inputs.filter { it.beamInfo == null }

        // Resolve beam groups
        for ((groupId, groupEvents) in beamGroups) {
            val groupResults = resolveBeamGroupDirection(groupId, groupEvents)
            results.putAll(groupResults)
        }

        // Resolve individual (non-beamed) events
        for (event in nonBeamedEvents) {
            val direction = resolveIndividualDirection(event)
            results[event.eventId] = direction
        }

        return results
    }

    /**
     * Resolve stem direction for a beam group.
     *
     * The beam group direction is determined by:
     * 1. User override on first or last note
     * 2. Voice-based default if multiple voices in measure
     * 3. Average position of first and last notes' extreme pitches
     *
     * All notes in the group share the same direction.
     *
     * @param groupId The beam group ID
     * @param events Events in the beam group
     * @return Map of EventId to resolved direction
     */
    fun resolveBeamGroupDirection(
        groupId: BeamGroupId,
        events: List<StemResolutionInput>
    ): Map<EventId, StemDirection> {
        if (events.isEmpty()) return emptyMap()

        // Cross-staff beam: the group's notes span more than one staff, so the beam sits
        // *between* the staves and stems point inward toward it (interleaved / 符杆交错):
        // notes on the upper staff point DOWN, notes on the lower staff point UP. This is a
        // per-note decision and overrides the single-direction group rules below.
        val staffIndices = events.map { it.voiceContext.staffIndex }.distinct()
        if (staffIndices.size > 1) {
            val upper = staffIndices.min()
            val lower = staffIndices.max()
            return events.associate { ev ->
                val staff = ev.voiceContext.staffIndex
                val dir = when {
                    staff <= upper -> StemDirection.DOWN
                    staff >= lower -> StemDirection.UP
                    // A note on an intermediate staff points toward the nearer beam edge.
                    staff - upper <= lower - staff -> StemDirection.DOWN
                    else -> StemDirection.UP
                }
                ev.eventId to dir
            }
        }

        // Sort events to find first and last
        val sortedEvents = events.sortedBy { it.beamInfo?.beamsLeft ?: 0 }
        val firstEvent = sortedEvents.firstOrNull { it.beamInfo?.isGroupStart == true }
            ?: sortedEvents.first()
        val lastEvent = sortedEvents.lastOrNull { it.beamInfo?.isGroupEnd == true }
            ?: sortedEvents.last()

        // Check for user override on first or last note
        val userOverride = firstEvent.userStemDirection?.takeIf { it != StorageStemDirection.AUTO }
            ?: lastEvent.userStemDirection?.takeIf { it != StorageStemDirection.AUTO }

        val groupDirection = when {
            userOverride != null -> {
                storageStemDirectionToRenderer(userOverride)
            }

            firstEvent.voiceContext.hasMultipleVoices -> {
                voiceConfig.defaultForVoice(firstEvent.voiceContext.voiceNumber)
            }

            else -> {
                // Check for split beam condition
                val splitResult = detectSplitBeam(events)
                if (splitResult.isSplit) {
                    // For split beams, we need per-note directions
                    // This is a complex case - for now, use average position
                    computeGroupDirectionFromPositions(firstEvent, lastEvent)
                } else {
                    computeGroupDirectionFromPositions(firstEvent, lastEvent)
                }
            }
        }

        // Apply same direction to all notes in the group
        return events.associate { it.eventId to groupDirection }
    }

    /**
     * Resolve stem direction for an individual (non-beamed) note.
     *
     * @param input The event to resolve
     * @return Resolved stem direction
     */
    fun resolveIndividualDirection(input: StemResolutionInput): StemDirection {
        // Check for user override
        val userDir = input.userStemDirection
        if (userDir != null && userDir != StorageStemDirection.AUTO) {
            return storageStemDirectionToRenderer(userDir)
        }

        // Standalone cross-staff note (no beam to interleave): apply the voice-insertion rule.
        // Borrowed up (target staff above, offset < 0) acts as the lower/added voice → stem DOWN;
        // borrowed down (offset > 0) is inserted as the new top voice → stem UP.
        if (input.voiceContext.crossStaffOffset != 0) {
            return if (input.voiceContext.crossStaffOffset < 0) StemDirection.DOWN else StemDirection.UP
        }

        // Multi-voice: use voice-based default
        if (input.voiceContext.hasMultipleVoices) {
            return voiceConfig.defaultForVoice(input.voiceContext.voiceNumber)
        }

        // Single voice: compute from staff position
        return computeDirectionFromStaffPosition(input.pitchData)
    }

    /**
     * Compute direction from average staff position.
     *
     * Notes on or above the middle line get stems down.
     * Notes below the middle line get stems up.
     * For chords, use the average position.
     */
    private fun computeDirectionFromStaffPosition(pitchData: List<ComputedPitchData>): StemDirection {
        if (pitchData.isEmpty()) return beamConfig.neutralDirection

        val avgPosition = pitchData.map { it.staffPosition }.average()
        return if (avgPosition > MIDDLE_LINE_POSITION) {
            StemDirection.DOWN
        } else {
            StemDirection.UP
        }
    }

    /**
     * Compute group direction from first and last note positions.
     */
    private fun computeGroupDirectionFromPositions(
        firstEvent: StemResolutionInput,
        lastEvent: StemResolutionInput
    ): StemDirection {
        val firstAvg = firstEvent.pitchData.map { it.staffPosition }.average()
        val lastAvg = lastEvent.pitchData.map { it.staffPosition }.average()
        val combinedAvg = (firstAvg + lastAvg) / 2

        return if (combinedAvg > MIDDLE_LINE_POSITION) {
            StemDirection.DOWN
        } else {
            StemDirection.UP
        }
    }

    /**
     * Convert storage stem direction to renderer stem direction.
     */
    private fun storageStemDirectionToRenderer(direction: StorageStemDirection): StemDirection {
        return when (direction) {
            StorageStemDirection.UP -> StemDirection.UP
            StorageStemDirection.DOWN -> StemDirection.DOWN
            StorageStemDirection.AUTO -> beamConfig.neutralDirection
        }
    }

    /**
     * Result of split beam detection.
     */
    data class SplitBeamResult(
        val isSplit: Boolean,
        val upperGroup: List<EventId> = emptyList(),
        val lowerGroup: List<EventId> = emptyList()
    )

    /**
     * Detect if a beam group should be split (kneed beam).
     *
     * A split beam occurs when notes span a large range (larger than autoKneeGap)
     * with notes above the staff (staffPosition > 5) and notes below the staff
     * (staffPosition < -5) but no notes in the middle range.
     *
     * Staff position coordinate system:
     * - 0 = middle line
     * - 4 = top line, 5 = space above top line, 6+ = ledger lines above
     * - -4 = bottom line, -5 = space below bottom line, -6 and below = ledger lines below
     */
    private fun detectSplitBeam(events: List<StemResolutionInput>): SplitBeamResult {
        val allPositions = events.flatMap { event ->
            event.pitchData.map { it.staffPosition }
        }

        if (allPositions.isEmpty()) return SplitBeamResult(false)

        val minPosition = allPositions.minOrNull() ?: 0
        val maxPosition = allPositions.maxOrNull() ?: 0

        // Check if gap exceeds autoKneeGap threshold
        // Each staff position is 0.5 staff spaces, so divide by 2 to get staff spaces
        val gapInStaffSpaces = (maxPosition - minPosition) / 2.0f

        if (gapInStaffSpaces < beamConfig.autoKneeGap) {
            return SplitBeamResult(false)
        }

        // Check for bipolar distribution (notes above and below staff with no middle)
        // Notes above staff: staffPosition > 5 (above top line space)
        // Notes below staff: staffPosition < -5 (below bottom line space)
        // Middle notes: -5 <= staffPosition <= 5 (within or on staff lines/spaces)
        val notesAboveStaff = events.filter { event ->
            event.pitchData.any { it.staffPosition > 5 }
        }
        val notesBelowStaff = events.filter { event ->
            event.pitchData.any { it.staffPosition < -5 }
        }
        val middleNotes = events.filter { event ->
            event.pitchData.all { it.staffPosition in -5..5 }
        }

        if (notesAboveStaff.isNotEmpty() && notesBelowStaff.isNotEmpty() && middleNotes.isEmpty()) {
            return SplitBeamResult(
                isSplit = true,
                upperGroup = notesAboveStaff.map { it.eventId },
                lowerGroup = notesBelowStaff.map { it.eventId }
            )
        }

        return SplitBeamResult(false)
    }
}
