package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun PlaybackHud(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onExit: () -> Unit,
) {
    PicoTheme {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(16.dp),
        ) {
            Row {
                Button(onClick = onPlayPause) {
                    Text(
                        text = if (state == PlaybackState.PLAYING) "暂停" else "播放",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
                Button(onClick = onExit) {
                    Text(
                        text = "退出",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
            }
        }
    }
}
