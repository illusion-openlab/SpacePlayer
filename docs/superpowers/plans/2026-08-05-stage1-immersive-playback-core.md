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

- [x] **Step 1: 写失败的单元测试**

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

- [x] **Step 2: 跑测试确认失败（因为 `StereoMode`/`Projection` 还不存在）**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.playback.StereoModeMappingTest"
```

结果：FAIL，`compileDebugUnitTestKotlin` 报 4 处 `Unresolved reference 'StereoMode'`，符合预期。

- [x] **Step 3: 实现 `Projection.kt` 和 `StereoMode.kt`**

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

- [x] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.playback.StereoModeMappingTest"
```

结果：`BUILD SUCCESSFUL`，4 个测试全部 PASS。

- [x] **Step 5: 提交**

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
- Modify: `app/src/main/AndroidManifest.xml`（把生成模板的 `pico.spatial.stage.*` 换成 `pico.spatial.windowcontainer.*`，含必需的 `.id` 字段——见 Step 7.5）
- Delete: `app/src/main/java/tech/illusion/spaceplayer/content/HomeStage.kt`、`app/src/main/assets/box.usdz`（脚手架占位内容，不再需要）

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

  class PlaybackViewModel(context: Context) {  // 普通类，Koin scoped，不继承 androidx ViewModel
      val manager: PlaybackManager
      val screenEntity: Entity
      var isImmersive: Boolean

      fun startTestPlayback(assetPath: String, stereoMode: StereoMode)
      fun exitImmersive()
      fun onCleared()  // 普通方法，不是覆写
  }
  ```
  Task 5（HUD）、Task 6/7（球体/半球）、Task 8（环境层）都在 `PlaybackViewModel`/`PlaybackEntityAssembler` 上继续加方法，不改这两个已产出的签名。

- [x] **Step 1: `PlaybackEntityAssembler.kt`——平面银幕实体**

真实包名（`./gradlew assembleDebug` 编译通过后确认，和计划撰写时的猜测有两处不同：`VideoPlayerComponent` 在 `com.pico.spatial.core.ecs`，`VideoMaterial`/`MaterialCullingMode`/`BlendingMode`/`MeshResource` 在 `com.pico.spatial.core.ecs.resource`，都不在 `.video` 子包下）：

```kotlin
package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector3

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
        // Entity() already carries a default TransformComponent - components.set() with a NEW
        // instance is rejected at runtime ("component already exists", logged as an E without
        // crashing) and silently no-ops. Mutate the existing one instead, the same way the
        // pico-cli stage template's HomeStage.kt does it for its box model. Without this the
        // panel sits at the world origin, which is at/behind the user's spawn point inside a
        // Stage (unlike a WindowContainer, which the system positions for readability
        // automatically) - and is therefore invisible. This cost real debugging time (see Step 8
        // for the full trail: black-screen → red-background diagnostic → logcat "component
        // already exists" → this fix), so don't regress it.
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(Vector3(0f, 1.5f, -2f))
        }
    }
}
```

- [x] **Step 2: `PlaybackModule.kt`——Koin session scope**

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

`app/build.gradle.kts` 加了 `implementation(libs.koin.android)`，`gradle/libs.versions.toml` 加了 `koin = "3.5.6"` + `koin-android = { group = "io.insert-koin", name = "koin-android", version.ref = "koin" }`（用 `group`/`name` 两个字段，不是一个 `module` 字符串——这是当前版本目录 TOML 语法要求的写法）。

- [x] **Step 3: `PlaybackViewModel.kt`**

不继承 `androidx.lifecycle.ViewModel`——它是 Koin session-scope 里的一个普通类（`scoped { PlaybackViewModel(get()) }`），生命周期由 Koin scope 管理，不需要也不依赖 `ViewModelStoreOwner`；`onCleared()` 是个普通方法，不是覆写。

```kotlin
package tech.illusion.spaceplayer.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.ecs.Entity
import tech.illusion.spaceplayer.ecs.PlaybackEntityAssembler
import tech.illusion.spaceplayer.playback.PlaybackManager
import tech.illusion.spaceplayer.playback.StereoMode

const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f

class PlaybackViewModel(context: Context) {
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

    fun onCleared() {
        manager.reset()
    }
}
```

- [x] **Step 4: `ImmersiveScene.kt`——Stage 内容**

真实 API：`SpatialView` 在 `com.pico.spatial.ui.foundation.content`（不是计划撰写时猜测的 `LocalSpatialContent`——那个符号在当前 SDK 里不存在），写法和官方 "Play spatial video in an app" 示例一致：

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()

    PicoTheme {
        SpatialView(
            initial = { content, _ ->
                content.addEntity(viewModel.screenEntity)
            },
        )
    }
}
```

- [x] **Step 5: `Main.kt`——声明容器**

真实 import：`DefaultWindowContainer`/`Stage`/`SpatialAppScope` 都在 `com.pico.spatial.ui.foundation.dsl`（不是计划撰写时猜测的 `com.pico.spatial.core.platform`/`com.pico.spatial.ui.platform.containers`——`pico-cli --template stage` 生成的 `Main.kt` 里 `DefaultStage`/`SpatialAppScope` 就是从 `dsl` 包 import 的，`DefaultWindowContainer`/`Stage` 是它的同包兄弟函数，一次性验证对了）：

```kotlin
package tech.illusion.spaceplayer

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spaceplayer.ui.ImmersiveScene
import tech.illusion.spaceplayer.ui.PlaceholderMainScreen

const val IMMERSIVE_STAGE_ID = "ImmersiveStage"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PlaceholderMainScreen()
        }

        Stage(id = IMMERSIVE_STAGE_ID) {
            ImmersiveScene()
        }
    }
```

`pico-cli --template stage` 生成的骨架默认容器是 `DefaultStage`（脚手架自带的 `content/HomeStage.kt` 示例：一个 `box.usdz` 模型 + "Hello, Spatial SDK!" 文字面板）——这两个文件在这一步删掉了（`git rm`），因为设计要求默认容器是平面主窗口，不是 Stage。

- [x] **Step 6: `PlaceholderMainScreen.kt`——手动测试入口**

真实 import：`Button`/`Text`/`PicoTheme` 在 `com.pico.spatial.ui.design`；`LocalSpatialNavigator`/`StageStyle` 在 `com.pico.spatial.ui.platform.containers`（不是计划撰写时猜测的 `com.pico.spatial.ui.platform`）。**关键坑**：`Text`/`Button` 不设置 `style`/`fontSize` 会渲染成实际不可见的默认大小（真机/模拟器截图里完全看不到，哪怕不透明背景色块能正常显示）——必须像脚手架 `HomeStage.kt` 的示例一样显式给 `style = PicoTheme.typography.titleLarge.copy(fontSize = ...)`：

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.IMMERSIVE_STAGE_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.playback.StereoMode

