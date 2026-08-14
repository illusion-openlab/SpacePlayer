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

**"返回主窗口"功能（2026-08-06）**：点击视频播放进入沉浸模式后主窗口会消失，这是因为 `Stage(StageStyle.Full)`
完全替代真实环境；`ImmersiveScene.kt` 现在用 `DisposableEffect` 显式 `closeWindowContainer(MAIN_WINDOW_ID)`
（进入时）/`openWindowContainer(MAIN_WINDOW_ID)`（退出时，`onDispose`）而不是依赖它自己重新出现——这是 SDK 自己
文档里"展开到沉浸模式"的既定模式。沉浸 HUD 的退出按钮改名"返回主窗口"，视频播放到 `onCompleted` 时也会自动走同一
条返回路径（`PlaybackManager.onPlaybackCompleted` → `PlaybackViewModel.returnToMainWindowRequested` →
`ImmersiveScene` 的 `LaunchedEffect`），替换掉之前退出即 `seekTo(0)` 死循环重播的行为。已通过完整 `adb logcat`
链路验证：主窗口容器销毁→播放到视频真实时长结束→`closeStage()`→主窗口容器重建并前台显示，全程无异常。

**视频资源库视觉重做（2026-08-06）**：用户对照一张模拟器截图和一张设计效果图，指出主窗口视觉语言（网格卡片、彩色
格式徽标、图标化侧栏、强调色主题、格式筛选入口）设计稿阶段从未真正实现过，一直是 SpatialUI 默认朴素样式。这次
按效果图整体重做：`LazyColumn` 单列行 → `LazyVerticalGrid` 三列网格卡片（`VideoGridCard.kt`，缩略图+居中播放
图标+彩色格式徽标+时长徽标+选中态强调色描边）；顶部加 `ToggleableChip` 格式筛选行接上 Stage 2 就写好但从未接 UI
的 `LibraryViewModel.selectFormatFilter`（之前是死代码）；侧栏加 `SideNavigation(header = "SpacePlayer")` +
每个分类项的图标（新增 5 个纯手绘 XML vector drawable，见下面"关键文件"，没有引入
`androidx.compose.material.icons`，避免碰 Material 命名空间）+ "其它·选择文件"从可选分类里拆出来做成侧栏底部
单独的虚线框按钮；`LibraryBottomBar` 环境选择器换成彩色圆点 `ToggleableChip`；主窗口 manifest 加
`pico.spatial.windowcontainer.materialbackground="0"` 关掉系统玻璃背景，根节点改成 `SpacePlayerPalette.kt`
里的固定深色背景（`design-style: opaque-root` 注释）。全程遵循 `spatial-ui-design-style` 技能的硬规则，
`scripts/verify-design-style.sh` 跑到 0 error/0 warning：所有非语义化层级色的固定色都标了
`// design-style: fixed-figma-color`，所有自定义 `.clickable(` 调用都补了共享 `interactionSource` 的
`LocalIndication.current` + `controllerHapticFeedback`。**唯一没做的**：自定义卡片没有 `spatialHoverEffect`——
这个项目实际锁定的 SDK 版本（0.13.3）里这个 API 只有底层 `SpatialHoverEffectRootScope` block 版本（要手写
scale/offset/alpha 动画节点），没有简单的 `enabled` 参数版本，风险收益比不划算，卡片退化成只有 `clickable` 自带
的按压反馈，没有原生 hover 动画（此前所有 built-in 组件如 `Button`/`ListItem` 的 hover 都是免费的，这是第一个
需要自己接 hover 的自定义组件）。已通过模拟器截图确认整体渲染符合预期（网格/彩色徽标/侧栏图标/筛选栏/环境圆点/
选中描边/深色背景全部正常），缩略图目前是纯黑色方块——这是测试视频本身首帧是黑色画面，不是缩略图加载代码的 bug。

**配色改暖色调（2026-08-06）**：用户反馈第一版视觉重做的深色主题"看起来太暗"，要求改成"暖和明亮"的色调。
`SpacePlayerPalette.kt` 整体换成暖白背景 + 暖沙色卡片 + 暖棕色文字层级 + 暖橙强调色（不再是深色背景 + 冷色系
teal/purple 徽标）；因为窗口根节点本来就是固定色覆盖系统玻璃（不是跟随系统深浅色的自适应色），这次把
`MainLibraryScreen.kt`/`VideoGridCard.kt`/`LibraryBottomBar.kt` 里原来还在用 `PicoTheme.colorScheme.label*`
（跟随系统深浅色）的文字/侧栏/按钮颜色也一并换成 `SpacePlayerPalette.kt` 里的固定色——否则自定义根背景是固定暖色、
但文字颜色还在跟系统深浅色自适应，系统如果是深色模式就会出现"暖色底+浅色系统文字"对比度错误的问题。格式徽标/
环境圆点颜色保留了饱和度较高的青色/紫色/蓝色作为小面积强调色（不是整体基调，跟暖白背景搭配是常见的"暖色画布+
彩色强调点"配色手法，没有为了"暖色"把所有颜色都往橙黄色系强推）。`scripts/verify-design-style.sh` 复查依然
0 error/0 warning，模拟器截图确认渲染正确。

**配色最终定版（2026-08-06）**：用户对暖色版又反馈"颜色不太合适"，要求先出几款方案再选——先用 `mcp__visualize`
做了两轮 HTML 效果图对比（先是几款柔和暖色调，用户觉得"需要明亮一些的颜色对比"；换成纯白底+高饱和强调色一轮后，
用户又觉得"这个太白了"），最后请求渲染真实的白底色块对比，从 4 款柔和白（极浅暖白/亚麻白/燕麦白/杏仁白）里选定
**极浅暖白 `#FFFBF7`** 作背景。强调色阶段直接在真机模拟器里改 `SpacePlayerAccent` 常量、重新编译安装、逐个截图
对比了 4 款候选（电光橙红 `#FF4500`/柠檬芥黄 `#FFB300`/番茄绯红 `#E63946`/芒果橙 `#FF6F00`），最终选定**番茄绯红
`#E63946`**。配套调整：卡片改成纯白 `#FFFFFF` 背景 + 常驻 1px 中性描边（`SpacePlayerBorder`，选中时描边变成
2dp 强调色而不是像之前那样只在选中态才有描边），文字层级改成更深的中性色（`#1A1A1A`/`#5C5347`/`#8C8275`）
以配合更高对比度的整体基调。**这次的经验**：色调这类主观决策，与其一次性猜一版让用户反馈修改，不如先用
`mcp__visualize` 出多版低成本效果图给用户挑方向，方向定了以后再进真实设备渲染做最终候选项对比——比反复"改一版
发一版真机截图再等反馈"来回快得多，且效果图和真实渲染两种媒介对普通人判断"要不要更暖/更亮/更白"这类问题来说
都有各自不可替代的作用（效果图快、真实渲染准）。

**对齐修复 + "视图被缩小"排查（2026-08-06）**：用户反馈"整体布局不太规整，部分元素没有对齐"+"整个视图貌似被缩小了，
现在连字都看不清"。逐个排查：

1. **对齐 bug 是真的，已修复**：`MainLibraryScreen.kt`/`LibraryBottomBar.kt`/`FormatCorrectionPopup.kt` 里有
   好几处 `Row { ... }` 混装了高度明显不同的子项（Icon+Text、标题 Text+筛选 Chip、环境 Chip+"开始播放"
   Button、字幕状态 Text+Button），但没有显式设 `verticalAlignment = Alignment.CenterVertically`——Compose
   Row 默认按顶部对齐，导致这些子项看起来一高一低。全部补上了。另外网格卡片之间的间距原来是每张卡片自己
   `Modifier.padding(8.dp)`，导致卡片之间的间隙是 8+8=16dp，但卡片到容器边缘只有 8dp，两侧不对称——改成
   `LazyVerticalGrid` 自带的 `contentPadding` + `Arrangement.spacedBy(16.dp)`，四周间距统一。还发现网格同一行里
   如果一张卡片显示"修正格式"提示行、另一张不显示（`formatSource != DEFAULT`），两张卡片底部会不齐——给
   `VideoGridCard` 的文字信息块加了 `heightIn(min = 96.dp)`，同一行卡片高度统一。
2. **"视图被缩小/字看不清"——排查后没找到代码层面的回归，如实记录**：对比了今天视觉重做三个版本（深色主题→暖色→
   最终番茄红）的 `adb shell uiautomator dump` 边界坐标，卡片和面板的像素尺寸**完全一致**，没有变化；又 diff 了
   这次重做前后的 `AndroidManifest.xml`/`Main.kt`，`windowConstraints`/`defaultsize`/`worldscaletype` 这些跟
   窗口真实尺寸相关的设置**全部没有改动**；字体大小（`fontSize`）跟重做前的 `VideoListCard.kt`/
   `MainLibraryScreen.kt` 逐项比对，除了卡片标题从 18sp 改成 16sp 以外**基本没变**。目前的结论：这次没有找到
   任何我改动的代码导致窗口或字体实际变小的证据。倒是在拍一张**完整未裁剪**的截图时发现，面板本身在模拟器
   房间场景里只占画面很小一块（悬浮在床头墙上，离"摄像机"位置较远/较小）——如果这就是用户看到"字看不清"的
   原因，那是这个窗口在 3D 场景里的**摆放位置/距离**导致的，跟这次改的 Compose 布局代码本身无关。**没有去猜测
   或者硬修一个不确定存在的问题**——如果用户是真机上看到的，需要用户确认具体是"离远了看不清"还是"字本身变
   小了"，才能判断下一步要不要动窗口摆放逻辑；如果反馈来自我发的对比截图，那是因为这几张图裁剪缩放比例
   前后不一致（`final_tomato_crop.png` 那批用的裁剪框比之前 `warm_02_crop.png` 那批宽很多、缩放倍数更低），
   不是应用本身的问题。

**画板三处细节调整落地（2026-08-06）**：用户看着 `mcp__visualize` 的最终配色画板，提了三点具体调整——"其它"要在
侧栏最下边、"开始播放"那一栏要在内容区最下边、"SpacePlayer"要更大且跟"视频资源库"底部对齐——要求应用到真实代码。
"开始播放"那一栏在真实 Compose 代码里其实已经是靠 `weight(1f)` 的网格 Box 撑到最下边了（画板是简化的 HTML，没有
体现这一点，代码不用改）。真正要动的是另外两处：

1. **"其它"移出 `SideNavigation` 的 `content` 插槽**，包进一个新的外层 `Column(Modifier.fillMaxHeight())`，
   `SideNavigation` 和"其它"之间插一个 `Spacer(Modifier.weight(1f))` 才能把"其它"推到侧栏最下面——
   `SideNavigation` 自己内部那层可滚动 Column 只会包裹内容高度，在它的 `content` 里塞一个 `weight()` 的 Spacer
   完全不起作用（父级没有约束高度可分配）。
2. **"SpacePlayer"和"视频资源库"分别包进等高（56dp）的 `Box(contentAlignment = Alignment.BottomStart)`**，
   两侧 Column 顶部再对齐同一个 padding（16dp），字号从 22sp 提到 28sp——两栏是完全独立的 Compose 子树，
   要视觉对齐只能靠"给两边相同的起始偏移 + 相同高度的容器 + 底部对齐"这套笨办法，没有跨子树的"共享基线"API。

**踩的坑（真机/模拟器都要小心）**：把"其它"从 `SideNavigation.content` 挪到外层普通 `Column` 后，第一版
忘记给这个新 Column 设置宽度——`SideNavigation` 自己内部有 `Modifier.width(IntrinsicSize.Min)` 把自己限制在
内容需要的宽度，但外层这个新 Column 没有任何宽度约束，导致"其它"Box 的 `fillMaxWidth()` 直接撑满了**整个
Row 的宽度**（不再是侧栏那一小条），把右边内容列表挤没了——症状是应用整个内容区域完全空白（标题、网格、
筛选栏全部不见），但 `adb logcat` 一条异常都没有（不是崩溃，是布局挤压），排查时一度怀疑是模拟器画面合成器
卡死（见上面"连续快速重装拖死合成器"那条坑）——用 `md5` 比对了两张间隔几秒的截图，确实字节完全相同，但**做了
一次完整的 `pico-cli emulator stop`/`start` 重启后 MD5 还是不变**，这才排除"合成器冻结"、确认是真实的布局
bug，最后靠 `adb shell uiautomator dump` 直接看到"其它"那个 View 的 bounds 横跨了整个屏幕宽度才定位到根因。
修复：给外层 Column 显式 `Modifier.width(220.dp)`。**教训**：把一个 UI 元素从某个内置组件的插槽（这类插槽通常
自带宽度/高度约束）搬到普通 `Column`/`Row` 里时，原来隐式生效的约束会跟着消失，`fillMaxWidth()`/`fillMaxHeight()`
的实际含义会变，必须显式补回等价约束，不能想当然认为"挪个位置不影响布局"；另外，"MD5 连续两次相同"只能说明
"没在动"，不能仅凭这一点就断定是合成器冻结——冻结的正确判定是"重启后依然相同"，否则可能是真 bug 被误判成
环境问题，反而错过了真正的根因。

**中英文国际化（2026-08-06）**：把全部用户可见文案迁到 Android 标准字符串资源，`res/values/strings.xml`
是英文默认（fallback），`res/values-zh/strings.xml` 是中文覆盖（`app_name` 是专有名词，两边共用默认值，
`values-zh` 里没有重复定义）。枚举的 label 函数（`Projection`/`StereoMode`/`FormatSource`/`Environment`/
`LibraryCategory`）之前分散成好几份：`Projection.label()` 在 `FormatCorrectionPopup.kt`/
`VideoGridCard.kt`/`MainLibraryScreen.kt`（叫 `filterLabel()`）里各写了一份完全相同的映射，
`Environment`/`LibraryCategory` 则是把中文文案直接塞进枚举构造参数（`val label: String`）——这两种写法都
没法做到"跟随语言环境"，所以趁这次改造：
- 新建 `ui/Labels.kt`，把这些 label 统一成 `@Composable` 扩展函数，内部走 `stringResource()`，同一个枚举
  只有一份权威实现，三份重复的 `Projection.label()` 全部删掉改成引用共享函数。
- `Environment`/`LibraryCategory` 枚举本身去掉 `label: String` 构造参数（固定中文字面量没法本地化），
  改成纯值枚举，label 完全交给 `ui/Labels.kt`。
- `StereoMode` 拆成两个函数而不是合并成一个：`badgeLabel()`（网格卡片用的紧凑缩写 SBS/TB/MV-HEVC）和
  `fullLabel()`（格式修正弹层 `SegmentControl` 用的完整描述"左右 3D"/"上下 3D"）——这两处本来就是不同的
  UI 用途，业务含义不同，不应该被"去重"合并成一个。
- 有一处不能直接在使用点调用 `stringResource()`：`MainLibraryScreen.kt` 里 SAF 导入视频取不到文件名时的
  兜底显示名（"导入的视频"），这段逻辑在 `rememberLauncherForActivityResult` 的回调 lambda 里，这个
  回调在 Activity Result 真正返回时才执行，已经脱离了组合（composition）上下文，不能调用 `stringResource()`——
  提前在 Composable 函数体里解析成 `val importedVideoDefaultName = stringResource(...)`，回调里直接引用
  这个已解析好的局部变量（闭包捕获，没有跨组合调用的问题）。
- 验证用了 `adb shell cmd locale set-app-locales <pkg> --locales zh-CN`（Android 13+ 的按应用切语言
  命令）而不是切整机系统语言——不需要重启设备，也不会影响这台模拟器上其它已装应用或系统语言，切完记得再
  `set-app-locales <pkg> --locales ""` 清空覆盖，避免影响下次会话。中英文都截图/`uiautomator dump`
  核对过主界面和格式修正弹层的全部文案。

