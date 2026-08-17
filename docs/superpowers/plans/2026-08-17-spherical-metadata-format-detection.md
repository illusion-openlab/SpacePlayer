# Spherical/Stereo Metadata Format Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the filename-keyword and aspect-ratio format-detection layers with direct parsing of a video file's own `st3d`/`sv3d` MP4 boxes (real embedded stereo/spherical metadata), and remove the format badge from the library grid card.

**Architecture:** A generic, pure-Kotlin ISO/IEC 14496-12 box-tree walker (`Mp4BoxReader`) at the bottom; a spherical/stereo-specific reader built on top of it that locates the first video track's sample entry and reads `st3d` (stereo mode) and `sv3d` (spherical projection, presence-only) from its children; `FormatDetector` rewired to use this instead of the two deleted heuristic layers, falling back to `FLAT`/`MONO` exactly as before when nothing is found.

**Tech Stack:** Kotlin, plain JVM unit tests (no Robolectric, no Android framework dependency in the box-parsing core), `android.os.ParcelFileDescriptor` + `java.nio.channels.FileChannel` for the one Android-facing file-opening step.

## Global Constraints

- Every task must build clean: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- The box-tree-walking core (`Mp4BoxReader` and the spherical/stereo reader logic) must have **zero Android framework imports** and be testable as plain JVM unit tests via a `ByteArray`-backed fixture — matching this project's established convention for `AspectRatioFormatDetector`/`PinchDetector` (pure functions, no Robolectric).
- `st3d` box payload: 4-byte FullBox header (version+flags) + 1-byte `stereo_mode` (0=mono, 1=top-bottom, 2=side-by-side; any other value, e.g. 3="stereo-custom", is unsupported and must map to `null`, not a guess).
- `sv3d` box: presence alone (not its internal `proj`/`equi` contents) is the signal used — maps to `Projection.SPHERE_360`. There is no box-level signal for `HEMISPHERE_180` in this scheme; a file with `st3d` but no `sv3d` correctly detects stereo mode and leaves projection undetected (defaults to `FLAT` downstream in `FormatDetector`, same as any other "not detected" case). This is a deliberate, documented limitation, not a bug to work around in this plan.
- The legacy Google Spatial Media V1 metadata scheme (XML text inside a `uuid` box) is explicitly out of scope for this plan.
- `FormatDetector.detect()` drops its `displayName: String` parameter entirely - no code path in the new pipeline consumes a filename.
- Only the **first** video track (first `trak` whose `mdia/hdlr` handler_type is `"vide"`) is inspected - matches `MultiviewTrackProbe`'s existing "first video/ mime track" convention.

---

### Task 1: `Mp4BoxReader` - generic ISO/IEC 14496-12 box-tree walker

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReader.kt`
- Create: `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/ByteArraySeekableByteSource.kt`
- Create: `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/TestBoxBuilder.kt`
- Create: `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReaderTest.kt`

**Interfaces:**
- Produces: `interface SeekableByteSource { val size: Long; fun readAt(position: Long, length: Int): ByteArray }`; `data class BoxHeader(val type: String, val start: Long, val totalSize: Long, val payloadStart: Long)` with computed `val end: Long`; `object Mp4BoxReader` with `fun readHeaderAt(source: SeekableByteSource, offset: Long, rangeEnd: Long): BoxHeader?`, `fun findChild(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, type: String): BoxHeader?`, `fun findAllChildren(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, type: String): List<BoxHeader>`, `fun findPath(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, path: List<String>): BoxHeader?`. Task 2 consumes all of these plus a test-only `ByteArraySeekableByteSource` and `box(type, payload)` helper (both defined here, reused by Task 2's tests).

This is a leaf task with no dependency on anything else in the plan. Write the tests first (TDD).

- [ ] **Step 1: Write the test-only box-builder and byte-source helpers**

Create `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/TestBoxBuilder.kt`:

```kotlin
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
```

Create `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/ByteArraySeekableByteSource.kt`:

```kotlin
package tech.illusion.spaceplayer.library.boxparse

