package com.mecon.plugins.chord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.KeySignature
import com.mecon.desktop.uikit.PitchMode
import com.mecon.desktop.uikit.rememberBravuraFont
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.plugins.chord.ComputedChordEvent
import com.mecon.plugins.chord.RuntimeChordEvent
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.theory.ChordSymbolDisplayStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 12-tone chromatic disk visualizing the pitch classes of [chord].
 *
 * Layout (top-to-bottom within [modifier]):
 *  - Single-row header: chord symbol (bold) + component note list
 *  - Canvas disk: outer dashed ring, radial spokes, polygon fill, node circles + labels
 *
 * Visual conventions:
 *  - Root node: [MeconColors.PrimaryDark] fill
 *  - Other chord-tone nodes: [MeconColors.SurfaceLight] fill + [MeconColors.PrimaryLight] stroke
 *  - Selected pitch classes: orange fill/stroke, including non-chord tones on the outer ring
 *  - Inactive pitch classes: small dot + outer label (muted)
 *  - Polygon: [MeconColors.Primary] fill (semi-transparent) + [MeconColors.PrimaryLight] stroke
 *
 * Chromatic notes display both enharmonic forms stacked vertically inside active
 * nodes ("♯C" / "♭D") and inline in the outer ring ("♯C/♭D"). Accidentals are
 * rendered at 72 % of the surrounding font size via an em-relative span.
 */
