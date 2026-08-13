package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FilenameFormatDetectorTest {

    @Test
    fun `no keyword returns hint with both fields null`() {
        val result = FilenameFormatDetector.detect("my_trip.mp4")
        assertNull(result.projection)
        assertNull(result.stereoMode)
    }

    @Test
    fun `_180_ keyword detects hemisphere projection, stereo mode left null`() {
        val result = FilenameFormatDetector.detect("hawaii_180_beach.mp4")
        assertEquals(Projection.HEMISPHERE_180, result.projection)
        assertNull(result.stereoMode)
    }

    @Test
    fun `_180x180 keyword detects hemisphere projection`() {
        assertEquals(Projection.HEMISPHERE_180, FilenameFormatDetector.detect("clip_180x180.mp4").projection)
    }

    @Test
    fun `_360_ keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("concert_360_live.mp4").projection)
    }

    @Test
    fun `_equirect keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("scene_equirect.mp4").projection)
    }

    @Test
    fun `_sbs keyword detects side-by-side stereo, projection left null`() {
        val result = FilenameFormatDetector.detect("movie_sbs.mp4")
        assertNull(result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `_3dh keyword detects side-by-side stereo`() {
        assertEquals(StereoMode.SIDE_BY_SIDE, FilenameFormatDetector.detect("show_3dh.mp4").stereoMode)
    }

    @Test
    fun `_tb keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_tb.mp4").stereoMode)
    }

    @Test
    fun `_ou keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_ou.mp4").stereoMode)
    }

    @Test
    fun `_3dv keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_3dv.mp4").stereoMode)
    }

    @Test
    fun `_mvhevc keyword detects multiview stereo`() {
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, FilenameFormatDetector.detect("spatial_mvhevc.mp4").stereoMode)
    }

    @Test
    fun `combined projection and stereo keywords both detected`() {
        val result = FilenameFormatDetector.detect("trip_360_sbs.mp4")
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `keyword matching is case-insensitive`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("Trip_360_Live.MP4").projection)
    }
}
