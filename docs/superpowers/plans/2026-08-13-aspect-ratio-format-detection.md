# Aspect-Ratio Format Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third video-format-detection layer — aspect-ratio heuristics — that fills in projection/stereo-mode gaps the existing filename-keyword detector leaves behind, without opening any video file a second time.

**Architecture:** A new pure, Android-independent object (`AspectRatioFormatDetector`) classifies a width/height pair into a projection or stereo-mode hint using four sequential ratio bands. `MultiviewTrackProbe`'s single existing `MediaExtractor` pass is extended to also report the primary video track's width/height (no second file open). `FilenameFormatDetector`'s return type changes from one nullable, fully-defaulted result to two independently-nullable fields, so `FormatDetector` can fill only the field filename actually missed — filename always wins on a field-by-field basis, aspect ratio never overrides a field filename matched.

**Tech Stack:** Kotlin, JUnit4 (existing `app/src/test` JVM unit tests, no Android instrumentation needed for this feature).

## Global Constraints

- Filename detection outranks aspect-ratio detection on every field independently; aspect ratio only fills a field filename left `null`. (spec §架构与数据流)
- Aspect ratio never touches MV-HEVC detection — that stays exclusively the container track-count probe's job. (spec §范围)
- No MP4 Spherical Video V2 metadata parsing, no combined 360°+stereo frame detection, no pixel decoding — width/height arithmetic only. (spec §非目标)
- Reuse the single existing `MediaExtractor` pass in `MultiviewTrackProbe` for width/height; never open the video file a second time just for aspect ratio. (spec §组件改动 `MultiviewTrackProbe.kt`)
- Thresholds (exact spec values, do not vary): `EQUIRECT_360` 1.85f–2.15f, `HEMISPHERE_180` 0.9f–1.1f, `NORMAL_VIDEO_RATIO` 1.3f–2.4f (lower bound is 1.3, not 1.0 — 1.0 was tried during design and produced a false positive on ordinary 1080×1920 portrait video; see spec's "下限为什么是 1.3 不是 1.0"). (spec §新增 `AspectRatioFormatDetector.kt`)
- This is backend-only Kotlin logic plus one string resource; no ECS/Spatial SDK/Stage/WindowContainer involvement, no emulator/device verification required — fully covered by JVM unit tests. (spec §测试计划)

## Task Sequencing Note

`MultiviewTrackProbe`, `FilenameFormatDetector`, and `FormatDetector` are compile-coupled: `FormatDetector.kt` is the only caller of the other two, so changing either one's public signature breaks `FormatDetector.kt` until it's updated too, and the whole module (main + test sources) has to compile before Gradle can run *any* test in it — there's no way to get a real green run for "just the probe" or "just the filename detector" in isolation. Splitting them into separately-reviewable tasks would mean each one's own verification step reports a false failure caused by a file it doesn't own. So they're one task (Task 2) with several write-test/implement pairs inside it, and a single real green check at the end. `AspectRatioFormatDetector` (Task 1) has no such coupling — nothing existing calls it yet — so it stays its own fully independent, fully verifiable task.

---

### Task 1: `AspectRatioFormatDetector` — pure width/height classifier

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetectorTest.kt`

**Interfaces:**
- Consumes: `Projection` (`app/src/main/java/tech/illusion/spaceplayer/playback/Projection.kt`, values `FLAT`/`HEMISPHERE_180`/`SPHERE_360`), `StereoMode` (`app/src/main/java/tech/illusion/spaceplayer/playback/StereoMode.kt`, values `MONO`/`SIDE_BY_SIDE`/`TOP_AND_DOWN`/`MULTIVIEW_MVHEVC`), `DetectedFormat` and `FormatSource` (both in `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt`).
- Produces: `AspectRatioFormatDetector.detect(width: Int, height: Int): DetectedFormat?` — a top-level `object` with this single public function. Task 2 calls this directly. When it returns non-null, the field NOT relevant to the matched band is set to the neutral default (`Projection.FLAT` for the two stereo bands, `StereoMode.MONO` for the two projection bands) so callers can safely read either field off the result without checking which band fired.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetectorTest.kt`:

```kotlin
package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class AspectRatioFormatDetectorTest {

    @Test
    fun `2 to 1 ratio detects sphere 360 projection`() {
        val result = AspectRatioFormatDetector.detect(3840, 1920)
        assertEquals(Projection.SPHERE_360, result?.projection)
        assertEquals(StereoMode.MONO, result?.stereoMode)
        assertEquals(FormatSource.DETECTED_ASPECT_RATIO, result?.formatSource)
    }

    @Test
    fun `2 to 1 ratio at a larger resolution also detects sphere 360`() {
        assertEquals(Projection.SPHERE_360, AspectRatioFormatDetector.detect(5760, 2880)?.projection)
    }

    @Test
    fun `square ratio detects hemisphere 180 projection`() {
        val result = AspectRatioFormatDetector.detect(2160, 2160)
        assertEquals(Projection.HEMISPHERE_180, result?.projection)
        assertEquals(StereoMode.MONO, result?.stereoMode)
    }

    @Test
    fun `square ratio at a smaller resolution also detects hemisphere 180`() {
        assertEquals(Projection.HEMISPHERE_180, AspectRatioFormatDetector.detect(1440, 1440)?.projection)
    }

    @Test
    fun `double-wide frame detects side-by-side stereo`() {
        // 3840x1080 halves to 1920x1080 (16:9) - a plausible single-eye frame.
        val result = AspectRatioFormatDetector.detect(3840, 1080)
        assertEquals(Projection.FLAT, result?.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result?.stereoMode)
    }

    @Test
    fun `double-height frame detects top-and-down stereo`() {
        // 1920x2160 halves to 1920x1080 (16:9) - a plausible single-eye frame.
        val result = AspectRatioFormatDetector.detect(1920, 2160)
        assertEquals(Projection.FLAT, result?.projection)
        assertEquals(StereoMode.TOP_AND_DOWN, result?.stereoMode)
    }

    @Test
    fun `ordinary 16 by 9 landscape video matches no band`() {
        assertNull(AspectRatioFormatDetector.detect(1920, 1080))
    }

    @Test
    fun `ordinary 9 by 16 portrait video matches no band`() {
        // Regression case: an earlier 1.0f lower bound on the stereo band made this false-positive
        // as TOP_AND_DOWN (1080 / (1920/2) = 1.125). Portrait video is common, so this must stay null.
        assertNull(AspectRatioFormatDetector.detect(1080, 1920))
    }

    @Test
    fun `non-positive dimensions return null`() {
        assertNull(AspectRatioFormatDetector.detect(0, 1080))
        assertNull(AspectRatioFormatDetector.detect(1920, 0))
        assertNull(AspectRatioFormatDetector.detect(-1920, 1080))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "tech.illusion.spaceplayer.library.AspectRatioFormatDetectorTest"`
Expected: FAIL — `AspectRatioFormatDetector` is unresolved (class does not exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt`:

```kotlin
package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 从视频轨道宽高推测投影/立体格式，作为文件名检测之后的补缺信号（见设计稿
 * docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md）。四个分支按顺序判断，
 * 命中即返回，不会重复分类；返回值里跟命中分支无关的字段用中性默认值（FLAT/MONO）占位。
 *
 * NORMAL_VIDEO_RATIO 下限是 1.3 不是看起来更自然的 1.0——1.0 会把常规 9:16 竖屏视频（1080x1920）
 * 误判成 TOP_AND_DOWN（1080 / (1920/2) = 1.125，落进 1.0~2.4）。竖屏视频很常见，这个误判代价不小，
 * 所以把下限提到 1.3，排掉近似正方形的"半高比"，同时仍覆盖 4:3=1.33 到 21:9=2.33 的常见横屏比例。
 */
object AspectRatioFormatDetector {
    private const val EQUIRECT_360_MIN = 1.85f
    private const val EQUIRECT_360_MAX = 2.15f
    private const val HEMISPHERE_180_MIN = 0.9f
    private const val HEMISPHERE_180_MAX = 1.1f
    private const val NORMAL_VIDEO_RATIO_MIN = 1.3f
    private const val NORMAL_VIDEO_RATIO_MAX = 2.4f

    fun detect(width: Int, height: Int): DetectedFormat? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio in EQUIRECT_360_MIN..EQUIRECT_360_MAX ->
                DetectedFormat(Projection.SPHERE_360, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            ratio in HEMISPHERE_180_MIN..HEMISPHERE_180_MAX ->
                DetectedFormat(Projection.HEMISPHERE_180, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            (width / 2f) / height in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.SIDE_BY_SIDE, FormatSource.DETECTED_ASPECT_RATIO)
            width / (height / 2f) in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.TOP_AND_DOWN, FormatSource.DETECTED_ASPECT_RATIO)
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "tech.illusion.spaceplayer.library.AspectRatioFormatDetectorTest"`
Expected: PASS, 9 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetectorTest.kt
git commit -m "Add AspectRatioFormatDetector for width/height-based format hints"
```

---

### Task 2: Wire aspect ratio into `MultiviewTrackProbe`, `FilenameFormatDetector`, and `FormatDetector`

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt`
- Modify: `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt`
- Modify: `app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt`
- Modify: `app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt:8`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt:56-63`
- Modify: `app/src/main/res/values/strings.xml:13-16`
- Modify: `app/src/main/res/values-zh/strings.xml:11-14`

**Interfaces:**
- Consumes: `AspectRatioFormatDetector.detect(width, height): DetectedFormat?` (Task 1).
- Produces: `data class ContainerProbeResult(val isMultiview: Boolean, val videoWidth: Int?, val videoHeight: Int?)`, `MultiviewTrackProbe.probe(context, uri): ContainerProbeResult` (replaces the old `looksLikeMultiview(context, uri): Boolean`), `FakeMultiviewTrackProbe(isMultiview: Boolean, videoWidth: Int? = null, videoHeight: Int? = null)`, `data class FilenameHint(val projection: Projection?, val stereoMode: StereoMode?)`, `FilenameFormatDetector.detect(displayName: String): FilenameHint` (no longer nullable itself). `FormatDetector.detect(context, uri, displayName): DetectedFormat` keeps its existing public signature — `LibraryViewModel.toVideoItem()` (`app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt:71`) calls this and needs **no changes**.

This task has four write/implement pairs that land together because of the compile coupling described above — do them in this order, but only run the build/tests at the very end (Step 9); the intermediate "implement" steps will leave the module non-compiling by design until Step 8 is done, same as any multi-file rename.

- [ ] **Step 1: Rewrite `MultiviewTrackProbe.kt`**

Replace the full contents of `app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt`:

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

- [ ] **Step 2: Update the `MultiviewTrackProbe` test double**

Replace the full contents of `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt`:

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

`MediaExtractorMultiviewProbe` itself has no unit test (same as before this change) — it calls into `android.media.MediaExtractor`, unavailable in a plain JVM test. It's only exercised through this fake (in `FormatDetectorTest`, Step 6 below) and through real playback on-device.

- [ ] **Step 3: Rewrite the `FilenameFormatDetectorTest.kt`**

Replace the full contents of `app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt`:

```kotlin
package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FilenameFormatDetectorTest {

    @Test
    fun `no keyword returns hint with both fields null`() {
        val result = FilenameFormatDetector.detect("my_trip.mp4")
        assertNull(result.projection)
        assertNull(result.stereoMode)
    }

    @Test
    fun `_180_ keyword detects hemisphere projection, stereo mode left null`() {
        val result = FilenameFormatDetector.detect("hawaii_180_beach.mp4")
        assertEquals(Projection.HEMISPHERE_180, result.projection)
        assertNull(result.stereoMode)
    }

    @Test
    fun `_180x180 keyword detects hemisphere projection`() {
        assertEquals(Projection.HEMISPHERE_180, FilenameFormatDetector.detect("clip_180x180.mp4").projection)
    }

    @Test
    fun `_360_ keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("concert_360_live.mp4").projection)
    }

    @Test
    fun `_equirect keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("scene_equirect.mp4").projection)
    }

    @Test
    fun `_sbs keyword detects side-by-side stereo, projection left null`() {
        val result = FilenameFormatDetector.detect("movie_sbs.mp4")
        assertNull(result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `_3dh keyword detects side-by-side stereo`() {
        assertEquals(StereoMode.SIDE_BY_SIDE, FilenameFormatDetector.detect("show_3dh.mp4").stereoMode)
    }

    @Test
    fun `_tb keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_tb.mp4").stereoMode)
    }

    @Test
    fun `_ou keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_ou.mp4").stereoMode)
    }

    @Test
    fun `_3dv keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_3dv.mp4").stereoMode)
    }

    @Test
    fun `_mvhevc keyword detects multiview stereo`() {
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, FilenameFormatDetector.detect("spatial_mvhevc.mp4").stereoMode)
    }

    @Test
    fun `combined projection and stereo keywords both detected`() {
        val result = FilenameFormatDetector.detect("trip_360_sbs.mp4")
        assertEquals(Projection.SPHERE_360, result.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result.stereoMode)
    }

    @Test
    fun `keyword matching is case-insensitive`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("Trip_360_Live.MP4").projection)
    }
}
```

- [ ] **Step 4: Rewrite `FilenameFormatDetector.kt`**

Replace the full contents of `app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt`:

```kotlin
package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

