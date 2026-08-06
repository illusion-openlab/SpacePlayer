package tech.illusion.spaceplayer.library

import android.net.Uri
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, MANUAL_OVERRIDE, DEFAULT }

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    // MediaStore 传统缩略图表（MediaStore.Video.Thumbnails）的 Uri 概念在这里故意不用——
    // 卡片改用 ContentResolver.loadThumbnail(uri, size, null)（API 29+）直接从视频本身懒加载
    // 缩略图，不需要单独的缩略图 Uri。这个字段保留是为了不偏离设计稿的数据模型，恒为 null。
    val thumbnailUri: Uri?,
    val projection: Projection,
    val stereoMode: StereoMode,
    val formatSource: FormatSource,
    val preferredEnvironment: Environment?,
)

/** 格式识别流水线（[FormatDetector]）某一级命中后的结果。 */
data class DetectedFormat(
    val projection: Projection,
    val stereoMode: StereoMode,
    val formatSource: FormatSource,
)
