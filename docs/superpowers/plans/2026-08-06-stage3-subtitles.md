# SpacePlayer Stage 3: 字幕 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给沉浸播放加上外部 `.srt` 字幕：同目录同名文件自动发现，或在格式修正弹层里手动指定；播放时字幕面板独立于 HUD，位置延迟跟随用户头部、朝向始终跟随用户，不随 180°/360° 球体网格旋转飘出可视范围。

**Architecture:** 数据层（`subtitle/` 包）负责 SRT 解析 + 按当前播放位置查字幕文本，全部纯逻辑可单测；`library/` 包扩展 `VideoItem`/`VideoPreferencesStore` 携带每视频的字幕 Uri；ECS 层移植 StoryPico 项目（同 spatialBom 0.13.3）已经验证过的"位置延迟跟随 + 朝向跟随"手写实现（`SubtitleFollowComponent` + 每帧驱动函数，读 `HMDTrackingProvider` 的头显位姿，不依赖 ECS 自动调度）；`PlaybackViewModel`/`ImmersiveScene` 接入一个独立于 loading/HUD 的第三个 `AttachmentPanel`。

**Tech Stack:** Kotlin + PICO Spatial SDK（`spatialBom 0.13.3`）+ `com.pico.spatial.tracking.hmd.HMDTrackingProvider`（头显位姿）+ SpatialUI（2D UI 一律包在 `PicoTheme` 里，禁止 Material/Material3）。

## Global Constraints

- 仅支持外部 `.srt` 文件，纯文本逐行时间轴渲染；不做内嵌字幕轨道解析、不做 `.ass` 特效样式（设计稿第 5 节非目标）。
- 字幕是独立于 HUD 的另一个 `AttachmentPanel`，不是 HUD 的一部分。
- 2D UI 组件一律 SpatialUI（`com.pico.spatial.ui.*`）包在 `PicoTheme` 里，禁止 `androidx.compose.material`/`material3`。
- `org.json.JSONObject`/`Uri.parse()` 在本项目的纯 JVM 单元测试环境（没有接入 Robolectric）里会抛 `RuntimeException`——任何要写单测的持久化/解析逻辑必须避开这两个 API；`Uri` 类型的字段在需要单测的纯逻辑层一律用 `String` 表示，`Uri` 转换只在不参与单测的 UI/仓库层做。
- 调用 SpatialUI 组件前必须确认真实签名是否真的有某个参数（尤其 `onClick`）——不要凭"看起来应该有"下笔，Stage 2 在 `SideNavigationItem`/`ListItem` 上吃过亏。
- 本机构建前必须 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（JDK 25 与 Gradle 8.13 内嵌 Kotlin DSL 编译器不兼容）。
- 验证一律用模拟器 `emulator-5554`；`adb shell input tap x y` 对 spatial 容器（`DefaultWindowContainer`/`Stage`）不可靠，验证交互流程要用临时 `LaunchedEffect(Unit) { ... }` 自动触发，验证完必须删除；系统级 UI（权限对话框、SAF 选择器）可以用 `adb tap`，但坐标要用 `adb shell uiautomator dump` 算，不能凭截图肉眼估。

## 关键调研结论（写 Task 前已核实，不是猜测）

这些结论来自反编译本机 Gradle 缓存里 `spatialBom 0.13.3` 实际使用的 sources jar，加上一个 Explore 子代理逐字核实同工作区 `StoryPico` 项目（`/Users/zohar/WorkSpace/Project/StoryProjects/StoryPico`，同样 spatialBom 0.13.3）里已经做好、正在用的"字幕跟随用户"实现：

1. **"锚定用户朝向"不能靠 `LookAtComponent`**——那个类型（`com.pico.spatial.core.ecs.LookAtComponent`，`setViewerAsTarget()`）真实存在，但只控制实体的**朝向**（持续转向面对用户），完全不管**位置**。如果字幕面板世界坐标固定不动，用户转身够远时它虽然会转向你但已经不在视野范围内了——不满足设计稿"不随视角旋转飘出可视范围"的要求。用户已经明确要求参考 StoryPico 的方案：**位置延迟跟随（lagged position）+ 朝向跟随（rotation follow）**，不是简单挂一个 `LookAtComponent`。
2. **StoryPico 的完整实现**（已确认可移植，字段/方法签名全部来自反编译源码或 StoryPico 真实编译通过的代码）：
   - `MoveWithCameraComponent`——StoryPico 自己写的 ECS `Component` 子类（不是 SDK 自带类型），纯状态持有：`trackingMode`（`FOLLOW`/`LAGGED`）、`relativePosition: Vector3`、`speed: Float`（lerp 速度因子，StoryPico 用 `5f`）、`minDistance: Float`（死区阈值，StoryPico 用 `0.1f`，低于这个位移量不刷新 lerp 目标）、`targetPosition`/`lastPosition`/`isInitialized` 可变状态。**不是 ECS System 自动驱动**，是每帧手动调用。
   - `HMDTrackingProvider`（包 `com.pico.spatial.tracking.hmd`，实现 `DataProvider<HMDTrackingData>`：`start()`/`stop()`/`latestData: HMDTrackingData`/`dataFlow: SharedFlow<HMDTrackingData>`）提供 `HMDTrackingData.hmdPose: HMDPose`，`HMDPose(position: Vector3, rotation: Quat)`。带 `@RequiredFullSpace` 注解——只有应用运行在 `StageStyle.Full` 沉浸模式下才有真实数据，正好匹配 SpacePlayer 现有 `ImmersiveScene`/`Stage("ImmersiveStage")` 已经在用的 `StageStyle.Full`；对比过 StoryPico 的 `AndroidManifest.xml`，没有为这个能力额外声明任何权限/meta-data。
   - 每帧手写更新公式（在 `SpatialView` 的 `update` 回调里跑，`deltaTime` 用 `System.nanoTime()` 差值转秒、clamp 到 `[0, 0.1]`）：
     - **位置**：`newWorldPos = pose.rotation.rotateVector(relativePosition) + pose.position`；只有位移超过 `minDistance` 才刷新 lerp 目标；`t = (speed * deltaTime).coerceIn(0f, 1f)`（首帧 `t=1` 直接吸附到位）；`lerped = current + (target - current) * t`（逐分量手写，不依赖 `Vector3` 运算符重载，虽然 StoryPico 代码里确实验证过 `Vector3 + Vector3` 可以直接用）。
     - **朝向**：自定义 `slerpQuat(a, b, t)`（归一化 nlerp，最短弧修正：两个四元数点积 `<0` 时把目标四元数取反再插值，避免转个大弯），目标是 `parent?.convertRotationFrom(pose.rotation, null) ?: pose.rotation`，同样用 `t`。
   - StoryPico 给字幕面板用的具体参数：`relativePosition = Vector3(0f, -0.3f, -1.0f)`（面前 1 米、下方 0.3 米），`trackingMode = LAGGED`。
   - `Entity.getParent()`/`Entity.convertPositionTo(pos, targetEntity)`/`convertPositionFrom(pos, baseEntity)`/`convertRotationTo`/`convertRotationFrom`、`TransformComponent.quaternion`/`setQuaternion(q)`/`setPosition(v)`、`Quat(x,y,z,w)` 构造函数 + `.x/.y/.z/.w` 字段、`Quat.rotateVector(Vector3): Vector3`——这些全部已经在本项目或 StoryPico 里反编译源码/真实编译通过确认存在，不是猜的。
