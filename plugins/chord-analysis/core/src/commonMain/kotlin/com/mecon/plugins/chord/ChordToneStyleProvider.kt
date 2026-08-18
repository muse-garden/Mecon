package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.plugin.NoteStyleProvider
import com.mecon.api.plugin.NoteStylePatch
import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor
import com.mecon.theory.NonChordToneType

object ChordToneStyleProvider : NoteStyleProvider {
    override val pluginTrackTypes: Set<String> = setOf(StorageChordEvent.TRACK_TYPE)

    /** Toggled by the chord analysis panel to show/hide per-notehead coloring. */
    var isEnabled: Boolean = false
    private var lastComputedEnabled: Boolean? = null

    private val chordToneColor = RenderColor.rgb(34, 197, 94)
    private val unclassifiedColor = RenderColor.rgb(239, 68, 68)

    /** UI 图例与渲染共用的稳定色板。 */
    val typeColors: Map<NonChordToneType, RenderColor> = mapOf(
        NonChordToneType.PASSING to RenderColor.rgb(14, 165, 233),
        NonChordToneType.NEIGHBOR to RenderColor.rgb(99, 102, 241),
        NonChordToneType.SUSPENSION to RenderColor.rgb(168, 85, 247),
        NonChordToneType.RETARDATION to RenderColor.rgb(217, 70, 239),
        NonChordToneType.APPOGGIATURA to RenderColor.rgb(236, 72, 153),
        NonChordToneType.ESCAPE to RenderColor.rgb(249, 115, 22),
        NonChordToneType.NEIGHBOR_GROUP to RenderColor.rgb(245, 158, 11),
        NonChordToneType.ANTICIPATION to RenderColor.rgb(20, 184, 166),
        NonChordToneType.SUSTAINED to RenderColor.rgb(6, 182, 212),
        NonChordToneType.PEDAL to RenderColor.rgb(132, 204, 22),
    )

    override fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride> {
        lastComputedEnabled = isEnabled
        if (!isEnabled) return emptyMap()
        val analysis = ChordToneAnalysis.computeDetailed(computedScore)
        if (analysis.isEmpty()) return emptyMap()
        return analysis.mapValues { (_, result) -> styleFor(result) }
    }

    override fun computeStylePatch(computedScore: ComputedScore): NoteStylePatch? {
        if (lastComputedEnabled != isEnabled) return null
        if (!isEnabled) return NoteStylePatch(emptyMap(), emptySet())
        val analysis = ChordToneAnalysis.computeDetailed(computedScore)
        val dirty = ChordToneAnalysis.lastChangedKeys
        return NoteStylePatch(
            upserts = dirty.mapNotNull { key -> analysis[key]?.let { key to styleFor(it) } }.toMap(),
            removes = dirty.filterTo(linkedSetOf()) { it !in analysis },
        )
    }

    private fun styleFor(result: ChordToneResult): StyleOverride = StyleOverride(
        fillColor = when {
            result.isChordTone -> chordToneColor
            result.type != null -> typeColors.getValue(result.type!!)
            else -> unclassifiedColor
        }
    )
}