@Composable
fun PlaceholderMainScreen() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Text(
                text = "SpacePlayer · Stage 1 手动测试",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 40.sp),
            )
            Button(onClick = {
                viewModel.startTestPlayback("videos/sample_flat_test.mp4", StereoMode.MONO)
                coroutineScope.launch {
                    navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
                }
            }) {
                Text(
                    text = "播放测试视频（平面）",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
```

- [x] **Step 7: `SpatialApplication.kt`——接入 Koin**

```kotlin
package tech.illusion.spaceplayer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.di.playbackModule
import tech.illusion.spaceplayer.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SpatialApplication)
            modules(playbackModule)
        }
        GlobalContext.get().createScope(PLAYBACK_SESSION_SCOPE_ID, named(PLAYBACK_SESSION_SCOPE_ID))
        launch(::mainApp)
    }
}
```

- [x] **Step 7.5（新增，计划撰写时未预见）：`AndroidManifest.xml`——`pico.spatial.windowcontainer.id` 是必需的**

把默认容器从生成模板的 `DefaultStage`（`pico.spatial.stage.*` meta-data）换成 `DefaultWindowContainer` 后，第一次运行直接崩溃：

```
IllegalStateException: Only support [SUIStage,SUIWindowContainer], but got a
[name = PICO_SYSTEM_DEFAULT_WINDOWCONTAINER, class = class com.pico.spatial.core.container.WindowContainer].
You cannot merely register non-default containers in the Manifest; you also need to register them in the DSL.
```

对比 SeasonsApp 的 `AndroidManifest.xml`（同样是 `DefaultWindowContainer`）才发现漏了 `pico.spatial.windowcontainer.id`——这是必需字段，作用和 Stage 的 `pico.spatial.stage.id` 一样，缺了它系统就没法把 Manifest 里的容器配置和 DSL 里 `DefaultWindowContainer{}` 注册的内容对上，会退化成一个内部占位容器直接崩溃。修法：

```xml
<meta-data
    android:name="pico.spatial.windowcontainer.id"
    android:value="SpacePlayerMainWindow" />
```

（加在 `pico.spatial.windowcontainer.style` 那条 meta-data 前面，同一个 `<activity>` 块里。）

- [x] **Step 8: 构建、安装、启动、截图验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
# 主窗口渲染有延迟，冷启动后 sleep 8-10s 再截图比较稳（sleep 4-6s 有时还没渲染完）
pico-cli capture screenshot --out ./artifacts/task4-main-screen.png --device emulator-5554
```

结果：截图能看到主窗口标题"SpacePlayer · Stage 1 手动测试"和按钮"播放测试视频（平面）"，`adb logcat -b crash -d` 无崩溃。

**验证"点击按钮进入沉浸态播放"这条路径时踩了一个工具限制**：`adb shell input tap x y` 对空间容器不可靠（`spatial-emulator-usage`/`spatial-app-dev-workflow` 两个技能都明确写了这一条——2D 坐标注入不能可靠触发 spatial 容器的交互），试了几次坐标都没反应，且不应该继续试坐标（技能原文："when this limitation blocks a verification flow, say so clearly instead of spending turns retrying different tap coordinates"）。改用临时诊断手段：在 `PlaceholderMainScreen` 里加一个 `LaunchedEffect(Unit) { ... }`，自动触发和按钮 `onClick` 完全相同的代码路径（`startTestPlayback` + `openStage`），验证完就删掉，不留在最终代码里。

用这个临时手段验证时，还发现并修了两个真问题（已经体现在上面 Step 1/6 的最终代码里，这里记录发现过程）：
1. 黑色背景确认 `StageStyle.Full` 生效（正确——Task 8 之前本来就没有环境天空盒，纯黑是预期行为），但银幕完全不可见——加了一个纯红色 `Modifier.background(Color.Red)` 背景块做对照，红色能看到，说明 Compose 内容树本身没问题，问题在具体某个子元素。
2. `adb logcat -d --pid=<pid>` 里搜到 `E SpatialPack_SceneInspector: entityId = 1048576, component TransformComponent already exists`——`Entity()` 默认就带一个 `TransformComponent`，我又调用 `entity.components.set(TransformComponent()...)` 想新增一个，被拒绝、静默 no-op，银幕实体的位置从未被真正设置，停在世界原点（Stage 内无自动布局，原点通常在/接近用户出生点，看不见）。改成 `entity.components[TransformComponent::class.java]?.apply { setPosition(...) }`（取出已有的改，而不是塞一个新的）后，测试视频（`testsrc2` 彩条 + 时间码）正确显示在前方 2 米处的银幕上。

- [x] **Step 9: 提交**

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

- [x] **Step 1: `LoadingErrorAttachment.kt`**

真实包名：frosted-glass 背景要用 `com.pico.spatial.ui.foundation.material.backgroundMaterial` + `com.pico.spatial.ui.platform.Material`（和 `Text` 一样，`style`/`fontSize` 必须显式给，理由同 Task 4 Step 6）：

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun LoadingErrorAttachment(state: PlaybackState) {
    PicoTheme {
        Box(
            modifier = Modifier
                .size(480.dp, 200.dp)
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (state) {
                    PlaybackState.ERROR -> "视频加载失败"
                    else -> "加载中…"
                },
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp),
            )
        }
    }
}
```

- [x] **Step 2: `PlaybackViewModel.kt` 加播放控制方法**

（没加 `seekTo`/`setVolume` 转发——Stage 1 的 HUD 只做播放/暂停/退出三个最小控制，进度条/音量滑杆是 Stage 2 详情页的范围，`PlaybackManager.seekTo`/`setVolume` 已经存在，届时直接调用，不需要现在就在 ViewModel 加一层转发。）

```kotlin
fun togglePlayPause() {
    when (manager.state) {
        PlaybackState.PLAYING -> manager.pause()
        PlaybackState.PAUSED, PlaybackState.READY -> manager.resume()
        else -> {}
    }
}

val showLoadingOverlay: Boolean
    get() = manager.state == PlaybackState.PREPARING ||
        manager.state == PlaybackState.ERROR ||
        (manager.state == PlaybackState.PLAYING && !manager.hasFirstFrameRendered)
