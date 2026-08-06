# SpacePlayer Stage 2: 真实文件库 UI + 格式识别 + 播放历史 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用真实的 `MediaStore`/SAF 本机视频、容器探测→文件名识别→默认兜底→手动覆盖的格式识别流水线、以及播放历史，替换 Stage 1 遗留的纯测试占位主窗口（`PlaceholderMainScreen.kt`），让"开始播放"按钮驱动真实文件而不是硬编码的测试视频。

**Architecture:** 主窗口（`DefaultWindowContainer`）拆成"数据层"（`library/` 包：`VideoItem` 数据模型、格式识别流水线、`MediaStore`/SAF 仓库、本地小型键值持久化）+ "UI 层"（`ui/library/` 包：左侧分类栏 + 右侧缩略图列表 + 底部环境选择器/开始播放按钮，全部 SpatialUI 组件）。沉浸播放侧（`Stage("ImmersiveStage")`、`PlaybackViewModel`、`PlaybackManager`、ECS 组装）继续沿用 Stage 1 的架构，只是入口从"固定 asset 路径的测试方法"改造成"真实 `VideoItem`/`Uri`"。

**Tech Stack:** Kotlin + Jetpack Compose（通过 PICO Spatial SDK 重新发布的 `androidx.compose.foundation`/`ui` fork，见下方"关键探索结论"）、SpatialUI (`com.pico.spatial.ui.design`)、Android `MediaStore`/`ContentResolver`/`MediaExtractor`（标准平台 API，非 SDK 专有）、`SharedPreferences` + `org.json`（无新增第三方依赖）、Koin（沿用 Stage 1 的 session scope，仅 `PlaybackViewModel` 需要跨容器共享，新增的 `LibraryViewModel` 只在主窗口内用 `remember` 持有，不入 DI）。

## Global Constraints

- 所有 2D UI 必须用 SpatialUI (`com.pico.spatial.ui.*`) 组件并包在 `PicoTheme` 里；**禁止** `androidx.compose.material`/`material3`（沿用项目根 AGENTS.md 的硬性规则）。
- `compileSdk`/`minSdk`/`targetSdk` = 35（`app/build.gradle.kts` 已定），因此只需要 `READ_MEDIA_VIDEO` 运行时权限（API 33+ 的 scoped media 权限），不需要处理任何 legacy `READ_EXTERNAL_STORAGE` 分支。
- 不做网络/云盘/URL 直链；不与 FileSendApp 集成；不做内嵌字幕轨道/`.ass`/AI 2D→3D 转换（见设计稿第 5 节非目标，本计划不涉及字幕，字幕是 Stage 3）。
- `Text`/`Button` 等 SpatialUI 组件必须显式设置 `style = PicoTheme.typography.xxx.copy(fontSize = ...)`，否则渲染成实际不可见的默认字号（Stage 1 踩过的坑，见 AGENTS.md）。
- `Entity()` 自带 `TransformComponent`，设置位置用 `entity.components[TransformComponent::class.java]?.apply { setPosition(...) }`，不要 `entity.components.set(TransformComponent())`（同上）。
- `adb shell input tap x y` 对 spatial 容器（`DefaultWindowContainer`/`Stage`）不可靠，验证交互流程要用临时 `LaunchedEffect(Unit) { ... }` 自动触发，验证完必须删除（Stage 1 的既定做法，本计划的每个 UI 验证步骤都沿用）。
- 本机构建前必须 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（JDK 25 与 Gradle 8.13 内嵌 Kotlin DSL 编译器不兼容）。
- 验证一律用模拟器 `emulator-5554`（本机连接的真机是 API 34，装不了 compileSdk 35 的 APK）。

## 关键探索结论（写 Task 前已核实，避免凭空捏造 API）

这些结论来自直接反编译/阅读本机 Gradle 缓存里 `spatialBom 0.13.3` 实际使用的 sources jar（`~/.gradle/caches/modules-2/files-2.1/com.pico.spatial.*`），不是凭记忆或猜测：

1. **`app/build.gradle.kts` 排除了官方 `androidx.compose.foundation:foundation`/`androidx.compose.ui:ui`/`ui-graphics`/`ui-text`**，但 PICO Spatial SDK 通过 `com.pico.spatial.ui.foundation:foundation-android` 重新发布了**同名包**（`androidx.compose.foundation.*`）的完整分支，其中确认包含 `androidx.compose.foundation.lazy.LazyColumn`/`LazyRow`（`LazyDsl.kt`）和 `androidx.compose.foundation.lazy.grid.LazyVerticalGrid`/`LazyHorizontalGrid`（`LazyGridDsl.kt`），签名与官方 Compose Foundation 一致。**本计划可以放心用标准 `LazyColumn` 渲染视频列表**，不需要手写滚动容器。
2. **`com.pico.spatial.ui:design` 里已有的可复用组件**（均已读取真实源码确认签名）：
   - `SideNavigation { SideNavigationItem(selected, leading, trailing, content) }`（`SideNavigation.kt`）— 左侧分类栏。
   - `LazyColumn` 内用 `ListItem(headlineContent, supportingContent, leadingContent, trailingContent, colors, padding, shape)`（`ListItem.kt`）— 视频行卡片；**没有内建 `selected` 参数**，选中高亮通过 `colors = ListItemDefaults.listItemColors(backgroundColor = ...)` 手动传入实现。
   - `Badge { Text(...) }`（`Badge.kt`）— 格式徽标。
   - `SegmentControl { SegmentItem(selected, onClick, title) }`（`SegmentControls.kt`）— 顶部格式筛选、修正弹层里的投影/立体选择。
   - `SpatialPopup(onDismissRequest, content)`（`windows/SpatialPopup.kt`）— "轻量弹层"，锚定在声明它的 Box 位置，`dismissOnClickOutside = true`，正是设计稿"修正格式"弹层要的形态（不是全屏 `AlertDialog`）。
   - `Modifier.windowConstraints(minWidth, minHeight, ...)`（`com.pico.spatial.ui.platform.resize.WindowConstraintsModifier.kt`）— 是 `WindowContainerScope`（`DefaultWindowContainer` 内容 lambda 的 receiver）上的扩展函数，只能在 `DefaultWindowContainer { ... }` 块内、或把已应用过的 `Modifier` 作为普通参数传给下层 Composable 来用。
3. **`CypressMediaPlayer` 没有 `Uri` 重载**（`core-0.13.3-sources.jar` 里 `CypressMediaPlayer.kt` 确认只有 `setDataSource(path: String)` / `setDataSource(fd: Int, offset: Long, length: Long)` / `setDataSource(afd: AssetFileDescriptor)` 三个重载）。真实 `content://` Uri（MediaStore 或 SAF）要靠 `context.contentResolver.openFileDescriptor(uri, "r")` 包成 `AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)` 再调用第三个重载——这正是 Stage 1 `PlaybackManager.setup(assetPath: String)` 已经在用的同一条代码路径，只是数据源从 `context.assets.openFd(...)` 换成 `contentResolver.openFileDescriptor(...)`。
4. **`SpatialStubActivity`（`LaunchActivity` 的基类）继承 `androidx.fragment.app.FragmentActivity`**（继承自 `ComponentActivity`），且 `androidx.activity:activity-compose` 已经是 Spatial SDK 的传递依赖（本机 Gradle 缓存里能找到）。这意味着 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`（运行时权限）和 `ActivityResultContracts.OpenDocument()`（SAF"其它"选择器）可以像普通 Android Compose 应用一样直接在 Composable 里用，不需要改造 `LaunchActivity.kt`。本计划仍会在 `app/build.gradle.kts` 里显式声明 `androidx-activity-compose` 依赖（不写死具体版本号，让 Gradle 用它自己的冲突消解规则对齐到 Spatial SDK 实际需要的版本），避免依赖一个从未显式声明过的传递依赖。
5. **MV-HEVC（Apple 空间视频）多视图轨道在标准 Android `MediaExtractor`/`MediaFormat` 里没有可验证的公开探测字段**——ISO/IEC 23008-2 Annex G 的 multiview 分组信息不是 AOSP `MediaFormat` 的文档化 key。本计划的容器探测（Task 2）只能做"同分辨率 HEVC 视频轨道数 ≥ 2"这种启发式代理判断，**且本机没有真实 MV-HEVC 样本文件验证准确性**——这一点会在代码注释、AGENTS.md、Task 2 的验证步骤里都如实说明为"未验证的启发式"，不会假装它是精确识别。文件名关键词识别（`_mvhevc`）和手动覆盖仍是 V1 实际可靠的兜底路径。

## File Structure

```
app/src/main/java/tech/illusion/spaceplayer/
  library/                              # 数据层，新增
    VideoItem.kt                        # VideoItem 数据类 + FormatSource 枚举
    PlaybackHistoryEntry.kt             # PlaybackHistoryEntry 数据类
    FilenameFormatDetector.kt           # 纯函数：文件名关键词 → DetectedFormat?
    MultiviewTrackProbe.kt              # 接口 + MediaExtractor 启发式实现
    FormatDetector.kt                   # 容器探测 → 文件名 → 默认兜底 三级流水线
    VideoLibraryRepository.kt           # RawVideoRecord + MediaStore 查询（视频资源库/下载）
    VideoPreferencesStore.kt            # 手动覆盖 + 每视频"上次环境"持久化
    PlaybackHistoryStore.kt             # 播放历史持久化（去重取最新，按时间倒序）
    storage/
      KeyValueStore.kt                  # 接口 + SharedPreferencesKeyValueStore 实现
  playback/
    PlaybackManager.kt                  # 修改：setup(assetPath: String) → setup(uri: Uri)
  ui/
    PlaybackViewModel.kt                # 修改：三个测试方法合并成 startPlayback(item: VideoItem)
    library/                            # UI 层，新增
      LibraryCategory.kt                # 视频资源库/下载/历史/其它 枚举
      LibraryViewModel.kt               # 主窗口浏览状态（分类/筛选/选中项/列表），plain class + remember
      MainLibraryScreen.kt              # 替换 PlaceholderMainScreen.kt：权限门 + 侧栏 + 列表 + 顶栏筛选
      VideoListCard.kt                  # 单个视频行：缩略图（懒加载）+ 文件名 + 时长 + 格式徽标 + 修正入口
      FormatCorrectionPopup.kt          # SpatialPopup：投影/立体格式手动覆盖
      LibraryBottomBar.kt               # 环境选择器 + 开始播放按钮
  Main.kt                               # 修改：接入 MainLibraryScreen + windowConstraints
  di/PlaybackModule.kt                  # 修改：PlaybackViewModel 构造参数增加两个 Store
