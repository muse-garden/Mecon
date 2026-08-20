package com.mecon.mobile.android

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mecon.api.render.RenderColor
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.features.scoreediting.ScoreEditingFrame
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.mobile.MobileScoreRenderSession
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.DrawBezier
import com.mecon.renderer.render.DrawEllipse
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.DrawRect
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.LineCap
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderGroup
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.RenderedBarlineHit
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostPointSymbol
import com.mecon.renderer.render.edit.PointSymbolKind
import com.mecon.renderer.render.edit.ExpressionSpanKind
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostTimeSignature
import com.mecon.renderer.render.TextAlignment
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A referential frame: publishing it never structurally compares a full [RenderResult]. */
class AndroidScoreRenderFrame(
    val result: RenderResult? = null,
    val rendering: Boolean = false,
    val error: String? = null,
)

/**
 * One serial conflated engraving worker. Rapid edits replace queued requests; the currently
 * displayed immutable frame remains visible until a complete new [RenderResult] is published.
 */
class AndroidScoreRenderCoordinator(
    context: Context,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val requests = Channel<ScoreEditingFrame>(Channel.CONFLATED)
    private val mutableFrame = MutableStateFlow(AndroidScoreRenderFrame(rendering = true))
    @Volatile
    private var renderSession: MobileScoreRenderSession? = null
    val frame: StateFlow<AndroidScoreRenderFrame> = mutableFrame.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            val session = runCatching {
                val font = withContext(Dispatchers.IO) {
                    val metadata = appContext.assets.open("bravura/bravuraMetadata.json")
                        .bufferedReader().use { it.readText() }
                    val names = appContext.assets.open("bravura/glyphnames.json")
                        .bufferedReader().use { it.readText() }
                    BravuraFont.fromJson(metadata, names)
                }
                MobileScoreRenderSession(font)
            }.getOrElse { failure ->
                mutableFrame.value = AndroidScoreRenderFrame(error = failure.message ?: failure.toString())
                return@launch
            }
            renderSession = session

            for (request in requests) {
                mutableFrame.value = AndroidScoreRenderFrame(
                    result = mutableFrame.value.result,
                    rendering = true,
                )
                mutableFrame.value = runCatching {
                    AndroidScoreRenderFrame(result = session.render(request, StaffSpace(48f)))
                }.getOrElse { failure ->
                    AndroidScoreRenderFrame(
                        result = mutableFrame.value.result,
                        error = failure.message ?: failure.toString(),
                    )
                }
            }
        }
    }

    fun submit(frame: ScoreEditingFrame) {
        requests.trySend(frame)
    }

    fun computeNoteGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        duration: Duration,
        restMode: Boolean,
    ): GhostNote? = renderSession?.computeNoteGhost(
        frame = frame,
        result = result,
        point = point,
        duration = duration,
        restMode = restMode,
    )

    fun computeClefGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        clef: Clef,
    ): GhostClef? = renderSession?.computeClefGhost(frame, result, point, clef)

    fun computeTimeSignatureGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        timeSignature: TimeSignature,
    ): GhostTimeSignature? = renderSession?.computeTimeSignatureGhost(frame, result, point, timeSignature)

    fun computeKeySignatureGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        keySignature: KeySignature,
    ): GhostKeySignature? = renderSession?.computeKeySignatureGhost(frame, result, point, keySignature)

    fun computeExpressionSpanGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        start: TimeCode,
        end: TimeCode,
        kind: ExpressionSpanKind,
    ): GhostExpressionSpan? = renderSession?.computeExpressionSpanGhost(
        frame,
        result,
        staffTrackId,
        start,
        end,
        kind,
    )

    fun computeDefaultExpressionSpanGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        start: TimeCode,
        kind: ExpressionSpanKind,
    ): GhostExpressionSpan? = renderSession?.computeDefaultExpressionSpanGhost(
        frame,
        result,
        staffTrackId,
        start,
        kind,
    )

    fun computePointSymbolGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        onset: TimeCode,
        kind: PointSymbolKind,
    ): GhostPointSymbol? = renderSession?.computePointSymbolGhost(
        frame,
        result,
        staffTrackId,
        onset,
        kind,
    )

    fun selectionTargetAt(result: RenderResult, point: AbsolutePoint): ScoreSelectionTarget? =
        renderSession?.selectionTargetAt(result, point)

    fun selectionCandidatesAt(result: RenderResult, point: AbsolutePoint): List<ScoreSelectionTarget> =
        renderSession?.selectionCandidatesAt(result, point).orEmpty()

    fun selectionTargetsInRegion(result: RenderResult, rect: AbsoluteRect): List<ScoreSelectionTarget> =
        renderSession?.selectionTargetsInRegion(result, rect).orEmpty()

    fun elementsForSelection(result: RenderResult, target: ScoreSelectionTarget): List<RenderElement> =
        renderSession?.elementsForSelection(result, target).orEmpty()

    fun close() {
        requests.close()
    }
}

fun loadAndroidBravuraTypeface(context: Context): Typeface =
    Typeface.createFromAsset(context.assets, "bravura/Bravura.otf")