@Composable
internal fun ChordDisk(
    chord: StorageChordEvent,
    pitchMode: PitchMode = PitchMode.ABSOLUTE,
    chordSymbolStyle: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
    keySignature: KeySignature = KeySignature.C_MAJOR,
    selectedPitchClasses: Set<Int> = emptySet(),
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val bravuraFont  = rememberBravuraFont()

    val computed = remember(chord) {
        ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(chord))
    }
    val pitchClasses: Set<Int> = remember(computed) {
        computed.chord.pitchClasses.map { it.value }.toSet()
    }
    val rootPc = chord.root

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header row: symbol + pitch-class list
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chordSymbolAnnotatedText(
                    computed.formattedSymbol(chordSymbolStyle, keySignature),
                    chordSymbolStyle,
                ),
                fontSize = 18.sp,
                fontWeight = if (chordSymbolStyle == ChordSymbolDisplayStyle.LETTER) FontWeight.Bold else FontWeight.Normal,
                color = MeconColors.TextPrimary
            )
            Text(
                text = buildAnnotatedString {
                    pitchClasses.sorted().forEachIndexed { index, pc ->
                        if (index > 0) append(" · ")
                        append(pitchClassAnnotatedLabel(pc, pitchMode, keySignature, bravuraFont))
                    }
                },
                fontSize = 10.sp,
                color = MeconColors.TextMuted
            )
        }

        Spacer(Modifier.height(6.dp))

        // Disk canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)

            val radius     = minOf(size.width, size.height) * 0.31f
            val outerRingR = radius + 21.dp.toPx()

            // Outer dashed decorative ring
            drawCircle(
                color = MeconColors.SurfaceLight,
                radius = outerRingR,
                center = center,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
            )

            // Radial spokes from center to active nodes
            for (i in 0..11) {
                if (i !in pitchClasses) continue
                val angle = (i * 30f - 90f) * (PI / 180.0).toFloat()
                val pt = Offset(cx + radius * cos(angle), cy + radius * sin(angle))
                drawLine(MeconColors.Primary.copy(alpha = 0.2f), center, pt, 1.5.dp.toPx())
            }

            // Polygon fill connecting chord tones
            val chordPoints = pitchClasses.sorted().map { i ->
                val angle = (i * 30f - 90f) * (PI / 180.0).toFloat()
                Offset(cx + radius * cos(angle), cy + radius * sin(angle))
            }
            if (chordPoints.size >= 2) {
                val path = Path().apply {
                    moveTo(chordPoints[0].x, chordPoints[0].y)
                    for (pt in chordPoints.drop(1)) lineTo(pt.x, pt.y)
                    close()
                }
                if (chordPoints.size >= 3) drawPath(path, color = MeconColors.Primary.copy(alpha = 0.22f))
                drawPath(path, color = MeconColors.PrimaryLight, style = Stroke(width = 2.dp.toPx()))
            }

            // Nodes and labels
            val activeR   = 16.5.dp.toPx()
            val inactiveR = 3.75.dp.toPx()

            for (i in 0..11) {
                val angle    = (i * 30f - 90f) * (PI / 180.0).toFloat()
                val pt       = Offset(cx + radius * cos(angle), cy + radius * sin(angle))
                val isActive = i in pitchClasses
                val isRoot   = i == rootPc
                val isSelected = i in selectedPitchClasses

                if (isActive) {
                    val fillColor = when {
                        isSelected -> MeconColors.Orange
                        isRoot -> MeconColors.PrimaryDark
                        else -> MeconColors.SurfaceLight
                    }
                    val strokeColor = if (isSelected) MeconColors.OrangeLight else MeconColors.PrimaryLight
                    drawCircle(
                        color = fillColor,
                        radius = activeR, center = pt
                    )
                    drawCircle(
                        color = strokeColor, radius = activeR, center = pt,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    if (isSelected) {
                        drawCircle(
                            color = MeconColors.OrangeLight.copy(alpha = 0.35f),
                            radius = activeR + 4.dp.toPx(),
                            center = pt,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Chromatic notes: two stacked lines ("♯C" / "♭D")
                    // Diatonic notes: single centered line ("C" / "3")
                    val parts = pitchClassLabelParts(i, pitchMode, keySignature, bravuraFont)
                    val nodeStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (parts.size == 1) {
                        val m = textMeasurer.measure(parts[0], nodeStyle.copy(fontSize = 12.sp))
                        drawText(m, topLeft = Offset(pt.x - m.size.width / 2f, pt.y - m.size.height / 2f))
                    } else {
                        val lineStyle = nodeStyle.copy(fontSize = 10.sp)
                        val m1 = textMeasurer.measure(parts[0], lineStyle)
                        val m2 = textMeasurer.measure(parts[1], lineStyle)
                        val gap = 5.dp.toPx()
                        drawText(m1, topLeft = Offset(pt.x - m1.size.width / 2f, pt.y - gap - m1.firstBaseline * 0.8f))
                        drawText(m2, topLeft = Offset(pt.x - m2.size.width / 2f, pt.y + gap - m2.firstBaseline * 0.8f))
                    }
                } else {
                    drawCircle(
                        color = if (isSelected) MeconColors.OrangeLight else MeconColors.TextDark,
                        radius = if (isSelected) inactiveR * 1.65f else inactiveR,
                        center = pt
                    )
                    val outerPt = Offset(cx + outerRingR * cos(angle), cy + outerRingR * sin(angle))
                    val outerStyle = TextStyle(
                        color = if (isSelected) MeconColors.OrangeLight else MeconColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                    val parts = pitchClassLabelParts(i, pitchMode, keySignature, bravuraFont)
                    if (parts.size == 1) {
                        val m = textMeasurer.measure(parts[0], outerStyle)
                        drawText(m, topLeft = Offset(outerPt.x - m.size.width / 2f, outerPt.y - m.size.height / 2f))
                    } else {
                        val m1 = textMeasurer.measure(parts[0], outerStyle)
                        val m2 = textMeasurer.measure(parts[1], outerStyle)
                        val gap = 5.dp.toPx()
                        drawText(m1, topLeft = Offset(outerPt.x - m1.size.width / 2f, outerPt.y - gap - m1.firstBaseline * 0.8f))
                        drawText(m2, topLeft = Offset(outerPt.x - m2.size.width / 2f, outerPt.y + gap - m2.firstBaseline * 0.8f))
                    }
                }
            }

            drawCircle(color = MeconColors.TextDark, radius = 3.dp.toPx(), center = center)
        }
    }
}
