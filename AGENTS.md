# AGENTS.md — SpacePlayer

## 这个项目是什么

`SpacePlayer`（包名 `tech.illusion.spaceplayer`）是一款 PICO Spatial SDK 沉浸式视频播放器，参考
visionOS 应用 [Moon Player](https://moonvrplayer.com/zh/moon-player-apple-vision-pro)。完整产品规划见
`docs/superpowers/specs/2026-08-05-spaceplayer-design.md`。

**Stage 1（项目骨架 + 沉浸播放核心）、Stage 2（真实文件库 UI + 格式识别 + 播放历史）、Stage 3（外部 `.srt`
字幕）都已完成**，计划分别见 `docs/superpowers/plans/2026-08-05-stage1-immersive-playback-core.md`、
`docs/superpowers/plans/2026-08-06-stage2-video-library.md`、
`docs/superpowers/plans/2026-08-06-stage3-subtitles.md`。当前主窗口是真实的本机视频浏览界面
（`ui/library/MainLibraryScreen.kt`），不再是 Stage 1 遗留的固定测试按钮占位页——"视频资源库/下载/历史/其它"
四分类 + 三级格式识别（容器探测→文件名→默认兜底）+ 手动覆盖 + 播放历史/偏好环境持久化全部接入真实数据，沉浸播放时
若视频同目录下（或用户手动指定）有同名 `.srt` 字幕文件，会作为独立于 HUD 的 `AttachmentPanel` 显示。

Task 4/5/6/7/8 验证结果：平面测试视频（`sample_flat_test.mp4`，ffmpeg 合成的彩条测试图案）能在
`Stage("ImmersiveStage")` 里通过 `VideoPlayerComponent` + `CypressMediaPlayer` 正确渲染播放（模拟器截图确认，
时间码/帧计数器清晰可见）；银幕下方的 HUD 播放控制条（播放/暂停/退出）正确显示，loading 层在首帧渲染后正确隐藏，
点退出能正常 `closeStage()` 回到主窗口；360°/180° 测试视频都用移植自 StoryPico 项目的 `MeshGenerator`（手写网格 +
`createWithMeshModel`）渲染，360° 完整包裹视野无接缝（截图确认），180° 前方视野正确显示（截图确认，但转身后半球
背面是否真的留空——没找到能在模拟器里无头模拟转身的办法，这一点只有代码层面的把握，不是实机验证过的）；电影院/海景
两个沉浸环境切换过程中测试视频持续播放不中断、HUD 与主窗口的环境选择状态同步一致。HUD 独立于视频实体，三种视频模式
下都能看到。全程无崩溃。

Task 9（端到端回归，六条路径全部截图确认，见 `./artifacts/task9-regression-{1..6}-*.png`）：
1. 平面 + 电影院 2. 平面 + 星空（星空环境画面本身在 Task 8 时没单独截图确认，这次补上了，渐变占位贴图正常显示）
3. 平面 + 海景，**且是在已经沉浸播放中途实时切换过去的**（不是播放前预选）——背景从电影院渐变切到海景渐变的同时
视频播放没有中断，直接验证了"沉浸中也能实时切换环境"这条核心需求 4. 180° 半球 5. 360° 球体 6. 退出沉浸回到主窗口
（`exitImmersive()` + `closeStage()` 后模拟器 passthrough 房间正常显示，主窗口 UI 正常，无视频/天空盒/HUD 残留）。
`./gradlew clean assembleDebug`、`:app:testDebugUnitTest`（5/5 通过）均成功。全程无崩溃（`adb logcat` 确认，
仅有正常的 Watchdog/AppRecordManagerService 日志，无 FATAL/AndroidRuntime）。

**Stage 2（真实文件库 + 格式识别 + 播放历史）验证结果**：Task 9 端到端回归七条路径全部截图/数据确认（见
`./artifacts/stage2-regression-{1..7}-*.png`）：1. 权限门（未授权时的引导页）2. 授权后"视频资源库"/"下载"分类
分别正确显示真实 MediaStore 文件（按 `RELATIVE_PATH` 是否以 `Download/` 开头区分）3. 格式修正弹层
（`SpatialPopup` + 两组 `SegmentControl`）第一次在真机上完整渲染确认 4. 真实文件（`content://` Uri，不是
assets 里的测试视频）在沉浸 Stage 里正确播放（`adb logcat` 确认 `AppRecordManagerService: isPlaying=true
fps=30`，不是只看截图外观）5. 播放历史在首帧渲染时写入、偏好环境在退出时持久化（直接读设备上的
`SharedPreferences` XML 文件确认，见下面的坑）6. "历史"分类正确显示刚播放过的视频 7. "其它"分类触发 SAF 系统
文件选择器，只列出视频文件，选中后正确加入选中态。`./gradlew clean assembleDebug :app:testDebugUnitTest`
27/27 单测通过，全程无崩溃。

Stage 2 过程中发现并修复的**关键 bug**：`AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)` 会让
`CypressMediaPlayer` 卡死在 `PREPARING`（不报错、不崩溃，只是永远"加载中"）——这个坑编译期和静态审查都查不出来，
只有真机播放一个通过 `ContentResolver.openFileDescriptor` 打开的真实文件才会暴露。修复：用
`pfd.statSize`（`ParcelFileDescriptor` 的 `fstat` 真实文件大小）代替 `UNKNOWN_LENGTH`。详见下面"本机环境注意
事项"和 Stage 2 计划 Task 7 的记录。

**Stage 3（外部 `.srt` 字幕）验证结果**：`./gradlew clean assembleDebug :app:testDebugUnitTest` 43/43 单测通过
（`SrtParserTest` 8 + `SubtitleCueLookupTest` 6 + `VideoPreferencesStoreTest` 新增 2 + Stage 1/2 遗留 27）。设备
验证：同目录同名 `.srt` 自动发现 + 字幕面板渲染正确的文本（截图确认显示"第一行字幕"，与 SRT 时间轴 0-3s 吻合，见
`./artifacts/stage3-fresh-verify.png`），手动指定入口的弹层文案（"字幕：未设置/已设置"）+ SAF 选择器弹出都截图
确认（见 `./artifacts/stage3-task7-*.png`）。**没有验证到的部分，如实记录，不假装测过**：① 字幕文本随播放位置从
"第一行字幕"切到"第二行字幕"这一步没有拿到一次干净的设备截图（本 Task 8 回归时模拟器合成器连续两次卡在同一张
半透明叠加的"幽灵帧"上，见下面"本机环境注意事项"里新增的坑）——这一步现在只有 `SubtitleCueLookupTest`（含边界
时间戳用例）的单测覆盖，不是端到端设备验证；② 在 SAF 选择器里点选一个具体 `.srt` 文件这一步没能用 adb 点击关闭
闭环（点击事件到不了这块虚拟屏幕，换了两个 display id 都无效，见 Stage 3 计划 Task 7 的记录），`onPickSubtitle`
走的是和 Stage 2 已验证过的视频导入同一条 `OpenDocument()` 代码路径，只是没能在 UI 层面点选到文件；③ "转头后字幕
位置/朝向真的跟着变"——和 Stage 1 的 180° 半球背面留空一样，PICO 模拟器没有无头模拟头部转身的办法
（`HMDTrackingProvider.start()` 在模拟器上直接失败，`HMD tracking provider start failed`），只在真机上才能验证；
④ SAF"其它"导入视频的字幕自动发现应返回空——只做了代码审查（`SubtitleDiscovery` 对缺失的 `DATA` 列有防御性
`columnIndex == -1` 判断），没有实机跑一次完整的 SAF 导入+播放。

Stage 3 过程中发现并修复的**关键 bug**：`SubtitleFollowComponent` 最初按计划直接
`entity.components.set(SubtitleFollowComponent())` 挂进 ECS，但原生 ECS 层只认自己内置的 `Component` 子类型，
对任意自定义 `Component` 子类一律静默拒绝（logcat: `component Component is not supported`），导致 `update` 块里
`components.get(SubtitleFollowComponent::class.java)` 永远拿 `null`，字幕跟随逻辑实际上从未执行过、编译和运行都
不报错。修复：`SubtitleFollowComponent` 改成用 `remember {}` 存在 Composable 局部变量里，完全不进 ECS
`components` 系统，直接把引用传给 `applySubtitleFollow`。详见 Stage 3 计划 Task 6 的记录。

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
- **但系统级 UI（运行时权限对话框、SAF 文件选择器）不是 spatial 容器，`adb tap` 对它们是有效的**——前提是**必须用
  `adb shell uiautomator dump /sdcard/window_dump.xml` 拿精确 `bounds` 算坐标**，不能凭截图肉眼估坐标（截图是
  3D 合成场景，像素位置和真实 Android 触摸坐标空间完全不是 1:1 映射，Stage 2 Task 7/9 都在这上面吃过亏——肉眼
  估的坐标点了好几次都点不中，换成 `uiautomator dump` 算出的 bounds 中心点一次就中）。SAF 的 `GridView` 文件
  项有时第一次 tap 只是"聚焦"，需要再点一次才真正触发选中/返回，遇到"点了但没反应"先重试一次而不是急着换方案。
- **`rememberLauncherForActivityResult` 的回调在这套多容器 spatial 架构下有时不会触发 UI 更新**——即使
  `dumpsys package` 确认权限已经 `granted=true`，App 自己的 Compose 状态也可能没跟着变（怀疑是 Activity Result
  回调链路在这套架构下的环境特性，没有深挖根因）。可靠的绕过办法：`adb shell am force-stop <pkg>` 之后重新
  `pico-cli app launch`，让 `checkSelfPermission`（或任何等价的"启动时重新读一次真实状态"逻辑）在全新启动里
  重新求值。
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
- **环境天空盒同样不需要 Spatial Editor / `.bundle`，也是一个大号球体 `Entity`。** 和视频球体（`ecs/MeshGenerator.kt`）
  共用同一个网格生成函数，只是材质换成 `UnlitMaterial`（静态图片）而不是 `VideoMaterial`（视频）；剔除模式也不一样，
  `UnlitMaterial` 天空盒用 `MaterialCullingMode.BACK`（不是视频球体的 `NONE`）——这是照抄 StoryPico
  `SkyboxPlayableEntity` 的组合，两个具体数值都不要混用。
- **`StageEnvironmentLightingComponent` 需要 `.ktx` HDR cubemap，本机没有编码工具（`toktx` 等）**，Stage 1 里没有接入
  这个组件，只做了天空盒贴图切换（纯视觉，没有真实环境光照/反射）。构造签名是
  `StageEnvironmentLightingComponent(source: ImageBasedLightSource, intensityExponent: Float)`，真的要做的话先解决
  `.ktx` 资产从哪来的问题。
- **会话轮次之间的实际耗时不完全可控**，用 `sleep N` 卡固定延迟去截图某个"播放 N 秒后应该处于的状态"这类验证方式不
  可靠——写代码到实际截图之间可能隔了好几个工具调用/来回，真实经过的时间比 `sleep` 参数加起来大得多。需要卡时间窗口
  截图验证时，留足够宽的余量，或者接受"这个中间状态没能截图确认，但代码路径和已确认的状态相同"这种结论，不要为了
  凑一张截图反复重跑。
- **`AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)` 会让 `CypressMediaPlayer` 卡死在
  `PREPARING`**（不报错、不崩溃，`onError`/`onPrepared` 都不触发，`hasFirstFrameRendered` 永远是 false，"加载
  中…" 遮罩永远盖着）——只有真实 `content://` Uri（`ContentResolver.openFileDescriptor` 打开）会触发，Stage 1
  用 `context.assets.openFd()` 返回的 `AssetFileDescriptor` 自带正确长度，从来没暴露过这个坑。`PlaybackManager.
  setup(uri: Uri)` 现在用 `pfd.statSize`（`ParcelFileDescriptor` 的 `fstat` 真实文件大小）代替
  `UNKNOWN_LENGTH`。判断"播放是否真的在跑"不要只看截图画面像不像在动（短视频播完会停在某一帧，两次截图撞上同一
  停止帧看着像"没变"），改用 `adb logcat | grep AppRecordManagerService` 找 `isPlaying=true fps=30` 这类真实
  解码器状态。
- **`org.json.JSONObject` 和 `Uri.parse()`（Android 静态方法）在纯 JVM 单元测试里都会抛 `RuntimeException`**
  （这个项目没有接入 Robolectric，Android 单测 stub jar 没有这两者的真实实现）。`library/` 包下任何要写单测的
  持久化/解析逻辑，序列化改用不碰 Android 类的手写字符串格式（`VideoPreferencesStore` 用 `key=value;key=value`），
  凡是要处理 `Uri` 的纯逻辑改成全程用 `String` key（`PlaybackHistoryStore`），`Uri` 转换只在不参与单测的 UI/仓库
  层做（和 `VideoLibraryRepository`/`PlaybackManager` 触碰 Android 框架但不写单测是同一个约定）。
- **不要假设 SpatialUI 组件有 `onClick` 参数，先看真实签名**——`SideNavigationItem`/`ListItem` 看起来像是可点击的
  列表项组件，但两者的真实构造函数里都**没有** `onClick`，只是带 hover/haptic 效果的 `Row`/`Box`，点击手势要自己
  用 `Modifier.clickable(onClick = ...)` 接。这个坑在 Stage 2 Task 5 第一次 `./gradlew assembleDebug` 就报了
  `No parameter with name 'onClick' found`——写新 SpatialUI 组件调用前，哪怕看着眼熟，也应该实际确认参数列表，
  不要凭"看起来应该有"下笔。
- **自定义 `Component` 子类不能通过 `entity.components.set(...)` 挂进 ECS**——原生层只认自己内置的类型
  （`TransformComponent`/`ModelComponent`/`VideoPlayerComponent`/...），任意自定义子类一律静默拒绝
  （logcat: `component Component is not supported`），不抛异常也不崩溃，之后任何 `components.get(...)` 都会拿
  `null`。需要每帧手动驱动的自定义状态（比如 Stage 3 的 `SubtitleFollowComponent`），改成用 `remember {}` 存在
  Composable 局部变量里，完全绕开 `components` 系统，直接把引用传给驱动函数。见 Stage 3 计划 Task 6 的记录。
- **`HMDTrackingProvider.start()` 在 PICO 模拟器上直接失败**（logcat: `HMD tracking provider start failed`）——
  和"无头模拟头部转身"是同一类模拟器限制，任何依赖真实头部姿态（位置，不只是 Stage 1 已经记录过的朝向）的功能都
  没法在模拟器上端到端验证，只能验证"没有姿态数据时的兜底行为是否合理"。给这类跟随实体一个固定兜底
  `TransformComponent` 位置（参考 loading/HUD 的做法），而不是任由它停在世界原点等真实姿态数据，这样至少模拟器
  上也能看到内容，不是纯粹的真机专属验证。
- **自制测试视频的编码参数会直接决定 `CypressMediaPlayer` 能不能播，不是随便 `ffmpeg -f lavfi` 生成就行**——用
  `testsrc2` + High profile H.264 + 无音轨生成的测试视频在模拟器上会卡死在 `PREPARING`（logcat:
  `amc: no suitable codec` + `prepare player async: -3`），而已经验证能播的视频用的是 Constrained Baseline
  profile + AAC 音轨 + `moov` 在 `mdat` 之前的 faststart 布局（`ffprobe`/手写小脚本读 top-level box 顺序即可
  确认）。新写测试视频时照抄这套参数（`-profile:v baseline -movflags +faststart`，带一条哪怕是静音的音轨）
  更保险。但也要留意：`amc: no suitable codec` 和 `prepare player async: -3` 这两行本身其实是良性噪音——已验证
  成功播放的视频日志里也会有——真正判断"卡住了没有"要看 `showLoadingOverlay` 是否最终变 false / HUD 是否出现，
  不要看到这两行日志就断定失败。
- **连续快速 `am force-stop` + `am start` 重装/重启循环会把模拟器的画面合成器拖死**——`pico-cli capture
  screenshot`、`adb shell screencap`、`adb shell input keyevent KEYCODE_HOME` 全部对不上号，反复拿到同一张
  冻结的合成帧（用 `md5` 逐字节比对确认过），需要 `pico-cli emulator stop` + `pico-cli emulator start` 完整重启
  才能恢复（重启后是全新 AVD 数据，要重新装 App/`pm grant`/推测试文件）。这条限制在 Stage 3 Task 6/8 验证过程中
  连续踩中两次，两次都是短时间内多轮重装/重启导致的——以后连续验证同一个功能时，尽量减少重装次数（改一次代码测
  一次，而不是每个小改动都重装），且每次重启之间给合成器留出实际喘息时间，而不是几秒内连续折腾。
- **SAF 文件选择器渲染在独立的虚拟屏幕上（`dumpsys display` 能查到，`uniqueId` 里带
  `com.pxr.scenarioprovider`），`adb shell input tap` 在这块虚拟屏幕上有时完全不生效**——Stage 2 Task 7/9 验证
  视频导入时点击有效（当时可能命中的是默认屏幕或 GridView 布局），但 Stage 3 Task 7 用同样的 `uiautomator dump`
  拿到的精确 bounds，无论是默认 `input tap`、`input -d 0 tap` 还是 `input -d <virtual-display-id> tap`（虚拟屏幕
  id 用 `adb shell dumpsys display | grep mDisplayId` 查），对这次会话里的 ListView 布局选择器都没有任何反应。
  没有深挖是模拟器这次冷启动状态的问题还是 ListView/GridView hit-test 区域计算不同，遇到这种情况如实记录"点击
  没能验证"，不要靠反复重试同一坐标硬凑。
- **用临时 `LaunchedEffect(key)` 自动触发某个流程验证时，如果这个流程会导致 App 自己的窗口容器被销毁重建（比如
  `closeWindowContainer` 之后又 `openWindowContainer`），要格外小心 `key` 的选择**——窗口容器整个销毁重建时，
  这个容器里的整个 Compose 组合（包括所有 `remember` 状态）都会从头重新初始化，之前用来"只触发一次"的
  `remember { mutableStateOf(false) }` 守卫也会被重置。如果 `LaunchedEffect` 的 key 又是那种会在容器重建后重新
  满足触发条件的值（比如 `libraryItems`，容器重建后 `LaunchedEffect(hasVideoPermission) { refreshLibrary() }`
  会重新跑一次），临时调试代码就会在主窗口重新打开的瞬间又自动重新触发一遍，形成"打开沉浸→播放结束→主窗口重开→
  立刻又自动重新打开沉浸"的快速循环，几轮下来就会把模拟器的画面合成器拖死（连续两次踩到这个坑，后来才通过
  `adb logcat` 里两次一模一样的 `closeSpatialContainer`/`opening stage` 调用栈追出根因）。验证这类"退出会重建
  容器"的流程时，临时触发代码要么只触发一次就彻底移除自身逻辑（不要留一个可能被重新满足的守卫条件），要么改用
  `adb logcat` 追踪真实调用链（`closeWindowContainer`/`onContainerDestroy`/`openWindowContainer`/
  `onContainerForeground` 这些日志行本身就足够证明整条链路是否按预期跑通，不一定需要额外截图）。

## 关键文件

- `Main.kt` — `DefaultWindowContainer { MainLibraryScreen(modifier = Modifier.windowConstraints(...)) }` +
  `Stage(id = "ImmersiveStage") { ImmersiveScene() }`（`pico-cli` 生成时是 `DefaultStage { HomeStage() }`，
  Task 4（Stage 1）先改造成 `PlaceholderMainScreen`，Stage 2 Task 7 再换成真实的 `MainLibraryScreen`，
  `content/HomeStage.kt`/`assets/box.usdz`/`ui/PlaceholderMainScreen.kt` 都已删除）。
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
  `enabled`（`disableAllVideoEntities()` 统一处理，避免加第三个实体后手写两两互斥漏掉），单一入口
  `startPlayback(item: VideoItem)`（Stage 2 Task 7 把 Stage 1 的三个测试方法合并成这一个，按 `item.projection`
  内部分流），`currentItem` 记录当前播放项供历史写入/退出时持久化偏好环境使用。
- `di/PlaybackModule.kt` — Koin session scope，让 `DefaultWindowContainer` 和 `Stage` 两棵独立 Compose 树共享同一个
  `PlaybackViewModel`/`CypressMediaPlayer` 实例；也注册 `VideoPreferencesStore`/`PlaybackHistoryStore` 为
  Koin `single`（Stage 2 Task 8）。
- `ui/PlaybackHud.kt`、`ui/LoadingErrorAttachment.kt` — HUD 播放控制条（播放/暂停/退出/环境切换）和 loading/error
  覆盖层，**不**挂在 `screenEntity`/`sphereEntity`/`hemisphereEntity`/环境实体下面（见上面"子实体可见性跟随父实体"
  那条坑），独立加入内容树，固定绝对坐标（用户前方 1.5 米）。HUD 只在 `isFlatProjection` 为真时显示环境切换按钮组，
  球体/半球模式显示"全景视频 · 自动沉浸"提示文案。
- `ui/ImmersiveScene.kt` — `SpatialView(attachments = {...}, initial = {...}, update = {...})`：`update` 块里
  用 `PlaybackViewModel.showLoadingOverlay` 互斥控制 loading/HUD 两个 attachment 的 `enabled`。
- `playback/Environment.kt` — `CINEMA`/`STARRY_SKY`/`SEASIDE` 三态枚举，自带 `assetPath`/`label`。
- `app/src/main/assets/skyboxes/*.jpg` — ffmpeg 生成的三张 2048×1024 渐变占位天空盒贴图（不是真实 HDRI）。
- `app/build.gradle.kts` / `gradle/libs.versions.toml` — `spatialBom = "0.13.3"`，`compileSdk/minSdk/targetSdk
  = 35`，加了 `koin-android:3.5.6`（Stage 1）+ `mockito-core:5.14.2`/`androidx-activity-compose:1.9.3`/
  `androidx-documentfile:1.0.0`（Stage 2），无 Material/Material3 依赖（SpatialUI-only 自检通过）。

### Stage 2 新增文件（真实文件库 + 格式识别 + 播放历史）

- `library/VideoItem.kt`、`library/PlaybackHistoryEntry.kt` — 数据模型（`FormatSource`/`DetectedFormat` 枚举
  和数据类也在 `VideoItem.kt` 里）。
- `library/FilenameFormatDetector.kt` — 纯文件名关键词识别（`_180_`/`_360_`/`_sbs`/`_tb`/`_mvhevc` 等），13 个
  单测全过。
- `library/MultiviewTrackProbe.kt` — `MediaExtractorMultiviewProbe` 是**启发式**（同分辨率 HEVC 轨道数 ≥2），
  不是精确的 MV-HEVC 识别，标准 Android API 没有可验证的多视图分组信息读取接口，本机也没有真实 Apple 空间视频
  样本验证过准确率——文件名识别和手动覆盖仍是实际可靠的路径。
- `library/FormatDetector.kt` — 容器探测→文件名→默认兜底三级流水线。
- `library/storage/KeyValueStore.kt`（接口）/`SharedPreferencesKeyValueStore.kt`（实现）——所有本地持久化的
  统一底层抽象，方便单测用内存假实现替换。
- `library/VideoPreferencesStore.kt`、`library/PlaybackHistoryStore.kt` — 格式覆盖/偏好环境、播放历史，序列化
  和 key 类型的选择见上面"本机环境注意事项"里 `org.json`/`Uri.parse()` 那条坑。
- `library/VideoLibraryRepository.kt` — `MediaStore.Video.Media` 查询，按 `RELATIVE_PATH` 是否以 `Download/`
  开头区分"视频资源库"/"下载"。
- `ui/library/LibraryViewModel.kt` — 主窗口浏览状态（分类/筛选/选中项/列表），**不**入 Koin，直接在
  `MainLibraryScreen` 里 `remember` 持有（只在主窗口用，不需要跨容器共享）。
- `ui/library/MainLibraryScreen.kt` — 权限门 + `SideNavigation`（四分类）+ `LazyColumn`（真实列表）+
  `LibraryBottomBar` + `FormatCorrectionPopup` + SAF"其它"，替换掉 Stage 1 的 `PlaceholderMainScreen.kt`。
- `ui/library/VideoListCard.kt` — `ListItem` + `contentResolver.loadThumbnail()` 懒加载缩略图 + `Badge` 格式徽标。
- `ui/library/FormatCorrectionPopup.kt` — `SpatialPopup` + 两组 `SegmentControl`（投影/立体格式）+ Stage 3 加的
  字幕状态行/选择按钮。
- `ui/library/LibraryBottomBar.kt` — 环境选择器（复用 `Environment` 枚举）+ "开始播放"。

### Stage 3 新增文件（外部 `.srt` 字幕）

- `subtitle/SubtitleCue.kt`、`subtitle/SrtParser.kt` — 纯数据类 + SRT 解析器（处理 BOM、CRLF/LF、缺失/畸形序号，
  时间戳行靠正则定位不是固定行号），8 个单测全过。
- `subtitle/SubtitleCueLookup.kt` — `textAt(cues, positionMs): String` 纯函数，给定播放位置返回应显示的字幕文本
  （或空串），6 个单测全过，含边界时间戳用例。
- `subtitle/SubtitleDiscovery.kt` — `findSiblingSrt(context, videoUri)`，用 `MediaStore.Video.Media.DATA` 拿真实
  文件路径找同目录同名 `.srt`；SAF 导入的视频没有这个路径，代码里对缺失列有防御性 `columnIndex == -1` 判断，一律
  返回 `null`（未做实机验证，只有代码审查，见上面的坑）。
- `ecs/SubtitleFollowComponent.kt` — `SubtitleFollowComponent`（纯状态持有类，**不**经过 ECS `components`
  系统，见上面的坑）+ `applySubtitleFollow(entity, component, pose, deltaTime)`：位置延迟跟随（死区门控的 lerp）
  + 朝向跟随（手写 `slerpQuat`），移植自同工作区 `StoryPico` 项目的 `MoveWithCameraComponent`，合并了 StoryPico
  原本分开处理的位置/朝向更新（本项目只有一个跟随实体，不需要 StoryPico 那种通用 `TrackingManager` 架构）。
- `ui/SubtitleAttachment.kt` — 字幕面板 Composable，视觉复用 `PlaybackHud`/`LoadingErrorAttachment` 的
  `Material.Regular` 磨砂玻璃背景，不是照抄 StoryPico 原版的纯黑半透明底。
- `library/VideoItem.kt`（改）— 新增 `subtitleUri: Uri?` 字段。
- `library/VideoPreferencesStore.kt`（改）— `VideoPreferences` 新增 `subtitleUri: String?`（`String` 不是 `Uri`，
  避开单测里的 `Uri.parse()` 坑），序列化对这个字段单独用 `URLEncoder`/`URLDecoder`。
- `ui/PlaybackViewModel.kt`（改）— `hmdTrackingProvider: HMDTrackingProvider`、`currentSubtitleText`（Compose
  state）、`refreshSubtitleText()`（每帧从 `ImmersiveScene` 的 `update` 块调用）、`startPlayback`/`exitImmersive`
  分别接入 `hmdTrackingProvider.start()`/`stop()`。
- `ui/ImmersiveScene.kt`（改）— 加了字幕 `AttachmentPanel`，`update` 块里每帧算 `deltaTime`、调
  `applySubtitleFollow`；字幕实体给了一个固定兜底 `TransformComponent` 位置（模拟器上 `HMDTrackingProvider`
  拿不到真实姿态时的可见性保底，见上面的坑）。

## 已用的 Spatial SDK 能力

- `DefaultWindowContainer` + `Stage` + `SpatialView` + `Entity`/`TransformComponent`
- `VideoPlayerComponent` + `CypressMediaPlayer`、`VideoMaterial`/`VideoDimensionMode`、
  `MeshResource.createVideoPanel`——平面视频播放已跑通并截图验证
- `AttachmentPanel`（HUD + loading/error）+ `closeStage()` 退出流程——已跑通并截图验证
- `SpatialNavigator.closeWindowContainer(id)`/`openWindowContainer(id)`——沉浸 Stage 打开时主动关闭主窗口、
  Stage 销毁时（`DisposableEffect.onDispose`）重新打开，而不是指望 `closeStage()` 自己把主窗口带回来（两者是
  独立的容器生命周期）。这是 SDK 自己教程里"expand to immersive"推荐的写法（`SpatialNavigator.kt` 源码里两者都是
  普通 `fun`，不是 `suspend fun`，可以直接在 `DisposableEffect`/`onClick` 里同步调用，不需要 `coroutineScope.
  launch`）。已通过 `adb logcat` 完整追踪过一次真实的开→播放结束→自动返回主窗口全流程（`WindowContainer-
  SpacePlayerMainWindow onContainerDestroy` → 播放 `onCompleted` 触发 `closeStage()` → `Stage-ImmersiveStage
  onContainerDestroy` → `opening windowContainer SpacePlayerMainWindow` → `onContainerForeground`），全程无
  异常。
- `MeshResource.createWithMeshModel` + 手写 `MeshModel`（`ecs/MeshGenerator.kt`）——360°/180° 球体/半球播放已跑通
  并截图验证（180° 转身后背面留空这一点未做实机验证，见上面的坑）
- 环境天空盒（`ModelComponent` + `UnlitMaterial` + 复用的球体网格）+ 播放中实时切换——三个环境（电影院/星空/海景）
  都已截图验证，其中海景是在沉浸播放中途实时切换过去的，直接验证了核心需求
- `HMDTrackingProvider`/`HMDTrackingData`/`HMDPose`（`com.pico.spatial.tracking.hmd`）——Stage 3 用来驱动字幕
  面板的位置/朝向跟随，模拟器上 `start()` 直接失败（见上面的坑），只在代码层面 + 兜底位置渲染做了验证
- 还没用到：`StageEnvironmentLightingComponent`（需要 `.ktx` HDR cubemap，本机没有编码工具，Stage 1 不做）

### Stage 2 新用到的 SpatialUI 组件（均已在真机上截图验证，不只是编译通过）

- `SideNavigation`/`SideNavigationItem`（`com.pico.spatial.ui.design`）——左侧分类栏，**没有 `onClick` 参数**，
  点击手势要靠 `Modifier.clickable` 自己接。
- `LazyColumn`/`items`（`androidx.compose.foundation.lazy`）——PICO 用同名包重新发布了完整的 Compose Foundation
  分支（含 lazy list/grid），可以放心当标准 Compose 用。
- `ListItem`/`ListItemDefaults`/`Badge`（`com.pico.spatial.ui.design`）——视频列表行 + 格式徽标，`ListItem` 同样
  **没有 `onClick`**，选中高亮靠 `colors = ListItemDefaults.listItemColors(backgroundColor = ...)` 手动传。
- `SegmentControl`/`SegmentItem`（`com.pico.spatial.ui.design`）——有真实 `onClick` 参数，格式修正弹层里的
  投影/立体格式选择器。
- `SpatialPopup`（`com.pico.spatial.ui.design.windows`）——锚定在声明它的位置附近的轻量弹层，`dismissOnClickOutside
  = true`，格式修正弹层用这个而不是全屏 `AlertDialog`。
- `Modifier.windowConstraints(minWidth, minHeight, ...)`（`com.pico.spatial.ui.platform.resize`）——只能在
  `DefaultWindowContainer { ... }` 内容 lambda 的 receiver 作用域里直接调用，或者把已应用过的 `Modifier` 当参数
  往下传。
- `rememberLauncherForActivityResult`/`ActivityResultContracts.RequestPermission()`/`OpenDocument()`
  （`androidx.activity.compose`）——运行时权限请求 + SAF 文件选择器，`LaunchActivity` 的基类链
  （`SpatialLaunchActivity` → `SpatialStubActivity` → `FragmentActivity`）支持这套标准 Activity Result API，
  但回调有时不会触发 Compose 重组（见"本机环境注意事项"），需要 `am force-stop` + 重新 `launch` 兜底。Stage 3
  的字幕 `OpenDocument()` 选择器复用同一套机制，弹层 UI 本身截图确认过，但选择器内部点选具体文件这一步在这次会话
  里没能用 adb 点击验证通过（见上面的坑）。

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

Stage 1、Stage 2、Stage 3 均已完成，`docs/superpowers/specs/2026-08-05-spaceplayer-design.md` 第 1-4 节
（架构、数据模型/格式识别、沉浸环境、UI 布局含字幕）描述的 V1 范围已经全部实现；第 5 节列的是明确不做的非目标
（网络协议播放、FileSendApp 集成、内嵌字幕轨道/`.ass`、AI 2D→3D、电影院高保真美术、环境切换过渡动画），不是待办
的后续 Stage。

如果要继续往前推进，比较合理的方向是**补齐这次会话里如实标注过、还没做实机验证的几个点**（不是新功能，是把已经
写好的代码路径在真机上跑一遍）：
1. 真机上验证字幕面板的位置延迟跟随 + 朝向跟随是否符合预期（模拟器 `HMDTrackingProvider.start()` 直接失败，
   见"本机环境注意事项"）。
2. 真机或换一种点击方式，把"SAF 选择器里点选一个 `.srt` 文件"这一步跑通（这次会话里 adb 点击进不了那块虚拟屏幕）。
3. 真实 SAF 导入一个视频，确认字幕自动发现在这种场景下确实返回空、手动指定仍然可用（目前只有代码审查）。
4. 如果之后有新的功能性需求（比如非目标里提到的电影院高保真美术、字幕样式），从设计稿的"非目标"章节移出、单独
   立项写新的 spec/plan，而不是默认继续加到 Stage 3 里。
