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
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.graphics.SpatialHoverStyle
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
// instead of as manual per-child trailing padding. Correction after a final-review pass actually
// decompiled Chips.kt/FormatMenuButton.kt: the environment ToggleableChip's and the old
// FormatMenuButton's per-child .padding(end = Ndp) were NOT dead zones - both are wrap-content
// (only a defaultMinSize floor, no fixed outer .height()/.size()), so padding placed before their
// .toggleable()/.clickable() only adds real space around the full hit region, it can't shrink it.
// The one genuine dead-zone bug this class of mistake ever caused was the sidebar's
// SideNavigationItem (see MainLibraryScreen.kt's SIDEBAR_ITEM_GAP comment): there, an outer FIXED
// .height() sits between the padding and .clickable(), so the padding is forced to carve its
// space out of that fixed budget instead of adding to it. The old subtitle Text's real problem was
// different again - zero padding INSIDE its hit region (nothing to grow the tap target beyond the
// bare glyph bounds), not padding excluded from one. Arrangement.spacedBy here is still the right,
// simpler choice regardless (real space between siblings, immune to any child's own modifier
// chain), just not because every element it replaces was individually broken.
private val BAR_ITEM_GAP = 28.dp

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
        // The environment/theme chips (影院/星空/海边) that used to lead this bar are hidden per
        // user request, along with the "全景视频自动进入沉浸" line that took their place for
        // non-flat videos. `selectedEnvironment`/`onSelectEnvironment` stay on the signature and
        // the value still feeds VideoItem.preferredEnvironment at playback start - only the picker
        // UI is gone, so restoring it means putting this block back, not rewiring state.
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
            // Highlight, not the Default style: SpatialHoverStyle.Default is documented as
            // "determined by the system" and is subtle enough that these pills read as having no
            // hover feedback at all. Highlight overlays a visible highlight on the target view.
            .spatialHoverEffect(SpatialHoverStyle.Highlight)
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
