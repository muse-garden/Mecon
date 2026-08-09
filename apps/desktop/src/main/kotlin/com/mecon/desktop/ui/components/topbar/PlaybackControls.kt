package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.engine.PlaybackState
import com.mecon.audio.soundfont.SoundFontLoadState
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.i18n.i18n

/**
 * Transport group: play-from-start, play/pause, play-from-selection, the tempo
 * readout, and the audio-settings entry point.
 *
 * Reads playback state from [PlaybackController] and the score to play from
 * the active [RuntimeScore], calling the controller directly on each intent.
 */
@Composable
internal fun PlaybackControls(
    playback: PlaybackController,
    score: RuntimeScore?,
    tempoBpm: Int? = null,
    selectionTimeCode: TimeCode?,
    hasSelection: Boolean,
    onOpenAudioSettings: () -> Unit
) {
    val playbackState by playback.playbackState.collectAsState()
    val tempoMultiplier by playback.tempoMultiplier.collectAsState()
    val soundFontLoadState by playback.soundFontLoadState.collectAsState()
    val isPreparingSoundFont = soundFontLoadState is SoundFontLoadState.Loading

    ToolbarButton(
        icon = Icons.Default.SkipPrevious,
        label = i18n("toolbar.playFromStart"),
        enabled = score != null && !isPreparingSoundFont,
        onClick = { playback.playFromStart(score, tempoBpm) }
    )

    Spacer(Modifier.width(4.dp))

    PlayPauseButton(
        isPlaying = playbackState == PlaybackState.PLAYING,
        enabled = score != null && !isPreparingSoundFont,
        onPlay = { playback.playFromCurrent(score, tempoBpm) },
        onPause = playback::pause
    )

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.PlaylistPlay,
        label = i18n("toolbar.playFromSelection"),
        enabled = score != null && hasSelection && !isPreparingSoundFont,
        onClick = { playback.playFromSelection(score, selectionTimeCode, tempoBpm) }
    )

    Spacer(Modifier.width(8.dp))

    (soundFontLoadState as? SoundFontLoadState.Loading)?.let { loading ->
        Column(modifier = Modifier.width(170.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading.progress == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MeconColors.SelectedIcon
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    i18n("toolbar.soundfontLoading").replace("{name}", loading.soundFontName) +
                        (loading.total?.let { " ${loading.current}/$it" } ?: ""),
                    fontSize = 10.sp,
                    color = MeconColors.TextSecondary
                )
            }
            loading.progress?.let { progress ->
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(170.dp).height(3.dp),
                    color = MeconColors.SelectedIcon,
                    trackColor = MeconColors.Border,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
    }
    (soundFontLoadState as? SoundFontLoadState.Failed)?.let { failed ->
        Text(
            i18n("toolbar.soundfontLoadFailed").replace("{name}", failed.soundFontName),
            fontSize = 10.sp,
            color = Color(0xFFFCA5A5)
        )
        Spacer(Modifier.width(8.dp))
    }

    TempoReadout(tempoMultiplier = tempoMultiplier)

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.Settings,
        label = i18n("toolbar.audioSettings"),
        onClick = onOpenAudioSettings
    )
}

/** The prominent play/pause toggle with its bordered, state-tinted container. */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .background(
                if (isPlaying) MeconColors.SelectedSurface else MeconColors.Background,
                RoundedCornerShape(4.dp)
            )
            .border(1.dp, if (isPlaying) MeconColors.SelectedBorder else MeconColors.Border, RoundedCornerShape(4.dp))
            .hoverable(interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (isPlaying) onPause() else onPlay() }
            )
            .padding(4.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(18.dp),
            tint = if (!enabled) MeconColors.TextMuted else if (isPlaying || isHovered) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
        )
    }
}

/** Read-only tempo percentage display. */
@Composable
private fun TempoReadout(tempoMultiplier: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${(tempoMultiplier * 100).toInt()}%",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MeconColors.TextPrimary
        )
        Text(i18n("toolbar.playControl"), fontSize = 9.sp, color = MeconColors.TextMuted)
    }
}
