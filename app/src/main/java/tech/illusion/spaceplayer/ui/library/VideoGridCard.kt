package tech.illusion.spaceplayer.ui.library

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.BadgeDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.ui.label

private const val THUMBNAIL_SIZE_PX = 480

// Reserves enough height for title + metadata so cards in the same grid row line up evenly.
private val INFO_BLOCK_MIN_HEIGHT = 96.dp

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(sizeBytes: Long): String {
    val mb = sizeBytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

@Composable
fun VideoGridCard(
    item: VideoItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var thumbnail by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    item.uri,
                    Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX),
                    null,
                )
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(shape)
            // Before clickable and after clip, per spatial-ui-design-style's modifier order, so the
            // native hover follows this card's rounded shape. 0.13.3 does have the simple overload
            // (SpatialHoverEffectStyle.kt) alongside the low-level block form in SpatialHoverEffect.kt -
            // an earlier note in AGENTS.md claiming only the block API exists was wrong.
            .spatialHoverEffect()
            .background(SpacePlayerSurface)
            .border(if (selected) 2.dp else 1.dp, if (selected) SpacePlayerAccent else SpacePlayerBorder, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interactionSource),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(SpacePlayerThumbnailPlaceholder),
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Badge(
                    badgeColor = BadgeDefaults.badgeColors(
                        backgroundColor = SpacePlayerTextPrimary,
                        contentColor = SpacePlayerOnAccent,
                    ),
                ) {
                    Text(
                        text = formatDuration(item.durationMs),
                        style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
            }

        }

        Column(modifier = Modifier.fillMaxWidth().heightIn(min = INFO_BLOCK_MIN_HEIGHT).padding(12.dp)) {
            Text(
                text = item.displayName,
                color = SpacePlayerTextPrimary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            )
            Text(
                text = "${item.formatSource.label()} · ${formatFileSize(item.sizeBytes)}",
                color = SpacePlayerTextTertiary,
                style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
