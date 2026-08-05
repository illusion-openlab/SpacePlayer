# AGENTS.md — SpacePlayer

## 这个项目是什么

`SpacePlayer`（包名 `tech.illusion.spaceplayer`）是一款 PICO Spatial SDK 沉浸式视频播放器，参考
visionOS 应用 [Moon Player](https://moonvrplayer.com/zh/moon-player-apple-vision-pro)。完整产品规划见
`docs/superpowers/specs/2026-08-05-spaceplayer-design.md`。

当前处于 **Stage 1（项目骨架 + 沉浸播放核心），Task 1-7 已完成并验证**，计划见
`docs/superpowers/plans/2026-08-05-stage1-immersive-playback-core.md`。Stage 1 只用硬编码测试视频跑通
"平面 → 环境化平面（电影院/星空/海景，可实时切换）→ 180°半球 → 360°球体" 这条播放链路，不含真实文件库 UI
（Stage 2）、不含字幕（Stage 3）。

Task 4/5/6/7 验证结果：平面测试视频（`sample_flat_test.mp4`，ffmpeg 合成的彩条测试图案）能在
`Stage("ImmersiveStage")` 里通过 `VideoPlayerComponent` + `CypressMediaPlayer` 正确渲染播放（模拟器截图确认，
时间码/帧计数器清晰可见）；银幕下方的 HUD 播放控制条（播放/暂停/退出）正确显示，loading 层在首帧渲染后正确隐藏，
点退出能正常 `closeStage()` 回到主窗口；360°/180° 测试视频都用移植自 StoryPico 项目的 `MeshGenerator`（手写网格 +
`createWithMeshModel`）渲染，360° 完整包裹视野无接缝（截图确认），180° 前方视野正确显示（截图确认，但转身后半球
背面是否真的留空——没找到能在模拟器里无头模拟转身的办法，这一点只有代码层面的把握，不是实机验证过的）。HUD 独立于
视频实体，三种模式下都能看到。全程无崩溃。

## 为什么这么设计

- 单一 `DefaultWindowContainer`（占位主窗口，选测试用例用）+ 单一共享 `Stage(id = "ImmersiveStage")`：见设计稿
  第 1 节的架构取舍——同一个 `CypressMediaPlayer` 驱动三选一的银幕实体，环境切换不触碰播放器。
- Task 1 用 `pico-cli project create --template stage` 引导：这是官方推荐的"immersive space from the
  start"路径（见 `spatial-app-onboarding` 技能的 template-playbook）。**注意**：`stage` 模板生成的默认容器是
  `DefaultStage`（脚手架自带一个盒子模型 + "Hello, Spatial SDK!" 文字面板），**不是**空白的
  `DefaultWindowContainer`——Task 4 会把 `Main.kt` 改造成设计稿要求的"默认平面窗口 + 非默认 ImmersiveStage"
  形状，届时会替换掉这个占位内容。

## 本机环境注意事项（下次构建前先看这里）

- **JDK 25（系统默认 `java`）和 Gradle 8.13 的 Kotlin DSL 解析不兼容**，`./gradlew` 会直接报
  `IllegalArgumentException: 25.0.2` 构建失败。构建前设置：
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
  （Android Studio 自带 JBR 21，./gradlew 用它没问题；`/opt/homebrew/Cellar/openjdk@17` 的 JDK 17 应该也可以，
  没有额外验证过。）
- `local.properties`（已在 `.gitignore` 里，不会提交）需要手动创建，内容：
  ```
  sdk.dir=/Users/zohar/Library/Android/sdk
  spatial.tools.dir=/Users/zohar/Library/PICO/sdk
  ```
- **本机连接的真机（`D3HDXD2D4363000138`）是 API 34**（PICO OS 版本较旧），装不了 `compileSdk/targetSdk = 35`
  的 APK（`INSTALL_FAILED_OLDER_SDK`）。验证请用模拟器：
  ```bash
  pico-cli emulator start --avd Pico_Emulator_0_13 --wait-timeout 180 -y   # API 36，兼容
  pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
  pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
  pico-cli capture screenshot --out ./artifacts/<name>.png --device emulator-5554
  ```
- pico-spatial-knowledge 的知识库（`graph.json`）在这台机器上一度缺失（`pico-cli mcp doctor` 报错），已用
  `pico-cli setup --tool claude-code --plugin pico-spatial-agentic-tools` 重新拉取 agent-vault 6.0.2——如果
  MCP 工具在新会话里查不到文档，可能需要额外跑一次 `graphify build`（重建 `graph.json`，比 `setup` 慢很多，
  涉及语义抽取）。
- **`adb shell input tap x y` 对空间容器（Stage、以及某些 WindowContainer 交互元素）不可靠**，2D 坐标注入不能
  可靠触发 spatial UI 的点击——不要在这上面反复试坐标（`spatial-emulator-usage`/`spatial-app-dev-workflow` 两个
  技能都写了这条限制）。需要验证"点击触发某个沉浸流程"时，临时在对应 Composable 里加一个
  `LaunchedEffect(Unit) { /* 和 onClick 一样的代码 */ }` 自动触发，验证完删掉。
- **模拟器冷启动后主窗口渲染有延迟**，`launch` 后至少 `sleep 8-10s` 再截图，`sleep 4-6s` 有时还看不到内容
  （不是崩溃，只是还没渲染完）。
- **`Entity()` 默认已经带一个 `TransformComponent`**——想设置位置用
  `entity.components[TransformComponent::class.java]?.apply { setPosition(...) }`，不要
  `entity.components.set(TransformComponent())`（会被拒绝，日志报 "component already exists" 但不崩溃，静默
  no-op，位置永远设置不上，实体停在世界原点，在 Stage 里通常等于看不见——这个坑排查花了不少功夫，见 Stage 1
  计划 Task 4 Step 8 的详细记录）。
- **SpatialUI 的 `Text`/`Button` 不设置 `style`/`fontSize` 会渲染成实际不可见的默认字号**，即使背景色块本身能
  正常显示。照抄 `content/HomeStage.kt`（Task 4 后已删除，但可以在 git 历史里找到）那样显式设置
  `style = PicoTheme.typography.titleLarge.copy(fontSize = ...)`。
- **默认 `DefaultWindowContainer` 必须在 `AndroidManifest.xml` 里加 `pico.spatial.windowcontainer.id`**（任意
  唯一字符串），漏了会崩溃：`IllegalStateException: Only support [SUIStage,SUIWindowContainer], but got a
  [name = PICO_SYSTEM_DEFAULT_WINDOWCONTAINER, ...]`。和 Stage 的 `pico.spatial.stage.id` 是同一类必需字段。
- **`MeshResource` 有一整套程序化几何体生成 API**（`createPlane`/`createVideoPanel`/`createSphere`/
  `createCylinder`/`createCone`/`createCapsule`/`createBox`/`createTorus`），**但没有半球/局部扫描角函数**——
  球体/半球最终没有用这套内置 API，改用了下面这条经验里的手写网格方案。这条"有一整套程序化 API"的信息只在 SDK
  **6.0** 版本的文档库里查得到（`spatial-sdk_resource-management_mesh.md`），项目实际用的是 spatialBom 0.13.3，
  编译验证过这些函数在 0.13.3 里也存在。查文档时如果只查到旧版本 agent-vault（0.13）内容不全，记得同时查一下
  6.0 版本的（`/Users/zohar/Library/PICO/sdk/6.0/agent-vault/`），接口签名通常是稳定的。
- **半球（180°）网格：参考同工作区 `StoryPico` 项目（`/Users/zohar/WorkSpace/Project/StoryProjects/StoryPico`，
  同样 spatialBom 0.13.3）的 `VideoPlayableEntity`/`MeshGenerator`。**`MeshResource` 没有半球函数，StoryPico 的
  解法是手写顶点数据（球坐标参数方程，水平扫描角按 FOV 缩放：360° 传满 `2π`，180° 只传 `π`），通过
  `MeshResource.createWithMeshModel(MeshModel(positions, triangleIndices, normals, uv0), name)` 导入。已经原样
  移植到本项目的 `ecs/MeshGenerator.kt`，编译通过，360°/180° 都截图验证过。有类似"SDK 没有现成 API"的困难时，先
  看看 StoryPico 有没有已经解决过。
- **实体的子实体可见性跟随父实体的 `enabled`**——如果一个 UI 元素（比如 HUD）需要在多个互斥切换 `enabled` 的实体
  （比如 `screenEntity`/`sphereEntity`/`hemisphereEntity`）之间都保持可见，不能把它挂成其中任何一个的子实体，要
  独立加入内容树、用固定绝对坐标定位。
- **PICO 模拟器没有找到无头方式模拟头部 6dof 转身**——控制台的 `rotate` 命令只转 2D 屏幕方向，`physics`/`sensor`
  子命令不支持直接设置姿态。需要验证"转身之后看到什么"这类效果时，如实说明这一步验证不了，不要假装截图证明了转身
  后的画面。

## 关键文件

- `Main.kt` — `DefaultWindowContainer { PlaceholderMainScreen() }` + `Stage(id = "ImmersiveStage") {
  ImmersiveScene() }`（`pico-cli` 生成时是 `DefaultStage { HomeStage() }`，Task 4 改造成现在这个形状，
  `content/HomeStage.kt`/`assets/box.usdz` 已删除）。
- **确认无误的真实 import 路径**（全部经 `./gradlew assembleDebug` 编译通过验证）：
  - `DefaultWindowContainer`/`Stage`/`SpatialAppScope`/`launch` → `com.pico.spatial.ui.foundation.dsl`
  - `SpatialView` → `com.pico.spatial.ui.foundation.content`
  - `PicoTheme`/`Text`/`Button` → `com.pico.spatial.ui.design`
  - `LocalSpatialNavigator`/`StageStyle` → `com.pico.spatial.ui.platform.containers`
  - `Entity`/`TransformComponent`/`VideoPlayerComponent` → `com.pico.spatial.core.ecs`
  - `CypressMediaPlayer`/`CypressMediaPlayerCallback`/`CypressMediaPlayerErrorCode`/`VideoDimensionMode` →
    `com.pico.spatial.core.ecs.video`
  - `VideoMaterial`/`MaterialCullingMode`/`BlendingMode`/`MeshResource` → `com.pico.spatial.core.ecs.resource`
  - frosted-glass 背景：`com.pico.spatial.ui.foundation.material.backgroundMaterial` +
    `com.pico.spatial.ui.platform.Material`（SDK 自己的 `Material` 类型，不是 androidx Material/Material3）
- `playback/PlaybackManager.kt` — 封装 `CypressMediaPlayer` 生命周期（`setup`/`play`/`pause`/`seekTo`/`reset`）。
- `playback/Projection.kt`、`playback/StereoMode.kt` — 投影/立体格式枚举，`StereoMode.toVideoDimensionMode()`
  有单元测试（`StereoModeMappingTest`，4/4 通过）。
- `ecs/PlaybackEntityAssembler.kt` — `assembleScreenEntity`（平面银幕，显式设置 `TransformComponent` 位置，见上面
  "必须设置位置"那条坑）+ `assembleSphereEntity`（360°/180° 共用，`horizontalFovDegrees` 参数区分，内部调用
  `MeshGenerator.generateVideoSphere`，`MaterialCullingMode.NONE`，球体保持在世界原点不额外设置位置——它本来就该
  包住用户）。
- `ecs/MeshGenerator.kt` — 从 StoryPico 移植的手写球体/半球网格生成器，见上面"半球网格"那条经验。
- `ui/PlaybackViewModel.kt` — Koin scoped 的共享状态：`screenEntity`/`sphereEntity`/`hemisphereEntity` 三态互斥
  `enabled`（`disableAllVideoEntities()` 统一处理，避免加第三个实体后手写两两互斥漏掉），
  `startTestPlayback`/`startSphereTestPlayback`/`startHemisphereTestPlayback` 三个入口。
- `di/PlaybackModule.kt` — Koin session scope，让 `DefaultWindowContainer` 和 `Stage` 两棵独立 Compose 树共享同一个
  `PlaybackViewModel`/`CypressMediaPlayer` 实例。
- `ui/PlaybackHud.kt`、`ui/LoadingErrorAttachment.kt` — HUD 播放控制条（播放/暂停/退出）和 loading/error 覆盖层，
  **不**挂在 `screenEntity`/`sphereEntity` 下面（见上面"子实体可见性跟随父实体"那条坑），独立加入内容树，固定绝对
  坐标（用户前方 1.5 米）。
- `ui/ImmersiveScene.kt` — `SpatialView(attachments = {...}, initial = {...}, update = {...})`：`update` 块里
  用 `PlaybackViewModel.showLoadingOverlay` 互斥控制 loading/HUD 两个 attachment 的 `enabled`。
- `app/build.gradle.kts` / `gradle/libs.versions.toml` — `spatialBom = "0.13.3"`，`compileSdk/minSdk/targetSdk
  = 35`，加了 `koin-android:3.5.6`，无 Material/Material3 依赖（SpatialUI-only 自检通过）。

## 已用的 Spatial SDK 能力

- `DefaultWindowContainer` + `Stage` + `SpatialView` + `Entity`/`TransformComponent`
- `VideoPlayerComponent` + `CypressMediaPlayer`、`VideoMaterial`/`VideoDimensionMode`、
  `MeshResource.createVideoPanel`——平面视频播放已跑通并截图验证
- `AttachmentPanel`（HUD + loading/error）+ `closeStage()` 退出流程——已跑通并截图验证
- `MeshResource.createWithMeshModel` + 手写 `MeshModel`（`ecs/MeshGenerator.kt`）——360°/180° 球体/半球播放已跑通
  并截图验证（180° 转身后背面留空这一点未做实机验证，见上面的坑）
- 还没用到：`StageEnvironmentLightingComponent`（Task 8）

## 如何构建/安装/运行

见上面"本机环境注意事项"。简要：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
pico-cli emulator start --avd Pico_Emulator_0_13 --wait-timeout 180 -y   # 若模拟器未运行
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
```

## 下一步

Task 8：三个沉浸式环境（电影院/星空/海景）+ 播放中实时切换。这一步需要天空盒/环境资产（HDRI 或简化 3D 场景）和
`StageEnvironmentLightingComponent`，两者目前都还没查过确切 API，动手前先查文档。
