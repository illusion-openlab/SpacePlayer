package tech.illusion.spaceplayer.library.boxparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4BoxReaderTest {

    private fun boxWithLargesize(type: String, payload: ByteArray): ByteArray {
        val totalSize = 16L + payload.size
        val header = ByteArray(16)
        header[3] = 1 // size field == 1 signals "largesize follows"
        type.toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        for (i in 0 until 8) {
            header[8 + i] = ((totalSize shr (8 * (7 - i))) and 0xFF).toByte()
        }
        return header + payload
    }

    @Test
    fun `findChild locates a later sibling by skipping over an earlier one`() {
        val mvhd = box("mvhd", byteArrayOf(5, 6, 7, 8))
        val trak = box("trak", byteArrayOf(1, 2, 3, 4))
        val moov = box("moov", mvhd + trak)
        val source = ByteArraySeekableByteSource(moov)
        val moovHeader = Mp4BoxReader.readHeaderAt(source, 0L, source.size)!!

        val found = Mp4BoxReader.findChild(source, moovHeader.payloadStart, moovHeader.end, "trak")

        assertNotNull(found)
        assertEquals("trak", found!!.type)
    }

    @Test
    fun `findChild returns null when the type is not present`() {
        val mvhd = box("mvhd", byteArrayOf(5, 6, 7, 8))
        val moov = box("moov", mvhd)
        val source = ByteArraySeekableByteSource(moov)
        val moovHeader = Mp4BoxReader.readHeaderAt(source, 0L, source.size)!!

        val found = Mp4BoxReader.findChild(source, moovHeader.payloadStart, moovHeader.end, "trak")

        assertNull(found)
    }

    @Test
    fun `findAllChildren returns every matching sibling in order`() {
        val mvhd = box("mvhd", byteArrayOf(9))
        val trak1 = box("trak", byteArrayOf(1))
        val trak2 = box("trak", byteArrayOf(2))
        val source = ByteArraySeekableByteSource(mvhd + trak1 + trak2)

        val found = Mp4BoxReader.findAllChildren(source, 0L, source.size, "trak")

        assertEquals(2, found.size)
    }

    @Test
    fun `readHeaderAt parses a 64-bit largesize box`() {
        val payload = byteArrayOf(9, 9, 9, 9)
        val bytes = boxWithLargesize("free", payload)
        val source = ByteArraySeekableByteSource(bytes)

        val header = Mp4BoxReader.readHeaderAt(source, 0L, source.size)

        assertNotNull(header)
        assertEquals("free", header!!.type)
        assertEquals(16L, header.payloadStart)
        assertEquals(bytes.size.toLong(), header.totalSize)
    }

    @Test
    fun `readHeaderAt rejects a box whose declared size exceeds the available range`() {
        val bytes = box("free", ByteArray(4))
        bytes[3] = (bytes.size + 100).toByte() // corrupt the size field to claim more than exists

        val header = Mp4BoxReader.readHeaderAt(ByteArraySeekableByteSource(bytes), 0L, bytes.size.toLong())

        assertNull(header)
    }

    @Test
    fun `findPath walks multiple nested levels`() {
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "vide".toByteArray(Charsets.US_ASCII) + ByteArray(12))
        val mdia = box("mdia", hdlr)
        val trak = box("trak", mdia)
        val source = ByteArraySeekableByteSource(trak)

        val found = Mp4BoxReader.findPath(source, 0L, source.size, listOf("mdia", "hdlr"))

        assertNotNull(found)
        assertEquals("hdlr", found!!.type)
    }

    @Test
    fun `findPath returns null when an intermediate segment is missing`() {
        val mdiaWithNoHdlr = box("mdia", ByteArray(0))
        val trak = box("trak", mdiaWithNoHdlr)
        val source = ByteArraySeekableByteSource(trak)

        val found = Mp4BoxReader.findPath(source, 0L, source.size, listOf("mdia", "hdlr"))

        assertNull(found)
    }
}
