package com.mecon.core.engine

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedSlur
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.IncrementalComputeResult
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack

/**
 * Incremental (local) recompute of a [ComputedScore] after a bounded edit.
 *
 * Strategy (see `docs/data_model/incremental-update.md`): no field-level dependency graph. Given the
 * edit interval, expand it to a small **measure window** (one measure back, plus beam/tuplet group
 * expansion handled inside [ComputeEngine.computeVoiceTrackRange]) and recompute only the changed
 * voice tracks' events within it, plus the implicit rests and per-track slurs the edit could touch.
 * Everything else — barlines/clefs/keys/time signatures, untouched events — is reused by reference.
 *
 * Structural edits (measure count / time- or key-signature / clef / staff-set changes) are **out of
 * scope** for this path (decision Q4): they degrade to a full [computeScore] and are reported with
 * [ComputeChangeSet.structureReflow] = true so the caller/renderer knows to reflow.
 *
 * @param previous     the previous computed score (its [ComputedScore.runtime] is the pre-edit runtime)
 * @param newRuntime   the runtime with the edit already applied (caller mutates runtime first; decision Q1)
 * @param editInterval the half-open `[start, end)` time span the edit touched. For a move, it must
 *                     cover both the source and destination positions.
 */
fun computeScoreIncremental(
    previous: ComputedScore,
    newRuntime: RuntimeScore,
    editInterval: TimeRange,
): IncrementalComputeResult {
    // Out-of-scope structural change → full recompute, reported as a reflow.
    if (isStructuralChange(previous.runtime, newRuntime)) {
        val full = computeScore(newRuntime)
        return IncrementalComputeResult(full, fullDiffChangeSet(previous, full))
    }

    // If the edit changes the score's measure count (append past the end, or empty the tail so the
    // final barline moves), the reused barlines would be wrong — fall back to full. Both sides use
    // the same cheap formula (declared measures vs. last event measure), so the comparison is exact.
    val previousMaxMeasure = scoreMaxMeasure(previous.runtime)
    val maxMeasure = scoreMaxMeasure(newRuntime)
    if (maxMeasure != previousMaxMeasure) {
        val full = computeScore(newRuntime)
        return IncrementalComputeResult(full, fullDiffChangeSet(previous, full))
    }

    val engine = ComputeEngine(newRuntime)

    // Window = [editStart.measure − 1, editEnd.measure], clamped to the score. BACK = 1 lets a
    // predecessor whose tie reaches into the edit be re-resolved; group expansion happens inside
    // computeVoiceTrackRange.
    val windowLo = maxOf(1, editInterval.start.measure - 1)
    val windowHi = minOf(maxMeasure, editInterval.end.measure)
    val window = windowLo..windowHi

    // Voice tracks whose reference changed. Pitch edits propagate to voice tracks via
    // RuntimeScore's hierarchy update, so a reference diff catches both pitch and voice edits.
    val changedTracks: List<Pair<RuntimeStaffTrack, RuntimeVoiceTrack>> =
        newRuntime.staffTracks.values.flatMap { staff ->
            staff.voiceTracks
                .filter { previous.runtime.voiceTracks[it.id] !== it }
                .map { staff to it }
        }

    if (changedTracks.isEmpty() || window.isEmpty()) {
        return IncrementalComputeResult(previous.copy(runtime = newRuntime), ComputeChangeSet.EMPTY)
    }

    // Persistent store: each put/remove copies only O(log N + k) nodes, sharing the rest with
    // `previous` — no whole-table copy. See docs/data_model/computed-event-store.md.
    var store = previous.computedEvents
    val added = HashSet<EventId>()
    val removed = HashSet<EventId>()
    val modified = HashSet<EventId>()
    var affectedLo = windowLo
    var affectedHi = windowHi

    for ((staff, voiceTrack) in changedTracks) {
        val recomputed = engine.computeVoiceTrackRange(staff, voiceTrack, window)
        val recomputedById = recomputed.associateBy { it.id }

        // The compute set may have grown past the requested window (group expansion); use its real
        // measure span so deletions are detected over exactly the region that was recomputed.
        val spanLo = recomputed.minOfOrNull { it.onset.measure } ?: windowLo
        val spanHi = recomputed.maxOfOrNull { it.onset.measure } ?: windowHi
        val expLo = minOf(windowLo, spanLo)
        val expHi = maxOf(windowHi, spanHi)
        affectedLo = minOf(affectedLo, expLo)
        affectedHi = maxOf(affectedHi, expHi)

        // Old events of this track inside the recompute span that no longer exist → removed.
        val oldTrack = previous.runtime.voiceTracks[voiceTrack.id]
        if (oldTrack != null) {
            val oldWindowEvents = oldTrack.events
                // Beat-zero grace notes sort before the measure-only lower bound.
                .range(
                    TimeCode.ofMeasure((expLo - 1).coerceAtLeast(0)),
                    TimeCode.ofMeasure(expHi + 1),
                )
                .filter { it.onset.measure in expLo..expHi }
            for (old in oldWindowEvents) {
                if (old.id !in recomputedById) {
                    store = store.remove(old.id)
                    removed.add(old.id)
                }
            }
        }

        // Insert / overwrite recomputed events, classifying add vs modify.
        for (ev in recomputed) {
            val old = previous.computedEvents[ev.id]
            store = store.put(ev)
            when {
                old == null -> added.add(ev.id)
                old != ev -> modified.add(ev.id)
            }
        }
    }

    val affectedWindow = affectedLo..affectedHi

    // Implicit whole-measure rests within the affected window: an edit can fill or empty a measure.
    val oldRestsInWindow = previous.computedEvents.values.filter {
        it.isRest && it.originVoiceTrackId != null && it.onset.measure in affectedWindow
    }
    val newRests = engine.computeImplicitRestsInWindow(affectedWindow, maxMeasure)
    val newRestById = newRests.associateBy { it.id }
    for (old in oldRestsInWindow) {
        if (old.id !in newRestById) {
            store = store.remove(old.id)
            removed.add(old.id)
        }
    }
    for (rest in newRests) {
        val old = previous.computedEvents[rest.id]
        store = store.put(rest)
        when {
            old == null -> added.add(rest.id)
            old != rest -> modified.add(rest.id)
        }
    }

    // Slurs: nesting is whole-track, so recompute the changed tracks' slurs in full and splice them
    // back. Untouched tracks keep their previous slurs by reference.
    val changedTrackIds = changedTracks.mapTo(HashSet()) { it.second.id }
    val keptSlurs = previous.slurs.filterNot { it.voiceTrackId in changedTrackIds }
    val recomputedSlurs: List<ComputedSlur> = changedTracks.flatMap { (_, voiceTrack) ->
        SlurResolver.computeForVoiceTrack(voiceTrack)
    }
    val newSlurs = keptSlurs + recomputedSlurs

    val newComputed = previous.copy(
        runtime = newRuntime,
        computedEvents = store,
        slurs = newSlurs,
    )

    val changeSet = ComputeChangeSet(
        addedEvents = added,
        removedEvents = removed,
        modifiedEvents = modified,
        affectedMeasures = affectedWindow,
        notationChanged = false,   // notation is structural → never changes on the incremental path
        structureReflow = false,
    )
    return IncrementalComputeResult(newComputed, changeSet)
}

