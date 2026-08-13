package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class AspectRatioFormatDetectorTest {

    @Test
    fun `2 to 1 ratio detects sphere 360 projection`() {
        val result = AspectRatioFormatDetector.detect(3840, 1920)
        assertEquals(Projection.SPHERE_360, result?.projection)
        assertEquals(StereoMode.MONO, result?.stereoMode)
        assertEquals(FormatSource.DETECTED_ASPECT_RATIO, result?.formatSource)
    }

    @Test
    fun `2 to 1 ratio at a larger resolution also detects sphere 360`() {
        assertEquals(Projection.SPHERE_360, AspectRatioFormatDetector.detect(5760, 2880)?.projection)
    }

    @Test
    fun `square ratio detects hemisphere 180 projection`() {
        val result = AspectRatioFormatDetector.detect(2160, 2160)
        assertEquals(Projection.HEMISPHERE_180, result?.projection)
        assertEquals(StereoMode.MONO, result?.stereoMode)
    }

    @Test
    fun `square ratio at a smaller resolution also detects hemisphere 180`() {
        assertEquals(Projection.HEMISPHERE_180, AspectRatioFormatDetector.detect(1440, 1440)?.projection)
    }

    @Test
    fun `double-wide frame detects side-by-side stereo`() {
        // 3840x1080 halves to 1920x1080 (16:9) - a plausible single-eye frame.
        val result = AspectRatioFormatDetector.detect(3840, 1080)
        assertEquals(Projection.FLAT, result?.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result?.stereoMode)
    }

    @Test
    fun `double-height frame detects top-and-down stereo`() {
        // 1920x2160 halves to 1920x1080 (16:9) - a plausible single-eye frame.
        val result = AspectRatioFormatDetector.detect(1920, 2160)
        assertEquals(Projection.FLAT, result?.projection)
        assertEquals(StereoMode.TOP_AND_DOWN, result?.stereoMode)
    }

    @Test
    fun `ordinary 16 by 9 landscape video matches no band`() {
        assertNull(AspectRatioFormatDetector.detect(1920, 1080))
    }

    @Test
    fun `ordinary 9 by 16 portrait video matches no band`() {
        // Regression case: an earlier 1.0f lower bound on the stereo band made this false-positive
        // as TOP_AND_DOWN (1080 / (1920/2) = 1.125). Portrait video is common, so this must stay null.
        assertNull(AspectRatioFormatDetector.detect(1080, 1920))
    }

    @Test
    fun `non-positive dimensions return null`() {
        assertNull(AspectRatioFormatDetector.detect(0, 1080))
        assertNull(AspectRatioFormatDetector.detect(1920, 0))
        assertNull(AspectRatioFormatDetector.detect(-1920, 1080))
    }
}