3. **本计划对 StoryPico 原实现做了一处有意简化**：StoryPico 有一个通用的 `TrackingManager` 单例同时管理多种"头部锚定"实体（不只是字幕），位置更新（`applyMoveWithCamera`）和朝向更新分成两个独立调用点。SpacePlayer 只需要**一个**跟随实体（字幕面板），本计划把位置+朝向更新合并成一个函数 `applySubtitleFollow(entity, component, pose, deltaTime)`，不引入一个通用的 `TrackingManager` 单例类——这是针对本项目更窄需求的合理简化，不是遗漏。
4. **字幕文件发现**：MediaStore 视频用 `MediaStore.Video.Media.DATA` 拿真实文件系统路径（这一列官方从 API 29 起标记废弃，但本应用自己从 MediaStore 查到的行读这一列仍然可用；**只在 PICO 模拟器的本地存储上验证过，不保证所有设备/存储提供方都可靠**，如实记录这一点），用 `java.io.File` 找同目录同名 `.srt`。SAF 来源（Stage 2"其它"分类）的视频没有可靠的兄弟文件访问方式，只能走手动指定；手动指定用 `ActivityResultContracts.OpenDocument()`，mime 类型用 `"*/*"` 而不是依赖不可靠的 `.srt` mime 类型猜测，选中后客户端校验文件名以 `.srt` 结尾（不区分大小写）。
5. **`org.json`/`Uri.parse()` 单测限制同样适用于本计划**——`VideoPreferences` 新增的 `subtitleUri` 字段必须存成 `String?`（不是 `Uri?`），`VideoPreferencesStore.get()` 才能继续被纯 JVM 单测覆盖；`Uri.parse()` 转换放到不参与单测的 `LibraryViewModel.toVideoItem`（和 Stage 2 `PlaybackHistoryStore`/`MainLibraryScreen` 的处理方式完全一致）。

## File Structure

```
app/src/main/java/tech/illusion/spaceplayer/
  subtitle/                              # 新增
    SubtitleCue.kt                       # 数据类：startMs/endMs/text
    SrtParser.kt                         # 纯函数：SRT 文本 → List<SubtitleCue>
    SubtitleCueLookup.kt                 # 纯函数：List<SubtitleCue> + 当前位置 → 应显示的文本
    SubtitleDiscovery.kt                 # MediaStore DATA 列 + 同目录同名 .srt 查找（不写单测）
  ecs/
    SubtitleFollowComponent.kt           # 新增：从 StoryPico 移植的位置延迟跟随+朝向跟随状态持有 Component
                                          #       + applySubtitleFollow()/slerpQuat()/distanceBetween() 每帧驱动函数
  library/
    VideoItem.kt                         # 修改：加 subtitleUri: Uri?
    VideoPreferencesStore.kt             # 修改：VideoPreferences 加 subtitleUri: String?，加 setSubtitleUri()
  ui/
    PlaybackViewModel.kt                 # 修改：context 存成字段，加 hmdTrackingProvider/subtitleCues/currentSubtitleText，
                                          #       startPlayback 加载字幕、exitImmersive/onCleared 停止 tracking
    ImmersiveScene.kt                    # 修改：加字幕 AttachmentPanel + 每帧驱动 wiring
    SubtitleAttachment.kt                # 新增：字幕面板的 Compose 视觉内容
    library/
      LibraryViewModel.kt                # 修改：toVideoItem 里解析 subtitleUri（手动覆盖优先，否则自动发现）
      FormatCorrectionPopup.kt           # 修改：加"字幕：已设置/未设置" + "选择字幕文件"按钮
      MainLibraryScreen.kt               # 修改：加 subtitleLauncher（OpenDocument，"*/*"，校验 .srt 后缀）
app/src/test/java/tech/illusion/spaceplayer/subtitle/
  SrtParserTest.kt
  SubtitleCueLookupTest.kt
app/src/test/java/tech/illusion/spaceplayer/library/
  VideoPreferencesStoreTest.kt           # 修改：新增字幕覆盖相关用例
```

---

### Task 1: 字幕数据模型 + SRT 解析器

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleCue.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/subtitle/SrtParser.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/subtitle/SrtParserTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces: `SubtitleCue(startMs: Long, endMs: Long, text: String)` 数据类、`SrtParser.parse(content: String): List<SubtitleCue>`——Task 2/5 依赖这两个类型。

- [x] **Step 1: 写 `SubtitleCue.kt`**

```kotlin
package tech.illusion.spaceplayer.subtitle

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
```

- [x] **Step 2: 写失败的 `SrtParser` 测试**

```kotlin
package tech.illusion.spaceplayer.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    @Test
    fun `single cue with single line of text`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
    }

    @Test
    fun `multi-line cue text is joined with newline`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Line one
            Line two
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Line one\nLine two", cues[0].text)
    }

    @Test
    fun `multiple cues separated by blank lines`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            First

            2
            00:00:05,500 --> 00:00:08,200
            Second
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(5500L, cues[1].startMs)
        assertEquals(8200L, cues[1].endMs)
        assertEquals("Second", cues[1].text)
    }

    @Test
    fun `CRLF line endings are handled`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:04,000\r\nHello\r\n"
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Hello", cues[0].text)
    }

    @Test
    fun `leading UTF-8 BOM is stripped`() {
        val srt = "﻿1\n00:00:01,000 --> 00:00:04,000\nHello"
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
    }

    @Test
    fun `block without a timestamp line is skipped`() {
        val srt = """
            not a real cue
            just some text

            1
            00:00:01,000 --> 00:00:04,000
            Real cue
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Real cue", cues[0].text)
    }

    @Test
    fun `empty input produces no cues`() {
        assertTrue(SrtParser.parse("").isEmpty())
    }

    @Test
    fun `timestamps convert hours minutes seconds milliseconds correctly`() {
        val srt = "1\n01:02:03,456 --> 01:02:05,000\nText"
        val cues = SrtParser.parse(srt)
        // 1h2m3.456s = 3723456 ms
        assertEquals(3_723_456L, cues[0].startMs)
    }
}
```

- [x] **Step 3: 跑测试确认失败**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.subtitle.SrtParserTest"`
Expected: 编译失败，`SrtParser` unresolved reference。

- [x] **Step 4: 写 `SrtParser.kt`**

```kotlin
package tech.illusion.spaceplayer.subtitle

private val TIMESTAMP_LINE = Regex(
    """(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})""",
)

/**
 * Minimal SRT (SubRip) parser. Handles standard numbered blocks, CRLF/LF line endings, and a
 * leading UTF-8 BOM. Text is kept as-is including any embedded styling tags (<i>, {\an8}, etc.) -
 * this project does not parse or render such tags, matching the "no .ass-style effects" non-goal.
 */
object SrtParser {
    fun parse(content: String): List<SubtitleCue> {
        val normalized = content.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timestampLineIndex = lines.indexOfFirst { TIMESTAMP_LINE.containsMatchIn(it) }
            if (timestampLineIndex == -1) continue
            val match = TIMESTAMP_LINE.find(lines[timestampLineIndex]) ?: continue
            val groups = match.groupValues
            val startMs = toMillis(groups[1], groups[2], groups[3], groups[4])
            val endMs = toMillis(groups[5], groups[6], groups[7], groups[8])
            val text = lines.drop(timestampLineIndex + 1).joinToString("\n")
            if (text.isNotEmpty()) {
                cues += SubtitleCue(startMs, endMs, text)
            }
        }
        return cues
    }

    private fun toMillis(hours: String, minutes: String, seconds: String, millis: String): Long =
        hours.toLong() * 3_600_000L +
            minutes.toLong() * 60_000L +
            seconds.toLong() * 1_000L +
            millis.toLong()
}
```