data class FilenameHint(
    val projection: Projection?,
    val stereoMode: StereoMode?,
)

/**
 * 纯文件名关键词识别，命中优先级见设计稿第 2 节。同一文件名可以同时命中投影关键词和立体格式关键词
 * （如 "trip_360_sbs.mp4"），两者互相独立判断。两个字段各自可能是 null——不在这里填成 FLAT/MONO，
 * 是为了让 FormatDetector 能分辨"文件名真的没提到这个字段"和"提到了但结果恰好是默认值"，从而让
 * 宽高比检测去补前者、不覆盖后者（见 docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md）。
 */
object FilenameFormatDetector {
    private val HEMISPHERE_180_KEYWORDS = listOf("_180_", "_180x180")
    private val SPHERE_360_KEYWORDS = listOf("_360_", "_equirect")
    private val SIDE_BY_SIDE_KEYWORDS = listOf("_sbs", "_3dh")
    private val TOP_AND_DOWN_KEYWORDS = listOf("_tb", "_ou", "_3dv")
    private val MULTIVIEW_KEYWORDS = listOf("_mvhevc")

    fun detect(displayName: String): FilenameHint {
        val lower = displayName.lowercase()
        val projection = when {
            HEMISPHERE_180_KEYWORDS.any(lower::contains) -> Projection.HEMISPHERE_180
            SPHERE_360_KEYWORDS.any(lower::contains) -> Projection.SPHERE_360
            else -> null
        }
        val stereoMode = when {
            MULTIVIEW_KEYWORDS.any(lower::contains) -> StereoMode.MULTIVIEW_MVHEVC
            SIDE_BY_SIDE_KEYWORDS.any(lower::contains) -> StereoMode.SIDE_BY_SIDE
            TOP_AND_DOWN_KEYWORDS.any(lower::contains) -> StereoMode.TOP_AND_DOWN
            else -> null
        }
        return FilenameHint(projection, stereoMode)
    }
}
```

- [ ] **Step 5: Add the new `FormatSource` value, string resources, and `Labels.kt` branch**

In `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt:8`, change:

```kotlin
enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, MANUAL_OVERRIDE, DEFAULT }
```

to:

```kotlin
enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, DETECTED_ASPECT_RATIO, MANUAL_OVERRIDE, DEFAULT }
```

In `app/src/main/res/values/strings.xml`, add a line after the `format_source_filename` entry (around line 14):

```xml
    <string name="format_source_aspect_ratio">Aspect-ratio detection</string>