/**
 * The score's measure count = max(highest declared measure, highest event measure). Cheap: each
 * track's last event (max onset) is its B+ tree tail, so this is O(tracks · log N), not O(N).
 */
private fun scoreMaxMeasure(score: RuntimeScore): Int {
    val declared = score.measures.maxOfOrNull { it.key } ?: 0
    val lastEvent = score.voiceTracks.values.maxOfOrNull { vt ->
        if (vt.events.isEmpty()) 0 else vt.events[vt.events.size - 1].onset.measure
    } ?: 0
    return maxOf(declared, lastEvent)
}

/**
 * Whether the edit changed anything beyond voice/pitch track events — i.e. something the incremental
 * window cannot capture: measure structure, default key/time, staff set, or per-staff clef / key /
 * transposition / clef-changes / attachments. Such edits fall back to a full recompute.
 */
private fun isStructuralChange(old: RuntimeScore, new: RuntimeScore): Boolean {
    // Global performance marks (fermata and global breath) can affect every voice/staff,
    // so the local voice-event splice is not sufficient.
    if (old.globalTrack != new.globalTrack) return true
    if (old.defaultTimeSignature != new.defaultTimeSignature) return true
    if (old.showTimeSignatures != new.showTimeSignatures) return true
    if (old.defaultKeySignature != new.defaultKeySignature) return true
    if (old.staffTracks.keys != new.staffTracks.keys) return true
    if (old.voiceTracks.keys != new.voiceTracks.keys) return true
    if (old.pitchTracks.keys != new.pitchTracks.keys) return true

    // Measure structure: count + per-measure time/key signature.
    val oldMeasures = old.measures.associate { it.key to it.value }
    val newMeasures = new.measures.associate { it.key to it.value }
    if (oldMeasures.keys != newMeasures.keys) return true
    for ((number, oldM) in oldMeasures) {
        val newM = newMeasures.getValue(number)
        if (oldM.timeSignature != newM.timeSignature) return true
        if (oldM.keySignature != newM.keySignature) return true
    }

    // Per-staff fields that affect staff position / notation / accidental context.
    for ((id, oldStaff) in old.staffTracks) {
        val newStaff = new.staffTracks.getValue(id)
        if (oldStaff.clef != newStaff.clef) return true
        if (oldStaff.keySignature != newStaff.keySignature) return true
        if (oldStaff.transposition != newStaff.transposition) return true
        if (oldStaff.clefChanges != newStaff.clefChanges) return true
        if (oldStaff.attachments != newStaff.attachments) return true
    }
    return false
}

/**
 * Build a [ComputeChangeSet] by diffing a freshly fully-recomputed score against the previous one.
 * Used on the structural fallback; O(N) but only on that path.
 */
private fun fullDiffChangeSet(previous: ComputedScore, full: ComputedScore): ComputeChangeSet {
    val oldEvents = previous.computedEvents
    val newEvents = full.computedEvents
    val oldKeys = oldEvents.ids.toHashSet()
    val newKeys = newEvents.ids.toHashSet()
    val added = newKeys - oldKeys
    val removed = oldKeys - newKeys
    val modified = (oldKeys intersect newKeys)
        .filterTo(HashSet()) { oldEvents.getValue(it) != newEvents.getValue(it) }
    val maxMeasure = full.barlines.maxOfOrNull { it.measureNumber } ?: 1
    return ComputeChangeSet(
        addedEvents = added,
        removedEvents = removed,
        modifiedEvents = modified,
        affectedMeasures = 1..maxMeasure,
        notationChanged = true,
        structureReflow = true,
    )
}