**底部操作栏垂直居中对齐（2026-08-06）**：用户看着截图指出侧栏"其它·选择文件"和内容区"环境选择器 + 开始播放"
这一整排看起来没有垂直居中对齐。两者其实在完全独立的两个 Column 里（侧栏 vs 内容区），`LibraryBottomBar` 内部
的 `Row` 本来就有 `verticalAlignment = CenterVertically`，只对齐了它自己内部的环境 Chip 和"开始播放"按钮，
跟侧栏那个"其它"框完全不知道对方的存在。修复思路跟之前"SpacePlayer 标题对齐"那次一样——两边分别包一个**同样
高度**（新增 `FOOTER_HEIGHT = 56.dp`，跟已有的 `HEADER_ROW_HEIGHT` 是同一个套路）的容器再居中，而不是指望
"挪一下 padding 就能对齐"：
- 侧栏"其它"框：外层加 `.height(FOOTER_HEIGHT)`，`Box` 自身用 `contentAlignment = Alignment.CenterStart`
  把内部图标+文字整体在这个固定高度里垂直居中；顺手把它自己的 `padding(bottom = 20.dp)` 改成 `16.dp`，
  跟内容区 Column 自带的 `padding(16.dp)` 底部间距对齐（原来两边到窗口底边的距离差 4dp，也是造成"看起来没对齐"
  的一部分原因）。
- 内容区：`MainLibraryScreen.kt` 里包住 `LibraryBottomBar` 的外层 `Box` 加 `.height(FOOTER_HEIGHT)`；
  `LibraryBottomBar.kt` 内部的 `Row` 改成 `Modifier.fillMaxSize()`（原来是 `fillMaxWidth().padding(16.dp)`）
  ——`verticalAlignment` 要在 `Row` 自己不是"包多高就是多高"、而是被外部撑到固定高度时才有实际居中效果，
  光设 `verticalAlignment` 参数不会凭空生效。`FOOTER_HEIGHT` 这个常量只定义在 `MainLibraryScreen.kt` 一处，
  没有在 `LibraryBottomBar.kt` 里重复定义同一个数字——那个文件的 `Row` 只管 `fillMaxSize()` 去适配调用方
  给多高，具体高度由调用方（`MainLibraryScreen.kt`）统一决定。

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
- **真机（PICO B3110 实测）内置屏幕带 `FLAG_SECURE`，`adb shell screencap`/`pico-cli capture screenshot` 在真机
  上直接报错"Capturing failed"拿不到任何截图**（模拟器的虚拟屏幕没有这个限制，截图一直正常）——这不是权限或
  唤醒状态问题，`mWakefulness=Awake` 时一样失败。真机上做视觉验证只能靠 `adb shell uiautomator dump`（文字层级
  树，不受 `FLAG_SECURE` 限制）+ `adb logcat` + 用户目视确认三者组合，不要花时间反复重试截图命令。
- **真机屏幕靠佩戴传感器复用 Android 的"翻盖开关"（`lid_switch`）机制自动休眠/唤醒**——没人戴的时候
  `adb shell input keyevent KEYCODE_WAKEUP` 能瞬间唤醒但立刻又被压回休眠（`dumpsys power` 里
  `mLastSleepReason=lid_switch`），`adb` 无法长期保持屏幕唤醒；这不是可以靠改系统设置绕过的地方（改佩戴检测
  阈值属于修改系统设置，不在允许范围内），需要用户实际戴上设备触发传感器才能继续截图/点击验证。
- **本机 adb daemon 在这次会话里反复自发断线**（`* daemon still not running / cannot connect to daemon at
  tcp:5037`），怀疑是 Android Studio 自带的 adb 客户端和命令行 `adb`/`pico-cli` 内部调用的 adb 抢 5037 端口——
  `adb kill-server && adb start-server` 或者干脆重跑一遍失败的命令通常就能自愈，不需要更激烈的手段（比如重启
  模拟器/设备）。
- **`Modifier.spatialHoverEffect` 在这个项目实际锁定的 SDK 版本（0.13.3）上只有一个重载**：
  `spatialHoverEffect(block: SpatialHoverEffectRootScope.(SpatialHoverEffectContext) -> Unit)`——没有更简单的
  `enabled: Boolean` 参数版本（那是更新版本 SDK 才有的 API 面，`spatial-ui-design-style` 技能的示例代码是按新版本
  写的，跟这个项目实际编译进来的 `design-0.13.3-sources.jar`/`foundation-0.13.3-sources.jar` 对不上）。给自定义
  组件接 hover 前，先解压对应版本的 sources jar（`~/.gradle/caches/modules-2/files-2.1/com.pico.spatial.ui/
  foundation/<version>/*/foundation-<version>-sources.jar`）确认真实签名，不要直接照抄技能文档里的调用方式。

**播放控制器 HUD 加入进度条 + 播放/暂停/环境切换/返回全部换成内置组件（2026-08-06）**：用户先在
`mcp__visualize` 画板上看了一版重设计（圆形强调色播放/暂停按钮 + 环境选择药丸 + 弱化的返回链接），确认后又追加
"还有进度条控制"，最后确认"背景可以使用透明玻璃材质"（即维持已有的 `backgroundMaterial(true, Material.Regular)`，
不需要换风格）。落地到真实代码：

- `PlaybackViewModel.kt` 新增 `currentPositionMs`（`mutableStateOf`，每帧由 `refreshPlaybackProgress()` 从
  `manager.player.getCurrentPosition()` 刷新，和已有的 `refreshSubtitleText()` 同一个节奏）、`durationMs`
  （直接读 `manager.duration`）、`seekTo(ms)`（转发 `manager.seekTo(ms)` 并立即自赋值 `currentPositionMs`，避免
  拖动松手后有一帧的回弹）。`ImmersiveScene.kt` 的 `update` 块里紧挨着 `refreshSubtitleText()` 加一行
  `refreshPlaybackProgress()` 调用。
- `PlaybackHud.kt` 整个重写：进度条用 SDK 内置 `com.pico.spatial.ui.design.Slider`（**不是**手写
  `pointerInput`/`detectDragGestures`——`spatial-ui-design-style` 的 R2 built-in-first 规则加上反编译
  `design-0.13.3-sources.jar` 里的 `Slider.kt` 确认这个组件本来就是给"音量/进度"这类连续值场景设计的，内置了
  drag/tap 手势、`controllerHapticFeedback`、`OpDragBegin/OpDragEnd/OpClick` 音效，全部不用自己接）；
  `sliderSpec = SliderDefaults.Small`（8dp 细轨道，比默认 `Regular` 的 24dp 更接近效果图的纤细进度条），
  `colors = SliderDefaults.sliderColors(progressColor/progressHighColor/thumbColor/thumbHighColor =
  SpacePlayerAccent)`，轨道色留空吃主题默认（自动适配玻璃背景，不需要硬编码）。拖动预览用一个
  `dragPreviewMs: Long?` 本地状态：拖动中 `onValueChange` 只更新这个预览值（同时驱动时间文字和滑块位置），真正
  `onSeek()` 只在 `onValueChangeFinished`（松手/点击结束）时提交一次——完全对应 `Slider` 文档里
  `onValueChangeFinished` "不要用来更新值，只用来知道用户选完了"这句话的字面意思，而不是每次拖动 delta 都真的
  seek 一次播放器。播放/暂停从纯文字 `Button` 换成 `IconButton`（`IconButtonDefaults.Small` + 强调色
  containerColor + `SpacePlayerOnAccent` contentColor），新增 `ic_pause_bars.xml`（仿照已有的
  `ic_play_triangle.xml` 风格，两条竖条，`#FF000000` 占位色，实际渲染色由 `IconButton` 的 `contentColor` 通过
  `LocalContentColor` 接管）。环境选择器从纯文字 `Button` 换成和 `LibraryBottomBar.kt` 同款的 `ToggleableChip`
  （复用 `env.dotColor()` 圆点、`ChipsDefaults.toggleableChipColors(activeContentColor/activeBackgroundColor =
  SpacePlayerOnAccent/SpacePlayerAccent)`，`contentColor`/`backgroundColor` 留空吃主题默认——这里**没有**照搬
  `LibraryBottomBar.kt` 里给 `SpacePlayerTextPrimary`/`SpacePlayerSurface` 显式赋值的做法，因为那两个是给
  *不透明暖白背景*的主界面调的固定色，HUD 是悬浮在深色玻璃材质上的面板，应该让 `PicoTheme.colorScheme` 的
  自适应角色自己去算对比色，而不是复用另一个背景语境下调好的固定值）。"返回主窗口"从 `Button` 降级成 `Link`
  （默认 `contentColor = PicoTheme.colorScheme.interaction`，天然比强调色更弱化，不需要额外传色）。
- **一个关键 API 事实是靠反编译 SDK 源码而不是 MCP 查到的**：这次会话里
  `pico-spatial-knowledge` 的 `query_graph`/`get_node` 两次调用都直接超时/连接断开（1800s 无响应），MCP 整体
  不可用，如实按 fallback 顺序走：先查项目已有代码里的 `PicoTheme.colorScheme.*`/`backgroundMaterial` 用法当
  词表，再解压
  `~/.gradle/caches/modules-2/files-2.1/com.pico.spatial.ui/design/0.13.3/*/design-0.13.3-sources.jar`
  （用项目实际锁定的 0.13.3，不是最新版本）逐个读 `Slider.kt`/`IconButton.kt`/`Link.kt`/`Chips.kt` 的真实签名
  ——`Slider`/`SymbolSlider`/`SegmentSlider`/`IconButton`/`Link`/`ToggleableChip` 的参数名、默认值、颜色角色
  都是从这份源码原样抄出来的，不是猜的。下次如果 MCP 还是连不上，同样用这条路径，不要跳过验证直接编代码。
- **调试"点击触发某个流程"时，`adb shell input tap` 对这套 spatial WindowContainer 的可靠性比之前 Stage 2/3
  记录的更细致**：这次专门做了对照实验——`ToggleableChip`/`VideoGridCard` 这类自定义 `Modifier.clickable`
  组件，用 `uiautomator dump` 拿到的 bounds 中心点去 `input tap`，**完全可靠、不需要任何坐标换算**（截图像素
  和 `uiautomator`/`input tap` 坐标系不是同一回事，但两者的对应关系不需要关心——只要坐标来自 `uiautomator dump` 就行）；
  但 `LibraryBottomBar.kt` 的"开始播放" `Button` 和顶部的格式筛选 `SegmentControl` style 那一排（`全部`/`平面`/
  `180°`/`360°`）在 `uiautomator dump` 里全部精确报告 `bounds="[0,0][0,0]"`，无论怎么用截图三角测量法反推真实
  U 坐标去 `input tap`（试过局部仿射插值、全局仿射插值，两者都因为面板本身有透视畸变而外推失败，算出来的坐标经常
  超出 `2160` 的面板宽度）都点不中——**这不是坐标算错了，是这几个元素本身在这次会话的模拟器状态下对 `input tap`
  没有反应**（可能和它们都是 SDK 内置 `Button`/segment 组件而不是自定义 `Box+clickable` 有关，没有深挖根因）。
  另外发现一个容易误诊的坑：**验证"点击后有没有生效"至少要等 3-4 秒再截图**，这次两次把
  "点了但没反应"误判为"坐标错了"，其实是截图截早了（1-2 秒），换环境选择器色点验证时，同一个坐标点两次，第一次
  1 秒后截图看着没反应，第二次 3 秒后截图确认真的生效了。真正卡住的元素（"开始播放"）无论等多久截图都没用。遇到
  "改了新代码但点不中入口验证"时，别在坐标上反复重试，直接用已有的临时
  `LaunchedEffect(selectedItem) { /* 和 onClick 一样的代码 */ }` 自动触发方案（AGENTS.md 前面 Stage 2/3 就记过
  这条），验证完立刻删掉，不要偷懒留着。

**修复"连续播放下一个视频时没有清理上一个播放器"（2026-08-06）**：用户反馈退出沉浸模式后再播放下一个视频，上一个
`CypressMediaPlayer` 没有被清理。反编译 `core-0.13.3-sources.jar` 里的 `CypressMediaPlayer.kt` 确认：
`setDataSource()`/`prepareAsync()` 之前没有先调用 `player.reset()` 的话，旧数据源的解码器/资源不会被替换——
`reset()` 的 KDoc 原文把"Switching between different video sources"列为典型用例，"Do NOT call this method within
any CypressMediaPlayerCallback methods"是唯一的调用限制。原来的 `PlaybackManager.setup(uri)` 每次都直接
`setDataSource()`，从没调用过这个 `reset()`（和 `PlaybackManager.reset()` 是两个不同的东西——后者额外调
`player.close()` 把整个播放器实例销毁掉，只在 `onCleared()` 整个会话结束时调一次，不能每个视频都调）。修复：在
`setup()` 开头加 `if (state != PlaybackState.INIT) player.reset()`，只在"不是这个播放器第一次使用"时重置，避免
对一个从没配置过数据源的全新播放器调 `reset()`。

验证这条修复时踩了一个和这次会话前面几条一致的坑，值得单独记一下：**同一份代码（带 `reset()`）连续测了三次，
结果是"失败(全黑屏且 uiautomator dump 完全空)/成功/成功但只有视频没有 HUD"**——一度怀疑是 `reset()` 引入了
新 bug，回退掉 `reset()` 重新测又成功了一次，正准备下结论"这个修复是错的"时，想起前面已经记过的教训
（"连续快速 am force-stop + am start 重装/重启循环会把模拟器画面合成器拖死"）——这次验证过程本身就是短时间内
连续 5-6 次 `install -r` + `force-stop` + `am start`，符合那条教训描述的触发条件。用 `pico-cli emulator stop`
+ `pico-cli emulator start` 完整重启模拟器后，同样带 `reset()` 的代码干净地测试成功（视频 A 自动播完→选视频
B→HUD 正确显示 B 的进度条和 `0:06` 时长，logcat 无异常）。**结论：不要用"改了代码后连续测了几次，结果时好时坏"
本身作为"这个改动有问题"的证据——先看是不是踩了已知的模拟器重装/重启节流限制，重启一次模拟器拿到干净状态再复测，
而不是急着回退一个有官方文档背书的正确修复。**

**修复"进度条没有跟着走"——`SpatialView` 的 `update` 参数根本不是每帧循环（2026-08-06）**：这是这次会话里
最重要的一条架构性发现，直接推翻了 Stage 1/3 沿用至今的一个错误假设。用户反馈进度条不走，先按
`systematic-debugging` 流程走：

1. 加日志排查时先撞见一个 ANR（"TossAR没有响应"）——根因是 `PlaybackHud` 的进度条给 `PlaybackViewModel` 加了
   独立的 `refreshPlaybackProgress()`，和原有的 `refreshSubtitleText()` 一样都调
   `manager.player.getCurrentPosition()`，等于每帧多了一次 `CypressMediaPlayer` 方法调用。反编译
   `core-0.13.3-sources.jar` 的 `ThreadPool.kt` 确认 `runOnScheduleThread` 在非 Schedule 线程调用时是
   `runBlocking(Schedule) { block() }`——真同步阻塞调用线程等 Schedule 线程处理完。把两个方法合并成一个
   `refreshPlaybackFrame()`（只读一次 `getCurrentPosition()`，同时喂给字幕查找和进度条）解决了 ANR，
   但进度条本身仍然没有动——说明加日志时先入为主的"两次阻塞调用撞了 ANR watchdog"只是压榨掉了一部分症状，
   不是真正根因（系统性调试流程里"fix 不work 就退回 Phase 1"这条在这里救了一次）。
2. 认真验证"进度条不动"这个症状本身时，用 `ffmpeg -f lavfi testsrc2=...:duration=60`（baseline profile + AAC +
   faststart，同一套 Stage 1 就验证过能播的编码参数）造了一条 60 秒测试视频推到 `/sdcard/Movies/` 再
   `MEDIA_SCANNER_SCAN_FILE` 广播一下让 MediaStore 收录——比反复用现成的 6 秒素材抢时间窗口靠谱得多。用视频
   画面本身自带的时间戳叠加层（`testsrc2` 内置）和 HUD 上显示的时间对比，实锤：视频叠加层显示已经播到
   `52.48s`，HUD 进度条的当前时间文字还停在 `0:00`——`currentPositionMs` 真的完全没刷新过，不是刷新慢。
3. 加一个每次调用自增计数器 + 时间戳的日志（`Log.e`，最高优先级，排除日志级别过滤的可能）直接证实：
   `refreshPlaybackFrame()`（连同它所在的整个 `update = { ... }` 代码块）**在一次完整的 60 秒播放过程里只被
   调用了一次**，就是刚进入 PLAYING 状态那一刻，之后再也没被调用过。
