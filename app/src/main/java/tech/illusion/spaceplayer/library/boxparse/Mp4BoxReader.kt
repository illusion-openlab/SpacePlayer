package tech.illusion.spaceplayer.library.boxparse

/**
 * A random-access source of bytes. One implementation wraps a real file's [java.nio.channels.FileChannel]
 * (production, see SphericalStereoMetadataProbe.kt), another wraps a plain ByteArray (unit tests, no
 * file I/O at all) - see ByteArraySeekableByteSource in the test sources.
 */
interface SeekableByteSource {
    val size: Long

    /** Reads exactly [length] bytes starting at [position]. */
    fun readAt(position: Long, length: Int): ByteArray
}

/**
 * One ISO/IEC 14496-12 box's header. [end] is the absolute offset one past this box's last byte -
 * also the start offset of its next sibling, if any.
 */
data class BoxHeader(
    val type: String,
    val start: Long,
    val totalSize: Long,
    val payloadStart: Long,
) {
    val end: Long get() = start + totalSize
}

/**
 * Walks an ISO/IEC 14496-12 ("MP4") box tree. Every lookup takes an explicit [rangeStart]/[rangeEnd]
 * pair (the byte range a container box's children occupy) rather than assuming "from the file root" -
 * callers searching inside an already-found container box pass that box's own payloadStart/end.
 *
 * Malformed input (a box whose declared size is missing, truncated, or larger than the range that
 * contains it) is treated as "not found", not an error - every function here returns null/empty rather
 * than throwing, so a probe built on top of this can never crash on a corrupt or unusual file.
 */
object Mp4BoxReader {
    private const val HEADER_SIZE = 8L
    private const val LARGESIZE_HEADER_SIZE = 16L

    /** Reads one box header at [offset]. Returns null if the header (and, for a largesize box, the
     * largesize field) don't fully fit before [rangeEnd] or the source's own end, or if the box's
     * declared total size would extend past [rangeEnd]. size==0 ("extends to end of file") is treated
     * as unsupported and returns null - no box on the moov/trak/.../st3d/sv3d paths this project reads
     * legitimately takes that form. */
    fun readHeaderAt(source: SeekableByteSource, offset: Long, rangeEnd: Long): BoxHeader? {
        if (offset + HEADER_SIZE > rangeEnd || offset + HEADER_SIZE > source.size) return null
        val header = source.readAt(offset, 8)
        val size32 = readUInt32BE(header, 0)
        val type = String(header, 4, 4, Charsets.US_ASCII)
        return when (size32) {
            0L -> null
            1L -> {
                if (offset + LARGESIZE_HEADER_SIZE > rangeEnd || offset + LARGESIZE_HEADER_SIZE > source.size) {
                    return null
                }
                val largesizeBytes = source.readAt(offset + 8, 8)
                val totalSize = readUInt64BE(largesizeBytes, 0)
                if (totalSize < LARGESIZE_HEADER_SIZE || offset + totalSize > rangeEnd) return null
                BoxHeader(type, offset, totalSize, offset + LARGESIZE_HEADER_SIZE)
            }
            else -> {
                if (size32 < HEADER_SIZE || offset + size32 > rangeEnd) return null
                BoxHeader(type, offset, size32, offset + HEADER_SIZE)
            }
        }
    }

    /** Scans direct children of a container box occupying [rangeStart] until [rangeEnd] for the first
     * one matching [type]. Stops (returns null) at the first unparseable box, since its true extent -
     * and therefore where its sibling would start - can't be known. */
    fun findChild(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, type: String): BoxHeader? {
        var offset = rangeStart
        while (offset < rangeEnd) {
            val header = readHeaderAt(source, offset, rangeEnd) ?: return null
            if (header.type == type) return header
            offset = header.end
        }
        return null
    }

    /** Like [findChild] but collects every matching sibling instead of stopping at the first. On an
     * unparseable box, stops scanning and returns whatever was already found (rather than discarding
     * it), since those earlier results are still valid. */
    fun findAllChildren(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, type: String): List<BoxHeader> {
        val results = mutableListOf<BoxHeader>()
        var offset = rangeStart
        while (offset < rangeEnd) {
            val header = readHeaderAt(source, offset, rangeEnd) ?: break
            if (header.type == type) results.add(header)
            offset = header.end
        }
        return results
    }

    /** Descends through a chain of container boxes, e.g. ["mdia", "hdlr"] starting from a trak box's
     * own payload range. Every path segment before the last must itself be a container box, i.e. its
     * payload range is exactly the range the next segment is searched within - true for every path
     * this project uses (moov/trak/mdia/minf/stbl are all container boxes). Returns null as soon as any
     * segment is missing. */
    fun findPath(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, path: List<String>): BoxHeader? {
        require(path.isNotEmpty()) { "path must not be empty" }

        // If rangeStart points to a container box, read it first and search within its payload
        val rootBox = readHeaderAt(source, rangeStart, rangeEnd) ?: return null
        var currentStart = rootBox.payloadStart
        var currentEnd = rootBox.end

        var found: BoxHeader? = null
        for (type in path) {
            found = findChild(source, currentStart, currentEnd, type) ?: return null
            currentStart = found.payloadStart
            currentEnd = found.end
        }
        return found
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt64BE(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