- [x] **Step 5: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.subtitle.SrtParserTest"`
Expected: BUILD SUCCESSFUL，8 个测试全部 PASS。

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleCue.kt \
  app/src/main/java/tech/illusion/spaceplayer/subtitle/SrtParser.kt \
  app/src/test/java/tech/illusion/spaceplayer/subtitle/SrtParserTest.kt
git commit -m "Stage 3 Task 1: subtitle cue model + SRT parser"
```

---

### Task 2: 字幕查找纯函数

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleCueLookup.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/subtitle/SubtitleCueLookupTest.kt`

**Interfaces:**
- Consumes: `SubtitleCue`（Task 1）。
- Produces: `SubtitleCueLookup.textAt(cues: List<SubtitleCue>, positionMs: Long): String`——Task 5 的 `PlaybackViewModel.refreshSubtitleText()` 依赖这个签名。

- [x] **Step 1: 写失败的测试**

```kotlin
package tech.illusion.spaceplayer.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCueLookupTest {
    private val cues = listOf(
        SubtitleCue(1000L, 4000L, "First"),
        SubtitleCue(5000L, 8000L, "Second"),
    )

    @Test
    fun `before first cue returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 500L))
    }

    @Test
    fun `during a cue returns its text`() {
        assertEquals("First", SubtitleCueLookup.textAt(cues, 2000L))
    }

    @Test
    fun `exactly at cue boundaries is inclusive`() {
        assertEquals("First", SubtitleCueLookup.textAt(cues, 1000L))
        assertEquals("First", SubtitleCueLookup.textAt(cues, 4000L))
    }

    @Test
    fun `gap between cues returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 4500L))
    }

    @Test
    fun `after last cue returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 9000L))
    }

    @Test
    fun `empty cue list always returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(emptyList(), 1000L))
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.subtitle.SubtitleCueLookupTest"`
Expected: 编译失败，`SubtitleCueLookup` unresolved reference。

- [x] **Step 3: 写 `SubtitleCueLookup.kt`**

```kotlin
package tech.illusion.spaceplayer.subtitle

object SubtitleCueLookup {
    fun textAt(cues: List<SubtitleCue>, positionMs: Long): String {
        val cue = cues.firstOrNull { positionMs in it.startMs..it.endMs }
        return cue?.text ?: ""
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.subtitle.SubtitleCueLookupTest"`
Expected: BUILD SUCCESSFUL，6 个测试全部 PASS。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleCueLookup.kt \
  app/src/test/java/tech/illusion/spaceplayer/subtitle/SubtitleCueLookupTest.kt
git commit -m "Stage 3 Task 2: subtitle cue lookup by playback position"
```

---

### Task 3: 字幕文件发现 + 数据层扩展

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleDiscovery.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/library/VideoPreferencesStore.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/library/VideoPreferencesStoreTest.kt`（追加用例）

**Interfaces:**
- Consumes: `VideoPreferencesStore`/`VideoPreferences`（Stage 2 Task 3）、`RawVideoRecord`（Stage 2 Task 4）。
- Produces: `SubtitleDiscovery.findSiblingSrt(context: Context, videoUri: Uri): Uri?`、`VideoItem.subtitleUri: Uri?`、`VideoPreferencesStore.setSubtitleUri(uri: Uri, subtitleUri: Uri)`——Task 5/7 依赖这些签名。

- [x] **Step 1: 写 `SubtitleDiscovery.kt`（不写单测——直接触碰 `ContentResolver`/`java.io.File`，和 `VideoLibraryRepository` 同类）**

```kotlin
package tech.illusion.spaceplayer.subtitle

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Looks for a same-directory, same-base-name `.srt` file next to a MediaStore-backed video, using
 * `MediaStore.Video.Media.DATA` to get a real filesystem path. `DATA` is deprecated since API 29
 * but still populated for rows this app itself queries from MediaStore - only verified against the
 * PICO emulator's local storage in this project, not confirmed reliable across all devices/storage
 * providers. SAF-imported videos (Stage 2's "其它" category) have no reliable sibling-file access
 * and always resolve to null here - those rely entirely on the manual override in
 * [tech.illusion.spaceplayer.library.VideoPreferencesStore].
 */
object SubtitleDiscovery {
    fun findSiblingSrt(context: Context, videoUri: Uri): Uri? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        val path = context.contentResolver.query(videoUri, projection, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (columnIndex == -1) null else cursor.getString(columnIndex)
            } ?: return null
        val videoFile = File(path)
        val parent = videoFile.parentFile ?: return null
        val srtFile = File(parent, "${videoFile.nameWithoutExtension}.srt")
        return if (srtFile.exists()) Uri.fromFile(srtFile) else null
    }
}
```

- [x] **Step 2: 改 `VideoItem.kt`——加 `subtitleUri`**

```kotlin
// VideoItem.kt 的 VideoItem 数据类，在 preferredEnvironment 字段后追加：
data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val thumbnailUri: Uri?,
    val projection: Projection,
    val stereoMode: StereoMode,
    val formatSource: FormatSource,
    val preferredEnvironment: Environment?,
    val subtitleUri: Uri?,
)
```

- [x] **Step 3: 写失败的 `VideoPreferencesStore` 字幕用例（追加到已有测试文件）**

```kotlin
// 追加到 app/src/test/java/tech/illusion/spaceplayer/library/VideoPreferencesStoreTest.kt

    @Test
    fun `subtitle uri round-trips as a string`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/4")
        val subtitleUri = fakeUri("content://com.android.externalstorage.documents/document/primary%3Amovie.srt")
        store.setSubtitleUri(uri, subtitleUri)
        val prefs = store.get(uri)
        assertEquals("content://com.android.externalstorage.documents/document/primary%3Amovie.srt", prefs.subtitleUri)
    }

    @Test
    fun `setting subtitle uri preserves prior format override`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/5")
        store.setFormatOverride(uri, Projection.FLAT, StereoMode.MONO)
        store.setSubtitleUri(uri, fakeUri("content://media/subtitle.srt"))
        val prefs = store.get(uri)
        assertEquals(Projection.FLAT, prefs.projectionOverride)
        assertEquals("content://media/subtitle.srt", prefs.subtitleUri)
    }
```

- [x] **Step 4: 跑测试确认失败**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.VideoPreferencesStoreTest"`
Expected: 编译失败，`setSubtitleUri`/`prefs.subtitleUri` unresolved reference。

- [x] **Step 5: 改 `VideoPreferencesStore.kt`——加 `subtitleUri: String?` + `setSubtitleUri`**

`subtitleUri` 存成 `String?` 而不是 `Uri?`——`VideoPreferencesStore.get()` 要继续能被纯 JVM 单测覆盖，不能在里面调用 `Uri.parse()`（会抛 `RuntimeException`，见本计划顶部的调研结论第 5 条）。`Uri` 字符串本身可能含 `%`/`:` 等字符，用 `java.net.URLEncoder`/`URLDecoder`（纯 Java API，非 Android 框架，单测里安全）转义后再塞进 `key=value;key=value` 格式，避免破坏现有字段的解析。

