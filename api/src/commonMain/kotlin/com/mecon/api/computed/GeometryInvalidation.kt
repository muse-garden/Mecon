package com.mecon.api.computed

import com.mecon.api.primitive.EventId
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.events.StaffAttachmentPlacement

/**
 * How an edit partitions a persisted [ScoreGeometry] overlay (Phase 2 of
 * `docs/data_model/incremental-update.md` §10).
 *
 * A stored entry is **anchor-relative**, so on most edits it merely *follows*
 * its notes: the renderer re-anchors it to the moved notehead / slot and the
 * stored offsets + shape still hold ("auto-adjust", reuse by reference). An
 * entry is **stale** only when the edit could change its *auto-layout* — then it
 * must be recomputed from scratch (slur reshape, articulation re-anchor on a
 * pitch change).
 *
 *  - [staleArticulations] / [staleSlurs] / [staleAttachments] / [staleBeams]: entries the
 *    renderer must recompute.
 *  - [reusableArticulations] / [reusableSlurs] / [reusableAttachments] / [reusableBeams]: entries
 *    kept by reference.
 *  - [full]: the edit changed notation / measure structure — the overlay can't
 *    be patched incrementally and must be recaptured wholesale.
 *
 * New symbols (added events with articulations, a newly authored slur) are in
 * *neither* set: they are absent from [previous] and the consumer captures them
 * fresh while folding the new overlay.
 */
data class GeometryInvalidation(
    val staleArticulations: Set<EventId>,
    val staleSlurs: Set<EventId>,
    val reusableArticulations: Set<EventId>,
    val reusableSlurs: Set<EventId>,
    val staleAttachments: Set<EventId> = emptySet(),
    val reusableAttachments: Set<EventId> = emptySet(),
    val staleBeams: Set<String> = emptySet(),
    val reusableBeams: Set<String> = emptySet(),
    val staleTies: Set<EventId> = emptySet(),
    val reusableTies: Set<EventId> = emptySet(),
    /** Stored slurs that no longer exist after the edit; patch consumers remove these by id. */
    val removedSlurs: Set<EventId> = emptySet(),
    val full: Boolean = false,
) {
    /** True when nothing is stale and a full recapture is not forced. */
    val isEmpty: Boolean
        get() = !full && staleArticulations.isEmpty() && staleSlurs.isEmpty() &&
            staleAttachments.isEmpty() && staleBeams.isEmpty() && staleTies.isEmpty()

    companion object {
        /** Force a whole-overlay recapture (notation / structure change). */
        val FULL = GeometryInvalidation(emptySet(), emptySet(), emptySet(), emptySet(), full = true)
    }
}

/**
 * Classify every entry of the [previous] overlay against an edit described by
 * [changeSet] over the freshly merged [edited] computed score.
 *
 * **Rules** (each conservative — a stale classification is always safe, a wrong
 * reusable one is not, so the bounds err toward staleness):
 *  - Any notation / measure-structure change ⇒ [GeometryInvalidation.FULL].
 *  - **Articulation** auto-layout depends only on its own event (notehead
 *    positions, stem side, the mark list, slot X) — never on neighbours. So an
 *    entry is stale iff its event was added / modified; otherwise it follows its
 *    anchor (slot X + staff midline) by reference.
 *  - **Slur** auto-layout depends on its endpoint anchors *and* on every
 *    intervening obstacle and the measure widths inside its span. So an entry is
 *    stale iff an endpoint event was touched, or the slur's measure span
 *    intersects [ComputeChangeSet.affectedMeasures] (an intervening note moved,
 *    a mark changed, or a measure was re-stretched). Edits outside the span only
 *    translate the slur rigidly — the stored anchor-relative shape still holds.
 *  - **Attachment** (hairpin / 8va / 8vb) auto-layout likewise avoids the
 *    elements inside its span, so a span entry is stale iff its measure span
 *    intersects [ComputeChangeSet.affectedMeasures]; a point dynamic is stale iff
 *    its own measure is affected. Additionally, attachments **stack vertically**
 *    on the same staff + side (8va sits *outside* a hairpin), so a stale inner
 *    symbol bumps every overlapping outer one: staleness **cascades** outward to
 *    any attachment whose span overlaps a stale one on the same staff + placement.
 *    They are then recomputed together (inner before outer) by the layout pass.
 *  - **Beam** automatic captures are invalidated by touched group events. User-adjusted geometry becomes
 *    stale only when the group's event membership/range changes; pitch or position edits within the same
 *    group keep it. The renderer performs the post-layout clearance check and falls back to automatic beam
 *    placement only if a note approaches/crosses it.
 */
