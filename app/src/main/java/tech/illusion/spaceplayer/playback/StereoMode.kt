package tech.illusion.spaceplayer.playback

import com.pico.spatial.core.ecs.video.VideoDimensionMode

enum class StereoMode {
    MONO, SIDE_BY_SIDE, TOP_AND_DOWN, MULTIVIEW_MVHEVC;

    fun toVideoDimensionMode(): VideoDimensionMode = when (this) {
        MONO -> VideoDimensionMode.MONO
        SIDE_BY_SIDE -> VideoDimensionMode.SIDE_BY_SIDE
        TOP_AND_DOWN -> VideoDimensionMode.TOP_AND_DOWN
        MULTIVIEW_MVHEVC -> VideoDimensionMode.MULTIPLE_VIEW
    }
}
