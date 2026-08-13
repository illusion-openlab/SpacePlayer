package tech.illusion.spaceplayer.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

data class ContainerProbeResult(
    val isMultiview: Boolean,
    val videoWidth: Int?,
    val videoHeight: Int?,
)

interface MultiviewTrackProbe {
    fun probe(context: Context, uri: Uri): ContainerProbeResult
}

private const val TAG = "MediaExtractorMultiviewProbe"

/**
 * 启发式代理判断，不是精确的 MV-HEVC 识别：标准 Android `MediaExtractor`/`MediaFormat` 没有
 * 文档化的 ISO/IEC 23008-2 Annex G 多视图分组信息读取接口。这里只是数"同分辨率 HEVC 视频轨道数
 * 是否 ≥ 2"，命中就当作多视图。本机没有真实 Apple 空间视频样本文件验证过这个启发式的准确率——
 * 文件名识别（`_mvhevc`）和用户手动覆盖仍是 V1 实际可靠的兜底路径，见 AGENTS.md Stage 2 记录。
 *
 * `videoWidth`/`videoHeight` 顺带取遍历轨道时遇到的第一条视频轨（`mime` 以 "video/" 开头，不限定
 * HEVC——宽高比检测要对任意编码的视频生效，跟多视图判断各自独立），复用这同一次
 * `MediaExtractor.setDataSource()` 解析，不额外开一次文件。
 */
class MediaExtractorMultiviewProbe : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hevcVideoTrackCount = 0
            var firstWidth = -1
            var firstHeight = -1
            var resolutionsMatch = true
            var videoWidth: Int? = null
            var videoHeight: Int? = null
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoWidth == null) {
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                }
                if (mime != "video/hevc") continue
                hevcVideoTrackCount++
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (firstWidth == -1) {
                    firstWidth = width
                    firstHeight = height
                } else if (width != firstWidth || height != firstHeight) {
                    resolutionsMatch = false
                }
            }
            ContainerProbeResult(
                isMultiview = hevcVideoTrackCount >= 2 && resolutionsMatch,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            )
        } catch (e: Exception) {
            Log.e(TAG, "probe failed for $uri", e)
            ContainerProbeResult(isMultiview = false, videoWidth = null, videoHeight = null)
        } finally {
            extractor.release()
        }
    }
}
