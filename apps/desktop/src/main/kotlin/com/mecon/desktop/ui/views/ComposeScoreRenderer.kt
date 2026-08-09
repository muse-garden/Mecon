package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.interaction.SectionIndex
import com.mecon.renderer.interaction.StyleSnapshot
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.render.RenderColor
import com.mecon.desktop.uikit.text.toAnnotatedString
import com.mecon.renderer.render.*
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface

/** Process-wide Bravura Skia typeface, loaded lazily once and shared by every renderer. */
private val sharedSkiaTypeface: Typeface? by lazy {
    try {
        val bytes = ComposeScoreRenderer::class.java.classLoader
            ?.getResourceAsStream("fonts/Bravura.otf")
            ?.readBytes()
        if (bytes != null) FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) else null
    } catch (e: Exception) {
        println("Warning: Could not load Skia typeface: ${e.message}")
        null
    }
}

/**
 * Renders RenderCommands to a Compose Canvas DrawScope.
 *
 * This bridge converts platform-agnostic render commands into
 * Compose Canvas drawing operations.
 *
 * High-DPI handling: The renderer applies a uniform scale transform based on
 * display density. Font sizes are divided by density before using .sp units,
 * so that after the scale transform, everything renders at the correct size.
 */
class ComposeScoreRenderer(
    private val bravuraFontFamily: FontFamily? = null
) {
    // Skia typeface for efficient glyph rendering. Shared process-wide: the Bravura OTF is
    // several MB, and many renderers can exist at once (e.g. one per new-score-dialog preview),
    // so loading it once avoids repeated reads and parses.
    private val skiaTypeface: Typeface? = sharedSkiaTypeface

    // Cache Skia Font objects by size to avoid repeated creation
    private val skiaFontCache = mutableMapOf<Float, Font>()

    private fun getSkiaFont(size: Float): Font? {
        val typeface = skiaTypeface ?: return null
        return skiaFontCache.getOrPut(size) {
            Font(typeface).apply { this.size = size }
        }
    }

    /**
     * Render all elements from a RenderResult.
     *
     * @param drawScope The Compose Canvas draw scope
     * @param result The render result containing all elements
     * @param textMeasurer Optional text measurer for text rendering
     * @param styleSnapshot Optional style snapshot for color overrides
     * @param sectionIndex Optional section index for sectionId-based style lookups
     */
    fun render(
        drawScope: DrawScope,
        result: RenderResult,
        textMeasurer: TextMeasurer? = null,
        styleSnapshot: StyleSnapshot? = null,
        sectionIndex: SectionIndex? = null,
        cullRect: com.mecon.renderer.geometry.AbsoluteRect? = null
    ) {
        render(drawScope, result.elements, textMeasurer, styleSnapshot, sectionIndex, cullRect)
    }

    /**
     * Render an explicit list of elements (e.g. one page's page-local elements in paginated
     * mode). Applies the same high-DPI scale transform as [render]. Element ids are preserved
     * through page slicing, so [sectionIndex] / [styleSnapshot] lookups still resolve.
     */
    fun render(
        drawScope: DrawScope,
        elements: List<RenderElement>,
        textMeasurer: TextMeasurer? = null,
        styleSnapshot: StyleSnapshot? = null,
        sectionIndex: SectionIndex? = null,
        cullRect: com.mecon.renderer.geometry.AbsoluteRect? = null,
        /** When non-null, forces every command's colour (uniform ink), unless a per-element style
         *  override sets its own fill. Used by monochrome thumbnails (e.g. [SimpleScoreView]). */
        overrideColor: RenderColor? = null
    ) {
        val density = drawScope.density

        // Apply uniform scale for high-DPI support
        // Use Offset.Zero as pivot to scale from top-left corner
        drawScope.withTransform({
            scale(density, density, pivot = Offset.Zero)
        }) {
            for (element in elements) {
                // Viewport culling: skip elements whose bounds don't intersect the visible rect.
                // For large scores this turns a full re-engrave (every element) into ~one screenful,
                // making each draw cheap even though the layer redraws on every pan frame.
                if (cullRect != null && !intersects(element.hitBox, cullRect)) continue
                renderElement(this, element, textMeasurer, density, styleSnapshot, sectionIndex, overrideColor)
            }
        }
    }

    // --- Cached, layered score Pictures (pan/zoom fast path) ---------------------------------
    // Recording elements into Skia Pictures once lets pan/zoom frames replay them via drawPicture
    // (Skia rasterizes only the visible ops, glyphs hit the GPU atlas) instead of re-running the
    // Kotlin render loop + per-glyph TextLine.make/Paint allocation on every frame.
    //
    // The score is split into three z-ordered layers — staff (lines/clefs/barlines), notes
    // (heads/stems/beams/accidentals/ties/slurs) and marks (dynamics/text/etc.). Each layer caches
    // its own Picture and re-records only when its own elements or the style overrides touching its
    // elements change. So selecting a note re-records only the notes layer, leaving the (often
    // larger) staff layer untouched — and a pure pan/zoom touches nothing, just replaying all three.
    private class ScoreLayerCache(
        val elements: List<RenderElement>,
        /** sectionIds of every element in this layer, for style-change detection. */
        val sectionIds: Set<EventSectionId>,
    ) {
        var picture: org.jetbrains.skia.Picture? = null
        /** The subset of style overrides that touch this layer; re-record when it changes. */
        var signature: Map<EventSectionId, com.mecon.renderer.interaction.OrderedOverride> = emptyMap()
        var density: Float = Float.NaN
        var valid: Boolean = false
    }

    /**
     * Whether two layer element lists record the same pixels. Incremental page assembly gives every
     * regenerated system a new element-ID generation, so RenderElement.equals is false even for an
     * unchanged staff/marks layer. IDs and hit-test metadata are not consumed by Picture recording;
     * commands are. Keep hitBox because a style override may draw a background from it.
     */
    private fun samePictureContent(
        previous: List<RenderElement>,
        fresh: List<RenderElement>,
    ): Boolean {
        if (previous.size != fresh.size) return false
        return previous.indices.all { index ->
            val a = previous[index]
            val b = fresh[index]
            a.type == b.type &&
                a.commands == b.commands &&
                a.hitBox == b.hitBox &&
                a.selected == b.selected &&
                a.highlighted == b.highlighted
        }
    }

    /** Move an owned Picture to a fresh cache carrying the new frame's element/section identities. */
    private fun transferPicture(previous: ScoreLayerCache, fresh: ScoreLayerCache): ScoreLayerCache {
        fresh.picture = previous.picture
        previous.picture = null
        fresh.signature = previous.signature
        fresh.density = previous.density
        fresh.valid = previous.valid
        return fresh
    }

    // Layers are rebuilt (by reference) whenever the element list changes; z-order is staff→notes→marks.
    private var layerSource: List<RenderElement>? = null
    private var scoreLayers: List<ScoreLayerCache> = emptyList()

    private enum class ScoreLayer { STAFF, NOTES, MARKS }

    private fun layerOf(type: RenderElementType): ScoreLayer = when (type) {
        RenderElementType.NOTE, RenderElementType.REST, RenderElementType.CHORD,
        RenderElementType.GRACE_NOTE, RenderElementType.NOTEHEAD, RenderElementType.STEM,
        RenderElementType.FLAG, RenderElementType.BEAM, RenderElementType.DOT,
        RenderElementType.ACCIDENTAL, RenderElementType.LEDGER_LINE,
        RenderElementType.TIE, RenderElementType.SLUR -> ScoreLayer.NOTES

        RenderElementType.STAFF, RenderElementType.STAFF_LINE, RenderElementType.CLEF,
        RenderElementType.KEY_SIGNATURE, RenderElementType.TIME_SIGNATURE, RenderElementType.BARLINE,
        RenderElementType.SYSTEM_BRACKET, RenderElementType.SYSTEM_BRACE,
        RenderElementType.MEASURE -> ScoreLayer.STAFF

        else -> ScoreLayer.MARKS
    }

    /** Partition [elements] into z-ordered layer caches (stable order preserved within each layer). */
    private fun buildLayers(elements: List<RenderElement>, sectionIndex: SectionIndex?): List<ScoreLayerCache> {
        val buckets = mapOf(
            ScoreLayer.STAFF to mutableListOf<RenderElement>(),
            ScoreLayer.NOTES to mutableListOf<RenderElement>(),
            ScoreLayer.MARKS to mutableListOf<RenderElement>(),
        )
        for (e in elements) {
            // Editor markers are a tiny conditional overlay. Keeping them out of Pictures lets the
            // edit/preview toggle hide them without invalidating any cached engraving layer.
            if (e.type == RenderElementType.EDITOR_MARKER) continue
            buckets.getValue(layerOf(e.type)).add(e)
        }
        // z-order: staff at the bottom, then notes, then marks on top.
        return listOf(ScoreLayer.STAFF, ScoreLayer.NOTES, ScoreLayer.MARKS).map { layer ->
            val subset = buckets.getValue(layer)
            val ids = if (sectionIndex == null) emptySet()
                      else subset.flatMapTo(HashSet()) { sectionIndex.sectionIdsFor(it.id) }
            ScoreLayerCache(subset, ids)
        }
    }

    /**
     * Draw the score via cached, layered Skia Pictures. Rebuilds the layer partition when the
     * element list changes; otherwise each layer re-records only if its own elements' style
     * overrides (or density) changed. A pure pan/zoom re-records nothing — all three layers replay.
     *
     * [bounds] is the score's full extent (design px), used as each picture's RTree cull rect.
     */
    fun drawCachedScore(
        drawScope: DrawScope,
        elements: List<RenderElement>,
        bounds: com.mecon.renderer.geometry.AbsoluteRect,
        textMeasurer: TextMeasurer? = null,
        styleSnapshot: StyleSnapshot? = null,
        sectionIndex: SectionIndex? = null,
    ) {
        if (layerSource !== elements) {
            scoreLayers.forEach { it.picture?.close() }
            scoreLayers = buildLayers(elements, sectionIndex)
            layerSource = elements
        }
        replayLayers(drawScope, scoreLayers, bounds, textMeasurer, styleSnapshot, sectionIndex, "score")
    }

    // --- Per-page cached Pictures (paginated fast path) -----------------------------------------
    // Mirror [drawCachedScore] but keyed per page on the [RenderPage.elements] reference identity.
    // A paginated edit reuses unaffected RenderPages by reference (RenderPageBuilder.buildIncremental),
    // so their cached Pictures replay unchanged and only the edited page re-records — turning a
    // whole-score re-engrave (every glyph, every page) into one page's worth of work.
    private class PageCache {
        var elements: List<RenderElement>? = null // identity key
        var layers: List<ScoreLayerCache> = emptyList()
    }
    private val pageCaches = HashMap<Int, PageCache>()

    /**
     * Draw one paginated page via its own cached, layered Skia Pictures. [page] carries page-local
     * elements (Y measured from the page top); the caller has already applied the page's slot
     * translate. Re-records only when this page's [RenderPage.elements] reference (or style/density)
     * changed; unaffected pages (reused by reference) just replay.
     */
    fun drawCachedPage(
        drawScope: DrawScope,
        page: RenderPage,
        textMeasurer: TextMeasurer? = null,
        styleSnapshot: StyleSnapshot? = null,
        sectionIndex: SectionIndex? = null,
    ) {
        val cache = pageCaches.getOrPut(page.pageIndex) { PageCache() }
        if (cache.elements !== page.elements) {
            val previousLayers = cache.layers
            val freshLayers = buildLayers(page.elements, sectionIndex)
            // An affected page receives a new flat elements list even when only its notes changed.
            // Preserve value-identical staff/marks layers (and their recorded Pictures) instead of
            // invalidating all three layers by page-list identity. A page is small (~hundreds of
            // elements), so these bounded per-layer equality checks are far cheaper than re-recording.
            cache.layers = freshLayers.mapIndexed { index, fresh ->
                val previous = previousLayers.getOrNull(index)
                if (previous != null && samePictureContent(previous.elements, fresh.elements)) {
                    // Retain the new frame's element IDs / section IDs for future style lookup, but
                    // transfer the already-recorded pixels. replayLayers will still invalidate it if
                    // the style signature touching those fresh section IDs differs.
                    transferPicture(previous, fresh)
                } else {
                    previous?.picture?.close()
                    fresh
                }
            }
            if (previousLayers.size > freshLayers.size) {
                previousLayers.drop(freshLayers.size).forEach { it.picture?.close() }
            }
            cache.elements = page.elements
        }
        // Page-local cull rect: elements live in [0, page.width] × [0, page.height].
        val bounds = com.mecon.renderer.geometry.AbsoluteRect(
            origin = com.mecon.renderer.geometry.AbsolutePoint(Pixels(0f), Pixels(0f)),
            width = page.width,
            height = page.height,
        )
        replayLayers(
            drawScope, cache.layers, bounds, textMeasurer, styleSnapshot, sectionIndex,
            "page=${page.pageIndex}",
        )
    }

    /** Drop cached Pictures for pages no longer present (e.g. after a reflow shrinks the page count). */
    fun prunePageCaches(validPageIndices: Set<Int>) {
        val stale = pageCaches.keys.filter { it !in validPageIndices }
        for (k in stale) {
            pageCaches.remove(k)?.layers?.forEach { it.picture?.close() }
        }
    }

    /**
     * Validate + (re)record each layer's Picture as needed, then replay all layers into the canvas.
     * Shared by [drawCachedScore] (whole score) and [drawCachedPage] (one page). A layer re-records
     * only when invalidated, its density changed, or the style overrides touching it changed.
     */
    private fun replayLayers(
        drawScope: DrawScope,
        layers: List<ScoreLayerCache>,
        bounds: com.mecon.renderer.geometry.AbsoluteRect,
        textMeasurer: TextMeasurer?,
        styleSnapshot: StyleSnapshot?,
        sectionIndex: SectionIndex?,
        debugLabel: String,
    ) {
        val density = drawScope.density
        val overrides = styleSnapshot?.overrides ?: emptyMap()
        val recordStartedAt = System.nanoTime()
        var recordedLayers = 0
        for (layer in layers) {
            // The overrides that actually touch this layer's elements; empty when nothing applies.
            val sig = if (layer.sectionIds.isEmpty() || overrides.isEmpty()) emptyMap()
                      else overrides.filterKeys { it in layer.sectionIds }
            if (!layer.valid || layer.density != density || layer.signature != sig) {
                layer.picture?.close()
                layer.picture = recordScorePicture(layer.elements, bounds, textMeasurer, styleSnapshot, sectionIndex, density)
                recordedLayers += 1
                layer.signature = sig
                layer.density = density
                layer.valid = true
            }
        }
        drawScope.drawIntoCanvas { canvas ->
            for (layer in layers) layer.picture?.let { canvas.nativeCanvas.drawPicture(it) }
        }
        if (recordedLayers > 0) {
            com.mecon.renderer.debug.PerfLog.log("compose.draw") {
                "$debugLabel pictureRecord=${(System.nanoTime() - recordStartedAt) / 1_000_000}ms " +
                    "layers=$recordedLayers elements=${layers.sumOf { it.elements.size }}"
            }
        }
    }

    /** Record an element list into an RTree-indexed Picture in design*density space. */
    private fun recordScorePicture(
        elements: List<RenderElement>,
        bounds: com.mecon.renderer.geometry.AbsoluteRect,
        textMeasurer: TextMeasurer?,
        styleSnapshot: StyleSnapshot?,
        sectionIndex: SectionIndex?,
        density: Float,
    ): org.jetbrains.skia.Picture {
        val pad = 64f * density
        val left = bounds.origin.x.value * density - pad
        val top = bounds.origin.y.value * density - pad
        val right = (bounds.origin.x.value + bounds.width.value) * density + pad
        val bottom = (bounds.origin.y.value + bounds.height.value) * density + pad
        val recorder = org.jetbrains.skia.PictureRecorder()
        val skiaCanvas = recorder.beginRecording(
            org.jetbrains.skia.Rect(left, top, right, bottom),
            org.jetbrains.skia.RTreeFactory(),
        )
        CanvasDrawScope().draw(
            Density(density),
            LayoutDirection.Ltr,
            skiaCanvas.asComposeCanvas(),
            Size(right - left, bottom - top),
        ) {
            // Record every element (no cull); visibility culling happens at replay via the RTree.
            render(this, elements, textMeasurer, styleSnapshot, sectionIndex, cullRect = null)
        }
        return recorder.finishRecordingAsPicture()
    }

    /** AABB intersection test between an element's bounds and the visible [cull] rect (design px). */
    private fun intersects(
        b: com.mecon.renderer.geometry.AbsoluteRect,
        cull: com.mecon.renderer.geometry.AbsoluteRect
    ): Boolean {
        val bx0 = b.origin.x.value; val bx1 = bx0 + b.width.value
        val by0 = b.origin.y.value; val by1 = by0 + b.height.value
        val cx0 = cull.origin.x.value; val cx1 = cx0 + cull.width.value
        val cy0 = cull.origin.y.value; val cy1 = cy0 + cull.height.value
        return bx1 >= cx0 && bx0 <= cx1 && by1 >= cy0 && by0 <= cy1
    }

    /**
     * Draw a raw list of render [commands] with every colour forced to [color]. Used for transient
     * overlays such as the note-pen ghost preview, which carries its own absolute geometry and is
     * tinted a single translucent colour rather than going through the element/section pipeline.
     *
     * Applies the same high-DPI scale transform as [render], so the commands' coordinates are in the
     * same (design-pixel) space as a normal render pass.
     */
    fun renderCommandsTinted(
        drawScope: DrawScope,
        commands: List<RenderCommand>,
        textMeasurer: TextMeasurer?,
        color: RenderColor
    ) {
        val density = drawScope.density
        drawScope.withTransform({
            scale(density, density, pivot = Offset.Zero)
        }) {
            for (command in commands) {
                renderCommandWithOverride(this, command, textMeasurer, density, color)
            }
        }
    }

    /**
     * Render a single element, applying style overrides if present.
     */
    fun renderElement(
        drawScope: DrawScope,
        element: RenderElement,
        textMeasurer: TextMeasurer?,
        density: Float,
        styleSnapshot: StyleSnapshot? = null,
        sectionIndex: SectionIndex? = null,
        overrideColor: RenderColor? = null
    ) {
        val sectionIds = if (styleSnapshot != null && sectionIndex != null) {
            sectionIndex.sectionIdsFor(element.id)
        } else {
            null
        }
        // A hide override (e.g. the original of a note being drag-transposed) suppresses the element
        // entirely; checked across all of its sections so a selection-colour override can't mask it.
        if (sectionIds != null && styleSnapshot != null && styleSnapshot.isHidden(sectionIds)) return
        val override = sectionIds?.let { styleSnapshot?.getOverride(it) }

        // Draw background color behind the element if specified
        val bgColor = override?.backgroundColor
        if (bgColor != null) {
            val bounds = element.hitBox
            drawScope.drawRect(
                color = bgColor.toComposeColor(),
                topLeft = Offset(bounds.origin.x.value, bounds.origin.y.value),
                size = Size(bounds.width.value, bounds.height.value),
                style = Fill
            )
        }

        // Render commands, optionally replacing colors. A per-element style override wins; otherwise
        // the caller-wide [overrideColor] (monochrome thumbnails) applies.
        val fillColor = override?.fillColor ?: overrideColor
        for (command in element.commands) {
            if (fillColor != null) {
                renderCommandWithOverride(drawScope, command, textMeasurer, density, fillColor)
            } else {
                renderCommand(drawScope, command, textMeasurer, density)
            }
        }
    }

    /**
     * Render a single command.
     */
    fun renderCommand(
        drawScope: DrawScope,
        command: RenderCommand,
        textMeasurer: TextMeasurer?,
        density: Float
    ) {
        when (command) {
            is DrawLine -> renderLine(drawScope, command)
            is DrawRect -> renderRect(drawScope, command)
            is DrawEllipse -> renderEllipse(drawScope, command)
            is DrawGlyph -> renderGlyph(drawScope, command)
            is DrawText -> renderText(drawScope, command, textMeasurer, density)
            is DrawPath -> renderPath(drawScope, command)
            is DrawBezier -> renderBezier(drawScope, command)
            is RenderGroup -> renderGroup(drawScope, command, textMeasurer, density)
        }
    }

    private fun renderLine(drawScope: DrawScope, command: DrawLine) {
        val color = command.color.toComposeColor()
        val cap = when (command.cap) {
            LineCap.BUTT -> StrokeCap.Butt
            LineCap.ROUND -> StrokeCap.Round
            LineCap.SQUARE -> StrokeCap.Square
        }

        val pathEffect = command.dashIntervals?.let {
            androidx.compose.ui.graphics.PathEffect.dashPathEffect(it.toFloatArray(), 0f)
        }

        drawScope.drawLine(
            color = color,
            start = Offset(command.start.x.value, command.start.y.value),
            end = Offset(command.end.x.value, command.end.y.value),
            strokeWidth = command.thickness.value,
            cap = cap,
            pathEffect = pathEffect
        )
    }

    private fun renderRect(drawScope: DrawScope, command: DrawRect) {
        val topLeft = Offset(command.rect.origin.x.value, command.rect.origin.y.value)
        val size = Size(command.rect.width.value, command.rect.height.value)

        // Fill
        command.fillColor?.let { fillColor ->
            drawScope.drawRect(
                color = fillColor.toComposeColor(),
                topLeft = topLeft,
                size = size,
                style = Fill
            )
        }

        // Stroke
        command.strokeColor?.let { strokeColor ->
            drawScope.drawRect(
                color = strokeColor.toComposeColor(),
                topLeft = topLeft,
                size = size,
                style = Stroke(width = command.strokeThickness.value)
            )
        }
    }

    private fun renderEllipse(drawScope: DrawScope, command: DrawEllipse) {
        // Convert center + radii to topLeft + size
        val topLeft = Offset(
            command.center.x.value - command.radiusX.value,
            command.center.y.value - command.radiusY.value
        )
        val size = Size(command.radiusX.value * 2, command.radiusY.value * 2)

        // Fill
        command.fillColor?.let { fillColor ->
            drawScope.drawOval(
                color = fillColor.toComposeColor(),
                topLeft = topLeft,
                size = size,
                style = Fill
            )
        }

        // Stroke
        command.strokeColor?.let { strokeColor ->
            drawScope.drawOval(
                color = strokeColor.toComposeColor(),
                topLeft = topLeft,
                size = size,
                style = Stroke(width = command.strokeThickness.value)
            )
        }
    }

    private fun renderGlyph(
        drawScope: DrawScope,
        command: DrawGlyph
    ) {
        val text = command.glyph.codepoint.toString()
        val fontSize = command.fontSize.value
        val x = command.position.x.value
        val y = command.position.y.value

        val skiaFont = getSkiaFont(fontSize) ?: return

        drawScope.drawIntoCanvas { canvas ->
            val textLine = TextLine.make(text, skiaFont)
            val paint = org.jetbrains.skia.Paint().apply {
                color = command.color.toSkiaColor()
            }
            // TextLine draws with y as baseline position
            if (command.scaleX == 1f && command.scaleY == 1f) {
                canvas.nativeCanvas.drawTextLine(textLine, x, y, paint)
            } else {
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.translate(x, y)
                canvas.nativeCanvas.scale(command.scaleX, command.scaleY)
                canvas.nativeCanvas.translate(-x, -y)
                canvas.nativeCanvas.drawTextLine(textLine, x, y, paint)
                canvas.nativeCanvas.restore()
            }
        }
    }

    private fun renderText(
        drawScope: DrawScope,
        command: DrawText,
        textMeasurer: TextMeasurer?,
        density: Float
    ) {
        val color = command.color.toComposeColor()
        // Divide by density to counteract .sp scaling, since scale(density) will enlarge the text
        val fontSize = (command.fontSize.value / density).sp

        val fontWeight = when (command.fontWeight) {
            com.mecon.renderer.render.FontWeight.THIN -> FontWeight.Thin
            com.mecon.renderer.render.FontWeight.LIGHT -> FontWeight.Light
            com.mecon.renderer.render.FontWeight.NORMAL -> FontWeight.Normal
            com.mecon.renderer.render.FontWeight.MEDIUM -> FontWeight.Medium
            com.mecon.renderer.render.FontWeight.BOLD -> FontWeight.Bold
            com.mecon.renderer.render.FontWeight.BLACK -> FontWeight.Black
        }

        val fontStyle = when (command.fontStyle) {
            com.mecon.renderer.render.FontStyle.NORMAL -> FontStyle.Normal
            com.mecon.renderer.render.FontStyle.ITALIC -> FontStyle.Italic
        }

        textMeasurer?.let { measurer ->
            val style = TextStyle(
                fontSize = fontSize,
                color = color,
                fontWeight = fontWeight,
                fontStyle = fontStyle
            )

            // richText carries per-run styling (sizes as em, relative to `style`).
            val layoutResult = command.richText?.let { rich ->
                measurer.measure(rich.toAnnotatedString(), style)
            } ?: measurer.measure(command.text, style)

            // layoutResult.size = value pixels, use directly as design pixels
            val x = when (command.alignment) {
                TextAlignment.LEFT -> command.position.x.value
                TextAlignment.CENTER -> command.position.x.value - layoutResult.size.width / 2f
                TextAlignment.RIGHT -> command.position.x.value - layoutResult.size.width
            }
            val offset = Offset(x, command.position.y.value)

            // Draw the already-measured layout result rather than the
            // (textMeasurer, text, …) overload. That overload re-measures with
            // constraints derived from DrawScope.size minus topLeft, which makes
            // maxHeight go negative — and crash — when an element sits below the
            // reported canvas height. Since DrawScope.size depends on the actual
            // on-screen pixel area, the failure is resolution-dependent. Drawing
            // the pre-measured result keeps text rendering size-independent.
            drawScope.drawText(
                textLayoutResult = layoutResult,
                topLeft = offset
            )
        }
    }

    private fun renderPath(drawScope: DrawScope, command: DrawPath) {
        val path = Path().apply {
            for (segment in command.path.segments) {
                when (segment) {
                    is com.mecon.renderer.geometry.AbsolutePathSegment.MoveTo ->
                        moveTo(segment.point.x.value, segment.point.y.value)
                    is com.mecon.renderer.geometry.AbsolutePathSegment.LineTo ->
                        lineTo(segment.point.x.value, segment.point.y.value)
                    is com.mecon.renderer.geometry.AbsolutePathSegment.QuadTo ->
                        quadraticTo(
                            segment.control.x.value, segment.control.y.value,
                            segment.end.x.value, segment.end.y.value
                        )
                    is com.mecon.renderer.geometry.AbsolutePathSegment.CubicTo ->
                        cubicTo(
                            segment.control1.x.value, segment.control1.y.value,
                            segment.control2.x.value, segment.control2.y.value,
                            segment.end.x.value, segment.end.y.value
                        )
                    is com.mecon.renderer.geometry.AbsolutePathSegment.Close ->
                        close()
                }
            }
        }

        // Fill
        command.fillColor?.let { fillColor ->
            val composeFill = fillColor.toComposeColor()
            drawScope.drawPath(path = path, color = composeFill, style = Fill)
            // Hairline stroke of the same color smooths jagged fill edges (anti-aliasing).
            drawScope.drawPath(
                path = path,
                color = composeFill,
                style = Stroke(width = 0.6f)
            )
        }

        // Explicit stroke (separate color / thickness requested by caller)
        command.strokeColor?.let { strokeColor ->
            drawScope.drawPath(
                path = path,
                color = strokeColor.toComposeColor(),
                style = Stroke(width = command.strokeThickness.value)
            )
        }
    }

    private fun renderBezier(drawScope: DrawScope, command: DrawBezier) {
        val curve = command.curve
        val color = command.color.toComposeColor()

        if (command.filled) {
            // For filled beziers (thick slurs/ties), draw as filled path
            // This is a simplified approach - proper slur rendering would need
            // parallel curves for inner and outer edges
            val path = Path().apply {
                moveTo(curve.p0.x.value, curve.p0.y.value)
                cubicTo(
                    curve.p1.x.value, curve.p1.y.value,
                    curve.p2.x.value, curve.p2.y.value,
                    curve.p3.x.value, curve.p3.y.value
                )
            }

            drawScope.drawPath(
                path = path,
                color = color,
                style = Stroke(width = command.midpointThickness.value, cap = StrokeCap.Round)
            )
        } else {
            // Simple stroke
            val path = Path().apply {
                moveTo(curve.p0.x.value, curve.p0.y.value)
                cubicTo(
                    curve.p1.x.value, curve.p1.y.value,
                    curve.p2.x.value, curve.p2.y.value,
                    curve.p3.x.value, curve.p3.y.value
                )
            }

            drawScope.drawPath(
                path = path,
                color = color,
                style = Stroke(width = command.endpointThickness.value)
            )
        }
    }

    private fun renderGroup(
        drawScope: DrawScope,
        command: RenderGroup,
        textMeasurer: TextMeasurer?,
        density: Float
    ) {
        // Apply opacity and clipping if needed
        // TODO: Handle clipRect and opacity properly
        for (child in command.commands) {
            renderCommand(drawScope, child, textMeasurer, density)
        }
    }

    /**
     * Render a command with its colors replaced by [overrideColor].
     */
    private fun renderCommandWithOverride(
        drawScope: DrawScope,
        command: RenderCommand,
        textMeasurer: TextMeasurer?,
        density: Float,
        overrideColor: RenderColor
    ) {
        val replaced = when (command) {
            is DrawGlyph -> command.copy(color = overrideColor)
            is DrawLine -> command.copy(color = overrideColor)
            is DrawText -> command.copy(color = overrideColor)
            is DrawBezier -> command.copy(color = overrideColor)
            is DrawRect -> command.copy(
                fillColor = command.fillColor?.let { overrideColor },
                strokeColor = command.strokeColor?.let { overrideColor }
            )
            is DrawPath -> command.copy(
                fillColor = command.fillColor?.let { overrideColor },
                strokeColor = command.strokeColor?.let { overrideColor }
            )
            is DrawEllipse -> command.copy(
                fillColor = command.fillColor?.let { overrideColor },
                strokeColor = command.strokeColor?.let { overrideColor }
            )
            is RenderGroup -> {
                // Recursively render children with override
                for (child in command.commands) {
                    renderCommandWithOverride(drawScope, child, textMeasurer, density, overrideColor)
                }
                return
            }
        }
        renderCommand(drawScope, replaced, textMeasurer, density)
    }

    /**
     * Convert RenderColor to Compose Color.
     */
    private fun RenderColor.toComposeColor(): Color {
        return Color(red, green, blue, alpha)
    }

    /**
     * Convert RenderColor to Skia color int (ARGB format).
     * RenderColor fields are already 0-255 integers.
     */
    private fun RenderColor.toSkiaColor(): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