/** Pure in-memory [SeekableByteSource] for unit tests - no file I/O, no Android dependency. */
class ByteArraySeekableByteSource(private val bytes: ByteArray) : SeekableByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, length: Int): ByteArray {
        val start = position.toInt()
        return bytes.copyOfRange(start, start + length)
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReaderTest.kt`:

```kotlin
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
        // findPath's range is a container's own payload range (what findChild/findAllChildren also
        // take), not the container box's own bytes - so first locate trak's header, same as
        // production callers do (see SphericalStereoMetadataReader.findFirstVideoTrak).
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "vide".toByteArray(Charsets.US_ASCII) + ByteArray(12))
        val mdia = box("mdia", hdlr)
        val trak = box("trak", mdia)
        val source = ByteArraySeekableByteSource(trak)
        val trakHeader = Mp4BoxReader.readHeaderAt(source, 0L, source.size)!!

        val found = Mp4BoxReader.findPath(source, trakHeader.payloadStart, trakHeader.end, listOf("mdia", "hdlr"))

        assertNotNull(found)
        assertEquals("hdlr", found!!.type)
    }

    @Test
    fun `findPath returns null when an intermediate segment is missing`() {
        val mdiaWithNoHdlr = box("mdia", ByteArray(0))
        val trak = box("trak", mdiaWithNoHdlr)
        val source = ByteArraySeekableByteSource(trak)
        val trakHeader = Mp4BoxReader.readHeaderAt(source, 0L, source.size)!!

        val found = Mp4BoxReader.findPath(source, trakHeader.payloadStart, trakHeader.end, listOf("mdia", "hdlr"))

        assertNull(found)
    }
}
```

**Note (recorded after Task 1's implementer flagged it during execution):** an earlier draft of `findPath` auto-read a root box at `rangeStart` to make the two tests above pass as originally written (with `source` built from the whole `trak` box and range `(0, source.size)`). That contradicts Task 2's planned usage, which passes an already-located box's `payloadStart`/`end` (a range already inside the container, not another box header) - e.g. `findPath(source, trak.payloadStart, trak.end, HDLR_PATH)`. The tests above are the corrected version: `findPath`'s implementation in Step 4 keeps its original range-is-already-a-payload-range contract unchanged, and the fix lives entirely in these two tests (locate the outer box's header first, then pass its `payloadStart`/`end`).

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.boxparse.Mp4BoxReaderTest"`
Expected: FAIL (compilation error - `Mp4BoxReader`, `SeekableByteSource`, `BoxHeader` don't exist yet)

- [ ] **Step 4: Implement `Mp4BoxReader`**

Create `app/src/main/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReader.kt`:

```kotlin
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
        var currentStart = rangeStart
        var currentEnd = rangeEnd
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.boxparse.Mp4BoxReaderTest"`
Expected: PASS, 7/7

- [ ] **Step 6: Full build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReader.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/boxparse/ByteArraySeekableByteSource.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/boxparse/TestBoxBuilder.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/boxparse/Mp4BoxReaderTest.kt
git commit -m "Add generic ISO 14496-12 box-tree walker for MP4 metadata parsing"
```

---

### Task 2: `SphericalStereoMetadataProbe` - read `st3d`/`sv3d` from a video file

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataProbe.kt`
- Create: `app/src/test/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataReaderTest.kt`
- Create: `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeSphericalMetadataProbe.kt`

**Interfaces:**
- Consumes: `Mp4BoxReader`, `SeekableByteSource`, `BoxHeader` (Task 1); `box(type, payload)` and `ByteArraySeekableByteSource` (Task 1's test helpers, reused here).
- Produces: `data class SphericalMetadataHint(val projection: Projection?, val stereoMode: StereoMode?)`; `interface SphericalMetadataProbe { fun probe(context: Context, uri: Uri): SphericalMetadataHint }`; `class Mp4SphericalStereoMetadataProbe : SphericalMetadataProbe`; `object SphericalStereoMetadataReader { fun read(source: SeekableByteSource): SphericalMetadataHint }` (the pure, testable core `Mp4SphericalStereoMetadataProbe` delegates to). Task 4 consumes `SphericalMetadataProbe`/`SphericalMetadataHint`/`Mp4SphericalStereoMetadataProbe`. `FakeSphericalMetadataProbe` is consumed by Task 4's rewritten `FormatDetectorTest`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataReaderTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.SphericalStereoMetadataReaderTest"`
Expected: FAIL (compilation error - `SphericalStereoMetadataReader` doesn't exist yet)

- [ ] **Step 3: Implement `SphericalStereoMetadataProbe.kt`**

Create `app/src/main/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataProbe.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.SphericalStereoMetadataReaderTest"`
Expected: PASS, 7/7

- [ ] **Step 5: Add the test fake for `FormatDetectorTest` (Task 4 will consume this)**

Create `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeSphericalMetadataProbe.kt`:

```kotlin
package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.SphericalMetadataHint
import tech.illusion.spaceplayer.library.SphericalMetadataProbe
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FakeSphericalMetadataProbe(
    private val projection: Projection? = null,
    private val stereoMode: StereoMode? = null,
) : SphericalMetadataProbe {
    override fun probe(context: Context, uri: Uri): SphericalMetadataHint =
        SphericalMetadataHint(projection, stereoMode)
}
```

- [ ] **Step 6: Full build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataProbe.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/SphericalStereoMetadataReaderTest.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeSphericalMetadataProbe.kt
git commit -m "Add SphericalStereoMetadataProbe to read real st3d/sv3d video metadata"
```

---

### Task 3: `FormatSource` enum + labels + string resources

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt:8`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt:55-64`
- Modify: `app/src/main/res/values/strings.xml:13-17`
- Modify: `app/src/main/res/values-zh/strings.xml:11-15`

**Interfaces:**
- Produces: `FormatSource.DETECTED_METADATA` (replaces `DETECTED_FILENAME`/`DETECTED_ASPECT_RATIO`). Task 4 consumes this.

This task is independent of Tasks 1/2 - it only touches the enum and its display strings, not any detection logic.

- [ ] **Step 1: Update the `FormatSource` enum**

Current `VideoItem.kt:8`:

```kotlin
enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, DETECTED_ASPECT_RATIO, MANUAL_OVERRIDE, DEFAULT }
```

Change to:

```kotlin
enum class FormatSource { DETECTED_CONTAINER, DETECTED_METADATA, MANUAL_OVERRIDE, DEFAULT }
```

- [ ] **Step 2: Update `Labels.kt`'s `FormatSource.label()`**

Current `Labels.kt:55-64`:

```kotlin
@Composable
fun FormatSource.label(): String = stringResource(
    when (this) {
        FormatSource.DETECTED_CONTAINER -> R.string.format_source_container
        FormatSource.DETECTED_FILENAME -> R.string.format_source_filename
        FormatSource.DETECTED_ASPECT_RATIO -> R.string.format_source_aspect_ratio
        FormatSource.MANUAL_OVERRIDE -> R.string.format_source_manual
        FormatSource.DEFAULT -> R.string.format_source_default
    },
)
```

Change to:

```kotlin
@Composable
fun FormatSource.label(): String = stringResource(
    when (this) {
        FormatSource.DETECTED_CONTAINER -> R.string.format_source_container
        FormatSource.DETECTED_METADATA -> R.string.format_source_metadata
        FormatSource.MANUAL_OVERRIDE -> R.string.format_source_manual
        FormatSource.DEFAULT -> R.string.format_source_default
    },
)
```

- [ ] **Step 3: Update English strings**

Current `app/src/main/res/values/strings.xml:13-17`:

```xml
    <string name="format_source_container">Container detection</string>
    <string name="format_source_filename">Filename detection</string>
    <string name="format_source_aspect_ratio">Aspect-ratio detection</string>
    <string name="format_source_manual">Manual override</string>
    <string name="format_source_default">Default fallback</string>
```

Change to:

```xml
    <string name="format_source_container">Container detection</string>
    <string name="format_source_metadata">Metadata detection</string>
    <string name="format_source_manual">Manual override</string>
    <string name="format_source_default">Default fallback</string>
```

- [ ] **Step 4: Update Chinese strings**

Current `app/src/main/res/values-zh/strings.xml:11-15`:

```xml
    <string name="format_source_container">容器探测</string>
    <string name="format_source_filename">文件名识别</string>
    <string name="format_source_aspect_ratio">宽高比推测</string>
    <string name="format_source_manual">手动指定</string>
    <string name="format_source_default">默认兜底</string>
```

Change to:

```xml
    <string name="format_source_container">容器探测</string>
    <string name="format_source_metadata">元数据识别</string>
    <string name="format_source_manual">手动指定</string>
    <string name="format_source_default">默认兜底</string>
```

- [ ] **Step 5: Build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: **This will FAIL** - `FormatDetector.kt`, `AspectRatioFormatDetector.kt`, and their tests still reference the now-deleted `DETECTED_FILENAME`/`DETECTED_ASPECT_RATIO` enum constants. This is expected and resolved by Task 4, which touches those files next. Confirm the failure is specifically about the missing enum constants (unresolved reference), not something else, then proceed - do not try to make Task 3 build in isolation by leaving both old and new enum values in place, since that would ship a half-migrated enum.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml
git commit -m "Replace DETECTED_FILENAME/DETECTED_ASPECT_RATIO with DETECTED_METADATA"
```

---

### Task 4: Rewire `FormatDetector`, delete the two old detector layers

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt`
- Modify: `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt:8-10,33,83` (approximate - see exact snippets below)
- Modify: `app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt` (full rewrite)
- Delete: `app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt`
- Delete: `app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt`
- Delete: `app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt`
- Delete: `app/src/test/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetectorTest.kt`

**Interfaces:**
- Consumes: `Mp4SphericalStereoMetadataProbe`, `SphericalMetadataProbe`, `SphericalMetadataHint` (Task 2); `FakeSphericalMetadataProbe` (Task 2); `FormatSource.DETECTED_METADATA` (Task 3).
- Produces: `FormatDetector(multiviewTrackProbe: MultiviewTrackProbe, sphericalMetadataProbe: SphericalMetadataProbe)` with `fun detect(context: Context, uri: Uri): DetectedFormat` (no `displayName` parameter). Task 5 does not depend on this task's internals (it only touches `VideoGridCard.kt`'s rendering, not `FormatDetector`).

This task is intentionally one large, atomic unit rather than split further: `ContainerProbeResult`'s shape, `FormatDetector`'s pipeline, `LibraryViewModel`'s call site, and the deletion of the two now-unused detector files are all mutually coupled - none of them compiles correctly with only some of the others applied, so splitting this into smaller tasks would create a non-buildable intermediate state.

- [ ] **Step 1: Simplify `ContainerProbeResult` and `MediaExtractorMultiviewProbe`**

Current `MultiviewTrackProbe.kt`, full file:

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

data class ContainerProbeResult(
    val isMultiview: Boolean,
    val videoWidth: Int?,
    val videoHeight: Int?,
)

interface MultiviewTrackProbe {
    fun probe(context: Context, uri: Uri): ContainerProbeResult
}

private const val TAG = "MediaExtractorMultiviewProbe"

/**
 * 启发式代理判断，不是精确的 MV-HEVC 识别：标准 Android `MediaExtractor`/`MediaFormat` 没有
 * 文档化的 ISO/IEC 23008-2 Annex G 多视图分组信息读取接口。这里只是数"同分辨率 HEVC 视频轨道数
 * 是否 ≥ 2"，命中就当作多视图。本机没有真实 Apple 空间视频样本文件验证过这个启发式的准确率——
 * 文件名识别（`_mvhevc`）和用户手动覆盖仍是 V1 实际可靠的兜底路径，见 AGENTS.md Stage 2 记录。
 *
 * `videoWidth`/`videoHeight` 顺带取遍历轨道时遇到的第一条视频轨（`mime` 以 "video/" 开头，不限定
 * HEVC——宽高比检测要对任意编码的视频生效，跟多视图判断各自独立），复用这同一次
 * `MediaExtractor.setDataSource()` 解析，不额外开一次文件。
 */
class MediaExtractorMultiviewProbe : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hevcVideoTrackCount = 0
            var firstWidth = -1
            var firstHeight = -1
            var resolutionsMatch = true
            var videoWidth: Int? = null
            var videoHeight: Int? = null
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoWidth == null) {
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                }
                if (mime != "video/hevc") continue
                hevcVideoTrackCount++
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (firstWidth == -1) {
                    firstWidth = width
                    firstHeight = height
                } else if (width != firstWidth || height != firstHeight) {
                    resolutionsMatch = false
                }
            }
            ContainerProbeResult(
                isMultiview = hevcVideoTrackCount >= 2 && resolutionsMatch,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            )
        } catch (e: Exception) {
            Log.e(TAG, "probe failed for $uri", e)
            ContainerProbeResult(isMultiview = false, videoWidth = null, videoHeight = null)
        } finally {
            extractor.release()
        }
    }
}
```

Replace the whole file with:

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

data class ContainerProbeResult(val isMultiview: Boolean)

interface MultiviewTrackProbe {
    fun probe(context: Context, uri: Uri): ContainerProbeResult
}

private const val TAG = "MediaExtractorMultiviewProbe"

/**
 * 启发式代理判断，不是精确的 MV-HEVC 识别：标准 Android `MediaExtractor`/`MediaFormat` 没有
 * 文档化的 ISO/IEC 23008-2 Annex G 多视图分组信息读取接口。这里只是数"同分辨率 HEVC 视频轨道数
 * 是否 ≥ 2"，命中就当作多视图。本机没有真实 Apple 空间视频样本文件验证过这个启发式的准确率——
 * 用户手动覆盖仍是实际可靠的兜底路径，见 AGENTS.md Stage 2 记录。
 */
class MediaExtractorMultiviewProbe : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hevcVideoTrackCount = 0
            var firstWidth = -1
            var firstHeight = -1
            var resolutionsMatch = true
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime != "video/hevc") continue
                hevcVideoTrackCount++
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (firstWidth == -1) {
                    firstWidth = width
                    firstHeight = height
                } else if (width != firstWidth || height != firstHeight) {
                    resolutionsMatch = false
                }
            }
            ContainerProbeResult(isMultiview = hevcVideoTrackCount >= 2 && resolutionsMatch)
        } catch (e: Exception) {
            Log.e(TAG, "probe failed for $uri", e)
            ContainerProbeResult(isMultiview = false)
        } finally {
            extractor.release()
        }
    }
}
```

**Note (recorded after Task 4's task review flagged it):** the doc comment above previously still said "文件名识别（`_mvhevc`）和用户手动覆盖仍是 V1 实际可靠的兜底路径" (filename detection remains a reliable fallback) — stale, since this same task deletes `FilenameFormatDetector.kt`, the only place that filename-based `_mvhevc` detection existed. The version above is corrected (filename-detection clause removed). Fixed post-implementation in commit `c60432b`.

- [ ] **Step 2: Simplify `FakeMultiviewTrackProbe`**

Current, full file:

```kotlin
package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.ContainerProbeResult
import tech.illusion.spaceplayer.library.MultiviewTrackProbe

