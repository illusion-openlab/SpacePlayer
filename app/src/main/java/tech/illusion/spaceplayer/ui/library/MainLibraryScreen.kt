package tech.illusion.spaceplayer.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.RawVideoRecord
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.ui.PlaybackViewModel

// Fixed dark background for the main window - the launcher <activity> has
// pico.spatial.windowcontainer.materialbackground="0" (see AndroidManifest.xml), so this root is
// the sole owner of this window's background; painting over the system glass here would be wrong,
// but painting it after disabling the glass switch is the documented opaque-root pattern.
// design-style: opaque-root
private val MainWindowBackground = Color(0xFF16161A) // design-style: fixed-figma-color main window background

private fun LibraryCategory.iconRes(): Int = when (this) {
    LibraryCategory.LIBRARY -> R.drawable.ic_nav_library
    LibraryCategory.DOWNLOADS -> R.drawable.ic_nav_download
    LibraryCategory.HISTORY -> R.drawable.ic_nav_history
    LibraryCategory.IMPORT -> R.drawable.ic_nav_import
}

private fun Projection.filterLabel(): String = when (this) {
    Projection.FLAT -> "平面"
    Projection.HEMISPHERE_180 -> "180°"
    Projection.SPHERE_360 -> "360°"
}

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

    val subtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val item = itemPendingCorrection
        val name = uri?.let { DocumentFile.fromSingleUri(context, it)?.name }
        if (uri != null && item != null && name?.endsWith(".srt", ignoreCase = true) == true) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryViewModel.preferencesStore.setSubtitleUri(item.uri, uri)
            itemPendingCorrection = item.copy(subtitleUri = uri)
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
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
        Box(modifier = modifier.fillMaxSize().background(MainWindowBackground)) {
            if (!hasVideoPermission) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
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
                return@Box
            }

            Row(modifier = Modifier.fillMaxSize()) {
                SideNavigation(
                    header = {
                        Text(
                            text = "SpacePlayer",
                            color = PicoTheme.colorScheme.labelPrimary,
                            style = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    },
                ) {
                    LibraryCategory.entries.filter { it != LibraryCategory.IMPORT }.forEach { category ->
                        val categoryInteractionSource = remember(category) { MutableInteractionSource() }
                        SideNavigationItem(
                            selected = libraryViewModel.selectedCategory == category,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .clickable(
                                    interactionSource = categoryInteractionSource,
                                    indication = LocalIndication.current,
                                    onClick = { libraryViewModel.selectCategory(category) },
                                )
                                .controllerHapticFeedback(interactionSource = categoryInteractionSource),
                            leading = {
                                Icon(
                                    painter = painterResource(id = category.iconRes()),
                                    contentDescription = null,
                                )
                            },
                            content = {
                                Text(
                                    text = category.label,
                                    style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                )
                            },
                        )
                    }

                    // "其它·选择文件" is a one-shot SAF trigger, not a persistent category - kept
                    // visually distinct (dashed outline) from the LIBRARY/DOWNLOADS/HISTORY tabs
                    // above per the design mockup, rather than living in the same selectable list.
                    val importInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .fillMaxWidth()
                            .dashedBorder(SpacePlayerAccent, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = importInteractionSource,
                                indication = LocalIndication.current,
                                onClick = { importLauncher.launch(arrayOf("video/*")) },
                            )
                            .controllerHapticFeedback(interactionSource = importInteractionSource)
                            .padding(12.dp),
                    ) {
                        Row {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_import),
                                contentDescription = null,
                                tint = SpacePlayerAccent,
                            )
                            Text(
                                text = "其它 · 选择文件",
                                color = SpacePlayerAccent,
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = libraryViewModel.selectedCategory.label,
                            color = PicoTheme.colorScheme.labelPrimary,
                            style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        ToggleableChip(
                            label = { Text("全部") },
                            isToggleOn = libraryViewModel.formatFilter == null,
                            onClick = { libraryViewModel.selectFormatFilter(null) },
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        Projection.entries.forEach { candidate ->
                            ToggleableChip(
                                label = { Text(candidate.filterLabel()) },
                                isToggleOn = libraryViewModel.formatFilter == candidate,
                                onClick = { libraryViewModel.selectFormatFilter(candidate) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    val historyItems = historyStore.recentEntriesDescending().mapNotNull { (uriKey, _) ->
                        (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
                            .find { it.uri.toString() == uriKey }
                    }
                    val items = libraryViewModel.visibleItems(historyItems = historyItems)

                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).weight(1f)) {
                        val gridState = rememberLazyGridState()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = items, key = { it.uri }) { item ->
                                VideoGridCard(
                                    item = item,
                                    selected = libraryViewModel.selectedItem?.uri == item.uri,
                                    onClick = { libraryViewModel.selectItem(item) },
                                    onRequestFormatCorrection = { itemPendingCorrection = item },
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        ScrollIndicator(state = gridState)
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
                    hasSubtitle = item.subtitleUri != null,
                    onDismissRequest = { itemPendingCorrection = null },
                    onConfirm = { projection, stereoMode ->
                        libraryViewModel.preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
                        libraryViewModel.refreshLibrary()
                        libraryViewModel.refreshDownloads()
                        itemPendingCorrection = null
                    },
                    onPickSubtitle = { subtitleLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}

// No built-in component offers a dashed outline; SegmentControl/ButtonChip/Card all draw solid
// borders. Compose's own Modifier.border doesn't accept a PathEffect either, so this draws the
// dash directly - a legitimate custom-component case per spatial-ui-design-style's builtins.md
// ("only customize when no built-in fits").
private fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape): Modifier =
    this
        .clip(shape)
        .drawBehind {
            drawRoundRect(
                color = color,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                ),
                cornerRadius = CornerRadius(12.dp.toPx()),
            )
        }
