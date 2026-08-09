package com.mecon.renderer.interaction

import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.renderer.render.RenderElementId

/** Mutable full-render builder. The immutable result is partitioned by render system. */
class SectionIndexBuilder {
    private val registrations = ArrayList<Pair<EventSection, RenderElementId>>()

    fun register(section: EventSection, elementId: RenderElementId) {
        registrations += section to elementId
    }

    fun build(): SectionIndex = SectionIndex.fromRegistrations(registrations)
}

/**
 * Immutable bidirectional section index with dense per-system element arrays.
 *
 * The drawing hot path decodes [RenderElementId.systemIndex], routes its integer generation, and
 * directly indexes the dense local-ordinal array. Forward queries use numeric [EventSectionId] keys
 * and merge the small number of system buckets. Incremental renders replace only buckets touched by
 * the edit; old results keep their old bucket arrays and remain lock-free readers.
 */
class SectionIndex private constructor(
    @PublishedApi internal val systemBuckets: List<SectionBucket?>,
    @PublishedApi internal val globalBucket: SectionBucket,
) {
    fun elementsFor(section: EventSection): RenderedElements = RenderedElements(buildList {
        globalBucket.elementsFor(section.id)?.let(::addAll)
        for (bucket in systemBuckets) bucket?.elementsFor(section.id)?.let(::addAll)
    })

    fun sectionsFor(elementId: RenderElementId): List<EventSection> =
        bucketFor(elementId)?.sectionsFor(elementId) ?: emptyList()

    inline fun <reified T : EventSection> allOfType(): List<T> = buildList {
        for (section in globalBucket.sections) if (section is T) add(section)
        for (bucket in systemBuckets) {
            if (bucket != null) for (section in bucket.sections) if (section is T) add(section)
        }
    }

    fun elementsForEvent(eventId: EventId): RenderedElements = RenderedElements(buildList {
        forEachSection { section, ids ->
            if (section is VoiceEventSection && section.event.id == eventId) addAll(ids)
        }
    }.distinct())

    fun elementsForSectionId(sectionId: EventSectionId): RenderedElements = RenderedElements(buildList {
        globalBucket.elementsFor(sectionId)?.let(::addAll)
        for (bucket in systemBuckets) bucket?.elementsFor(sectionId)?.let(::addAll)
    })

    /** O(1) hot lookup: decode bucket and index its dense local-ordinal array. */
    fun sectionIdsFor(elementId: RenderElementId): List<EventSectionId> =
        bucketFor(elementId)?.sectionIdsFor(elementId) ?: emptyList()

    fun elementsForPitchEventCascade(
        pitchEventId: EventId,
        runtimeScore: RuntimeScore,
    ): RenderedElements {
        val voiceEventIds = HashSet<EventId>()
        for ((_, voiceTrack) in runtimeScore.voiceTracks) {
            for (voiceEvent in voiceTrack.events) {
                if (voiceEvent.pitchEvent.id == pitchEventId) voiceEventIds += voiceEvent.id
            }
        }
        return RenderedElements(buildList {
            forEachSection { section, ids ->
                if (section is VoiceEventSection && section.event.id in voiceEventIds) addAll(ids)
            }
        }.distinct())
    }

    fun spliceWindow(
        removedElementIds: Set<RenderElementId>,
        added: List<Pair<EventSection, RenderElementId>>,
    ): SectionIndex = spliceWindowEntries(
        removedElementIds = removedElementIds,
        added = added,
        elementId = { it.second },
        sections = { listOf(it.first) },
    )

    /**
     * Replace complete system buckets. This is the paginated splice fast path: unaffected buckets are
     * retained by reference and affected systems are rebuilt from their regenerated rich elements.
     */
    internal fun <T> replaceSystems(
        affectedSystems: Set<Int>,
        added: Iterable<T>,
        elementId: (T) -> RenderElementId,
        sections: (T) -> Iterable<EventSection>,
    ): SectionIndex {
        if (affectedSystems.isEmpty()) return this
        val grouped = HashMap<Int, MutableList<Pair<EventSection, RenderElementId>>>()
        for (entry in added) {
            val id = elementId(entry)
            val systemIndex = id.systemIndex ?: continue
            if (systemIndex !in affectedSystems) continue
            val target = grouped.getOrPut(systemIndex) { ArrayList() }
            for (section in sections(entry)) target += section to id
        }
        val maxIndex = maxOf(systemBuckets.lastIndex, affectedSystems.maxOrNull() ?: -1)
        val next = ArrayList<SectionBucket?>(maxIndex + 1)
        for (index in 0..maxIndex) {
            next += if (index in affectedSystems) {
                SectionBucket.build(grouped[index].orEmpty())
            } else {
                systemBuckets.getOrNull(index)
            }
        }
        return SectionIndex(next, globalBucket)
    }

    /** Partial-system compatibility path used by continuous rendering; only touched buckets are copied. */
    internal fun <T> spliceWindowEntries(
        removedElementIds: Set<RenderElementId>,
        added: Iterable<T>,
        elementId: (T) -> RenderElementId,
        sections: (T) -> Iterable<EventSection>,
    ): SectionIndex {
        val addedList = added.toList()
        val touchedSystems = buildSet {
            removedElementIds.mapNotNullTo(this) { it.systemIndex }
            addedList.mapNotNullTo(this) { elementId(it).systemIndex }
        }
        val nextSystems = systemBuckets.toMutableList()
        for (systemIndex in touchedSystems) {
            while (nextSystems.size <= systemIndex) nextSystems += null
            val registrations = nextSystems[systemIndex]
                ?.registrationsExcluding(removedElementIds)
                ?.toMutableList() ?: ArrayList()
            for (entry in addedList) {
                val id = elementId(entry)
                if (id.systemIndex != systemIndex) continue
                for (section in sections(entry)) registrations += section to id
            }
            nextSystems[systemIndex] = SectionBucket.build(registrations)
        }

        val globalTouched = removedElementIds.any { it.systemIndex == null } ||
            addedList.any { elementId(it).systemIndex == null }
        val nextGlobal = if (!globalTouched) globalBucket else {
            val registrations = globalBucket.registrationsExcluding(removedElementIds).toMutableList()
            for (entry in addedList) {
                val id = elementId(entry)
                if (id.systemIndex != null) continue
                for (section in sections(entry)) registrations += section to id
            }
            SectionBucket.build(registrations)
        }
        return SectionIndex(nextSystems, nextGlobal)
    }

    private fun bucketFor(id: RenderElementId): SectionBucket? =
        id.systemIndex?.let(systemBuckets::getOrNull) ?: globalBucket

    private inline fun forEachSection(action: (EventSection, List<RenderElementId>) -> Unit) {
        globalBucket.forEachSection(action)
        for (bucket in systemBuckets) bucket?.forEachSection(action)
    }

    companion object {
        val EMPTY = SectionIndex(emptyList(), SectionBucket.EMPTY)

        internal fun fromRegistrations(
            registrations: Iterable<Pair<EventSection, RenderElementId>>,
        ): SectionIndex {
            val bySystem = HashMap<Int, MutableList<Pair<EventSection, RenderElementId>>>()
            val global = ArrayList<Pair<EventSection, RenderElementId>>()
            var maxSystem = -1
            for (registration in registrations) {
                val systemIndex = registration.second.systemIndex
                if (systemIndex == null) global += registration else {
                    bySystem.getOrPut(systemIndex) { ArrayList() } += registration
                    maxSystem = maxOf(maxSystem, systemIndex)
                }
            }
            return SectionIndex(
                systemBuckets = List(maxSystem + 1) { index ->
                    bySystem[index]?.let(SectionBucket::build)
                },
                globalBucket = SectionBucket.build(global),
            )
        }
    }
}

