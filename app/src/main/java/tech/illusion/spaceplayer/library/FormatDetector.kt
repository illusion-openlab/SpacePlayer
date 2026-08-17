package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 识别流水线：容器探测（多视图判断）→ 球面/立体元数据探测（读视频文件本身的 st3d/sv3d box）→
 * 默认兜底。文件名关键词和宽高比推测两层已删除——用 ffprobe 实测过的真实文件证明两者在 180°SBS/
 * 360°TB 这类组合上会给出确凿的错误答案（不是阈值没调好，是整帧宽高比本身就有歧义），而这些真实
 * 文件都携带标准的 st3d/sv3d 元数据。见设计稿
 * docs/superpowers/specs/2026-08-17-spherical-metadata-format-detection-design.md。
 */
class FormatDetector(
    private val multiviewTrackProbe: MultiviewTrackProbe,
    private val sphericalMetadataProbe: SphericalMetadataProbe,
) {
    fun detect(context: Context, uri: Uri): DetectedFormat {
        val containerResult = multiviewTrackProbe.probe(context, uri)
        val metadataHint = sphericalMetadataProbe.probe(context, uri)

        if (containerResult.isMultiview) {
            return DetectedFormat(
                projection = metadataHint.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }

        val formatSource = if (metadataHint.projection != null || metadataHint.stereoMode != null) {
            FormatSource.DETECTED_METADATA
        } else {
            FormatSource.DEFAULT
        }
        return DetectedFormat(
            projection = metadataHint.projection ?: Projection.FLAT,
            stereoMode = metadataHint.stereoMode ?: StereoMode.MONO,
            formatSource = formatSource,
        )
    }
}
