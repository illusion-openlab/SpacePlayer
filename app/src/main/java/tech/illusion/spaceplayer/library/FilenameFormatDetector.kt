package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 纯文件名关键词识别，命中优先级见设计稿第 2 节。同一文件名可以同时命中投影关键词和立体格式关键词
 * （如 "trip_360_sbs.mp4"），两者互相独立判断。
 */
object FilenameFormatDetector {
    private val HEMISPHERE_180_KEYWORDS = listOf("_180_", "_180x180")
    private val SPHERE_360_KEYWORDS = listOf("_360_", "_equirect")
    private val SIDE_BY_SIDE_KEYWORDS = listOf("_sbs", "_3dh")
    private val TOP_AND_DOWN_KEYWORDS = listOf("_tb", "_ou", "_3dv")
    private val MULTIVIEW_KEYWORDS = listOf("_mvhevc")

    fun detect(displayName: String): DetectedFormat? {
        val lower = displayName.lowercase()
        val projection = when {
            HEMISPHERE_180_KEYWORDS.any(lower::contains) -> Projection.HEMISPHERE_180
            SPHERE_360_KEYWORDS.any(lower::contains) -> Projection.SPHERE_360
            else -> null
        }
        val stereoMode = when {
            MULTIVIEW_KEYWORDS.any(lower::contains) -> StereoMode.MULTIVIEW_MVHEVC
            SIDE_BY_SIDE_KEYWORDS.any(lower::contains) -> StereoMode.SIDE_BY_SIDE
            TOP_AND_DOWN_KEYWORDS.any(lower::contains) -> StereoMode.TOP_AND_DOWN
            else -> null
        }
        if (projection == null && stereoMode == null) return null
        return DetectedFormat(
            projection = projection ?: Projection.FLAT,
            stereoMode = stereoMode ?: StereoMode.MONO,
            formatSource = FormatSource.DETECTED_FILENAME,
        )
    }
}