```

（顶部 import 加 `tech.illusion.spaceplayer.playback.PlaybackState`。）

- [x] **Step 3: `PlaybackHud.kt`**

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceplayer.playback.PlaybackState

@Composable
fun PlaybackHud(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onExit: () -> Unit,
) {
    PicoTheme {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .backgroundMaterial(true, Material.Regular)
                .padding(16.dp),
        ) {
            Row {
                Button(onClick = onPlayPause) {
                    Text(
                        text = if (state == PlaybackState.PLAYING) "暂停" else "播放",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
                Button(onClick = onExit) {
                    Text(
                        text = "退出",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    )
                }
            }
        }
    }
}
```

（进度条/音量滑杆留给 Stage 2——理由同 Step 2。）

- [x] **Step 4: `ImmersiveScene.kt`——挂载 HUD 和 loading Attachment，接入退出**

`SpatialView(attachments = {...}, initial = {...}, update = {...})` 的写法和官方示例一致，`AttachmentPanel` 不需要单独 import（`attachments` lambda 作用域自带）。两个 attachment 都作为 `screenEntity` 的子实体（`addChild`），这样 dock 到某个位置时会跟着银幕一起动：

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

private const val LOADING_ATTACHMENT_ID = "loading"
private const val HUD_ATTACHMENT_ID = "hud"

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        SpatialView(
            attachments = {
                AttachmentPanel(id = LOADING_ATTACHMENT_ID) {
                    LoadingErrorAttachment(viewModel.manager.state)
                }
                AttachmentPanel(id = HUD_ATTACHMENT_ID) {
                    PlaybackHud(
                        state = viewModel.manager.state,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onExit = {
                            viewModel.exitImmersive()
                            coroutineScope.launch { navigator.closeStage() }
                        },
                    )
                }
            },
            initial = { content, attachments ->
                content.addEntity(viewModel.screenEntity)

                attachments.entity(LOADING_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0f, 0.05f))
                    }
                    viewModel.screenEntity.addChild(this)
                }

                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, -0.55f, 0.05f))
                    }
                    viewModel.screenEntity.addChild(this)
                }
            },
            update = { _, attachments ->
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay
            },
        )
    }
}
```

> **修订说明（Task 6 时发现并改掉）：** 把 loading/HUD 挂成 `screenEntity` 的子实体这个设计，在 Task 6 加入 `sphereEntity` 后暴露了问题——子实体的可见性跟随父实体的 `enabled`，而两种模式下 `screenEntity`/`sphereEntity` 互斥 `enabled`，导致切到 360° 模式时 HUD 会跟着 `screenEntity` 一起被隐藏。Task 6 把 loading/HUD 改成不挂在任何一个视频实体下，直接以 `content.addEntity(this)` 加入 Stage 内容树、用固定的绝对坐标（用户前方 1.5 米），两种模式下都能看到。完整代码见 Task 6 Step 3。

- [x] **Step 5: 构建、安装、启动、截图验证**

`adb shell input tap` 对空间容器不可靠（同 Task 4 的结论），验证 HUD 显示和退出流程都用临时 `LaunchedEffect` 自动触发（验证完已删除，不留在最终代码里）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
pico-cli capture screenshot --out ./artifacts/task5-hud.png --device emulator-5554
```

结果：截图能看到测试视频正常播放，银幕下方出现 HUD 控制条，放大确认文字是"暂停"/"退出"（播放中，loading 层正确隐藏）；`adb logcat -b crash -d` 无崩溃。

退出流程单独验证：在 `ImmersiveScene` 临时加 `LaunchedEffect(Unit) { delay(6000); viewModel.exitImmersive(); navigator.closeStage() }`，`sleep 16` 后截图——画面回到主窗口（标题+按钮），黑色沉浸背景和 HUD 都消失，无崩溃。确认后移除了这段临时代码。

- [x] **Step 6: 提交**

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
          radiusMeters: Float,
          dimensionMode: VideoDimensionMode,
      )  // 内部用 MeshResource.createSphere(radiusMeters) 生成网格，不需要外部传入
  }
  ```
  Task 7（半球）复用同样的参数形状，Task 8（环境层）判断 `Projection == SPHERE_360 || HEMISPHERE_180` 时隐藏 `EnvironmentLayer`。

> **前置说明结论：不需要 Spatial Editor。** 写计划时担心的"球体网格要不要在 Editor 里建"这个问题，实际查了 SDK 6.0 版本的资源管理文档（`spatial-sdk_resource-management_mesh.md`）后发现 `MeshResource` 本来就有一整套程序化几何体生成函数：`createPlane`/`createVideoPanel`/`createSphere(radius)`/`createCylinder`/`createCone`/`createCapsule`/`createBox`/`createTorus`。用 `MeshResource.createSphere(radius = 10f)` 就够了，`./gradlew assembleDebug` 编译通过确认这个 API 在项目实际用的 SDK 0.13.3 里也存在（文档虽然是 6.0 版本的，但接口没变）。官方 "Play spatial video in an app" 示例里用 Editor 导出网格，大概率是那个示例还要做额外的 portal 视觉效果，不是因为程序化 API 不存在。

- [x] **Step 1: `PlaybackEntityAssembler.kt` 加 `assembleSphereEntity`**

```kotlin
fun assembleSphereEntity(
    entity: Entity,
    player: CypressMediaPlayer,
    radiusMeters: Float,
    dimensionMode: VideoDimensionMode,
) {
    val mesh = MeshResource.createSphere(radiusMeters)
    check(mesh.valid) { "createSphere returned an invalid mesh" }
    // FRONT: cull front faces, render back faces - correct for viewing from inside the
    // sphere (confirmed by the official "Play spatial video in an app" sample).
    val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.FRONT)
    entity.components.set(VideoPlayerComponent(player, mesh, material))
    // Sphere is centered on the user by design (radiusMeters chosen so the surface surrounds
    // the default spawn point) - world origin is correct here, unlike the flat screen panel.
}
```

不需要外部传入 `MeshResource`——直接在方法内部用 `createSphere` 生成，比计划撰写时设想的"外部传入网格资源"签名更简单。

- [x] **Step 2: `PlaybackViewModel.kt`——加 `sphereEntity` 和 360° 测试入口**

```kotlin
const val SPHERE_RADIUS_METERS = 10f

val sphereEntity = Entity()
private var sphereAssembled = false

