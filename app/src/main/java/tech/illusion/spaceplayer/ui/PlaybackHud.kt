package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun PlaybackHud(
    state: PlaybackState,
    isFlatProjection: Boolean,
    currentEnvironment: Environment,
    onPlayPause: () -> Unit,
    onSelectEnvironment: (Environment) -> Unit,
    onReturnToMainWindow: () -> Unit,
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
                        text = stringResource(
                            if (state == PlaybackState.PLAYING) R.string.playback_pause else R.string.playback_play,
                        ),
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
                if (isFlatProjection) {
                    Environment.entries.forEach { env ->
                        Button(onClick = { onSelectEnvironment(env) }) {
                            Text(
                                text = if (env == currentEnvironment) "[${env.label()}]" else env.label(),
                                color = PicoTheme.colorScheme.labelPrimary,
                                style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.playback_panorama_auto_immersive),
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
                Button(onClick = onReturnToMainWindow) {
                    Text(
                        text = stringResource(R.string.playback_return_to_main_window),
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
            }
        }
    }
}