4. 反编译 `foundation-0.13.3-sources.jar` 里 `SpatialView.kt` 的真实实现（不是猜的）找到根因——它的 `update`
   参数的 KDoc 原文："Every time the compose state which is read in this function changes, the update will be
   invoked by the compose runtime. **Will be invoked once after initial is called.**" 这就是标准 Compose
   `AndroidView(update = {...})` 的语义：`update` 只在初始化后调一次，之后只有当**这个 lambda 自己读取过的某个
   Compose 状态**发生变化时才会再触发一次重组——**完全不是每帧渲染循环**，尽管它的参数名和用法（读 ECS
   entity、传 `attachments`）看起来非常像一个 game-loop 回调。旧代码里 `update` 块读了
   `viewModel.showLoadingOverlay`（依赖 `manager.state`/`hasFirstFrameRendered`），这两个值从 PREPARING 变到
   PLAYING 时触发了唯一那一次调用——这也解释了为什么"loading 遮罩正确隐藏"和"HUD 正确显示"这两个纯粹靠状态翻转
   一次就能做对的效果，从 Stage 1 到现在看起来一直"正常工作"：它们本来就只需要触发一次，从未依赖过真正的连续
   循环，直到这次给进度条接了一个需要连续刷新才有意义的值，才把这个从 Stage 1 就存在的错误假设暴露出来。
   **同样受影响、值得警惕的是字幕功能**——`refreshSubtitleText()`/`applySubtitleFollow()` 之前也挂在这个
   `update` 块上，理论上同样只会执行一次，字幕的显示/隐藏时机和跟随位置在播放过程中大概率也没有真正持续刷新过，
   只是 Stage 3 的验证没有测到"播过一个字幕时间窗口之后再继续检查"这种场景，没暴露出来（这条没有单独复测字幕，
   但架构修复本身已经把它们一起迁到了真循环里）。
5. 修复：`ImmersiveScene.kt` 新增一个独立的 `LaunchedEffect(Unit) { while (isActive) { withFrameNanos { ... } } }`
   循环，把原来挂在 `update` 块上的连续性工作（`refreshPlaybackFrame()`、字幕跟随的 `applySubtitleFollow()`、
   字幕面板可见性）整个搬进去——`withFrameNanos` 是 Compose 运行时自己的帧时钟（`Recomposer` 的
   `AndroidUiFrameClock`，底层挂在 `Choreographer.postFrameCallback` 上），是 Compose 里做连续逐帧驱动
   （动画、物理式插值）的标准写法，真正跟随显示器实际刷新率。`SpatialView` 的 `update` 块保留下来，但只留
   `showLoadingOverlay` 驱动的 loading/HUD 可见性切换——这部分本来就是"状态变化触发一次"的正确用法，不需要连续
   循环。`SpatialViewAttachments`（`update`/`initial` 参数类型）在 `initial` 里通过
   `remember { mutableStateOf<SpatialViewAttachments?>(null) }` 存一份引用供 `LaunchedEffect` 循环读取——
   反编译源码确认它是 `SpatialView` 内部 `remember { SpatialViewAttachmentsImpl() }` 出来的同一个长生命周期对象，
   不是只在某次 `update`/`initial` 调用里才有效的临时值，可以安全跨越到独立的协程里持有。
6. 验证：用上面那条 60 秒 `long_test.mp4`，选中播放后间隔几秒截两次图对比进度条——从 `0:01` 到 `0:51`，
   随时间正确前进，时间文字和滑块位置同步更新。单测 48/48，`verify-design-style.sh` 0 错误 0 警告。
   **另外发现一个和这条 bug 无关、但同一次测试里顺带撞见的现象**：视频画面自带时间戳显示的播放速度比真实挂钟
   时间快很多（60 秒素材大约 9-10 秒挂钟时间就播完/自动返回主窗口）——没有深挖根因（可能是解码器不受
   vsync/帧率节流、也可能是别的因素），如实记录为一个独立的、还没验证过的疑似问题，不要和这次修复的"进度条不
   刷新"混为一谈。

**HUD 和画板对比色差太大——`PicoTheme.colorScheme` 的自适应角色在这个模拟器上没有读出预期的深色玻璃底
（2026-08-06）**：进度条修好之后用户直接拿真机截图和 `mcp__visualize` 画板对比，反馈色差太大——画板设计的是
深色（接近黑色）半透明玻璃底，配浅色高对比度文字/色块；模拟器上 `backgroundMaterial(true, Material.Regular)`
实际渲染出来是纯色中灰底，不是深色玻璃。这台面板上原来给时间文字、环境 Chip 未选中态、进度条未填充轨道都用的
是 `PicoTheme.colorScheme.labelSecondary`/`ChipsDefaults`/`SliderDefaults` 的自适应默认值——这套自适应色系统
假设背景是深色，算出来的浅灰配色在真正的中灰底上对比度不够，"星空"/"海景" Chip 几乎融进背景里看不清。没有去
深究"这个材质在这台模拟器上为什么没渲染出深色玻璃"（留了个开放问题，真机上是否会不一样目前不确定），而是直接
在 `PlaybackHud.kt` 里给这几处加了固定色（`HudTrackColor`/`HudTimeTextColor`/`HudChipBackground`/
`HudChipContent`/`HudLinkContent`，全部 `// design-style: fixed-figma-color` 标注，数值抄自已经批准的画板
HTML 里的 rgba 值），不再依赖自适应系统去猜背景深浅——截图验证时间文字、Chip 背景、轨道线条都变得清晰可辨。
背景玻璃材质本身没有动（用户之前明确说了"背景可以使用透明玻璃材质"，维持 `Material.Regular` 不变）。

**HUD 控制行宽度没对齐 + 缺分组分隔线（2026-08-06）**：用户直接甩回两张最早批准的 `mcp__visualize` 画板截图，
指出"播放器宽度都没有对齐"，要求重新分析画板里每个元素的功能与位置关系。逐一对比后确认两个真问题：

1. **宽度没对齐**：进度条那一行（时间文字+`Slider`+时间文字）天然由 `Slider` 的固定宽度撑开（约
   312-336dp，见前面"进度条控制"那条记录），但按钮行的 `Row` 没加 `Modifier.fillMaxWidth()`——`Spacer(
   Modifier.weight(1f))` 在一个没有宽度约束、纯靠内容撑开（wrap-content）的 `Row` 里没有可分配的剩余空间可用，
   等于失效，"返回主窗口"就紧跟在环境 Chip 后面，而不是像画板那样贴在面板最右边。给按钮行的 `Row` 加
   `Modifier.fillMaxWidth()` 后，`Column` 会先按最宽的子项（进度条行）确定自己的宽度，按钮行的
   `fillMaxWidth()` 再撑到同一个宽度，`weight(1f)` 才有真正的剩余空间可分配——这是"非 fillMaxWidth 的 Column
   包一个 fillMaxWidth 的 Row"这种常见 Compose 组合模式，不需要给两行都加 `fillMaxWidth()`。
2. **画板明确画了分组分隔线**：`[播放/暂停]` | `[环境选择器 或 全景文案]` | `[返回]` 三组之间画板用了细竖线
   分隔，原来的实现完全没有。补上 `VerticalDivider`（`com.pico.spatial.ui.design.Divider.kt`，内置组件）时
   发现一个没深挖根因的怪现象：同一个 `Row` 里，两处用了完全相同的 `VerticalDivider(modifier =
   Modifier.height(24.dp), color = ...)` 调用，播放按钮和 Chip 之间那处死活不渲染，环境组和返回按钮之间那处
   却正常显示——反编译源码确认 `VerticalDivider` 内部实现就是 `Box(modifier.fillMaxHeight().width(thickness)
   ).background(color)`，没有任何 hover/点击/触觉反馈之类值得保留的行为，索性直接用等价的
   `Box().width(1.dp).height(24.dp).background(HudDividerColor)` 手写替代，两处都稳定显示——不是"重新发明"
   内置组件，只是绕开了一个位置相关但没查出根因的渲染不一致。
3. 顺带把画板里"返回"文字后面跟着的回退箭头图标也补上了（新建 `ic_return.xml`，同样是最简单的纯色矢量路径，
   和 `ic_play_triangle.xml`/`ic_pause_bars.xml`风格一致）——`Link` 组件的真实签名只有 `trailingIcon`
   没有 `leadingIcon`（反编译 `Link.kt` 确认），图标只能挂在"返回主窗口"文字后面，和画板"图标在文字前面"的
   顺序不完全一致，但保留内置 `Link` 组件比手写一个匹配图标顺序的自定义按钮更值得，这个顺序差异属于可接受的
   妥协。播放/暂停的 `IconButton` 尺寸也从 `.Small` 换成 `.Regular`，比例上更接近画板里明显更大的红色圆形按钮。
   截图验证：两条分隔线清晰可见，"返回主窗口"贴到了和进度条行同宽的右边缘。

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
- `ui/library/MainLibraryScreen.kt` — 权限门 + `SideNavigation`（四分类，2026-08-06 视觉重做后是三个可选分类
  + 侧栏底部单独的"其它"虚线框）+ `LazyVerticalGrid`（真实网格列表，2026-08-06 起替换原来的 `LazyColumn`）+
  `LibraryBottomBar` + `FormatCorrectionPopup` + SAF"其它"，替换掉 Stage 1 的 `PlaceholderMainScreen.kt`。
- `ui/library/VideoGridCard.kt`（2026-08-06 视觉重做前叫 `VideoListCard.kt`，`ListItem` 单列行）— 网格卡片：
  大缩略图 + 居中播放图标浮层 + `contentResolver.loadThumbnail()` 懒加载缩略图 + 左上角按 `projection` 着色的
  格式 `Badge` + 右下角时长 `Badge` + 选中态强调色描边。
- `ui/library/SpacePlayerPalette.kt`（2026-08-06 新增）— 设计稿要求的固定品牌色（强调色橙、按 projection/
  environment 区分的徽标色/圆点色），全部用 `Color(0x...).withVibrant(Vibrant.None)` 钉死，不走
  `PicoTheme.colorScheme` 自适应层级（因为语义色表里没有"视频格式"/"环境"这种角色），每处都有
  `// design-style: fixed-figma-color` 行内注释满足 `spatial-ui-design-style` 校验脚本。
- `ui/library/FormatCorrectionPopup.kt` — `SpatialPopup` + 两组 `SegmentControl`（投影/立体格式）+ Stage 3 加的
  字幕状态行/选择按钮。
- `ui/library/LibraryBottomBar.kt` — 环境选择器（复用 `Environment` 枚举，2026-08-06 起用 `ToggleableChip` +
  彩色圆点图标代替纯文字按钮）+ "开始播放"（强调色填充按钮）。

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

### 中英文国际化新增/改动文件（2026-08-06）

- `res/values/strings.xml` — 英文默认（fallback）字符串资源。
- `res/values-zh/strings.xml` — 中文覆盖，键名跟默认文件一一对应，`app_name` 除外（专有名词不重复定义）。
- `ui/Labels.kt`（新增）— `Projection`/`StereoMode`/`FormatSource`/`Environment`/`LibraryCategory` 的
  共享 `@Composable` label 扩展函数，取代原来分散在三个文件里的重复实现。
- `playback/Environment.kt`（改）— 去掉 `label: String` 构造参数，只剩 `assetPath`。
- `ui/library/LibraryCategory.kt`（改）— 去掉 `label: String` 构造参数，变成纯值枚举。
- `ui/library/MainLibraryScreen.kt`/`VideoGridCard.kt`/`LibraryBottomBar.kt`/`FormatCorrectionPopup.kt`/
  `ui/PlaybackHud.kt`/`ui/LoadingErrorAttachment.kt`（改）— 硬编码中文字符串全部换成 `stringResource(R.string.xxx)`
  或 `ui/Labels.kt` 里的共享 label 函数。

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

2026-08-06 这次会话额外做了"返回主窗口"功能（真机验证前先在模拟器 logcat 完整链路确认）和视频资源库的视觉重做
（网格卡片/彩色徽标/图标侧栏/格式筛选/深色主题，模拟器截图确认渲染正确）。会话中途拿到了一台真机 **PICO
B3110**（`PB311XKGL5070015G`），但发现这台设备的内置屏幕带 `FLAG_SECURE`，`adb shell screencap`/`pico-cli
capture screenshot` 在真机上直接报错拿不到截图（模拟器没有这个限制）——真机验证要么靠 `adb logcat`/
`uiautomator dump`（文字，不受 `FLAG_SECURE` 限制）配合用户目视确认，要么换用户手柄真实交互而不是 adb 模拟点击；
另外这台设备靠佩戴传感器（`lid_switch` 复用）控制屏幕唤醒/休眠，没人戴的时候会立刻休眠且 `adb`
唤醒不了——这几点已经在下面"本机环境注意事项"里记录。

如果要继续往前推进，比较合理的方向：
1. **真机验证清单（用户已经把 B3110 戴上过，但这次会话转去做视觉重做了，还没跑完）**：
   - 字幕面板的位置延迟跟随 + 朝向跟随是否符合预期（模拟器 `HMDTrackingProvider.start()` 直接失败，真机上
     `register remote topic success: T:XR_HMD_DATA_TOPIC` 已经在 logcat 里见过一次，但没有专门转头验证跟随
     手感）。
   - 真机上把"SAF 选择器里点选一个 `.srt` 文件"这一步跑通——真机没有模拟器那种虚拟屏幕路由问题，理论上直接用
     手柄交互就行，但这次会话没有实际走一遍。
   - 真实 SAF 导入一个视频，确认字幕自动发现在这种场景下确实返回空、手动指定仍然可用（目前只有代码审查）。
   - 这次视觉重做也应该在真机上（不只是模拟器）用手柄实际点一遍格式筛选/网格卡片/侧栏/环境圆点选择器，确认
     手柄交互（不是 adb 模拟点击）下点击区域、hover 反馈都符合预期。
2. ~~设计对齐时发现但这次没做的功能性缺口：沉浸 HUD 完全没有进度条和音量控制~~ —— 已在 2026-08-07 补齐（进度条、
   静音按钮），见下方记录。
3. 如果之后有新的功能性需求（比如非目标里提到的电影院高保真美术、字幕样式），从设计稿的"非目标"章节移出、单独
   立项写新的 spec/plan，而不是默认继续加到 Stage 3 里。

## 2026-08-07 HUD 面板宽度、进度条填充、返回文案、音量按钮

用户反馈四点，逐一分析后一起修：面板宽度过长、进度条应该填满剩余横向空间、"返回主窗口"改为"返回"+图标、在
靠近返回控件的分割线右侧补一个音量按钮。

### 根因：`AttachmentPanel` 默认 `WRAP_CONTENT`，`fillMaxWidth()` 会撑到 2048dp 上限

反编译 `core-0.13.3-sources.jar` 的 `AttachmentPanelComponent.kt` 确认：`AttachmentPanel`（`ImmersiveScene.kt`
里 `AttachmentPanel(id = HUD_ATTACHMENT_ID) { ... }` 不传 `size` 参数）默认 `width = WRAP_CONTENT`，而
`WRAP_CONTENT` 时原生层用 `MAX_PANEL_SIZE_DP = 2048` 作为 AT_MOST 上限（`resolveSizePx()` 源码里硬编码的常量）。
上一轮（`4fb2d8b`）给按钮行加 `Modifier.fillMaxWidth()` 是为了让 `Spacer(weight(1f))` 有空间可分配，但这个
`fillMaxWidth()` 是相对这个 2048dp 上限展开的，不是相对"内容自然宽度"，于是整个面板被撑到接近全屏——这才是
"面板宽度过长"的根本原因，不是任何一次新增内容造成的。

修法：给外层 `Box` 加显式 `Modifier.width(HudPanelWidth)`（当前 640dp，凭经验选的、已通过模拟器截图验证观感
合理，不是从任何公式推导的精确值），把面板从"不受约束的 WRAP_CONTENT"变回"有界容器"，两行 `Row` 各自的
`fillMaxWidth()`/`weight(1f)` 才会相对这个有意义的宽度展开，而不是相对 2048dp。

### 进度条改用 `Modifier.weight(1f)` 真正填满剩余空间——推翻了上一轮"trailing `.size()` 总是赢"的结论

反编译 `design-0.13.3-sources.jar` 的 `Slider.kt`，`SliderImpl` 内部链式是：

```kotlin
Modifier.sizeIn(minHeight = sliderSpec.sliderMinHeight())
    .then(modifier)                                    // 调用方传入的 modifier
    .size(width = sliderSpec.defaultComponentWidth(), height = ...)
```