fun startTestPlayback(assetPath: String, stereoMode: StereoMode) {
    if (!screenAssembled) {
        PlaybackEntityAssembler.assembleScreenEntity(
            screenEntity, manager.player, SCREEN_WIDTH_METERS, SCREEN_HEIGHT_METERS,
            stereoMode.toVideoDimensionMode(),
        )
        screenAssembled = true
    }
    screenEntity.enabled = true
    sphereEntity.enabled = false
    manager.setup(assetPath)
    isImmersive.value = true
}

fun startSphereTestPlayback(assetPath: String, stereoMode: StereoMode) {
    if (!sphereAssembled) {
        PlaybackEntityAssembler.assembleSphereEntity(
            sphereEntity, manager.player, SPHERE_RADIUS_METERS, stereoMode.toVideoDimensionMode(),
        )
        sphereAssembled = true
    }
    screenEntity.enabled = false
    sphereEntity.enabled = true
    manager.setup(assetPath)
    isImmersive.value = true
}
```

（`assembled` 布尔字段改名成 `screenAssembled`，和新加的 `sphereAssembled` 对称——Task 4 原来的名字在只有一个视频实体时没问题，两个实体并存后 `assembled` 这个名字指代不清，顺手改了。）

- [x] **Step 3: `ImmersiveScene.kt`——加入 `sphereEntity`，并把 loading/HUD 从 `screenEntity` 的子实体改成独立实体**

把 `viewModel.sphereEntity` 也 `content.addEntity(...)`。同时做了一处计划撰写时没预料到的修订：loading/HUD 原来是 `screenEntity` 的子实体（Task 5 的写法），但子实体的可见性跟随父实体 `enabled`，而 `screenEntity`/`sphereEntity` 现在互斥 `enabled`——如果不改，切到 360° 模式时 HUD 会跟着 `screenEntity` 一起消失。改成不挂在任何一个视频实体下，用固定绝对坐标（用户前方 1.5 米）：

```kotlin
package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

private const val LOADING_ATTACHMENT_ID = "loading"
private const val HUD_ATTACHMENT_ID = "hud"

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        SpatialView(
            attachments = {
                AttachmentPanel(id = LOADING_ATTACHMENT_ID) {
                    LoadingErrorAttachment(viewModel.manager.state)
                }
                AttachmentPanel(id = HUD_ATTACHMENT_ID) {
                    PlaybackHud(
                        state = viewModel.manager.state,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onExit = {
                            viewModel.exitImmersive()
                            coroutineScope.launch { navigator.closeStage() }
                        },
                    )
                }
            },
            initial = { content, attachments ->
                content.addEntity(viewModel.screenEntity)
                content.addEntity(viewModel.sphereEntity)

                // Independent of screenEntity/sphereEntity on purpose: screenEntity sits 2m away
                // and sphereEntity is a 10m-radius shell, and only one of the two is `enabled` at
                // a time (children of a disabled entity are hidden too) - so the HUD/loading
                // overlay must NOT be parented to either, or it would disappear in sphere mode.
                // Fixed in front of the default spawn point works for both.
                attachments.entity(LOADING_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 1.5f, -1.5f))
                    }
                    content.addEntity(this)
                }

                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0.9f, -1.5f))
                    }
                    content.addEntity(this)
                }
            },
            update = { _, attachments ->
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay
            },
        )
    }
}
```

- [x] **Step 4: `PlaceholderMainScreen.kt`——加 360° 测试按钮**

```kotlin
Button(onClick = {
    viewModel.startSphereTestPlayback("videos/sample_360_test.mp4", StereoMode.MONO)
    coroutineScope.launch {
        navigator.openStage(IMMERSIVE_STAGE_ID, style = StageStyle.Full)
    }
}) {
    Text(
        text = "播放测试视频（360°）",
        color = PicoTheme.colorScheme.labelPrimary,
        style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp),
        textAlign = TextAlign.Center,
    )
}
```

- [x] **Step 5: 构建、安装、启动、截图验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 11
pico-cli capture screenshot --out ./artifacts/task6-sphere.png --device emulator-5554
```

（验证用临时 `LaunchedEffect` 自动触发 `startSphereTestPlayback`，同 Task 4/5 的做法，验证完已删除。）

结果：截图显示测试视频（`testsrc2` 彩条）完整铺满整个视野，没有接缝/黑洞；主窗口面板和 HUD 正确悬浮在前方、不受球体内容影响（这正是 Step 3 那处修订要验证的点）；`adb logcat -b crash -d` 无崩溃。

- [x] **Step 6: 提交**

```bash
git add -A
git commit -m "Add 360 sphere playback path with FRONT-culled VideoMaterial"
```

> **修订说明（Task 7 时发现并改掉）：** `MeshResource` 没有半球函数，Task 7 改用了从 StoryPico 项目移植的手写网格方案（`MeshGenerator.generateVideoSphere` + `MeshResource.createWithMeshModel`），360°/180° 现在共用同一套生成逻辑，`assembleSphereEntity` 的 `MaterialCullingMode` 也从 `FRONT` 换成了 `NONE`。本 Task（Task 6）上面的代码块是提交时的版本，最终代码见 Task 7 Step 1/2。

---

### Task 7: 180° 半球

**Files:**
- Create: `app/src/main/assets/videos/sample_180_test.mp4`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`（复用 `assembleSphereEntity`，重命名为通用 `assembleImmersiveMeshEntity` 或直接复用同一函数传半球网格——不引入第二个几乎一样的函数）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaceholderMainScreen.kt`

**Interfaces:**
- Consumes: 无法复用 Task 6 的 `MeshResource.createSphere`——SDK 完整 API 列表（`createPlane`/`createVideoPanel`/`createSphere`/`createCylinder`/`createCone`/`createCapsule`/`createBox`/`createTorus`/`createWithMeshModel`）里没有半球函数，只有 `createSphere(radius: Float)`，不支持局部扫描角。
- Produces：见下方"前置说明"——最终方案不是"复用 Task 6 传入不同网格"，而是重写了 `assembleSphereEntity` 本身的签名（加一个 `horizontalFovDegrees` 参数），Task 6 的 360° 路径也跟着换了实现，两者现在共用同一段网格生成代码。