/** Replays the authoritative renderer commands; this function performs no engraving decisions. */
fun DrawScope.drawScoreResult(
    result: RenderResult,
    textMeasurer: TextMeasurer,
    bravuraTypeface: Typeface,
    entryPosition: TimeCode? = null,
    selectedElementIds: Set<RenderElementId> = emptySet(),
    selectedBarlineHit: RenderedBarlineHit? = null,
    marqueeRect: AbsoluteRect? = null,
    geometryDragElements: List<RenderElement> = emptyList(),
    geometryDragDx: Float = 0f,
    geometryDragDy: Float = 0f,
    previewCommands: List<RenderCommand> = emptyList(),
) {
    val displayDensity = density
    val hiddenForDrag = geometryDragElements.mapTo(hashSetOf()) { it.id }
    withTransform({
        scale(displayDensity, displayDensity, pivot = Offset.Zero)
        translate(-result.bounds.origin.x.value, -result.bounds.origin.y.value)
    }) {
        result.elements.asSequence().filterNot { it.id in hiddenForDrag }.forEach { element ->
            element.commands.forEach { command ->
                drawRenderCommand(command, textMeasurer, bravuraTypeface)
            }
        }
        if (selectedElementIds.isNotEmpty()) {
            result.elements.asSequence()
                .filter { it.id in selectedElementIds }
                .forEach { element ->
                    drawRect(
                        color = Color(0x332563EB),
                        topLeft = Offset(
                            element.hitBox.origin.x.value - 3f,
                            element.hitBox.origin.y.value - 3f,
                        ),
                        size = Size(element.hitBox.width.value + 6f, element.hitBox.height.value + 6f),
                        style = Fill,
                    )
                    drawRect(
                        color = Color(0xFF2563EB),
                        topLeft = Offset(
                            element.hitBox.origin.x.value - 3f,
                            element.hitBox.origin.y.value - 3f,
                        ),
                        size = Size(element.hitBox.width.value + 6f, element.hitBox.height.value + 6f),
                        style = Stroke(width = 1.5f),
                    )
                }
        }
        selectedBarlineHit?.let(result::barlinePositionAt)?.let { position ->
            drawLine(
                color = Color(0xFF7C3AED),
                start = Offset(position.x, position.topY - 10f),
                end = Offset(position.x, position.bottomY + 10f),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Color(0xFF7C3AED),
                radius = 5f,
                center = Offset(position.x, position.topY - 10f),
            )
        }
        marqueeRect?.let { rect ->
            drawRect(
                color = Color(0x222563EB),
                topLeft = Offset(rect.origin.x.value, rect.origin.y.value),
                size = Size(rect.width.value, rect.height.value),
                style = Fill,
            )
            drawRect(
                color = Color(0xFF2563EB),
                topLeft = Offset(rect.origin.x.value, rect.origin.y.value),
                size = Size(rect.width.value, rect.height.value),
                style = Stroke(width = 1.5f),
            )
        }
        if (geometryDragElements.isNotEmpty()) {
            withTransform({ translate(geometryDragDx, geometryDragDy) }) {
                geometryDragElements.forEach { element ->
                    element.commands.forEach { command ->
                        drawRenderCommand(command, textMeasurer, bravuraTypeface, opacity = 0.72f)
                    }
                }
            }
        }
        previewCommands.forEach { command ->
            drawRenderCommand(command, textMeasurer, bravuraTypeface, opacity = 0.72f)
        }
        entryPosition?.let(result::insertionPositionAt)?.let { position ->
            val color = Color(0xFF2563EB)
            drawLine(
                color = color,
                start = Offset(position.x, position.topY - 12f),
                end = Offset(position.x, position.bottomY + 12f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
            drawCircle(color, radius = 4.5f, center = Offset(position.x, position.topY - 12f))
        }
    }
}

private fun DrawScope.drawRenderCommand(
    command: RenderCommand,
    textMeasurer: TextMeasurer,
    bravuraTypeface: Typeface,
    opacity: Float = 1f,
) {
    when (command) {
        is DrawLine -> drawLine(
            color = command.color.composeColor(opacity),
            start = Offset(command.start.x.value, command.start.y.value),
            end = Offset(command.end.x.value, command.end.y.value),
            strokeWidth = command.thickness.value,
            cap = when (command.cap) {
                LineCap.BUTT -> StrokeCap.Butt
                LineCap.ROUND -> StrokeCap.Round
                LineCap.SQUARE -> StrokeCap.Square
            },
            pathEffect = command.dashIntervals?.let { PathEffect.dashPathEffect(it.toFloatArray()) },
        )

        is DrawRect -> {
            val topLeft = Offset(command.rect.origin.x.value, command.rect.origin.y.value)
            val size = Size(command.rect.width.value, command.rect.height.value)
            command.fillColor?.let { drawRect(it.composeColor(opacity), topLeft, size, style = Fill) }
            command.strokeColor?.let {
                drawRect(it.composeColor(opacity), topLeft, size, style = Stroke(command.strokeThickness.value))
            }
        }

        is DrawEllipse -> {
            val topLeft = Offset(
                command.center.x.value - command.radiusX.value,
                command.center.y.value - command.radiusY.value,
            )
            val size = Size(command.radiusX.value * 2f, command.radiusY.value * 2f)
            command.fillColor?.let { drawOval(it.composeColor(opacity), topLeft, size, style = Fill) }
            command.strokeColor?.let {
                drawOval(it.composeColor(opacity), topLeft, size, style = Stroke(command.strokeThickness.value))
            }
        }

        is DrawGlyph -> drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = bravuraTypeface
                textSize = command.fontSize.value
                color = command.color.androidColor(opacity)
            }
            val x = command.position.x.value
            val y = command.position.y.value
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.scale(command.scaleX, command.scaleY, x, y)
            canvas.nativeCanvas.drawText(command.glyph.codepoint.toString(), x, y, paint)
            canvas.nativeCanvas.restore()
        }

        is DrawText -> {
            val layout = textMeasurer.measure(
                command.text,
                TextStyle(
                    color = command.color.composeColor(opacity),
                    fontSize = (command.fontSize.value / density).sp,
                    fontWeight = command.fontWeight.composeWeight(),
                    fontStyle = if (command.fontStyle == com.mecon.renderer.render.FontStyle.ITALIC) {
                        FontStyle.Italic
                    } else FontStyle.Normal,
                ),
            )
            val x = when (command.alignment) {
                TextAlignment.LEFT -> command.position.x.value
                TextAlignment.CENTER -> command.position.x.value - layout.size.width / 2f
                TextAlignment.RIGHT -> command.position.x.value - layout.size.width
            }
            drawText(layout, topLeft = Offset(x, command.position.y.value))
        }

        is DrawPath -> {
            val path = command.path.composePath()
            command.fillColor?.let {
                val color = it.composeColor(opacity)
                drawPath(path, color, style = Fill)
                drawPath(path, color, style = Stroke(0.6f))
            }
            command.strokeColor?.let {
                drawPath(path, it.composeColor(opacity), style = Stroke(command.strokeThickness.value))
            }
        }

        is DrawBezier -> {
            val curve = command.curve
            val path = Path().apply {
                moveTo(curve.p0.x.value, curve.p0.y.value)
                cubicTo(
                    curve.p1.x.value, curve.p1.y.value,
                    curve.p2.x.value, curve.p2.y.value,
                    curve.p3.x.value, curve.p3.y.value,
                )
            }
            drawPath(
                path,
                command.color.composeColor(opacity),
                style = Stroke(
                    width = if (command.filled) command.midpointThickness.value else command.endpointThickness.value,
                    cap = if (command.filled) StrokeCap.Round else StrokeCap.Butt,
                ),
            )
        }

        is RenderGroup -> {
            val childOpacity = (opacity * command.opacity).coerceIn(0f, 1f)
            val drawChildren = {
                command.commands.forEach {
                    drawRenderCommand(it, textMeasurer, bravuraTypeface, childOpacity)
                }
            }
            command.clipRect?.let { clip ->
                clipRect(
                    left = clip.origin.x.value,
                    top = clip.origin.y.value,
                    right = clip.origin.x.value + clip.width.value,
                    bottom = clip.origin.y.value + clip.height.value,
                ) { drawChildren() }
            } ?: drawChildren()
        }
    }
}