app/src/main/AndroidManifest.xml        # 修改：新增 READ_MEDIA_VIDEO 权限
app/src/test/java/tech/illusion/spaceplayer/library/
  FilenameFormatDetectorTest.kt
  FormatDetectorTest.kt
  VideoPreferencesStoreTest.kt
  PlaybackHistoryStoreTest.kt
  fakes/InMemoryKeyValueStore.kt        # 测试专用 KeyValueStore 假实现
  fakes/FakeMultiviewTrackProbe.kt      # 测试专用 MultiviewTrackProbe 假实现
```

删除：`app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`（Task 7 里被 `MainLibraryScreen.kt` 取代）。

---

### Task 1: 视频数据模型 + 文件名格式识别

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/PlaybackHistoryEntry.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt`

**Interfaces:**
- Consumes: `tech.illusion.spaceplayer.playback.Projection`、`tech.illusion.spaceplayer.playback.StereoMode`、`tech.illusion.spaceplayer.playback.Environment`（Stage 1 已有，路径 `playback/Projection.kt`/`StereoMode.kt`/`Environment.kt`）。
- Produces: `VideoItem` 数据类、`FormatSource` 枚举、`DetectedFormat` 数据类、`FilenameFormatDetector.detect(displayName: String): DetectedFormat?` —— Task 2/4/5 都依赖这些类型。

- [ ] **Step 1: 写 `VideoItem.kt`（含 `FormatSource`、`DetectedFormat`）**

```kotlin
package tech.illusion.spaceplayer.library

import android.net.Uri
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, MANUAL_OVERRIDE, DEFAULT }

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    // MediaStore 传统缩略图表（MediaStore.Video.Thumbnails）的 Uri 概念在这里故意不用——
    // Task 5 的卡片改用 ContentResolver.loadThumbnail(uri, size, null)（API 29+）直接从视频本身
    // 懒加载缩略图，不需要单独的缩略图 Uri。这个字段保留是为了不偏离设计稿的数据模型，恒为 null。
    val thumbnailUri: Uri?,
    val projection: Projection,
    val stereoMode: StereoMode,
    val formatSource: FormatSource,
    val preferredEnvironment: Environment?,
)

/** 格式识别流水线（Task 2 的 [FormatDetector]）某一级命中后的结果。 */
data class DetectedFormat(
    val projection: Projection,
    val stereoMode: StereoMode,
    val formatSource: FormatSource,
)
```

- [ ] **Step 2: 写 `PlaybackHistoryEntry.kt`**

```kotlin
package tech.illusion.spaceplayer.library

import android.net.Uri

data class PlaybackHistoryEntry(val videoUri: Uri, val lastPlayedAt: Long)
```

- [ ] **Step 3: 写失败的文件名识别测试**

```kotlin
package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FilenameFormatDetectorTest {

    @Test
    fun `no keyword returns null`() {
        assertNull(FilenameFormatDetector.detect("my_trip.mp4"))
    }

    @Test
    fun `_180_ keyword detects hemisphere projection with mono stereo default`() {
        val result = FilenameFormatDetector.detect("hawaii_180_beach.mp4")
        assertEquals(Projection.HEMISPHERE_180, result?.projection)
        assertEquals(StereoMode.MONO, result?.stereoMode)
        assertEquals(FormatSource.DETECTED_FILENAME, result?.formatSource)
    }

    @Test
    fun `_180x180 keyword detects hemisphere projection`() {
        assertEquals(Projection.HEMISPHERE_180, FilenameFormatDetector.detect("clip_180x180.mp4")?.projection)
    }

    @Test
    fun `_360_ keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("concert_360_live.mp4")?.projection)
    }

    @Test
    fun `_equirect keyword detects sphere projection`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("scene_equirect.mp4")?.projection)
    }

    @Test
    fun `_sbs keyword detects side-by-side stereo with flat projection default`() {
        val result = FilenameFormatDetector.detect("movie_sbs.mp4")
        assertEquals(Projection.FLAT, result?.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result?.stereoMode)
    }

    @Test
    fun `_3dh keyword detects side-by-side stereo`() {
        assertEquals(StereoMode.SIDE_BY_SIDE, FilenameFormatDetector.detect("show_3dh.mp4")?.stereoMode)
    }

    @Test
    fun `_tb keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_tb.mp4")?.stereoMode)
    }

    @Test
    fun `_ou keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_ou.mp4")?.stereoMode)
    }

    @Test
    fun `_3dv keyword detects top-and-down stereo`() {
        assertEquals(StereoMode.TOP_AND_DOWN, FilenameFormatDetector.detect("clip_3dv.mp4")?.stereoMode)
    }

    @Test
    fun `_mvhevc keyword detects multiview stereo`() {
        assertEquals(StereoMode.MULTIVIEW_MVHEVC, FilenameFormatDetector.detect("spatial_mvhevc.mp4")?.stereoMode)
    }

    @Test
    fun `combined projection and stereo keywords both detected`() {
        val result = FilenameFormatDetector.detect("trip_360_sbs.mp4")
        assertEquals(Projection.SPHERE_360, result?.projection)
        assertEquals(StereoMode.SIDE_BY_SIDE, result?.stereoMode)
    }

    @Test
    fun `keyword matching is case-insensitive`() {
        assertEquals(Projection.SPHERE_360, FilenameFormatDetector.detect("Trip_360_Live.MP4")?.projection)
    }
}
```

- [ ] **Step 4: 跑测试确认失败（类还不存在）**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.FilenameFormatDetectorTest"`
Expected: 编译失败，`FilenameFormatDetector` unresolved reference。

- [ ] **Step 5: 写 `FilenameFormatDetector.kt` 让测试通过**

```kotlin
package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/**
 * 纯文件名关键词识别，命中优先级见设计稿第 2 节。同一文件名可以同时命中投影关键词和立体格式关键词
 * （如 "trip_360_sbs.mp4"），两者互相独立判断。
 */
object FilenameFormatDetector {
    private val HEMISPHERE_180_KEYWORDS = listOf("_180_", "_180x180")
    private val SPHERE_360_KEYWORDS = listOf("_360_", "_equirect")
    private val SIDE_BY_SIDE_KEYWORDS = listOf("_sbs", "_3dh")
    private val TOP_AND_DOWN_KEYWORDS = listOf("_tb", "_ou", "_3dv")
    private val MULTIVIEW_KEYWORDS = listOf("_mvhevc")

    fun detect(displayName: String): DetectedFormat? {
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
        if (projection == null && stereoMode == null) return null
        return DetectedFormat(
            projection = projection ?: Projection.FLAT,
            stereoMode = stereoMode ?: StereoMode.MONO,
            formatSource = FormatSource.DETECTED_FILENAME,
        )
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.FilenameFormatDetectorTest"`
Expected: BUILD SUCCESSFUL，14 个测试全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/PlaybackHistoryEntry.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/FilenameFormatDetector.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/FilenameFormatDetectorTest.kt
git commit -m "Stage 2 Task 1: video catalog data model + filename format detector"
```

---

### Task 2: 容器探测（MV-HEVC 启发式）+ 三级识别流水线

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt`

**Interfaces:**
- Consumes: `DetectedFormat`/`FormatSource`（Task 1，`library/VideoItem.kt`）、`FilenameFormatDetector.detect`（Task 1）。
- Produces: `MultiviewTrackProbe` 接口 + `MediaExtractorMultiviewProbe` 实现、`FormatDetector` 类，`fun detect(context: Context, uri: Uri, displayName: String): DetectedFormat` —— Task 4/5 的 `VideoLibraryRepository`/`LibraryViewModel` 依赖这个签名。

- [ ] **Step 1: 写测试用的假探测器**

```kotlin
package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.MultiviewTrackProbe

class FakeMultiviewTrackProbe(private val result: Boolean) : MultiviewTrackProbe {
    override fun looksLikeMultiview(context: Context, uri: Uri): Boolean = result
}
```