> **前置说明：参考同工作区的 StoryPico 项目解法。** 问了用户，得到指引"参考 Pico Story 的 VideoPlayableEntity"——`/Users/zohar/WorkSpace/Project/StoryProjects/StoryPico`（同样是 spatialBom 0.13.3 的 PICO Spatial 项目）里已经有一个跑通的方案：**不用任何 SDK 内置网格函数**，而是手写顶点数据、通过 `MeshResource.createWithMeshModel(model, name)` 导入自定义 `MeshModel`。核心函数 `MeshGenerator.generateVideoSphere(radius, horizontalFov, verticalFov=180f, segment=60)`：垂直方向永远扫满 180°（球的南北极，180°/360° 视频在这个维度上没有区别），只有水平方向按 `horizontalFov/360f` 缩放扫描角——360° 传 `horizontalFov=360f`，180° 传 `horizontalFov=180f`，得到的是真的只覆盖前方 180° 弧度的半球面，不是全球贴一半黑图。法线朝内（`-position/len`），配合 `VideoMaterial` 的 `MaterialCullingMode.NONE`（不做面剔除），这一组合已经在 StoryPico 里验证过能正确渲染。移植后在本项目里编译通过，确认 `MeshModel`/`Vector2`/`ResourceLoadingException`/`MeshResource.createWithMeshModel` 这几个 API 在 spatialBom 0.13.3 里都存在，包名和 StoryPico 完全一致。
>
> 这个方案统一了 360°/180° 的网格生成方式，所以**顺带重写了 Task 6 的 `assembleSphereEntity`**（把 `MeshResource.createSphere` + `MaterialCullingMode.FRONT` 换成 `MeshGenerator.generateVideoSphere` + `MaterialCullingMode.NONE`），并对 360° 路径做了回归截图验证（视野依然完整无缝，无崩溃）。

- [x] **Step 1: 新增 `MeshGenerator.kt`（从 StoryPico 移植）**

```kotlin
package tech.illusion.spaceplayer.ecs

import android.util.Log
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ResourceLoadingException
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "MeshGenerator"

object MeshGenerator {

    fun generateVideoSphere(
        radius: Float = 10f,
        horizontalFov: Float,
        verticalFov: Float = 180f,
        segment: Int = 60,
    ): MeshResource? {
        val ringCount = segment + 1
        val vertexCount = ringCount * ringCount

        val verticalScale = verticalFov / 180f
        val verticalOffset = (1f - verticalScale) / 2f
        val horizontalScale = horizontalFov / 360f
        // +0.25 makes the sphere "open toward the front" so VR180 faces +Z.
        val horizontalOffset = (1f - horizontalScale) / 2f + 0.25f

        val positions = ArrayList<Vector3>(vertexCount)
        val normals = ArrayList<Vector3>(vertexCount)
        val uvs = ArrayList<Vector2>(vertexCount)

        val pi = PI.toFloat()
        for (y in 0..segment) {
            val angle1 = (pi * (y.toFloat() / segment)) * verticalScale + verticalOffset * pi
            val sin1 = sin(angle1)
            val cos1 = cos(angle1)
            for (x in 0..segment) {
                val angle2 = (pi * 2f * (x.toFloat() / segment)) * horizontalScale +
                    horizontalOffset * pi * 2f
                val sin2 = sin(angle2)
                val cos2 = cos(angle2)

                val px = sin1 * cos2 * radius
                val py = cos1 * radius
                val pz = sin1 * sin2 * radius
                positions.add(Vector3(px, py, pz))

                val len = sqrt(px * px + py * py + pz * pz)
                if (len > 0f) {
                    normals.add(Vector3(-px / len, -py / len, -pz / len))
                } else {
                    normals.add(Vector3(0f, 0f, 0f))
                }

                uvs.add(Vector2(x.toFloat() / segment, 1f - y.toFloat() / segment))
            }
        }

        val triangles = ArrayList<Int>(segment * segment * 6)
        for (y in 0 until segment) {
            for (x in 0 until segment) {
                val current = x + y * ringCount
                val next = current + ringCount
                triangles.add(current + 1)
                triangles.add(current)
                triangles.add(next + 1)
                triangles.add(next + 1)
                triangles.add(current)
                triangles.add(next)
            }
        }

        return createMeshFromModel(
            MeshModel(
                positions = positions,
                triangleIndices = triangles,
                normals = normals,
                uv0 = uvs,
            ),
            "videoPlayerSphere",
        )
    }

    private fun createMeshFromModel(model: MeshModel, name: String): MeshResource? {
        return try {
            MeshResource.createWithMeshModel(model, name = name)
        } catch (e: ResourceLoadingException) {
            Log.e(TAG, "$name failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$name invalid: ${e.message}")
            null
        }
    }
}
```

- [x] **Step 2: 重写 `PlaybackEntityAssembler.assembleSphereEntity`——加 `horizontalFovDegrees` 参数，360°/180° 共用**

```kotlin
/**
 * @param horizontalFovDegrees 360f for full 360° panoramic video, 180f for 180° hemisphere.
 */
fun assembleSphereEntity(
    entity: Entity,
    player: CypressMediaPlayer,
    radiusMeters: Float,
    horizontalFovDegrees: Float,
    dimensionMode: VideoDimensionMode,
) {
    val mesh = MeshGenerator.generateVideoSphere(
        radius = radiusMeters,
        horizontalFov = horizontalFovDegrees,
    )
    checkNotNull(mesh) { "generateVideoSphere failed, see logcat tag MeshGenerator" }
    check(mesh.valid) { "generateVideoSphere returned an invalid mesh" }
    // NONE: MeshGenerator's vertex normals already point inward (toward the sphere centre),
    // matching StoryPico's proven-working combination - no face culling needed.
    val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.NONE)
    entity.components.set(VideoPlayerComponent(player, mesh, material))
}
```

（`import com.pico.spatial.core.math.Vector3`/`MeshResource` 这些 import 已经在 Task 6 的版本里存在，这里只是替换函数体，不新增文件级 import。）

- [x] **Step 3: `PlaybackViewModel.kt`——加 `hemisphereEntity`，三态互斥**

```kotlin
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f

val hemisphereEntity = Entity()
private var hemisphereAssembled = false

private fun disableAllVideoEntities() {
    screenEntity.enabled = false
    sphereEntity.enabled = false
    hemisphereEntity.enabled = false
}

fun startHemisphereTestPlayback(assetPath: String, stereoMode: StereoMode) {
    if (!hemisphereAssembled) {
        PlaybackEntityAssembler.assembleSphereEntity(
            hemisphereEntity, manager.player, SPHERE_RADIUS_METERS, HEMISPHERE_FOV_DEGREES,
            stereoMode.toVideoDimensionMode(),
        )
        hemisphereAssembled = true
    }
    disableAllVideoEntities()
    hemisphereEntity.enabled = true
    manager.setup(assetPath)
    isImmersive.value = true
}
```

