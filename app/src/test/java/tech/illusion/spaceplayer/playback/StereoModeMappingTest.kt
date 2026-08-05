package tech.illusion.spaceplayer.playback

import com.pico.spatial.core.ecs.video.VideoDimensionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StereoModeMappingTest {

    @Test
    fun `MONO maps to VideoDimensionMode MONO`() {
        assertEquals(VideoDimensionMode.MONO, StereoMode.MONO.toVideoDimensionMode())
    }

    @Test
    fun `SIDE_BY_SIDE maps to VideoDimensionMode SIDE_BY_SIDE`() {
        assertEquals(VideoDimensionMode.SIDE_BY_SIDE, StereoMode.SIDE_BY_SIDE.toVideoDimensionMode())
    }

    @Test
    fun `TOP_AND_DOWN maps to VideoDimensionMode TOP_AND_DOWN`() {
        assertEquals(VideoDimensionMode.TOP_AND_DOWN, StereoMode.TOP_AND_DOWN.toVideoDimensionMode())
    }

    @Test
    fun `MULTIVIEW_MVHEVC maps to VideoDimensionMode MULTIPLE_VIEW`() {
        assertEquals(VideoDimensionMode.MULTIPLE_VIEW, StereoMode.MULTIVIEW_MVHEVC.toVideoDimensionMode())
    }
}
