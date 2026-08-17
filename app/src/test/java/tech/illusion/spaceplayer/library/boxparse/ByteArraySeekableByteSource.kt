package tech.illusion.spaceplayer.library.boxparse

/** Pure in-memory [SeekableByteSource] for unit tests - no file I/O, no Android dependency. */
class ByteArraySeekableByteSource(private val bytes: ByteArray) : SeekableByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, length: Int): ByteArray {
        val start = position.toInt()
        return bytes.copyOfRange(start, start + length)
    }
}
