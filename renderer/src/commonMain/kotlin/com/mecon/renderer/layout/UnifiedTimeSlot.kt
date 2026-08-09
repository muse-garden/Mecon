package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.LayoutElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.geometry.StaffSpace
import kotlin.math.max
import kotlin.math.min
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A time slot containing all elements at the same time code.
 *
 * Elements are grouped by time code and processed together to ensure
 * proper vertical alignment and horizontal spacing.
 */
@Serializable
data class UnifiedTimeSlot(
    /** The time code for this slot */
    val time: TimeCode,
    /** All elements at this time code, sorted by priority then staffIndex */
    val events: List<LayoutElement>,
    /**
     * Computed X position for this time slot. Assigned by the proportional pass and
     * (when paginating) re-stretched by [SystemBreaker]. Immutable: each pass produces
     * a new slot via [copy] rather than mutating in place, so a cached layout's slot X
     * can be safely reused / shared without aliasing.
     */
    val x: StaffSpace = StaffSpace.ZERO,
    /**
     * System (line) this slot belongs to after line-breaking. Always 0 in
     * continuous (single-system) mode. Set by [SystemBreaker] when paginating.
     */
    val systemIndex: Int = 0
) {
    /**
     * Get elements for a specific staff.
     */
    fun eventsForStaff(staffIndex: Int): List<LayoutElement> =
        events.filter { it.staffIndex == staffIndex || it.staffIndex == -1 }

    /**
     * Get note elements only.
     */
    fun noteEvents(): List<NoteElement> =
        events.filterIsInstance<NoteElement>()

    /** Lazily built index from event ID to its [NoteElement] in this slot (O(1) lookup). */
    private val noteByEventId: Map<EventId, NoteElement> by lazy {
        events.filterIsInstance<NoteElement>().associateBy { it.eventId }
    }

    /**
     * Get the note element for a specific event in this slot, or null if absent.
     * Replaces the O(slot size) `events.filterIsInstance<NoteElement>().find { ... }` pattern.
     */
    fun noteByEvent(eventId: EventId): NoteElement? = noteByEventId[eventId]

    /**
     * Get barline elements only.
     */
    fun barlineEvents(): List<BarlineElement> =
        events.filterIsInstance<BarlineElement>()

    /**
     * Check if this slot contains a barline.
     */
    fun hasBarline(): Boolean = events.any { it is BarlineElement }

    /**
     * Check if this slot contains any notes.
     */
    fun hasNotes(): Boolean = events.any { it is NoteElement }

    /**
     * Minimum width required for all elements in this slot.
     */
    fun minimumWidth(): StaffSpace {
        if (events.isEmpty()) return StaffSpace.ZERO
        // For elements at the same time, we need the max width (they stack vertically)
        // But different priority elements need their own space
        val byPriority = events.groupBy { it.priority }
        return StaffSpace(byPriority.values.sumOf { group ->
            group.maxOf { it.minimumWidth.value }.toDouble()
        }.toFloat())
    }

    companion object {
        /**
         * Create a time slot from a list of elements.
         */
        fun fromEvents(time: TimeCode, events: List<LayoutElement>): UnifiedTimeSlot =
            UnifiedTimeSlot(
                time = time,
                events = events.sortedWith(compareBy({ it.priority }, { it.staffIndex }))
            )
    }
}

/**
 * Collection of all time slots for a system, sorted by time.
 */
