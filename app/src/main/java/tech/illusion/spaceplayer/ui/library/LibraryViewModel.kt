package tech.illusion.spaceplayer.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.illusion.spaceplayer.library.FormatDetector
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.MediaExtractorMultiviewProbe
import tech.illusion.spaceplayer.library.Mp4SphericalStereoMetadataProbe
import tech.illusion.spaceplayer.library.RawVideoRecord
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.library.VideoLibraryRepository
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.library.storage.SharedPreferencesKeyValueStore
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.subtitle.SubtitleDiscovery

/**
 * Most of this state (libraryItems/selectedItem/format filter) only lives inside the main window
 * and doesn't need to survive it being torn down, so it stays a plain `remember` in
 * [MainLibraryScreen] rather than going into Koin. `selectedCategory` is the one exception - its
 * initial value and every write go through [sessionState], because it's the one field that must
 * survive the main WindowContainer being closed and reopened around each immersive playback
 * session. See docs/superpowers/specs/2026-08-14-library-ux-and-hand-interaction-design.md section
 * 3 for why only this one field moved and not the rest of this class.
 */
class LibraryViewModel(
    private val context: Context,
    private val sessionState: LibrarySessionState,
) {
    private val repository = VideoLibraryRepository(context)
    private val formatDetector = FormatDetector(MediaExtractorMultiviewProbe(), Mp4SphericalStereoMetadataProbe())
    val preferencesStore =
        VideoPreferencesStore(SharedPreferencesKeyValueStore(context, "video_preferences"))

    var selectedCategory by mutableStateOf(sessionState.selectedCategory)
        private set
    var formatFilter by mutableStateOf<Projection?>(null)
        private set
    var selectedItem by mutableStateOf<VideoItem?>(null)
        private set
    var libraryItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var downloadsItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    fun selectCategory(category: LibraryCategory) {
        selectedCategory = category
        sessionState.selectedCategory = category
        selectedItem = null
    }

    fun selectFormatFilter(projection: Projection?) {
        formatFilter = projection
    }

    fun selectItem(item: VideoItem) {
        selectedItem = item
    }

    fun refreshLibrary() {
        libraryItems = repository.queryLibrary().map(::toVideoItem)
    }

    fun refreshDownloads() {
        downloadsItems = repository.queryDownloads().map(::toVideoItem)
    }

    /** [historyItems] 由调用方（Task 8 接入历史后）传入，Task 5/6/7 阶段恒为空列表。 */
    fun visibleItems(historyItems: List<VideoItem>): List<VideoItem> {
        val source = when (selectedCategory) {
            LibraryCategory.LIBRARY -> libraryItems
            LibraryCategory.DOWNLOADS -> downloadsItems
            LibraryCategory.HISTORY -> historyItems
            LibraryCategory.IMPORT -> emptyList()
        }
        return formatFilter?.let { filter -> source.filter { it.projection == filter } } ?: source
    }

    fun toVideoItem(record: RawVideoRecord): VideoItem {
        val uriPrefs = preferencesStore.get(record.uri)
        val detected = formatDetector.detect(context, record.uri)
        // Uri.parse 在这里可以放心用——toVideoItem 全程没有单测覆盖，属于 VideoLibraryRepository/
        // PlaybackManager 那一类"触碰 Android 框架、只做构建+模拟器验证"的代码。
        val subtitleUri = uriPrefs.subtitleUri?.let(Uri::parse)
            ?: SubtitleDiscovery.findSiblingSrt(context, record.uri)
        return VideoItem(
            uri = record.uri,
            displayName = record.displayName,
            durationMs = record.durationMs,
            sizeBytes = record.sizeBytes,
            thumbnailUri = null,
            projection = uriPrefs.projectionOverride ?: detected.projection,
            stereoMode = uriPrefs.stereoModeOverride ?: detected.stereoMode,
            formatSource = if (uriPrefs.projectionOverride != null) {
                FormatSource.MANUAL_OVERRIDE
            } else {
                detected.formatSource
            },
            preferredEnvironment = uriPrefs.preferredEnvironment,
            subtitleUri = subtitleUri,
        )
    }
}
