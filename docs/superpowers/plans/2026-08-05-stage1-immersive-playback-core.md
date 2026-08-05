# SpacePlayer Stage 1: 项目骨架 + 沉浸播放核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 `SpacePlayer` 项目骨架，并用一个硬编码测试视频把"平面 → 环境化平面（电影院/星空/海景，可实时切换）→ 180°半球 → 360°球体"这条最高风险的沉浸播放链路完整跑通，不含真实文件库 UI（那是 Stage 2）、不含字幕（那是 Stage 3）。

**Architecture:** 单一 `DefaultWindowContainer`（本阶段只是一个手动选测试用例的占位主窗口）+ 单一共享 `Stage(id = "ImmersiveStage")`。同一个 `CypressMediaPlayer` 通过 `VideoPlayerComponent` 驱动一个"银幕"实体（平面 `ScreenEntity` / 半球 `HemisphereEntity` / 球体 `SphereEntity` 三选一 `enabled`），`EnvironmentLayer` 三个天空盒实体互斥且可在播放中随时切换、不触碰播放器。所有跨 `WindowContainer`/`Stage` 共享的状态放在一个 Koin session-scope 的 `PlaybackViewModel` 里（复用官方 "Play spatial video in an app" 示例的做法）。

**Tech Stack:** PICO Spatial SDK（Kotlin + Jetpack Compose + SpatialUI）、`VideoPlayerComponent` / `CypressMediaPlayer`、Koin（跨容器共享 ViewModel）、`pico-cli` + PICO Emulator 做构建/安装/截图验证。

## Global Constraints

- 包名固定为 `tech.illusion.spaceplayer`；`compileSdk`/`minSdk`/`targetSdk` 以 `pico-cli project create` 生成的模板为准，不手动改动。
- 播放统一使用 `VideoPlayerComponent` + SDK 内置 `CypressMediaPlayer`；不引入 `VideoComponent`、ExoPlayer 或任何第三方播放器。
- 沉浸容器只声明一个非默认 `Stage(id = "ImmersiveStage")`；不为不同环境/投影类型声明多个 Stage。
- `openStage` 一律传 `StageStyle.Full`（虚拟内容完全接管显示，真实环境 Video Seethrough 关闭）。
- 所有 2D UI 必须用 SpatialUI（`com.pico.spatial.ui.*`）组件并整体包在 `PicoTheme { }` 内；禁止 `androidx.compose.material`/`material3`。
- 每个 Task 完成后必须执行 `./gradlew assembleDebug`，再用 `pico-cli app install` + `pico-cli app launch` + `pico-cli capture screenshot` 在模拟器（`Pico_Emulator_0_13` 或当前可用 AVD）上跑一遍，并用 `adb logcat -b crash -d` 确认没有新崩溃——这是本工作区里 `spatial-app-dev-workflow` 技能定义的标准验证节奏，Task 里不再重复解释，只写具体命令。
- 涉及 `StageEnvironmentLightingComponent` 精确构造参数、以及球体/半球网格资源的地方，本计划**不臆造 API 签名**——这两处 PICO Spatial SDK 的官方文档库在写这份计划时暂时无法访问（见 Task 6/8 里的说明），实现者必须在动手写那一小段代码前，用 `spatial-sdk-guideline` 技能或 pico-spatial-knowledge 查一遍当时的真实签名。

## File Structure

```
SpacePlayer/
├── app/src/main/java/tech/illusion/spaceplayer/
│   ├── platform/
│   │   ├── LaunchActivity.kt          # pico-cli 生成，入口 Activity（Task 1 产出，之后基本不改）
│   │   └── SpatialApplication.kt      # pico-cli 生成 + Task 4 加 Koin 初始化
│   ├── Main.kt                        # mainApp DSL：DefaultWindowContainer + Stage("ImmersiveStage")
│   ├── di/
│   │   └── PlaybackModule.kt          # Koin module，提供 session-scoped PlaybackViewModel（Task 4）
│   ├── playback/
│   │   ├── PlaybackState.kt           # 播放状态枚举（Task 2）
│   │   ├── PlaybackManager.kt         # CypressMediaPlayer 生命周期封装（Task 2）
│   │   ├── Projection.kt              # 投影类型枚举（Task 3）
│   │   ├── StereoMode.kt              # 立体格式枚举 + 到 VideoDimensionMode 的映射（Task 3）
│   │   └── Environment.kt             # 环境枚举：CINEMA / STARRY_SKY / SEASIDE（Task 8）
│   ├── ecs/
│   │   └── PlaybackEntityAssembler.kt # 组装 ScreenEntity/HemisphereEntity/SphereEntity/EnvironmentLayer 实体（Task 4/6/7/8）
│   └── ui/
│       ├── PlaceholderMainScreen.kt   # 占位主窗口：测试用例选择器 + 开始播放（Task 1 stub → Task 4/6/7/8 接线）
│       ├── ImmersiveScene.kt          # Stage 内容：环境层 + 银幕实体 + Attachment 挂载（Task 4/8）
│       ├── PlaybackHud.kt             # 播放控制条 AttachmentPanel（Task 5）
│       ├── LoadingErrorAttachment.kt  # 首帧门控的 loading/error 覆盖层（Task 5）
│       └── PlaybackViewModel.kt       # 共享状态：entity 引用、PlaybackManager、当前环境（Task 4 起持续扩展）
├── app/src/main/assets/videos/
│   ├── sample_flat_test.mp4           # Task 2 引入：5-10s 单目平面测试片段
│   ├── sample_360_test.mp4            # Task 6 引入：360° 单目等距柱状测试片段
│   └── sample_180_test.mp4            # Task 7 引入：180° 单目半球测试片段
├── app/src/test/java/tech/illusion/spaceplayer/playback/
│   └── StereoModeMappingTest.kt       # Task 3 的 JVM 单元测试
└── docs/superpowers/plans/2026-08-05-stage1-immersive-playback-core.md   # 本文件
```

---

### Task 1: 项目骨架（pico-cli 引导）

