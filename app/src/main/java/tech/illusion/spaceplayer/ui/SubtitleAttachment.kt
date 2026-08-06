package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/**
 * Visual style deliberately matches PlaybackHud/LoadingErrorAttachment's frosted-glass background
 * (Material.Regular) rather than porting StoryPico's raw black semi-transparent pill, to stay
 * consistent with the rest of SpacePlayer's HUD look.
 */
@Composable
fun SubtitleAttachment(text: String) {
    if (text.isEmpty()) return
    PicoTheme {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }
    }
}
