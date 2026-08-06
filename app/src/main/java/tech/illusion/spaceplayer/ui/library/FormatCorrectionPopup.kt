package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.SpatialPopup
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

private fun Projection.label() = when (this) {
    Projection.FLAT -> "平面"
    Projection.HEMISPHERE_180 -> "180°"
    Projection.SPHERE_360 -> "360°"
}

private fun StereoMode.label() = when (this) {
    StereoMode.MONO -> "单目"
    StereoMode.SIDE_BY_SIDE -> "左右 3D"
    StereoMode.TOP_AND_DOWN -> "上下 3D"
    StereoMode.MULTIVIEW_MVHEVC -> "MV-HEVC"
}

@Composable
fun FormatCorrectionPopup(
    initialProjection: Projection,
    initialStereoMode: StereoMode,
    onDismissRequest: () -> Unit,
    onConfirm: (Projection, StereoMode) -> Unit,
) {
    var projection by remember { mutableStateOf(initialProjection) }
    var stereoMode by remember { mutableStateOf(initialStereoMode) }

    SpatialPopup(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "修正格式",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
            SegmentControl(modifier = Modifier.padding(top = 8.dp)) {
                Projection.entries.forEach { candidate ->
                    SegmentItem(
                        selected = projection == candidate,
                        onClick = { projection = candidate },
                        title = {
                            Text(
                                text = candidate.label(),
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            )
                        },
                    )
                }
            }
            SegmentControl(modifier = Modifier.padding(top = 8.dp)) {
                StereoMode.entries.forEach { candidate ->
                    SegmentItem(
                        selected = stereoMode == candidate,
                        onClick = { stereoMode = candidate },
                        title = {
                            Text(
                                text = candidate.label(),
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            )
                        },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = onDismissRequest) {
                    Text(text = "取消", style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                }
                Button(onClick = { onConfirm(projection, stereoMode) }) {
                    Text(text = "确定", style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                }
            }
        }
    }
}