**Files:**
- Create: 整个 `SpacePlayer/app/`、`SpacePlayer/gradle/`、`SpacePlayer/settings.gradle.kts`、`SpacePlayer/build.gradle.kts`、`SpacePlayer/gradle.properties`（由 `pico-cli project create` 生成，不手写）
- Modify: `SpacePlayer/AGENTS.md`（新建，参考 SeasonsApp/FileSendApp 的 AGENTS.md 风格）

**Interfaces:**
- Consumes: 无（第一个 Task）
- Produces: 一个可 `assembleDebug`/`installDebug`/启动的空壳应用，包名 `tech.illusion.spaceplayer`，`DefaultWindowContainer` 显示一个空白/占位画面。后续 Task 在这个骨架上继续。

- [x] **Step 1: 用 `pico-cli` 在当前工作区里生成项目**

实际支持的 flag（`pico-cli project create --help`）：`--dir --name --package --template <planar|volumetric|stage> --sdk --force`。

选了 `--template stage`（而不是空白模板——CLI 没有"完全空白"这个模板选项；`stage` 对应
"immersive space/spatial interaction from the start"，最贴近本项目核心是沉浸式 Stage 播放这一事实，见
`spatial-app-onboarding` 技能 template-playbook 第 3 节的路由表）：

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpacePlayer
pico-cli project create --dir . --name SpacePlayer --package tech.illusion.spaceplayer --template stage --force
```

生成结果：`Main.kt` 是 `DefaultStage { PicoTheme { HomeStage() } }`，`content/HomeStage.kt` 里有一个加载
`asset://box.usdz` 的示例实体 + 文字 `AttachmentPanel`。**没有** `DefaultWindowContainer`——这是 Task 4 要自己加的。

- [x] **Step 2: SpatialUI 自检**

按 `spatial-app-onboarding` 技能的硬性规则，确认生成的入口 Composable 树用 `PicoTheme { }` 包裹；在 `app/src/main/java` 下搜索：

```bash
grep -rn "androidx.compose.material" app/src/main/java
grep -rn "MaterialTheme" app/src/main/java
```

两条命令都应无输出。如果生成模板带了 Material 依赖或用法，改成 SpatialUI 等价组件（`com.pico.spatial.ui.design.*`），并从 `app/build.gradle.kts` 里移除 Material 依赖。

- [x] **Step 3: 构建、安装、启动、截图验证**

实际执行时发现三个本机环境问题（已写进 `AGENTS.md` 的"本机环境注意事项"，此处记录结论）：

1. 系统默认 JDK 25 和 Gradle 8.13 的 Kotlin DSL 解析不兼容，需要 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（Android Studio 自带 JBR 21）再跑 `./gradlew`。
2. 需要手写 `local.properties`（`sdk.dir=/Users/zohar/Library/Android/sdk`，`spatial.tools.dir=/Users/zohar/Library/PICO/sdk`），这个文件不提交。
3. 本机连接的真机是 API 34，装不了 `compileSdk 35` 的 APK（`INSTALL_FAILED_OLDER_SDK`），改用 `Pico_Emulator_0_13` 模拟器（API 36）。

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpacePlayer
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli emulator start --avd Pico_Emulator_0_13 --wait-timeout 180 -y   # 若模拟器未运行
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
adb -s emulator-5554 logcat -b crash -d
pico-cli capture screenshot --out ./artifacts/task1-scaffold.png --device emulator-5554
```

实际结果：`assembleDebug` 成功；`pico-cli project create --template stage` 生成的默认容器是
`DefaultStage`（不是空白 `DefaultWindowContainer`——`stage` 模板本来就没有这个选项，模板路由见
`spatial-app-onboarding` 的 template-playbook），截图显示脚手架自带的 `HomeStage()` 内容：一个盒子模型 +
"Hello, Spatial SDK!" 文字面板；`adb logcat -b crash -d` 无新增崩溃。把默认容器改造成设计稿要求的
"平面主窗口 + 非默认 ImmersiveStage" 形状是 Task 4 的工作，不是 Task 1。

- [x] **Step 4: 写项目级 `AGENTS.md`**

已写：项目是什么/为什么这么设计/**本机环境注意事项**（JDK 25 不兼容、`local.properties` 内容、真机 API 34 装不了、改用模拟器）/关键文件（含真实 import 路径，比设计文档写作时凭旧版文档猜测的更准）/已用 SDK 能力/构建命令/下一步。

- [x] **Step 5: 提交**

```bash
git add -A
git commit -m "Bootstrap SpacePlayer via pico-cli scaffold"
```

---

### Task 2: 播放状态与 `PlaybackManager`（CypressMediaPlayer 封装）

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackState.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/playback/PlaybackManager.kt`
- Create: `app/src/main/assets/videos/sample_flat_test.mp4`（任意 5-10 秒、H.264、单目平面的小体积测试片段；来源不限，只要能塞进 `assets/` 且能被 `CypressMediaPlayer` 解码）

**Interfaces:**
- Consumes: SDK 的 `CypressMediaPlayer` / `CypressMediaPlayerCallback` / `CypressMediaPlayerErrorCode`（PICO Spatial SDK 内置类，`use-video-player-component.md` 文档已确认的 API：`prepareAsync()`/`play()`/`pause()`/`resume()`/`stop()`/`isPlaying()`/`setLoop()`/`setVolume()`/`getVolume()`/`getCurPosition()`/`getDuration()`/`setDataSource()`/`registerCypressMediaPlayerCallback()`/`unregisterCypressMediaPlayerCallback()`/`reset()`/`close()`）
- Produces:
  ```kotlin
  enum class PlaybackState { INIT, PREPARING, READY, PLAYING, PAUSED, ERROR }

  class PlaybackManager(private val context: Context) {
      val player: CypressMediaPlayer
      var state: PlaybackState        // Compose 可观察（mutableStateOf）
      var duration: Long              // 毫秒
      var hasFirstFrameRendered: Boolean

      fun setup(assetPath: String)    // 从 assets:// 加载，prepareAsync
      fun play()
      fun pause()
      fun resume()
      fun seekTo(ms: Long)
      fun setVolume(volume: Float)
      fun reset()                     // 停止+释放监听，供切换视频/退出沉浸时调用
  }
  ```
  后续 Task（4/5/6/7）都通过 `PlaybackManager.player` 拿到 `CypressMediaPlayer` 实例喂给 `VideoPlayerComponent`。