private fun com.mecon.renderer.geometry.AbsolutePath.composePath(): Path = Path().also { path ->
    segments.forEach { segment ->
        when (segment) {
            is AbsolutePathSegment.MoveTo -> path.moveTo(segment.point.x.value, segment.point.y.value)
            is AbsolutePathSegment.LineTo -> path.lineTo(segment.point.x.value, segment.point.y.value)
            is AbsolutePathSegment.QuadTo -> path.quadraticTo(
                segment.control.x.value, segment.control.y.value,
                segment.end.x.value, segment.end.y.value,
            )
            is AbsolutePathSegment.CubicTo -> path.cubicTo(
                segment.control1.x.value, segment.control1.y.value,
                segment.control2.x.value, segment.control2.y.value,
                segment.end.x.value, segment.end.y.value,
            )
            AbsolutePathSegment.Close -> path.close()
        }
    }
}

private fun RenderColor.composeColor(opacity: Float): Color = Color(
    red = red / 255f,
    green = green / 255f,
    blue = blue / 255f,
    alpha = alpha / 255f * opacity,
)

private fun RenderColor.androidColor(opacity: Float): Int = android.graphics.Color.argb(
    (alpha * opacity).toInt().coerceIn(0, 255), red, green, blue,
)

private fun com.mecon.renderer.render.FontWeight.composeWeight(): FontWeight = when (this) {
    com.mecon.renderer.render.FontWeight.THIN -> FontWeight.Thin
    com.mecon.renderer.render.FontWeight.LIGHT -> FontWeight.Light
    com.mecon.renderer.render.FontWeight.NORMAL -> FontWeight.Normal
    com.mecon.renderer.render.FontWeight.MEDIUM -> FontWeight.Medium
    com.mecon.renderer.render.FontWeight.BOLD -> FontWeight.Bold
    com.mecon.renderer.render.FontWeight.BLACK -> FontWeight.Black
}