上一轮（本文件更早的记录）断言"trailing `.size(width=...)` 总是赢，调用方传的宽度 modifier 不起作用"。这次照 Compose
`Constraints.constrain()` 的真实语义重新推了一遍：`.size(width, height)` 内部用
`incomingConstraints.constrain(Constraints.fixed(width, height))`，而 `constrain()` 是"receiver 夹住 other"——
如果 `modifier` 传入的是一个**tight（min==max）**约束（`fillMaxWidth()`、`weight(1f)` 在 `Row(fill=true)` 下都是
tight），夹的结果就是 tight 值本身，跟 `.size()` 里写的宽度无关。也就是说结论并不是"箱子的宽度 modifier 完全
无效"，而是"只有 tight 约束才能覆盖，普通的 `Modifier.width(x)`（未必总是 tight，视上下文）不一定够"。这次直接
给 `Slider` 传 `Modifier.weight(1f)`（在一个 `fillMaxWidth()` 的 `Row` 里，`weight` 默认 `fill=true`，产生 tight
约束），模拟器截图确认进度条条真的撑满了剩余空间——推翻了上一轮的结论，以这次反编译推导+实测为准。

### 分割线从 1dp 加宽到 3dp——这也解释了上一轮"一条分割线显示、一条不显示"的谜团

新面板截图里两条 `HudDivider()` 完全不可见。用 Python 量了面板在最终合成截图里的实际像素宽度：640dp 的面板测出
来只有 ~532px，即 **~0.83 px/dp**——因为 `AttachmentPanel` 是先被合成成一张纹理贴到 3D 场景里的面片上，再整体
渲染进最终 2D 截图，这个"纹理→3D 贴图→2D 截图"的额外缩放跟 Compose 用来做 dp 布局的密度是两回事，实测比值远
低于任何正常设备屏幕的 px/dp。1dp 的分割线在这个有效缩放下不到 1 物理像素，被抗锯齿糊掉了。这也补上了上一轮
（`4fb2d8b`）里"两处结构完全相同的 `VerticalDivider` 一个显示一个不显示"这个当时没查出根因的谜团的最合理解释：
不是 SDK 行为不一致，是亚像素舍入噪声（谁凑巧四舍五入到那零点几个像素，谁就多多少少显示出来）。把 `HudDivider`
宽度从 1dp 提到 3dp 后，模拟器截图确认两条分割线稳定可见。

### "返回主窗口" → "返回"

`playback_return_to_main_window` 字符串值改成"返回"（英文 "Return"），保留原来的 key 名（这个 key 描述的是
"点击后的动作"，动作没变，只是显示文案缩短了，改名字反而增加不必要的 diff）。

### 新增音量静音/取消静音按钮

`PlaybackManager` 把原来的 `private const val INIT_VOLUME` 改名导出成 `const val DEFAULT_VOLUME`（同一个值，
0.8f），复用给取消静音时恢复音量用，避免魔法数字重复。`PlaybackViewModel` 新增 `isMuted`（`mutableStateOf`）+
`toggleMute()`（静音时 `setVolume(0f)`，取消静音时 `setVolume(DEFAULT_VOLUME)`）。新增两个矢量图标
`ic_volume_up.xml`/`ic_volume_off.xml`（跟现有 `ic_pause_bars.xml` 等一样，纯色 path，靠 `Icon()` 自己的
`tint` 上色，`fillColor` 具体值不重要）。按钮放在第二条分割线和"返回"链接之间（`IconButtonDefaults.Small`+
`HudChipBackground`/`HudChipContent`，跟 chip 视觉语言保持一致，不用主色，避免跟播放按钮抢视觉重量）。

### 验证方法上的一个新发现：沉浸 Stage 里的 `AttachmentPanel` 内容，`uiautomator dump` 看不到

在主窗口（`MainLibraryScreen`）阶段 `uiautomator dump` 能正常拿到真实 bounds（用来点选视频卡片这步仍然可靠）。
但进入沉浸 `Stage` 之后再 dump，返回的是 `com.picoxr.launcher` 包的一个近乎空的层级（`bounds="[0,0][2160,1440]"`
几层空 `FrameLayout`），完全看不到我们自己 `AttachmentPanel` 里的任何 Compose 节点。这意味着 HUD 上所有控件在
沉浸模式下**没有任何可靠的 bounds 来源**，只能凭截图像素肉眼估坐标去 `adb tap`——而这次实测这种估法对播放/暂停
这种大按钮基本能中，但对彼此靠得很近的小控件（音量按钮和"返回"链接只隔了 8dp）就不可靠了：反复在目测坐标上
tap 音量按钮，图标始终没有切换成静音状态（同时也没有误触发旁边的"返回"，此前一次误以为点到了"返回"导致退出
沉浸模式，事后核实其实是同一时间视频播放到头触发的自动返回，纯属巧合，跟这次 tap 无关）。最终改用最小侵入的
临时手段验证：把 `PlaybackViewModel.isMuted` 的初始值临时改成 `true`、重新编译安装、截图确认静音图标正确渲染，
再改回 `false`——比继续猜测坐标或者搭一次性点击脚手架更直接，且改动范围小、容易在验证完之后原样撤销确认干净。

### 已知但本轮没有处理的问题（同类，不在本次范围内）

- 之前记录过的"视频播放速度比真实时间快很多"的疑点这次测试中又见到了一次（`long_test.mp4` 60秒素材，实际测试
  中不到一分钟就已经播完触发了自动返回主窗口）——跟这次 HUD 改动无关，维持"已知未解决"状态。
- 沉浸 Stage 关闭后主窗口重新打开这一步，这次有一次截图显示既没有回到 `MainLibraryScreen` 也没有停留在 HUD，
  而是直接看到了 PICO 系统主页（passthrough 卧室场景），等了几秒后仍是这个状态，直到手动 `am force-stop` +
  重新 `launch` 才恢复正常。没有进一步深挖是重开时机问题还是窗口重新打开的位置问题（比如相对头部朝向的锚点在
  多次沉浸开关后逐渐偏移），记在这里供下次遇到类似情况时参考，不建议现在就去追。

## 2026-08-07（续）HUD 主题色从"西瓜红"换成磨砂黑玻璃

用户反馈沉浸播放器的主题色太突出（"西瓜"，即 `SpacePlayerAccent = Color(0xFFE63946)` 那个饱和红），要求换成
"玻璃材质的磨砂黑"。

**范围判断**：`SpacePlayerAccent`/`SpacePlayerOnAccent` 定义在 `ui/library/SpacePlayerPalette.kt`，是资源库
页面（网格卡片选中态、底部操作栏、格式徽标等）跟沉浸 HUD 共用的同一个 token。用户明确说的是"沉浸模式下播放器"，
没有提资源库页面，所以没有改共享的 `SpacePlayerAccent` 本身（改了会连带影响资源库的选中态/按钮），而是在
`PlaybackHud.kt` 本地新增了 `HudAccentContainer`/`HudAccentContent` 两个固定色，只替换 HUD 内部三处用到
`SpacePlayerAccent`/`SpacePlayerOnAccent` 的地方（播放/暂停按钮、选中环境 chip、进度条 progress/thumb），资源
库页面的红色强调色不受影响。

**"玻璃材质"没有对小控件二次调用 `backgroundMaterial`**：反编译 `design-0.13.3-sources.jar` 确认
`IconButton`/`ToggleableChip`/`Slider` 内部都是"调用方传入的 `modifier` → 自己的 `graphicsLayer{clip+shape}` →
自己的 `.background(containerColor)`"这个顺序（`Button.kt` 的 `BasicButton` 可以直接看到）——如果在调用方
`modifier` 里追加 `backgroundMaterial`，它离该组件自己的 `clip`/`shape` 有一层不确定的先后关系，容易出现虚化
没有正确裁到圆形/胶囊形状的问题，且每个组件都要单独验证。权衡下来选择了更稳的做法：一个足够不透明的纯色深黑
`Color(0xE6151515)`，本身叠在整个面板已有的 `Material.Regular` 磨砂玻璃背景之上，视觉上就是"磨砂玻璃面板上
一块更深的黑色"，不需要再对小控件二次实时虚化。这跟本文件其它地方"用固定色而不是硬啃 Material 在各设备上的
渲染差异"的既有原则一致。

修改文件：`PlaybackHud.kt`（新增 `HudAccentContainer`/`HudAccentContent`，去掉 `SpacePlayerAccent`/
`SpacePlayerOnAccent` 的 import 和三处引用）。模拟器截图确认：播放/暂停按钮、选中的"电影院" chip、进度条已
播放部分和拖动手柄都变成了深黑色，跟未选中 chip 的浅灰、轨道的浅灰线对比清晰，环境圆点（`dotColor()`，红/蓝/
绿）不受影响仍然保留各自颜色。48/48 单测，`verify-design-style.sh` 0 错误 0 警告。

## 2026-08-07（再续）播放/静音按钮改透明 + 进度条改用默认配色

用户看了黑色玻璃方案后仍然觉得不够合适，先用一个 Workflow（4 个并行方向 + 独立评委打分）在 mcp__visualize 画板
上渲染了"海玻璃/冰川蓝/烟熏石墨"三个候选配色供选择，但用户没有选其中任何一个，而是给了一个更简单直接的方向：
把播放/静音按钮的容器颜色设为 `Color.Transparent`，交给 SDK 自带的 spatial hover 效果处理交互反馈；进度条不
再自定义颜色，直接用 `SliderDefaults.sliderColors()` 默认配置。

改动：
- 播放/暂停 `IconButton`、静音 `IconButton`：`containerColor = Color.Transparent`（图标颜色保持不变，播放
  用不透明白 `HudPrimaryIconContent`，静音沿用原来的 `HudChipContent`）。反编译确认过 `spatialHoverEffect`
  本来就是 `BasicButton` 内部固定挂载的一部分（跟 `colors` 参数无关），所以去掉 `containerColor` 不会丢失
  hover 反馈，只是去掉了静止态的填充色。
- 进度条 `Slider`：整个 `colors = SliderDefaults.sliderColors(...)` 参数直接删掉，退回组件自己的默认值
  （`PicoTheme.colorScheme` 的 `fillTertiary`/`fillSecondary`/`labelPrimaryLight` 等自适应角色）。

**范围说明（这次没动的地方）**：用户原话只提到"那几个按钮"和"进度条"，没有提到环境选择器 chip 的选中态——
`ToggleableChip` 概念上不是"按钮"，而且它的"选中态"依赖填充色做持续可见的分组选中提示（不像按钮只需要点击时
的 hover 反馈），如果也套用 `Color.Transparent` 会导致选中态的透明度（alpha=0）比未选中态（`0x1FFFFFFF`，
约 12% 不透明度）还要低，视觉上"选中的反而比没选中的更不显眼"，是一个真实的功能性倒退，不是单纯的美观问题。
所以这次保留了 chip 选中态原来的深黑色（`HudAccentContainer`/`HudAccentContent`，这两个色值现在只被 chip
引用，注释已同步更新说明用途收窄），把这个问题在回复里明确标出来，等用户下一步指示，没有自己猜。

模拟器截图确认：播放/暂停/静音按钮静止态只剩图标本身，没有任何填充圆形背景；进度条呈现为浅灰轨道+深一点的灰色
已播放段+白色拖动手柄，能看出播放进度但对比度比较柔和（PicoTheme 默认配色本身如此，不是本次改动引入的新问题）。
48/48 单测，`verify-design-style.sh` 0 错误 0 警告。

## 2026-08-07（再续2）分割线挪位置 + 环境 chip 也改透明+玻璃选中色

用户直接回答了上一条记录里留下的"chip 要不要也透明"的问题，同时带了两张参考图：一张是当前 HUD 效果图，
一张是系统自带 spatial hover 效果的实际渲染截图（一个圆形按钮呈现出淡紫蓝色的磨砂玻璃质感）。改了两点：

1. **分割线位置**：从"chips 与静音按钮之间"挪到"静音按钮与返回链接之间"——`Row` 里的顺序从
   `Spacer(weight) → HudDivider → Spacer → 静音IconButton → Spacer → Link` 改成
   `Spacer(weight) → 静音IconButton → Spacer → HudDivider → Spacer → Link`。
2. **环境选择 chip**：未选中态的 `backgroundColor` 从 `HudChipBackground`（12% 白）改成 `Color.Transparent`
   （回答了上一条记录的开放问题：是的，chip 也要透明）；但选中态没有跟着变透明，而是换成一个新的玻璃色
   `HudAccentContainer = Color(0xCC97A8D8)`——柔和的雪青/薰衣草蓝，视觉上比对用户给的第二张参考图（系统
   spatial hover 效果截图）目测取色，不是从某个 API 常量算出来的精确值（反编译确认 `SpatialHoverEffect`
   的实际着色是原生渲染层做的，Compose 侧拿不到这个颜色值，只能凭肉眼比对截图估一个足够接近的固定色）。
   `HudChipBackground` 常量因此不再被引用，已删除。

模拟器截图确认：未选中的"星空"/"海景" chip 现在只剩点+文字，没有背景色块；选中的"电影院" chip 呈现淡雪青色
胶囊背景，视觉上与参考截图的玻璃质感比较接近；分割线确认已经在静音图标和"返回"文字之间，而不是在 chips 和
静音图标之间。48/48 单测，`verify-design-style.sh` 0 错误 0 警告。

## 2026-08-07（再续3）首次用真实立体素材验证 SBS/TB 播放路径

用户问"现在有没有做立体视频的播放处理"。代码审查确认识别→`StereoMode.toVideoDimensionMode()`→
`VideoDimensionMode`→`PlaybackEntityAssembler` 里 `VideoMaterial` 这条链路在 Stage 1/2 就已经接好了（对平面/
360°/180° 三种投影都会传），但截至这次会话之前，**从来没有拿真正的左右/上下格式素材实际播放验证过**——所有
测试素材都是纯平面的 ffmpeg 彩条，AGENTS.md 里也一直如实记着这条空白。这次没有去网上下载视频（避免来源不明的
文件+免不了的版权问题），改用本机 ffmpeg 生成了两条合成立体测试素材，直接推到 `/sdcard/Movies/`：

- `stereo_sbs_test.mp4`（左右格式）：左半 640x720 是 `testsrc2`（跟项目原有测试素材同一个滚动彩条+对角线
  时间戳图案），右半 640x720 是 `smptebars`（经典电视条纹图案，色调风格上跟 testsrc2 差异很大，一眼能分清）。
- `stereo_tb_test.mp4`（上下格式）：上半 640x360 `testsrc2`，下半 640x360 `smptebars`。
- 两者都是 20 秒、H.264 baseline + yuv420p + AAC + faststart，命名分别带 `_sbs`/`_tb` 关键词，交给
  `FilenameFormatDetector` 自动识别，不需要手动走格式修正弹层。

验证结果：
1. 库页面网格卡片正确显示"平面 · SBS"/"平面 · TB"徽标，来源是"文件名识别"而不是"默认兜底"——识别这一步
   没问题（这一步之前也测过，这次只是用真实场景名再确认一次）。
2. 实际播放后，模拟器截图里的 2D 伴随预览画面**只显示 `testsrc2` 的完整图案，完全看不到任何 `smptebars`
   的痕迹**——两个文件（SBS 和 TB）表现一致。如果 `dimensionMode` 没有真正生效（比如整帧原样贴图没做任何
   UV 裁剪），应该会看到 `testsrc2` 和 `smptebars` 被压缩挤在同一屏里；实际看到的是完整、没有变形拉伸的单一
   图案，说明播放器确实只采样了源画面的一半（左眼那一半），而不是把整帧塞给每只眼睛。

**没有验证、也没法靠这套工具验证的部分**：2D 伴随预览目前的理解是复用了跟真实立体渲染同一条着色器路径镜像
出来的单眼画面（不是一条独立于立体渲染之外的调试直通），所以上面这个结果具有一定说服力，但这只是推断，不是
直接证据——`adb screencap` 拿到的终究只是一张 2D 图，没有办法从这一张图上同时看到左右两只眼睛分别渲染的是
什么，所以严格来说"右眼/下半是不是真的显示了 smptebars（而不是也显示 testsrc2 或者黑屏）"以及"真机上双眼
合成后的立体视差观感是否正常"这两点仍然没有被证实，需要真人戴上真机头显实际看一遍才能彻底验证。测试文件已经
留在模拟器的 `/sdcard/Movies/` 下（`stereo_sbs_test.mp4`/`stereo_tb_test.mp4`），下次可以直接复用，或者
`adb push` 到真机 B3110 上用同样文件走一遍头显实测。