- [x] **Step 1: 写 `PlaybackState.kt`**

```kotlin
package tech.illusion.spaceplayer.playback

enum class PlaybackState { INIT, PREPARING, READY, PLAYING, PAUSED, ERROR }
```

- [x] **Step 2: 写 `PlaybackManager.kt`**

```kotlin
package tech.illusion.spaceplayer.playback

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.video.CypressMediaPlayer
import com.pico.spatial.core.video.CypressMediaPlayerCallback
import com.pico.spatial.core.video.CypressMediaPlayerErrorCode

private const val TAG = "PlaybackManager"
private const val INIT_VOLUME = 0.8f

class PlaybackManager(private val context: Context) {

    val player: CypressMediaPlayer = CypressMediaPlayer()

    var state by mutableStateOf(PlaybackState.INIT)
        private set
    var duration by mutableStateOf(1L)
        private set
    var hasFirstFrameRendered by mutableStateOf(false)
        private set

    private val callback = object : CypressMediaPlayerCallback {
        override fun onPrepared() {
            state = PlaybackState.READY
            duration = player.getDuration()
            player.play()
            state = PlaybackState.PLAYING
        }
        override fun onStarted() {
            state = PlaybackState.PLAYING
        }
        override fun onCompleted() {
            player.seekTo(0)
        }
        override fun onSeekToCompleted() {}
        override fun onPaused() {
            state = PlaybackState.PAUSED
        }
        override fun onStopped() {}
        override fun onVideoSizeChanged(width: Int, height: Int) {
            hasFirstFrameRendered = true
        }
        override fun onError(error: CypressMediaPlayerErrorCode) {
            Log.e(TAG, "onError code ${error.code}")
            state = PlaybackState.ERROR
        }
    }

    fun setup(assetPath: String) {
        state = PlaybackState.PREPARING
        duration = 1L
        hasFirstFrameRendered = false
        player.registerCypressMediaPlayerCallback(callback)
        val afd = context.assets.openFd(assetPath)
        player.setDataSource(afd)
        afd.close()
        player.setVolume(INIT_VOLUME)
        player.prepareAsync()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun resume() = player.resume()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setVolume(volume: Float) = player.setVolume(volume)

    fun reset() {
        player.stop()
        player.unregisterCypressMediaPlayerCallback()
        player.close()
        state = PlaybackState.INIT
        duration = 1L
        hasFirstFrameRendered = false
    }
}
```

> 已确认（`./gradlew assembleDebug` 编译通过）：`CypressMediaPlayer`/`CypressMediaPlayerCallback`/`CypressMediaPlayerErrorCode` 的真实包名是 `com.pico.spatial.core.ecs.video`，不是计划撰写时猜测的 `com.pico.spatial.core.video`。上面代码块已经是编译通过的版本。

- [x] **Step 3: 放测试视频资源**

用 `ffmpeg`（本机已装，`/opt/homebrew/bin/ffmpeg`）合成了一段 8 秒、960x540、H.264 baseline + AAC 的测试图案视频（`testsrc2` 图案 + 440Hz 正弦测试音，没有用 `drawtext` 滤镜——这台机器的 ffmpeg build 没编译 `libfreetype`）：

```bash
ffmpeg -y \
  -f lavfi -i "testsrc2=size=960x540:rate=30:duration=8" \
  -f lavfi -i "sine=frequency=440:duration=8" \
  -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.1 \
  -c:a aac -b:a 96k \
  -movflags +faststart \
  app/src/main/assets/videos/sample_flat_test.mp4
```

未额外配置 `noCompress`——Android 默认的 aapt 打包对常见媒体扩展名（含 `.mp4`）本来就不做二次压缩，Task 4 实际播放验证时如果出现异常再回来加。

- [x] **Step 4: 构建验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

结果：编译通过，`com.pico.spatial.core.ecs.video.{CypressMediaPlayer, CypressMediaPlayerCallback, CypressMediaPlayerErrorCode}` 包名确认无误。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/playback app/src/main/assets/videos
git commit -m "Add PlaybackState/PlaybackManager wrapping CypressMediaPlayer"
```

---

### Task 3: 投影/立体格式枚举 + 到 `VideoDimensionMode` 的映射（纯 Kotlin，JVM 单元测试）

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/playback/Projection.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/playback/StereoMode.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/playback/StereoModeMappingTest.kt`

**Interfaces:**
- Consumes: SDK 的 `com.pico.spatial.core.ecs.video.VideoDimensionMode`（已确认的枚举：`MONO` / `TOP_AND_DOWN` / `SIDE_BY_SIDE` / `MULTIPLE_VIEW` / `UNKNOWN`）
- Produces:
  ```kotlin
  enum class Projection { FLAT, HEMISPHERE_180, SPHERE_360 }

  enum class StereoMode {
      MONO, SIDE_BY_SIDE, TOP_AND_DOWN, MULTIVIEW_MVHEVC;

      fun toVideoDimensionMode(): VideoDimensionMode
  }
  ```
  Task 4/6/7 里的 `PlaybackEntityAssembler` 直接调用 `stereoMode.toVideoDimensionMode()` 构造 `VideoMaterial`。

  `Projection` 在 Stage 1 里只定义、不消费——Stage 1 的手动测试入口是靠调用 `startTestPlayback`/`startSphereTestPlayback`/`startHemisphereTestPlayback` 三个不同方法来区分投影类型的，不是靠一个 `Projection` 参数分支。之所以现在就把它定义出来，是因为设计稿 `VideoItem.projection` 字段（第 2 节）需要它，Stage 2 接入真实文件库时会用它来决定该调用上面三个方法里的哪一个——现在先把类型定好，避免 Stage 2 再回头改 Stage 1 的枚举定义。

- [ ] **Step 1: 写失败的单元测试**

