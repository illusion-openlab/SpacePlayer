package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
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
import tech.illusion.spaceplayer.ui.fullLabel
import tech.illusion.spaceplayer.ui.label

// Height of the primary row (environment chips / start playback) - matches the sidebar's "其它"
// action height (MainLibraryScreen.kt's FOOTER_HEIGHT) so the two stay visually aligned whenever
// the correction row below isn't also showing.
private val PRIMARY_ROW_HEIGHT = 56.dp

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onFormatChange: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
    onStartPlayback: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // "修正格式"入口只在纯兜底猜测时出现，见设计稿第 2 节 - the selectable types themselves
        // sit inline here (two SegmentControls, same widget the old FormatCorrectionPopup used),
        // not collapsed behind a single button/menu trigger. Kept on its own row above the
        // primary row rather than crammed into it - environment chips + both SegmentControls +
        // subtitle text + start button all together overflow past the window's right edge.
        if (selectedItem != null && selectedItem.formatSource == FormatSource.DEFAULT) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // SegmentControl's own Row has no fillMaxWidth of its own, but each SegmentItem
                // inside it uses Modifier.weight(1f) - a Compose Row with a weighted child always
                // expands to its full incoming max width to have space to distribute, so without
                // width(IntrinsicSize.Min) here a single SegmentControl silently swallows the rest
                // of this Row and pushes every later sibling (the other SegmentControl, the
                // subtitle text) out of the visible area entirely.
                SegmentControl(modifier = Modifier.width(IntrinsicSize.Min).padding(end = 8.dp)) {
                    Projection.entries.forEach { candidate ->
                        SegmentItem(
                            selected = selectedItem.projection == candidate,
                            onClick = { onFormatChange(candidate, selectedItem.stereoMode) },
                            title = { Text(candidate.label()) },
                        )
                    }
                }
                SegmentControl(modifier = Modifier.width(IntrinsicSize.Min).padding(end = 8.dp)) {
                    StereoMode.entries.forEach { candidate ->
                        SegmentItem(
                            selected = selectedItem.stereoMode == candidate,
                            onClick = { onFormatChange(selectedItem.projection, candidate) },
                            title = { Text(candidate.fullLabel()) },
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

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
                        .clickable(
                            interactionSource = subtitleInteractionSource,
                            indication = LocalIndication.current,
                            onClick = onPickSubtitle,
                        )
                        .controllerHapticFeedback(interactionSource = subtitleInteractionSource),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(PRIMARY_ROW_HEIGHT),
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
}
