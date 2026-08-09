package com.mecon.input

data class CollectedChord(
    val startedAtNanos: Long,
    val notes: List<PerformanceInputEvent.NoteOn>,
)

/**
 * Fixed-window chord collection. The window never slides: latency is bounded by [windowNanos].
 * Call [flushExpired] from the controller's clock/timer and before accepting a later NoteOn.
 */
class ChordCollector(
    val windowNanos: Long = 60_000_000L,
) {
    init {
        require(windowNanos > 0L)
    }

    private val held = LinkedHashSet<InputKeyId>()
    private var startedAt: Long? = null
    private val pending = LinkedHashMap<InputKeyId, PerformanceInputEvent.NoteOn>()

    fun noteOn(event: PerformanceInputEvent.NoteOn): CollectedChord? {
        val expired = flushExpired(event.atNanos)
        val id = InputKeyId(event.sourceId, event.key)
        if (!held.add(id)) return expired

        if (startedAt == null) startedAt = event.atNanos
        pending.putIfAbsent(id, event)
        return expired
    }

    fun noteOff(event: PerformanceInputEvent.NoteOff) {
        held.remove(InputKeyId(event.sourceId, event.key))
    }

    fun flushExpired(nowNanos: Long): CollectedChord? {
        val start = startedAt ?: return null
        if (nowNanos - start < windowNanos) return null
        return flush()
    }

    fun flush(): CollectedChord? {
        val start = startedAt ?: return null
        val result = CollectedChord(start, pending.values.toList())
        startedAt = null
        pending.clear()
        return result.takeIf { it.notes.isNotEmpty() }
    }

    fun releaseSource(sourceId: String) {
        held.removeAll { it.sourceId == sourceId }
    }

    fun reset() {
        held.clear()
        startedAt = null
        pending.clear()
    }
}
