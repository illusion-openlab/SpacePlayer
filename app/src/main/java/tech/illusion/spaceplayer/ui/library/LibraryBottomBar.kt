package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.FormatMenuButton
import tech.illusion.spaceplayer.ui.FormatMenuButtonColors
import tech.illusion.spaceplayer.ui.FormatMenuButtonDefaults
import tech.illusion.spaceplayer.ui.label
import tech.illusion.spaceplayer.ui.shortLabel

// Real, uniform gap between every clickable element in this bar, applied once at the Row level
// instead of as manual per-child trailing padding - a per-child .padding(end = Ndp) is only a
// real gap if it comes AFTER that child's own .clickable()/.toggleable() in its modifier chain;
// several elements here had it BEFORE (dead space excluded from that child's own hit region, not
// space protecting it from its neighbor - confirmed for ToggleableChip and the old subtitle Text
// by decompiling design-0.13.3-sources.jar). Arrangement.spacedBy lives at the parent Row, so it
// can never be absorbed into or excluded from any child's own hit-test region.
private val BAR_ITEM_GAP = 16.dp

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onFormatChange: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
    onStartPlayback: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_ITEM_GAP),
    ) {
        if (selectedItem?.projection == Projection.FLAT) {
            val envChipColors = ChipsDefaults.toggleableChipColors(
                contentColor = SpacePlayerTextPrimary,
                backgroundColor = SpacePlayerSurface,
                activeContentColor = SpacePlayerOnAccent,
                activeBackgroundColor = SpacePlayerAccent,
            )
            Environment.entries.forEach { env ->
                ToggleableChip(
                    label = { Text(env.label()) },
                    isToggleOn = env == selectedEnvironment,
                    onClick = { onSelectEnvironment(env) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(env.dotColor(), CircleShape),
                        )
                    },
                    colors = envChipColors,
                    // Regular (40.dp), up from the default Small (32.dp) - the chip's own
                    // .toggleable() already wraps its full chipSize-tall box, so growing this
                    // tier grows the real tap target, not just the visual size.
                    chipSize = ChipsDefaults.Regular,
                )
            }
        } else if (selectedItem != null) {
            Text(
                text = stringResource(R.string.playback_panorama_auto_immersive),
                color = SpacePlayerTextSecondary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 投影/立体格式各自封装成一个菜单按钮，始终显示（不再按 formatSource == DEFAULT 隐藏）——
        // 之前用 SegmentControl 内联展开选项时，选中任何一项都会把 formatSource 从 DEFAULT 改成
        // MANUAL_OVERRIDE，导致这一整块选项随之消失，用户反馈这是不需要的"选完就关闭修正模式"的
        // 副作用。菜单按钮只是显示当前值，选值不会让按钮本身消失，天然规避了这个问题。
        if (selectedItem != null) {
            FormatMenuButton(label = selectedItem.projection.label()) { collapse ->
                Projection.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.label()) },
                        onClick = {
                            onFormatChange(candidate, selectedItem.stereoMode)
                            collapse()
                        },
                    )
                }
            }
            FormatMenuButton(label = selectedItem.stereoMode.shortLabel()) { collapse ->
                StereoMode.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.shortLabel()) },
                        onClick = {
                            onFormatChange(selectedItem.projection, candidate)
                            collapse()
                        },
                    )
                }
            }
            PillButton(
                text = stringResource(
                    if (selectedItem.subtitleUri != null) {
                        R.string.subtitle_status_set
                    } else {
                        R.string.subtitle_status_unset
                    },
                ),
                onClick = onPickSubtitle,
                contentColor = if (selectedItem.subtitleUri != null) {
                    SpacePlayerAccent
                } else {
                    SpacePlayerTextSecondary
                },
            )
        }

        Button(
            onClick = onStartPlayback,
            // Max (56.dp minHeight), up from the default Regular (48.dp) - the primary action in
            // this bar, now comparably-or-more prominent than the secondary controls beside it.
            size = ButtonDefaults.Max,
            colors = ButtonDefaults.buttonColors(
                containerColor = SpacePlayerAccent,
                contentColor = SpacePlayerOnAccent,
            ),
        ) {
            Text(
                text = stringResource(R.string.library_start_playback),
                color = SpacePlayerOnAccent,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
        }
    }
}

/**
 * A plain clickable pill with the same visual chrome as [FormatMenuButton] (rounded shape, 1.dp
 * border, background fill, 44.dp minimum height) but a single direct [onClick] instead of a
 * caret-plus-[com.pico.spatial.ui.design.menu.Menu] - used for the subtitle-picker trigger, which
 * previously had no chrome or dedicated minimum tap-target size at all (a bare clickable [Text]
 * with zero padding inside its hit region).
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    contentColor: Color,
    colors: FormatMenuButtonColors = FormatMenuButtonDefaults.libraryColors(),
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .spatialHoverEffect()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interactionSource)
            .border(1.dp, colors.borderColor, shape)
            .background(colors.containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        )
    }
}
