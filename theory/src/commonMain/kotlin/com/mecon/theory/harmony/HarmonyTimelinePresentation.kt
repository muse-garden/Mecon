package com.mecon.theory.harmony

import com.mecon.api.primitive.Fraction
import com.mecon.theory.Chord
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey

/** A key-specific label set shared by editable and analysis harmony timelines. */
data class HarmonyTimelineReading(
    val key: ModulationKey,
    val functionalSymbol: String,
    val absoluteTones: List<String>,
    val relativeTones: List<String>,
)

/**
 * Canonical projection from an audible chord to the same catalog readings used by free practice.
 * Callers may retain all readings (pivot/ambiguous regions) or choose one for a compact surface.
 */
object HarmonyTimelineReadingProjector {
    fun readings(chord: Chord, keys: List<ModulationKey>): List<HarmonyTimelineReading> {
        val sounding = chord.pitchClasses.mapTo(linkedSetOf()) { it.value }
        return keys.distinct().flatMap { key ->
            ChordSelectionCatalog.choices(key)
                .filter { it.pitchClasses == sounding }
                .flatMap { choice ->
                    choice.interpretationSymbols.map { symbol ->
                        HarmonyTimelineReading(
                            key = key,
                            functionalSymbol = symbol,
                            absoluteTones = choice.absoluteTones,
                            relativeTones = choice.relativeTones,
                        )
                    }
                }
        }.distinctBy { it.key to it.functionalSymbol }
    }

    fun reading(choice: ChordSelectionChoice, key: ModulationKey): HarmonyTimelineReading =
        HarmonyTimelineReading(
            key = key,
            functionalSymbol = choice.functionalSymbol,
            absoluteTones = choice.absoluteTones,
            relativeTones = choice.relativeTones,
        )
}

/** A definite or ambiguous tonal interval on a linear whole-note axis. */
data class HarmonyTonalRange(
    val id: String,
    val start: Fraction,
    val end: Fraction?,
    val keys: List<ModulationKey>,
    val resolvedKey: ModulationKey? = keys.singleOrNull(),
    val derived: Boolean = false,
    val priority: Int = 0,
) {
    init {
        require(keys.isNotEmpty()) { "A tonal range requires at least one key" }
        require(end == null || end > start) { "A tonal range must be open-ended or non-empty" }
        require(resolvedKey == null || resolvedKey in keys) {
            "The resolved key must be one of the tonal-range candidates"
        }
    }

    fun contains(time: Fraction): Boolean = time >= start && (end == null || time < end)

    companion object {
        /**
         * Builds a range from *stored* interval data, returning null instead of throwing when the
         * interval does not survive.
         *
         * Plugin events outlive the measures they point at — `MeasureEditEngine` does not remap
         * plugin tracks — so a saved region can start past the current score end, and clipping it
         * to that end collapses it. A hand-edited or older document can likewise carry an empty
         * interval or a `resolvedKey` outside `keys`. Point annotations already fail safe here (an
         * unresolvable time simply yields no element); a range must do the same, because these
         * constructors run inside `AnnotationStaffProvider.layout`, where an exception takes down
         * the whole render frame rather than one stale marking.
         */
        fun clippedOrNull(
            id: String,
            start: Fraction,
            end: Fraction?,
            keys: List<ModulationKey>,
            resolvedKey: ModulationKey? = keys.singleOrNull(),
            derived: Boolean = false,
            priority: Int = 0,
        ): HarmonyTonalRange? {
            if (keys.isEmpty()) return null
            if (end != null && end <= start) return null
            return HarmonyTonalRange(
                id = id,
                start = start,
                end = end,
                // Keep the candidate keys rather than dropping the whole region: only the
                // continuation-after-resolution hint is lost.
                resolvedKey = resolvedKey?.takeIf { it in keys },
                keys = keys,
                derived = derived,
                priority = priority,
            )
        }
    }
}

/** Shared interval semantics for free-practice key lines and score-analysis tonal regions. */
object HarmonyTonalTimeline {
    fun keysAt(
        time: Fraction,
        ranges: List<HarmonyTonalRange>,
        defaultKey: ModulationKey,
    ): List<ModulationKey> {
        val active = ranges.asSequence()
            .filter { it.contains(time) }
            .maxWithOrNull(compareBy<HarmonyTonalRange>({ it.priority }, { it.start }))
        val continuation = ranges.asSequence()
            .filter { it.end != null && it.end <= time && it.resolvedKey != null }
            .maxWithOrNull(compareBy<HarmonyTonalRange>({ it.priority }, { it.end!! }))

        if (active != null && continuation != null) {
            return if (active.priority >= continuation.priority) {
                active.keys
            } else {
                listOf(continuation.resolvedKey!!)
            }
        }
        active?.let { return it.keys }
        continuation?.resolvedKey?.let { return listOf(it) }

        return listOf(defaultKey)
    }

    /** Packs overlapping tonal ranges into the minimum number of deterministic display lanes. */
    fun lanes(ranges: List<HarmonyTonalRange>, displayEnd: Fraction): List<Int> {
        val ordered = ranges.indices.sortedWith(
            compareBy<Int> { ranges[it].start }
                .thenByDescending { ranges[it].end ?: displayEnd }
                .thenBy { ranges[it].derived }
                .thenBy { ranges[it].id },
        )
        val laneEnds = mutableListOf<Fraction>()
        val result = MutableList(ranges.size) { 0 }
        ordered.forEach { index ->
            val range = ranges[index]
            val lane = laneEnds.indexOfFirst { it <= range.start }
                .takeIf { it >= 0 }
                ?: laneEnds.size.also { laneEnds += Fraction.ZERO }
            laneEnds[lane] = range.end ?: displayEnd
            result[index] = lane
        }
        return result
    }

    /** Coalesces touching or overlapping chord-owned readings with the same key. */
    fun coalesce(ranges: List<HarmonyTonalRange>): List<HarmonyTonalRange> =
        ranges.groupBy { it.keys.singleOrNull() }.flatMap { (key, grouped) ->
            if (key == null) return@flatMap grouped
            val ordered = grouped.sortedBy(HarmonyTonalRange::start)
            if (ordered.isEmpty()) return@flatMap emptyList()
            val merged = mutableListOf<HarmonyTonalRange>()
            var current = ordered.first()
            ordered.drop(1).forEach { next ->
                val currentEnd = current.end
                current = if (currentEnd == null || next.start <= currentEnd) {
                    current.copy(
                        end = when {
                            currentEnd == null || next.end == null -> null
                            else -> maxOf(currentEnd, next.end)
                        },
                    )
                } else {
                    merged += current
                    next
                }
            }
            merged += current
            merged
        }.sortedWith(compareBy(HarmonyTonalRange::start, { it.end ?: Fraction.ZERO }))
}

fun ModulationKey.timelineLabel(): String =
    displayName + if (mode == KeySignatureMode.MINOR) "m" else ""
