package com.mecon.desktop.uikit.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class FifthsKeyMode { MAJOR, MINOR }

data class FifthsKey(
    val fifths: Int,
    val mode: FifthsKeyMode,
) {
    init {
        require(fifths in -7..7)
    }

    internal val circleSlot: Int
        get() = fifths.mod(CIRCLE_SLOT_COUNT)

    internal fun isEnharmonicWith(other: FifthsKey): Boolean =
        mode == other.mode && circleSlot == other.circleSlot
}

/**
 * Shared fifth-circle picker used by exploration and score-analysis tools.
 *
 * The component owns only geometry and selection visuals. Tonal spelling and
 * domain conversion stay with callers through [label].
 */
@Composable
fun CircleOfFifthsPicker(
    selectedKeys: Set<FifthsKey>,
    onKeyClick: (FifthsKey) -> Unit,
    modifier: Modifier = Modifier,
    currentKey: FifthsKey? = null,
    matchedKeys: Set<FifthsKey> = emptySet(),
    size: Dp = 320.dp,
    centerLabel: String? = null,
    centerCaption: String? = null,
    label: (FifthsKey) -> String,
) {
    val center = size.value / 2.0
    val nodeDiameter = when {
        size >= 400.dp -> 40.dp
        size >= 320.dp -> 36.dp
        else -> 30.dp
    }
    val outerFontSize = if (size >= 320.dp) 9.sp else 8.sp
    val innerFontSize = if (size >= 320.dp) 10.sp else 9.sp
    val outerRadius = size.value * 0.385
    val innerRadius = size.value * 0.255
    val innerStagger = size.value * 0.015
    Box(
        modifier = modifier
            .size(size)
            .background(MeconColors.Background.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .border(1.dp, MeconColors.Border, RoundedCornerShape(12.dp)),
    ) {
        Canvas(Modifier.size(size)) {
            val centerPx = Offset(this.size.width / 2f, this.size.height / 2f)
            val outerGuideRadius = this.size.minDimension * 0.385f
            val innerGuideRadius = this.size.minDimension * 0.255f
            drawCircle(MeconColors.Border, outerGuideRadius, centerPx, style = Stroke(2f))
            drawCircle(MeconColors.Border, innerGuideRadius, centerPx, style = Stroke(2f))
            (0 until CIRCLE_SLOT_COUNT).forEach { slot ->
                val angle = angleForCircleSlot(slot)
                drawLine(
                    color = MeconColors.Border.copy(alpha = 0.55f),
                    start = centerPx,
                    end = centerPx + polar(outerGuideRadius, angle),
                    strokeWidth = 1f,
                )
            }
        }
        FifthsKeyMode.entries.forEach { mode ->
            (0 until CIRCLE_SLOT_COUNT).forEach { circleSlot ->
                val fifthsAtSlot = fifthsForCircleSlot(circleSlot)
                val representativeFifths = fifthsAtSlot.first()
                val angle = angleForCircleSlot(circleSlot)
                val staggerDirection = if ((representativeFifths + 7) % 2 == 0) -1.0 else 1.0
                val taperedInnerStagger = innerStagger * abs(cos(angle))
                val radius = if (mode == FifthsKeyMode.MAJOR) {
                    outerRadius
                } else {
                    innerRadius + staggerDirection * taperedInnerStagger
                }
                val x = center + cos(angle) * radius - nodeDiameter.value / 2.0
                val y = center + sin(angle) * radius - nodeDiameter.value / 2.0
                val keys = fifthsAtSlot.map { FifthsKey(it, mode) }
                FifthsKeyGroupNode(
                    keys = keys,
                    currentKey = currentKey,
                    selectedKeys = selectedKeys,
                    matchedKeys = matchedKeys,
                    label = label,
                    onKeyClick = onKeyClick,
                    diameter = nodeDiameter,
                    fontSize = if (mode == FifthsKeyMode.MINOR) {
                        innerFontSize
                    } else {
                        outerFontSize
                    },
                    modifier = Modifier.offset(x.dp, y.dp),
                )
            }
        }
        if (centerLabel != null || centerCaption != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                centerLabel?.let {
                    Text(it, color = MeconColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                centerCaption?.let {
                    Text(it, color = MeconColors.TextMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun FifthsKeyGroupNode(
    keys: List<FifthsKey>,
    currentKey: FifthsKey?,
    selectedKeys: Set<FifthsKey>,
    matchedKeys: Set<FifthsKey>,
    label: (FifthsKey) -> String,
    onKeyClick: (FifthsKey) -> Unit,
    diameter: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
) {
    require(keys.size in 1..2)
    if (keys.size == 1) {
        val key = keys.single()
        FifthsKeyNode(
            label = label(key),
            current = key == currentKey,
            selected = key in selectedKeys,
            matched = key in matchedKeys,
            onClick = { onKeyClick(key) },
            diameter = diameter,
            fontSize = fontSize,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .border(1.dp, MeconColors.Border, CircleShape),
    ) {
        Row(Modifier.size(diameter)) {
            keys.forEach { key ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            keyNodeBackground(
                                current = key == currentKey,
                                selected = key in selectedKeys,
                                matched = key in matchedKeys,
                            )
                        )
                        .clickable { onKeyClick(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(key),
                        color = keyNodeContentColor(
                            current = key == currentKey,
                            selected = key in selectedKeys,
                            matched = key in matchedKeys,
                        ),
                        fontSize = fontSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(MeconColors.Border)
        )
    }
}

@Composable
private fun FifthsKeyNode(
    label: String,
    current: Boolean,
    selected: Boolean,
    matched: Boolean,
    onClick: () -> Unit,
    diameter: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
) {
    val background = keyNodeBackground(current, selected, matched)
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(
                1.dp,
                if (current || selected || matched) background else MeconColors.Border,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = keyNodeContentColor(current, selected, matched),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun keyNodeBackground(
    current: Boolean,
    selected: Boolean,
    matched: Boolean,
): Color =
    when {
        current -> MeconColors.Orange.copy(alpha = 0.92f)
        selected -> MeconColors.Primary.copy(alpha = 0.9f)
        matched -> MeconColors.Emerald.copy(alpha = 0.72f)
        else -> MeconColors.SurfaceDark.copy(alpha = 0.94f)
    }

/**
 * Highlighted nodes (current/selected/matched) sit on a saturated fill, so pure white text
 * stays legible in both themes; the default neutral fill tracks the theme's surface tone
 * and needs the reactive text role instead, or it goes invisible on a light skin.
 */
private fun keyNodeContentColor(current: Boolean, selected: Boolean, matched: Boolean): Color =
    if (current || selected || matched) MeconColors.White else MeconColors.TextPrimary

private fun angleForCircleSlot(slot: Int): Double =
    -PI / 2.0 + slot * (2.0 * PI / CIRCLE_SLOT_COUNT)

internal fun fifthsForCircleSlot(slot: Int): List<Int> {
    require(slot in 0 until CIRCLE_SLOT_COUNT)
    return (-7..7)
        .filter { it.mod(CIRCLE_SLOT_COUNT) == slot }
        .sortedDescending()
}

private fun polar(radius: Float, angle: Double): Offset =
    Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())

private const val CIRCLE_SLOT_COUNT = 12
