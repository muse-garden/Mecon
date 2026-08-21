package com.mecon.desktop.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.engine.PlaybackState
import com.mecon.renderer.render.TimeCodePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the playhead is, and where on the page that lands. */
internal data class ScorePlaybackMapping(
    /** Tick → rendered slot, sorted by tick. Null unless a playhead can actually be shown. */
    val tickToX: List<Pair<Long, TimeCodePosition>>?,
    /** Playback ticks mapped back through repeats/holds to a position in the written score. */
    val scorePositionTicks: Long,
)

/**
 * Resolve the playhead's position for the current frame.
 *
 * Both halves are O(score) and therefore built off the UI thread, and only while a playhead can
 * actually be shown or followed — see [docs/performance/large-score-editing.md]: no `produceState`
 * here may do whole-score work on the Compose thread. Superseded computations are discarded by
 * `produceState` cancellation, and identity keys keep structural score equality out of the hot path.
 */
@Composable
internal fun rememberScorePlaybackMapping(
    score: RuntimeScore?,
    frame: ScoreRenderFrame,
    playbackState: PlaybackState,
    currentPositionTicks: Long,
): ScorePlaybackMapping {
    val needsPositionMap =
        playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.PAUSED
    val tickToX by produceState<List<Pair<Long, TimeCodePosition>>?>(
        initialValue = null,
        frame.identityKey,
        frame.scoreIdentityKey,
        needsPositionMap,
    ) {
        val playbackScore = score
        val result = frame.result
        value = if (needsPositionMap && result != null && playbackScore != null) {
            withContext(Dispatchers.Default) {
                val startedAt = System.nanoTime()
                val positions = result.timeCodePositions.values.toList()
                // One batched measure-offset pass rather than a conversion per TimeCode.
                val ticks = ScoreToMidiConverter.timeCodesToTicks(
                    positions.map { it.timeCode },
                    playbackScore,
                )
                positions.zip(ticks) { position, tick -> tick to position }
                    .sortedBy { it.first }
                    .also { mapping ->
                        com.mecon.renderer.debug.PerfLog.log("render.compose") {
                            "playbackPositionMap=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                                "entries=${mapping.size}"
                        }
                    }
            }
        } else null
    }
    // Repeat/hold expansion can scan every voice for each fermata, so it never runs on the UI thread:
    // starting playback (and edits made while playing) must not stall Compose.
    val timeline by produceState<ScoreToMidiConverter.PlaybackTimeline?>(
        initialValue = null,
        frame.scoreIdentityKey,
        needsPositionMap,
    ) {
        value = if (needsPositionMap) {
            val playbackScore = score
            if (playbackScore == null) null else withContext(Dispatchers.Default) {
                val startedAt = System.nanoTime()
                ScoreToMidiConverter.playbackTimeline(playbackScore).also { built ->
                    com.mecon.renderer.debug.PerfLog.log("render.compose") {
                        "playbackTimeline=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                            "occurrences=${built.occurrences.size}"
                    }
                }
            }
        } else null
    }
    return ScorePlaybackMapping(
        tickToX = tickToX,
        scorePositionTicks = timeline?.sourceTicksAt(currentPositionTicks) ?: currentPositionTicks,
    )
}

/**
 * Keep the playhead on screen while playing, unless the user has taken over by panning.
 *
 * Paginated only: continuous mode has no page grid to scroll the playhead into.
 */
@Composable
internal fun ScorePlayheadFollowEffect(
    viewport: RenderedScoreViewportState,
    frame: ScoreRenderFrame,
    playback: ScorePlaybackMapping,
    playbackState: PlaybackState,
    density: Float,
) {
    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.PLAYING) viewport.followPlayback.value = true
    }
    LaunchedEffect(
        playbackState,
        playback.scorePositionTicks,
        playback.tickToX,
        frame.pages,
        frame.pageSlots,
        frame.paginated,
        viewport.viewportSize.value,
        viewport.scale.value,
        viewport.followPlayback.value,
        density,
    ) {
        val viewportSize = viewport.viewportSize.value
        if (
            playbackState != PlaybackState.PLAYING || !viewport.followPlayback.value ||
            !frame.paginated || viewportSize == Size.Zero || playback.tickToX.isNullOrEmpty()
        ) return@LaunchedEffect

        val mapping = playback.tickToX ?: return@LaunchedEffect
        val matchIndex = mapping.indexOfLast { it.first <= playback.scorePositionTicks }
        val position = if (matchIndex < 0) mapping.first().second else mapping[matchIndex].second
        val playheadTop = globalToDesign(position.x, position.topY, frame.pages, frame.pageSlots)
            ?: return@LaunchedEffect
        val playheadBottom = globalToDesign(position.x, position.bottomY, frame.pages, frame.pageSlots)
            ?: return@LaunchedEffect
        viewport.offset.value = scorePlayheadFollowOffset(
            currentOffset = viewport.offset.value,
            playheadTop = playheadTop,
            playheadBottom = playheadBottom,
            viewportSize = viewportSize,
            scale = viewport.scale.value,
            density = density,
        )
    }
}