```kotlin
package tech.illusion.spaceplayer.playback

import com.pico.spatial.core.ecs.video.VideoDimensionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StereoModeMappingTest {

    @Test
    fun `MONO maps to VideoDimensionMode MONO`() {
        assertEquals(VideoDimensionMode.MONO, StereoMode.MONO.toVideoDimensionMode())
    }

    @Test
    fun `SIDE_BY_SIDE maps to VideoDimensionMode SIDE_BY_SIDE`() {
        assertEquals(VideoDimensionMode.SIDE_BY_SIDE, StereoMode.SIDE_BY_SIDE.toVideoDimensionMode())
    }

    @Test
    fun `TOP_AND_DOWN maps to VideoDimensionMode TOP_AND_DOWN`() {
        assertEquals(VideoDimensionMode.TOP_AND_DOWN, StereoMode.TOP_AND_DOWN.toVideoDimensionMode())
    }

    @Test
    fun `MULTIVIEW_MVHEVC maps to VideoDimensionMode MULTIPLE_VIEW`() {
        assertEquals(VideoDimensionMode.MULTIPLE_VIEW, StereoMode.MULTIVIEW_MVHEVC.toVideoDimensionMode())
    }
}
```

- [ ] **Step 2: 跑测试确认失败（因为 `StereoMode`/`Projection` 还不存在）**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.playback.StereoModeMappingTest"
```

预期：FAIL，报 `Unresolved reference: StereoMode`。

- [ ] **Step 3: 实现 `Projection.kt` 和 `StereoMode.kt`**

```kotlin
// Projection.kt
package tech.illusion.spaceplayer.playback

enum class Projection { FLAT, HEMISPHERE_180, SPHERE_360 }
```

```kotlin
// StereoMode.kt
package tech.illusion.spaceplayer.playback

import com.pico.spatial.core.ecs.video.VideoDimensionMode

enum class StereoMode {
    MONO, SIDE_BY_SIDE, TOP_AND_DOWN, MULTIVIEW_MVHEVC;

    fun toVideoDimensionMode(): VideoDimensionMode = when (this) {
        MONO -> VideoDimensionMode.MONO
        SIDE_BY_SIDE -> VideoDimensionMode.SIDE_BY_SIDE
        TOP_AND_DOWN -> VideoDimensionMode.TOP_AND_DOWN
        MULTIVIEW_MVHEVC -> VideoDimensionMode.MULTIPLE_VIEW
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.playback.StereoModeMappingTest"
```

预期：4 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/playback/Projection.kt app/src/main/java/tech/illusion/spaceplayer/playback/StereoMode.kt app/src/test/java/tech/illusion/spaceplayer/playback/StereoModeMappingTest.kt
git commit -m "Add Projection/StereoMode enums with VideoDimensionMode mapping + unit tests"
```

---

### Task 4: 平面视频跑通——`Stage("ImmersiveStage")` + `ScreenEntity` + 占位主窗口接线

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/Main.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/platform/SpatialApplication.kt`（加 Koin 初始化）
- Modify: `app/src/main/AndroidManifest.xml`（声明 `pico.spatial.stage.id` 等非默认 Stage 属性，若模板要求）

**Interfaces:**
- Consumes: `PlaybackManager`（Task 2）、`StereoMode.toVideoDimensionMode()`（Task 3）、SDK 的 `MeshResource.createVideoPanel(width, height, cornerRadius)`、`VideoMaterial(blendingMode, dimensionMode, cullingMode)`、`VideoPlayerComponent(player, mesh, material)`、`Entity`、`Stage(id, ...) { }` DSL、`LocalSpatialNavigator.current.openStage(id, style)` / `.closeStage()`
- Produces:
  ```kotlin
  object PlaybackEntityAssembler {
      fun assembleScreenEntity(
          entity: Entity,
          player: CypressMediaPlayer,
          widthMeters: Float,
          heightMeters: Float,
          dimensionMode: VideoDimensionMode,
      )
  }

  class PlaybackViewModel : ViewModel() {
      val manager: PlaybackManager
      val screenEntity: Entity
      var isImmersive: Boolean

      fun startTestPlayback(assetPath: String, stereoMode: StereoMode)
      fun exitImmersive()
  }
  ```
  Task 5（HUD）、Task 6/7（球体/半球）、Task 8（环境层）都在 `PlaybackViewModel`/`PlaybackEntityAssembler` 上继续加方法，不改这两个已产出的签名。

- [ ] **Step 1: `PlaybackEntityAssembler.kt`——平面银幕实体**

```kotlin
package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.video.BlendingMode
import com.pico.spatial.core.ecs.video.MaterialCullingMode
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.ecs.video.VideoMaterial
import com.pico.spatial.core.ecs.video.VideoPlayerComponent
import com.pico.spatial.core.video.CypressMediaPlayer

object PlaybackEntityAssembler {

    fun assembleScreenEntity(
        entity: Entity,
        player: CypressMediaPlayer,
        widthMeters: Float,
        heightMeters: Float,
        dimensionMode: VideoDimensionMode,
    ) {
        val mesh = MeshResource.createVideoPanel(widthMeters, heightMeters, 0.05f)
        check(mesh.valid) { "createVideoPanel returned an invalid mesh" }
        val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.BACK)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
    }
}
```

> 备注：`BlendingMode`/`MaterialCullingMode`/`VideoMaterial`/`VideoPlayerComponent` 的确切包名以 Task 1 生成项目实际能 import 到的为准（和 `CypressMediaPlayer` 一样，用 IDE 跳转确认，不要凭空猜）。

- [ ] **Step 2: `PlaybackModule.kt`——Koin session scope**

```kotlin
package tech.illusion.spaceplayer.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import tech.illusion.spaceplayer.ui.PlaybackViewModel

const val PLAYBACK_SESSION_SCOPE_ID = "playback_session_scope"

val playbackModule = module {
    scope(named(PLAYBACK_SESSION_SCOPE_ID)) {
        scoped { PlaybackViewModel(get()) }
    }
}
```

（`app/build.gradle.kts` 需要加 Koin 依赖：`implementation(libs.koin.android)`——若版本目录 `libs.versions.toml` 里没有 Koin 坐标，手动加一行 `koin-android = { module = "io.insert-koin:koin-android", version = "3.5.6" }` 及对应 `[versions]` 条目，参考官方示例项目同款依赖写法。）

- [ ] **Step 3: `PlaybackViewModel.kt`**

```kotlin
package tech.illusion.spaceplayer.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.pico.spatial.core.ecs.Entity
import tech.illusion.spaceplayer.ecs.PlaybackEntityAssembler
import tech.illusion.spaceplayer.playback.PlaybackManager
import tech.illusion.spaceplayer.playback.StereoMode

const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f

class PlaybackViewModel(context: Context) : ViewModel() {
    val manager = PlaybackManager(context)
    val screenEntity = Entity()

