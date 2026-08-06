package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.LinkDefaults
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

// This HUD floats over the video/skybox on top of a Material.Regular glass panel whose actual
// rendered tone (how dark/translucent it really looks) varies by device and isn't something this
// code controls - PicoTheme.colorScheme's adaptive roles assume a properly dark backdrop and read
// as low-contrast when the glass renders lighter than that in practice (confirmed against the
// approved mcp__visualize mockup, which specified these exact literal tones). Fixed instead of
// adaptive, matching the mockup pixel-for-pixel rather than hoping the adaptive system guesses
// the same tone the mockup did.
private val HudTrackColor = Color(0x33FFFFFF) // design-style: fixed-figma-color HUD progress track
private val HudTimeTextColor = Color(0x99FFFFFF) // design-style: fixed-figma-color HUD time label
private val HudChipBackground = Color(0x1FFFFFFF) // design-style: fixed-figma-color HUD inactive chip bg
private val HudChipContent = Color(0xB3FFFFFF) // design-style: fixed-figma-color HUD inactive chip text
private val HudLinkContent = Color(0xB3FFFFFF) // design-style: fixed-figma-color HUD return-link text
private val HudDividerColor = Color(0x33FFFFFF) // design-style: fixed-figma-color HUD group divider

// SpatialUI's own VerticalDivider is a one-line wrapper (Box(modifier.fillMaxHeight().width(t)
// .background(color))) - no hover/click/haptics behavior to preserve - so a plain sized Box here
// is equivalent, not a reimplementation, and sidesteps an observed inconsistency where the
// built-in rendered visible in one spot of this Row but not the other (unresolved, but this Box
// form measures/paints predictably either way).
@Composable
private fun HudDivider() {
    Box(modifier = Modifier.width(1.dp).height(24.dp).background(HudDividerColor))
}

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = SpacePlayerAccent,
                            contentColor = SpacePlayerOnAccent,
                        ),
                        size = IconButtonDefaults.Regular,
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
                    HudDivider()
                    Spacer(modifier = Modifier.width(12.dp))
                    if (isFlatProjection) {
                        val envChipColors = ChipsDefaults.toggleableChipColors(
                            contentColor = HudChipContent,
                            backgroundColor = HudChipBackground,
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
                            color = HudTimeTextColor,
                            style = PicoTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HudDivider()
                    Spacer(modifier = Modifier.width(12.dp))
                    Link(
                        onClick = onReturnToMainWindow,
                        colors = LinkDefaults.linkColors(contentColor = HudLinkContent),
                        trailingIcon = {
                            Icon(painter = painterResource(id = R.drawable.ic_return), contentDescription = null)
                        },
                    ) {
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
            color = HudTimeTextColor,
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
                trackColor = HudTrackColor,
                progressColor = SpacePlayerAccent,
                progressHighColor = SpacePlayerAccent,
                thumbColor = SpacePlayerAccent,
                thumbHighColor = SpacePlayerAccent,
            ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatPlaybackTimestamp(safeDurationMs),
            color = HudTimeTextColor,
            style = PicoTheme.typography.bodySmall,
        )
    }
}
