package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import android.util.Log
import tech.illusion.spaceplayer.library.boxparse.BoxHeader
import tech.illusion.spaceplayer.library.boxparse.Mp4BoxReader
import tech.illusion.spaceplayer.library.boxparse.SeekableByteSource
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Result of reading a video file's own embedded st3d/sv3d boxes - either field may be null if that
 * box wasn't present (not the same as "detected and it's MONO/FLAT"; FormatDetector applies those
 * defaults, this type only reports what was actually found). */
data class SphericalMetadataHint(val projection: Projection?, val stereoMode: StereoMode?)

interface SphericalMetadataProbe {
    fun probe(context: Context, uri: Uri): SphericalMetadataHint
}

private const val TAG = "Mp4SphericalStereoMetadataProbe"

/**
 * Reads the real st3d (Stereoscopic 3D Video Box, ISO/IEC 23001-10) and sv3d (Spherical Video V2 Box,
 * Google's public spec) boxes embedded in an MP4 file's first video track's sample entry - the same
 * boxes 360-camera exports (Insta360/GoPro-style) carry and that `ffprobe` surfaces as "Stereo
 * 3D"/"Spherical Mapping" side_data. Verified against three real files in this project's design spec
 * (docs/superpowers/specs/2026-08-17-spherical-metadata-format-detection-design.md) - both heuristic
 * layers this replaces (filename keywords, aspect-ratio guessing) got 2 of those 3 files wrong, while
 * this reads the same real metadata the system player almost certainly uses.
 *
 * Android's MediaExtractor/MediaFormat expose neither box, so this walks the raw box tree directly via
 * Mp4BoxReader. Uses a real FileChannel (not sequential InputStream reads) because `moov` isn't always
 * near the front of the file ("faststart") - seeking straight to the target box costs nothing, while
 * sequentially skipping through a multi-hundred-MB `mdat` payload first would not.
 */
class Mp4SphericalStereoMetadataProbe : SphericalMetadataProbe {
    override fun probe(context: Context, uri: Uri): SphericalMetadataHint {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "failed to open $uri", e)
            null
        } ?: return SphericalMetadataHint(null, null)

        return pfd.use {
            val channel = FileInputStream(it.fileDescriptor).channel
            runCatching { SphericalStereoMetadataReader.read(FileChannelByteSource(channel)) }
                .onFailure { e -> Log.e(TAG, "probe failed for $uri", e) }
                .getOrDefault(SphericalMetadataHint(null, null))
        }
    }
}

private class FileChannelByteSource(private val channel: FileChannel) : SeekableByteSource {
    override val size: Long get() = channel.size()

    override fun readAt(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = channel.read(buffer, position + totalRead)
            if (read < 0) error("Unexpected end of file while reading $length bytes at $position")
            totalRead += read
        }
        return buffer.array()
    }
}

/** The pure, Android-free box-walking logic - directly unit-testable against a ByteArray-backed
 * [SeekableByteSource], no file I/O or Uri involved. [Mp4SphericalStereoMetadataProbe] is the thin
 * Android wrapper that opens the real file and delegates here. */
object SphericalStereoMetadataReader {
    private const val VISUAL_SAMPLE_ENTRY_HEADER_SIZE = 78L
    private val HDLR_PATH = listOf("mdia", "hdlr")
    private val STSD_PATH = listOf("mdia", "minf", "stbl", "stsd")

    fun read(source: SeekableByteSource): SphericalMetadataHint {
        val moov = Mp4BoxReader.findChild(source, 0L, source.size, "moov")
            ?: return SphericalMetadataHint(null, null)
        val trak = findFirstVideoTrak(source, moov) ?: return SphericalMetadataHint(null, null)
        val stsd = Mp4BoxReader.findPath(source, trak.payloadStart, trak.end, STSD_PATH)
            ?: return SphericalMetadataHint(null, null)
        if (stsd.end - stsd.payloadStart < 8) return SphericalMetadataHint(null, null)

        val sampleEntry = Mp4BoxReader.readHeaderAt(source, stsd.payloadStart + 8, stsd.end)
            ?: return SphericalMetadataHint(null, null)
        val childrenStart = sampleEntry.payloadStart + VISUAL_SAMPLE_ENTRY_HEADER_SIZE
        val childrenEnd = sampleEntry.end
        if (childrenStart >= childrenEnd) return SphericalMetadataHint(null, null)

        val st3d = Mp4BoxReader.findChild(source, childrenStart, childrenEnd, "st3d")
        val sv3d = Mp4BoxReader.findChild(source, childrenStart, childrenEnd, "sv3d")

        val stereoMode = st3d?.let { readStereoMode(source, it) }
        val projection = if (sv3d != null) Projection.SPHERE_360 else null
        return SphericalMetadataHint(projection, stereoMode)
    }

    private fun findFirstVideoTrak(source: SeekableByteSource, moov: BoxHeader): BoxHeader? {
        val traks = Mp4BoxReader.findAllChildren(source, moov.payloadStart, moov.end, "trak")
        for (trak in traks) {
            val hdlr = Mp4BoxReader.findPath(source, trak.payloadStart, trak.end, HDLR_PATH) ?: continue
            if (readHandlerType(source, hdlr) == "vide") return trak
        }
        return null
    }

    private fun readHandlerType(source: SeekableByteSource, hdlr: BoxHeader): String? {
        if (hdlr.end - hdlr.payloadStart < 12) return null
        return String(source.readAt(hdlr.payloadStart + 8, 4), Charsets.US_ASCII)
    }

    private fun readStereoMode(source: SeekableByteSource, st3d: BoxHeader): StereoMode? {
        if (st3d.end - st3d.payloadStart < 5) return null
        val mode = source.readAt(st3d.payloadStart + 4, 1)[0].toInt() and 0xFF
        return when (mode) {
            0 -> StereoMode.MONO
            1 -> StereoMode.TOP_AND_DOWN
            2 -> StereoMode.SIDE_BY_SIDE
            else -> null
        }
    }
}