/** One immutable system bucket. Continuous splice generations route to dense reverse element arrays. */
@PublishedApi
internal class SectionBucket private constructor(
    @PublishedApi internal val sections: Array<EventSection>,
    @PublishedApi internal val elementsBySection: Array<List<RenderElementId>>,
    private val sectionOrdinals: SectionOrdinalTable,
    private val elementsByGeneration: Map<Int, ElementGenerationBucket>,
) {
    fun elementsFor(sectionId: EventSectionId): List<RenderElementId>? =
        sectionOrdinals[sectionId]?.let(elementsBySection::get)

    fun sectionsFor(elementId: RenderElementId): List<EventSection> =
        elementsByGeneration[elementId.generation]?.sectionsFor(elementId.localOrdinal) ?: emptyList()

    fun sectionIdsFor(elementId: RenderElementId): List<EventSectionId> =
        elementsByGeneration[elementId.generation]?.sectionIdsFor(elementId.localOrdinal) ?: emptyList()

    inline fun forEachSection(action: (EventSection, List<RenderElementId>) -> Unit) {
        for (index in sections.indices) action(sections[index], elementsBySection[index])
    }

    fun registrationsExcluding(removed: Set<RenderElementId>): List<Pair<EventSection, RenderElementId>> =
        buildList {
            forEachSection { section, ids ->
                for (id in ids) if (id !in removed) add(section to id)
            }
        }

    companion object {
        val EMPTY = SectionBucket(emptyArray(), emptyArray(), SectionOrdinalTable.EMPTY, emptyMap())

        fun build(registrations: Iterable<Pair<EventSection, RenderElementId>>): SectionBucket {
            val forward = LinkedHashMap<EventSectionId, Pair<EventSection, MutableList<RenderElementId>>>()
            val reverse = HashMap<Int, MutableMap<Int, MutableList<EventSection>>>()
            for ((section, id) in registrations) {
                val entry = forward.getOrPut(section.id) { section to ArrayList() }
                entry.second += id
                reverse.getOrPut(id.generation) { HashMap() }
                    .getOrPut(id.localOrdinal) { ArrayList() } += section
            }
            if (forward.isEmpty()) return EMPTY

            val sections = forward.values.map { it.first }.toTypedArray()
            val sectionIds = LongArray(sections.size) { index -> sections[index].id.value }
            val elementsBySection = forward.values.map { it.second.toList() }.toTypedArray()
            val ordinals = SectionOrdinalTable.build(sectionIds)
            val generations = reverse.mapValues { (_, elements) -> ElementGenerationBucket.build(elements) }
            return SectionBucket(sections, elementsBySection, ordinals, generations)
        }
    }
}