fun analyzeGeometryInvalidation(
    previous: ScoreGeometry,
    edited: ComputedScore,
    changeSet: ComputeChangeSet,
    previousComputed: ComputedScore? = null,
): GeometryInvalidation {
    if (changeSet.notationChanged || changeSet.structureReflow) return GeometryInvalidation.FULL
    if (previous.isEmpty) return GeometryInvalidation(emptySet(), emptySet(), emptySet(), emptySet())

    val touched = changeSet.touchedEvents
    val affected = changeSet.affectedMeasures

    val staleArt = LinkedHashSet<EventId>()
    val reuseArt = LinkedHashSet<EventId>()
    for (id in previous.articulations.keys) {
        if (id in touched) staleArt += id else reuseArt += id
    }

    val staleSlur = LinkedHashSet<EventId>()
    val reuseSlur = LinkedHashSet<EventId>()
    val removedSlur = LinkedHashSet<EventId>()
    val slurSpanById = edited.slurs.associateBy { it.slurId }
    for (id in previous.slurs.keys) {
        val slur = slurSpanById[id]
        // Slur gone from the edited score (its endpoint was deleted): not stale,
        // not reusable — the consumer simply drops it.
        if (slur == null) {
            removedSlur += id
            continue
        }
        val stale = slur.startEventId in touched ||
            slur.endEventId in touched ||
            slurSpanIntersects(slur, edited, affected)
        if (stale) staleSlur += id else reuseSlur += id
    }

    val staleTie = LinkedHashSet<EventId>()
    // A tie resolves only to the next event / next-measure opening event. Limit invalidation
    // candidates to the affected window plus its preceding measure; never scan all computed events.
    val tieCandidateIds = LinkedHashSet<EventId>()
    if (!affected.isEmpty()) {
        val candidateRange = maxOf(1, affected.first - 1)..affected.last
        edited.eventsInMeasureRange(candidateRange).forEach { event ->
            if (event.id in previous.ties) tieCandidateIds += event.id
        }
        previousComputed?.eventsInMeasureRange(candidateRange)?.forEach { event ->
            if (event.id in previous.ties) tieCandidateIds += event.id
        }
    }
    touched.forEach { if (it in previous.ties) tieCandidateIds += it }
    for (sourceId in tieCandidateIds) {
        val geometries = previous.ties[sourceId] ?: continue
        val source = edited.getComputedEvent(sourceId) ?: continue
        val stale = geometries.any { geometry ->
            if (geometry.manuallyAdjusted) return@any false
            val targetId = source.pitchData.getOrNull(geometry.sourcePitchIndex)
                ?.tieTarget?.targetEventId
            sourceId in touched ||
                (targetId != null && targetId in touched) ||
                tieSpanIntersects(sourceId, targetId, edited, affected)
        }
        if (stale) staleTie += sourceId
    }

    val (staleAtt, reuseAtt) = analyzeAttachments(previous, edited, affected)

    val oldBeamMembers = previousComputed?.getBeamGroups()?.entries?.associate { (groupId, events) ->
        groupId.value to events.mapTo(LinkedHashSet()) { it.id }
    }.orEmpty()
    val newBeamMembers = edited.getBeamGroups().entries.associate { (groupId, events) ->
        groupId.value to events.mapTo(LinkedHashSet()) { it.id }
    }
    val touchedBeamIds = LinkedHashSet<String>()
    for (eventId in touched) {
        previousComputed?.getComputedEvent(eventId)?.beamInfo?.groupId?.value?.let(touchedBeamIds::add)
        edited.getComputedEvent(eventId)?.beamInfo?.groupId?.value?.let(touchedBeamIds::add)
    }
    val staleBeam = LinkedHashSet<String>()
    val reuseBeam = LinkedHashSet<String>()
    for ((groupId, geometry) in previous.beams) {
        val newMembers = newBeamMembers[groupId] ?: continue // group was removed: drop it
        val oldMembers = oldBeamMembers[groupId]
        val membershipChanged = oldMembers == null || oldMembers != newMembers
        val stale = if (geometry.manuallyAdjusted) membershipChanged else groupId in touchedBeamIds
        if (stale) staleBeam += groupId else reuseBeam += groupId
    }

    return GeometryInvalidation(
        staleArticulations = staleArt,
        staleSlurs = staleSlur,
        reusableArticulations = reuseArt,
        reusableSlurs = reuseSlur,
        staleAttachments = staleAtt,
        reusableAttachments = reuseAtt,
        staleBeams = staleBeam,
        reusableBeams = reuseBeam,
        staleTies = staleTie,
        reusableTies = emptySet(),
        removedSlurs = removedSlur,
    )
}

