package com.mecon.renderer.render.edit

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.ArticulationGlyphs
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.DynamicGlyphs
import com.mecon.renderer.render.OrnamentGlyphs
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/** One point-placement candidate rendered against the exact displayed generation. */
sealed interface PointSymbolKind {
    data class Dynamic(val level: DynamicLevel) : PointSymbolKind
    data class Tempo(val bpm: Float) : PointSymbolKind
    data class Fermata(val shape: FermataShape) : PointSymbolKind
    data class Breath(val shape: BreathMarkShape) : PointSymbolKind
    data class Ornament(val kind: OrnamentKind) : PointSymbolKind
}

data class GhostPointSymbol(
    val staffTrackId: TrackId,
    val onset: TimeCode,
    val kind: PointSymbolKind,
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

context(BravuraFont)
class GhostPointSymbolComputer {
    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        staffTrackId: TrackId,
        onset: TimeCode,
        kind: PointSymbolKind,
        absoluteX: Float? = null,
        systemIndex: Int? = null,
    ): GhostPointSymbol? {
        val staffIndex = runtime.orderedStaffs().indexOfFirst { it.id == staffTrackId }
        if (staffIndex < 0) return null
        val transformer = result.transformerSnapshot
        val position = result.insertionPositionAt(onset)
        val system = systemIndex?.let { requested ->
            result.spatialIndex.allSystems().firstOrNull { it.systemIndex == requested }
        } ?: position?.let {
            val positionY = (it.topY + it.bottomY) / 2f
            val relativeY = transformer.toRelative(
                AbsolutePoint(Pixels(it.x), Pixels(positionY)),
            ).y
            result.spatialIndex.allSystems().firstOrNull { candidate ->
                relativeY in candidate.topY..candidate.bottomY
            }
        } ?: return null
        val staffCenter = system.staffRegions.firstOrNull { it.staffIndex == staffIndex }?.centerY
            ?: return null
        val targetX = absoluteX ?: position?.x ?: return null
        val staffCenterY = transformer.toAbsolute(RelativePoint(StaffSpace.ZERO, staffCenter)).y
        val x = transformer.toRelative(AbsolutePoint(Pixels(targetX), staffCenterY)).x
        val commands = when (kind) {
            is PointSymbolKind.Dynamic -> glyphCommands(
                DynamicGlyphs.glyphsFor(kind.level), x, staffCenter + StaffSpace(4f), transformer,
            )
            is PointSymbolKind.Fermata -> glyphCommands(
                listOfNotNull(ArticulationGlyphs.glyphFor(Articulation.FERMATA, true, kind.shape)),
                x, staffCenter - StaffSpace(3.4f), transformer,
            )
            is PointSymbolKind.Breath -> glyphCommands(
                listOf(breathGlyph(kind.shape)), x, staffCenter - StaffSpace(3.4f), transformer,
            )
            is PointSymbolKind.Ornament -> glyphCommands(
                listOf(OrnamentGlyphs.glyphFor(kind.kind)), x, staffCenter - StaffSpace(3.4f), transformer,
            )
            is PointSymbolKind.Tempo -> {
                val anchor = transformer.toAbsolute(RelativePoint(x, staffCenter - StaffSpace(4.2f)))
                val size = transformer.toPixels(StaffSpace(1.55f))
                val text = "♩ = ${kind.bpm.toInt()}"
                listOf(
                    DrawText(
                        position = anchor,
                        text = text,
                        fontSize = size,
                        color = RenderColor.BLACK,
                        bounds = AbsoluteRect(
                            origin = AbsolutePoint(anchor.x, Pixels(anchor.y.value - size.value)),
                            width = Pixels(size.value * text.length * 0.58f),
                            height = Pixels(size.value * 1.25f),
                        ),
                    ),
                )
            }
        }
        if (commands.isEmpty()) return null
        return GhostPointSymbol(
            staffTrackId,
            onset,
            kind,
            commands,
            transformer.toAbsolute(RelativePoint(x, staffCenter)),
        )
    }

    private fun glyphCommands(
        glyphs: List<GlyphInfo>,
        centerX: StaffSpace,
        centerY: StaffSpace,
        transformer: com.mecon.renderer.render.CoordinateTransformer,
    ): List<RenderCommand> {
        if (glyphs.isEmpty()) return emptyList()
        val boxes = glyphs.mapNotNull { glyph -> getBBox(glyph)?.let { glyph to it } }
        if (boxes.isEmpty()) return emptyList()
        val totalWidth = boxes.sumOf { it.second.width.value.toDouble() }.toFloat()
        var left = centerX.value - totalWidth / 2f
        return boxes.map { (glyph, box) ->
            val origin = RelativePoint(
                StaffSpace(left - box.southWest.x.value),
                centerY + box.northEast.y,
            )
            left += box.width.value
            RenderHelpers.createGlyphCommand(
                glyph,
                transformer.toAbsolute(origin),
                transformer.toPixels(StaffSpace(4f)),
            )
        }
    }

    private fun breathGlyph(shape: BreathMarkShape): GlyphInfo = when (shape) {
        BreathMarkShape.COMMA -> SmuflGlyphs.breathMarkComma
        BreathMarkShape.TICK -> SmuflGlyphs.breathMarkTick
        BreathMarkShape.UPBOW -> SmuflGlyphs.breathMarkUpbow
        BreathMarkShape.SALZEDO -> SmuflGlyphs.breathMarkSalzedo
    }
}
