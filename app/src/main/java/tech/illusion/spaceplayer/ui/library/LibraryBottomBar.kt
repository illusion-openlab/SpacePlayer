package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.FormatMenuButton
import tech.illusion.spaceplayer.ui.label
import tech.illusion.spaceplayer.ui.shortLabel

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
                    modifier = Modifier.padding(end = 8.dp),
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
            FormatMenuButton(
                label = selectedItem.projection.label(),
                modifier = Modifier.padding(end = 8.dp),
            ) { collapse ->
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
            FormatMenuButton(
                label = selectedItem.stereoMode.shortLabel(),
                modifier = Modifier.padding(end = 8.dp),
            ) { collapse ->
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
            val subtitleInteractionSource = remember { MutableInteractionSource() }
            Text(
                text = stringResource(
                    if (selectedItem.subtitleUri != null) {
                        R.string.subtitle_status_set
                    } else {
                        R.string.subtitle_status_unset
                    },
                ),
                color = if (selectedItem.subtitleUri != null) {
                    SpacePlayerAccent
                } else {
                    SpacePlayerTextSecondary
                },
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                modifier = Modifier
                    .padding(end = 20.dp)
                    .clickable(
                        interactionSource = subtitleInteractionSource,
                        indication = LocalIndication.current,
                        onClick = onPickSubtitle,
                    )
                    .controllerHapticFeedback(interactionSource = subtitleInteractionSource),
            )
        }

        Button(
            onClick = onStartPlayback,
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

