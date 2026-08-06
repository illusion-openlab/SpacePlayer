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
        val detector = FormatDetector(FakeMultiviewTrackProbe(result = true))
        val result = detector.detect(context, anyUri, "trip_360_sbs.mp4")
        assertEquals(FormatSource.DETECTED_CONTAINER, result.formatSource)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
        // projection 仍然采信文件名识别（容器探测只对 stereo mode 有意义，见设计稿第 2 节）
        assertEquals(Projection.SPHERE_360, result.projection)
    }

    @Test
    fun `container probe hit with no filename hint defaults projection to flat`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(result = true))
        val result = detector.detect(context, anyUri, "IMG_0001.mp4")
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
    }

    @Test
    fun `container probe miss falls back to filename detection`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(result = false))
        val result = detector.detect(context, anyUri, "hawaii_180_beach.mp4")
        assertEquals(FormatSource.DETECTED_FILENAME, result.formatSource)
        assertEquals(Projection.HEMISPHERE_180, result.projection)
    }

    @Test
    fun `no container hit and no filename hint falls back to default`() {
        val detector = FormatDetector(FakeMultiviewTrackProbe(result = false))
        val result = detector.detect(context, anyUri, "IMG_0002.mp4")
        assertEquals(FormatSource.DEFAULT, result.formatSource)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }
}