class FakeMultiviewTrackProbe(
    private val isMultiview: Boolean,
    private val videoWidth: Int? = null,
    private val videoHeight: Int? = null,
) : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult =
        ContainerProbeResult(isMultiview, videoWidth, videoHeight)
}
```

Replace with:

```kotlin
package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.ContainerProbeResult
import tech.illusion.spaceplayer.library.MultiviewTrackProbe

class FakeMultiviewTrackProbe(private val isMultiview: Boolean) : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult =
        ContainerProbeResult(isMultiview)
}
```

- [ ] **Step 3: Rewire `FormatDetector`**

Current, full file:

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 识别流水线：容器探测（多视图判断 + 顺带拿宽高）→ 文件名关键词 → 宽高比补缺 → 默认兜底。
 * 文件名在每个字段上都优先于宽高比——宽高比只填文件名没提到的那个字段，从不覆盖文件名已经命中的
 * 字段。见设计稿 docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md。
 */
class FormatDetector(private val multiviewTrackProbe: MultiviewTrackProbe) {
    fun detect(context: Context, uri: Uri, displayName: String): DetectedFormat {
        val containerResult = multiviewTrackProbe.probe(context, uri)
        val filenameHint = FilenameFormatDetector.detect(displayName)
        val aspectHint = aspectRatioHintOrNull(containerResult)

        if (containerResult.isMultiview) {
            return DetectedFormat(
                projection = filenameHint.projection ?: aspectHint?.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }

        val projection = filenameHint.projection ?: aspectHint?.projection
        val stereoMode = filenameHint.stereoMode ?: aspectHint?.stereoMode
        val formatSource = when {
            filenameHint.projection != null || filenameHint.stereoMode != null -> FormatSource.DETECTED_FILENAME
            aspectHint != null -> FormatSource.DETECTED_ASPECT_RATIO
            else -> FormatSource.DEFAULT
        }
        return DetectedFormat(
            projection = projection ?: Projection.FLAT,
            stereoMode = stereoMode ?: StereoMode.MONO,
            formatSource = formatSource,
        )
    }

    private fun aspectRatioHintOrNull(containerResult: ContainerProbeResult): DetectedFormat? {
        val width = containerResult.videoWidth ?: return null
        val height = containerResult.videoHeight ?: return null
        return AspectRatioFormatDetector.detect(width, height)
    }
}
```

