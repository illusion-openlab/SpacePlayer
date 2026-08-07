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

// Was SpacePlayerAccent (a saturated "watermelon" red shared with the library screens) - too loud
// against this HUD's own dark glass. Swapped for a HUD-local near-black instead of literally
// re-invoking backgroundMaterial on these shapes: IconButton/ToggleableChip/Slider each clip and
// paint their own solid containerColor over whatever modifier the caller passes in, so a second
// live blur pass here would fight each component's own clip shape for uncertain visual benefit. A
// solid, mostly-opaque near-black already reads as frosted dark glass sitting on top of the panel's
// own Material.Regular backdrop, and keeps good contrast against the lighter unselected chips/track.
private val HudAccentContainer = Color(0xE6151515) // design-style: fixed-figma-color HUD accent (matte black glass)
private val HudAccentContent = Color(0xFFFFFFFF) // design-style: fixed-figma-color HUD accent content

// AttachmentPanel defaults to WRAP_CONTENT (confirmed via decompiled core-0.13.3-sources.jar's
// AttachmentPanelComponent.kt), which the native side measures with an AT_MOST bound of
// MAX_PANEL_SIZE_DP (2048dp) - so any fillMaxWidth() inside an unconstrained panel stretches all
// the way out to that bound instead of to some sensible toolbar width (this is what made the panel
// span almost the full view after the previous alignment fix added fillMaxWidth() to the button
// row). Giving the panel this explicit width turns it back into a bounded container, so the two
// rows' own fillMaxWidth()/weight(1f) calls resolve against a sane number instead.
private val HudPanelWidth = 640.dp

// SpatialUI's own VerticalDivider is a one-line wrapper (Box(modifier.fillMaxHeight().width(t)
// .background(color))) - no hover/click/haptics behavior to preserve - so a plain sized Box here
// is equivalent, not a reimplementation. Measured this panel's actual on-screen pixel width against
// its dp width in an emulator screenshot: ~0.83px per dp, since the AttachmentPanel is a 2D texture
// composited into the 3D scene rather than rendered 1:1 to the display like a normal window - a 1dp
// divider lands under a physical pixel and gets anti-aliased away (this is almost certainly what
// made the previous 1dp VerticalDivider attempt show up in one spot and not the other - sub-pixel
// rounding noise, not a genuine SDK inconsistency). 3dp survives that downscale reliably.
@Composable
private fun HudDivider() {
    Box(modifier = Modifier.width(3.dp).height(24.dp).background(HudDividerColor))
}

@Composable
fun PlaybackHud(
    state: PlaybackState,
    isFlatProjection: Boolean,
    currentEnvironment: Environment,
    currentPositionMs: Long,
    durationMs: Long,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onSelectEnvironment: (Environment) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onReturnToMainWindow: () -> Unit,
) {
    PicoTheme {
        Box(
            modifier = Modifier
                .width(HudPanelWidth)
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
                            containerColor = HudAccentContainer,
                            contentColor = HudAccentContent,
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
                            activeContentColor = HudAccentContent,
                            activeBackgroundColor = HudAccentContainer,
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
                    IconButton(
                        onClick = onToggleMute,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = HudChipBackground,
                            contentColor = HudChipContent,
                        ),
                        size = IconButtonDefaults.Small,
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                            ),
                            contentDescription = stringResource(
                                if (isMuted) R.string.playback_unmute else R.string.playback_mute,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPlaybackTimestamp(displayedPositionMs),
            color = HudTimeTextColor,
            style = PicoTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Slider(
            modifier = Modifier.weight(1f),
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
                progressColor = HudAccentContainer,
                progressHighColor = HudAccentContainer,
                thumbColor = HudAccentContainer,
                thumbHighColor = HudAccentContainer,
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
