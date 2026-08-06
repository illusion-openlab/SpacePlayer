package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onStartPlayback: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (selectedItem?.projection == Projection.FLAT) {
            Environment.entries.forEach { env ->
                Button(onClick = { onSelectEnvironment(env) }) {
                    Text(
                        text = if (env == selectedEnvironment) "[${env.label}]" else env.label,
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    )
                }
            }
        } else if (selectedItem != null) {
            Text(
                text = "全景视频 · 自动沉浸",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }
        Button(onClick = onStartPlayback) {
            Text(
                text = "开始播放",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
        }
    }
}