    var isImmersive = mutableStateOf(false)
        private set

    private var assembled = false

    fun startTestPlayback(assetPath: String, stereoMode: StereoMode) {
        if (!assembled) {
            PlaybackEntityAssembler.assembleScreenEntity(
                screenEntity,
                manager.player,
                SCREEN_WIDTH_METERS,
                SCREEN_HEIGHT_METERS,
                stereoMode.toVideoDimensionMode(),
            )
            assembled = true
        }
        manager.setup(assetPath)
        isImmersive.value = true
    }

    fun exitImmersive() {
        manager.pause()
        isImmersive.value = false
    }

    override fun onCleared() {
        super.onCleared()
        manager.reset()
    }
}
```

- [ ] **Step 4: `ImmersiveScene.kt`——Stage 内容**

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.LocalSpatialContent
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    PicoTheme {
        LocalSpatialContent.current.addEntity(viewModel.screenEntity)
    }
}
```

> 备注：把实体加进 Stage 内容树的确切 API（`LocalSpatialContent`/`SpatialView { content -> content.addEntity(...) }` 等）以官方示例 `spatial-sdk_video_sample-play-spatial-video-in-an-app.md` 里 `SpatialView(initial = { content, attachments -> content.addEntity(...) })` 的写法为准，如果 `LocalSpatialContent` 这个符号在当前 SDK 里不存在，改用该示例里验证过的 `SpatialView` 写法。

- [ ] **Step 5: `Main.kt`——声明容器**

```kotlin
package tech.illusion.spaceplayer

import com.pico.spatial.core.platform.SpatialAppScope
import com.pico.spatial.ui.platform.containers.DefaultWindowContainer
import com.pico.spatial.ui.platform.containers.Stage
import tech.illusion.spaceplayer.ui.ImmersiveScene
import tech.illusion.spaceplayer.ui.PlaceholderMainScreen

const val IMMERSIVE_STAGE_ID = "ImmersiveStage"

fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PlaceholderMainScreen()
    }

    Stage(id = IMMERSIVE_STAGE_ID) {
        ImmersiveScene()
    }
}
```

- [ ] **Step 6: `PlaceholderMainScreen.kt`——手动测试入口**

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.playback.StereoMode

@Composable
fun PlaceholderMainScreen() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        Column {
            Text("SpacePlayer · Stage 1 手动测试")
            Button(onClick = {
                viewModel.startTestPlayback("videos/sample_flat_test.mp4", StereoMode.MONO)
                coroutineScope.launch {
                    LocalSpatialNavigator.current.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
                }
            }) {
                Text("播放测试视频（平面）")
            }
        }
    }
}
```

> 备注：`Button`/`Text`/`Column` 等具体从哪个 SpatialUI 包 import、`LocalSpatialNavigator.current` 在 `@Composable` 外要不要 `remember`，以生成项目模板里已有的示例 Composable 写法为准——`pico-cli` 生成的骨架里通常会带一两个示例屏幕，照抄它的 import 和写法比凭空猜更可靠。

- [ ] **Step 7: `SpatialApplication.kt`——接入 Koin**

在 `platform/SpatialApplication.kt` 的 `onCreate`（或等价初始化点）里加：

```kotlin
startKoin {
    androidContext(this@SpatialApplication)
    modules(playbackModule)
}
GlobalContext.get().createScope(PLAYBACK_SESSION_SCOPE_ID, named(PLAYBACK_SESSION_SCOPE_ID))
```

- [ ] **Step 8: 构建、安装、启动、截图验证**

```bash
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
pico-cli capture screenshot --out ./artifacts/task4-main-screen.png
```

在截图/模拟器画面里点击"播放测试视频（平面）"按钮，再截一次图：

```bash
pico-cli capture screenshot --out ./artifacts/task4-flat-playing.png
adb logcat -b crash -d
```

预期：第二张截图能看到 `sample_flat_test.mp4` 以一块矩形银幕的形式在沉浸空间里播放；`adb logcat -b crash -d` 无新增崩溃。

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "Play flat test video inside a shared ImmersiveStage via VideoPlayerComponent"
```

---

### Task 5: HUD 播放控制条 + Loading/Error 门控 + 退出流程

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackHud.kt`
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/LoadingErrorAttachment.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`

**Interfaces:**
- Consumes: `PlaybackManager.state`/`duration`/`hasFirstFrameRendered`（Task 2）、`PlaybackViewModel.exitImmersive()`（Task 4）、SDK 的 `SpatialView(attachments = { AttachmentPanel(id) { ... } }, initial = { content, attachments -> ... }, update = { _, attachments -> ... })`
- Produces: `PlaybackViewModel` 新增 `fun togglePlayPause()`、`fun seekTo(ms: Long)`、`fun setVolume(v: Float)`——Task 6/7/8 不需要再碰这几个方法，只是复用。

- [ ] **Step 1: `LoadingErrorAttachment.kt`**

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun LoadingErrorAttachment(state: PlaybackState) {
    PicoTheme {
        Text(
            when (state) {
                PlaybackState.PREPARING -> "加载中…"
                PlaybackState.ERROR -> "视频加载失败"
                else -> "加载中…"
            }
        )
    }
}
```

- [ ] **Step 2: `PlaybackViewModel.kt` 加播放控制方法**

```kotlin
fun togglePlayPause() {
    when (manager.state) {
        tech.illusion.spaceplayer.playback.PlaybackState.PLAYING -> manager.pause()
        tech.illusion.spaceplayer.playback.PlaybackState.PAUSED,
        tech.illusion.spaceplayer.playback.PlaybackState.READY -> manager.resume()
        else -> {}
    }
}

fun seekTo(ms: Long) = manager.seekTo(ms)
fun setVolume(volume: Float) = manager.setVolume(volume)

