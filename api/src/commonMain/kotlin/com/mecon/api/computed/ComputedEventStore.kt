package com.mecon.api.computed

import com.mecon.api.collection.BPlusTree
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.TimeIndexedList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * Persistent store for [ComputedVoiceEvent]s, backing [ComputedScore.computedEvents].
 *
 * Replaces a plain `Map<EventId, ComputedVoiceEvent>`. Two persistent B+ tree indexes are kept in
 * sync so both access patterns stay O(log N) **and** an edit copies only O(log N + k) nodes instead
 * of the whole table (see `docs/data_model/computed-event-store.md`):
 *
 * - [byMeasure] — measure-keyed [TimeIndexedList]; powers [range] / [inMeasure] and onset-ordered
 *   [values] iteration.
 * - [byId] — [EventId]-keyed [BPlusTree]; powers O(log N) point lookup ([get] / [getValue]).
 *
 * Equality is **by content** (the `id → event` mapping), independent of tree shape or insertion
 * history — required because [ComputedScore] is a `data class` compared field-by-field in the
 * incremental golden-rule tests.
 */
/**
 * Outcome of [ComputedEventStore.diffFrom]: the added / removed / modified event ids and the inclusive
 * measure span they touch ([IntRange.EMPTY] when nothing changed).
 */
data class EventStoreDiff(
    val added: Set<EventId>,
    val removed: Set<EventId>,
    val modified: Set<EventId>,
    val affectedMeasures: IntRange,
)

