package tech.illusion.spaceplayer.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.SideNavigationItemDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.graphics.SpatialHoverStyle
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.RawVideoRecord
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
// parent Box is given, rather than duplicating this constant into that file. Bumped from 56.dp to
// give the bottom bar's enlarged elements (44.dp-min format/subtitle pills, 56.dp-min "开始播放")
// comfortable vertical room instead of nearly touching the box's edges - this also makes the
// sidebar's "其它" trigger taller, consistent with (not a regression of) this constant's own
// documented purpose of keeping the two vertically aligned.
private val FOOTER_HEIGHT = 64.dp

// Fixed sidebar width - see the comment at its usage site for why this must be explicit now that
// the "其它" action lives in a plain wrapping Column instead of inside SideNavigation's own
// width-constrained content slot.
private val SIDEBAR_WIDTH = 220.dp

// SideNavigationItem's natural height is only the SDK default (~48dp: a 32dp icon box plus 8dp top/
// bottom content padding) - explicitly taller per user request. The SDK applies the caller's
// modifier BEFORE its own contentPadding (confirmed by decompiling design-0.13.3-sources.jar's
// SideNavigation.kt), so a plain .height() here sets the outer row height cleanly without being
// squeezed by the internal padding.
private val NAV_ITEM_HEIGHT = 64.dp

// Real gap between sidebar items, inserted as a sibling Spacer rather than padding on either
// item's own modifier - see the comment at its usage site for why padding-before-clickable would
// make this dead space carved out of an item's own tap area instead of a real gap between two
// full-sized ones. Widened from 8.dp per user request; also gives each item's hover highlight room
// to read as belonging to one item rather than bleeding into its neighbour.
private val SIDEBAR_ITEM_GAP = 20.dp

private fun LibraryCategory.iconRes(): Int = when (this) {
    LibraryCategory.LIBRARY -> R.drawable.ic_nav_library
    LibraryCategory.DOWNLOADS -> R.drawable.ic_nav_download
    LibraryCategory.HISTORY -> R.drawable.ic_nav_history
    LibraryCategory.IMPORT -> R.drawable.ic_nav_import
}

