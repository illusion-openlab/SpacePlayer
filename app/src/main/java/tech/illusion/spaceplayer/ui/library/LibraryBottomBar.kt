package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
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

        // "修正格式"入口只在纯兜底猜测时出现，见设计稿第 2 节 - the selectable types themselves
        // sit inline in this row (two SegmentControls, same widget the old FormatCorrectionPopup
        // used), not collapsed behind a single button/menu trigger.
        if (selectedItem != null && selectedItem.formatSource == FormatSource.DEFAULT) {
            // SegmentControl's own Row has no fillMaxWidth of its own, but each SegmentItem inside
            // it uses Modifier.weight(1f) - a Compose Row with a weighted child always expands to
            // its full incoming max width to have space to distribute, so without an explicit
            // width here a SegmentControl silently swallows the rest of this Row. IntrinsicSize.Max
            // (not Min) is required: Min sizes to the longest *unbreakable* word, which is smaller
            // than a multi-word label like "Side-by-side 3D" and forces that label to wrap into two
            // lines - Max sizes to the label's full single-line width instead.
            SegmentControl(modifier = Modifier.width(IntrinsicSize.Max).padding(end = 8.dp)) {
                Projection.entries.forEach { candidate ->
                    SegmentItem(
                        selected = selectedItem.projection == candidate,
                        onClick = { onFormatChange(candidate, selectedItem.stereoMode) },
                        title = { Text(candidate.label()) },
                    )
                }
            }
            SegmentControl(modifier = Modifier.width(IntrinsicSize.Max).padding(end = 8.dp)) {
                StereoMode.entries.forEach { candidate ->
                    SegmentItem(
                        selected = selectedItem.stereoMode == candidate,
                        onClick = { onFormatChange(selectedItem.projection, candidate) },
                        title = { Text(candidate.shortLabel()) },
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
