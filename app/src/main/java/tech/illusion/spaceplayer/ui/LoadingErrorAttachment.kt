package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun LoadingErrorAttachment(state: PlaybackState) {
    PicoTheme {
        Box(
            modifier = androidx.compose.ui.Modifier
                .size(480.dp, 200.dp)
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    when (state) {
                        PlaybackState.ERROR -> R.string.playback_load_failed
                        else -> R.string.playback_loading
                    },
                ),
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp),
            )
        }
    }
}
