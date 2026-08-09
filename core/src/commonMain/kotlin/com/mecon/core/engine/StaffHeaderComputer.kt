package com.mecon.core.engine

import com.mecon.api.computed.ComputedStaffBracket
import com.mecon.api.computed.ComputedStaffHeader
import com.mecon.api.computed.ComputedStaffLabel
import com.mecon.api.computed.StaffIndexRange
import com.mecon.api.computed.StaffLabelPlacement
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.tracks.RuntimeStaffGroup
import com.mecon.api.runtime.tracks.RuntimeStaffGroupMember
import com.mecon.api.storage.tracks.BracketStyle

/**
 * Computes the [ComputedStaffHeader] for a [RuntimeScore].
 *
 * Staff display order comes from depth-first traversal of [RuntimeScore.staffGroups]
 * via [RuntimeScore.orderedStaffs].
 *
 * ## Depth assignment
 *
 * Depth encodes nesting level relative to the immediately enclosing group:
 *  - depth 0: per-staff labels (range = 1 staff)
 *  - depth N (N≥1): group brackets/labels at nesting level N-1
 *    (level 0 = top-level group, level 1 = first nested group, etc.)
 *
 * The layout computer ([StaffHeaderLayoutComputer]) uses the staff-range size to
 * determine column ordering independently of depth.
 */
object StaffHeaderComputer {

    fun compute(score: RuntimeScore): ComputedStaffHeader {
        val orderedStaffs = score.orderedStaffs()
        val staffIndexById: Map<TrackId, Int> =
            orderedStaffs.mapIndexed { idx, staff -> staff.id to idx }.toMap()

        val brackets = mutableListOf<ComputedStaffBracket>()
        val labels = mutableListOf<ComputedStaffLabel>()

        // Walk the group tree.  level=0 for top-level groups, +1 for each nesting layer.
        fun walk(group: RuntimeStaffGroup, level: Int) {
            val staffsInGroup = group.allStaffs()
            val indices = staffsInGroup.mapNotNull { staffIndexById[it.id] }
            if (indices.isEmpty()) return
            val range = StaffIndexRange(indices.min(), indices.max())

            // Per-staff labels from direct Staff members of this group
            for (member in group.members) {
                if (member is RuntimeStaffGroupMember.Staff) {
                    val idx = staffIndexById[member.staff.id] ?: continue
                    member.staff.staffLabel?.let { lbl ->
                        labels.add(ComputedStaffLabel(
                            text = lbl,
                            abbreviation = member.staff.staffLabelAbbreviation,
                            staffRange = StaffIndexRange(idx, idx),
                            depth = 0,
                            sourceId = member.staff.id.value
                        ))
                    }
                }
            }

            // Group bracket
            if (group.bracket != BracketStyle.NONE) {
                brackets.add(ComputedStaffBracket(
                    style = group.bracket,
                    staffRange = range,
                    depth = level + 1,
                    sourceId = group.id.value
                ))
            }

            // Group label
            group.label?.let { lbl ->
                labels.add(ComputedStaffLabel(
                    text = lbl,
                    abbreviation = group.abbreviation,
                    staffRange = range,
                    depth = level + 1,
                    sourceId = group.id.value
                ))
            }

            // Recurse into nested groups
            for (member in group.members) {
                if (member is RuntimeStaffGroupMember.Group) {
                    walk(member.group, level + 1)
                }
            }
        }

        for (g in score.staffGroups) walk(g, 0)

        val orchestration = score.orchestration
        if (orchestration != null) {
            score.instruments.forEach { instrument ->
                val players = orchestration.players.filter { player ->
                    player.instruments.any { it.id == instrument.id }
                }
                val numberById = players.mapIndexed { index, player -> player.id to index + 1 }.toMap()
                if (players.size <= 1) return@forEach
                instrument.staves.forEach staffLoop@{ staff ->
                    val staffIndex = staffIndexById[staff.id] ?: return@staffLoop
                    val numbers = orchestration.staffAssignments
                        .filter {
                            it.lineId == null &&
                                it.staffId == staff.id &&
                                it.onset <= com.mecon.api.primitive.TimeCode.ofMeasure(1)
                        }
                        .mapNotNull { numberById[it.playerId] }
                        .distinct()
                        .sorted()
                    if (numbers.isNotEmpty()) {
                        labels.add(
                            ComputedStaffLabel(
                                text = numbers.joinToString(","),
                                abbreviation = null,
                                staffRange = StaffIndexRange(staffIndex, staffIndex),
                                depth = 0,
                                sourceId = "players:${staff.id.value}",
                                placement = StaffLabelPlacement.BEFORE_BRACKETS,
                            )
                        )
                    }
                }
            }
        }

        val connectivity = computeBarlineConnectivity(score, staffIndexById)

        return ComputedStaffHeader(
            brackets = brackets,
            labels = labels,
            barlineConnectivity = connectivity
        )
    }

    /**
     * Resolve per-system barline connectivity.
     *
     * Two adjacent staves are connected iff they share a common ancestor group
     * where [RuntimeStaffGroup.barlineConnect] is true.
     */
    private fun computeBarlineConnectivity(
        score: RuntimeScore,
        staffIndexById: Map<TrackId, Int>
    ): List<StaffIndexRange> {
        val orderedStaffs = score.orderedStaffs()
        val total = orderedStaffs.size
        if (total == 0) return emptyList()

        val connected = BooleanArray(total - 1)
        for (i in 0 until total - 1) {
            val idA = orderedStaffs[i].id
            val idB = orderedStaffs[i + 1].id
            connected[i] = score.staffGroups.any { groupConnects(it, idA, idB) }
        }

        val ranges = mutableListOf<StaffIndexRange>()
        var start = 0
        for (i in 0 until total - 1) {
            if (!connected[i]) {
                ranges.add(StaffIndexRange(start, i))
                start = i + 1
            }
        }
        ranges.add(StaffIndexRange(start, total - 1))
        return ranges
    }

    private fun groupConnects(
        group: RuntimeStaffGroup,
        idA: TrackId,
        idB: TrackId
    ): Boolean {
        val staffIds = group.allStaffs().map { it.id }.toSet()
        if (idA in staffIds && idB in staffIds && group.barlineConnect) return true
        for (m in group.members) {
            if (m is RuntimeStaffGroupMember.Group && groupConnects(m.group, idA, idB)) return true
        }
        return false
    }
}
