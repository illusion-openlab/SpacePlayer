package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.SpatialPopup
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.fullLabel
import tech.illusion.spaceplayer.ui.label

@Composable
fun FormatCorrectionPopup(
    initialProjection: Projection,
    initialStereoMode: StereoMode,
    hasSubtitle: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
) {
    var projection by remember { mutableStateOf(initialProjection) }
    var stereoMode by remember { mutableStateOf(initialStereoMode) }

    SpatialPopup(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.format_popup_title),
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
                                text = candidate.fullLabel(),
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            )
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        if (hasSubtitle) R.string.subtitle_status_set else R.string.subtitle_status_unset,
                    ),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                )
                Button(onClick = onPickSubtitle) {
                    Text(
                        text = stringResource(R.string.subtitle_pick_file),
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onDismissRequest) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    )
                }
                Button(onClick = { onConfirm(projection, stereoMode) }) {
                    Text(
                        text = stringResource(R.string.action_confirm),
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    )
                }
            }
        }
    }
}
