package com.mecon.api.computed

import com.mecon.api.primitive.StaffGroupId
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.BracketStyle
import kotlinx.serialization.Serializable

@Serializable
enum class StaffLabelPlacement { LEFT_OF_BRACKETS, BEFORE_BRACKETS }

/**
 * One bracket to draw on the left of a system, spanning a contiguous staff range.
 *
 * Brackets are emitted by [StaffHeaderComputer] from the storage staff-group tree.
 * Each bracket carries a [depth] so the renderer can lay nested brackets side-by-side.
 * The outermost group has the smallest depth; final engraving places it closest
 * to the staves, while the editor intentionally uses the opposite visual order.
 *
 * @property style Visual style — see [BracketStyle].
 * @property staffRange Inclusive staff-index range this bracket spans.
 * @property depth 1 = outermost; increasing values denote deeper nested groups.
 * @property sourceId Diagnostic / interaction handle: the StaffGroupId for group
 *   brackets, or the part's TrackId (as String) for per-part brackets.
 */
@Serializable
data class ComputedStaffBracket(
    val style: BracketStyle,
    val staffRange: StaffIndexRange,
    val depth: Int,
    val sourceId: String
)

/**
 * One label drawn on the left of a system, spanning a contiguous staff range.
 *
 * Labels include both per-part instrument names and group-level labels ("Choir",
 * "Strings"). Per-staff labels (e.g. "S./A./T./B." inside a SATB choir part) are
 * emitted as one ComputedStaffLabel per staff with a 1-staff range.
 *
 * @property text Display text.
 * @property abbreviation Optional shortened form for subsequent systems.
 * @property staffRange Inclusive staff-index range; label is vertically centred over it.
 * @property depth 0 = closest to the staves (per-staff labels), 1 = part labels,
 *   2+ = group labels (innermost group first).
 * @property sourceId Diagnostic handle: TrackId or StaffGroupId stringified.
 */
@Serializable
data class ComputedStaffLabel(
    val text: String,
    val abbreviation: String?,
    val staffRange: StaffIndexRange,
    val depth: Int,
    val sourceId: String,
    val placement: StaffLabelPlacement = StaffLabelPlacement.LEFT_OF_BRACKETS,
)

/**
 * Aggregated header information for a single system: all brackets and labels to
 * draw on the left margin, plus the resolved barline connectivity that should
 * be applied to every barline of the system.
 *
 * Connectivity is computed once per system because it depends only on the
 * group/part configuration, which does not change between barlines within a
 * system. [ComputedBarline.connectedStaffRanges] is populated from this.
 */
@Serializable
data class ComputedStaffHeader(
    val brackets: List<ComputedStaffBracket>,
    val labels: List<ComputedStaffLabel>,
    val barlineConnectivity: List<StaffIndexRange>
) {
    companion object {
        val EMPTY = ComputedStaffHeader(emptyList(), emptyList(), emptyList())
    }
}
