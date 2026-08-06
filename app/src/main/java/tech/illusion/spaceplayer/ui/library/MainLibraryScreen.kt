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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.SideNavigationItemDefaults
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
import tech.illusion.spaceplayer.ui.label

// Shared height for the sidebar's "SpacePlayer" header and the content column's category-title
// row, so the two bottom-align across the Row's two independent Columns - see the mockup that
// asked for "SpacePlayer" to sit larger and bottom-aligned with "视频资源库".
private val HEADER_ROW_HEIGHT = 56.dp

// Matches the content Column's own Modifier.padding(16.dp) top inset, so both header rows start
// from the same Y offset before bottom-aligning within HEADER_ROW_HEIGHT.
private val HEADER_ROW_HEIGHT_PADDING = 16.dp

// Shared height for the sidebar's "其它" action and the content column's bottom bar
// (environment selector + "开始播放"), so the two vertically center-align across the Row's two
// independent Columns the same way HEADER_ROW_HEIGHT does at the top. Applied to the wrapping Box
// at each call site; LibraryBottomBar's own Row just fillMaxSize()s to adopt whatever height its
// parent Box is given, rather than duplicating this constant into that file.
private val FOOTER_HEIGHT = 56.dp

// Fixed sidebar width - see the comment at its usage site for why this must be explicit now that
// the "其它" action lives in a plain wrapping Column instead of inside SideNavigation's own
// width-constrained content slot.
private val SIDEBAR_WIDTH = 220.dp

private fun LibraryCategory.iconRes(): Int = when (this) {
    LibraryCategory.LIBRARY -> R.drawable.ic_nav_library
    LibraryCategory.DOWNLOADS -> R.drawable.ic_nav_download
    LibraryCategory.HISTORY -> R.drawable.ic_nav_history
    LibraryCategory.IMPORT -> R.drawable.ic_nav_import
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

    // Resolved here (composable scope) rather than inline in the launcher callback below, since
    // that callback runs outside of composition when the activity result arrives and can't call
    // stringResource() directly.
    val importedVideoDefaultName = stringResource(R.string.library_imported_video_default_name)

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val documentName = DocumentFile.fromSingleUri(context, uri)?.name
                ?: importedVideoDefaultName
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
        // The launcher <activity> has pico.spatial.windowcontainer.materialbackground="0" (see
        // AndroidManifest.xml), so this root is the sole owner of this window's background -
        // painting over the system glass would be wrong, but painting it after disabling the
        // glass switch is the documented opaque-root pattern.
        // design-style: opaque-root
        Box(modifier = modifier.fillMaxSize().background(SpacePlayerBackground)) {
            if (!hasVideoPermission) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    Text(
                        text = stringResource(R.string.library_permission_rationale),
                        color = SpacePlayerTextPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpacePlayerAccent,
                            contentColor = SpacePlayerOnAccent,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.library_grant_permission),
                            color = SpacePlayerOnAccent,
                            style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        )
                    }
                }
                return@Box
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // Wraps SideNavigation plus the "其它" action so the latter can be pinned to the
                // very bottom of the sidebar via a weighted Spacer - SideNavigation's own internal
                // content column only wraps its content height, so a Spacer(weight) placed inside
                // its content lambda would have no bounded height to distribute against. A fixed
                // width is required here: without it this Column has no width constraint of its
                // own, so the "其它" box's fillMaxWidth() below expands to the *entire Row's*
                // width instead of just the sidebar's, squeezing the content column on the right
                // down to nothing (confirmed via a blank content area + uiautomator dump showing
                // that box's bounds spanning the full screen width).
                Column(
                    modifier = Modifier
                        .width(SIDEBAR_WIDTH)
                        .fillMaxHeight()
                        .padding(top = HEADER_ROW_HEIGHT_PADDING),
                ) {
                    SideNavigation(
                        header = {
                            // padding(bottom) before height() so it adds a gap *below* the fixed-
                            // height header box (extending the total space SideNavigation reserves
                            // for it) rather than shrinking the header's own content area - the
                            // header box and the nav item list below it would otherwise sit flush
                            // against each other with no breathing room.
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .height(HEADER_ROW_HEIGHT),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text = "SpacePlayer",
                                    color = SpacePlayerTextPrimary,
                                    style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp),
                                )
                            }
                        },
                    ) {
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT }.forEach { category ->
                            val categoryInteractionSource = remember(category) { MutableInteractionSource() }
                            SideNavigationItem(
                                selected = libraryViewModel.selectedCategory == category,
                                colors = SideNavigationItemDefaults.colors(
                                    unselectedContentColor = SpacePlayerTextSecondary,
                                    unselectedContainerColor = Color.Transparent,
                                    selectedContentColor = SpacePlayerTextPrimary,
                                    selectedContainerColor = SpacePlayerSurfaceSelected,
                                ),
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
                                        text = category.label(),
                                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                    )
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // "其它·选择文件" is a one-shot SAF trigger, not a persistent category - kept
                    // visually distinct (dashed outline) from the LIBRARY/DOWNLOADS/HISTORY tabs
                    // above, and pinned to the sidebar's bottom edge per the design mockup, rather
                    // than living in the same selectable list.
                    val importInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            // Matches the content column's own Modifier.padding(16.dp) bottom
                            // inset around LibraryBottomBar, so both footer elements sit the same
                            // distance from the window's bottom edge.
                            .padding(bottom = 16.dp)
                            .fillMaxWidth()
                            .height(FOOTER_HEIGHT)
                            .dashedBorder(SpacePlayerAccent, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = importInteractionSource,
                                indication = LocalIndication.current,
                                onClick = { importLauncher.launch(arrayOf("video/*")) },
                            )
                            .controllerHapticFeedback(interactionSource = importInteractionSource)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_import),
                                contentDescription = null,
                                tint = SpacePlayerAccent,
                            )
                            Text(
                                text = stringResource(R.string.library_import_action),
                                color = SpacePlayerAccent,
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val filterChipColors = ChipsDefaults.toggleableChipColors(
                        contentColor = SpacePlayerTextPrimary,
                        backgroundColor = SpacePlayerSurface,
                        activeContentColor = SpacePlayerOnAccent,
                        activeBackgroundColor = SpacePlayerAccent,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(HEADER_ROW_HEIGHT),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = libraryViewModel.selectedCategory.label(),
                            color = SpacePlayerTextPrimary,
                            style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        ToggleableChip(
                            label = { Text(stringResource(R.string.library_filter_all)) },
                            isToggleOn = libraryViewModel.formatFilter == null,
                            onClick = { libraryViewModel.selectFormatFilter(null) },
                            colors = filterChipColors,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        Projection.entries.forEach { candidate ->
                            ToggleableChip(
                                label = { Text(candidate.label()) },
                                isToggleOn = libraryViewModel.formatFilter == candidate,
                                onClick = { libraryViewModel.selectFormatFilter(candidate) },
                                colors = filterChipColors,
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
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = items, key = { it.uri }) { item ->
                                VideoGridCard(
                                    item = item,
                                    selected = libraryViewModel.selectedItem?.uri == item.uri,
                                    onClick = { libraryViewModel.selectItem(item) },
                                    onRequestFormatCorrection = { itemPendingCorrection = item },
                                )
                            }
                        }
                        ScrollIndicator(state = gridState)
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(FOOTER_HEIGHT)) {
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
