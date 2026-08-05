# AGENTS.md — SpacePlayer

## 这个项目是什么

`SpacePlayer`（包名 `tech.illusion.spaceplayer`）是一款 PICO Spatial SDK 沉浸式视频播放器，参考
visionOS 应用 [Moon Player](https://moonvrplayer.com/zh/moon-player-apple-vision-pro)。完整产品规划见
`docs/superpowers/specs/2026-08-05-spaceplayer-design.md`。

当前处于 **Stage 1（项目骨架 + 沉浸播放核心）**，计划见
`docs/superpowers/plans/2026-08-05-stage1-immersive-playback-core.md`。Stage 1 只用硬编码测试视频跑通
"平面 → 环境化平面（电影院/星空/海景，可实时切换）→ 180°半球 → 360°球体" 这条播放链路，不含真实文件库 UI
（Stage 2）、不含字幕（Stage 3）。

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

## 关键文件

- `Main.kt` — `pico-cli` 生成时是 `DefaultStage { PicoTheme { HomeStage() } }`；Task 4 起会改造成
  `DefaultWindowContainer`（占位主窗口）+ `Stage(id = "ImmersiveStage")` 两个容器。
  **真实 import 路径**（比设计/计划文档写作时凭旧版文档猜测的更准确，以生成代码为准）：
  `DefaultStage`/`SpatialAppScope` 来自 `com.pico.spatial.ui.foundation.dsl`；`SpatialView` 来自
  `com.pico.spatial.ui.foundation.content`；`PicoTheme`/`Text` 来自 `com.pico.spatial.ui.design`；
  frosted-glass 背景用 `com.pico.spatial.ui.foundation.material.backgroundMaterial` +
  `com.pico.spatial.ui.platform.Material`（这是 SDK 自己的 `Material` 类型，不是 androidx Material/Material3，
  没有违反 SpatialUI-only 规则）。`DefaultWindowContainer`/非默认 `Stage(...)`/`LocalSpatialNavigator`/
  `StageStyle` 的确切 import 待 Task 4 实际编译验证后在这里更新。
- `content/HomeStage.kt` — 脚手架自带示例：加载 `asset://box.usdz`，挂一个文字 `AttachmentPanel`。Task 4 后
  会被 Stage 1 自己的 `ImmersiveScene.kt` 取代/共存。
- `app/build.gradle.kts` / `gradle/libs.versions.toml` — `spatialBom = "0.13.3"`，`compileSdk/minSdk/targetSdk
  = 35`，无 Material/Material3 依赖（SpatialUI-only 自检通过）。

## 已用的 Spatial SDK 能力

- `DefaultStage` + `SpatialView` + `Entity`/`TransformComponent`（脚手架自带）
- 计划新增（Task 2 起）：`VideoPlayerComponent` + `CypressMediaPlayer`、`VideoMaterial`/`VideoDimensionMode`、
  `MeshResource.createVideoPanel`、`AttachmentPanel` 播放 HUD

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

Task 2：`PlaybackState`/`PlaybackManager`（封装 `CypressMediaPlayer`）+ 打包一个测试用平面视频资源。