## 2026-08-10 排查中：沉浸模式返回主窗口偶发不弹出（未解决，先记录进展）

真机上用户反馈：视频**自动播放完毕**触发返回主窗口时，主窗口没有弹出来（怀疑崩溃）。抓真机 logcat 排查：

- **不是崩溃**：进程全程同一个 PID，logcat 里没有 `FATAL EXCEPTION`。
- **系统层证据**：Stage 销毁的那一刻，系统自己的 WindowManagerService（不是我们的代码）打出
  `Failed looking up window session=Session{...}` / `setOnBackInvokedCallback(): No window state for
  package:tech.illusion.spaceplayer` / `SpatialAudioHelper: can not get attached window`——系统那一刻
  确认已经找不到这个 app 的任何窗口了。
- **规律**：那次事故是连续开关了 3 轮沉浸模式都正常（每轮日志里 Stage 销毁后紧接着都能看到对称的
  "SpacePlayerMainWindow ... life=create"），第 4 轮才卡住，之后等了近 20 秒也没有任何后续。
- **反编译 SDK 源码发现的真实线程行为**：`CypressMediaPlayer.registerCypressMediaPlayerCallback()` 里
  `runOnScheduleThread` 包的是**注册**这个动作本身，`onCompleted()`/`onPrepared()` 等回调方法被原生层调用时
  完全没有线程切换包装——直接在原生解码线程上调用。
- **加诊断日志（写文件而不是 `Log`，见下面"本机环境注意事项"）验证结果**：`onCompleted()` 原生回调确实在
  非主线程（`Thread-23`）触发，`returnToMainWindowRequested = true` 也是在这个线程写的；但 Compose 在 18ms
  内正确把状态传播到了主线程的 `LaunchedEffect`，后续 `exitImmersive()`→`closeStage()`→`openWindowContainer()`
  全部在主线程正常执行完，**且这一次主窗口确实正常弹出来了**。
- **当前结论（未最终定性）**：单独测一次"自动播放完成→返回"不会必现，说明这不是简单的"后台线程写状态"就能
  解释的确定性 bug；更像是短时间内连续切换沉浸模式/主窗口若干轮之后，系统底层（PICO SDK 原生层或 Android
  WindowManagerService）的某个状态才会出问题，是间歇性的时序竞态，且失败点在我们自己的 Kotlin 代码调用
  `openWindowContainer()` **成功返回之后**的更底层——不是能靠改我们代码逻辑直接修的问题。
- **下一步（已经请用户帮忙复现，还没有结果）**：连续播放多个视频、每个都自动播完触发返回，重复 4-5 轮，
  一旦复现就立刻抓完整诊断轨迹对比每轮之间的差异（比如原生回调线程是不是每次都新建、有没有累积不释放的
  东西）。

**本机环境注意事项（新增一条）**：这次排查里，`PlaybackManager`/`PlaybackViewModel`/`ImmersiveScene` 里加的
`android.util.Log` 调用又一次没有在真机 logcat 里出现过（同一个"Log 调用有时候就是不出现在 logcat 里"的
诡异现象这次会话至少第三次遇到，仍然没有查清根因）。这次改用直接写文件（`context.getExternalFilesDir(null)`
下的 `thread_debug.txt`，配合 `adb pull /sdcard/Android/data/tech.illusion.spaceplayer/files/thread_debug.txt`）
绕开这个问题，完全不依赖 logcat，验证下来是可靠的——以后需要在真机上做类似的"这段代码到底有没有执行/在哪个
线程执行"这种确定性诊断时，优先用写文件这个办法，不要指望 `Log.e`/`Log.i` 一定能在 logcat 里看到。

**这轮排查在代码里留下的临时诊断代码**（`PlaybackManager.kt`/`PlaybackViewModel.kt`/`ImmersiveScene.kt`
里的 `thread_debug.txt` 写入调用）**已于 2026-08-14 随根因修复一并清理**——根因见下面"2026-08-14 终于定位
根因"那一节，问题已由用户实机复测确认解决，这些埋点完成使命。它们采集到的时间戳恰恰是定位根因的决定性证据
（`onDispose` 比 `closeStage` 晚 524ms 那组数据），需要重新排查同类问题时可以从 git 历史里取回这套写文件
埋点的写法（不要指望 `Log.e` 一定出现在真机 logcat 里，见上面那条）。

## 2026-08-10 资源库交互调整：格式修正挪到底部栏，去掉卡片上的播放视觉暗示

用户反馈两点：
1. "格式修正"入口应该放到主窗口底部（"开始播放"旁边）。
2. 用户测试时发现："选中片源中心可直接播放，跳过下面的场景选择、修正步骤"——不应该允许点击片源直接播放。

审查 `MainLibraryScreen.kt`/`VideoGridCard.kt` 源码：卡片的整体点击事件 (`onClick = { selectItem(item)
}`) 从来就只是"选中"，没有代码路径会直接调用 `startPlayback()`——`selectItem()` 本身也没有任何副作用。但
卡片缩略图中间确实有一个纯装饰性的圆形播放三角形图标（没有绑定任何点击事件），视觉上很容易让人以为"点这里
就能直接播放"。没能在真机上复现"点击直接播放"这个具体路径（问题反馈时手边的真机连接已断开，后续也没能重新
接上验证），所以这次改动是照用户描述的**意图**实现（点击卡片永远只应该是选中，不应该有任何暗示"点击=播放"
的视觉/交互），而不是确认修复了一个已定位根因的具体 bug——如果后续用户在新版本上仍然遇到"点击直接播放"，
需要重新用真机 logcat/日志排查，不能假设这次改动已经解决。

改动：
- `VideoGridCard.kt`：删除缩略图中间的装饰性播放三角形图标；删除卡片内联的"修正格式"文字入口和
  `onRequestFormatCorrection` 参数（格式判断为 `FormatSource.DEFAULT` 时才出现这条，逻辑原样保留，只是
  搬到了新位置）。
- `LibraryBottomBar.kt`：新增 `onRequestFormatCorrection` 回调参数，在"开始播放"按钮左侧、环境选择器右侧
  加一条同款式的"修正格式"文字链接，仅当 `selectedItem?.formatSource == FormatSource.DEFAULT` 时显示。
- `MainLibraryScreen.kt`：`itemPendingCorrection` 状态和 `FormatCorrectionPopup` 弹层本身不变，只是触发源
  从 `VideoGridCard` 的回调改成 `LibraryBottomBar` 的回调。

模拟器截图确认：网格卡片缩略图不再显示播放三角形图标；选中一个"默认兜底"格式的视频后，底部栏"开始播放"按钮
左侧正确出现红色"修正格式"文字；点击卡片本身只改变选中状态（红色描边），不会跳转到沉浸播放。48/48 单测，
`verify-design-style.sh` 0 错误 0 警告。

## 2026-08-10 续：底部栏"修正格式"从单文字链接改成内联 SegmentControl，中途踩了两个坑

上一节做完之后用户又提了两轮要求：

1. "直接将修正格式的选项放到底部栏处，改为使用菜单选项的方式进行选择"——第一次理解成了字面意思，用
   `Menu`/`MenuItem`（反编译 `design-0.13.3-sources.jar` 确认了这两个组件的真实签名）做了一个"点击'修正
   格式'按钮 → 弹出下拉菜单选具体类型"的实现，在模拟器上构建+截图验证过确实能用。
2. 但用户看到后明确否定了这个方向："修正格式不要只列一个按钮，要把当前的可选类型摆在那一行内"——也就是说
   "菜单选项"指的不是 SDK 里那个字面意义上的 `Menu` 下拉组件，而是"所有可选项直接摆出来，跟环境选择器那几个
   chip 一样，不要收在一次点击背后"。于是把 `Menu`/`MenuItem`/`menuExpanded` 状态整段删掉，换成两个内联的
   `SegmentControl`（投影 Projection + 立体格式 StereoMode），跟已删除的 `FormatCorrectionPopup.kt` 用的是
   同一个组件，只是直接摆在底部栏这一行里，不再收在弹层/菜单背后。

**踩坑 1——`SegmentControl` 会偷偷吃掉整行宽度**：把两个 `SegmentControl` 和字幕状态文字都塞进同一个
`Row` 后，模拟器截图显示第一个 `SegmentControl`（投影）的灰色背景胶囊铺满了几乎整行，第二个
`SegmentControl`（立体格式）和字幕文字完全看不到——一度以为是空间不够要做取舍。反编译
`SegmentControls.kt` 源码确认了根因：`SegmentItem` 内部用的是 `Modifier.weight(1f)`（在 `SegmentControl`
自己包的那层 `Row` 里），而 Compose 的 `Row` 只要有一个子项用了 `weight()`，这个 `Row` 自己就会占满收到的
全部可用宽度（不管它自己的 modifier 有没有写 `fillMaxWidth()`）——所以只要 `SegmentControl` 不显式限制自己
的宽度，摆在别的 `Row` 里当非 weighted 的普通子项时，它会把同一行里排在它后面的所有兄弟节点全部挤出可视
区域。**修复**：给每个 `SegmentControl` 加 `Modifier.width(IntrinsicSize.Min)`，强制它按自己内容的最小
真实宽度测量，而不是撑满可用空间。加完之后两个 `SegmentControl` 才第一次同时正确显示。

**踩坑 2——就算宽度修好了，投影(3项)+立体格式(4项)+字幕文字+环境chip+开始播放按钮全部塞一行仍然会超出
窗口右边缘**：宽度修复后再截图，虽然两个 `SegmentControl` 各自不再互相挤占，但连同环境 chip
（影院/星空/海滩）和"开始播放"按钮一起放进同一行时，整行内容总宽度还是超过了窗口本身的宽度——"开始播放"
按钮被直接挤到窗口右边缘之外，完全看不到（这是真实的可用性问题，不是截图取景问题：按钮消失意味着用户在这
种情况下点不到"开始播放"）。**修复**：把 `LibraryBottomBar` 从单个 `Row` 改成 `Column`，拆成两行——
上面一行只在 `formatSource == FormatSource.DEFAULT` 时出现，放两个 `SegmentControl` + 字幕状态文字；
下面一行始终显示（选中了任意视频就会显示），放环境 chip / "全景·自动沉浸播放"提示文字 + "开始播放"按钮，
高度固定为 `PRIMARY_ROW_HEIGHT = 56.dp`（跟侧边栏"其它"按钮对齐的老逻辑不变）。`MainLibraryScreen.kt` 里
包裹底部栏的 `Box` 相应从固定 `.height(FOOTER_HEIGHT)` 改成 `.defaultMinSize(minHeight = FOOTER_HEIGHT)`——
不需要修正格式时保持原来的单行高度（不影响网格区域可用空间），需要两行时能自动长高，不会裁切内容。

验证：`./gradlew assembleDebug` / `testDebugUnitTest` 均 BUILD SUCCESSFUL；`verify-design-style.sh` 0 错误
0 警告；模拟器上选中一个 `formatSource=DEFAULT` 的平面视频后截图确认——上面一行的"平铺/180°/360°"投影
SegmentControl、"单眼/侧双3D/上下3D/MV-HEVC"立体格式 SegmentControl、"字幕未设置"文字全部完整可见，互不
挤占；下面一行"影院/星空/海滩"环境 chip 和"开始播放"按钮也完整可见，按钮不再被挤出窗口。

**这次连带发现的环境教训（补充到"本机环境注意事项"）**：这轮验证过程中模拟器多次出现"窗口离摄像机的远近/
取景在两次截图之间自己变了"的情况（同一个 app 窗口，字体/整体大小在截图里忽大忽小，两侧内容被屏幕物理边缘
裁掉而不是窗口自己的边缘裁掉）——这跟"内容溢出窗口内部"是两个完全不同的现象，肉眼分辨的关键是看窗口自己的
圆角边框是否完整出现在截图里：如果圆角边框整个都在，说明是内部布局溢出（真 bug）；如果圆角边框本身都被切
没了，说明是取景/相机距离问题（环境噪声，不是代码问题）。另外这次会话还发现这个模拟器上残留了好几个跟
SpacePlayer 无关的历史测试 App（`com.illusion.tossar`/`com.illusion.portalcantoss`/
`tech.illusion.boxdepthpoc`/`com.pxr.scenarioprovider` 等），它们的悬浮面板会跟 SpacePlayer 的窗口抢占
同一块可视空间、抢 accessibility 焦点（导致 `uiautomator dump` 抓到风马牛不相及的另一个 App 的界面树），
排查这类"看到的画面不对"的问题时应该先用 `pico-cli emulator stop` + `pico-cli emulator start` 重开一个
干净的模拟器实例，而不是在一堆残留 App 里反复 force-stop 排查。

## 2026-08-10 续二：改回单行布局，`IntrinsicSize.Min` 用错了应该用 `Max`

上一节拆成两行的方案用户看完截图后否定了："绿色箭头所标记的这一行应该放到底部与场景选择、开始播放在同一行
内，并且要求不要压缩文案形成两行"——箭头指的是修正格式那两个 `SegmentControl`，用户截图里能看到
"东右3D"/"上下3D"/"MV-HEVC" 这几个立体格式选项的文字被压成了两行（"东右"换行"3D"）。这两点要求：
（1）合并回一行，不要单独占一整行；（2）文字不能因为被压窄而换行。

**发现上一节的 `IntrinsicSize.Min` 用错了**：以为 `Modifier.width(IntrinsicSize.Min)` 是"按内容需要的最小
宽度测量、不多占空间"的意思，实际上 Compose 对可换行 `Text` 的定义是——**min intrinsic width = 最长的那个
不可再拆分的词/字符片段的宽度**（因为文字允许换行，理论上最小宽度只需要放下最长的单个词），不是整行不换行
文案的宽度。"左右 3D" 这种带空格、可以在空格处断行的文案，min intrinsic width 只等于"左右"或"3D"两者中较
宽的一个——所以 `SegmentControl` 被这个偏小的宽度值锁死后，实际渲染时文字确实会在这个空格处换行，这正是
用户截图里看到的"压缩成两行"现象。**正确的选择是 `IntrinsicSize.Max`**——它测量的是"给无限宽度时这段内容
会占多宽"，也就是文案完全不换行时的真实宽度，既不会像不加约束那样撑满整行（因为 `SegmentControl` 内部
`SegmentItem` 的 `weight(1f)` 只在"有确定宽度好分配"时才会生效），又不会小到逼着文字换行。

**同时把立体格式的文案换成短版**：`StereoMode.fullLabel()`（"左右 3D"/"上下 3D" 这种带空格的描述性短语）
整个删掉，改成 `StereoMode.shortLabel()`，复用跟网格卡片徽标同一套缩写字符串资源（`stereo_sbs_badge`=
"SBS"、`stereo_tb_badge`="TB"，`stereo_mono`/`stereo_mvhevc` 保留原样），彻底避免"多词文案"这个换行诱因，
顺便也让单行总宽度更容易放下所有元素。`stereo_sbs_full`/`stereo_tb_full` 这两个不再被引用的字符串资源
（中英文两份 `strings.xml`）一并删除。

**改动**：
- `Labels.kt`：`StereoMode.fullLabel()` → `StereoMode.shortLabel()`（同上）。
- `LibraryBottomBar.kt`：撤销上一节的 `Column` 两行拆分，改回单个 `Row`（跟"格式修正挪到底部栏"那次提交
  之前的结构一致）；两个 `SegmentControl` 的宽度修饰符从 `IntrinsicSize.Min` 改成 `IntrinsicSize.Max`；
  立体格式 `SegmentItem` 的文案从 `candidate.fullLabel()` 换成 `candidate.shortLabel()`。
- `MainLibraryScreen.kt`：底部栏外层 `Box` 的高度撤回成固定 `.height(FOOTER_HEIGHT)`（不再需要
  `defaultMinSize` 那种可变高度，因为不会再有两行的情况）。

验证：`assembleDebug`/`testDebugUnitTest` 均 BUILD SUCCESSFUL，`verify-design-style.sh` 0 错误 0 警告。
模拟器截图确认单行内环境 chip（电影院/星空/海景）+ 投影 SegmentControl（平面/180°/360°）+ 立体格式
SegmentControl（单目/SBS/TB/MV-HEVC）+ 字幕状态文字 + "开始播放"按钮全部在同一行、单行不换行、
"开始播放"按钮完整可见不被挤出窗口；点击"180°"选项确认能正确切换卡片的投影徽标和 `formatSource`
（变成"手动指定"），交互功能正常。

