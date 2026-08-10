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
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.ui.label

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onRequestFormatCorrection: () -> Unit,
    onStartPlayback: () -> Unit,
) {
    // fillMaxSize (not fillMaxWidth) so this Row adopts whatever fixed height its parent Box is
    // given (MainLibraryScreen.kt's FOOTER_HEIGHT) - verticalAlignment only has room to center
    // children within a height the Row doesn't already just wrap.
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

        // "修正格式"入口只在纯兜底猜测时出现，见设计稿第 2 节 - moved here from each grid card so
        // it sits next to "开始播放" as one deliberate pre-playback step, rather than living inside
        // the card's own click target (which only ever selects the item, never plays it directly).
        if (selectedItem?.formatSource == FormatSource.DEFAULT) {
            val correctionInteractionSource = remember { MutableInteractionSource() }
            Text(
                text = stringResource(R.string.format_popup_title),
                color = SpacePlayerAccent,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                modifier = Modifier
                    .padding(end = 20.dp)
                    .clickable(
                        interactionSource = correctionInteractionSource,
                        indication = LocalIndication.current,
                        onClick = onRequestFormatCorrection,
                    )
                    .controllerHapticFeedback(interactionSource = correctionInteractionSource),
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
