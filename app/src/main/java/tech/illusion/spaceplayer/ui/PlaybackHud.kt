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
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.LinkDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.PlaybackState
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

// This HUD floats over the video/skybox on top of a Material.Regular glass panel whose actual
// rendered tone (how dark/translucent it really looks) varies by device and isn't something this
// code controls - PicoTheme.colorScheme's adaptive roles assume a properly dark backdrop and read
// as low-contrast when the glass renders lighter than that in practice (confirmed against the
// approved mcp__visualize mockup, which specified these exact literal tones). Fixed instead of
// adaptive, matching the mockup pixel-for-pixel rather than hoping the adaptive system guesses
// the same tone the mockup did.
private val HudTimeTextColor = Color(0x99FFFFFF) // design-style: fixed-figma-color HUD time label
private val HudChipContent = Color(0xB3FFFFFF) // design-style: fixed-figma-color HUD inactive chip text
private val HudLinkContent = Color(0xB3FFFFFF) // design-style: fixed-figma-color HUD return-link text
private val HudDividerColor = Color(0x33FFFFFF) // design-style: fixed-figma-color HUD group divider
private val HudPrimaryIconContent = Color(0xFFFFFFFF) // design-style: fixed-figma-color HUD primary icon (play/pause)

// Two rounds of a hand-picked accent fill (a saturated red, then a matte black) both read as too
// heavy for this glass panel. Per direction: drop the fill entirely on the play/pause and mute
// IconButtons - containerColor = Color.Transparent - and let the SDK's own built-in
// spatialHoverEffect (already part of every IconButton's modifier chain, confirmed in decompiled
// Button.kt regardless of colors) carry the interactive feedback instead of a resting-state color.
// Inactive chips went the same way (Color.Transparent, no more HudChipBackground). The active chip
// still needs a persistent (not just hover-driven) fill so the group's selection reads without
// requiring a hover - color is a visual match to a reference screenshot of the SDK's own frosted
// glass hover tint, eyeballed rather than sourced from an API constant (SpatialHoverEffect's actual
// tint is applied natively, not exposed as a Kotlin Color).

// Format-correction pills: same component as the library bottom bar's, restyled for this glass
// panel - a barely-there fill plus a visible hairline border, so they read as "openable menus"
// rather than as another selected/unselected chip in the environment group next to them.
private val HudPillContainer = Color(0x12FFFFFF) // design-style: fixed-figma-color HUD format-pill fill
private val HudPillBorder = Color(0x38FFFFFF) // design-style: fixed-figma-color HUD format-pill border

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
    currentProjection: Projection,
    currentStereoMode: StereoMode,
    currentEnvironment: Environment,
    currentPositionMs: Long,
    durationMs: Long,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onSelectEnvironment: (Environment) -> Unit,
    onCorrectFormat: (Projection, StereoMode) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onReturnToMainWindow: () -> Unit,
) {
    val isFlatProjection = currentProjection == Projection.FLAT
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
                            containerColor = Color.Transparent,
                            contentColor = HudPrimaryIconContent,
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
                    // The environment/theme chips (影院/星空/海边) that used to sit here behind their
                    // own divider are hidden per user request. `currentEnvironment` /
                    // `onSelectEnvironment` stay on this composable's signature and the value still
                    // drives the flat-video backdrop - only the picker UI is gone, so restoring it
                    // is a matter of putting this block back, not rewiring state. For non-flat
                    // videos this slot only ever held an informational line, so nothing replaces it.
                    Spacer(modifier = Modifier.width(12.dp))
                    HudDivider()
                    Spacer(modifier = Modifier.width(12.dp))
                    // Format correction - the same two menus the library bottom bar shows, grouped
                    // after the environment chips behind a divider: both groups set "how this video
                    // is presented", while the controls on either end (play, mute, return) act on
                    // playback itself.
                    val pillColors = FormatMenuButtonColors(
                        containerColor = HudPillContainer,
                        contentColor = HudChipContent,
                        borderColor = HudPillBorder,
                        trailingContentColor = HudTimeTextColor,
                    )
                    FormatMenuButton(
                        label = currentProjection.label(),
                        modifier = Modifier.padding(end = 6.dp),
                        colors = pillColors,
                    ) { collapse ->
                        Projection.entries.forEach { candidate ->
                            MenuItem(
                                title = { Text(candidate.label()) },
                                onClick = {
                                    onCorrectFormat(candidate, currentStereoMode)
                                    collapse()
                                },
                            )
                        }
                    }
                    FormatMenuButton(
                        label = currentStereoMode.shortLabel(),
                        colors = pillColors,
                    ) { collapse ->
                        StereoMode.entries.forEach { candidate ->
                            MenuItem(
                                title = { Text(candidate.shortLabel()) },
                                onClick = {
                                    onCorrectFormat(currentProjection, candidate)
                                    collapse()
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onToggleMute,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Transparent,
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
                    Spacer(modifier = Modifier.width(12.dp))
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
            // No custom colors here per direction - SliderDefaults.sliderColors() (PicoTheme's own
            // adaptive roles) instead of a hand-picked fixed color, matching the same "let the
            // system handle it" call made above for the two IconButtons.
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatPlaybackTimestamp(safeDurationMs),
            color = HudTimeTextColor,
            style = PicoTheme.typography.bodySmall,
        )
    }
}