**验证过程中顺带发现的一个环境陷阱（记录以防下次误判为代码 bug）**：这次验证时新推的测试文件
`quick_test.mp4` 一开始被识别成"手动指定 · 180°"而不是期望的"默认兜底 · 平面"——起初怀疑是检测逻辑出了
新 bug，但对照 `FormatDetector.kt`/`FilenameFormatDetector.kt` 源码确认文件名根本不含任何关键词、理应走
默认兜底路径。原因是这个模拟器实例上 `/sdcard/Movies/` 路径 + MediaStore 这套组合，在这次会话里被反复
push 同名/不同名文件很多轮，`quick_test.mp4` 恰好复用了 MediaStore 分配给某个更早测试文件的行 ID，而
`VideoPreferencesStore` 里当时对那个旧 ID 存过一条手动指定 180° 的偏好（`SharedPreferences` 不会因为
`adb install -r` 或 `pico-cli emulator stop/start` 被清空）——纯粹是这个高频复用测试文件名的模拟器实例
自己的历史包袱，不是这次改动引入的问题。换一个从没用过的新文件名（`verifyrow_0810.mp4`）重新验证后就
正确显示"默认兜底 · 平面"了。以后在同一个模拟器上反复测格式检测逻辑时，如果结果跟预期不符，先检查是不是
文件名撞上了旧的 `VideoPreferencesStore` 记录，而不是急着怀疑检测代码本身。

## 2026-08-10 续三：沉浸 HUD 里也能修正格式（投影/立体格式热切换，不中断播放）

用户要求"播放 HUD 中应同样支持格式修正"，并明确两点：修正栏要和场景选择器**放在同一行**（不要单独占一行）；
播放中改投影要**热切换、保留进度**。

### 先反编译确认可行性，再动手

- `VideoMaterial.setDimensionMode(VideoDimensionMode)` 是**公开稳定 API**（`core-0.13.3-sources.jar`，无
  `@ExperimentalSpatialApi`，内部自己 `runOnScheduleThread`，线程安全）——立体格式可以直接热改，不用重建实体。
- `VideoPlayerComponent` 有 `setMesh()`/`setMaterial()`，但**只有 `getMesh()` 没有 `getMaterial()`**——所以
  `VideoMaterial` 的引用必须由 app 侧自己留着，这就是 `PlaybackEntityAssembler` 两个 assemble 函数改成返回
  `VideoMaterial` 的原因。
- 投影切换没走 `setMesh()`，而是沿用项目原有结构：三个视频实体（银幕/半球/球体）本来就共用同一个
  `manager.player`，切换只改 `enabled`（首次用到时才 assemble）。

**这条推翻了设计稿第 1 节的一条约束**："`ScreenEntity`/`SphereEntity` 二选一 enabled，由视频的 `projection`
决定，播放期间不切换"。那条约束当时是为简化架构自己加的，不是 SDK 限制。设计稿那句应该改写成"投影可在播放中
热切换，播放器不重建"，否则下次读 spec 的人会按旧约束否掉这个功能。

### 改动

- **新增 `ui/FormatMenuButton.kt`**：把原来私有在 `LibraryBottomBar.kt` 里的 pill+`Menu` 组件抽出来共用，
  加 `FormatMenuButtonColors`/`FormatMenuButtonDefaults.libraryColors()`——资源库用不透明卡片色（默认值），
  HUD 传玻璃色（`HudPillContainer`/`HudPillBorder`）。同一处修正逻辑、两处入口，形态一致。
- **`PlaybackViewModel`**：`startPlayback` 里那段三分支 `when(projection)` 抽成 `applyProjection(projection,
  dimensionMode)`，新增 `correctFormat(projection, stereoMode)` 复用它；`isFlatProjection` 这个跟
  `currentProjection` 重复的状态删掉，改成 `currentProjection`/`currentStereoMode` 两个 Compose 状态（HUD 的
  两个下拉直接显示它们，`== FLAT` 同时用来决定要不要显示环境选择器）；`screenAssembled`/`sphereAssembled`/
  `hemisphereAssembled` 三个 flag 换成 `screenMaterial`/`sphereMaterial`/`hemisphereMaterial` 是否为 null
  （引用本来就要留，不需要再多三个布尔量）。
- **顺手修掉一个既有 bug**：原来 `dimensionMode` 只在实体第一次 assemble 时传进 `VideoMaterial`，而实体
  assemble 一次就长期存活、跨多次播放复用——所以**第二个视频如果立体格式不同，会继续用第一个视频的
  dimensionMode**。现在 `applyProjection` 每次都对目标实体的 material 重新 `setDimensionMode()`。
- **`PlaybackHud`**：参数 `isFlatProjection: Boolean` 换成 `currentProjection`/`currentStereoMode` +
  `onCorrectFormat`，控制行在环境 chip 之后加一条 `HudDivider()` 再放两个 `FormatMenuButton`——分组语义是
  "环境 + 格式都是'这个视频怎么呈现'，两端的播放/静音/返回才是操作播放本身"。
- **`MainLibraryScreen`**：新增一个 `LaunchedEffect(playbackViewModel.isImmersive.value)`，退出沉浸时
  重新 `refreshLibrary()`/`refreshDownloads()` 并按 uri 重选当前项——HUD 里改的格式虽然已经写进
  `VideoPreferencesStore`，但网格渲染的是上次刷新时的 `VideoItem` 快照，不刷新徽标不会变。

### 验证（模拟器 emulator-5554，API 36）

`./gradlew assembleDebug testDebugUnitTest` 均 BUILD SUCCESSFUL，48/48 单测（0 failure 0 error），
`verify-design-style.sh app/src/main` 0 error 0 warning。设备证据：

1. **单行布局不溢出**（这是这次唯一真正的风险点，原先估算 640dp 面板只剩不到 30dp 余量）：平面态截图
   `./artifacts/hudfmt-24-flat-row.png` 里 `⏸ | ●电影院 ●星空 ●海景 | 平面▾ 单目▾ … 🔊 | 返回←` 全部在一行内
   完整可见，"返回"没有被挤出面板、文案没有换行；全景态（`hudfmt-21`）是 `⏸ | 全景视频·自动沉浸 | 180▾ SBS▾ …`。
   **没有加宽面板**，`HudPanelWidth` 仍是 640dp。
2. **热切换保留进度**（HUD 控件在沉浸 Stage 里没法用 adb 可靠点击，按本文件既有办法临时加 `LaunchedEffect`
   定时调 `correctFormat`，并把每次切换前后的 `positionMs`/`state` 写进 `fmt_verify.txt`，验证完已删除）：
   - 180°/SBS → 平面/单目：positionMs 9330 → 14340，state 全程 `PLAYING`
   - 平面/单目 → 360°：9962 → 12044，`PLAYING`
   - 360° → 180°/SBS：20044 → 22103，`PLAYING`
   三次切换都没有回到 0、没有 `PREPARING`，`adb logcat -b crash` 全程空——**同一个 `CypressMediaPlayer` 同时被
   多个 `VideoPlayerComponent` 持有这件事，实测是可行的**（这是改之前唯一没底的地方，现在有实测结论了）。
3. **持久化 + 回到资源库同步**：设备上 `shared_prefs/video_preferences.xml` 里
   `content://media/external/video/media/121` = `projection=HEMISPHERE_180;stereo=SIDE_BY_SIDE`（HUD 改的）；
   干净重装后启动，资源库卡片显示"180° · SBS"+"手动指定"（`hudfmt-30-library-after.png` 对应的 uiautomator dump）。
4. **没有验证到的部分**：① 用手柄/射线**真的点** HUD 上这两个 pill 并在弹出的 `Menu` 里选一项——沉浸 Stage 里的
   `AttachmentPanel` 内容没有 uiautomator bounds，adb 点不到，所以"点击能否命中、`Menu` 会不会被面板边界裁掉"
   只能真机手柄实测；上面的功能链路是绕过 UI 直接调 `correctFormat` 验的。② 360° 那一态没拿到一张能看清
   pill 文字是"360°"的截图（只有 `fmt_verify.txt` 的状态记录），180°/平面两态有。

### 这轮踩到的环境坑（都不是代码问题，下次别再花时间排查）

- **模拟器里残留的 `com.illusion.portalcantoss` 会自己起一个前台 Stage，把 SpacePlayer 的 Stage 挡死**：症状极具
  误导性——`navigator.openStage()` **正常返回、不报错**，但 `ImmersiveScene` 的 composable 根本不组合（临时诊断
  文件里连第一行 `immersive-composed` 都写不出来），画面上是一个紫色线框盒子（那是 PortalCanToss 的 Stage
  边界，不是我们的）。判定方法：`adb logcat | grep -oE "Info\(Id=[0-9]+,pkg=[a-z.]+,visb=[a-z]+,focus=[a-z]+,type=[a-z_]+"`
  ——如果看到别的包 `type=stage` 且 `visb=true`，我们的 Stage 就上不来（一个 space 里 Stage 是互斥的）。
  处理：`am force-stop` + `pm disable-user --user 0 com.illusion.portalcantoss`（光 force-stop 会被系统拉回来）。
  同一台机器上 `com.illusion.tossar`/`tech.illusion.boxdepthpoc`/`tech.illusion.stockquote` 一并 disable 了。
- **`adb push` 到 `/sdcard/Movies/` 可能留下 `is_pending=1` 的 MediaStore 记录，对其它 App 完全不可见**：
  本轮 push 时正好赶上设备离线，记录停在 `is_pending=1, duration=NULL, _size=NULL`，`content query` 看得到，
  但 App 的 `MediaStore.Video` 查询查不到（表现为"文件明明在，资源库里就是没有这一项"）。修：
  `adb shell content call --uri content://media --method scan_file --arg /storage/emulated/0/Movies/<name>.mp4`
  （路径要用 `/storage/emulated/0/...`，用 `/sdcard/...` 返回 `STREAM=null` 不生效）。**另外警告**：
  `content delete --uri content://media/external/video/media/<id>` 会**把真实文件一起删掉**，不是只删索引。
- **底部栏里有些 SpatialUI 节点在 uiautomator dump 里 bounds 是 `[0,0][0,0]`**（这次是"开始播放"按钮、字幕状态
  文字、立体格式 pill），不代表它们跑出屏幕——同一张截图里面板圆角完整、按钮清晰可见。要判断"是不是真的溢出"，
  看面板自己的圆角边框是否完整（本文件前面记过这条），不要靠 bounds 是否为 0。
- **`pico-cli emulator stop` 没有 `-y` 选项**（`start` 才有），要停指定实例用 `--adb-device emulator-5554`；
  误用 `-y` 会让 stop 失败但 start 照样起一个新实例，结果同时出现 `emulator-5554`/`emulator-5556` 两台。

### 对本文件既有记录的一处更正

前面"视频资源库视觉重做"那节写着"这个项目锁定的 SDK 版本（0.13.3）里 `spatialHoverEffect` 只有底层
`SpatialHoverEffectRootScope` block 版本，没有简单的 `enabled` 参数版本"——**这条是错的**。0.13.3 里
`com/pico/spatial/ui/foundation/hover/SpatialHoverEffectStyle.kt` 就有
`fun Modifier.spatialHoverEffect(style: SpatialHoverStyle = SpatialHoverStyle.Default, enabled: Boolean = true)`，
block 版本在另一个文件（`SpatialHoverEffect.kt`）里，是两个重载。新增的 `FormatMenuButton` 已经按
design-style 硬规则挂上了 `Modifier.spatialHoverEffect()`。**`VideoGridCard` 那个"唯一没做的 hover"因此是可以
补上的**（一行 modifier），本轮范围内没动，留作下一步。

### 续三的收尾：补齐 hover + 同步设计稿

- **`VideoGridCard` / "其它·选择文件" 两个自定义可点击元素补上 `Modifier.spatialHoverEffect()`**（放在 `clip`/
  `dashedBorder` 之后、`clickable` 之前，按 design-style 的 modifier 顺序规则，hover 才会跟着圆角形状）。
  这就是上面那条更正的直接后果——`VideoGridCard` 从视觉重做那次开始一直被记为"唯一没做的 hover"，实际上
  0.13.3 有简易重载，一行就能补。运行时证据：logcat 里出现
  `func_name":"SpatialHoverEffect"` 的 SDK 埋点 + `BaseEffect$SpatialHoverStyle` 的原生字段链接
  （说明 modifier 真的挂上生效了，不只是编译通过）；hover 的**视觉**是原生层跨进程渲染的，adb 造不出
  "指针悬停"这个状态，所以外观仍需真机手柄确认。资源库整体渲染无回归（`./artifacts/hudfmt-31-hover-regression.png`）。
- **设计稿 `docs/superpowers/specs/2026-08-05-spaceplayer-design.md` 同步改了三处**（都标了"2026-08-10 修订"）：
  第 1 节"播放期间不切换投影"改写成"投影可在播放期间热切换、播放器不重建"并附实测结论；第 2 节手动覆盖入口
  从"列表项弹层 + 仅 DEFAULT 时提示"改成"底部操作栏 + 沉浸 HUD 两处、始终显示"，并说明为什么不能按
  `formatSource == DEFAULT` 隐藏（选完一次控件自己消失）；第 4 节 HUD 控件清单加上"格式修正"一项。

## 2026-08-13：宽高比启发式格式检测（第三层识别信号）

`FormatDetector` 原来只有两层识别（容器探测多视图 + 文件名关键词），完全没看视频本身的几何信息，
不按命名规范来的素材只能落到默认兜底。新增第三层：从视频轨道宽高推测投影/立体格式，作为文件名检测
之后的补缺信号。设计稿见 `docs/superpowers/specs/2026-08-13-aspect-ratio-format-detection-design.md`，
实施计划见 `docs/superpowers/plans/2026-08-13-aspect-ratio-format-detection.md`。

- **优先级规则**：文件名在投影/立体格式两个字段上都独立优先于宽高比——宽高比只填文件名没提到的那个
  字段，从不覆盖文件名已经命中的字段。为此把 `FilenameFormatDetector.detect()` 的返回值从"一个全填好
  默认值的 `DetectedFormat`（或 null）"改成两个独立可空字段的 `FilenameHint(projection, stereoMode)`，
  否则 `FormatDetector` 分不清"文件名真的没提到"和"提到了但结果恰好是默认值"。
- **复用同一次容器解析，不多开一次文件**：`MultiviewTrackProbe` 的接口从 `looksLikeMultiview(): Boolean`
  改成 `probe(): ContainerProbeResult`，顺带带出遍历轨道时遇到的第一条视频轨宽高（不限编码，跟多视图
  判断各自独立）。之前已经查出资源库刷新本身有主线程阻塞风险（`refreshLibrary()`/`refreshDownloads()`
  对每个视频都做同步的 `MediaExtractor` 探测），所以新增的宽高比检测不能再让这个操作更重。
- **新增 `AspectRatioFormatDetector`**：纯函数，四个分支按顺序判断（~2:1→360°，~1:1→180°，半宽像正常
  单眼画面→SBS，半高像正常单眼画面→TB）。阈值区间下限**是 1.3 不是看起来更自然的 1.0**——设计阶段先定
  的 1.0 会把常规 9:16 竖屏视频（1080×1920）误判成 TOP_AND_DOWN（1080/(1920/2)=1.125，恰好落进
  1.0~2.4），竖屏视频很常见，误判代价不小，才把下限提到 1.3。这是纯算术设计里手算测试用例才发现的，
  提醒以后碰到类似"两个数字互相除一下判断"的启发式规则，务必手算几个真实常见分辨率过一遍边界，不要只
  验证"应该命中"的例子，也要验证"不该命中"的例子。
- **已知未解决的边界情况**（记录下来，不是这一版要修的）：32:9 超宽显示器录屏（如 3840×1080）本身是
  真实存在的平面视频分辨率，跟"横屏内容做成 SBS"的比例区间有重叠，仅凭宽高比分不清两者。这类内容较少
  见于 VR 播放器的典型素材，检测错了可以通过资源库/HUD 里已有的手动修正入口纠正。