val showLoadingOverlay: Boolean
    get() = manager.state == tech.illusion.spaceplayer.playback.PlaybackState.PREPARING ||
        manager.state == tech.illusion.spaceplayer.playback.PlaybackState.ERROR ||
        (manager.state == tech.illusion.spaceplayer.playback.PlaybackState.PLAYING && !manager.hasFirstFrameRendered)
```

- [ ] **Step 3: `PlaybackHud.kt`**

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun PlaybackHud(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onExit: () -> Unit,
) {
    PicoTheme {
        Row {
            Button(onClick = onPlayPause) {
                Text(if (state == PlaybackState.PLAYING) "暂停" else "播放")
            }
            Button(onClick = onExit) {
                Text("退出")
            }
        }
    }
}
```

（进度条/音量滑杆用 SpatialUI 的 `Slider`/等价组件补上，具体组件名参照 `spatial-ui-design-style` 技能里当前推荐的滑杆组件——Stage 1 先保证播放/暂停/退出这三个最小可用的控制，进度拖拽和音量在 Stage 2 补齐详情页时可以一并加，不阻塞本 Task 的验证目标。）

- [ ] **Step 4: `ImmersiveScene.kt`——挂载 HUD 和 loading Attachment，接入退出**

用官方示例 `spatial-sdk_video_sample-play-spatial-video-in-an-app.md` "Step 7: Loading/error overlay: AttachmentPanel" 里验证过的 `SpatialView(attachments = {...}, initial = {...}, update = {...})` 写法，把 `LoadingErrorAttachment` 和 `PlaybackHud` 分别挂载为两个 `AttachmentPanel`，`update` 块里：loading 面板的 `enabled` 绑定 `viewModel.showLoadingOverlay`；HUD 面板的 `enabled` 绑定 `!viewModel.showLoadingOverlay`。HUD 的退出按钮调用 `viewModel.exitImmersive()`，同时用 `rememberCoroutineScope` 调 `LocalSpatialNavigator.current.closeStage()` 关闭 `ImmersiveStage` 回到主窗口。

- [ ] **Step 5: 构建、安装、启动、截图验证**

```bash
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
pico-cli capture screenshot --out ./artifacts/task5-loading.png
# 等待首帧渲染后再截一张
pico-cli capture screenshot --out ./artifacts/task5-hud.png
adb logcat -b crash -d
```

预期：第一张截图能看到 loading 提示，第二张能看到播放/暂停/退出控制条；点击退出后应用回到 `PlaceholderMainScreen`；无新增崩溃。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "Add playback HUD, loading/error gating, and exit-to-main-window flow"
```

---

### Task 6: 360° 球体 + MV-HEVC/SBS/TB 立体参数化

**Files:**
- Create: `app/src/main/assets/videos/sample_360_test.mp4`（360° 单目等距柱状测试片段；若能拿到 SBS 或 MV-HEVC 的 360° 素材更好，用于同时验证立体参数化，拿不到的话先用单目验证管线，立体格式只做参数层面的单元测试覆盖）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`（加一个"播放测试视频（360°）"按钮）

**Interfaces:**
- Consumes: `Projection`/`StereoMode`（Task 3）、`PlaybackEntityAssembler.assembleScreenEntity`（Task 4，作为对照）
- Produces:
  ```kotlin
  object PlaybackEntityAssembler {
      // 已有 assembleScreenEntity 不变
      fun assembleSphereEntity(
          entity: Entity,
          player: CypressMediaPlayer,
          sphereMesh: MeshResource,
          dimensionMode: VideoDimensionMode,
      )
  }
  ```
  Task 7（半球）复用同样的参数形状，Task 8（环境层）判断 `Projection == SPHERE_360 || HEMISPHERE_180` 时隐藏 `EnvironmentLayer`。

> **前置说明（重要，先做这个再写代码）：** 360° 球体需要一个"UV 正确映射、朝内表面渲染"的球体网格资源——官方 "Play spatial video in an app" 示例里这个网格**不是**代码里临时生成的，而是在 PICO Spatial Editor 里预先建好、通过 `AssetBundle.loadMeshResource(path)` 加载的（示例里的 `VideoEffectManager.getVideoSphereMesh()` / `VIDEO_SPHERE_MESH_PATH`）。写这份计划时 SDK 文档库暂时无法访问，没能确认 `MeshResource` 是否另外提供了纯代码的球体生成 API（例如 `MeshResource.createSphere(...)`）。**实现这个 Task 前，先用 `spatial-sdk-guideline` 技能或 pico-spatial-knowledge 查一遍当前 SDK 里 `MeshResource`/`com.pico.spatial.core.ecs.resource` 的完整 API：**
> - 如果确实有纯代码的球体生成函数，直接用它，`assembleSphereEntity` 的 `sphereMesh` 参数改成在方法内部生成，不需要外部传入。
> - 如果没有，就按官方示例的路子：打开 PICO Spatial Editor，新建一个场景，加一个球体图元（Editor 工具栏的 Add/Mesh 菜单，具体菜单项名称以当前 Editor 版本为准），把球体材质剔除模式设为只渲染内表面（对应代码里的 `MaterialCullingMode.FRONT`，见下），导出为 `.bundle`，放进 `app/src/main/assets/bundles/`，代码里用 `AssetBundle` 加载后传给 `assembleSphereEntity`。

- [ ] **Step 1: `PlaybackEntityAssembler.kt` 加 `assembleSphereEntity`**

```kotlin
fun assembleSphereEntity(
    entity: Entity,
    player: CypressMediaPlayer,
    sphereMesh: MeshResource,
    dimensionMode: VideoDimensionMode,
) {
    check(sphereMesh.valid) { "sphere mesh resource is invalid" }
    val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.FRONT)
    entity.components.set(VideoPlayerComponent(player, sphereMesh, material))
}
```

`MaterialCullingMode.FRONT`（剔除正面、渲染背面）已由官方示例确认，是"从球体内部往外看"场景的正确设置，直接复用，不需要重新验证。

- [ ] **Step 2: `PlaybackViewModel.kt`——加 `sphereEntity` 和 360° 测试入口**