- [ ] **Step 2: 写失败的流水线测试**

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
```

这个测试需要 `mockito-core`（mock `Context`/`Uri` 两个 Android 类，纯 JVM 单测里没有真实实现）。检查 `gradle/libs.versions.toml` 目前没有 mockito——补上：

```toml
# gradle/libs.versions.toml 的 [versions] 段追加
mockitoCore = "5.14.2"

# [libraries] 段追加
mockito-core = { group = "org.mockito", name = "mockito-core", version.ref = "mockitoCore" }
```

```kotlin
// app/build.gradle.kts 的 dependencies 段追加
testImplementation(libs.mockito.core)
```

- [ ] **Step 3: 跑测试确认失败**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.FormatDetectorTest"`
Expected: 编译失败，`FormatDetector`/`MultiviewTrackProbe` unresolved reference。

- [ ] **Step 4: 写 `MultiviewTrackProbe.kt`**

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

interface MultiviewTrackProbe {
    fun looksLikeMultiview(context: Context, uri: Uri): Boolean
}

private const val TAG = "MediaExtractorMultiviewProbe"

/**
 * 启发式代理判断，不是精确的 MV-HEVC 识别：标准 Android `MediaExtractor`/`MediaFormat` 没有
 * 文档化的 ISO/IEC 23008-2 Annex G 多视图分组信息读取接口。这里只是数"同分辨率 HEVC 视频轨道数
 * 是否 ≥ 2"，命中就当作多视图。本机没有真实 Apple 空间视频样本文件验证过这个启发式的准确率——
 * 文件名识别（`_mvhevc`）和用户手动覆盖仍是 V1 实际可靠的兜底路径，见 AGENTS.md Stage 2 记录。
 */
class MediaExtractorMultiviewProbe : MultiviewTrackProbe {
    override fun looksLikeMultiview(context: Context, uri: Uri): Boolean {
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
            hevcVideoTrackCount >= 2 && resolutionsMatch
        } catch (e: Exception) {
            Log.e(TAG, "probe failed for $uri", e)
            false
        } finally {
            extractor.release()
        }
    }
}
```

- [ ] **Step 5: 写 `FormatDetector.kt`**

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

/** 识别流水线：容器探测（仅对 stereo mode 有意义）→ 文件名关键词 → 默认兜底。见设计稿第 2 节。 */
class FormatDetector(private val multiviewTrackProbe: MultiviewTrackProbe) {
    fun detect(context: Context, uri: Uri, displayName: String): DetectedFormat {
        if (multiviewTrackProbe.looksLikeMultiview(context, uri)) {
            val filenameHint = FilenameFormatDetector.detect(displayName)
            return DetectedFormat(
                projection = filenameHint?.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }
        return FilenameFormatDetector.detect(displayName)
            ?: DetectedFormat(Projection.FLAT, StereoMode.MONO, FormatSource.DEFAULT)
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.FormatDetectorTest"`
Expected: BUILD SUCCESSFUL，4 个测试全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/java/tech/illusion/spaceplayer/library/MultiviewTrackProbe.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/FormatDetectorTest.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/fakes/FakeMultiviewTrackProbe.kt
git commit -m "Stage 2 Task 2: MV-HEVC container probe heuristic + format detection pipeline"
```

---

### Task 3: 本地持久化——格式覆盖 + 每视频偏好环境 + 播放历史

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/storage/KeyValueStore.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/VideoPreferencesStore.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/PlaybackHistoryStore.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/fakes/InMemoryKeyValueStore.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/VideoPreferencesStoreTest.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/PlaybackHistoryStoreTest.kt`

**Interfaces:**
- Consumes: `Projection`/`StereoMode`（`playback/`）、`Environment`（`playback/Environment.kt`）、`PlaybackHistoryEntry`（Task 1）。
- Produces: `KeyValueStore` 接口 + `SharedPreferencesKeyValueStore` 实现、`VideoPreferences` 数据类 + `VideoPreferencesStore`（`get(uri: Uri)`/`setFormatOverride(uri, projection, stereoMode)`/`setPreferredEnvironment(uri, environment)`）、`PlaybackHistoryStore`（`recordPlayed(uriKey: String, timestampMs)`/`recentEntriesDescending(): List<Pair<String, Long>>`）——Task 5/7/8 的 `LibraryViewModel`/`PlaybackViewModel` 依赖这些签名。

**实现时的真实发现（两处都不是原计划草稿里的样子，写代码时才发现）：**
1. **`org.json.JSONObject` 在纯 JVM 单元测试里会抛 `RuntimeException`**（Android 单测用的 stub jar 没有真实实现，只有接入 Robolectric 才有）——`VideoPreferencesStore` 没有按原计划用 JSON 序列化，改成手写不碰任何 Android 类的 `key=value;key=value` 格式（纯 Kotlin stdlib）。
2. **同样的问题存在于 `Uri.parse()`（Android 静态方法）**——`PlaybackHistoryStore` 因此把接口从"整个签名都用 `Uri`"改成"内部全程用 `String` key"：`recordPlayed(uriKey: String, ...)`、`recentEntriesDescending(): List<Pair<String, Long>>`（不是 `List<PlaybackHistoryEntry>`）。`uriKey`（调用方传 `uri.toString()`）↔ 真实 `Uri` 的转换留给 Task 8 的 UI 层调用方去做（`Uri.parse(uriKey)`），和 `VideoLibraryRepository`/`PlaybackManager` 触碰 Android 框架但不写单测是同一个约定——这样 `PlaybackHistoryStore` 本身的去重/排序逻辑还是能被单测完整覆盖，不用引入 Robolectric。

- [x] **Step 1: 写 `KeyValueStore.kt`（接口 + 真实实现）**

**实际结果**：拆成两个文件（`KeyValueStore.kt` 接口 + `SharedPreferencesKeyValueStore.kt` 实现），内容和下面草稿一致，未改动。

```kotlin
package tech.illusion.spaceplayer.library.storage

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun all(): Map<String, String>
}
```

```kotlin
package tech.illusion.spaceplayer.library.storage

import android.content.Context

class SharedPreferencesKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}
```

- [x] **Step 2: 写测试用的内存假实现**

```kotlin
package tech.illusion.spaceplayer.library.fakes

import tech.illusion.spaceplayer.library.storage.KeyValueStore

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun all(): Map<String, String> = map.toMap()
}
```

- [x] **Step 3: 写失败的 `VideoPreferencesStore` 测试**

```kotlin
package tech.illusion.spaceplayer.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tech.illusion.spaceplayer.library.fakes.InMemoryKeyValueStore
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class VideoPreferencesStoreTest {

    private fun fakeUri(value: String): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(value)
        return uri
    }

    @Test
    fun `unknown uri returns all-null defaults`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val prefs = store.get(fakeUri("content://media/1"))
        assertNull(prefs.projectionOverride)
        assertNull(prefs.stereoModeOverride)
        assertNull(prefs.preferredEnvironment)
    }

    @Test
    fun `format override round-trips`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/2")
        store.setFormatOverride(uri, Projection.SPHERE_360, StereoMode.SIDE_BY_SIDE)
        val prefs = store.get(uri)
        assertEquals(Projection.SPHERE_360, prefs.projectionOverride)
        assertEquals(StereoMode.SIDE_BY_SIDE, prefs.stereoModeOverride)
    }

    @Test
    fun `setting preferred environment preserves prior format override`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/3")
        store.setFormatOverride(uri, Projection.HEMISPHERE_180, StereoMode.TOP_AND_DOWN)
        store.setPreferredEnvironment(uri, Environment.SEASIDE)
        val prefs = store.get(uri)
        assertEquals(Projection.HEMISPHERE_180, prefs.projectionOverride)
        assertEquals(Environment.SEASIDE, prefs.preferredEnvironment)
    }
}
```

- [x] **Step 4: 跑测试确认失败**

**实际结果**：确认 `Unresolved reference 'VideoPreferencesStore'` 编译失败，符合预期。

- [x] **Step 5: 写 `VideoPreferencesStore.kt`**

**实际结果（不是上面草稿的样子）**：`org.json.JSONObject` 在纯 JVM 单测里跑不通（见本 Task 顶部"实现时的真实发现"），实际实现改成手写 `key=value;key=value` 格式：