- **验证方式**：这一层全是整数/浮点算术，JVM 单测（`AspectRatioFormatDetectorTest`/
  `FilenameFormatDetectorTest`/`FormatDetectorTest`）完全覆盖，`assembleDebug`/`testDebugUnitTest` 均
  BUILD SUCCESSFUL，没有涉及 UI/ECS/Spatial SDK，不需要模拟器或真机验证。

## 2026-08-14 续：给 `PlaybackManager` 的原生回调补上主线程代理（对应 08-10 那条未解决排查）

用户反馈真机上播放完毕仍会崩溃，并提示参考同工作区 `StoryPico` 项目的播放逻辑，怀疑跟"代理设置"有关。这正是
08-10 那条"沉浸模式返回主窗口偶发不弹出"排查记录（见上面对应章节）当时定性为"未解决"的同一个方向，只是这次
真的复现成了用户描述的"崩溃"，不只是"没弹出来"。

**根因（模式对比得出，不是新反编译）**：08-10 那次排查已经反编译确认过
`CypressMediaPlayer.registerCypressMediaPlayerCallback()` 只把**注册**这个动作包进
`runOnScheduleThread`，`onPrepared()`/`onCompleted()` 等回调方法本身完全没有线程切换包装，原生解码线程
直接调用（`thread_debug.txt` 证实过：`Thread-23`/`Thread-58`，每次 prepare 都是新线程，从来不是 main）。
对照同工作区 `StoryPico` 的 `VideoPlayableEntity.kt`：它的 `CypressMediaPlayerCallback` 六个回调方法**全部**
用 `Handler(Looper.getMainLooper())`（成员变量 `mainHandler`）包了一层 `mainHandler.post { ... }`，注释原文
"CypressMediaPlayer JNI callbacks arrive on a non-main thread; use this handler to dispatch all playListener
calls and SDK operations back to the main thread"。本项目的 `PlaybackManager.kt` 一直没有这一层——回调里直接
在原生线程上改 Compose `mutableStateOf`（`state`/`duration`/`hasFirstFrameRendered`）、直接调
`player.play()`、直接 `invoke()` 外部 lambda（`onFirstFrameRendered`/`onPlaybackCompleted`，后者最终驱动
`PlaybackViewModel.returnToMainWindowRequested` → `ImmersiveScene` 的 `closeStage()`/`openWindowContainer()`）。
08-10 那次单轮测试运气好、Compose 状态碰巧在 18ms 内正确传播到了主线程的 `LaunchedEffect`，但这从来不是
可以依赖的保证——`mutableStateOf` 的跨线程写入和后续 SDK 调用链条本身不是线程安全契约的一部分，间歇性
（"连续切换若干轮才出问题"）正好符合竞态的特征，跟这次真机复现成真崩溃是同一个根因的两种表现形式。

**修复**：`PlaybackManager.kt` 新增 `mainHandler = Handler(Looper.getMainLooper())`，`callback` 对象六个
覆写方法（`onPrepared`/`onStarted`/`onCompleted`/`onPaused`/`onVideoSizeChanged`/`onError`）的方法体全部
包进 `mainHandler.post { ... }`，跟 `StoryPico` 的 `VideoPlayableEntity` 逐字段对齐（`onSeekToCompleted`/
`onStopped` 两个空实现本来就没有需要保护的状态，原样保留空实现）。原来 `onCompleted()` 里那行专门为了
08-10 排查加的 `thread_debug.txt` 诊断写入（用来证明"原生回调在非主线程触发"）已经删掉——这一点在 08-10
已经证实过，继续保留只会在修复后显示"总是 main"，没有新增诊断价值；`PlaybackViewModel.kt`/`ImmersiveScene.kt`
里剩下的几处 `thread_debug.txt` 埋点**没有删**（追踪的是修复后应该变化的下游链路：`onPlaybackCompleted-lambda`
这一行修复前是 `Thread-23`/`Thread-58`，修复后应该变成 `main=true`），留着做这次修复的验证证据，等真机
回归确认后再统一清理，不要看到"TEMP DEBUG"注释就当遗留垃圾删掉。

**验证状态（如实记录，未完全闭环）**：
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（`JAVA_HOME` 指到 Android Studio
  自带 JBR，见"本机环境注意事项"）。
- 已装上真机（这次会话连接的是 `PB3B4XJGL2090011G`，型号同样是 B3110，API 36——跟 AGENTS.md 之前记录的
  `D3HDXD2D4363000138` 不是同一台设备，但同型号同样兼容 `compileSdk/targetSdk=35`）、启动，`adb`
  确认进程存活、`pico-cli app logcat` 里没有 `FATAL`/涉及 `tech.illusion.spaceplayer` 的异常。
- **没有验证到的部分**：没能验证"播放到自然结束→自动返回主窗口"这条真正触发过原 bug 的路径本身——设备
  没人佩戴时屏幕会休眠（`lid_switch` 机制）、且沉浸态下 `adb shell input tap` 对 spatial 容器不可靠（两条
  都是 AGENTS.md 前面已经记过的真机限制），没法靠 adb 单独跑通"打开资源库→选视频→进沉浸→等播放完"这条
  完整流程。原 bug 本身是**间歇性**的（08-10 记录是连续四轮才复现一次），所以即使真机上手动测一轮成功，
  也不能就此断言"修复了"——需要用户实际戴上设备，**连续播放多个视频到自然结束、重复几轮**（复现原 bug
  用的同一条件），再把 `thread_debug.txt`（`pico-cli files pull` 或 `adb pull`）和
  `pico-cli app logcat --level E` 一起拉出来，确认：①全程无 `FATAL EXCEPTION`；②`onPlaybackCompleted-lambda`
  这一行变成 `main=true`。这一步还没有做，不能声称问题已经解决。

**用户反馈"还是崩溃"，这个修复不是真正的根因——用 `dumpsys dropbox --print` 抓到了真实证据，纠正**：装上上面
这版修复、用户实机测试后反馈"还是遇到了崩溃"。这次没有再靠猜测或者复用旧结论，直接拉了真机的
`dumpsys dropbox --print`：全程没有一次 `FATAL EXCEPTION`（Compose 回调的主线程代理修复本身没有问题，
`CypressMediaPlayer` 回调链路那部分推理是对的，只是**不是用户这次遇到的这个崩溃**），但抓到一条完整的 ANR
记录，`Subject: Input dispatching timed out ... Waited 5000ms for MotionEvent`，native 栈完整、可读：

```
tech.illusion.spaceplayer.ui.library.MainLibraryScreenKt$MainLibraryScreen$3...（"开始播放"点击回调）
  → tech.illusion.spaceplayer.ui.PlaybackViewModel.startPlayback
  → com.pico.spatial.tracking.hmd.HMDTrackingProvider.start
  → com.pico.spatial.tracking.BaseTrackingDataProvider.start
  → com.pico.spatial.tracking.TrackingDataSourceAdapter.addDataCallback
  → Java_..._HMDTrackingDataSource_nativeStartHMDTrackingDataSource（JNI）
  → spatial::jobs::JobWaiter::wait（原生层同步阻塞等待）
```

**真正的根因**：`startPlayback()` 里 `HMDTrackingProvider().also { it.start() }` 是在主线程（点击回调）
同步调用的，而 `start()` 内部（反编译 `tracking-0.13.3-sources.jar` 确认，
`BaseTrackingDataProvider.start()` 直接调 `dataSource.addDataCallback(callback)`，没有任何线程切换）会
一直阻塞到原生 `JobWaiter::wait` 返回——这条阻塞可以长达 5 秒以上，超过 Android 的输入分发超时阈值，
触发 ANR（用户感受到的"崩溃"，其实是系统弹出"应用未响应"或者直接被 Watchdog 杀掉）。这条 native 栈其实
**已经在 `PlaybackViewModel.kt` 的代码注释里被记录过**（"08-13...确认过...ANR...main thread blocked for
5+ seconds inside the native tracking-start call"）——但当时的应对是"改成每个播放会话用一个全新的
`HMDTrackingProvider` 实例，不跨会话复用"，这个修法处理的是"重复 start/stop 累积状态"这个猜测，没有处理
"`start()` 本身就是一次可能长达数秒的同步阻塞调用，不管实例是不是全新的，只要在主线程调用就有 ANR 风险"这个
更直接的根因——这次真机复测证明前一次的修法没有真正解决问题，是同一个 native 阻塞点、同一条调用栈，只是
换了个"看起来合理"的解释。

**教训**：这次一开始被用户提到"代理"就直接联想到 `CypressMediaPlayer` 回调线程（因为这正是 08-10 那条排查
记录唯一提到"代理"/线程的地方），套用了 StoryPico 的 `mainHandler.post` 模式——这个修复本身没有错（也确实是
一个真实存在、值得修的线程安全问题），但**在没有拿到这次崩溃的真实证据之前就基于旧排查记录的相似性下结论，
是模式匹配代替了证据**。用户第二次反馈"还是崩溃"后才去拉 `dumpsys dropbox --print`，如果一开始就先做这一步，
可以直接跳过第一次误判。**以后遇到"崩溃"类反馈，即使已经有一份看起来高度相关的历史排查记录，也应该先拉一次
新鲜的崩溃证据（`dumpsys dropbox --print` / `logcat -b crash` / `pico-cli app watch-crash`）确认这次的崩溃
签名和历史记录是不是真的同一个，而不是直接套用旧结论。**

**修复**：`PlaybackViewModel.kt` 新增 `backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`，
`startPlayback()` 里改成先把 `HMDTrackingProvider()` 实例立即赋给 `hmdTrackingProvider` 字段（`ImmersiveScene`
每帧读取这个字段本来就是 null-safe 的 `?.`，赋值本身在主线程，不涉及跨线程可见性问题），再用
`backgroundScope.launch { provider.start() }` 把真正阻塞的 `start()` 调用甩到后台线程——点击回调本身立刻返回，
不再占用主线程等待原生调用。反编译确认过 `BaseTrackingDataProvider`/`HMDTrackingProvider` 源码和 SDK 文档
（`pico-dev-knowledge`：`spatial-sdk_tracking_hmd-tracking.md`/`use-dataprovider.md`）都没有"必须在主线程调用
`start()`"这类约束或 `@MainThread` 标注，官方示例代码在 `DisposableEffect` 里调用只是常见写法，不是硬性要求，
所以挪到后台线程是有根据的最小改动，不是猜的。`onCleared()` 里补了 `backgroundScope.cancel()` 避免协程泄漏。
`exitImmersive()` 里的 `hmdTrackingProvider?.stop()` 暂时没动——目前只有 `start()` 被证实会阻塞，`stop()`
只是 `removeDataCallback`，没有证据支持它也会阻塞，不在没有证据的情况下顺手"预防性"改动。

**验证状态（如实记录）**：`assembleDebug`/`testDebugUnitTest` BUILD SUCCESSFUL；已重新装到真机
（`PB3B4XJGL2090011G`）、启动、进程存活。**同样没有能验证到真正触发过 ANR 的那条路径本身**——点击"开始播放"
需要真人戴设备操作（`adb tap` 对这套 spatial 容器不可靠），还没有请用户重新测过这一版。上一版 `mainHandler`
那个修复保留不变（它解决的是一个真实但不同的问题），这次的 `HMDTrackingProvider` 异步化改动是叠加上去的
第二个独立修复，两者都需要用户下一轮实机测试后用 `dumpsys dropbox --print` 确认这次点击"开始播放"（尤其是
"自动返回主窗口→立刻点下一个视频"这个高频触发场景）不再触发 ANR。

## 2026-08-14 终于定位根因：`openWindowContainer`/`closeStage` 的调用顺序反了（对比 StoryPico 得出）

用户第三次反馈"还是崩溃"，并提出了一个正确的问题："为什么 Pico Story 的播放逻辑不会造成这个问题，但当前的
会？"——这个问题直接指向了根因。

### 先拉证据：这压根不是"崩溃"

按上一节记下的教训，先拉新鲜证据而不是接着猜：`dumpsys dropbox --print` 里**没有新的 ANR**（上一版
`HMDTrackingProvider` 异步化修复确实生效了，最新一条 ANR 还是修复前那次），`/data/tombstones/` 最近的
tombstone 是 6 月的（没有原生崩溃），`logcat -b crash` 完全是空的，进程 PID 一直存活。**三种崩溃形态
（Java 异常 / ANR / native crash）一个都没有。** 这和 08-10 那条排查记录第一句"不是崩溃：进程全程同一个
PID"完全吻合——用户看到的"崩溃"，真实症状是**播放结束后主窗口再也没有回来**（眼前一片空/卡住），观感等同
于崩溃，但进程其实好好活着。前面两轮之所以修错方向，一部分原因就是把"崩溃"这个词照字面理解成了进程死亡。

### 根因：容器交接顺序反了，中间出现"零窗口真空期"

对比两个项目的退出路径，差异是结构性的、而且极其一致。**StoryPico 全项目 11 处退出沉浸的地方
（`PlayerSpace`/`OobeSpace`/`WorldAnchorSpace`/`EditorView`/`Main.kt` ×4 等），无一例外全是同一个顺序**：

```kotlin
spatialNavigator.openWindowContainer(id = WINDOW_CONTAINER_HOME_ID)   // 先开下一个容器
closeStage()                                                          // 再关当前容器
```

而且 `PlayerSpace` 的 `DisposableEffect.onDispose` 里**只有资源清理**（`palmGlow.release()`/
`sceneManager.exitStory()`/`stopTracking()`/`stopControllerInput()`/`assetBundle.close()`），**一行导航
都没有**。

SpacePlayer 的 `ImmersiveScene.kt` 恰好是**反过来的**：`returnToMainWindow()` 里先 `closeStage()`，
主窗口的 `openWindowContainer(MAIN_WINDOW_ID)` 放在 `DisposableEffect` 的 `onDispose` 里——也就是等
Stage 已经关掉、整个组合正在销毁的时候才执行。项目自己的 `thread_debug.txt` 把这个时间差记录得清清楚楚：

```
before-closeStage                      t=1786587801552
after-closeStage                       t=1786587801557
onDispose-before-openWindowContainer   t=1786587802081   ← 比 closeStage 晚了 524ms
onDispose-after-openWindowContainer    t=1786587802090
```

**这 524 毫秒里，这个 App 名下一个容器都没有**：主窗口在进入沉浸时就被 `closeWindowContainer` 关掉了，
Stage 也已经关了。系统会在这个真空期里把 App 的 window session 整个拆掉——这正是 08-10 那次抓到、当时
判断为"更底层、改不了"的那三行系统日志的真正含义：

```
Failed looking up window session=Session{...}
setOnBackInvokedCallback(): No window state for package:tech.illusion.spaceplayer
SpatialAudioHelper: can not get attached window
```

session 拆完之后再到达的 `openWindowContainer()` 请求会被静默丢弃（不抛异常、不崩溃），于是主窗口永远
回不来。**"请求先到"还是"拆除先完成"是一场竞态**——这就完美解释了为什么它是间歇性的、为什么 08-10 记录
里"连续三轮都正常、第四轮才卡住"，也解释了为什么前两轮修复（`mainHandler` 代理、`HMDTrackingProvider`
异步化）都没用：**那两处都没碰容器交接顺序**。

StoryPico 从来碰不到这个问题，不是因为它的播放器逻辑更好，而是因为它的容器交接**始终有重叠**：新容器先
建立起来，旧容器才拆——App 名下任何时刻都至少有一个活着的容器，window session 根本没有机会被拆掉。

顺带一提，StoryPico 源码里还有两条注释直接印证了这套顺序是他们踩过坑之后固化下来的约定：
`OobeSpace.kt` 里"Avoid openWindowContainer(HOME) here — calling it together with PlayerSpace's own
openWindowContainer(HOME) on exit causes duplicate Home windows to stack"，以及 `HomeScreen.kt` 里那段
"绝不能再触发 openWindowContainer(HOME)——Pico SDK 会叠出第二个 Home 容器"。**所以"开"和"关"必须严格
配对成一处，不能两边都写**，这也是为什么这次修复必须把 `onDispose` 里那次调用删掉而不是两处都留。

### 修复

`ImmersiveScene.kt`：把 `navigator.openWindowContainer(MAIN_WINDOW_ID)` 从 `onDispose` 移到
`returnToMainWindow()` 里、`closeStage()` **之前**（Stage 还活着的时候同步调用；`openWindowContainer`
是同步方法，`closeStage()` 才是 suspend，所以前者直接调、后者仍在 `coroutineScope.launch` 里）。
`DisposableEffect` 保留进入时的 `closeWindowContainer(MAIN_WINDOW_ID)`，`onDispose` 留空并注释说明
为什么这里**不能**再开窗口（否则就是上面那条重复容器的坑）。改动只有这一处，没有动播放器、ECS、HUD
任何逻辑。

