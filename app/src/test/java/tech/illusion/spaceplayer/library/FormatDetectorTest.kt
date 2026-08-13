package tech.illusion.spaceplayer.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import tech.illusion.spaceplayer.library.fakes.FakeMultiviewTrackProbe
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FormatDetectorTest {
    private val context = mock(android.content.Context::class.java)
    private val anyUri: Uri = mock(Uri::class.java)

    @Test
    fun `container probe hit wins over filename and forces multiview stereo`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(isMultiview = true))
        val result = detector.detect(context, anyUri, "trip_360_sbs.mp4")
        assertEquals(FormatSource.DETECTED_CONTAINER, result.formatSource)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
        // projection 仍然采信文件名识别（容器探测只对 stereo mode 有意义，见设计稿第 2 节）
        assertEquals(Projection.SPHERE_360, result.projection)
    }

    @Test
    fun `container probe hit with no filename hint defaults projection to flat`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(isMultiview = true))
        val result = detector.detect(context, anyUri, "IMG_0001.mp4")
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
    }

    @Test
    fun `container probe miss falls back to filename detection`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(isMultiview = false))
        val result = detector.detect(context, anyUri, "hawaii_180_beach.mp4")
        assertEquals(FormatSource.DETECTED_FILENAME, result.formatSource)
        assertEquals(Projection.HEMISPHERE_180, result.projection)
    }

    @Test
    fun `no container hit and no filename hint falls back to default`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(isMultiview = false))
        val result = detector.detect(context, anyUri, "IMG_0002.mp4")
        assertEquals(FormatSource.DEFAULT, result.formatSource)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }

    @Test
    fun `filename catches only projection, aspect ratio fills the stereo mode gap`() {
        // "trip_360_video.mp4" only matches the projection keyword; 3840x1080 is the SBS aspect ratio
        // (halved width 1920x1080 = 16:9).
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false, videoWidth = 3840, videoHeight = 1080),
        )
        val result = detector.detect(context, anyUri, "trip_360_video.mp4")
        assertEquals(FormatSource.DETECTED_FILENAME, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `filename catches only stereo mode, aspect ratio fills the projection gap`() {
        // "clip_sbs.mp4" only matches the stereo keyword; 3840x1920 is the 360 aspect ratio (2 to 1).
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false, videoWidth = 3840, videoHeight = 1920),
        )
        val result = detector.detect(context, anyUri, "clip_sbs.mp4")
        assertEquals(FormatSource.DETECTED_FILENAME, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `filename catches both fields so aspect ratio is not consulted`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false, videoWidth = 1920, videoHeight = 1080),
        )
        val result = detector.detect(context, anyUri, "trip_360_sbs.mp4")
        assertEquals(FormatSource.DETECTED_FILENAME, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `no filename hint at all, aspect ratio detects both fields`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false, videoWidth = 3840, videoHeight = 1920),
        )
        val result = detector.detect(context, anyUri, "IMG_0003.mp4")
        assertEquals(FormatSource.DETECTED_ASPECT_RATIO, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }

    @Test
    fun `no filename hint and aspect ratio also misses falls back to default`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false, videoWidth = 1920, videoHeight = 1080),
        )
        val result = detector.detect(context, anyUri, "IMG_0004.mp4")
        assertEquals(FormatSource.DEFAULT, result.formatSource)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }
}