```kotlin
package tech.illusion.spaceplayer.library

import android.net.Uri
import tech.illusion.spaceplayer.library.storage.KeyValueStore
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

data class VideoPreferences(
    val projectionOverride: Projection? = null,
    val stereoModeOverride: StereoMode? = null,
    val preferredEnvironment: Environment? = null,
)

class VideoPreferencesStore(private val storage: KeyValueStore) {

    fun get(uri: Uri): VideoPreferences {
        val raw = storage.get(uri.toString()) ?: return VideoPreferences()
        val fields = raw.split(FIELD_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(ENTRY_SEPARATOR, limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        return VideoPreferences(
            projectionOverride = fields[KEY_PROJECTION]?.let(Projection::valueOf),
            stereoModeOverride = fields[KEY_STEREO]?.let(StereoMode::valueOf),
            preferredEnvironment = fields[KEY_ENVIRONMENT]?.let(Environment::valueOf),
        )
    }

    fun setFormatOverride(uri: Uri, projection: Projection, stereoMode: StereoMode) {
        save(uri, get(uri).copy(projectionOverride = projection, stereoModeOverride = stereoMode))
    }

    fun setPreferredEnvironment(uri: Uri, environment: Environment) {
        save(uri, get(uri).copy(preferredEnvironment = environment))
    }

    private fun save(uri: Uri, prefs: VideoPreferences) {
        val fields = buildList {
            prefs.projectionOverride?.let { add("$KEY_PROJECTION$ENTRY_SEPARATOR${it.name}") }
            prefs.stereoModeOverride?.let { add("$KEY_STEREO$ENTRY_SEPARATOR${it.name}") }
            prefs.preferredEnvironment?.let { add("$KEY_ENVIRONMENT$ENTRY_SEPARATOR${it.name}") }
        }
        storage.put(uri.toString(), fields.joinToString(FIELD_SEPARATOR))
    }

    private companion object {
        const val FIELD_SEPARATOR = ";"
        const val ENTRY_SEPARATOR = "="
        const val KEY_PROJECTION = "projection"
        const val KEY_STEREO = "stereo"
        const val KEY_ENVIRONMENT = "environment"
    }
}
```

- [x] **Step 6: 跑测试确认通过**

**实际结果**：BUILD SUCCESSFUL，3 个测试全部 PASS。

- [x] **Step 7: 写失败的 `PlaybackHistoryStore` 测试**

**实际结果（签名和上面草稿不一样）**：`PlaybackHistoryStore` 改成全程 `String` key（见本 Task 顶部"实现时的真实发现"第 2 条），测试也不再需要 mock `Uri`：

```kotlin
package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.illusion.spaceplayer.library.fakes.InMemoryKeyValueStore

class PlaybackHistoryStoreTest {

    @Test
    fun `entries sorted by most recently played first`() {
        val store = PlaybackHistoryStore(InMemoryKeyValueStore())
        store.recordPlayed("content://media/1", timestampMs = 1000L)
        store.recordPlayed("content://media/2", timestampMs = 2000L)
        val entries = store.recentEntriesDescending()
        assertEquals(2, entries.size)
        assertEquals(2000L, entries[0].second)
        assertEquals(1000L, entries[1].second)
    }

    @Test
    fun `replaying the same uri dedups to the latest timestamp`() {
        val store = PlaybackHistoryStore(InMemoryKeyValueStore())
        store.recordPlayed("content://media/1", timestampMs = 1000L)
        store.recordPlayed("content://media/1", timestampMs = 5000L)
        val entries = store.recentEntriesDescending()
        assertEquals(1, entries.size)
        assertEquals(5000L, entries[0].second)
    }
}
```

- [x] **Step 8: 跑测试确认失败**

**实际结果**：确认 `Unresolved reference 'PlaybackHistoryStore'` 编译失败，符合预期。

- [x] **Step 9: 写 `PlaybackHistoryStore.kt`**

**实际结果（不是上面草稿的样子）**：

```kotlin
package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.library.storage.KeyValueStore

class PlaybackHistoryStore(private val storage: KeyValueStore) {

    fun recordPlayed(uriKey: String, timestampMs: Long) {
        storage.put(uriKey, timestampMs.toString())
    }

    fun recentEntriesDescending(): List<Pair<String, Long>> =
        storage.all()
            .mapNotNull { (uriKey, value) -> value.toLongOrNull()?.let { uriKey to it } }
            .sortedByDescending { it.second }
}
```

- [x] **Step 10: 跑测试确认通过**

**实际结果**：BUILD SUCCESSFUL，2 个测试全部 PASS；随后跑了一次 `:app:testDebugUnitTest`（不带 `--tests` 过滤）确认 Task 1/2/3 全部单测一起跑也没有互相影响。

- [x] **Step 11: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/storage/KeyValueStore.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/VideoPreferencesStore.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/PlaybackHistoryStore.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/fakes/InMemoryKeyValueStore.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/VideoPreferencesStoreTest.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/PlaybackHistoryStoreTest.kt
git commit -m "Stage 2 Task 3: local persistence for format overrides and playback history"
```

---

### Task 4: MediaStore 仓库 + READ_MEDIA_VIDEO 权限

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/library/VideoLibraryRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: 无新依赖，纯 Android `ContentResolver`/`MediaStore` API。
- Produces: `RawVideoRecord` 数据类（`uri`/`displayName`/`durationMs`/`sizeBytes`）、`VideoLibraryRepository`（`queryLibrary(): List<RawVideoRecord>`/`queryDownloads(): List<RawVideoRecord>`）——Task 5 的 `LibraryViewModel` 依赖这个签名。这个 Task 直接触碰 `ContentResolver`，不写单元测试（和 Stage 1 的 `PlaybackManager` 一样，只做构建 + 模拟器手动验证），验证步骤见 Step 3。

- [ ] **Step 1: 在 `AndroidManifest.xml` 里加权限声明**

```xml
<!-- app/src/main/AndroidManifest.xml，加在 <manifest> 标签内、<application> 标签之前 -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

- [ ] **Step 2: 写 `VideoLibraryRepository.kt`**

```kotlin
package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class RawVideoRecord(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * "视频资源库"= MediaStore 扫描结果里排除 Download 目录的部分；"下载"= 只看 Download 目录。
 * 见设计稿第 2 节"文件库管理"。
 */
class VideoLibraryRepository(private val context: Context) {

    fun queryLibrary(): List<RawVideoRecord> = query(downloadsOnly = false)

    fun queryDownloads(): List<RawVideoRecord> = query(downloadsOnly = true)

    private fun query(downloadsOnly: Boolean): List<RawVideoRecord> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val records = mutableListOf<RawVideoRecord>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathColumn) ?: ""
                val isInDownloads = relativePath.startsWith("Download/")
                if (isInDownloads != downloadsOnly) continue
                val id = cursor.getLong(idColumn)
                records += RawVideoRecord(
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    displayName = cursor.getString(nameColumn) ?: "",
                    durationMs = cursor.getLong(durationColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                )
            }
        }
        return records
    }
}
```

- [ ] **Step 3: 构建确认编译通过（真正的功能验证放在 Task 5，因为查询结果要靠 UI 才能看到）**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/library/VideoLibraryRepository.kt app/src/main/AndroidManifest.xml
git commit -m "Stage 2 Task 4: MediaStore video repository + READ_MEDIA_VIDEO permission"
```

---

### Task 5: 主窗口骨架——侧栏分类 + 视频列表 + 权限门

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryCategory.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/VideoListCard.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`
- Modify: `app/build.gradle.kts`、`gradle/libs.versions.toml`（加 `androidx-activity-compose`）

**Interfaces:**
- Consumes: `VideoLibraryRepository`（Task 4）、`FormatDetector`/`MediaExtractorMultiviewProbe`（Task 2）、`VideoPreferencesStore`（Task 3）、`VideoItem`/`FormatSource`（Task 1）。
- Produces: `LibraryCategory` 枚举、`LibraryViewModel`（`selectedCategory`/`selectedItem`/`libraryItems`/`downloadsItems`/`selectCategory`/`selectItem`/`refreshLibrary`/`refreshDownloads`/`visibleItems`）、`MainLibraryScreen(modifier: Modifier)` Composable——Task 6/7 都会修改 `LibraryViewModel`/`MainLibraryScreen`。这个 Task 先不接"其它"分类和播放按钮（留给 Task 7），"历史"分类先显示空列表（留给 Task 8）。

- [ ] **Step 1: 加 `androidx-activity-compose` 依赖声明**

```toml
# gradle/libs.versions.toml 的 [libraries] 段追加（不设 version.ref，让 Gradle 冲突消解对齐
# Spatial SDK 传递依赖已经解析出的版本——本机缓存里能看到 1.9.3/1.10.1 两个版本共存，说明这本来就是
# 由多个上游一起决定的，不需要在这里锁死）
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
```

```kotlin
// app/build.gradle.kts 的 dependencies 段追加
implementation(libs.androidx.activity.compose)
```

- [ ] **Step 2: 写 `LibraryCategory.kt`**

```kotlin
package tech.illusion.spaceplayer.ui.library

enum class LibraryCategory(val label: String) {
    LIBRARY("视频资源库"),
    DOWNLOADS("下载"),
    HISTORY("历史"),
    IMPORT("其它"),
}
```

- [ ] **Step 3: 写 `LibraryViewModel.kt`**