```

In `app/src/main/res/values-zh/strings.xml`, add the matching line after the `format_source_filename` entry (around line 12):

```xml
    <string name="format_source_aspect_ratio">宽高比推测</string>
```

In `app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt`, change the `FormatSource.label()` function (lines 56-63) to:

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

- [ ] **Step 6: Rewrite `FormatDetectorTest.kt`**

Replace the full contents of `app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt`:

```kotlin
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
```

- [ ] **Step 7: Rewrite `FormatDetector.kt`**

Replace the full contents of `app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt`:

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

- [ ] **Step 8: Run the full test suite to verify everything passes together**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest`

(The `JAVA_HOME` override is required in this environment — the default `java` on `PATH` is a JDK 25 build that Gradle's embedded Kotlin compiler cannot parse.)

Expected: `BUILD SUCCESSFUL`. This is the first point at which the module compiles again since Step 1 — `FilenameFormatDetectorTest` (13 tests) and `FormatDetectorTest` (9 tests) both go green here, along with every pre-existing test elsewhere in the module. If anything fails, re-check the four files this task rewrote against the exact code blocks above before touching anything else — a mismatch between one file's new type and another's usage of it is the most likely cause.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt \
        app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt \
        app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt \
        app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt \
        app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml
git commit -m "FormatDetector orchestrates filename, container, and aspect-ratio detection"
```

---

### Task 3: Full regression pass and AGENTS.md record

**Files:**
- Modify: `AGENTS.md` (append a dated entry, do not rewrite existing content)

**Interfaces:** None — this task only verifies and documents.

- [ ] **Step 1: Full rebuild and full unit test run**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, all unit tests green — this repeats Task 2 Step 8's check after `assembleDebug` to also confirm the release/debug packaging path (resource merging in particular, since a string resource was added) isn't broken.

- [ ] **Step 2: Manual sanity check of the label change**

Run: `grep -n "format_source_aspect_ratio" app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/java/tech/illusion/spaceplayer/ui/Labels.kt`
Expected: three matches, one per file, confirming the string resource and the `when` branch both landed.

- [ ] **Step 3: Record the change in `AGENTS.md`**

Append a new dated section to the end of `AGENTS.md` (do not touch or reorder existing content) summarizing: what was added (aspect-ratio detection as a third format-detection layer), the exact priority rule (filename wins per-field, aspect ratio only fills gaps), the corrected threshold (1.3 lower bound, not 1.0, and why), and that this was verified entirely by JVM unit tests with no emulator/device step needed since the feature is pure width/height arithmetic with no UI beyond the one new label string.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "Record aspect-ratio format detection in AGENTS.md"
```