**一个已知的取舍**：如果 Stage 是被系统而不是被 `returnToMainWindow()` 关掉的（比如用户摘下头显、按 home
键），现在就没有兜底重开主窗口的地方了。这个取舍和 StoryPico 完全一致（它 11 处退出也都只在主动路径上开
容器），而且 `onDispose` 里那个"兜底"恰恰就是 bug 本身，不是可以两者兼得的东西。

### 验证状态

`assembleDebug`/`testDebugUnitTest` BUILD SUCCESSFUL，装到真机（`PB3B4XJGL2090011G`）后**由用户实机复测
确认："当前的修改有效，没有造成崩溃了"**——这是这个 bug 从 08-10 第一次记录以来第一次拿到"修好了"的
确认。之前两轮修复（`mainHandler` 主线程代理、`HMDTrackingProvider` 异步化）在真机上都仍会复现，只有这次
的容器交接顺序修复真正解决了问题，反过来印证了根因判断是对的。

确认解决后，排查期间的 `thread_debug.txt` 临时埋点已从 `PlaybackManager.kt`/`PlaybackViewModel.kt`/
`ImmersiveScene.kt` 全部清理干净（`assembleDebug`/`testDebugUnitTest` 复测仍 BUILD SUCCESSFUL），代码里
只保留注释形式的证据说明。

**前两轮修复的处置**：都保留。`PlaybackManager` 的 `mainHandler` 主线程代理和 `HMDTrackingProvider` 的
异步化各自解决的是真实存在的问题（前者是跨线程写 Compose 状态，后者有 `dumpsys dropbox` 里实打实的 ANR
栈为证），只是都不是用户反馈的这个症状的根因。三处改动互不冲突。

### 这次真正该记住的教训

1. **"崩溃"是用户的观感描述，不是技术结论。** 先用 `dumpsys dropbox --print` + `/data/tombstones/` +
   `logcat -b crash` 三件套确认到底是 Java 异常、ANR、native crash 还是"进程活着但界面没了"，这四种的
   根因和排查路径完全不同。这次前两轮都是在没做这个区分的情况下就开始修。
2. **用户说"参考某个项目"时，要对比的是架构和调用顺序，不只是相似的代码片段。** 第一轮我在 StoryPico 里
   搜到 `mainHandler.post` 就停下了——那确实是个真实差异，但不是这个症状的差异。真正的差异需要把两边
   **同一条用户路径**（退出沉浸→回主窗口）完整拉平了逐行对比才能看见。搜关键字容易命中"相似的东西"，
   拉平对比才能命中"不同的东西"。
3. **`onDispose` 只做资源清理，不做导航。** 组合销毁时目标容器往往已经不在了，此时发出的容器操作是在对
   一个正在拆除的 session 说话。容器交接必须在旧容器还活着时完成，保证任何时刻 App 名下至少有一个活容器。

## 2026-08-14 续：主窗口侧边栏调整 + 沉浸 HUD 手部交互（7 项需求，Subagent-Driven Development 执行）

设计稿见 `docs/superpowers/specs/2026-08-14-library-ux-and-hand-interaction-design.md`，实施计划见
`docs/superpowers/plans/2026-08-14-library-ux-and-hand-interaction.md`（9 个任务）。按
`subagent-driven-development` 流程执行：每个任务派独立 subagent 实现、独立 subagent 审查，全部 9 个任务
审查通过，过程记录在 `.superpowers/sdd/2026-08-14-library-ux-and-hand-interaction/progress.md`（这个目录是
scratch 工作区，最终会在收尾时删除，git 历史才是权威记录）。

**改动内容**：

1. 侧边栏 `SideNavigationItem` 高度从 SDK 默认约 48dp 加到 `NAV_ITEM_HEIGHT = 64.dp`。
2. 侧边栏渲染列表隐藏"历史"分类（枚举值和数据仍保留，播放历史仍照常记录，恢复入口只需改回一行 filter）。
3. 新增极小的 Koin 单例 `LibrarySessionState`（只有一个字段 `selectedCategory`），让侧边栏选中分类跨"进入
   沉浸播放→主窗口容器销毁重建→退出"这条边界存活——根因是 `LibraryViewModel` 是纯 `remember`，容器重建
   就重置；只搬这一个字段，其它状态（`selectedItem`/格式筛选等）有意不搬，保持改动面最小。
4. 沉浸 HUD 面板加 `setEulerAngles(EulerAngles(pitch = 22f, yaw = 0f, roll = 0f))` 向后仰。**关键事实**：
   反编译 `foundation-0.13.3-sources.jar` 确认 `EulerAngles` 三个字段单位是**度数**不是弧度（写计划时发现
   设计稿草稿阶段的假设是错的，已改正）。

   **`pitch` 符号这条踩了两次坑，如实记录，不美化**：全量分支 review 时靠反编译 `Matrix4.kt`（绕 X 轴旋转
   遵循右手定则）+ `EulerAngles.toQuat()`（标准 `sin(θ/2)/cos(θ/2)` 构造）+ 项目里已有的 `-Z 朝前` 先例
   （`ecs/SubtitleFollowComponent.kt:24`）推导出"`pitch = -22f` 才对"，并且**在真机测试之前**就在这份
   文档里写下了"不是所有方向性问题都要留到真机才能定"这条结论——装机一测，是反的：顶部往前扒，不是往后仰。
   派了一次多 agent 根因排查（并行验证：AttachmentPanel 内容 quad 的原生渲染路径到底认哪个轴是"正面"、
   独立重新推导旋转矩阵、在本项目和 StoryPico 里找同类先例）之后发现：**旋转数学本身是对的**（三个 agent
   独立反编译验证过 `EulerAngles`/`Matrix4`/`TransformComponent` 没有问题），但"这个面板正面朝 +Z"这个
   前提**从来没有真正验证过**——它是从 SDK 文档里 `LookAtComponent`（通用 ECS 组件）"默认 +Z 朝向目标"这
   条规则类比过来的，`AttachmentPanel` 实际渲染 Compose 内容用的原生路径（`android.view.ViewLink`/
   `ViewAttachment`）在任何已有的 SDK 源码里都找不到，这个类比到底成不成立没有代码层面的证据能确认或证伪。
   最终是**直接问用户"倒底具体是什么样子"**——回答是"顶部往前扒"（简单的前后方向排反，不是完全背对/镜像
   这种更严重的错位）——才把符号改回 `pitch = 22f`，代码注释里如实写明这是**装机直接观察定的，不是数学
   推出来的**。**教训（这次是真的教训，上一条写的"教训"本身就是被这次推翻的那个）**：像"哪个局部轴是这个
   UI 面板组件的正面"这种问题，如果没有可反编译的原生渲染路径能验证，纯靠"通用 ECS 组件的文档 + 另一个
   项目里没在真机上验证过的用法类比"推出来的结论，**置信度不该被当成"已经解决，只是主观好不好看"**——同一
   种"数学上推导出来所以不用测"的自信，这个功能里已经连续错了两次（先是 `+22f` 从没装过机就被换掉，
   然后是"更严谨推导"出来的 `-22f` 装机一测也是反的）。以后遇到"文档没写清楚+找不到原生实现源码"的方向性
   问题，直接问真人"具体看到什么样子"，比再自信地重新推一遍数学更快、更可靠。
5. HUD 首次显示后、首帧渲染完成起算 5 秒自动隐藏一次（不是从点击播放起算，避免被 loading 遮罩吃掉倒计时）；
   `.enabled` 从 `SpatialView.update`（不是每帧循环）搬到已有的每帧 `withFrameNanos` 循环，确保这个属性
   只有一个写入点。**全量分支 review 抓到的真实 bug（已修复）**：这一条单独看没问题，但和第 7 条（捏合切
   HUD 显隐，且是全代码库里唯一能重新显示 HUD 的入口）叠在一起，会在手部追踪完全不可用的场景（模拟器没有
   手部追踪；真机权限没给、设备不支持等）制造一个"5 秒后 HUD 自动消失、且永远没有办法再叫出来"的死路——
   HUD 上有"返回主窗口"这个沉浸播放唯一的退出入口。修复：加一个 `hasEverTrackedHand` 状态，只有真的观测到
   过至少一帧手部数据，5 秒计时器才会真的隐藏 HUD；从未追踪到手，就一直保持可见（回退到这个功能加入之前的
   安全行为）。**这类"每个任务单独看都对，组合起来才出问题"的缺陷是任务拆分执行模式的已知盲区**，全量分支
   review（不是任务级 review）才照见了它。
6. 右手拇指尖/食指尖各绑一个 8mm 纯色小球（`MeshResource.createSphere` + `UnlitMaterial`），跟踪不到时隐藏
   而不是停在世界原点。`HandTrackingProvider` 生命周期完全照抄项目里已验证过的 `HMDTrackingProvider` 模式
   ——每个播放会话新建实例、`start()` 走 `backgroundScope.launch`（阻塞原生调用，8/14 当天的真机 ANR
   就是直接在主线程调用同一条代码路径触发的，这次没有重蹈）、`exitImmersive()`/`onCleared()` 都停止置空。
   补了 `handProvider.start()` 的 `StartResult` + `supportState` 日志（`PlaybackViewModel` 新增
   `TAG`/`Log` import）——`WITHOUT_PERMISSION` 是运行时状态不是一次性检查，装机验证时如果小球不出现，
   这条日志能直接分辨是权限/设备不支持/单纯手不在视野里，不用连着猜好几轮。每帧读取手部数据那段代码包了
   `runCatching`——`HandPose.joint()` SDK 内部用的是 `.first { }` 不是 `.firstOrNull()`，关节列表不全时
   会抛异常，而这段代码跑在全 App 唯一的每帧驱动循环里，一旦抛出去会连累播放进度/字幕跟随/HUD 显隐一起
   罢工，捕获后统一降级成"隐藏小球、重置捏合状态"。
7. 捏合手势切换 HUD 显隐：新增 `PinchDetector`（纯函数，25mm 内判定捏合、40mm 外判定松开，中间维持上一帧
   状态，只在"非捏合→捏合"的上升沿触发一次），单元测试 11/11 通过（含两个阈值边界的严格大小判断、以及一条
   贯穿"进入→在滞回区间内停留几帧→松开→再次落入滞回区间"完整序列的多帧测试——这条是全量分支 review 加的,
   之前 10 条单测都是"调用一次 `update()`"的单帧测试，没有一条真正验证过滞回区间存在的意义——防抖动。

**流程记录**：9 个任务全部 subagent 实现 + 独立 subagent 审查通过后，还跑了一次**全量分支 review**（单个
任务视角看不到、只有把 9 次 commit 叠在一起才会显形的问题）——见上面第 4/5/6/7 条里标"全量分支 review 抓到"
的部分，其中第 5 条（HUD 自动隐藏在手部追踪不可用时会把用户困在沉浸播放里出不来）是这轮全部改动里最严重的
一个真实 bug，任务级 review 完全没发现，因为 Task 5（自动隐藏）和 Task 8（捏合是唯一的重新显示入口）分开看
都各自成立，只有合在一起看才会出现"5 秒后彻底没有退出路径"这个组合效应。**这是任务拆分执行模式的结构性
盲区，不是这次审查疏忽**——以后类似"多个任务分别给同一个 UI 元素的显隐/生命周期添加约束"的场景，全量分支
review 这一步不能省。全量 review 的 3 个 Important + 3 个 Minor 发现已经全部修复并经过 scoped re-review
确认（fix commit 见 git 历史，逐条 verdict 记录在
`.superpowers/sdd/2026-08-14-library-ux-and-hand-interaction/progress.md`，这个目录本身是 scratch 工作区，
收尾后会删除，git 历史才是权威记录）。

**验证状态（如实分层记录，不笼统写"已确认"）**：

- **可自证、已验证**：`./gradlew clean assembleDebug testDebugUnitTest` BUILD SUCCESSFUL，73/73 单测通过
  （0 failure 0 error）。9 个任务的 code review 全部 approved，全量分支 review 的 6 个发现全部修复并经
  scoped re-review 确认 ADDRESSED、无新增缺陷。2 处安全关键点（`HandTrackingProvider.start()` 确实包在
  `backgroundScope.launch` 里、`exitImmersive()`/`onCleared()` 都正确 stop+置空）逐行核对过 diff 文本，
  不是听 implementer 自报；`pitch` 符号方向已经靠反编译源码 + 项目内既有代码先例推导确认（见上文第 4 条），
  不再是"未知，等装机"。装到模拟器和真机（`PB3B4XJGL2090011G`）两边，进程存活，`logcat`/crash
  buffer/`dumpsys dropbox --print` 全干净，没有引入新的 FATAL/ANR。侧边栏高度+隐藏历史两项通过模拟器截图
  确认（`artifacts/task1-2-verify.png`）。
- **没有验证到、需要用户戴设备确认的部分**（如实列出，这次没有一项是"应该没问题就跳过"）：
  1. ~~HUD 倾斜方向~~——**已通过真机验证并修正**：`-22f`（当时自认为"已经推导确认"）装机后是反的（顶部
     往前扒），用户直接反馈现象后改回 `pitch = 22f`。这一条不再是"待验证"，是"验证过、且推翻了之前的
     推导结论"，过程记录见上面第 4 条。
  2. 5 秒自动隐藏的真实挂钟时间是否符合预期（自动化验证被设备端 `screencap`/焦点抢占问题挡住，退而用了
     代码层论证：`hasFirstFrameRendered` 一个会话内只会 false→true 一次，`delay(5000)` 从这个时刻起算，
     这个论证经 reviewer 独立核对过是成立的，但真实挂钟时间没有实测过）。**同时要确认**：`hasEverTrackedHand`
     兜底是否生效——如果这次设备/这次会话手部追踪就是不可用，HUD 应该保持常显，不应该在 5 秒后消失。
  3. 侧边栏选中分类跨沉浸播放往返是否真的保持（`adb tap` 打不进沉浸态的 spatial 容器，这条链路的代码
     被逐行核对过是对的，但端到端没有真机走过一遍）。
  4. 右手拇指/食指小球是否真的跟手、位置对不对。**新增一个具体要观察的点**（全量 review 提出，之前没人
     想到要单独确认）：手离开视野时，小球是"消失"还是"停在最后位置不动"？代码里 `handPose == null` 分支
     会隐藏两个球，但前提是原生层真的会在追踪丢失时把 `right` 置空——如果原生层只是"不再回调"、`latestData`
     一直返回上一个非空值，App 代码是分辨不出来的（`HandTrackingData.timestamp` 是 `internal`，App 侧拿
     不到时间戳做过期判断）。这是两个不同的问题，"跟不跟手"确认了不代表"追踪丢失时的兜底行为"也确认了。
  5. 捏合手势能否稳定触发 HUD 显隐、滞回区间（25-40mm）是否真的消除了抖动。
  6. `HandTrackingProvider().start()` 真正触发的那一刻（进入沉浸播放、开始播放视频）有没有 ANR——这是
     全部改动里唯一一个"和已知真实事故同一条代码路径"的风险点，代码层面的两处安全关键写法都核对过，
     而且现在这一步会往 logcat 打一行 `HandTrackingProvider start result=... supportState=...`（第 6 条
     改动新增），如果小球没出现，直接看这行日志能分清是权限/设备不支持/单纯手不在视野里，不用来回猜。

  以上 6 项没有一项是能靠 `adb`/模拟器自动化跑通的——全部需要用户实际戴上设备、用真手在真实沉浸播放里试。
  下一步：戴上设备，连续播放几个视频，进沉浸后等 HUD 自动隐藏、试着捏合几次看是否稳定切换、留意小球是否
  跟手（以及追踪丢失时是隐藏还是冻结）、如果小球没出现就先看那行新加的 `HandTrackingProvider start` 日志，
  任何一项不对都可以直接反馈，代码层面的证据（diff 逐行核对记录、全量 review 报告、fix wave 的 scoped
  re-review 记录）已经存在
  `.superpowers/sdd/2026-08-14-library-ux-and-hand-interaction/progress.md`，方便回溯是哪个任务、哪次
  review 的判断。