@Composable
fun MainLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val librarySessionState: LibrarySessionState = GlobalContext.get().get()
    val libraryViewModel = remember { LibraryViewModel(context, librarySessionState) }

    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val playbackScope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val playbackViewModel: PlaybackViewModel = playbackScope.get()
    val historyStore: PlaybackHistoryStore = GlobalContext.get().get()
    var selectedEnvironment by remember { mutableStateOf(Environment.CINEMA) }
    // Surfaced by the "开始播放" click handler below when the tapped item's backing file has gone
    // missing (deleted/moved, or a SAF/MediaStore grant revoked) - checked up front there instead of
    // letting PlaybackManager.setup() discover it, so the user gets an immediate in-window dialog
    // rather than a silent no-op tap (see PlaybackManager.kt's setup() catch for the deeper fix).
    var showFileMissingDialog by rememberSaveable { mutableStateOf(false) }

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
        val item = libraryViewModel.selectedItem
        val name = uri?.let { DocumentFile.fromSingleUri(context, it)?.name }
        if (uri != null && item != null && name?.endsWith(".srt", ignoreCase = true) == true) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryViewModel.preferencesStore.setSubtitleUri(item.uri, uri)
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
            // selectedItem is an independent snapshot, not derived from libraryItems/downloadsItems -
            // re-select the freshly refreshed copy so the bottom bar's menu reflects the new
            // subtitle status immediately instead of showing the pre-pick snapshot.
            (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
                .find { it.uri == item.uri }
                ?.let { libraryViewModel.selectItem(it) }
        }
    }

    // Always re-read from the system rather than trusting a single snapshot taken at composition:
    // the permission can be granted from OUTSIDE this composition (the system-settings escape
    // hatch below, or `pm grant` during development) while this process keeps running, and a
    // remembered-once value would then stay stale forever - the gate would keep showing a library
    // that is actually readable.
    fun readVideoPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VIDEO,
    ) == PackageManager.PERMISSION_GRANTED

    var hasVideoPermission by remember { mutableStateOf(readVideoPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasVideoPermission = granted || readVideoPermission() }

    // Re-check on every resume so returning from the system-settings page picks the grant up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasVideoPermission = readVideoPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasVideoPermission) {
        if (hasVideoPermission) {
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
        }
    }

    // A format correction made from inside the immersive HUD is persisted to VideoPreferencesStore,
    // but the grid renders VideoItems snapshotted at the last refresh - re-read them once immersive
    // playback ends so the corrected card's caption and the bottom bar's menus agree with what the
    // HUD was just showing. Re-selecting by uri is needed because selectedItem is its own snapshot,
    // not a value derived from libraryItems/downloadsItems (same reason as the subtitle picker).
    LaunchedEffect(playbackViewModel.isImmersive.value) {
        if (!playbackViewModel.isImmersive.value && hasVideoPermission) {
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
            libraryViewModel.selectedItem?.let { previous ->
                (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
                    .find { it.uri == previous.uri }
                    ?.let { libraryViewModel.selectItem(it) }
            }
        }
    }

    PicoTheme {
        // The launcher <activity> has pico.spatial.windowcontainer.materialbackground="0" (see
        // AndroidManifest.xml), so this root is the sole owner of this window's background -
        // painting over the system glass would be wrong, but painting it after disabling the
        // glass switch is the documented opaque-root pattern.
        // design-style: opaque-root
        Box(modifier = modifier.fillMaxSize().background(SpacePlayerBackground)) {
            // hasVideoPermission no longer gates this whole Box (a prior version did, hiding the
            // sidebar/frame entirely) - per user request the app's basic frame (sidebar, category
            // title) always renders; only the video-browsing content area below swaps to the
            // permission rationale + grant button in its place.
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
                        .background(Color(color = 0xF5F0EBff))
                        .padding(top = HEADER_ROW_HEIGHT_PADDING),
                ) {
                    // SideNavigation's own root Column sizes itself via Modifier.width(IntrinsicSize.Min)
                    // (SDK source, design-0.13.3), i.e. shrink-to-fit its narrowest-breakable content. For
                    // CJK labels that have no non-breaking word boundaries, that intrinsic minimum collapses
                    // to roughly one glyph's width, squeezing every label into a vertical single-character
                    // stack. fillMaxWidth() forces it back up to the fixed SIDEBAR_WIDTH already established
                    // by this parent Column.
                    SideNavigation(
                        modifier = Modifier.fillMaxWidth(),
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
                                    text = stringResource(R.string.app_name),
                                    color = SpacePlayerTextPrimary,
                                    style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp),
                                )
                            }
                        },
                    ) {
                        // HISTORY is intentionally excluded from the sidebar per user request, but the
                        // enum value itself is kept (LibraryViewModel.visibleItems()/iconRes()'s
                        // exhaustive `when` branches still need it to compile) - restoring the entry
                        // point later is a one-line revert of this filter.
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT && it != LibraryCategory.HISTORY }
                            .forEachIndexed { index, category ->
                                // A real sibling Spacer, not padding inside either item's own modifier -
                                // SideNavigationItem's clip()/drawBehind{} highlight paint sit outside
                                // the caller's modifier (confirmed by decompiling
                                // design-0.13.3-sources.jar), so a trailing padding on one item would
                                // shrink only what's clickable on that item, not what's painted, and
                                // would still leave the highlight rectangle extending into the "gap" -
                                // a Spacer can't be absorbed by either neighbor's hit box or paint area.
                                if (index > 0) {
                                    Spacer(modifier = Modifier.height(SIDEBAR_ITEM_GAP))
                                }
                                val categoryInteractionSource = remember(category) { MutableInteractionSource() }
                                SideNavigationItem(
                                    selected = libraryViewModel.selectedCategory == category,
                                    colors = SideNavigationItemDefaults.colors(
                                        // Dimmed while locked so "not clickable yet" is visible,
                                        // not just silently inert.
                                        unselectedContentColor = if (hasVideoPermission) {
                                            SpacePlayerTextSecondary
                                        } else {
                                            SpacePlayerTextDisabled
                                        },
                                        unselectedContainerColor = Color.Transparent,
                                        selectedContentColor = if (hasVideoPermission) {
                                            SpacePlayerTextPrimary
                                        } else {
                                            SpacePlayerTextDisabled
                                        },
                                        selectedContainerColor = SpacePlayerSurfaceSelected,
                                    ),
                                    modifier = Modifier
                                        .height(NAV_ITEM_HEIGHT)
                                        // SideNavigationItem ships NO hover of its own (its source
                                        // is clip(shape).drawBehind{...}.then(modifier)... with no
                                        // spatialHoverEffect anywhere), so the sidebar had no hover
                                        // feedback at all. Placed after .height() and before
                                        // .clickable(), which puts it after the SDK's own
                                        // clip(shape) - the native hover then picks up the item's
                                        // rounded shape instead of a square. Same ordering rule as
                                        // VideoGridCard and FormatMenuButton.
                                        //
                                        // Locked until the permission is granted, per user request:
                                        // both the hover affordance and the click are dropped
                                        // together, so a locked item gives no false "this responds"
                                        // feedback before silently doing nothing.
                                        .then(
                                            if (hasVideoPermission) {
                                                Modifier
                                                    .spatialHoverEffect(SpatialHoverStyle.Highlight)
                                                    .clickable(
                                                        interactionSource = categoryInteractionSource,
                                                        indication = LocalIndication.current,
                                                        onClick = { libraryViewModel.selectCategory(category) },
                                                    )
                                                    .controllerHapticFeedback(interactionSource = categoryInteractionSource)
                                            } else {
                                                Modifier
                                            },
                                        ),
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
                    // Locked before the permission is granted, same as the category items above,
                    // per explicit user choice. Worth knowing if this is ever revisited: this
                    // trigger uses SAF (OpenDocument + takePersistableUriPermission), which needs no
                    // runtime permission at all, so locking it also removes the only in-app way to
                    // open a video when the permission has been permanently denied - the permission
                    // panel's "打开系统设置" button is then the sole route back.
                    val importTint = if (hasVideoPermission) SpacePlayerAccent else SpacePlayerTextDisabled
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            // Matches the content column's own Modifier.padding(16.dp) bottom
                            // inset around LibraryBottomBar, so both footer elements sit the same
                            // distance from the window's bottom edge.
                            .padding(bottom = 16.dp)
                            .fillMaxWidth()
                            .height(FOOTER_HEIGHT)
                            .dashedBorder(importTint, RoundedCornerShape(12.dp))
                            // dashedBorder clips first, so the native hover picks up the rounded
                            // shape; same reasoning as VideoGridCard's.
                            .then(
                                if (hasVideoPermission) {
                                    Modifier
                                        .spatialHoverEffect()
                                        .clickable(
                                            interactionSource = importInteractionSource,
                                            indication = LocalIndication.current,
                                            onClick = { importLauncher.launch(arrayOf("video/*")) },
                                        )
                                        .controllerHapticFeedback(interactionSource = importInteractionSource)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_import),
                                contentDescription = null,
                                tint = importTint,
                            )
                            Text(
                                text = stringResource(R.string.library_import_action),
                                color = importTint,
                                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // The format-filter chip row (全部 + one chip per Projection) that used to live
                    // here has been removed per user request. libraryViewModel.formatFilter/
                    // selectFormatFilter() are left in place - formatFilter now always stays null,
                    // so LibraryViewModel.visibleItems()'s filter branch is unreachable but harmless.
                    Row(
                        modifier = Modifier.fillMaxWidth().height(HEADER_ROW_HEIGHT),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = libraryViewModel.selectedCategory.label(),
                            color = SpacePlayerTextPrimary,
                            style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        )
                    }

                    if (hasVideoPermission) {
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
                                    )
                                }
                            }
                            ScrollIndicator(state = gridState)
                        }
                    } else {
                        // The app's frame (sidebar, category title above) stays visible per user
                        // request - only this content area, where the grid would normally sit,
                        // swaps to the permission rationale + grant button.
                        //
                        // Deliberately NO background of its own: an earlier version used
                        // .backgroundMaterial(true, Material.Regular) here (copying
                        // LoadingErrorAttachment/SubtitleAttachment), but those two live on the
                        // immersive Stage where there is real content behind them for the glass to
                        // sample. This window sets pico.spatial.windowcontainer.materialbackground="0"
                        // and paints its own opaque root, so the glass had nothing to blur and
                        // rendered as a flat grey slab on the warm-white background. Plain text +
                        // button directly on the window background is what this screen actually
                        // wants.
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier
                                    // Widened along with the type scale below - at the old 480.dp
                                    // the enlarged rationale wrapped to three cramped lines.
                                    .widthIn(max = 720.dp)
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.library_permission_rationale),
                                    color = SpacePlayerTextPrimary,
                                    style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp),
                                )
                                Row(
                                    modifier = Modifier.padding(top = 32.dp),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
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
                                            style = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp),
                                        )
                                    }
                                    // Mandatory escape hatch, not a nicety: once the user has denied
                                    // this permission twice Android marks it USER_FIXED and
                                    // permissionLauncher.launch() becomes a guaranteed silent no-op -
                                    // no dialog, no callback change, no way out from inside the app.
                                    // ACTION_APPLICATION_DETAILS_SETTINGS is verified to resolve and
                                    // render as a spatial panel on this PICO build (unlike
                                    // ACTION_MANAGE_APP_PERMISSIONS, which does not resolve here).
                                    // The ON_RESUME re-check above picks the grant up on return.
                                    Button(
                                        onClick = {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null),
                                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SpacePlayerSurface,
                                            contentColor = SpacePlayerTextPrimary,
                                        ),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.library_permission_open_settings),
                                            color = SpacePlayerTextPrimary,
                                            style = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp),
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.library_permission_saf_hint),
                                    color = SpacePlayerTextSecondary,
                                    style = PicoTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                                    modifier = Modifier.padding(top = 28.dp),
                                )
                            }
                        }
                    }

                    // Outside the hasVideoPermission branch on purpose: "其它 · 选择文件" uses SAF
                    // (OpenDocument + takePersistableUriPermission), which grants per-file read
                    // access without any runtime permission. Previously this bar lived inside the
                    // granted branch, so a video picked that way was selected in the ViewModel but
                    // had no visible controls and could never be played - the pick silently went
                    // nowhere. The permission gate only ever needs to hide the MediaStore-derived
                    // grid, never the controls for an item the user handed us directly.
                    Box(modifier = Modifier.fillMaxWidth().height(FOOTER_HEIGHT)) {
                        if (libraryViewModel.selectedItem != null) {
                            LibraryBottomBar(
                                selectedItem = libraryViewModel.selectedItem,
                                selectedEnvironment = selectedEnvironment,
                                onSelectEnvironment = { selectedEnvironment = it },
                                onFormatChange = { projection, stereoMode ->
                                    val item = libraryViewModel.selectedItem ?: return@LibraryBottomBar
                                    libraryViewModel.preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
                                    libraryViewModel.refreshLibrary()
                                    libraryViewModel.refreshDownloads()
                                    (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
                                        .find { it.uri == item.uri }
                                        ?.let { libraryViewModel.selectItem(it) }
                                },
                                onPickSubtitle = { subtitleLauncher.launch(arrayOf("*/*")) },
                                onStartPlayback = {
                                    val item = libraryViewModel.selectedItem ?: return@LibraryBottomBar
                                    // DocumentFile.fromSingleUri(...).exists() is the same check already
                                    // used above for naming an imported/subtitle uri - it round-trips
                                    // through ContentResolver.query() rather than actually opening the
                                    // file, so it's cheap enough for a click handler on the main thread.
                                    if (DocumentFile.fromSingleUri(context, item.uri)?.exists() != true) {
                                        showFileMissingDialog = true
                                        return@LibraryBottomBar
                                    }
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
        }

        if (showFileMissingDialog) {
            AlertDialog(
                onDismissRequest = { showFileMissingDialog = false },
                content = {
                    Text(
                        text = stringResource(R.string.library_file_missing),
                        color = PicoTheme.colorScheme.labelSecondary,
                        style = PicoTheme.typography.bodyLarge,
                    )
                },
                buttons = {
                    Button(onClick = { showFileMissingDialog = false }) {
                        Text(text = stringResource(R.string.action_confirm))
                    }
                },
            )
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
