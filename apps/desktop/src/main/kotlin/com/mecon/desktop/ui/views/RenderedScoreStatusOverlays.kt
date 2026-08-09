package com.mecon.desktop.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun BoxScope.RenderedScoreStatusOverlays(
    interactionBlocked: Boolean,
    documentLoading: Boolean,
    showRenderUpdatingLabel: Boolean,
    scale: Float,
    showZoomIndicator: Boolean,
) {
    if (interactionBlocked) PointerBlockingOverlay()
    if (documentLoading) DocumentLoadingOverlay()
    if (showRenderUpdatingLabel) {
        Text(
            text = i18n("score.updating"),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(MeconColors.Surface, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 11.sp,
            color = MeconColors.TextSecondary,
        )
    }
    if (showZoomIndicator) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .background(MeconColors.Surface, RoundedCornerShape(4.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${(scale * 100).toInt()}%",
                fontSize = 10.sp,
                color = MeconColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun PointerBlockingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

@Composable
private fun BoxScope.DocumentLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeconColors.Background.copy(alpha = 0.62f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(MeconColors.Surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MeconColors.Selection,
            )
            Text(
                text = i18n("score.loading"),
                fontSize = 12.sp,
                color = MeconColors.TextSecondary,
            )
        }
    }
}
