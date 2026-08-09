package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.ArticulationPlacement
import com.mecon.api.storage.ScoreGeometry
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.ArticulationLayout
import com.mecon.renderer.layout.PlacedArticulation
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.smufl.BravuraFont

/**
 * Resolves the [Articulation]s carried by each [ComputedVoiceEvent] into placed
 * glyphs ([ArticulationLayout]) using post-layout notehead / stem positions.
 *
 * Division of responsibility (see AGENTS.md): the Computed layer decided *which*
 * marks exist; this renderer-side computer decides **side, stacking order and
 * coordinates** — pure typography.
 *
 * Placement policy:
 *  - **Notehead side (default, [ArticulationPlacement.AUTO]/[ArticulationPlacement.NOTEHEAD])**:
 *    opposite the stem (stem-up → below, stem-down → above; no stem → below).
 *    Pushed just outside the staff when the note sits inside it.
 *  - **Stem side ([ArticulationPlacement.STEM])**: at the stem-tip end.
 *  - Multiple marks stack outward in a fixed order: staccato/spiccato/tenuto
 *    nearest the note, then accent, then marcato.
 *
 * Mirrors [SlurLayoutComputer] in how it resolves an event's layout environment.
 */
internal class ArticulationLayoutComputer(
    private val config: RenderLayoutConfig
) {

    /**
     * Outermost staff line offsets from the centre, in staff spaces. A 5-line
     * staff has lines at relativeY -2, -1, 0, 1, 2 (one staff space apart).
     */
    private val staffLineMin = -2
    private val staffLineMax = 2

    /**
     * If a glyph centred at [centerY] (with half-height [halfH]) would overlap a
     * staff line, nudge it outward (in the [sign] direction) just enough to clear
     * the line by [RenderLayoutConfig.articulationLineClearance]. Marks outside
     * the staff are untouched, so they stay hugging the note.
     */
    private fun avoidStaffLine(centerY: Float, halfH: Float, sign: Float): Float {
        val margin = config.articulationLineClearance.value
        var y = centerY
        for (line in staffLineMin..staffLineMax) {
            val lineY = line.toFloat()
            if (lineY in (y - halfH - margin)..(y + halfH + margin)) {
                // Line intrudes into the glyph's vertical extent: push the glyph
                // to the outward side so its note-facing edge clears the line.
                y = lineY + sign * (halfH + margin)
            }
        }
        return y
    }

    context(BravuraFont)
    fun computeArticulationLayouts(
        computedScore: ComputedScore,
        query: LayoutQuery,
        geometry: ScoreGeometry? = null,
        measureFilter: IntRange? = null,
    ): Map<EventId, ArticulationLayout> {
        val result = LinkedHashMap<EventId, ArticulationLayout>()
        // Windowed passes (paginated splice / live-geometry fold) range-query only the affected measures
        // (already onset-ordered, matching allEventsSorted); the full pass scans every event. The per-event
        // guard below stays for the range query's inclusive-end overscan.
        val events = if (measureFilter != null) computedScore.eventsInMeasureRange(measureFilter)
        else computedScore.allEventsSorted()
        for (event in events) {
            if (measureFilter != null && event.onset.measure !in measureFilter) continue
            if (event.isRest || (event.articulations.isEmpty() && event.fermata == null)) continue
            // Persisted geometry (when present) is the source of truth; fall back
            // to auto layout when there is no stored entry or it can't resolve.
            val stored = geometry?.articulations?.get(event.id)
            val layout = stored?.let { GeometryProjector.resolveArticulation(it, event, query) }
                ?: buildLayout(event, query)
                ?: continue
            if (layout.marks.isNotEmpty()) result[event.id] = layout
        }
        return result
    }

    context(BravuraFont)
    private fun buildLayout(
        event: ComputedVoiceEvent,
        query: LayoutQuery
    ): ArticulationLayout? {
        val env = query.environment(event.id) ?: return null
        val noteheads = env.noteElement.noteBody.noteheads
        if (noteheads.isEmpty()) return null

        val stemDir = env.voiceLayout.stem?.direction
        val onStemSide = event.articulationPlacement == ArticulationPlacement.STEM && env.voiceLayout.stem != null
        val above = if (onStemSide) {
            stemDir == StemDirection.UP
        } else {
            // Notehead side: opposite the stem; default below when stemless.
            stemDir == StemDirection.DOWN
        }
        val sign = if (above) -1f else 1f

        // Horizontal centre of the mark column. Keep this shared with persisted-geometry
        // resolution: a cached origin must not leave the mark behind when a note's
        // post-layout horizontal offset changes.
        val centerX = articulationColumnCenterX(env, onStemSide)

        // Starting edge (relative to staff center) just outside the note on the chosen side.
        val edge: StaffSpace = if (onStemSide && env.voiceLayout.stem != null) {
            env.voiceLayout.stem.tipY
        } else if (above) {
            StaffSpace(noteheads.minOf { it.geometry.bounds.top.value })
        } else {
            StaffSpace(noteheads.maxOf { it.geometry.bounds.bottom.value })
        }

        // Cursor tracks the near (note-facing) edge of the next glyph, marching
        // outward. Marks hug the notehead — they are NOT forced outside the staff.
        var cursor = edge.value + sign * config.articulationNoteGap.value
        val marks = mutableListOf<PlacedArticulation>()

        val displayArticulations = event.articulations.filter { it != Articulation.FERMATA }.let { base ->
            if (event.fermata != null || Articulation.FERMATA in event.articulations) {
                base + Articulation.FERMATA
            } else base
        }
        for ((index, articulation) in orderedArticulations(displayArticulations)) {
            val glyph = ArticulationGlyphs.glyphFor(
                articulation,
                above,
                event.fermata?.shape ?: com.mecon.api.storage.tracks.FermataShape.NORMAL,
            ) ?: continue
            val bbox = this@BravuraFont.getBBox(glyph) ?: continue
            val halfH = bbox.height.value / 2f

            var centerY = cursor + sign * halfH
            // Keep the glyph from sitting on a staff line (notehead side only).
            // Tenuto bars especially must not coincide with a line.
            if (!onStemSide) centerY = avoidStaffLine(centerY, halfH, sign)

            val centerYRel = StaffSpace(centerY)
            val originYRel = centerYRel + StaffSpace((bbox.northEast.y.value + bbox.southWest.y.value) / 2f)
            val originX = centerX - bbox.southWest.x - StaffSpace(bbox.width.value / 2f)

            val originY = env.staffLayout.centerY + originYRel
            val topScreenY = env.staffLayout.centerY + centerYRel - StaffSpace(halfH)
            val bounds = RelativeRect(
                origin = RelativePoint(originX + bbox.southWest.x, topScreenY),
                width = bbox.width,
                height = bbox.height
            )

            marks.add(
                PlacedArticulation(
                    articulation = articulation,
                    glyph = glyph,
                    index = index,
                    origin = RelativePoint(originX, originY),
                    above = above,
                    bounds = bounds
                )
            )

            // Advance past this glyph plus the stacking gap.
            cursor = centerY + sign * (halfH + config.articulationStackSpacing.value)
        }

        if (marks.isEmpty()) return null
        return ArticulationLayout(
            eventId = event.id,
            trackId = env.voiceLayout.trackId,
            staffIndex = env.staffLayout.staffIndex,
            measureNumber = env.voiceLayout.measureNumber,
            marks = marks
        )
    }

    /**
     * Pair each articulation with its original index, then order them from
     * innermost (nearest the note) to outermost for stacking.
     */
    private fun orderedArticulations(articulations: List<Articulation>): List<Pair<Int, Articulation>> =
        articulations.withIndex()
            .map { it.index to it.value }
            .sortedBy { ArticulationGlyphs.stackRank(it.second) }

}

/** Resolve the visual X centre used by every articulation attached to [env]. */
internal fun articulationColumnCenterX(env: EventEnvironment, onStemSide: Boolean): StaffSpace {
    val baseX = env.slotX + env.noteElement.relativeX
    val stem = env.voiceLayout.stem
    if (onStemSide && stem != null) return baseX + stem.relativeX

    val noteheads = env.noteElement.noteBody.noteheads
    val left = noteheads.minOf { it.geometry.bounds.left.value }
    val right = noteheads.maxOf { it.geometry.bounds.right.value }
    return baseX + StaffSpace((left + right) / 2f)
}
