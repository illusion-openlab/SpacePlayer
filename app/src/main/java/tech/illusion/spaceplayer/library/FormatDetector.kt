package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 识别流水线：容器探测（多视图判断 + 顺带拿宽高）→ 文件名关键词 → 宽高比补缺 → 默认兜底。
 * 文件名在每个字段上都优先于宽高比——宽高比只填文件名没提到的那个字段，从不覆盖文件名已经命中的
 * 字段。见设计稿 docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md。
 */
class FormatDetector(private val multiviewTrackProbe: MultiviewTrackProbe) {
    fun detect(context: Context, uri: Uri, displayName: String): DetectedFormat {
        val containerResult = multiviewTrackProbe.probe(context, uri)
        val filenameHint = FilenameFormatDetector.detect(displayName)
        val aspectHint = aspectRatioHintOrNull(containerResult)

        if (containerResult.isMultiview) {
            return DetectedFormat(
                projection = filenameHint.projection ?: aspectHint?.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }

        val projection = filenameHint.projection ?: aspectHint?.projection
        val stereoMode = filenameHint.stereoMode ?: aspectHint?.stereoMode
        val formatSource = when {
            filenameHint.projection != null || filenameHint.stereoMode != null -> FormatSource.DETECTED_FILENAME
            aspectHint != null -> FormatSource.DETECTED_ASPECT_RATIO
            else -> FormatSource.DEFAULT
        }
        return DetectedFormat(
            projection = projection ?: Projection.FLAT,
            stereoMode = stereoMode ?: StereoMode.MONO,
            formatSource = formatSource,
        )
    }

    private fun aspectRatioHintOrNull(containerResult: ContainerProbeResult): DetectedFormat? {
        val width = containerResult.videoWidth ?: return null
        val height = containerResult.videoHeight ?: return null
        return AspectRatioFormatDetector.detect(width, height)
    }
}
