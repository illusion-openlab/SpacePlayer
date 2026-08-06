package tech.illusion.spaceplayer.ui.library

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

private const val THUMBNAIL_SIZE_PX = 160

private fun Projection.label(): String = when (this) {
    Projection.FLAT -> "平面"
    Projection.HEMISPHERE_180 -> "180°"
    Projection.SPHERE_360 -> "360°"
}

private fun StereoMode.label(): String? = when (this) {
    StereoMode.MONO -> null
    StereoMode.SIDE_BY_SIDE -> "SBS"
    StereoMode.TOP_AND_DOWN -> "TB"
    StereoMode.MULTIVIEW_MVHEVC -> "MV-HEVC"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun VideoListCard(
    item: VideoItem,
    selected: Boolean,
    onClick: () -> Unit,
    onRequestFormatCorrection: () -> Unit,
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

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.listItemColors(
            backgroundColor = if (selected) {
                PicoTheme.colorScheme.fillSecondary
            } else {
                PicoTheme.colorScheme.fillLight
            },
        ),
        leadingContent = {
            Box(modifier = Modifier.size(56.dp)) {
                thumbnail?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = item.displayName)
                }
            }
        },
        headlineContent = {
            Text(
                text = item.displayName,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        },
        supportingContent = {
            Text(
                text = formatDuration(item.durationMs),
                color = PicoTheme.colorScheme.labelTertiary,
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            )
        },
        trailingContent = {
            Box {
                Badge {
                    val stereoLabel = item.stereoMode.label()
                    Text(
                        text = if (stereoLabel != null) {
                            "${item.projection.label()} $stereoLabel"
                        } else {
                            item.projection.label()
                        },
                        style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
            }
        },
    )

    // "修正格式"入口只在纯兜底猜测时出现，见设计稿第 2 节。用独立可点击 Text 而不是叠加在 ListItem
    // 的 trailingContent 上，避免和上面的点击选中手势冲突。
    if (item.formatSource == FormatSource.DEFAULT) {
        Text(
            text = "修正格式",
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier
                .padding(start = 16.dp, bottom = 8.dp)
                .clickable(onClick = onRequestFormatCorrection),
        )
    }
}
