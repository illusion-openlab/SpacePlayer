package tech.illusion.spaceplayer.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import tech.illusion.spaceplayer.library.fakes.FakeMultiviewTrackProbe
import tech.illusion.spaceplayer.library.fakes.FakeSphericalMetadataProbe
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FormatDetectorTest {
    private val context = mock(android.content.Context::class.java)
    private val anyUri: Uri = mock(Uri::class.java)

    @Test
    fun `container probe hit forces multiview stereo, projection still comes from metadata`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = true),
            FakeSphericalMetadataProbe(projection = Projection.SPHERE_360),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(FormatSource.DETECTED_CONTAINER, result.formatSource)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
        assertEquals(Projection.SPHERE_360, result.projection)
    }

    @Test
    fun `container probe hit with no metadata projection defaults to flat`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = true),
            FakeSphericalMetadataProbe(),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, result.stereoMode)
    }

    @Test
    fun `container probe miss with both metadata fields present detects both`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false),
            FakeSphericalMetadataProbe(projection = Projection.SPHERE_360, stereoMode = StereoMode.TOP_AND_DOWN),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(FormatSource.DETECTED_METADATA, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.TOP_AND_DOWN, result.stereoMode)
    }

    @Test
    fun `metadata detects only stereo mode, projection defaults to flat`() {
        // Mirrors the real 180-LR.mp4 case: st3d present (side-by-side), no sv3d.
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false),
            FakeSphericalMetadataProbe(stereoMode = StereoMode.SIDE_BY_SIDE),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(FormatSource.DETECTED_METADATA, result.formatSource)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `metadata detects only projection, stereo mode defaults to mono`() {
        // Mirrors the real 360.mp4 case: sv3d present, no st3d.
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false),
            FakeSphericalMetadataProbe(projection = Projection.SPHERE_360),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(FormatSource.DETECTED_METADATA, result.formatSource)
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }

    @Test
    fun `no container hit and no metadata falls back to default`() {
        val detector = FormatDetector(
            FakeMultiviewTrackProbe(isMultiview = false),
            FakeSphericalMetadataProbe(),
        )
        val result = detector.detect(context, anyUri)
        assertEquals(FormatSource.DEFAULT, result.formatSource)
        assertEquals(Projection.FLAT, result.projection)
        assertEquals(StereoMode.MONO, result.stereoMode)
    }
}
