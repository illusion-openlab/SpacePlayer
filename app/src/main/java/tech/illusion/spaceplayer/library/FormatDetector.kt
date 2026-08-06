package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/** 识别流水线：容器探测（仅对 stereo mode 有意义）→ 文件名关键词 → 默认兜底。见设计稿第 2 节。 */
class FormatDetector(private val multiviewTrackProbe: MultiviewTrackProbe) {
    fun detect(context: Context, uri: Uri, displayName: String): DetectedFormat {
        if (multiviewTrackProbe.looksLikeMultiview(context, uri)) {
            val filenameHint = FilenameFormatDetector.detect(displayName)
            return DetectedFormat(
                projection = filenameHint?.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }
        return FilenameFormatDetector.detect(displayName)
            ?: DetectedFormat(Projection.FLAT, StereoMode.MONO, FormatSource.DEFAULT)
    }
}