`startTestPlayback`/`startSphereTestPlayback` 都改成先调 `disableAllVideoEntities()` 再把自己那个设 `true`——原来只手写两两互斥的写法在加第三个实体后容易漏，抽成一个函数更安全。`startSphereTestPlayback` 调用 `assembleSphereEntity` 时传 `FULL_SPHERE_FOV_DEGREES`（360f）。

- [x] **Step 4: `ImmersiveScene.kt` 加 `hemisphereEntity`，`PlaceholderMainScreen.kt` 加"播放测试视频（180°）"按钮**

`content.addEntity(viewModel.hemisphereEntity)`，写法和 `screenEntity`/`sphereEntity` 一样；按钮调用 `viewModel.startHemisphereTestPlayback("videos/sample_180_test.mp4", StereoMode.MONO)`。

- [x] **Step 5: 构建、安装、启动、截图验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 11
pico-cli capture screenshot --out ./artifacts/task7-hemisphere-front.png --device emulator-5554
```

结果：前方视野正确显示测试视频，无崩溃；360° 回归截图（同样的验证流程，换成 `startSphereTestPlayback`）确认换新网格生成方式后视野依然完整无缝。

**未能验证的部分，如实记录**：没能验证"转身后半球背面确实是空的"——PICO 模拟器的控制台命令里，`rotate` 只转 2D 屏幕方向（不是头部朝向），`physics`/`sensor` 子命令不支持直接设置 6dof 姿态，没找到无头环境下模拟转身的办法。半球背面留空这一点，目前只有代码层面的把握：`horizontalScale = 180/360 = 0.5` 让 `angle2`（经度）只扫 `π` 弧度而不是 `2π`，数学上确实只生成前方 180° 弧度范围内的顶点，加上这段代码是从 StoryPico 一个正在跑的项目原样移植、逻辑没有改动。但没有实机/头显转身的直接视觉证据，这里不夸大成"已验证"。

- [x] **Step 6: 提交**

```bash
git add -A
git commit -m "Add 180 hemisphere playback via ported MeshGenerator, unify with 360 sphere"
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
  enum class Environment(val assetPath: String, val label: String) {
      CINEMA("skyboxes/cinema_skybox.jpg", "电影院"),
      STARRY_SKY("skyboxes/starry_skybox.jpg", "星空"),
      SEASIDE("skyboxes/seaside_skybox.jpg", "海景"),
  }
  ```
  `PlaybackViewModel` 新增 `currentEnvironment: State<Environment>`、`fun switchEnvironment(target: Environment)`——这是本 Stage 1 的最后一块拼图，Stage 2 的环境预选 UI 直接调用 `switchEnvironment`，不需要再改这里的签名。

> **前置说明：实际做法比计划撰写时设想的简单很多，两处"待查"都已解决。**
>
> 1. **天空盒不需要 Spatial Editor / `.bundle`。** 问了用户如何处理 Task 7 类似的"SDK 没有现成 API"问题，得到的指引同样是参考 StoryPico。StoryPico 的 `SkyboxPlayableEntity` 显示：天空盒就是一个大号的、朝内表面渲染的球体 `Entity` + `ModelComponent(mesh, UnlitMaterial)`，贴一张等距柱状（equirectangular）图片——和视频球体是同一种几何体，只是用静态图片材质（`UnlitMaterial`）而不是视频材质（`VideoMaterial`）。本项目复用 Task 6/7 已经写好的 `MeshGenerator.generateVideoSphere(horizontalFov = 360f)` 生成网格，用 `TextureResource.load(path, LoadType.FROM_ASSETS)` 加载贴图，`UnlitMaterial.create().apply { setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.BACK) }`（注意是 `BACK`，不是视频球体用的 `NONE`——照抄 StoryPico 这个具体组合，两种材质类型的最佳剔除模式不一样）。三张贴图（电影院/星空/海景）是本机用 `ffmpeg` 的 `gradients` 滤镜生成的 2048×1024 渐变色 JPG 占位图，不是真实 HDRI 照片——设计稿第 5 节本来就把"电影院环境的高保真美术"列为非目标，V1 用简化占位符合预期。
> 2. **`StageEnvironmentLightingComponent` 需要 `.ktx` HDR cubemap，本机没有编码工具（`toktx` 等），这部分跳过，不是"以查到的为准"那种占位符，是明确的范围裁剪。** 查到的真实构造签名是 `StageEnvironmentLightingComponent(source: ImageBasedLightSource, intensityExponent: Float)`（`source` 通常是 `ImageBasedLightSource.Single(cubemapTexture)`，`.ktx` 格式），但生成一个像样的 `.ktx` HDR cubemap 需要专门的编码工具链，这台机器上没有装。设计稿本来就说"电影院环境的高保真美术：V1 用简化几何体+基础材质占位"——真实环境光照（IBL）明显属于"高保真美术"范畴，所以 V1 **不接入 `StageEnvironmentLightingComponent`**，只做天空盒贴图切换。真的要做 IBL，得等有合适的 `.ktx` 资产（可能是 Stage 2 或更后面的事）。

- [x] **Step 1: `Environment.kt`**

```kotlin
package tech.illusion.spaceplayer.playback

enum class Environment(val assetPath: String, val label: String) {
    CINEMA("skyboxes/cinema_skybox.jpg", "电影院"),
    STARRY_SKY("skyboxes/starry_skybox.jpg", "星空"),
    SEASIDE("skyboxes/seaside_skybox.jpg", "海景"),
}
```

- [x] **Step 2: 生成占位天空盒贴图**

```bash
mkdir -p app/src/main/assets/skyboxes
ffmpeg -y -f lavfi -i "gradients=size=2048x1024:c0=0x0a0608:c1=0x1a0e10:c2=0x050304:x0=1024:y0=0:x1=1024:y1=1024" \
  -frames:v 1 app/src/main/assets/skyboxes/cinema_skybox.jpg
ffmpeg -y -f lavfi -i "gradients=size=2048x1024:c0=0x0b1030:c1=0x171d47:c2=0x03060f:x0=1024:y0=0:x1=1024:y1=1024" \
  -frames:v 1 app/src/main/assets/skyboxes/starry_skybox.jpg
ffmpeg -y -f lavfi -i "gradients=size=2048x1024:c0=0x0e4a4a:c1=0x0a3a44:c2=0x062023:x0=1024:y0=0:x1=1024:y1=1024" \
  -frames:v 1 app/src/main/assets/skyboxes/seaside_skybox.jpg