class ComputedEventStore private constructor(
    private val byMeasure: TimeIndexedList<ComputedVoiceEvent>,
    private val byId: BPlusTree<EventId, ComputedVoiceEvent, Int>,
    private val tieSourceIds: PersistentSet<EventId>,
) {
    val size: Int get() = byId.size

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size > 0

    /** Point lookup by id, O(log N). */
    operator fun get(id: EventId): ComputedVoiceEvent? = byId.get(id)

    /** Point lookup by id; throws if absent (mirrors `Map.getValue`). */
    fun getValue(id: EventId): ComputedVoiceEvent =
        byId.get(id) ?: throw NoSuchElementException("No computed event for id $id")

    operator fun contains(id: EventId): Boolean = byId.get(id) != null

    /** All events in onset order. */
    val values: List<ComputedVoiceEvent> get() = byMeasure.toList()

    /**
     * The persistent measure-keyed [TimeIndexedList] backing this store. Exposed so consumers can align /
     * diff against it directly (e.g. plugin chord-tone analysis): two stores derived from one another by an
     * incremental edit share all but the edited measures, so [TimeIndexedList.changedSpan] over this is
     * O(changes · log N). Do **not** rebuild a fresh list from [values] for that purpose — it loses the
     * structural sharing the diff relies on.
     */
    fun asTimeIndexedList(): TimeIndexedList<ComputedVoiceEvent> = byMeasure

    /** All ids (onset order). */
    val ids: List<EventId> get() = byMeasure.toList().map { it.id }

    /** Tie source ids maintained with [put]/[remove], so tie layout never scans all events to discover them. */
    val tieSourceEventIds: Set<EventId> get() = tieSourceIds

    /**
     * Insert or overwrite [event] (keyed by [ComputedVoiceEvent.id]), returning a new store.
     *
     * If an event with the same id exists it is first removed from [byMeasure] by identity (the old
     * instance comes from [byId], so `===` removal is exact); this correctly handles same-onset edits
     * (pitch change) and onset-changing edits (a move) alike.
     */
    fun put(event: ComputedVoiceEvent): ComputedEventStore {
        val old = byId.get(event.id)
        // Value-equal re-put → no-op. This is both an optimisation and a *correctness* invariant: the two
        // indexes must always hold the **same instance** for an id, because [byMeasure] removal below is by
        // identity (`===`, the old instance comes from [byId]). If we proceeded, `byId.put` would return
        // the tree Unchanged (keeping the old instance) while `byMeasure` took the new one — the indexes
        // would diverge, and a later re-put's identity-based `byMeasure.remove` would miss, duplicating the
        // event in [byMeasure]. Chained incremental recomputes (overlapping BACK=1 windows) re-put many
        // unchanged events, so this path is hot.
        if (old != null && old == event) return this
        val newByMeasure = (if (old != null) byMeasure.remove(old) else byMeasure).insert(event)
        val newTieSourceIds = when {
            event.hasTies -> tieSourceIds.add(event.id)
            old?.hasTies == true -> tieSourceIds.remove(event.id)
            else -> tieSourceIds
        }
        return ComputedEventStore(newByMeasure, byId.put(event.id, event), newTieSourceIds)
    }

    /** Insert/overwrite all of [events], returning a new store. */
    fun putAll(events: Iterable<ComputedVoiceEvent>): ComputedEventStore {
        var store = this
        for (e in events) store = store.put(e)
        return store
    }

    /** Remove the event with [id] if present, returning a new store. */
    fun remove(id: EventId): ComputedEventStore {
        val old = byId.get(id) ?: return this
        return ComputedEventStore(
            byMeasure.remove(old),
            byId.remove(id),
            if (old.hasTies) tieSourceIds.remove(id) else tieSourceIds,
        )
    }

    /**
     * Structurally diff from [previous] (old) to this (new), reporting which events were added, removed
     * or modified and the inclusive measure span they touch. Backed by [byId]'s persistent-tree diff
     * ([BPlusTree.diff]): two stores derived from one another share all but the edited paths, so the cost
     * is O(changes · log N), not O(N) — see `docs/data_model/computed-event-store.md`.
     *
     * The measure span uses **both** old and new onsets (a moved note covers its source and destination
     * measure), and is [IntRange.EMPTY] when nothing changed. This is exactly the recompute window the
     * renderer needs to re-solve and the rest to translate (see
     * docs/data_model/incremental-update.md and docs/renderer/incremental-rendering.md).
     */
    fun diffFrom(previous: ComputedEventStore): EventStoreDiff {
        val added = HashSet<EventId>()
        val removed = HashSet<EventId>()
        val modified = HashSet<EventId>()
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        fun touch(measure: Int) {
            if (measure < lo) lo = measure
            if (measure > hi) hi = measure
        }
        previous.byId.diff(
            newer = byId,
            onRemoved = { id, ev -> removed.add(id); touch(ev.onset.measure) },
            onAdded = { id, ev -> added.add(id); touch(ev.onset.measure) },
            onChanged = { id, oldEv, newEv -> modified.add(id); touch(oldEv.onset.measure); touch(newEv.onset.measure) },
        )
        return EventStoreDiff(added, removed, modified, if (lo <= hi) lo..hi else IntRange.EMPTY)
    }

    /** Events whose onset lies in `[start, end]` (inclusive), onset order. */
    fun range(start: TimeCode, end: TimeCode): List<ComputedVoiceEvent> = byMeasure.range(start, end)

    /** Events with `onset.measure == measure`, onset order. */
    fun inMeasure(measure: Int): List<ComputedVoiceEvent> =
        byMeasure.range(TimeCode.ofMeasure(measure), TimeCode.ofMeasure(measure + 1))
            .filter { it.onset.measure == measure }

    /** Content-based equality: same `id → event` mapping, regardless of tree shape. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComputedEventStore) return false
        if (size != other.size) return false
        for (e in byMeasure) {
            if (other.byId.get(e.id) != e) return false
        }
        return true
    }

    override fun hashCode(): Int {
        // Order-independent: sum of per-entry hashes so equal content → equal hash.
        var acc = 0
        for (e in byMeasure) acc += (e.id.hashCode() * 31 + e.hashCode())
        return acc
    }

    override fun toString(): String = "ComputedEventStore(size=$size)"

    companion object {
        private fun emptyById(): BPlusTree<EventId, ComputedVoiceEvent, Int> =
            BPlusTree(aggregator = null)

        val EMPTY: ComputedEventStore =
            ComputedEventStore(TimeIndexedList.empty(), emptyById(), persistentSetOf())

        fun of(events: Iterable<ComputedVoiceEvent>): ComputedEventStore {
            var byId = emptyById()
            var tieSourceIds = persistentSetOf<EventId>()
            for (e in events) {
                byId = byId.put(e.id, e)
                if (e.hasTies) tieSourceIds = tieSourceIds.add(e.id)
            }
            return ComputedEventStore(TimeIndexedList.of(events), byId, tieSourceIds)
        }
    }
}
