package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.library.boxparse.ByteArraySeekableByteSource
import tech.illusion.spaceplayer.library.boxparse.box
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class SphericalStereoMetadataReaderTest {

    private fun st3dBox(stereoMode: Int): ByteArray =
        box("st3d", byteArrayOf(0, 0, 0, 0, stereoMode.toByte()))

    private fun sv3dBox(): ByteArray = box("sv3d", ByteArray(0))

    private fun hdlrBox(handlerType: String): ByteArray {
        val payload = ByteArray(4) + ByteArray(4) + handlerType.toByteArray(Charsets.US_ASCII) + ByteArray(12)
        return box("hdlr", payload)
    }

    /** A VisualSampleEntry's fixed 78-byte header (reserved/data_reference_index/width/height/...) -
     * its exact field values don't matter here, only its length, since st3d/sv3d live in the child
     * boxes that follow it. */
    private fun visualSampleEntry(type: String, children: ByteArray): ByteArray =
        box(type, ByteArray(78) + children)

    private fun stsdBox(sampleEntry: ByteArray): ByteArray {
        val fullBoxHeaderAndEntryCount = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        return box("stsd", fullBoxHeaderAndEntryCount + sampleEntry)
    }

    private fun videoTrak(sampleEntryChildren: ByteArray): ByteArray {
        val sampleEntry = visualSampleEntry("hvc1", sampleEntryChildren)
        val stbl = box("stbl", stsdBox(sampleEntry))
        val minf = box("minf", stbl)
        val mdia = box("mdia", hdlrBox("vide") + minf)
        return box("trak", mdia)
    }

    private fun mp4File(trak: ByteArray): ByteArray {
        val moov = box("moov", trak)
        return box("ftyp", ByteArray(4)) + moov
    }

    @Test
    fun `both st3d and sv3d present maps to top-and-bottom stereo and sphere-360 projection`() {
        val entryChildren = st3dBox(stereoMode = 1) + sv3dBox()
        val source = ByteArraySeekableByteSource(mp4File(videoTrak(entryChildren)))

        val hint = SphericalStereoMetadataReader.read(source)

        assertEquals(Projection.SPHERE_360, hint.projection)
        assertEquals(StereoMode.TOP_AND_DOWN, hint.stereoMode)
    }

    @Test
    fun `only st3d present detects stereo mode but leaves projection undetected`() {
        val entryChildren = st3dBox(stereoMode = 2)
        val source = ByteArraySeekableByteSource(mp4File(videoTrak(entryChildren)))

        val hint = SphericalStereoMetadataReader.read(source)

        assertNull(hint.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, hint.stereoMode)
    }

    @Test
    fun `only sv3d present detects sphere-360 projection but leaves stereo mode undetected`() {
        val entryChildren = sv3dBox()
        val source = ByteArraySeekableByteSource(mp4File(videoTrak(entryChildren)))

        val hint = SphericalStereoMetadataReader.read(source)

        assertEquals(Projection.SPHERE_360, hint.projection)
        assertNull(hint.stereoMode)
    }

    @Test
    fun `neither box present returns both fields null`() {
        val source = ByteArraySeekableByteSource(mp4File(videoTrak(ByteArray(0))))

        val hint = SphericalStereoMetadataReader.read(source)

        assertNull(hint.projection)
        assertNull(hint.stereoMode)
    }

    @Test
    fun `unsupported stereo_mode value 3 (stereo-custom) is treated as undetected`() {
        val entryChildren = st3dBox(stereoMode = 3)
        val source = ByteArraySeekableByteSource(mp4File(videoTrak(entryChildren)))

        val hint = SphericalStereoMetadataReader.read(source)

        assertNull(hint.stereoMode)
    }

    @Test
    fun `stsd missing entirely returns both fields null without crashing`() {
        val mdia = box("mdia", hdlrBox("vide")) // no minf/stbl/stsd at all
        val trak = box("trak", mdia)
        val source = ByteArraySeekableByteSource(mp4File(trak))

        val hint = SphericalStereoMetadataReader.read(source)

        assertNull(hint.projection)
        assertNull(hint.stereoMode)
    }

    @Test
    fun `non-video track is skipped in favor of the first real video track`() {
        val audioTrak = box("trak", box("mdia", hdlrBox("soun")))
        val videoTrakBytes = videoTrak(st3dBox(stereoMode = 2) + sv3dBox())
        val moov = box("moov", audioTrak + videoTrakBytes)
        val source = ByteArraySeekableByteSource(box("ftyp", ByteArray(4)) + moov)

        val hint = SphericalStereoMetadataReader.read(source)

        assertEquals(Projection.SPHERE_360, hint.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, hint.stereoMode)
    }
}