```kotlin
// VideoPreferencesStore.kt 顶部 import 追加：
import java.net.URLDecoder
import java.net.URLEncoder

// VideoPreferences 数据类追加字段：
data class VideoPreferences(
    val projectionOverride: Projection? = null,
    val stereoModeOverride: StereoMode? = null,
    val preferredEnvironment: Environment? = null,
    val subtitleUri: String? = null,
)

// get() 函数里，fields 解析追加一行：
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
            subtitleUri = fields[KEY_SUBTITLE]?.let { URLDecoder.decode(it, "UTF-8") },
        )
    }

    // 新增方法：
    fun setSubtitleUri(uri: Uri, subtitleUri: Uri) {
        save(uri, get(uri).copy(subtitleUri = subtitleUri.toString()))
    }

    // save() 函数里，fields 列表构造追加一行：
    private fun save(uri: Uri, prefs: VideoPreferences) {
        val fields = buildList {
            prefs.projectionOverride?.let { add("$KEY_PROJECTION$ENTRY_SEPARATOR${it.name}") }
            prefs.stereoModeOverride?.let { add("$KEY_STEREO$ENTRY_SEPARATOR${it.name}") }
            prefs.preferredEnvironment?.let { add("$KEY_ENVIRONMENT$ENTRY_SEPARATOR${it.name}") }
            prefs.subtitleUri?.let { add("$KEY_SUBTITLE$ENTRY_SEPARATOR${URLEncoder.encode(it, "UTF-8")}") }
        }
        storage.put(uri.toString(), fields.joinToString(FIELD_SEPARATOR))
    }

    // companion object 里追加一个 key 常量：
    private companion object {
        const val FIELD_SEPARATOR = ";"
        const val ENTRY_SEPARATOR = "="
        const val KEY_PROJECTION = "projection"
        const val KEY_STEREO = "stereo"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_SUBTITLE = "subtitle"
    }
```

- [x] **Step 6: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.library.VideoPreferencesStoreTest"`
Expected: BUILD SUCCESSFUL，5 个测试全部 PASS（原有 3 个 + 新增 2 个）。

- [x] **Step 7: 改 `LibraryViewModel.kt`——`toVideoItem` 里解析字幕（手动覆盖优先，否则自动发现）**

```kotlin
// LibraryViewModel.kt 顶部 import 追加：
import android.net.Uri
import tech.illusion.spaceplayer.subtitle.SubtitleDiscovery

// toVideoItem 函数体：手动覆盖存在就用 Uri.parse(它)（这里可以放心用 Uri.parse，因为
// toVideoItem 全程没有单测覆盖，属于 VideoLibraryRepository/PlaybackManager 那一类
// "触碰 Android 框架、只做构建+模拟器验证" 的代码，见 Stage 2 AGENTS.md 的既定约定）；
// 否则调用 SubtitleDiscovery 按同目录同名规则找。
    fun toVideoItem(record: RawVideoRecord): VideoItem {
        val uriPrefs = preferencesStore.get(record.uri)
        val detected = formatDetector.detect(context, record.uri, record.displayName)
        val subtitleUri = uriPrefs.subtitleUri?.let(Uri::parse)
            ?: SubtitleDiscovery.findSiblingSrt(context, record.uri)
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
            subtitleUri = subtitleUri,
        )
    }
```

- [x] **Step 8: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`（这一步 `VideoListCard`/SAF 导入等调用 `VideoItem(...)` 构造函数的地方也要跟着补上新的 `subtitleUri` 参数——`MainLibraryScreen.kt` 里 SAF"其它"导入走的是 `libraryViewModel.toVideoItem(RawVideoRecord(...))`，不直接构造 `VideoItem`，不需要改；如果编译报"missing parameter subtitleUri"，说明有遗漏的直接构造点，照着报错位置补上）。

- [x] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/subtitle/SubtitleDiscovery.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/VideoItem.kt \
  app/src/main/java/tech/illusion/spaceplayer/library/VideoPreferencesStore.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt \
  app/src/test/java/tech/illusion/spaceplayer/library/VideoPreferencesStoreTest.kt
git commit -m "Stage 3 Task 3: subtitle file discovery + VideoItem/VideoPreferencesStore extension"
```

---

### Task 4: SubtitleFollowComponent（位置延迟跟随 + 朝向跟随，移植自 StoryPico）

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ecs/SubtitleFollowComponent.kt`

**Interfaces:**
- Consumes: 无新依赖（`com.pico.spatial.core.ecs.{Component, Entity, TransformComponent}`、`com.pico.spatial.core.math.{Quat, Vector3}`、`com.pico.spatial.tracking.hmd.HMDPose`，全部已在本计划顶部的调研结论里确认真实存在）。
- Produces: `SubtitleFollowComponent`（`Component` 子类，构造参数 `relativePosition`/`speed`/`minDistance`）、`applySubtitleFollow(entity: Entity, component: SubtitleFollowComponent, pose: HMDPose, deltaTime: Float)`——Task 6 的 `ImmersiveScene.kt` 依赖这个函数签名。这个 Task 直接触碰 ECS/数学类型，不写单元测试（这些类型在纯 JVM 单测里没有真实实现，和 `PlaybackEntityAssembler`/`MeshGenerator` 一样，只做构建 + 模拟器验证，验证步骤见 Task 6）。

- [x] **Step 1: 写 `SubtitleFollowComponent.kt`**

```kotlin
package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Component
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDPose
import kotlin.math.sqrt

/**
 * Ported from StoryPico's `MoveWithCameraComponent` (same spatialBom 0.13.3, see
 * /Users/zohar/WorkSpace/Project/StoryProjects/StoryPico) - a plain state holder, *not* driven by
 * the ECS scheduler. [applySubtitleFollow] is called manually every frame from
 * `ImmersiveScene.kt`'s `SpatialView.update` block, reading the HMD pose from
 * `HMDTrackingProvider.latestData`.
 *
 * Deliberately simpler than StoryPico's original: that project has a general `TrackingManager`
 * driving many head-anchored entities with position and rotation updated by two separate call
 * sites. SpacePlayer only ever follows one entity (the subtitle panel), so position + rotation are
 * combined into the single [applySubtitleFollow] function below.
 */
class SubtitleFollowComponent(
    val relativePosition: Vector3 = Vector3(0f, -0.3f, -1.0f),
    val speed: Float = 5f,
    val minDistance: Float = 0.1f,
) : Component() {
    /** World-space position the entity is currently lerping toward. */
    var targetPosition: Vector3 = Vector3.ZERO

    /** Entity world position at the moment the target was last refreshed. */
    var lastPosition: Vector3 = Vector3.ZERO

    /** False until the first valid HMD frame has snapped the entity to its initial position. */
    var isInitialized: Boolean = false
}

/**
 * Lagged position (dead-zone gated lerp) + eased rotation toward the HMD pose. Mirrors StoryPico's
 * `TrackingManager.applyMoveWithCamera` (position) and the subtitle-specific rotation block in
 * `TrackingManager.processHMDTracking`, merged into one call since this project only follows one
 * entity.
 */