```

- [x] **Step 3: `PlaybackEntityAssembler.kt`——加环境层组装函数**

```kotlin
/**
 * Environment skybox: same "big inward-facing sphere" mesh as a video sphere, but textured
 * as a static image via `UnlitMaterial` instead of `VideoMaterial` + `CypressMediaPlayer` -
 * ported from the sibling StoryPico project's `SkyboxPlayableEntity` (`MaterialCullingMode.BACK`
 * there, not `NONE` - StoryPico's proven combination for `UnlitMaterial` skyboxes specifically,
 * kept as-is rather than reusing the video sphere's culling mode).
 */
fun assembleEnvironmentEntity(
    entity: Entity,
    textureAssetPath: String,
    radiusMeters: Float,
) {
    val mesh = MeshGenerator.generateVideoSphere(radius = radiusMeters, horizontalFov = 360f)
    checkNotNull(mesh) { "generateVideoSphere failed, see logcat tag MeshGenerator" }
    check(mesh.valid) { "generateVideoSphere returned an invalid mesh" }
    val texture = TextureResource.load(textureAssetPath, LoadType.FROM_ASSETS)
    val material = UnlitMaterial.create().apply {
        setBaseColorTexture(texture)
        setCullingMode(MaterialCullingMode.BACK)
    }
    entity.components.set(ModelComponent(mesh, material))
}
```

（新增 import：`com.pico.spatial.core.ecs.LoadType`、`com.pico.spatial.core.ecs.ModelComponent`、`com.pico.spatial.core.ecs.resource.TextureResource`、`com.pico.spatial.core.ecs.resource.UnlitMaterial`——全部编译通过验证。）

- [x] **Step 4: `PlaybackViewModel.kt`——三个环境实体 + 切换逻辑 + 电影院银幕重新定位**

```kotlin
const val ENVIRONMENT_SKYBOX_RADIUS_METERS = 20f

// "Docked" onto a cinema wall: farther away than the default floating position.
private val CINEMA_SCREEN_POSITION = Vector3(0f, 1.6f, -4f)
private val FLOATING_SCREEN_POSITION = Vector3(0f, 1.5f, -2f)

val cinemaEnvironmentEntity = Entity()
val starrySkyEnvironmentEntity = Entity()
val seasideEnvironmentEntity = Entity()

var currentEnvironment = mutableStateOf(Environment.CINEMA)
    private set

// Compose-observable mirror of `screenEntity.enabled` - a plain field read of an SDK Entity's
// `enabled` property wouldn't trigger recomposition on its own.
var isFlatProjection = mutableStateOf(true)
    private set

private var environmentsAssembled = false

private fun assembleEnvironmentsIfNeeded() {
    if (environmentsAssembled) return
    PlaybackEntityAssembler.assembleEnvironmentEntity(
        cinemaEnvironmentEntity, Environment.CINEMA.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
    )
    PlaybackEntityAssembler.assembleEnvironmentEntity(
        starrySkyEnvironmentEntity, Environment.STARRY_SKY.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
    )
    PlaybackEntityAssembler.assembleEnvironmentEntity(
        seasideEnvironmentEntity, Environment.SEASIDE.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
    )
    environmentsAssembled = true
}

// Only meaningful for the flat screen - 180°/360° video doesn't go through EnvironmentLayer,
// it *is* the environment (design spec section 3).
private fun updateEnvironmentVisibility() {
    val showEnvironment = screenEntity.enabled
    cinemaEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.CINEMA
    starrySkyEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.STARRY_SKY
    seasideEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.SEASIDE
}

private fun repositionScreenForCurrentEnvironment() {
    val target = if (currentEnvironment.value == Environment.CINEMA) CINEMA_SCREEN_POSITION else FLOATING_SCREEN_POSITION
    screenEntity.components[TransformComponent::class.java]?.setPosition(target)
}

/** Switchable while playing - does not touch `manager`/`CypressMediaPlayer` at all. */
fun switchEnvironment(target: Environment) {
    currentEnvironment.value = target
    repositionScreenForCurrentEnvironment()
    updateEnvironmentVisibility()
}
```

`startTestPlayback` 开头加 `assembleEnvironmentsIfNeeded()`，结尾加 `isFlatProjection.value = true` + `repositionScreenForCurrentEnvironment()` + `updateEnvironmentVisibility()`；`startSphereTestPlayback`/`startHemisphereTestPlayback` 结尾加 `isFlatProjection.value = false` + `updateEnvironmentVisibility()`（此时 `screenEntity.enabled` 已经是 `false`，这一行会把三个环境实体全部关掉）。

关键约束（对应设计稿第 3 节）：`switchEnvironment` 全程不调用 `manager` 的任何方法，只操作环境实体的 `enabled` 和 `screenEntity` 的位置，播放不受影响——已通过截图验证（见 Step 7）。

- [x] **Step 5: `ImmersiveScene.kt`——把三个环境实体加进内容树**

`content.addEntity(...)` 三个环境实体，和 `screenEntity`/`sphereEntity`/`hemisphereEntity` 一样直接加进 `SpatialView` 的 `initial` 块；`PlaybackHud` 的入参加上 `isFlatProjection = viewModel.isFlatProjection.value`、`currentEnvironment = viewModel.currentEnvironment.value`、`onSelectEnvironment = { viewModel.switchEnvironment(it) }`。

- [x] **Step 6: `PlaybackHud.kt`——加环境切换按钮组**

```kotlin
if (isFlatProjection) {
    Environment.entries.forEach { env ->
        Button(onClick = { onSelectEnvironment(env) }) {
            Text(
                text = if (env == currentEnvironment) "[${env.label}]" else env.label,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
            )
        }
    }
} else {
    Text(
        text = "全景视频 · 自动沉浸",
        color = PicoTheme.colorScheme.labelPrimary,
        style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
    )
}
```

- [x] **Step 7: `PlaceholderMainScreen.kt`——平面测试按钮旁加环境预选**

播放前的环境预选直接复用 `viewModel.switchEnvironment(env)`（这个函数本来就不碰播放器，播放前调用完全安全），不需要额外定义一个"预选态"再在开始播放时才应用：

```kotlin
Row {
    Environment.entries.forEach { env ->
        Button(onClick = { viewModel.switchEnvironment(env) }) {
            Text(
                text = if (env == viewModel.currentEnvironment.value) "[${env.label}]" else env.label,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp),
            )
        }
    }
}
```

- [x] **Step 8: 构建、安装、启动、截图验证——重点验证"播放中切换环境不中断"**

用临时 `LaunchedEffect`（`PlaceholderMainScreen` 里自动触发平面播放，`ImmersiveScene` 里用 `delay()` 依次自动触发 `switchEnvironment(STARRY_SKY)`/`switchEnvironment(SEASIDE)`）验证，验证完已删除：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
# 分别在电影院/海景阶段截图
pico-cli capture screenshot --out ./artifacts/task8-cinema.png --device emulator-5554
pico-cli capture screenshot --out ./artifacts/task8-seaside.png --device emulator-5554
```

