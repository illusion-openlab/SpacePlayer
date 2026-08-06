package tech.illusion.spaceplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.Text

@Composable
fun MainLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val libraryViewModel = remember { LibraryViewModel(context) }

    var hasVideoPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasVideoPermission = granted }

    LaunchedEffect(hasVideoPermission) {
        if (hasVideoPermission) {
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
        }
    }

    PicoTheme {
        if (!hasVideoPermission) {
            Column(modifier = modifier.fillMaxSize().padding(32.dp)) {
                Text(
                    text = "SpacePlayer 需要访问本机视频的权限才能显示视频库",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                )
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) }) {
                    Text(
                        text = "去授权",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    )
                }
            }
            return@PicoTheme
        }

        Row(modifier = modifier.fillMaxSize()) {
            SideNavigation {
                LibraryCategory.entries.forEach { category ->
                    SideNavigationItem(
                        selected = libraryViewModel.selectedCategory == category,
                        modifier = Modifier.clickable(onClick = { libraryViewModel.selectCategory(category) }),
                        content = {
                            Text(
                                text = category.label,
                                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            )
                        },
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = libraryViewModel.selectedCategory.label,
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                )
                val items = libraryViewModel.visibleItems(historyItems = emptyList())
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(items = items, key = { it.uri }) { item ->
                        VideoListCard(
                            item = item,
                            selected = libraryViewModel.selectedItem?.uri == item.uri,
                            onClick = { libraryViewModel.selectItem(item) },
                            onRequestFormatCorrection = {},
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