```kotlin
package tech.illusion.spaceplayer.ui.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.illusion.spaceplayer.library.FormatDetector
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.MediaExtractorMultiviewProbe
import tech.illusion.spaceplayer.library.RawVideoRecord
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.library.VideoLibraryRepository
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.library.storage.SharedPreferencesKeyValueStore
import tech.illusion.spaceplayer.playback.Projection

/** 只在主窗口内用，不需要跨容器共享，所以不入 Koin，直接在 [MainLibraryScreen] 里 `remember`。 */
class LibraryViewModel(private val context: Context) {
    private val repository = VideoLibraryRepository(context)
    private val formatDetector = FormatDetector(MediaExtractorMultiviewProbe())
    val preferencesStore =
        VideoPreferencesStore(SharedPreferencesKeyValueStore(context, "video_preferences"))

    var selectedCategory by mutableStateOf(LibraryCategory.LIBRARY)
        private set
    var formatFilter by mutableStateOf<Projection?>(null)
        private set
    var selectedItem by mutableStateOf<VideoItem?>(null)
        private set
    var libraryItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var downloadsItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    fun selectCategory(category: LibraryCategory) {
        selectedCategory = category
        selectedItem = null
    }

    fun selectFormatFilter(projection: Projection?) {
        formatFilter = projection
    }

    fun selectItem(item: VideoItem) {
        selectedItem = item
    }

    fun refreshLibrary() {
        libraryItems = repository.queryLibrary().map(::toVideoItem)
    }

    fun refreshDownloads() {
        downloadsItems = repository.queryDownloads().map(::toVideoItem)
    }

    /** [historyItems] 由调用方（Task 8 接入历史后）传入，Task 5/6/7 阶段恒为空列表。 */
    fun visibleItems(historyItems: List<VideoItem>): List<VideoItem> {
        val source = when (selectedCategory) {
            LibraryCategory.LIBRARY -> libraryItems
            LibraryCategory.DOWNLOADS -> downloadsItems
            LibraryCategory.HISTORY -> historyItems
            LibraryCategory.IMPORT -> emptyList()
        }
        return formatFilter?.let { filter -> source.filter { it.projection == filter } } ?: source
    }

    fun toVideoItem(record: RawVideoRecord): VideoItem {
        val uriPrefs = preferencesStore.get(record.uri)
        val detected = formatDetector.detect(context, record.uri, record.displayName)
        return VideoItem(
            uri = record.uri,
            displayName = record.displayName,
            durationMs = record.durationMs,
            sizeBytes = record.sizeBytes,
            thumbnailUri = null,
            projection = uriPrefs.projectionOverride ?: detected.projection,
            stereoMode = uriPrefs.stereoModeOverride ?: detected.stereoMode,
            formatSource = if (uriPrefs.projectionOverride != null) {
                FormatSource.MANUAL_OVERRIDE
            } else {
                detected.formatSource
            },
            preferredEnvironment = uriPrefs.preferredEnvironment,
        )
    }
}
```

- [ ] **Step 4: 写 `VideoListCard.kt`（缩略图懒加载 + 文件名 + 时长 + 格式徽标）**

```kotlin
package tech.illusion.spaceplayer.ui.library

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

private const val THUMBNAIL_SIZE_PX = 160

private fun Projection.label(): String = when (this) {
    Projection.FLAT -> "平面"
    Projection.HEMISPHERE_180 -> "180°"
    Projection.SPHERE_360 -> "360°"
}

private fun StereoMode.label(): String? = when (this) {
    StereoMode.MONO -> null
    StereoMode.SIDE_BY_SIDE -> "SBS"
    StereoMode.TOP_AND_DOWN -> "TB"
    StereoMode.MULTIVIEW_MVHEVC -> "MV-HEVC"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun VideoListCard(
    item: VideoItem,
    selected: Boolean,
    onClick: () -> Unit,
    onRequestFormatCorrection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var thumbnail by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    item.uri,
                    Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX),
                    null,
                )
            }.getOrNull()
        }
    }

    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.listItemColors(
            backgroundColor = if (selected) {
                PicoTheme.colorScheme.fillSecondary
            } else {
                PicoTheme.colorScheme.fillLight
            },
        ),
        leadingContent = {
            Box(modifier = Modifier.size(56.dp)) {
                thumbnail?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = item.displayName)
                }
            }
        },
        headlineContent = {
            Text(
                text = item.displayName,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        },
        supportingContent = {
            Text(
                text = formatDuration(item.durationMs),
                color = PicoTheme.colorScheme.labelTertiary,
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            )
        },
        trailingContent = {
            Box {
                Badge {
                    val stereoLabel = item.stereoMode.label()
                    Text(
                        text = if (stereoLabel != null) {
                            "${item.projection.label()} $stereoLabel"
                        } else {
                            item.projection.label()
                        },
                        style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
            }
        },
    )

    // "修正格式"入口只在纯兜底猜测时出现，见设计稿第 2 节。用独立可点击 Text 而不是叠加在 ListItem
    // 的 trailingContent 上，避免和上面的点击选中手势冲突。
    if (item.formatSource == tech.illusion.spaceplayer.library.FormatSource.DEFAULT) {
        Text(
            text = "修正格式",
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.clickableText(onRequestFormatCorrection),
        )
    }
}
```

等等——`Modifier.clickableText(...)` 不是真实 API，是我编造的占位。改成标准 `Modifier.clickable(onClick = ...)`（来自 `androidx.compose.foundation`，已确认是 PICO 重新发布的同名分支的一部分，Task 5 之前所有 `Button`/`ListItem` 都间接用到过点击手势，这里直接用最基础的 `clickable` 修饰符）：

```kotlin
// 把上面 VideoListCard.kt 末尾的 import 段追加：
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding

// 把上面那段 "修正格式" Text 改成：
    if (item.formatSource == tech.illusion.spaceplayer.library.FormatSource.DEFAULT) {
        Text(
            text = "修正格式",
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).clickable(onClick = onRequestFormatCorrection),
        )
    }
```

- [ ] **Step 5: 写 `MainLibraryScreen.kt`（权限门 + 侧栏 + 列表，先不含底部操作栏/修正弹层，留给 Task 6/7）**

```kotlin
package tech.illusion.spaceplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.Text

@Composable
fun MainLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val libraryViewModel = remember { LibraryViewModel(context) }

    var hasVideoPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasVideoPermission = granted }

    LaunchedEffect(hasVideoPermission) {
        if (hasVideoPermission) {
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
        }
    }

    PicoTheme {
        if (!hasVideoPermission) {
            Column(modifier = modifier.fillMaxSize().padding(32.dp)) {
                Text(
                    text = "SpacePlayer 需要访问本机视频的权限才能显示视频库",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                )
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) }) {
                    Text(
                        text = "去授权",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    )
                }
            }
            return@PicoTheme
        }

        Row(modifier = modifier.fillMaxSize()) {
            SideNavigation {
                LibraryCategory.entries.forEach { category ->
                    SideNavigationItem(
                        selected = libraryViewModel.selectedCategory == category,
                        onClick = { libraryViewModel.selectCategory(category) },
                        content = {
                            Text(
                                text = category.label,
                                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            )
                        },
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = libraryViewModel.selectedCategory.label,
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                )
                val items = libraryViewModel.visibleItems(historyItems = emptyList())
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(items = items, key = { it.uri }) { item ->
                        VideoListCard(
                            item = item,
                            selected = libraryViewModel.selectedItem?.uri == item.uri,
                            onClick = { libraryViewModel.selectItem(item) },
                            onRequestFormatCorrection = {},
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
```

注意：`SideNavigationItem` 真实签名的 `content`/`leading`/`trailing` 参数类型是 `@Composable (BoxScope.() -> Unit)`（见"关键探索结论"第 2 条），上面代码里 `content = { Text(...) }` 能编译是因为 Kotlin 允许尾随 lambda 省略 `BoxScope` 接收者的显式使用——`Text` 不需要用到 `this: BoxScope`。这一步先不接"其它"分类的点击行为（触发 SAF）和底部操作栏，`onRequestFormatCorrection` 先传空 lambda，Task 6/7 补上。

- [ ] **Step 6: 构建 + 安装 + 启动 + 截图验证（真机视频库，需要先往模拟器塞几个视频文件）**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
# 往模拟器的 Movies 和 Download 目录各塞一个测试视频，触发 MediaStore 扫描
adb -s emulator-5554 push app/src/main/assets/videos/sample_flat_test.mp4 /sdcard/Movies/library_test.mp4
adb -s emulator-5554 push app/src/main/assets/videos/sample_360_test.mp4 /sdcard/Download/download_test.mp4
adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/library_test.mp4
adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/download_test.mp4
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
```

因为 `Main.kt` 还没接入 `MainLibraryScreen`（下一步 Task 7 才改 `Main.kt`），这一步暂时没有真正可点的入口——先跳过截图验证，改成只确认 `./gradlew assembleDebug` 编译通过；真正的模拟器截图验证放在 Task 7（`Main.kt` 接入之后，侧栏/列表/权限门才真的能在设备上看到）。

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryCategory.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/VideoListCard.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Stage 2 Task 5: main library screen skeleton (sidebar + list + permission gate)"
```

---

### Task 6: 格式修正弹层

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/FormatCorrectionPopup.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`

**Interfaces:**
- Consumes: `SpatialPopup`/`SegmentControl`/`SegmentItem`（`com.pico.spatial.ui.design`/`com.pico.spatial.ui.design.windows`）、`VideoPreferencesStore.setFormatOverride`（Task 3，已通过 `LibraryViewModel.preferencesStore` 暴露）。
- Produces: `FormatCorrectionPopup` Composable，`onConfirm: (Projection, StereoMode) -> Unit` 回调——`MainLibraryScreen` 里接线到 `libraryViewModel.preferencesStore.setFormatOverride(...)` + 刷新当前分类列表。

- [ ] **Step 1: 写 `FormatCorrectionPopup.kt`**

```kotlin
package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.SpatialPopup
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