/** Immutable primitive long -> dense ordinal table used by cold forward section queries. */
internal class SectionOrdinalTable private constructor(
    private val keys: LongArray,
    private val ordinals: IntArray,
) {
    operator fun get(sectionId: EventSectionId): Int? {
        if (ordinals.isEmpty()) return null
        val mask = ordinals.lastIndex
        var slot = slotFor(sectionId.value, mask)
        while (true) {
            val ordinal = ordinals[slot]
            if (ordinal < 0) return null
            if (keys[slot] == sectionId.value) return ordinal
            slot = (slot + 1) and mask
        }
    }

    companion object {
        val EMPTY = SectionOrdinalTable(LongArray(0), IntArray(0))

        fun build(sectionIds: LongArray): SectionOrdinalTable {
            if (sectionIds.isEmpty()) return EMPTY
            var capacity = 1
            while (capacity < sectionIds.size * 2) capacity = capacity shl 1
            val keys = LongArray(capacity)
            val ordinals = IntArray(capacity) { -1 }
            val mask = capacity - 1
            for (ordinal in sectionIds.indices) {
                val key = sectionIds[ordinal]
                var slot = slotFor(key, mask)
                while (ordinals[slot] >= 0) {
                    check(keys[slot] != key) { "Duplicate EventSectionId $key" }
                    slot = (slot + 1) and mask
                }
                keys[slot] = key
                ordinals[slot] = ordinal
            }
            return SectionOrdinalTable(keys, ordinals)
        }

        private fun slotFor(key: Long, mask: Int): Int {
            var mixed = key
            mixed = mixed xor (mixed ushr 33)
            mixed *= -49064778989728563L
            mixed = mixed xor (mixed ushr 33)
            return mixed.toInt() and mask
        }
    }
}

/** Dense reverse arrays for one render generation within a system bucket. */
internal class ElementGenerationBucket private constructor(
    private val sectionsByElement: Array<List<EventSection>?>,
    private val sectionIdsByElement: Array<List<EventSectionId>?>,
) {
    fun sectionsFor(localOrdinal: Int): List<EventSection> =
        sectionsByElement.getOrNull(localOrdinal) ?: emptyList()

    fun sectionIdsFor(localOrdinal: Int): List<EventSectionId> =
        sectionIdsByElement.getOrNull(localOrdinal) ?: emptyList()

    companion object {
        fun build(elements: Map<Int, List<EventSection>>): ElementGenerationBucket {
            val maxOrdinal = elements.keys.maxOrNull() ?: -1
            val sections = arrayOfNulls<List<EventSection>>(maxOrdinal + 1)
            val sectionIds = arrayOfNulls<List<EventSectionId>>(maxOrdinal + 1)
            for ((localOrdinal, elementSections) in elements) {
                val immutableSections = elementSections.toList()
                sections[localOrdinal] = immutableSections
                sectionIds[localOrdinal] = immutableSections.mapTo(ArrayList()) { it.id }.distinct()
            }
            return ElementGenerationBucket(sections, sectionIds)
        }
    }
}