Replace with:

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 识别流水线：容器探测（多视图判断）→ 球面/立体元数据探测（读视频文件本身的 st3d/sv3d box）→
 * 默认兜底。文件名关键词和宽高比推测两层已删除——用 ffprobe 实测过的真实文件证明两者在 180°SBS/
 * 360°TB 这类组合上会给出确凿的错误答案（不是阈值没调好，是整帧宽高比本身就有歧义），而这些真实
 * 文件都携带标准的 st3d/sv3d 元数据。见设计稿
 * docs/superpowers/specs/2026-08-17-spherical-metadata-format-detection-design.md。
 */
class FormatDetector(
    private val multiviewTrackProbe: MultiviewTrackProbe,
    private val sphericalMetadataProbe: SphericalMetadataProbe,
) {
    fun detect(context: Context, uri: Uri): DetectedFormat {
        val containerResult = multiviewTrackProbe.probe(context, uri)
        val metadataHint = sphericalMetadataProbe.probe(context, uri)

        if (containerResult.isMultiview) {
            return DetectedFormat(
                projection = metadataHint.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }

        val formatSource = if (metadataHint.projection != null || metadataHint.stereoMode != null) {
            FormatSource.DETECTED_METADATA
        } else {
            FormatSource.DEFAULT
        }
        return DetectedFormat(
            projection = metadataHint.projection ?: Projection.FLAT,
            stereoMode = metadataHint.stereoMode ?: StereoMode.MONO,
            formatSource = formatSource,
        )
    }
}
```

- [ ] **Step 4: Update `LibraryViewModel`'s construction and call site**

Current `LibraryViewModel.kt:8-10`:

```kotlin
import tech.illusion.spaceplayer.library.FormatDetector
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.MediaExtractorMultiviewProbe
```

Change to:

```kotlin
import tech.illusion.spaceplayer.library.FormatDetector
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.MediaExtractorMultiviewProbe
import tech.illusion.spaceplayer.library.Mp4SphericalStereoMetadataProbe
```

Current `LibraryViewModel.kt:33`:

```kotlin
    private val formatDetector = FormatDetector(MediaExtractorMultiviewProbe())
```

Change to:

```kotlin
    private val formatDetector = FormatDetector(MediaExtractorMultiviewProbe(), Mp4SphericalStereoMetadataProbe())
```

Find the `detect(...)` call site (currently at `LibraryViewModel.kt:83`, inside `toVideoItem()`):

```kotlin
        val detected = formatDetector.detect(context, record.uri, record.displayName)
```

Change to:

```kotlin
        val detected = formatDetector.detect(context, record.uri)
```

- [ ] **Step 5: Rewrite `FormatDetectorTest`**

Replace the full file `app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt` with:

```kotlin
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
```

- [ ] **Step 6: Delete the two old detector layers and their tests**

```bash
git rm app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt
git rm app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt
git rm app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt
git rm app/src/test/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetectorTest.kt
```

- [ ] **Step 7: Run the rewritten test and the full suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.FormatDetectorTest"`
Expected: PASS, 6/6

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no leftover references to `FilenameFormatDetector`/`AspectRatioFormatDetector`/`DETECTED_FILENAME`/`DETECTED_ASPECT_RATIO` anywhere (grep to confirm: `grep -rn "FilenameFormatDetector\|AspectRatioFormatDetector\|DETECTED_FILENAME\|DETECTED_ASPECT_RATIO" app/src` should return nothing)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt \
        app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt
git commit -m "Rewire FormatDetector onto spherical metadata, delete filename/aspect-ratio layers"
```

---

### Task 5: Remove the format badge from the library grid card

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/VideoGridCard.kt:116-137`

**Interfaces:** None - this is a pure UI change independent of everything else in this plan. Can be done in any order relative to Tasks 1-4.

- [ ] **Step 1: Remove the projection/stereo badge block**

Current `VideoGridCard.kt:116-137`:

```kotlin
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                val stereoLabel = item.stereoMode.badgeLabel()
                Badge(
                    badgeColor = BadgeDefaults.badgeColors(
                        backgroundColor = item.projection.badgeColor(),
                        contentColor = SpacePlayerOnAccent,
                    ),
                ) {
                    Text(
                        text = if (stereoLabel != null) {
                            "${item.projection.label()} · $stereoLabel"
                        } else {
                            item.projection.label()
                        },
                        style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
            }

            Box(
```

Delete the first `Box { ... }` block (the one containing the `Badge` with `stereoLabel`/`item.projection.badgeColor()`) entirely, keeping the second `Box(modifier = Modifier.align(Alignment.BottomEnd)...)` (the duration badge) untouched. The file should read directly from the thumbnail `Image`/`thumbnail?.let { }` block straight into the duration badge's `Box`, with nothing in between.

- [ ] **Step 2: Check for now-unused imports/functions**

After deleting that block, `item.projection.badgeColor()` and `item.stereoMode.badgeLabel()` are no longer called from this file. Run:

```bash
grep -n "badgeLabel\|badgeColor" app/src/main/java/tech/illusion/spaceplayer/ui/library/VideoGridCard.kt
```

If `badgeLabel`/`badgeColor` no longer appear in this file at all, remove the now-unused `import tech.illusion.spaceplayer.ui.badgeLabel` line (check whether `Badge`/`BadgeDefaults` imports are still needed for the duration badge below - they are, since that Box still uses `Badge`/`BadgeDefaults.badgeColors`, so only the `badgeLabel` extension-function import becomes unused, not the SDK `Badge` import itself). Do **not** touch `Projection.badgeColor()`'s definition in `ui/library/SpacePlayerPalette.kt` (or wherever it's defined) - it may still be used elsewhere (check with `grep -rn "\.badgeColor()" app/src/main` before deciding whether that function itself becomes dead code; if it has no other callers, leave it in place for now rather than expanding this task's scope - a follow-up cleanup, not part of this plan).

- [ ] **Step 3: Build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Emulator screenshot verification**

```bash
pico-cli device list
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
pico-cli capture screenshot --out ./artifacts/task5-no-format-badge.png --device emulator-5554
```

Check the screenshot: grid cards show a thumbnail, the duration badge (bottom-right), title, and the `formatSource · size` caption line - no colored badge at the top-left of the thumbnail.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/VideoGridCard.kt
git commit -m "Remove the format badge from library grid cards"
```

---

### Task 6: Full verification against the three real sample files + AGENTS.md record

**Files:**
- Modify: `AGENTS.md` (append a new dated section)

**Interfaces:** None.

- [ ] **Step 1: Full clean build and test**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew clean assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all unit tests passing (this plan added 7 + 7 + 6 = 20 new test cases across `Mp4BoxReaderTest`, `SphericalStereoMetadataReaderTest`, and the rewritten `FormatDetectorTest`, and removed whatever `FilenameFormatDetectorTest`/`AspectRatioFormatDetectorTest` contributed - report the actual before/after total, don't assume a specific number).

- [ ] **Step 2: Push the three real sample files to a device/emulator's MediaStore**

These files live at `/Users/zohar/Downloads/视频/` (`180-LR.mp4`, `360_TB.mp4`, `360.mp4`) and are too large to commit to the repo (up to ~700MB). Push them directly for one-off verification:

```bash
pico-cli device list
pico-cli files push "/Users/zohar/Downloads/视频/180-LR.mp4" /sdcard/Movies/180-LR.mp4 --device emulator-5554
pico-cli files push "/Users/zohar/Downloads/视频/360_TB.mp4" /sdcard/Movies/360_TB.mp4 --device emulator-5554
pico-cli files push "/Users/zohar/Downloads/视频/360.mp4" /sdcard/Movies/360.mp4 --device emulator-5554
pico-cli shell content call --uri content://media --method scan_file --arg /storage/emulated/0/Movies/180-LR.mp4 --device emulator-5554
pico-cli shell content call --uri content://media --method scan_file --arg /storage/emulated/0/Movies/360_TB.mp4 --device emulator-5554
pico-cli shell content call --uri content://media --method scan_file --arg /storage/emulated/0/Movies/360.mp4 --device emulator-5554
```

(The explicit `content call ... scan_file` step matches this project's own documented lesson in `AGENTS.md` - a plain `files push` can leave the MediaStore record `is_pending=1` and invisible to the app until scanned.)

- [ ] **Step 3: Install the current build and inspect the library**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
pico-cli capture screenshot --out ./artifacts/task6-real-files-verify.png --device emulator-5554
```

Select each of the three new items in turn and check the bottom bar / correction UI's currently-detected projection and stereo mode against these expected results (from the design spec's "已知限制" section):

