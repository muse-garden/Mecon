package com.mecon.plugins.chord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.KeySignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.pluginTrackOf
import com.mecon.desktop.uikit.PitchMode
import com.mecon.desktop.uikit.rememberBravuraFont
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.plugins.chord.ComputedChordEvent
import com.mecon.plugins.chord.RuntimeChordEvent
import com.mecon.plugins.chord.StorageChordEvent
import kotlin.math.abs

private val DX = 55.dp
private const val DY_FACTOR = 0.866f


/**
 * Draggable hexagonal Tonnetz grid for [displayChord].
 *
 * Grid formula: pitchVal = ((x*7 + y*4) % 12 + 12) % 12
 *  - horizontal axis: perfect fifths (+7 semitones per step)
 *  - diagonal axis:   major thirds (+4 semitones per step)
 *
 * The visible cell range is computed dynamically from the canvas size and current drag
 * offset so the grid is effectively infinite — dragging never reaches an edge.
 */
@Composable
internal fun TonnetzCanvas(
    displayChord: StorageChordEvent?,
    runtimeScore: RuntimeScore?,
    pitchMode: PitchMode = PitchMode.ABSOLUTE,
    keySignature: KeySignature = KeySignature.C_MAJOR,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val bravuraFont  = rememberBravuraFont()

    val currentPitchClasses: Set<Int> = remember(displayChord) {
        displayChord?.let {
            ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(it))
                .chord.pitchClasses.map { pc -> pc.value }.toSet()
        } ?: emptySet()
    }

    val (prevPitchClasses, nextPitchClasses) = remember(displayChord?.id, runtimeScore) {
        if (displayChord == null || runtimeScore == null) return@remember emptySet<Int>() to emptySet<Int>()
        fun pcSet(sc: StorageChordEvent?) = sc?.let {
            ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(it))
                .chord.pitchClasses.map { pc -> pc.value }.toSet()
        } ?: emptySet()
        val track = runtimeScore.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            ?: return@remember emptySet<Int>() to emptySet<Int>()
        val prev = track.prevEvent(displayChord.onset)?.storageEvent
        val next = track.nextEvent(displayChord.onset)?.storageEvent
        pcSet(prev) to pcSet(next)
    }

    // Pre-measure all 12 note labels for each visual state. Chromatic pitches produce two
    // separate measurements (part1 / part2) so we can draw them with firstBaseline-based
    // positioning rather than a "\n"-joined string — Bravura's music-engraving line metrics
    // cause an enormous gap when newlines are used inside a single TextLayoutResult.
    val pitchParts = remember(pitchMode, keySignature, bravuraFont) {
        (0..11).map { pitchClassLabelParts(it, pitchMode, keySignature, bravuraFont) }
    }
    fun measureParts(style: TextStyle) = pitchParts.map { parts ->
        Pair(
            textMeasurer.measure(parts[0], style),
            if (parts.size > 1) textMeasurer.measure(parts[1], style) else null
        )
    }
    val labelActive   = remember(textMeasurer, pitchParts) { measureParts(TextStyle(color = Color.White,               fontSize = 11.sp, fontWeight = FontWeight.Bold,     textAlign = TextAlign.Center)) }
    val labelPrev     = remember(textMeasurer, pitchParts) { measureParts(TextStyle(color = MeconColors.EmeraldLight,  fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) }
    val labelNext     = remember(textMeasurer, pitchParts) { measureParts(TextStyle(color = MeconColors.OrangeLight,   fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) }
    val labelInactive = remember(textMeasurer, pitchParts) { measureParts(TextStyle(color = MeconColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Normal,   textAlign = TextAlign.Center)) }

    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount -> dragOffset += dragAmount }
                }
        ) {
            clipRect {
                val dxPx = DX.toPx()
                val dyPx = dxPx * DY_FACTOR
                val nodeR = dxPx * 0.36f

                // cx/cy: screen coordinate of logical origin (0,0)
                val cx = size.width / 2f + dragOffset.x
                val cy = size.height / 2f + dragOffset.y

                fun nodePos(x: Int, y: Int) = Offset(
                    cx + x * dxPx + y * (dxPx / 2f),
                    cy + y * dyPx
                )

                fun pitchVal(x: Int, y: Int) = ((x * 7 + y * 4) % 12 + 12) % 12

                // Compute visible cell range from canvas bounds + drag so the grid is infinite
                val pad = 2
                val yMin = ((-nodeR - cy) / dyPx).toInt() - pad
                val yMax = ((size.height + nodeR - cy) / dyPx).toInt() + pad
                val ySkew = (abs(yMin).coerceAtLeast(abs(yMax)) / 2) + 1
                val xMin = ((-nodeR - cx) / dxPx).toInt() - ySkew - pad
                val xMax = ((size.width + nodeR - cx) / dxPx).toInt() + ySkew + pad

                // Grid lines: right, down-right, down-left from each node
                for (y in yMin..yMax) {
                    for (x in xMin..xMax) {
                        val p0 = nodePos(x, y)
                        drawLine(MeconColors.SurfaceLight.copy(alpha = 0.5f), p0, nodePos(x + 1, y), 1.dp.toPx())
                        drawLine(MeconColors.SurfaceLight.copy(alpha = 0.5f), p0, nodePos(x, y + 1), 1.dp.toPx())
                        drawLine(MeconColors.SurfaceLight.copy(alpha = 0.5f), p0, nodePos(x - 1, y + 1), 1.dp.toPx())
                    }
                }

                // Nodes
                for (y in yMin..yMax) {
                    for (x in xMin..xMax) {
                        val pt = nodePos(x, y)
                        val pv = pitchVal(x, y)
                        val isCurrent = pv in currentPitchClasses
                        val isPrev = !isCurrent && pv in prevPitchClasses
                        val isNext = !isCurrent && !isPrev && pv in nextPitchClasses

                        // Fill
                        drawCircle(
                            color = if (isCurrent) MeconColors.PrimaryDark else MeconColors.SurfaceLight,
                            radius = nodeR,
                            center = pt
                        )

                        // Stroke
                        when {
                            isCurrent -> drawCircle(
                                color = MeconColors.PrimaryLight,
                                radius = nodeR,
                                center = pt,
                                style = Stroke(2.dp.toPx())
                            )
                            isPrev -> drawCircle(
                                color = MeconColors.Emerald,
                                radius = nodeR,
                                center = pt,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                                )
                            )
                            isNext -> drawCircle(
                                color = MeconColors.Orange,
                                radius = nodeR,
                                center = pt,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                                )
                            )
                            else -> drawCircle(
                                color = MeconColors.BorderLight.copy(alpha = 0.4f),
                                radius = nodeR,
                                center = pt,
                                style = Stroke(1.dp.toPx())
                            )
                        }

                        // Label — use pre-measured cache; no measurement per frame.
                        // Two-line chromatic labels are drawn as separate calls with firstBaseline
                        // positioning so Bravura's music-font line metrics don't create huge gaps.
                        val (m1, m2) = when {
                            isCurrent -> labelActive[pv]
                            isPrev    -> labelPrev[pv]
                            isNext    -> labelNext[pv]
                            else      -> labelInactive[pv]
                        }
                        if (m2 == null) {
                            drawText(m1, topLeft = Offset(pt.x - m1.size.width / 2f, pt.y - m1.size.height / 2f))
                        } else {
                            val gap = 5.dp.toPx()
                            // 0.65: glyphs sit above the baseline, so subtracting firstBaseline*1.0
                            // pushes the block above center; 0.65 ≈ the fraction that centers it visually.
                            drawText(m1, topLeft = Offset(pt.x - m1.size.width / 2f, pt.y - gap - m1.firstBaseline * 0.8f))
                            drawText(m2, topLeft = Offset(pt.x - m2.size.width / 2f, pt.y + gap - m2.firstBaseline * 0.8f))
                        }
                    }
                }
            } // end clipRect
        }

        // PLR legend
        Text(
            text = "P: \u2014  L: /  R: \\",
            fontSize = 9.sp,
            color = MeconColors.TextMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        )
    }
}
