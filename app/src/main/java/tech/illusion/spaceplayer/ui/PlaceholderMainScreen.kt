package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.playback.StereoMode

@Composable
fun PlaceholderMainScreen() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Text(
                text = "SpacePlayer · Stage 1 手动测试",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 40.sp),
            )
            Button(onClick = {
                viewModel.startTestPlayback("videos/sample_flat_test.mp4", StereoMode.MONO)
                coroutineScope.launch {
                    navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
                }
            }) {
                Text(
                    text = "播放测试视频（平面）",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