- `360_TB.mp4`: SPHERE_360 / TOP_AND_DOWN (both correct)
- `360.mp4`: SPHERE_360 / MONO (both correct)
- `180-LR.mp4`: stereo mode SIDE_BY_SIDE (correct), projection FLAT (expected default - no `sv3d` box in this file, this is the documented limitation, not a bug)

If any result doesn't match, do not adjust the detection logic to force a match without understanding why first - re-run `ffprobe -v quiet -print_format json -show_streams "<file>"` on the specific file to confirm what's actually embedded, since a mismatch here means either the box-parsing has a real bug (fix it) or the file's actual metadata differs from what this plan assumed (update the plan/spec's documented expectation, don't paper over it).

- [ ] **Step 4: Record the result in `AGENTS.md`**

Append a new dated section to `AGENTS.md` (follow the file's existing per-change dated-section convention - read the last few sections for tone/format before writing). Content must reflect the **actual** Step 3 result, not the expected one - if all three matched expectations, say so plainly; if `180-LR.mp4` or any other file didn't match, describe exactly what was seen. Cover: what changed (filename/aspect-ratio detection replaced by real `st3d`/`sv3d` box parsing; format badge removed from grid cards), why (both old heuristics proven wrong via `ffprobe` on real files, not just theoretically), the known limitation (files with `st3d` but no `sv3d` get correct stereo mode and default `FLAT` projection), and the verification evidence (build/test numbers, screenshot path, per-file detected-vs-expected table from Step 3).

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md
git commit -m "Record spherical/stereo metadata format detection in AGENTS.md"
```
