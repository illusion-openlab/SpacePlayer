package tech.illusion.spaceplayer.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.RawVideoRecord
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.ui.PlaybackViewModel

@Composable
fun MainLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val libraryViewModel = remember { LibraryViewModel(context) }

    var itemPendingCorrection by remember { mutableStateOf<VideoItem?>(null) }

    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val playbackScope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val playbackViewModel: PlaybackViewModel = playbackScope.get()
    val historyStore: PlaybackHistoryStore = GlobalContext.get().get()
    var selectedEnvironment by remember { mutableStateOf(Environment.CINEMA) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val documentName = DocumentFile.fromSingleUri(context, uri)?.name ?: "导入的视频"
            libraryViewModel.selectItem(
                libraryViewModel.toVideoItem(
                    RawVideoRecord(uri = uri, displayName = documentName, durationMs = 0L, sizeBytes = 0L),
                ),
            )
        }
    }

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
                        modifier = Modifier.clickable(
                            onClick = {
                                if (category == LibraryCategory.IMPORT) {
                                    importLauncher.launch(arrayOf("video/*"))
                                } else {
                                    libraryViewModel.selectCategory(category)
                                }
                            },
                        ),
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
                val historyItems = historyStore.recentEntriesDescending().mapNotNull { (uriKey, _) ->
                    (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
                        .find { it.uri.toString() == uriKey }
                }
                val items = libraryViewModel.visibleItems(historyItems = historyItems)
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(items = items, key = { it.uri }) { item ->
                        VideoListCard(
                            item = item,
                            selected = libraryViewModel.selectedItem?.uri == item.uri,
                            onClick = { libraryViewModel.selectItem(item) },
                            onRequestFormatCorrection = { itemPendingCorrection = item },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    if (libraryViewModel.selectedItem != null) {
                        LibraryBottomBar(
                            selectedItem = libraryViewModel.selectedItem,
                            selectedEnvironment = selectedEnvironment,
                            onSelectEnvironment = { selectedEnvironment = it },
                            onStartPlayback = {
                                val item = libraryViewModel.selectedItem ?: return@LibraryBottomBar
                                val itemToPlay = if (item.projection == Projection.FLAT) {
                                    item.copy(preferredEnvironment = item.preferredEnvironment ?: selectedEnvironment)
                                } else {
                                    item
                                }
                                playbackViewModel.startPlayback(itemToPlay)
                                coroutineScope.launch { navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full) }
                            },
                        )
                    }
                }
            }
        }

        itemPendingCorrection?.let { item ->
            FormatCorrectionPopup(
                initialProjection = item.projection,
                initialStereoMode = item.stereoMode,
                onDismissRequest = { itemPendingCorrection = null },
                onConfirm = { projection, stereoMode ->
                    libraryViewModel.preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
                    libraryViewModel.refreshLibrary()
                    libraryViewModel.refreshDownloads()
                    itemPendingCorrection = null
                },
            )
        }
    }
}
