package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 从视频轨道宽高推测投影/立体格式，作为文件名检测之后的补缺信号（见设计稿
 * docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md）。四个分支按顺序判断，
 * 命中即返回，不会重复分类；返回值里跟命中分支无关的字段用中性默认值（FLAT/MONO）占位。
 *
 * NORMAL_VIDEO_RATIO 下限是 1.3 不是看起来更自然的 1.0——1.0 会把常规 9:16 竖屏视频（1080x1920）
 * 误判成 TOP_AND_DOWN（1080 / (1920/2) = 1.125，落进 1.0~2.4）。竖屏视频很常见，这个误判代价不小，
 * 所以把下限提到 1.3，排掉近似正方形的"半高比"，同时仍覆盖 4:3=1.33 到 21:9=2.33 的常见横屏比例。
 */
object AspectRatioFormatDetector {
    private const val EQUIRECT_360_MIN = 1.85f
    private const val EQUIRECT_360_MAX = 2.15f
    private const val HEMISPHERE_180_MIN = 0.9f
    private const val HEMISPHERE_180_MAX = 1.1f
    private const val NORMAL_VIDEO_RATIO_MIN = 1.3f
    private const val NORMAL_VIDEO_RATIO_MAX = 2.4f

    fun detect(width: Int, height: Int): DetectedFormat? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio in EQUIRECT_360_MIN..EQUIRECT_360_MAX ->
                DetectedFormat(Projection.SPHERE_360, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            ratio in HEMISPHERE_180_MIN..HEMISPHERE_180_MAX ->
                DetectedFormat(Projection.HEMISPHERE_180, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            (width / 2f) / height in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.SIDE_BY_SIDE, FormatSource.DETECTED_ASPECT_RATIO)
            width / (height / 2f) in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.TOP_AND_DOWN, FormatSource.DETECTED_ASPECT_RATIO)
            else -> null
        }
    }
}