/** Span of one attachment on its staff, in measures (inclusive). */
private class AttachmentSpan(
    val staffIndex: Int,
    val placement: StaffAttachmentPlacement,
    val firstMeasure: Int,
    val lastMeasure: Int,
)

private fun ComputedStaffAttachment.span(): AttachmentSpan {
    val first = time.measure
    val last = when (this) {
        is ComputedHairpin -> endTime.measure
        is ComputedOctaveShift -> endTime.measure
        is ComputedOrnamentMark -> endTime?.measure ?: time.measure
        is ComputedTempoKeyframe -> if (isGradual) nextTime?.measure ?: time.measure else time.measure
        else -> first
    }
    return AttachmentSpan(staffIndex, placement, minOf(first, last), maxOf(first, last))
}

private fun AttachmentSpan.overlapsMeasures(affected: IntRange): Boolean =
    !affected.isEmpty() && firstMeasure <= affected.last && affected.first <= lastMeasure

private fun AttachmentSpan.stacksWith(other: AttachmentSpan): Boolean =
    staffIndex == other.staffIndex && placement == other.placement &&
        firstMeasure <= other.lastMeasure && other.firstMeasure <= lastMeasure

/**
 * Classify persisted attachment entries: directly stale when their span overlaps
 * the recompute window, then cascade outward through vertically-stacked siblings
 * (an 8va bracket sitting outside a reshaped hairpin must move with it).
 */
private fun analyzeAttachments(
    previous: ScoreGeometry,
    edited: ComputedScore,
    affected: IntRange,
): Pair<Set<EventId>, Set<EventId>> {
    if (previous.attachments.isEmpty()) return emptySet<EventId>() to emptySet()

    // Spans of the attachments still present in the edited score.
    val spanById = HashMap<EventId, AttachmentSpan>()
    for (a in edited.staffAttachments) spanById[a.id] = a.span()
    // Ids carried by the overlay that survive in the edited score (others were deleted).
    val present = previous.attachments.keys.filter { it in spanById }

    val stale = LinkedHashSet<EventId>()
    for (id in present) {
        val manualY = previous.attachments.getValue(id).manuallyAdjustedY
        if (!manualY && spanById.getValue(id).overlapsMeasures(affected)) stale += id
    }

    // Cascade: any present attachment overlapping a stale one on the same staff +
    // side is bumped too. Fixpoint, since stacking is transitive across a cluster.
    var changed = true
    while (changed) {
        changed = false
        for (id in present) {
            if (id in stale) continue
            if (previous.attachments.getValue(id).manuallyAdjustedY) continue
            val span = spanById.getValue(id)
            if (stale.any { spanById.getValue(it).stacksWith(span) }) {
                stale += id
                changed = true
            }
        }
    }

    val reuse = present.filterTo(LinkedHashSet()) { it !in stale }
    return stale to reuse
}

/** Whether the slur's measure span overlaps the recompute window [affected]. */
private fun slurSpanIntersects(slur: ComputedSlur, edited: ComputedScore, affected: IntRange): Boolean {
    if (affected.isEmpty()) return false
    val startMeasure = edited.getComputedEvent(slur.startEventId)?.measurePosition?.measure
    val endMeasure = edited.getComputedEvent(slur.endEventId)?.measurePosition?.measure
    // Endpoint missing — be conservative and treat as stale.
    if (startMeasure == null || endMeasure == null) return true
    val spanFirst = minOf(startMeasure, endMeasure)
    val spanLast = maxOf(startMeasure, endMeasure)
    return spanFirst <= affected.last && affected.first <= spanLast
}

private fun tieSpanIntersects(
    sourceId: EventId,
    targetId: EventId?,
    edited: ComputedScore,
    affected: IntRange,
): Boolean {
    if (affected.isEmpty()) return false
    val sourceMeasure = edited.getComputedEvent(sourceId)?.measurePosition?.measure ?: return true
    val targetMeasure = targetId?.let { edited.getComputedEvent(it)?.measurePosition?.measure } ?: sourceMeasure
    return minOf(sourceMeasure, targetMeasure) <= affected.last &&
        affected.first <= maxOf(sourceMeasure, targetMeasure)
}