private fun Projection.label() = when (this) {
    Projection.FLAT -> "平面"
    Projection.HEMISPHERE_180 -> "180°"
    Projection.SPHERE_360 -> "360°"
}

private fun StereoMode.label() = when (this) {
    StereoMode.MONO -> "单目"
    StereoMode.SIDE_BY_SIDE -> "左右 3D"
    StereoMode.TOP_AND_DOWN -> "上下 3D"
    StereoMode.MULTIVIEW_MVHEVC -> "MV-HEVC"
}

@Composable
fun FormatCorrectionPopup(
    initialProjection: Projection,
    initialStereoMode: StereoMode,
    onDismissRequest: () -> Unit,
    onConfirm: (Projection, StereoMode) -> Unit,
) {
    var projection by remember { mutableStateOf(initialProjection) }
    var stereoMode by remember { mutableStateOf(initialStereoMode) }

    SpatialPopup(onDismissRequest = onDismissRequest) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text(
                text = "修正格式",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
            SegmentControl(modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)) {
                Projection.entries.forEach { candidate ->
                    SegmentItem(
                        selected = projection == candidate,
                        onClick = { projection = candidate },
                        title = {
                            Text(text = candidate.label(), style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                        },
                    )
                }
            }
            SegmentControl(modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)) {
                StereoMode.entries.forEach { candidate ->
                    SegmentItem(
                        selected = stereoMode == candidate,
                        onClick = { stereoMode = candidate },
                        title = {
                            Text(text = candidate.label(), style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                        },
                    )
                }
            }
            Row(modifier = androidx.compose.ui.Modifier.padding(top = 12.dp)) {
                Button(onClick = onDismissRequest) {
                    Text(text = "取消", style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                }
                Button(onClick = { onConfirm(projection, stereoMode) }) {
                    Text(text = "确定", style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                }
            }
        }
    }
}
```

- [ ] **Step 2: 在 `MainLibraryScreen.kt` 里接线**

```kotlin
// MainLibraryScreen.kt 顶部 import 追加：
import androidx.compose.runtime.mutableStateOf
import tech.illusion.spaceplayer.library.VideoItem

// 在 hasVideoPermission 之后新增一个 correction 弹层状态：
var itemPendingCorrection by remember { mutableStateOf<VideoItem?>(null) }

// 把 VideoListCard 调用里的 onRequestFormatCorrection = {} 改成：
onRequestFormatCorrection = { itemPendingCorrection = item },

// 在 Row(...) { ... } 整个布局结束、PicoTheme 块结束之前追加：
itemPendingCorrection?.let { item ->
    FormatCorrectionPopup(
        initialProjection = item.projection,
        initialStereoMode = item.stereoMode,
        onDismissRequest = { itemPendingCorrection = null },
        onConfirm = { projection, stereoMode ->
            libraryViewModel.preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
            itemPendingCorrection = null
        },
    )
}
```

- [ ] **Step 3: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。真正在设备上点开弹层的截图验证放在 Task 7（`Main.kt` 接入 `MainLibraryScreen` 之后一起做，避免这里裸测一个还没接入主窗口的 Composable）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/FormatCorrectionPopup.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Stage 2 Task 6: format correction popup"
```

---

### Task 7: 底部操作栏 + 真实播放接线（PlaybackManager/PlaybackViewModel 改造 + Main.kt 接入 + SAF"其它")

这是本 Stage 里改动面最大的一个 Task：把 Stage 1 的"固定测试视频"入口，换成"真实 `VideoItem`"入口。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackManager.kt`（`setup(assetPath: String)` → `setup(uri: Uri)`）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`（三个测试方法合并成 `startPlayback(item: VideoItem)`）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryBottomBar.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/Main.kt`
- Delete: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`

**Interfaces:**
- Consumes: `VideoItem`（Task 1）、`Environment`（`playback/Environment.kt`）、Task 5 的 `MainLibraryScreen`/`LibraryViewModel`。
- Produces: `PlaybackViewModel.startPlayback(item: VideoItem)`（替代原来的三个 `startXxxTestPlayback`），`LibraryBottomBar` Composable——Task 8 会再改 `PlaybackViewModel` 加历史/偏好环境持久化。

- [ ] **Step 1: 改 `PlaybackManager.kt`——`setup` 从 asset 路径改成 `Uri`**

```kotlin
// PlaybackManager.kt 顶部 import 追加：
import android.content.res.AssetFileDescriptor
import android.net.Uri

// 把原来的：
//     fun setup(assetPath: String) {
//         state = PlaybackState.PREPARING
//         duration = 1L
//         hasFirstFrameRendered = false
//         player.registerCypressMediaPlayerCallback(callback)
//         val afd = context.assets.openFd(assetPath)
//         player.setDataSource(afd)
//         afd.close()
//         player.setVolume(INIT_VOLUME)
//         player.prepareAsync()
//     }
// 换成：
    fun setup(uri: Uri) {
        state = PlaybackState.PREPARING
        duration = 1L
        hasFirstFrameRendered = false
        player.registerCypressMediaPlayerCallback(callback)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open file descriptor for $uri")
        val afd = AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        player.setDataSource(afd)
        afd.close()
        player.setVolume(INIT_VOLUME)
        player.prepareAsync()
    }
```

- [ ] **Step 2: 改 `PlaybackViewModel.kt`——三个测试方法合并成 `startPlayback(item: VideoItem)`**

```kotlin
// PlaybackViewModel.kt 顶部 import 追加：
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Projection

// 新增一个字段，记录当前正在播的视频（Task 8 的历史写入/退出时记录偏好环境都要用）：
    var currentItem: VideoItem? = null
        private set

// 删掉原来的 startTestPlayback/startSphereTestPlayback/startHemisphereTestPlayback 三个函数，
// 换成一个统一入口：
    fun startPlayback(item: VideoItem) {
        currentItem = item
        val dimensionMode = item.stereoMode.toVideoDimensionMode()
        when (item.projection) {
            Projection.FLAT -> {
                assembleEnvironmentsIfNeeded()
                if (!screenAssembled) {
                    PlaybackEntityAssembler.assembleScreenEntity(
                        screenEntity, manager.player, SCREEN_WIDTH_METERS, SCREEN_HEIGHT_METERS, dimensionMode,
                    )
                    screenAssembled = true
                }
                disableAllVideoEntities()
                screenEntity.enabled = true
                isFlatProjection.value = true
                currentEnvironment.value = item.preferredEnvironment ?: currentEnvironment.value
                repositionScreenForCurrentEnvironment()
                updateEnvironmentVisibility()
            }
            Projection.SPHERE_360 -> {
                if (!sphereAssembled) {
                    PlaybackEntityAssembler.assembleSphereEntity(
                        sphereEntity, manager.player, SPHERE_RADIUS_METERS, FULL_SPHERE_FOV_DEGREES, dimensionMode,
                    )
                    sphereAssembled = true
                }
                disableAllVideoEntities()
                sphereEntity.enabled = true
                isFlatProjection.value = false
                updateEnvironmentVisibility()
            }
            Projection.HEMISPHERE_180 -> {
                if (!hemisphereAssembled) {
                    PlaybackEntityAssembler.assembleSphereEntity(
                        hemisphereEntity, manager.player, SPHERE_RADIUS_METERS, HEMISPHERE_FOV_DEGREES, dimensionMode,
                    )
                    hemisphereAssembled = true
                }
                disableAllVideoEntities()
                hemisphereEntity.enabled = true
                isFlatProjection.value = false
                updateEnvironmentVisibility()
            }
        }
        manager.setup(item.uri)
        isImmersive.value = true
    }
```

`PlaybackEntityAssembler`/`MeshGenerator`/`disableAllVideoEntities`/`assembleEnvironmentsIfNeeded`/`repositionScreenForCurrentEnvironment`/`updateEnvironmentVisibility` 全部不变，沿用 Stage 1 的实现——这个 Task 只重排"怎么调用它们"，不改它们本身。

- [ ] **Step 3: 改 `di/PlaybackModule.kt`——如果 `PlaybackViewModel` 构造签名没变就不用动**

`PlaybackViewModel` 的构造函数签名仍然是 `PlaybackViewModel(context: Context)`（Task 7 没有新增构造参数——历史/偏好环境持久化是 Task 8 才加），所以 `PlaybackModule.kt` 这一步不需要改。（如果实现时发现真的需要额外参数，在 Task 8 里改，不要提前在这里改。）

- [ ] **Step 4: 写 `LibraryBottomBar.kt`（环境选择器 + 开始播放按钮）**

```kotlin
package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onStartPlayback: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (selectedItem?.projection == Projection.FLAT) {
            Environment.entries.forEach { env ->
                Button(onClick = { onSelectEnvironment(env) }) {
                    Text(
                        text = if (env == selectedEnvironment) "[${env.label}]" else env.label,
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    )
                }
            }
        } else if (selectedItem != null) {
            Text(
                text = "全景视频 · 自动沉浸",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }
        Button(onClick = onStartPlayback) {
            Text(
                text = "开始播放",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
        }
    }
}
```

未选中任何视频时（`selectedItem == null`），设计稿要求整个底部操作栏禁用——`onStartPlayback`/`onSelectEnvironment` 的调用方（`MainLibraryScreen`）在 Step 5 里通过"`selectedItem == null` 时不渲染这个 Row"来实现，而不是给 `Button` 传一个还不存在的 `enabled` 参数（先看这个组件是否真的有 `enabled` 参数——`com.pico.spatial.ui.design.Button` 目前项目里的用法都没传过 `enabled`，为了不在没验证过的情况下假设它存在，这里选保守方案：不选中视频时直接不渲染底部栏）。

- [ ] **Step 5: 改 `MainLibraryScreen.kt`——接入底部操作栏 + SAF"其它" + 真实播放**

```kotlin
// MainLibraryScreen.kt 顶部 import 追加：
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.rememberCoroutineScope
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.ui.PlaybackViewModel

// 函数体内，hasVideoPermission 相关状态之后追加：
val navigator = LocalSpatialNavigator.current
val coroutineScope = rememberCoroutineScope()
val playbackScope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
val playbackViewModel: PlaybackViewModel = playbackScope.get()
var selectedEnvironment by remember { mutableStateOf(Environment.CINEMA) }

val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
) { uri ->
    if (uri != null) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val documentName = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "导入的视频"
        libraryViewModel.selectItem(
            libraryViewModel.toVideoItem(
                tech.illusion.spaceplayer.library.RawVideoRecord(
                    uri = uri,
                    displayName = documentName,
                    durationMs = 0L,
                    sizeBytes = 0L,
                ),
            ),
        )
    }
}

// SideNavigationItem 的 onClick 里，IMPORT 分类要触发 SAF 而不是单纯切换分类：
// 把 onClick = { libraryViewModel.selectCategory(category) } 改成：
onClick = {
    if (category == LibraryCategory.IMPORT) {
        importLauncher.launch(arrayOf("video/*"))
    } else {
        libraryViewModel.selectCategory(category)
    }
},

// 在 Column(...) { ... LazyColumn(...) ... } 结束之后、Row 结束之前，追加底部操作栏：
Box(modifier = Modifier.fillMaxWidth()) {
    if (libraryViewModel.selectedItem != null) {
        LibraryBottomBar(
            selectedItem = libraryViewModel.selectedItem,
            selectedEnvironment = selectedEnvironment,
            onSelectEnvironment = { selectedEnvironment = it },
            onStartPlayback = {
                val item = libraryViewModel.selectedItem ?: return@LibraryBottomBar
                val itemToPlay = if (item.projection == tech.illusion.spaceplayer.playback.Projection.FLAT) {
                    item.copy(preferredEnvironment = item.preferredEnvironment ?: selectedEnvironment)
                } else {
                    item
                }
                playbackViewModel.startPlayback(itemToPlay)
                coroutineScope.launch { navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full) }
            },
        )
    }
}
```

这一步需要 `androidx.documentfile:documentfile` 来把 SAF 返回的 `Uri` 解析出显示名（`DocumentFile.fromSingleUri(context, uri)?.name`）——检查一下这是不是又是一个未声明的传递依赖：

```bash
find ~/.gradle/caches/modules-2/files-2.1/androidx.documentfile -maxdepth 1 -type d
```

如果这条命令没有输出（说明传递依赖里没有这个库），就要显式加依赖：

```toml
# gradle/libs.versions.toml 追加
androidx-documentfile = { group = "androidx.documentfile", name = "documentfile", version = "1.0.1" }
```

```kotlin
// app/build.gradle.kts 追加
implementation(libs.androidx.documentfile)
```

（如果命令有输出，说明已经是传递依赖，可以跳过这一步，但仍然建议显式声明，理由同 Task 5 的 `activity-compose`。）

- [ ] **Step 6: 改 `Main.kt`——接入 `MainLibraryScreen` + `windowConstraints`**

```kotlin
package tech.illusion.spaceplayer

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spaceplayer.ui.ImmersiveScene
import tech.illusion.spaceplayer.ui.library.MainLibraryScreen

const val IMMERSIVE_STAGE_ID = "ImmersiveStage"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            MainLibraryScreen(modifier = androidx.compose.ui.Modifier.windowConstraints(minWidth = 1400.dp, minHeight = 860.dp))
        }

        Stage(id = IMMERSIVE_STAGE_ID) {
            ImmersiveScene()
        }
    }
```

- [ ] **Step 7: 删除 `PlaceholderMainScreen.kt`**

```bash
rm app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt
```

- [ ] **Step 8: 构建 + 单元测试**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，Stage 1 遗留的 `StereoModeMappingTest`（4 个）+ Stage 2 新增的所有单测全部 PASS。

- [ ] **Step 9: 模拟器截图验证——真实视频从选择到播放的完整链路**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
for i in $(seq 1 6); do sleep 2; done
pico-cli capture screenshot --out ./artifacts/task7-library-screen.png --device emulator-5554
```

看截图确认：权限已授予（Task 5 已经手动授权过一次的话这里应该直接进列表；如果又回到权限请求页说明每次装/卸 APK 权限状态会重置，属预期，重新点一次"去授权"），左侧四个分类可见，"视频资源库"分类下能看到 Step 6（Task 5）塞进去的 `library_test.mp4`。用临时 `LaunchedEffect` 验证选中+播放这条链路（验证完删除）：

```kotlin
// 临时加在 MainLibraryScreen.kt 函数体最前面，验证完必须删除
LaunchedEffect(libraryViewModel.libraryItems) {
    val firstItem = libraryViewModel.libraryItems.firstOrNull() ?: return@LaunchedEffect
    libraryViewModel.selectItem(firstItem)
    playbackViewModel.startPlayback(firstItem.copy(preferredEnvironment = Environment.CINEMA))
    navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
}
```

重新构建/安装/启动/截图，确认真实文件（不是 assets 里的测试视频，是 Step 6/Task 5 塞进 `/sdcard/Movies/` 的那个文件）能在沉浸 Stage 里播放，然后**删除这段临时代码**，重新构建一次确认干净。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackManager.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryBottomBar.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt \
  app/src/main/java/tech/illusion/spaceplayer/Main.kt \
  gradle/libs.versions.toml app/build.gradle.kts
git rm app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt
git commit -m "Stage 2 Task 7: wire real VideoItem playback (PlaybackManager Uri support, bottom bar, SAF import)"
```

---

### Task 8: 播放历史写入 + 每视频偏好环境持久化

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackManager.kt`（加 `onFirstFrameRendered` 回调）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`（"历史"分类改成读真实数据）

**Interfaces:**
- Consumes: `PlaybackHistoryStore`/`VideoPreferencesStore`（Task 3）。
- Produces: 无新的对外签名——这个 Task 是把已有的 Store 接进已有的播放生命周期。

- [ ] **Step 1: 改 `PlaybackManager.kt`——加一次性回调**

```kotlin
// PlaybackManager.kt 类体内新增：
    var onFirstFrameRendered: (() -> Unit)? = null

// 把 callback 里的：
//     override fun onVideoSizeChanged(width: Int, height: Int) {
//         hasFirstFrameRendered = true
//     }
// 改成：
    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (!hasFirstFrameRendered) {
            hasFirstFrameRendered = true
            onFirstFrameRendered?.invoke()
        }
    }
```

（`if (!hasFirstFrameRendered)` 防止同一次播放里多次触发 `onVideoSizeChanged`——比如视频中途分辨率变化——导致历史被重复写入多次，虽然 `PlaybackHistoryStore` 本身用 uri 做 key 天然去重，多写几次也不会产生脏数据，但没必要每次分辨率变化都触发一次历史写入。）

- [ ] **Step 2: 改 `di/PlaybackModule.kt`——`PlaybackViewModel` 构造参数加两个 Store**

```kotlin
package tech.illusion.spaceplayer.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.library.storage.SharedPreferencesKeyValueStore
import tech.illusion.spaceplayer.ui.PlaybackViewModel

const val PLAYBACK_SESSION_SCOPE_ID = "playback_session_scope"

val playbackModule = module {
    single { VideoPreferencesStore(SharedPreferencesKeyValueStore(get(), "video_preferences")) }
    single { PlaybackHistoryStore(SharedPreferencesKeyValueStore(get(), "playback_history")) }
    scope(named(PLAYBACK_SESSION_SCOPE_ID)) {
        scoped { PlaybackViewModel(get(), get(), get()) }
    }
}
```

注意：这里新增的 `VideoPreferencesStore` single 和 `LibraryViewModel`（Task 5，主窗口内 `remember` 持有）各自用同名 `SharedPreferences` 文件（`"video_preferences"`）但各自 new 了一个 `SharedPreferencesKeyValueStore` 实例——`SharedPreferences` 本身在同一个文件名下是进程内单例（`Context.getSharedPreferences` 返回的是同一个底层对象），所以两处分别持有的 `KeyValueStore` 实例读写的是同一份数据，不会不一致，但这确实是两套对象各管一份、没有共享同一个 `VideoPreferencesStore` 实例。这是可以接受的重复（`LibraryViewModel` 活在主窗口 Compose 树，`PlaybackViewModel` 活在 Koin session scope，两棵树本来就是分开的，Stage 1 就是这样设计的），先不为了去重这一点点重复而引入更复杂的跨容器共享。

- [ ] **Step 3: 改 `PlaybackViewModel.kt`——构造参数 + 历史写入 + 偏好环境持久化**

```kotlin
// 类声明从：
// class PlaybackViewModel(context: Context) {
// 改成：
class PlaybackViewModel(
    context: Context,
    private val historyStore: tech.illusion.spaceplayer.library.PlaybackHistoryStore,
    private val preferencesStore: tech.illusion.spaceplayer.library.VideoPreferencesStore,
) {

// startPlayback(item: VideoItem) 函数体末尾，在 `manager.setup(item.uri)` 之前插入：
// 注意：recordPlayed 现在收的是 String key（Task 3 的真实发现——PlaybackHistoryStore 全程用
// String，不碰 Uri，见 Task 3 顶部说明），这里传 item.uri.toString()。
        manager.onFirstFrameRendered = {
            historyStore.recordPlayed(item.uri.toString(), System.currentTimeMillis())
        }

// exitImmersive() 函数体里，在 `manager.pause()` 之前插入：
    fun exitImmersive() {
        val item = currentItem
        if (item != null && isFlatProjection.value) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        isImmersive.value = false
    }
```

`System.currentTimeMillis()` 是这里唯一需要真实墙钟时间的地方——`PlaybackHistoryEntry.lastPlayedAt` 本来就该是真实时间戳，不是 workflow 脚本里被禁用的那个 `Date.now()`（这是普通 Kotlin/Android 代码，不受 workflow 脚本沙箱限制）。

- [ ] **Step 4: 改 `MainLibraryScreen.kt`——"历史"分类读真实数据**

```kotlin
// 顶部 import 追加：
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// 函数体内，在 playbackViewModel 那一行之后追加：
val historyStore: tech.illusion.spaceplayer.library.PlaybackHistoryStore =
    GlobalContext.get().get()

// 把原来传给 visibleItems 的 emptyList() 改成从历史 store 解析出 VideoItem：
// recentEntriesDescending() 返回 List<Pair<String, Long>>（uriKey to lastPlayedAt），按 uriKey
// 关联回已经查询到的 VideoItem——这里用 item.uri.toString() 比较，不需要用到会在纯 JVM 单测里
// 抛异常的 Uri.parse()（这段代码本来就只在真机/模拟器上跑，不受这个限制，但沿用同一个 key 格式
// 更省心，不用两套转换逻辑）。
val historyItems = historyStore.recentEntriesDescending().mapNotNull { (uriKey, _) ->
    (libraryViewModel.libraryItems + libraryViewModel.downloadsItems)
        .find { it.uri.toString() == uriKey }
}
val items = libraryViewModel.visibleItems(historyItems = historyItems)
```

"历史"分类只能列出当前"视频资源库"/"下载"两个分类里已经查询到的视频（按 uri 关联），如果历史记录指向一个已经被删除/移出 MediaStore 扫描范围的文件，这里会被 `find` 静默过滤掉（返回 null 被 `mapNotNull` 丢弃）——这是合理行为，不应该在历史里展示一个已经不存在的文件。

- [ ] **Step 5: 构建 + 单元测试**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，全部单测 PASS（`PlaybackHistoryStoreTest`/`VideoPreferencesStoreTest` 沿用 Task 3 已经验证过的纯逻辑，这里只是接线，不需要新增单测）。

- [ ] **Step 6: 模拟器截图验证——播放一次后退出，确认"历史"分类出现该视频，且下次选中同一视频时环境预选正确**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
for i in $(seq 1 6); do sleep 2; done
```

用 Task 7 Step 9 同样的"临时 `LaunchedEffect` 自动触发选中+播放+等待+退出"手法（选一个环境比如"星空"、播放、等待首帧渲染、退出），退出后切到"历史"分类截图确认该视频出现；再次选中同一视频，确认 `LibraryBottomBar` 的环境选择器默认高亮显示上次选的"星空"（`item.preferredEnvironment` 从 `VideoPreferencesStore` 读回来了）。验证完删除临时代码，重新构建确认干净。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackManager.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt \
  app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Stage 2 Task 8: playback history write-on-first-frame + preferred environment persistence"
```

---

### Task 9: Stage 2 端到端回归 + AGENTS.md 更新 + 提交

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/2026-08-06-stage2-video-library.md`（本文件，标记完成 + 记录真实发现，沿用 Stage 1 的做法）

**Interfaces:**
- Consumes: Task 1-8 全部产出。
- Produces: 无新接口，回归验证 Task。

- [ ] **Step 1: 全量重新构建 + 单元测试**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`；`FilenameFormatDetectorTest`（14）+ `FormatDetectorTest`（4）+ `VideoPreferencesStoreTest`（3）+ `PlaybackHistoryStoreTest`（2）+ Stage 1 的 `StereoModeMappingTest`（4）+ 脚手架 `ExampleUnitTest`（1）全部 PASS，共 28 个。

- [ ] **Step 2: 走查完整流程并各截一张图到 `./artifacts/stage2-regression-*.png`**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
```

依次验证（沿用 Stage 1 Task 9 的手法：每条路径改一次临时 `LaunchedEffect`，截图后改下一条，全部走完后整体删除临时代码）：
1. 权限未授权时的引导页 → 点"去授权" → 弹出系统权限对话框（`adb shell input tap` 对这个系统级对话框是有效的，因为它不是 spatial 容器内的 Compose UI，是系统 `PackageManager` 的原生权限弹窗）。
2. 授权后"视频资源库"分类列出真实文件（缩略图 + 文件名 + 时长 + 格式徽标），"下载"分类只列 Download 目录下的文件。
3. 点一个 `formatSource == DEFAULT` 的视频的"修正格式"，弹出 `SpatialPopup`，改成 180°/TB，确定后徽标立即更新。
4. 选中一个平面视频 → 底部操作栏环境选择器可交互 → 选"海景" → 开始播放 → 沉浸播放正常（复用 Stage 1 已验证的 ECS/HUD 链路）→ 退出。
5. 再次选中同一个视频 → 底部操作栏默认高亮"海景"（偏好环境生效）。
6. "历史"分类里出现刚播放过的视频。
7. "其它"分类点击触发 SAF 文件选择器，选中一个视频后自动加入选中态（能进入底部操作栏播放）。

```bash
adb -s emulator-5554 logcat -d -t 500 | grep -iE "FATAL|AndroidRuntime|tech.illusion.spaceplayer"
```

Expected: 七条路径都符合预期；全程无新增崩溃（只有正常的 `SpatialRuntimeService: Watchdog` 之类日志，参考 Stage 1 Task 9 的判定标准）。

- [ ] **Step 3: 更新 `AGENTS.md`**

写清楚：Stage 2 完成的范围（真实 `MediaStore`/SAF 文件库、三级格式识别流水线、手动覆盖 + 播放历史持久化、`PlaybackManager`/`PlaybackViewModel` 从"固定测试视频"改造成"真实 `VideoItem`"）、MV-HEVC 容器探测是未验证的启发式这一点、新增的 `androidx-activity-compose`/`androidx-documentfile`/`mockito-core` 依赖、构建/安装/运行命令不变、下一步是 Stage 3（字幕，设计见 `docs/superpowers/specs/2026-08-05-spaceplayer-design.md` 第 4 节"字幕"小节）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "Stage 2 regression pass + AGENTS.md update"
```

## Self-Review 记录（写完计划后的复查结论）

- **Spec 覆盖**：设计稿第 2 节（数据模型/识别流水线/文件库管理）→ Task 1/2/3/4；第 4 节主窗口布局 → Task 5/6/7；播放历史/偏好环境 → Task 8；第 4 节"沉浸内 HUD"/"180°/360°"/"播放与 dock 流程" → 沿用 Stage 1 已完成部分，Task 7 只重接入口。字幕（第 4 节最后一小节）明确不在本计划范围内（Stage 3）。
- **未验证项**（已在对应 Task 里如实标注，不是遗漏）：MV-HEVC 容器探测准确率（Task 2，本机无真实样本文件）；`SideNavigationItem`/`ListItem` 的 `BoxScope` receiver 在尾随 lambda 里省略是否总能编译通过（Task 5 Step 5 备注里说明了原因，如果实现时报编译错误，需要把 `content = { Text(...) }` 显式换成 `content = { _: androidx.compose.foundation.layout.BoxScope -> Text(...) }` 之类的显式接收者写法）；`Button` 是否有 `enabled` 参数未经验证，Task 7 里选择了不依赖它的保守方案。
- **类型一致性**：`VideoItem`/`DetectedFormat`/`FormatSource`（Task 1）→ `FormatDetector`（Task 2）→ `VideoPreferencesStore`/`PlaybackHistoryStore`（Task 3）→ `VideoLibraryRepository`/`RawVideoRecord`（Task 4）→ `LibraryViewModel.toVideoItem`（Task 5）→ `PlaybackViewModel.startPlayback(item: VideoItem)`（Task 7）→ `historyStore`/`preferencesStore`（Task 8）全程用同一套类型和函数名，没有出现"Task 3 叫 `setFormatOverride` 但 Task 6 调用 `updateFormatOverride`"这类不一致。