@Serializable
data class UnifiedTimeSlotMap internal constructor(
    private val slots: List<UnifiedTimeSlot>,
    /** Precomputed bounds for a persistent slot patch; excluded from storage serialization. */
    @Transient private val seededMeasureBounds: Map<Int, SlotMeasureBounds>? = null,
) {
    // [seededMeasureBounds] is a derived cache, not part of this snapshot's value identity.
    override fun equals(other: Any?): Boolean =
        this === other || (other is UnifiedTimeSlotMap && slots == other.slots)

    override fun hashCode(): Int = slots.hashCode()

    override fun toString(): String = "UnifiedTimeSlotMap(slots=$slots)"

    /** Lazily built index from time code to slot (O(1) lookup). Slots are unique per time code. */
    private val byTime: Map<TimeCode, UnifiedTimeSlot> by lazy {
        slots.associateBy { it.time }
    }

    /**
     * Immutable measure transforms for this exact slot snapshot. Attachment extent queries consume the
     * same pre-break map more than once (tree patch + proportional index + justified index); retaining
     * this O(slots) reduction prevents each consumer from rebuilding an identical intermediate map.
     */
    internal val measureBounds: Map<Int, SlotMeasureBounds> by lazy {
        seededMeasureBounds ?: run {
            val result = HashMap<Int, SlotMeasureBounds>()
            for (slot in slots) {
                val measure = slot.time.measure
                val current = result[measure]
                result[measure] = if (current == null) {
                    SlotMeasureBounds(slot.x.value, slot.x.value, slot.systemIndex)
                } else {
                    SlotMeasureBounds(
                        min(current.minX, slot.x.value),
                        max(current.maxX, slot.x.value),
                        slot.systemIndex,
                    )
                }
            }
            result
        }
    }

    /**
     * Get time slot at a specific time.
     */
    fun atTime(time: TimeCode): UnifiedTimeSlot? =
        byTime[time]

    /** Last note-bearing slot strictly before [time] (skips barline-only slots). */
    fun lastNoteBefore(time: TimeCode): UnifiedTimeSlot? =
        slots.lastOrNull { it.time < time && it.hasNotes() }

    /**
     * Get all time slots in time order.
     */
    fun all(): List<UnifiedTimeSlot> = slots

    /**
     * Return a copy with every slot transformed (preserving order). Used to apply X
     * positions or system tags immutably — callers produce new [UnifiedTimeSlot]s via
     * [UnifiedTimeSlot.copy] instead of mutating the shared slot list in place.
     */
    internal fun mapSlots(transform: (UnifiedTimeSlot) -> UnifiedTimeSlot): UnifiedTimeSlotMap =
        UnifiedTimeSlotMap(slots.map(transform))

    /**
     * Return a copy whose slots are [newSlots]. The caller is responsible for keeping
     * them in time order (the contract of this map). Used by [SystemBreaker] to emit a
     * re-stretched, system-tagged snapshot without touching the input map.
     */
    internal fun withSlots(newSlots: List<UnifiedTimeSlot>): UnifiedTimeSlotMap =
        UnifiedTimeSlotMap(
            if (newSlots is PersistentList<UnifiedTimeSlot>) newSlots else newSlots.toPersistentList()
        )

    /** Patch a small set of slot indices while structurally sharing every untouched persistent-list node. */
    internal fun patchSlots(replacements: List<IndexedValue<UnifiedTimeSlot>>): UnifiedTimeSlotMap {
        if (replacements.isEmpty()) return this
        val persistent = (slots as? PersistentList<UnifiedTimeSlot>) ?: slots.toPersistentList()
        val builder = persistent.builder()
        for ((index, slot) in replacements) builder[index] = slot
        // The old snapshot's bounds have normally already been materialised by attachment placement.
        // Patch only measures represented by the complete affected-system replacement range.
        val boundsBuilder = measureBounds.toPersistentMap().builder()
        val affectedMeasures = replacements.mapTo(HashSet()) { it.value.time.measure }
        for (measure in affectedMeasures) boundsBuilder.remove(measure)
        for ((_, slot) in replacements) {
            val measure = slot.time.measure
            val current = boundsBuilder[measure]
            boundsBuilder[measure] = if (current == null) {
                SlotMeasureBounds(slot.x.value, slot.x.value, slot.systemIndex)
            } else SlotMeasureBounds(
                min(current.minX, slot.x.value),
                max(current.maxX, slot.x.value),
                slot.systemIndex,
            )
        }
        return UnifiedTimeSlotMap(builder.build(), boundsBuilder.build())
    }

    val size: Int get() = slots.size

    fun isEmpty(): Boolean = slots.isEmpty()

    companion object {
        val EMPTY = UnifiedTimeSlotMap(emptyList())

        /**
         * Build from a list of layout elements.
         */
        fun fromEvents(events: List<LayoutElement>): UnifiedTimeSlotMap {
            val grouped = events.groupBy { it.time }
            val slots = grouped.entries
                .sortedBy { it.key }
                .map { (time, eventsAtTime) ->
                    UnifiedTimeSlot.fromEvents(time, eventsAtTime)
                }
            return UnifiedTimeSlotMap(slots)
        }
    }
}

/** X transform of one measure inside an immutable [UnifiedTimeSlotMap] snapshot. */
internal data class SlotMeasureBounds(
    val minX: Float,
    val maxX: Float,
    val systemIndex: Int,
)