```kotlin
val sphereEntity = Entity()
private var sphereAssembled = false

fun startSphereTestPlayback(assetPath: String, stereoMode: StereoMode, sphereMesh: MeshResource) {
    if (!sphereAssembled) {
        PlaybackEntityAssembler.assembleSphereEntity(
            sphereEntity, manager.player, sphereMesh, stereoMode.toVideoDimensionMode(),
        )
        sphereAssembled = true
    }
    screenEntity.enabled = false
    sphereEntity.enabled = true
    manager.setup(assetPath)
    isImmersive.value = true
}
```

平面播放路径（`startTestPlayback`）对应加一行 `sphereEntity.enabled = false; screenEntity.enabled = true`，保证两者互斥。

- [ ] **Step 3: `ImmersiveScene.kt`——把 `sphereEntity` 也加进 Stage 内容树**

和 Task 4 Step 4 里加 `screenEntity` 一样的方式，把 `viewModel.sphereEntity` 也 `addEntity`。

- [ ] **Step 4: `PlaceholderMainScreen.kt`——加 360° 测试按钮**

```kotlin
Button(onClick = {
    viewModel.startSphereTestPlayback("videos/sample_360_test.mp4", StereoMode.MONO, sphereMeshResource)
    coroutineScope.launch {
        LocalSpatialNavigator.current.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
    }
}) {
    Text("播放测试视频（360°）")
}
```

（`sphereMeshResource` 的来源取决于 Step 1 前置说明里查到的结论：程序化生成就直接调用，Editor 导出就在这里同步/异步加载 `AssetBundle`。）

- [ ] **Step 5: 构建、安装、启动、截图验证**

```bash
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
pico-cli capture screenshot --out ./artifacts/task6-sphere-playing.png
adb logcat -b crash -d
```

预期：截图显示视频铺满整个视野（球体内表面），没有可见的接缝/黑洞；无新增崩溃。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "Add 360 sphere playback path with FRONT-culled VideoMaterial"
```

---

### Task 7: 180° 半球

**Files:**
- Create: `app/src/main/assets/videos/sample_180_test.mp4`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`（复用 `assembleSphereEntity`，重命名为通用 `assembleImmersiveMeshEntity` 或直接复用同一函数传半球网格——不引入第二个几乎一样的函数）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`

**Interfaces:**
- Consumes: Task 6 的 `PlaybackEntityAssembler.assembleSphereEntity`（本 Task 直接复用，函数签名不变，只是传入的 `MeshResource` 换成半球网格）
- Produces: `PlaybackViewModel.hemisphereEntity: Entity` + `fun startHemisphereTestPlayback(assetPath: String, stereoMode: StereoMode, hemisphereMesh: MeshResource)`，与 `sphereEntity`/`screenEntity` 三者互斥 `enabled`。

- [ ] **Step 1: 确认半球网格来源**

和 Task 6 的前置说明一样：先查 `MeshResource` 是否有半球程序化生成 API；没有就在 Spatial Editor 里再建一个半球图元（或者把 Task 6 的球体网格在 Editor 里裁一半），导出到同一个 `.bundle` 里，加一个新的 mesh 名称。

- [ ] **Step 2: `PlaybackViewModel.kt`——加 `hemisphereEntity`**

```kotlin
val hemisphereEntity = Entity()
private var hemisphereAssembled = false

fun startHemisphereTestPlayback(assetPath: String, stereoMode: StereoMode, hemisphereMesh: MeshResource) {
    if (!hemisphereAssembled) {
        PlaybackEntityAssembler.assembleSphereEntity(
            hemisphereEntity, manager.player, hemisphereMesh, stereoMode.toVideoDimensionMode(),
        )
        hemisphereAssembled = true
    }
    screenEntity.enabled = false
    sphereEntity.enabled = false
    hemisphereEntity.enabled = true
    manager.setup(assetPath)
    isImmersive.value = true
}
```

同时给 `startTestPlayback`/`startSphereTestPlayback` 补上把 `hemisphereEntity.enabled` 设为 `false` 的那一行，保持三者互斥。

- [ ] **Step 3: `ImmersiveScene.kt` 加 `hemisphereEntity`，`PlaceholderMainScreen.kt` 加"播放测试视频（180°）"按钮**

写法和 Task 6 的 Step 3/4 完全一样，只是换成 `hemisphereEntity`/`startHemisphereTestPlayback`。

- [ ] **Step 4: 构建、安装、启动、截图验证**

```bash
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
pico-cli capture screenshot --out ./artifacts/task7-hemisphere-playing.png
adb logcat -b crash -d
```

预期：截图显示视频覆盖前方 180° 视野，转身后方是空的/无纹理（半球背面本来就没有内容，这是正确行为，不是 bug）；无新增崩溃。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "Add 180 hemisphere playback path"
```

---

### Task 8: 三种沉浸式环境（电影院/星空/海景）+ 实时切换

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/playback/Environment.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackHud.kt`（加环境切换按钮组）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`（平面测试按钮旁加环境预选）

**Interfaces:**
- Consumes: Task 4 的 `PlaybackViewModel.screenEntity`/`startTestPlayback`
- Produces:
  ```kotlin
  enum class Environment { CINEMA, STARRY_SKY, SEASIDE }
  ```
  `PlaybackViewModel` 新增 `var currentEnvironment: Environment`、`fun switchEnvironment(target: Environment)`——这是本 Stage 1 的最后一块拼图，Stage 2 的环境预选 UI 直接调用 `switchEnvironment`，不需要再改这里的签名。

> **前置说明（和 Task 6 一样，先查文档再写代码）：** 三个环境天空盒 + 环境光照，需要：(1) 电影院一个简化的 3D 影厅网格（暗色墙面/地面 + 银幕嵌在墙上的锚点）+ 星空/海景各一张 equirectangular HDRI 贴到球体内表面；这些都在 Spatial Editor 里做，导出到 `app/src/main/assets/bundles/environments.bundle`。(2) 让天空盒驱动 Stage 环境光照要用到的 `StageEnvironmentLightingComponent`——写这份计划时文档库访问不到它的精确构造参数，**实现这一步之前必须先用 `spatial-sdk-guideline` 技能查 `spatial-sdk_rendering_image-based-lighting.md` 和 `lighting-components.md` 确认当前真实的构造签名**，不要照抄下面示意性的伪代码。

