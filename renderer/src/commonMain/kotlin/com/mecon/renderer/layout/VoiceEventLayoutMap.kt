package com.mecon.renderer.layout

import com.mecon.api.computed.BeamGroupId
import com.mecon.api.primitive.EventId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Map of voice event layouts for a score.
 */
@Serializable
class VoiceEventLayoutMap(
    private val layouts: Map<EventId, VoiceEventLayout>,
    /**
     * Derived render index inherited by an incremental window patch. It is deliberately transient:
     * serialized layout snapshots rebuild it lazily, while live incremental frames structurally share
     * every beam group outside the edited measures.
     */
    @Transient private val inheritedBeamGroupIndex: Map<BeamGroupId, List<VoiceEventLayout>>? = null,
) {
    /** Lazily built index from beam group ID to sorted layouts */
    private val beamGroupIndex: Map<BeamGroupId, List<VoiceEventLayout>> by lazy {
        inheritedBeamGroupIndex ?: buildBeamGroupIndex(layouts.values)
    }

    /**
     * Get layout for an event.
     */
    operator fun get(eventId: EventId): VoiceEventLayout? = layouts[eventId]

    /**
     * Get all layouts.
     */
    fun all(): Collection<VoiceEventLayout> = layouts.values

    /**
     * Get all beam group IDs.
     */
    fun beamGroupIds(): Set<BeamGroupId> = beamGroupIndex.keys

    /**
     * Get layouts for a specific beam group.
     */
    fun forBeamGroup(groupId: BeamGroupId): List<VoiceEventLayout> =
        beamGroupIndex[groupId] ?: emptyList()

    val size: Int get() = layouts.size

    fun isEmpty(): Boolean = layouts.isEmpty()

    /**
     * Replace the layouts owned by [measures] while structurally sharing every untouched HAMT branch.
     * [oldByMeasure] is the cached ownership table, so removal never scans the whole layout map.
     */
    internal fun patchMeasures(
        measures: IntRange,
        oldByMeasure: Map<Int, List<VoiceEventLayout>>,
        replacements: Iterable<VoiceEventLayout>,
    ): VoiceEventLayoutMap {
        val replacementList = replacements.toList()
        val persistent = layouts as? PersistentMap<EventId, VoiceEventLayout>
            ?: layouts.toPersistentMap()
        val builder = persistent.builder()
        for (measure in measures) {
            for (layout in oldByMeasure[measure].orEmpty()) builder.remove(layout.eventId)
        }
        for (layout in replacementList) builder[layout.eventId] = layout

        // A beam group never crosses a barline, so replacing complete measure chunks means only groups
        // owned by those measures can change. Patch that small set instead of making the first beam pass
        // on the new frame scan/group/sort every voice layout in the score again.
        val invalidatedGroupIds = HashSet<BeamGroupId>()
        for (measure in measures) {
            for (layout in oldByMeasure[measure].orEmpty()) {
                layout.beamInfo?.groupId?.let(invalidatedGroupIds::add)
            }
        }
        val beamBuilder = (beamGroupIndex as? PersistentMap<BeamGroupId, List<VoiceEventLayout>>
            ?: beamGroupIndex.toPersistentMap()).builder()
        for (groupId in invalidatedGroupIds) beamBuilder.remove(groupId)
        for ((groupId, groupLayouts) in buildBeamGroupIndex(replacementList)) {
            beamBuilder[groupId] = groupLayouts
        }
        return VoiceEventLayoutMap(builder.build(), beamBuilder.build())
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is VoiceEventLayoutMap && layouts == other.layouts)

    override fun hashCode(): Int = layouts.hashCode()

    override fun toString(): String = "VoiceEventLayoutMap(size=${layouts.size})"

    companion object {
        val EMPTY = VoiceEventLayoutMap(emptyMap())

        fun fromList(layouts: List<VoiceEventLayout>): VoiceEventLayoutMap =
            VoiceEventLayoutMap(layouts.associateBy { it.eventId }.toPersistentMap())

        private fun buildBeamGroupIndex(
            layouts: Iterable<VoiceEventLayout>,
        ): Map<BeamGroupId, List<VoiceEventLayout>> = layouts
            .filter { it.beamInfo != null }
            .groupBy { it.beamInfo!!.groupId }
            .mapValues { (_, groupLayouts) -> groupLayouts.sortedBy { it.time } }
    }
}
