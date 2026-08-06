package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.PlaybackState
import tech.illusion.spaceplayer.ui.library.SpacePlayerAccent
import tech.illusion.spaceplayer.ui.library.SpacePlayerOnAccent
import tech.illusion.spaceplayer.ui.library.dotColor

@Composable
fun PlaybackHud(
    state: PlaybackState,
    isFlatProjection: Boolean,
    currentEnvironment: Environment,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSelectEnvironment: (Environment) -> Unit,
    onSeek: (Long) -> Unit,
    onReturnToMainWindow: () -> Unit,
) {
    PicoTheme {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaybackProgressRow(
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPlayPause,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = SpacePlayerAccent,
                            contentColor = SpacePlayerOnAccent,
                        ),
                        size = IconButtonDefaults.Small,
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (state == PlaybackState.PLAYING) R.drawable.ic_pause_bars else R.drawable.ic_play_triangle,
                            ),
                            contentDescription = stringResource(
                                if (state == PlaybackState.PLAYING) R.string.playback_pause else R.string.playback_play,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    if (isFlatProjection) {
                        val envChipColors = ChipsDefaults.toggleableChipColors(
                            activeContentColor = SpacePlayerOnAccent,
                            activeBackgroundColor = SpacePlayerAccent,
                        )
                        Environment.entries.forEach { env ->
                            ToggleableChip(
                                label = { Text(env.label()) },
                                isToggleOn = env == currentEnvironment,
                                onClick = { onSelectEnvironment(env) },
                                leadingIcon = {
                                    Box(modifier = Modifier.size(8.dp).background(env.dotColor(), CircleShape))
                                },
                                colors = envChipColors,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.playback_panorama_auto_immersive),
                            color = PicoTheme.colorScheme.labelSecondary,
                            style = PicoTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Link(onClick = onReturnToMainWindow) {
                        Text(text = stringResource(R.string.playback_return_to_main_window))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackProgressRow(
    currentPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    // While dragging, the Slider's own onValueChange fires continuously and this preview value
    // (not the still-lagging real currentPositionMs) drives both the thumb and the time label -
    // onSeek() only commits once the drag/tap ends, matching the SDK's documented
    // onValueChangeFinished contract instead of seeking the real player on every pixel of drag.
    var dragPreviewMs by remember { mutableStateOf<Long?>(null) }
    val displayedPositionMs = dragPreviewMs ?: currentPositionMs.coerceIn(0L, safeDurationMs)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatPlaybackTimestamp(displayedPositionMs),
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Slider(
            value = displayedPositionMs.toFloat(),
            onValueChange = { dragPreviewMs = it.toLong() },
            valueRange = 0f..safeDurationMs.toFloat(),
            onValueChangeFinished = {
                dragPreviewMs?.let(onSeek)
                dragPreviewMs = null
            },
            sliderSpec = SliderDefaults.Small,
            colors = SliderDefaults.sliderColors(
                progressColor = SpacePlayerAccent,
                progressHighColor = SpacePlayerAccent,
                thumbColor = SpacePlayerAccent,
                thumbHighColor = SpacePlayerAccent,
            ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatPlaybackTimestamp(safeDurationMs),
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall,
        )
    }
}