- [ ] **Step 1: `Environment.kt`**

```kotlin
package tech.illusion.spaceplayer.playback

enum class Environment { CINEMA, STARRY_SKY, SEASIDE }
```

- [ ] **Step 2: `PlaybackEntityAssembler.kt`——加环境层组装函数**

```kotlin
fun assembleEnvironmentEntity(
    entity: Entity,
    skyboxMesh: MeshResource,
    skyboxMaterial: VideoMaterial, // 或者 ShaderGraphMaterial/普通 Material，取决于 HDRI 贴图怎么接（见下）
) {
    check(skyboxMesh.valid) { "environment skybox mesh is invalid" }
    entity.components.set(/* 具体挂载哪个渲染组件，以 Step 前置说明查到的 API 为准 */)
    // 在这个实体上附加 StageEnvironmentLightingComponent，参数以查到的真实签名为准
}
```

> 这个函数体故意留了两处"以查到的为准"——这不是走过场的占位符，而是本计划明确交代过的、必须先查文档再落笔的两个具体未知点（环境贴图怎么接渲染管线、`StageEnvironmentLightingComponent` 的构造参数）。实现者查完文档后，把这两处替换成真实、可编译的代码，其余部分（`assembleEnvironmentEntity` 的签名、调用方式）不变。

- [ ] **Step 3: `PlaybackViewModel.kt`——三个环境实体 + 切换逻辑**

```kotlin
val cinemaEnvironmentEntity = Entity()
val starrySkyEnvironmentEntity = Entity()
val seasideEnvironmentEntity = Entity()

var currentEnvironment by mutableStateOf(Environment.CINEMA)
    private set

fun switchEnvironment(target: Environment) {
    cinemaEnvironmentEntity.enabled = target == Environment.CINEMA
    starrySkyEnvironmentEntity.enabled = target == Environment.STARRY_SKY
    seasideEnvironmentEntity.enabled = target == Environment.SEASIDE
    currentEnvironment = target
    // 电影院模式下把 screenEntity 重新挂到墙面锚点；其余两个环境挂到悬浮锚点
    // 具体锚点/父子关系写法参照 Editor 里为 cinemaEnvironmentEntity 建的锚点子实体
}
```

关键约束（对应设计稿第 3 节）：`switchEnvironment` 全程不调用 `manager` 的任何方法，只操作这三个环境实体的 `enabled` 和 `screenEntity` 的父子挂载,播放不受影响。

- [ ] **Step 4: `ImmersiveScene.kt`——把三个环境实体加进内容树，初始状态只有 `CINEMA` 可见**

- [ ] **Step 5: `PlaybackHud.kt`——加环境切换按钮组**

```kotlin
@Composable
fun EnvironmentSwitcher(
    current: Environment,
    onSelect: (Environment) -> Unit,
) {
    PicoTheme {
        Row {
            listOf(Environment.CINEMA to "电影院", Environment.STARRY_SKY to "星空", Environment.SEASIDE to "海景")
                .forEach { (env, label) ->
                    Button(onClick = { onSelect(env) }) {
                        Text(if (env == current) "[$label]" else label)
                    }
                }
        }
    }
}
```

只在 `viewModel`当前播放的是平面视频（`screenEntity.enabled == true`）时显示这个按钮组；球体/半球播放时隐藏。

- [ ] **Step 6: `PlaceholderMainScreen.kt`——平面测试按钮旁加环境预选**

播放前允许选一个初始环境，调用 `viewModel.switchEnvironment(selected)` 后再 `startTestPlayback`。

- [ ] **Step 7: 构建、安装、启动、截图验证——重点验证"播放中切换环境不中断"**

```bash
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
pico-cli capture screenshot --out ./artifacts/task8-cinema.png
# 点击"星空"环境切换按钮
pico-cli capture screenshot --out ./artifacts/task8-starry.png
# 点击"海景"环境切换按钮
pico-cli capture screenshot --out ./artifacts/task8-seaside.png
adb logcat -b crash -d
```

预期：三张截图分别显示三种环境背景，同一块银幕、同一段测试视频持续播放（用 HUD 上的进度/时长文本或截图里视频画面内容的连续性判断没有从头重播）；无新增崩溃。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "Add cinema/starry-sky/seaside environments with live switching"
```

---

### Task 9: Stage 1 端到端回归

**Files:**
- 无新文件；本 Task 只做验证 + 文档收尾。
- Modify: `SpacePlayer/AGENTS.md`（补充 Stage 1 完成状态、关键文件、已知的两个"查文档后再定"事项是否已解决）

**Interfaces:**
- Consumes: Task 1-8 的全部产出
- Produces: 无新接口；这是回归验证 Task。

- [ ] **Step 1: 全量重新构建**

```bash
./gradlew clean assembleDebug
./gradlew :app:testDebugUnitTest
```

预期：`assembleDebug` 成功；`StereoModeMappingTest` 的 4 个用例全部 PASS。

- [ ] **Step 2: 逐一走查六条路径**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
pico-cli app launch tech.illusion.spaceplayer
```

依次手动触发并各截一张图到 `./artifacts/task9-regression-*.png`：平面+电影院、平面+星空、平面+海景（同一次播放里切换）、180°、360°、退出回主窗口。

```bash
adb logcat -b crash -d
```

预期：六张截图都符合设计稿第 1/3 节描述的画面；全程无新增崩溃。

- [ ] **Step 3: 更新 `AGENTS.md`**

写清楚：Stage 1 已完成的范围（本文件 Task 1-8 覆盖的内容）、Task 6/8 里"查文档后再定"的两处最终采用了什么方案（程序化 API 还是 Editor 导出的 bundle，bundle 放在哪）、构建/安装/运行命令、下一步是 Stage 2（真实文件库 UI + 格式识别 + 历史，设计见 `docs/superpowers/specs/2026-08-05-spaceplayer-design.md` 第 2/4 节）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "Stage 1 regression pass + AGENTS.md update"
```