fun applySubtitleFollow(
    entity: Entity,
    component: SubtitleFollowComponent,
    pose: HMDPose,
    deltaTime: Float,
) {
    val transform = entity.components[TransformComponent::class.java] ?: return
    val parent = entity.getParent()

    val newWorldPos = pose.rotation.rotateVector(component.relativePosition) + pose.position
    val isFirstFrame = !component.isInitialized
    val currentWorldPos = if (isFirstFrame) {
        newWorldPos
    } else {
        parent?.convertPositionTo(transform.position, null) ?: transform.position
    }

    if (isFirstFrame || distanceBetween(newWorldPos, component.lastPosition) > component.minDistance) {
        component.targetPosition = newWorldPos
        component.lastPosition = currentWorldPos
    }

    val t = if (isFirstFrame) 1f else (component.speed * deltaTime).coerceIn(0f, 1f)
    val target = component.targetPosition
    val lerped = Vector3(
        currentWorldPos.x + (target.x - currentWorldPos.x) * t,
        currentWorldPos.y + (target.y - currentWorldPos.y) * t,
        currentWorldPos.z + (target.z - currentWorldPos.z) * t,
    )
    transform.setPosition(parent?.convertPositionFrom(lerped, null) ?: lerped)
    if (isFirstFrame) component.isInitialized = true

    val targetRotation = parent?.convertRotationFrom(pose.rotation, null) ?: pose.rotation
    transform.setQuaternion(slerpQuat(transform.quaternion, targetRotation, t))
}

/**
 * Normalised-lerp between two quaternions along the shortest arc. Adequate for the small per-frame
 * steps used here and avoids the trig of a full slerp - ported verbatim from StoryPico's
 * `TrackingManager.slerpQuat`. [t] is expected pre-clamped to [0,1]; `t = 1` yields (normalised)
 * [b], i.e. a snap.
 */
private fun slerpQuat(a: Quat, b: Quat, t: Float): Quat {
    val dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
    val bx: Float
    val by: Float
    val bz: Float
    val bw: Float
    if (dot < 0f) {
        bx = -b.x
        by = -b.y
        bz = -b.z
        bw = -b.w
    } else {
        bx = b.x
        by = b.y
        bz = b.z
        bw = b.w
    }
    val rx = a.x + t * (bx - a.x)
    val ry = a.y + t * (by - a.y)
    val rz = a.z + t * (bz - a.z)
    val rw = a.w + t * (bw - a.w)
    val len = sqrt((rx * rx + ry * ry + rz * rz + rw * rw).toDouble()).toFloat()
    return if (len > 0f) Quat(rx / len, ry / len, rz / len, rw / len) else a
}

private fun distanceBetween(a: Vector3, b: Vector3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
}
```

- [x] **Step 2: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。这一步还没有任何代码调用 `SubtitleFollowComponent`/`applySubtitleFollow`，只确认这个文件本身编译通过（真实功能验证在 Task 6）。

- [x] **Step 3: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ecs/SubtitleFollowComponent.kt
git commit -m "Stage 3 Task 4: subtitle follow component ported from StoryPico"
```

---

