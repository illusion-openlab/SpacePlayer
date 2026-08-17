package tech.illusion.spaceplayer.library.boxparse

/**
 * Builds one ISO/IEC 14496-12 box's raw bytes: 4-byte big-endian size (covering this whole box,
 * header included) + 4-byte ASCII type + payload. Shared by Mp4BoxReaderTest (generic box-tree
 * shapes) and SphericalStereoMetadataReaderTest (realistic moov/trak/.../st3d/sv3d trees).
 */
fun box(type: String, payload: ByteArray): ByteArray {
    val size = 8 + payload.size
    val header = ByteArray(8)
    header[0] = (size ushr 24).toByte()
    header[1] = (size ushr 16).toByte()
    header[2] = (size ushr 8).toByte()
    header[3] = size.toByte()
    type.toByteArray(Charsets.US_ASCII).copyInto(header, 4)
    return header + payload
}