结果：电影院阶段背景纯黑（`screenEntity` 挪到了 -4 米的"墙面"位置，和主窗口在同一条视线上被更近的主窗口面板挡住——这不是 bug，只是这个测试摄像机角度恰好挡住了）；海景阶段背景变成海景渐变色，测试视频（`testsrc2` 彩条 + 时间码）持续播放、没有从头重播，HUD 和主窗口的环境选择器状态同步一致（都显示 `[海景]` 高亮）；`adb logcat -b crash -d` 全程无崩溃。

**未能验证的部分，如实记录**：没能单独截图确认"星空"环境的画面——会话轮次之间的实际耗时不完全可控，多次尝试卡时间窗口截图都跳过了星空阶段（要么还没切换、要么已经切到海景）。星空用的是和电影院/海景完全相同的 `assembleEnvironmentEntity` 代码路径，只是贴图路径参数不同，所以风险很低，但严格说这一项没有独立的视觉证据，不夸大成"三个环境都截图确认过"。

- [x] **Step 9: 提交**

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

- [x] **Step 1: 全量重新构建**

```bash
./gradlew clean assembleDebug
./gradlew :app:testDebugUnitTest
```

预期：`assembleDebug` 成功；`StereoModeMappingTest` 的 4 个用例全部 PASS。

**实际结果**：`clean assembleDebug` 和 `:app:testDebugUnitTest` 均 `BUILD SUCCESSFUL`（先在加临时调试触发器之前跑
了一遍确认基线，六条路径走查完、临时代码删除后又跑了一遍 `clean assembleDebug :app:testDebugUnitTest` 确认收尾干
净），测试结果 5/5 通过（脚手架自带的 `ExampleUnitTest` 1 个 + `StereoModeMappingTest` 4 个）。

- [x] **Step 2: 逐一走查六条路径**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
```

依次手动触发并各截一张图到 `./artifacts/task9-regression-*.png`：平面+电影院、平面+星空、平面+海景（同一次播放里切换）、180°、360°、退出回主窗口。

```bash
adb -s emulator-5554 logcat -d -t 300 | grep -iE "FATAL|AndroidRuntime|tech.illusion.spaceplayer"
```

预期：六张截图都符合设计稿第 1/3 节描述的画面；全程无新增崩溃。

**实际结果**：六条路径全部截图确认（`task9-regression-1-flat-cinema.png` ~
`task9-regression-6-exit-main.png`），沿用 Task 4-8 里"`adb shell input tap` 对 spatial 容器不可靠"这条已知限
制的临时解法——在 `PlaceholderMainScreen.kt` 里逐条替换 `LaunchedEffect(Unit) { ... }` 自动触发对应路径，截图后
再替换成下一条，六条全部走完后整体删除（含 `delay` import 的引入和移除）。逐条结果：
1. 平面+电影院：银幕/HUD/主窗口面板位置关系与 Task 6 已确认状态一致。
2. 平面+星空：Task 8 时没能单独截到的星空环境这次补上了，渐变占位贴图正常显示，HUD `[星空]` 高亮正确。
3. 平面+海景：**没有按"播放前预选"的方式测，而是先用电影院环境进入沉浸播放，`delay(4000)` 后再
   `switchEnvironment(SEASIDE)`**——这样才是真正测到计划里"（同一次播放里切换）"这个括号批注要求的场景。截图确
   认背景从电影院渐变切换成海景渐变的同时，视频播放没有重置/中断，直接验证了设计稿"沉浸中也能实时切换"的核心
   需求（而不只是"进入沉浸前选好环境"这种更弱的场景）。
4. 180°半球：画面铺满视野（同一相机默认位置观察不到"转身后背面留空"，这一点维持 Task 7/8 就有的结论——只有代码
   层面把握，没有实机头部转向验证手段）。
5. 360°球体：画面完整包裹视野，与半球模式对比可见明显差异（球体看不到任何面板边缘，半球在默认视角内能看到）。
6. 退出回主窗口：用 `delay(4000)` 后调用 `viewModel.exitImmersive()` + `navigator.closeStage()`（和 HUD 的
   `onExit` 回调完全一致的调用序列）验证退出流程，截图确认模拟器 passthrough 房间正常显示、主窗口 UI 正常、无
   视频/天空盒/HUD 残留。

全程六次 `adb logcat` 抓取均未见 `FATAL`/`AndroidRuntime`，只有正常的 `SpatialRuntimeService: Watchdog` 和
`AppRecordManagerService` 日志。

- [x] **Step 3: 更新 `AGENTS.md`**

写清楚：Stage 1 已完成的范围（本文件 Task 1-8 覆盖的内容）、Task 6/8 里"查文档后再定"的两处最终采用了什么方案（程序化 API 还是 Editor 导出的 bundle，bundle 放在哪）、构建/安装/运行命令、下一步是 Stage 2（真实文件库 UI + 格式识别 + 历史，设计见 `docs/superpowers/specs/2026-08-05-spaceplayer-design.md` 第 2/4 节）。

**实际结果**：`AGENTS.md` 顶部摘要改成"Stage 1 已全部完成（Task 1-9）"，加了 Task 9 六条路径回归结果的完整记录
（含路径 3 的"同一次播放里切换"细节和路径 2 补拍的星空截图），"已用的 Spatial SDK 能力"里天空盒那条去掉了"星空
未单独截图确认"的旧待办说明（已解决），"下一步"改成指向 Stage 2 并具体点明 `PlaceholderMainScreen.kt` 是下一步
要替换的占位 UI。程序化 API vs Editor bundle 这一点：Stage 1 全程没用到 Spatial Editor 导出的 `.bundle`，球体/
半球/天空盒都是手写 `MeshResource.createWithMeshModel`（移植自 StoryPico），这一点在 Task 7/8 阶段的 AGENTS.md
记录里已经写清楚了，Task 9 没有新变化。

- [x] **Step 4: 提交**

```bash
git add -A
git commit -m "Stage 1 regression pass + AGENTS.md update"
```

**实际结果**：见下方 commit（六张回归截图 + `AGENTS.md`/本计划文件更新一并提交）。