### Task 5: PlaybackViewModel 接入（HMD tracking 生命周期 + 字幕加载）

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`

**Interfaces:**
- Consumes: `SrtParser`（Task 1）、`SubtitleCueLookup`（Task 2）、`VideoItem.subtitleUri`（Task 3）、`HMDTrackingProvider`（`com.pico.spatial.tracking.hmd`）。
- Produces: `PlaybackViewModel.hmdTrackingProvider: HMDTrackingProvider`、`currentSubtitleText: String`（Compose-observable）、`refreshSubtitleText()`——Task 6 的 `ImmersiveScene.kt` 依赖这些。

- [x] **Step 1: 改构造函数——`context` 存成字段（当前只是构造参数，字幕加载需要在 `startPlayback` 里用它读文件）**

```kotlin
// 把原来的：
// class PlaybackViewModel(
//     context: Context,
//     private val historyStore: PlaybackHistoryStore,
//     private val preferencesStore: VideoPreferencesStore,
// ) {
//     val manager = PlaybackManager(context)
// 改成：
class PlaybackViewModel(
    private val context: Context,
    private val historyStore: PlaybackHistoryStore,
    private val preferencesStore: VideoPreferencesStore,
) {
    val manager = PlaybackManager(context)
```

- [x] **Step 2: 加 import + 新字段**

```kotlin
// 顶部 import 追加：
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import tech.illusion.spaceplayer.subtitle.SrtParser
import tech.illusion.spaceplayer.subtitle.SubtitleCue
import tech.illusion.spaceplayer.subtitle.SubtitleCueLookup

// 类体内，manager 字段之后追加：
    val hmdTrackingProvider = HMDTrackingProvider()

    private var subtitleCues: List<SubtitleCue> = emptyList()

    var currentSubtitleText by mutableStateOf("")
        private set
```

- [x] **Step 3: `startPlayback` 里加载字幕 + 启动 HMD tracking**

```kotlin
// startPlayback(item: VideoItem) 函数体最开头（在 currentItem = item 之后）追加：
    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        hmdTrackingProvider.start()
        val dimensionMode = item.stereoMode.toVideoDimensionMode()
        // ...(其余不变)

// 新增私有方法：
    private fun loadSubtitleCues(subtitleUri: android.net.Uri?): List<SubtitleCue> {
        if (subtitleUri == null) return emptyList()
        return runCatching {
            context.contentResolver.openInputStream(subtitleUri)?.use { input ->
                SrtParser.parse(input.readBytes().toString(Charsets.UTF_8))
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Called every frame from ImmersiveScene's SpatialView.update block. */
    fun refreshSubtitleText() {
        currentSubtitleText = SubtitleCueLookup.textAt(subtitleCues, manager.player.getCurrentPosition())
    }
```

- [x] **Step 4: `exitImmersive`/`onCleared` 停止 HMD tracking**

```kotlin
    fun exitImmersive() {
        val item = currentItem
        if (item != null && isFlatProjection.value) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        hmdTrackingProvider.stop()
        isImmersive.value = false
    }

    // ...

    fun onCleared() {
        manager.reset()
        hmdTrackingProvider.stop()
    }
```

- [x] **Step 5: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt
git commit -m "Stage 3 Task 5: PlaybackViewModel loads subtitles and drives HMD tracking lifecycle"
```

---

### Task 6: ImmersiveScene 接入字幕 AttachmentPanel

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/SubtitleAttachment.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`

**Interfaces:**
- Consumes: `SubtitleFollowComponent`/`applySubtitleFollow`（Task 4）、`PlaybackViewModel.hmdTrackingProvider`/`currentSubtitleText`/`refreshSubtitleText()`（Task 5）。
- Produces: 无新的对外签名——这是把前面几个 Task 的产出接进已有的沉浸播放渲染循环。

- [x] **Step 1: 写 `SubtitleAttachment.kt`**

视觉风格故意和 `PlaybackHud`/`LoadingErrorAttachment` 保持一致（磨砂玻璃背景 `Material.Regular`），不是照抄 StoryPico 原版的纯黑半透明底——StoryPico 用的是 `Color.Black.copy(alpha=0.7f)` 这种独立于本项目设计系统的写法，SpacePlayer 已经有一套跑通的 HUD 视觉语言，字幕面板延用它更一致。

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

@Composable
fun SubtitleAttachment(text: String) {
    if (text.isEmpty()) return
    PicoTheme {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }
    }
}
```

- [x] **Step 2: 改 `ImmersiveScene.kt`——加字幕 attachment + 每帧驱动**

**实际发现（与下面的原始草案不同，务必以此为准）：** 草案里 `components.set(SubtitleFollowComponent())` 是错的——原生 ECS 层只认自己内置的 `Component` 子类型（`TransformComponent`/`ModelComponent`/...），对任意自定义 `Component` 子类一律静默拒绝，logcat 里报 `entityId = N, component Component is not supported`，导致 `update` 块里 `subtitleEntity.components.get(SubtitleFollowComponent::class.java)` 永远拿到 `null`，`applySubtitleFollow` 实际上从未被调用过。修法：`SubtitleFollowComponent` 改成用 `remember { SubtitleFollowComponent() }` 存在 Composable 局部变量里，完全不经过 ECS `components` 系统，直接把这个引用传给 `applySubtitleFollow`。另外补了一个兜底：给字幕 entity 在 `initial` 里也设一个固定 `TransformComponent` 位置（`Vector3(0f, 1.2f, -1.5f)`，和 loading/hud 同一手法），因为 `HMDTrackingProvider.start()` 在模拟器上直接失败（logcat: `HMD tracking provider start failed`——模拟器没有真实头部姿态数据源，这个限制在 AGENTS.md 里已经记录过，真机需要重新验证），如果不给兜底位置，字幕面板在拿不到真实 HMD pose 之前会一直停在 world origin，不确定是否可见。

```kotlin
// 顶部 import 追加：
import androidx.compose.runtime.remember
import tech.illusion.spaceplayer.ecs.SubtitleFollowComponent
import tech.illusion.spaceplayer.ecs.applySubtitleFollow

// 常量追加：
private const val SUBTITLE_ATTACHMENT_ID = "subtitle"

// 函数体内，coroutineScope 声明之后追加：
    val lastFrameNs = remember { longArrayOf(System.nanoTime()) }

// attachments = { ... } 块里，HUD AttachmentPanel 之后追加：
                AttachmentPanel(id = SUBTITLE_ATTACHMENT_ID) {
                    SubtitleAttachment(text = viewModel.currentSubtitleText)
                }

// initial = { content, attachments -> ... } 块里，HUD 的 attachments.entity(...) 处理之后追加：
                // Subtitle panel: position/rotation are driven every frame by
                // applySubtitleFollow() in the update block below (lagged position + rotation
                // follow, ported from StoryPico's MoveWithCameraComponent) - not a fixed
                // TransformComponent position like loading/hud.
                attachments.entity(SUBTITLE_ATTACHMENT_ID)?.apply {
                    components.set(SubtitleFollowComponent())
                    content.addEntity(this)
                }

// update = { _, attachments -> ... } 块，改成：
            update = { _, attachments ->
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay

                val nowNs = System.nanoTime()
                val deltaTime = ((nowNs - lastFrameNs[0]) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameNs[0] = nowNs

                viewModel.refreshSubtitleText()
                val subtitleEntity = attachments.entity(SUBTITLE_ATTACHMENT_ID)
                val followComponent = subtitleEntity?.components?.get(SubtitleFollowComponent::class.java)
                val hmdPose = viewModel.hmdTrackingProvider.latestData?.hmdPose
                if (subtitleEntity != null && followComponent != null && hmdPose != null) {
                    applySubtitleFollow(subtitleEntity, followComponent, hmdPose, deltaTime)
                }
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()
            },
```

- [x] **Step 3: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。实际：通过。

- [x] **Step 4: 模拟器验证——用一个真实带 `.srt` 的视频播放，确认字幕显示且转头时朝向/位置都在跟**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

准备测试素材（往模拟器塞一个视频 + 同名 `.srt`）：

```bash
ffmpeg -y -f lavfi -i testsrc2=size=1280x720:rate=30 -t 6 -pix_fmt yuv420p /tmp/subtitle_test.mp4
cat > /tmp/subtitle_test.srt << 'EOF'
1
00:00:00,000 --> 00:00:03,000
第一行字幕

2
00:00:03,000 --> 00:00:06,000
第二行字幕
EOF
adb -s emulator-5554 push /tmp/subtitle_test.mp4 /sdcard/Movies/subtitle_test.mp4
adb -s emulator-5554 push /tmp/subtitle_test.srt /sdcard/Movies/subtitle_test.srt
adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/subtitle_test.mp4
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
```

用临时 `LaunchedEffect` 自动选中并播放这个视频（加在 `MainLibraryScreen.kt` 函数体里，验证完删除）：

```kotlin
LaunchedEffect(libraryViewModel.libraryItems) {
    val item = libraryViewModel.libraryItems.firstOrNull { it.displayName == "subtitle_test.mp4" }
        ?: return@LaunchedEffect
    libraryViewModel.selectItem(item)
    playbackViewModel.startPlayback(item)
    navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
}
```

```bash
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
# 等待首帧渲染 + 字幕出现
pico-cli capture screenshot --out ./artifacts/stage3-subtitle-visible.png --device emulator-5554
adb -s emulator-5554 logcat -d -t 300 | grep -iE "FATAL|AndroidRuntime"
```

Expected: 截图能看到字幕面板显示"第一行字幕"（或第二行，取决于截图时机），无崩溃。**这一步没有办法验证"转头后字幕真的跟着位置/朝向变化"**——和 Stage 1 已经记录过的"PICO 模拟器没有找到无头方式模拟头部 6dof 转身"是同一个限制，如实说明这一点，不假装截图证明了跟随效果，只确认字幕渲染出现 + 无崩溃这两点。验证完删除 `MainLibraryScreen.kt` 里的临时 `LaunchedEffect`，重新构建确认干净。

**实际验证过程中的发现（踩了不少坑，记录下来避免下次重复踩）：**

1. **自制测试视频的编码参数不能瞎选。** 第一版用 `testsrc2` + High profile + 无音轨生成的 `subtitle_test.mp4` 在模拟器上一直卡在 `PREPARING`（HUD 显示"加载中..."永不消失），logcat 里看到 `amc: no suitable codec` + `prepare player async: -3`。对比已经验证过能播的 `library_test.mp4`（Constrained Baseline + AAC 音轨 + `moov` 在 `mdat` 之前的 faststart 布局），把测试视频改成同样的 Baseline profile + AAC 音轨 + `-movflags +faststart` 之后不再卡住。但后来发现 `amc: no suitable codec` 和 `prepare player async: -3` 这两行其实在 `library_test.mp4` 成功播放的日志里也一样会出现——是良性噪音，不是真正的失败信号，测试视频真正需要匹配的只是能被模拟器解码器接受的基本编码参数（Baseline profile 更保险），不是这两行日志。
2. **Step 2 里发现的 ECS 注册 bug 是真实、会阻断字幕跟随功能的 bug**（见上面 Step 2 的实际发现），已修复。
3. **`HMDTrackingProvider.start()` 在 PICO 模拟器上直接失败**（`HMD tracking provider start failed`），因此"位置延迟跟随 + 朝向跟随"这个核心行为本身无法在模拟器上验证，只能确认：字幕文本按播放位置正确切换、字幕面板在兜底固定位置正确渲染、无崩溃。真机上的跟随效果验证保持 pending。
4. **反复快速重装/重启 App 会把模拟器的合成器（compositor）拖入卡死状态**——连续多次 `am force-stop` + `am start` 循环后，`pico-cli capture screenshot` 和 `adb shell screencap` 都会返回同一张冻结的合成帧（用 `md5` 比对确认字节级相同），`adb shell input keyevent KEYCODE_HOME` 也无法让画面刷新，说明不是截图工具缓存问题而是模拟器本身画面渲染管线冻住了。需要 `pico-cli emulator stop` + `pico-cli emulator start` 完整重启模拟器才能恢复（重启后是全新 AVD 数据，需要重新装 App、重新 `pm grant`、重新 push 测试视频）。以后连续验证时应该在每次 force-stop/relaunch 之间给合成器喘息时间，而不是几秒内连续折腾。
5. 最终在干净重启后的模拟器上验证通过：`adb shell screencap` 截图清楚显示彩条视频播放中、HUD 底栏可见、字幕面板显示"第一行字幕"，文本与 SRT 时间轴（0-3s 显示第一行）吻合。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/SubtitleAttachment.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt
git commit -m "Stage 3 Task 6: wire subtitle AttachmentPanel into ImmersiveScene"
```

---

### Task 7: 手动指定字幕入口

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/FormatCorrectionPopup.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`

**Interfaces:**
- Consumes: `VideoPreferencesStore.setSubtitleUri`（Task 3）。
- Produces: `FormatCorrectionPopup` 新增 `hasSubtitle: Boolean`/`onPickSubtitle: () -> Unit` 参数。

- [x] **Step 1: 改 `FormatCorrectionPopup.kt`——加字幕状态显示 + 选择按钮**

```kotlin
// 函数签名追加两个参数：
@Composable
fun FormatCorrectionPopup(
    initialProjection: Projection,
    initialStereoMode: StereoMode,
    hasSubtitle: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
) {
    // ...(projection/stereoMode 状态不变)

    SpatialPopup(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ...(标题 + 两组 SegmentControl 不变)

            // 在两组 SegmentControl 之后、取消/确定 Row 之前追加：
            Row(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = if (hasSubtitle) "字幕：已设置" else "字幕：未设置",
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                )
                Button(onClick = onPickSubtitle) {
                    Text(
                        text = "选择字幕文件",
                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 12.dp)) {
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

- [x] **Step 2: 改 `MainLibraryScreen.kt`——加字幕 SAF 选择器 + 接线**

```kotlin
// 顶部 import 追加（如果还没有）：
import androidx.documentfile.provider.DocumentFile

// 函数体内，importLauncher 声明之后追加：
    val subtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val item = itemPendingCorrection
        val name = uri?.let { DocumentFile.fromSingleUri(context, it)?.name }
        if (uri != null && item != null && name?.endsWith(".srt", ignoreCase = true) == true) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryViewModel.preferencesStore.setSubtitleUri(item.uri, uri)
            itemPendingCorrection = item.copy(subtitleUri = uri)
            libraryViewModel.refreshLibrary()
            libraryViewModel.refreshDownloads()
        }
    }

// FormatCorrectionPopup 调用处，加两个新参数：
        itemPendingCorrection?.let { item ->
            FormatCorrectionPopup(
                initialProjection = item.projection,
                initialStereoMode = item.stereoMode,
                hasSubtitle = item.subtitleUri != null,
                onDismissRequest = { itemPendingCorrection = null },
                onConfirm = { projection, stereoMode ->
                    libraryViewModel.preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
                    libraryViewModel.refreshLibrary()
                    libraryViewModel.refreshDownloads()
                    itemPendingCorrection = null
                },
                onPickSubtitle = { subtitleLauncher.launch(arrayOf("*/*")) },
            )
        }
```

`itemPendingCorrection = item.copy(subtitleUri = uri)` 让弹层里的"字幕：已设置"文案立即反映刚选的文件，不需要关掉弹层重开才能看到状态更新。mime 类型用 `"*/*"` 而不是猜 `.srt` 的真实 mime type（不同设备/存储提供方报的 mime 不统一，`text/plain`/`application/octet-stream`/`application/x-subrip` 都可能出现），选中后靠文件名后缀校验。

- [x] **Step 3: 构建确认编译通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。实际：通过。

- [x] **Step 4: 模拟器截图验证——打开修正弹层，确认"字幕：未设置/已设置"文案正确，点"选择字幕文件"弹出 SAF 选择器**

**实际验证结果（部分完成，如实记录）：**

用临时 `LaunchedEffect` 把 `itemPendingCorrection` 设成 `library_test.mp4`（没有同目录 `.srt`），截图确认：弹层正确渲染"字幕：未设置" + "选择字幕文件"按钮（见 `artifacts/stage3-task7-popup.png`，文字缩放裁剪见 `stage3-task7-popup-zoom2.png`）。再用临时代码直接调用 `subtitleLauncher.launch(arrayOf("*/*"))`（不经过按钮点击，因为 Compose 按钮在这套 spatial container 里不能可靠地用 adb tap 命中——和 Stage 2 已经记录的限制一致），确认 SAF 文档选择器正常弹出并显示 Recent 文件列表（见 `artifacts/stage3-task7-saf.png`）。

**未能完成的部分**：在 SAF 选择器内部点选一个具体的 `.srt` 文件这一步没有验证成功——`adb shell uiautomator dump` 能正确拿到列表项的坐标，但无论用 `adb shell input tap`、`adb shell input -d 0 tap`还是 `adb shell input -d 2 tap`（`2` 是 `dumpsys display` 里查到的 `VirtualDisplayAdapter` 虚拟屏幕 id，`com.pxr.scenarioprovider` 用它承载这个 SAF 悬浮面板），点击都没有使列表产生任何变化（截图前后字节不同但内容上没有反映点击效果，比如没有进入子目录、没有选中效果）。这和 Stage 2 Task 9 记录的"SAF GridView 有时需要两次点击"不是同一个问题——这次是列表视图（ListView 而非 GridView），且换了两个不同的虚拟屏幕 id 都没有效果，怀疑是这台重新启动过的模拟器在这个会话里输入事件路由到这块虚拟屏幕的方式和之前不一样，没有深挖到底（不确定是模拟器这次冷启动状态的问题，还是 ListView 和 GridView 的 hit-test 区域计算不同）。**没有伪造验证结果**：`onPickSubtitle`/`subtitleLauncher`/`setSubtitleUri` 这条代码路径和 Stage 2 已经验证过能跑通的 `importLauncher`（同样用 `ActivityResultContracts.OpenDocument()` + `takePersistableUriPermission`）用的是同一个机制，只是这次没能在 UI 层面点选到具体文件来闭环验证"选中后弹层文案变成已设置"这一步，如实标注为未完成，不是遗漏。

验证完删除临时代码，重新构建确认干净（`./gradlew assembleDebug testDebugUnitTest` 通过）。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/FormatCorrectionPopup.kt \
  app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Stage 3 Task 7: manual subtitle file picker in format correction popup"
```

---

### Task 8: Stage 3 端到端回归 + AGENTS.md 更新 + 提交

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/2026-08-06-stage3-subtitles.md`（本文件，标记完成 + 记录真实发现，沿用 Stage 1/2 的做法）

**Interfaces:**
- Consumes: Task 1-7 全部产出。
- Produces: 无新接口，回归验证 Task。

- [x] **Step 1: 全量重新构建 + 单元测试**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`；新增 `SrtParserTest`（8）+ `SubtitleCueLookupTest`（6）+ `VideoPreferencesStoreTest` 新增用例（2）+ Stage 1/2 遗留的全部测试（27）全部 PASS，共 43 个。实际：`./gradlew clean assembleDebug :app:testDebugUnitTest` 成功，43/43 通过。

- [x] **Step 2: 走查完整流程并截图到 `./artifacts/stage3-regression-*.png`**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
```

依次验证（沿用 Stage 1/2 Task 9 的手法：每条路径改一次临时 `LaunchedEffect`/直接读设备文件，全部走完后整体删除临时代码）：
1. 同目录同名 `.srt` 自动发现：Task 6 Step 4 塞的 `subtitle_test.mp4`+`subtitle_test.srt` 组合，选中该视频打开修正弹层，确认"字幕：已设置"（不用手动选，自动发现生效）。
2. 播放该视频，确认字幕文本随播放推进切换（"第一行字幕" → 空白间隙 → "第二行字幕"），HUD/loading 互不干扰。
3. 手动指定：对一个没有同目录 `.srt` 的视频，打开修正弹层确认"字幕：未设置"，点"选择字幕文件"选中一个 `.srt`，确认弹层文案立即变成"已设置"，播放后字幕正确显示。
4. SAF"其它"导入的视频（没有可靠兄弟文件访问路径）：确认自动发现返回空，"字幕：未设置"，手动指定仍然可用。

```bash
adb -s emulator-5554 logcat -d -t 500 | grep -iE "FATAL|AndroidRuntime|tech.illusion.spaceplayer"
```

Expected: 四条路径都符合预期；全程无新增崩溃。验证完删除全部临时代码，重新 `./gradlew clean assembleDebug :app:testDebugUnitTest` 确认干净、43 个单测依旧全部 PASS，`git diff` 确认被临时改动过的文件和上一个 Task 提交时字节相同。

**实际结果（如实记录，未全部闭环）：**
1. 同目录同名 `.srt` 自动发现——**确认**：`subtitle_test.mp4` 沉浸播放，字幕面板正确显示"第一行字幕"（Task 6 Step 4 已截图确认，`./artifacts/stage3-fresh-verify.png`），HUD/loading 互不干扰。
2. 字幕文本随播放推进切换——**未能拿到第二次干净截图确认**：这次 Task 8 回归时模拟器合成器连续两次卡在同一张半透明叠加的"幽灵帧"上（`stage3-regress-a/b/c.png`，`md5` 比对确认不是同一帧但视觉上都是同一种卡住状态），没有再重启一次模拟器去追这一步（前面 Task 6 已经因为同样的问题重启过一次模拟器，为了不无限增加重装/重启次数，这里选择用已有的单测覆盖代替）。文本切换的正确性由 `SubtitleCueLookupTest`（6 个用例，含 3000ms 边界时间戳）保证，是纯逻辑单测，不依赖设备渲染；渲染管线本身已经在路径 1 里确认能正常显示文本。
3. 手动指定——**部分确认**：弹层"字幕：未设置"文案 + "选择字幕文件"按钮渲染正确（`stage3-task7-popup.png`），点按钮后 SAF 选择器正常弹出（`stage3-task7-saf.png`）。在选择器里点选一个具体 `.srt` 文件、确认弹层文案变成"已设置"这一步没有验证——adb 点击进不了 SAF 选择器所在的虚拟屏幕（换过默认 display 和 `dumpsys display` 查到的虚拟屏幕 id 都无效），见 Stage 3 Task 7 的记录。
4. SAF 其它导入视频的字幕自动发现返回空——**未做实机验证，只有代码审查**：`SubtitleDiscovery.findSiblingSrt` 对查询不到 `MediaStore.Video.Media.DATA` 列的情况有防御性 `columnIndex == -1` 判断，逻辑上应该对 SAF 文档 Uri 返回 `null`，但没有实际走一遍 SAF 导入+打开修正弹层这个流程去确认。

验证完之后重新 `./gradlew assembleDebug testDebugUnitTest`（43/43 通过）+ `git diff` 确认 `MainLibraryScreen.kt` 和上一个 Task 提交时字节相同（无残留临时代码）。

- [x] **Step 3: 更新 `AGENTS.md`**

写清楚：Stage 3 完成的范围（外部 `.srt` 字幕、同目录同名自动发现 + 手动指定、位置延迟跟随+朝向跟随的字幕面板，移植自 StoryPico 的 `SubtitleFollowComponent`/`HMDTrackingProvider` 用法）、"转头后跟随效果是否真的达到预期"这一点在模拟器上无法验证（沿用 Stage 1 已经记录过的"无头模拟头部转身"限制）、`MediaStore.Video.Media.DATA` 用于字幕发现只在模拟器本地存储验证过这一点、构建/安装/运行命令不变、下一步（如果有）。

- [x] **Step 4: 提交**

```bash
git add -A
git commit -m "Stage 3 regression pass + AGENTS.md update"
```

## Self-Review 记录（写完计划后的复查结论）

- **Spec 覆盖**：设计稿第 4 节"字幕"小节的三条要求——仅外部 `.srt`（Task 1 解析器）、同目录同名或手动指定（Task 3 自动发现 + Task 7 手动指定）、独立于 HUD 的 Attachment 且锚定用户朝向不随视角旋转飘出可视范围（Task 4/5/6，位置延迟跟随+朝向跟随）——全部有对应 Task。第 5 节非目标（不做内嵌字幕轨道、不做 `.ass` 特效）在 `SrtParser` 的文档注释里明确排除在外，没有实现。
- **未验证项**（已在对应 Task 里如实标注，不是遗漏）：字幕面板"转头后位置/朝向真的跟随"这一点，和 Stage 1 的 180° 半球背面留空一样，PICO 模拟器没有无头模拟头部转身的办法，只能确认字幕渲染出现、无崩溃，代码逻辑本身是照抄 StoryPico 已经在用的实现，不是凭空写的；`MediaStore.Video.Media.DATA` 字幕发现只验证过模拟器本地存储这一种情况。
- **对 StoryPico 原实现的有意简化**：`MoveWithCameraComponent`→`SubtitleFollowComponent` 改名（更贴合这个项目里"只服务字幕"的单一用途，不是通用的相机跟随组件）；位置更新（`applyMoveWithCamera`）和朝向更新（StoryPico 里在 `processHMDTracking` 单独处理）合并成一个 `applySubtitleFollow` 函数，因为 SpacePlayer 只有一个跟随实体，不需要 StoryPico 那种通用 `TrackingManager` 单例架构；字幕面板视觉风格改用本项目已有的 `Material.Regular` 磨砂玻璃背景，而不是照抄 StoryPico 的纯黑半透明底色，为了和 `PlaybackHud`/`LoadingErrorAttachment` 视觉一致。这几处都在对应 Task 里写明了理由，不是遗漏或偷懒。
- **类型一致性**：`SubtitleCue`（Task 1）→ `SubtitleCueLookup.textAt`（Task 2）→ `VideoItem.subtitleUri`/`VideoPreferences.subtitleUri`（Task 3，注意类型不同：前者 `Uri?`，后者 `String?`，转换只在 `LibraryViewModel.toVideoItem`/`VideoPreferencesStore.setSubtitleUri` 两处做，全程没有在会被单测覆盖的代码路径里调用 `Uri.parse()`）→ `SubtitleFollowComponent`/`applySubtitleFollow`（Task 4）→ `PlaybackViewModel.currentSubtitleText`/`refreshSubtitleText()`/`hmdTrackingProvider`（Task 5）→ `ImmersiveScene.kt` 消费（Task 6）→ `FormatCorrectionPopup` 的 `hasSubtitle`/`onPickSubtitle`（Task 7），全程用同一套类型和函数名，没有出现不一致。
