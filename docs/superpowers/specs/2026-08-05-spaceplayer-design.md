# SpacePlayer — 设计文档

- 项目目录：`SpacePlayer`
- 包名：`tech.illusion.spaceplayer`
- 参考应用：[Moon Player](https://moonvrplayer.com/zh/moon-player-apple-vision-pro)（visionOS 沉浸式视频播放器），本设计是其核心播放/沉浸能力向 PICO Spatial SDK 的移植，不追求功能对等
- 平台：PICO Spatial SDK（Kotlin + Compose）

## 1. 目标与总体架构

### V1 目标

- 播放平面 / 180° / 360° 视频，支持单目、SBS（左右）、上下（TB）、MV-HEVC 四种立体封装。
- 平面视频可选择性地"入驻"到三种沉浸式环境之一播放：电影院、星空、海景；180°/360° 视频始终以视频本身作为环境（球体/半球），不叠加环境。
- 播放期间可实时切换环境（电影院⇄星空⇄海景），不打断播放、不重新缓冲。
- 视频来源仅本机存储（`MediaStore` + SAF 文件选择器）。
- 字幕为挂载在视频实体上的 Attachment 文本层，仅支持外部 `.srt`。

### 容器结构

```
DefaultWindowContainer（平面主窗口，唯一常驻容器，不渲染任何视频）
└── 视频库（左侧栏分类 + 右侧列表）+ 环境选择 + 播放入口

Stage(id = "ImmersiveStage")（沉浸容器，仅在点击"开始播放"时 openStage 加载）
├── EnvironmentLayer   —— 天空盒 + StageEnvironmentLightingComponent（电影院/星空/海景三选一，播放期间可实时切换）
├── ScreenEntity       —— 平面视频用：VideoPlayerComponent(mesh = createVideoPanel, VideoMaterial(dimensionMode))
├── SphereEntity       —— 180°/360° 视频用：VideoPlayerComponent(mesh = hemisphere/sphere, VideoMaterial(dimensionMode), cull = FRONT)
├── SubtitleAttachment —— AttachmentPanel 挂载的字幕 Text，锚定在"用户朝向"参考系，不随球体网格旋转飘出视野
└── HUDAttachment      —— AttachmentPanel 挂载的播放控制条（播放/暂停/进度/音量/环境切换/退出）
```

关键约束：

- `CypressMediaPlayer` 只在点击播放时创建一次；`ScreenEntity` / `SphereEntity` 二选一 `enabled`（由视频的 `projection` 决定）。**投影可以在播放期间热切换**（2026-08-10 修订：原文写的是"播放期间不切换"，那是为简化架构自己加的约束，不是 SDK 限制）——沉浸 HUD 的格式修正就走这条路：三个视频实体共用同一个 `CypressMediaPlayer`，切换只改实体 `enabled`（首次用到时才 assemble），播放器完全不碰，进度/音量/缓冲都保留。立体格式同理，直接 `VideoMaterial.setDimensionMode()` 热改。已在模拟器实测：三次切换（180°⇄平面⇄360°）播放位置持续前进、状态全程 `PLAYING`。
- `EnvironmentLayer` 仅对平面视频生效，三态互斥，可随时切换，且切换过程完全不触碰 `CypressMediaPlayer`。
- 退出播放（HUD 的退出按钮）即暂停/停止播放、`closeStage()`、释放 `CypressMediaPlayer`，回到主窗口。

### 架构取舍：单一共享 Stage vs. 每环境/模式一个 Stage

采用**单一共享 Stage + 可切换内容**：

| | 单一共享 Stage（采用） | 每种环境/模式各一个 Stage |
|---|---|---|
| 播放连续性 | 好，切换只是换实体可见性/天空盒，播放不中断 | 差，`openStage`/`closeStage` 会重建容器，容易丢进度或重新缓冲 |
| 代码量 | 一套沉浸场景 Compose 树 + 内容分支 | 5 套（电影院/星空/海景/180球/360球）Stage 声明，模板重复 |
| 未来加环境 | 加一个天空盒资源 + 一个枚举分支 | 加一个新 Stage 声明 + AndroidManifest 配置 |

播放组件统一使用 `VideoPlayerComponent`（而非 `VideoComponent` + 第三方播放器）：V1 无 DRM / ExoPlayer 需求，SDK 文档中 SBS/立体视频等能力已由 `VideoPlayerComponent` + `CypressMediaPlayer` 原生覆盖。

## 2. 数据模型与格式识别

### 核心数据模型

```kotlin
data class VideoItem(
    val uri: Uri,                       // MediaStore content:// 或 SAF 授予的持久化 Uri
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val thumbnailUri: Uri?,              // MediaStore 自带缩略图，取不到时懒生成
    val projection: Projection,          // FLAT / HEMISPHERE_180 / SPHERE_360
    val stereoMode: StereoMode,          // MONO / SIDE_BY_SIDE / TOP_AND_DOWN / MULTIVIEW_MVHEVC
    val formatSource: FormatSource,      // DETECTED_CONTAINER / DETECTED_FILENAME / MANUAL_OVERRIDE / DEFAULT
    val preferredEnvironment: Environment? // 仅 FLAT 有意义；null = 沿用上次选择/默认电影院
)

data class PlaybackHistoryEntry(
    val videoUri: Uri,
    val lastPlayedAt: Long
)
```

`projection` / `stereoMode` 直接映射到 SDK 的网格选择（平面 panel / 半球 / 球体）与 `VideoDimensionMode`（MONO / TOP_AND_DOWN / SIDE_BY_SIDE / MULTIPLE_VIEW）。

### 识别流水线（容器探测 → 文件名识别 → 默认兜底 → 手动覆盖）

1. **容器探测**（优先级最高，仅对 MV-HEVC 有意义）：用 `MediaExtractor` / `MediaFormat` 探测 mov/mp4 容器内是否存在多视图（multiview / MV-HEVC）轨道标识，命中则 `stereoMode = MULTIVIEW_MVHEVC`，`formatSource = DETECTED_CONTAINER`。
2. **文件名关键词识别**：解析常见业界约定关键词（`_180_`/`_180x180`、`_360_`/`_equirect`、`_sbs`/`_3dh`、`_tb`/`_ou`/`_3dv`、`_mvhevc`），命中则设置对应 `projection`/`stereoMode`，`formatSource = DETECTED_FILENAME`。
3. **默认兜底**：两者都未命中时，`projection = FLAT`、`stereoMode = MONO`，`formatSource = DEFAULT`。
4. **手动覆盖**：提供"投影类型 + 立体格式"两个下拉，保存为 `MANUAL_OVERRIDE` 并持久化（本地存储，按 `uri` 或文件内容 hash 做 key，避免文件改名后覆盖记录丢失）。

**2026-08-10 修订**：原文把手动覆盖入口放在列表项内的轻量弹层里，且只在 `formatSource == DEFAULT`（纯兜底猜测）时才提示。实际实现有两处调整：

- 入口从列表项弹层挪到了**主窗口底部操作栏**（与环境选择器、"开始播放"同一行），并且**沉浸 HUD 的控制行里也有同一组下拉**（见第 4 节）——发现识别错了不必退出沉浸再回主窗口改。两处共用同一个组件与同一条持久化路径。
- 两个下拉**始终显示**，不再按 `formatSource == DEFAULT` 隐藏：选中任意一项都会把来源改成 `MANUAL_OVERRIDE`，如果按原规则隐藏，就会出现"选完一次这组控件自己消失"的副作用。识别来源仍然显示在卡片副标题上（容器探测／文件名识别／默认兜底／手动指定），用来区分"有依据的识别"和"兜底猜测"。

### 文件库管理

- 启动/下拉刷新时用 `MediaStore.Video` 查询公共视频目录，列出缩略图 + 文件名 + 时长；
- "视频资源库"分类 = MediaStore 扫描结果中排除 Download 目录的部分（Movies/DCIM/自定义目录等）；
- "下载"分类 = MediaStore 扫描 Download 目录下的视频；
- "历史"分类 = 按 `PlaybackHistoryEntry.lastPlayedAt` 倒序查询，同一 `videoUri` 去重取最新一条，跨越以上所有来源；写入时机是**首帧成功渲染**那一刻，而非点击播放的瞬间，避免加载失败/取消的视频污染历史；
- "其它"分类为一次性触发的动作项（非列表）：点击即弹出 SAF `ACTION_OPEN_DOCUMENT`，选中的文件 `takePersistableUriPermission` 后并入"视频资源库"；
- 不做网络/云盘/URL 直链；不与 FileSendApp 集成（见第 5 节）。

## 3. 沉浸式环境设计与切换流程

### 三种环境的内容构成

| 环境 | 天空盒/场景 | 环境光照 | 银幕位置 | 美术资产复杂度 |
|---|---|---|---|---|
| 电影院 | 影厅内部网格（暗色墙面/地面 + 简化座椅剪影），银幕嵌在正前方墙面 | `StageEnvironmentLightingComponent` 低照度暗场 IBL，模拟影厅暗光 | `ScreenEntity` 位置/朝向随环境预设固定在墙面锚点 | 最高：需要一个简化的 3D 影厅模型（V1 用简化几何体 + 基础材质占位） |
| 星空 | equirectangular 星空 HDRI 贴到天空球内表面 | 中等亮度、偏冷色调 IBL | `ScreenEntity` 悬浮在用户前方固定距离/高度（无实体依托） | 较低：一张星空 HDRI + 一个球体网格 |
| 海景 | equirectangular 海天全景 HDRI | 明亮自然光 IBL，暖色调 | 同星空，悬浮锚点 | 较低：一张海景 HDRI + 一个球体网格 |

三者互斥挂在 `EnvironmentLayer` 下，同一时刻只有一个天空盒实体 `enabled = true`。切换环境时只做：

1. 关闭旧天空盒实体、开启新天空盒实体；
2. 更新 `StageEnvironmentLightingComponent` 参数；
3. 电影院模式下把 `ScreenEntity` 重新挂到墙面锚点，其余两个环境挂到悬浮锚点。

全程不触碰 `CypressMediaPlayer`，播放不中断。

### 180°/360° 视频

不经过 `EnvironmentLayer`：直接 `SphereEntity.enabled = true`，`EnvironmentLayer` 全部关闭（环境概念对该视频不适用）。沉浸 HUD 中的环境切换控件组禁用/隐藏，替换为"全景视频·自动沉浸"提示文案。

### 播放与 dock 的完整流程

1. 主窗口选中一个 `VideoItem`；若 `projection == FLAT`，底部操作栏的环境选择器可交互（默认 `preferredEnvironment` 或电影院）；180/360 视频时该区域置灰/替换提示文案。
2. 点击"开始播放"→ 创建 `CypressMediaPlayer`（`setDataSource` + `prepareAsync`）→ `openStage("ImmersiveStage", StageStyle.Full)`（V1 固定用 `Full`：视频/环境应完全替代真实环境的 Video Seethrough，而非与之混合；`Progressive` 沉浸度调节留作未来增强）→ 首帧渲染前展示 loading 的 `AttachmentPanel`（复用官方示例 `hasFirstFrameRendered` 门控：`PREPARING` / `ERROR` / `PLAYING && !hasFirstFrameRendered` 三态显示 loading/error）。
3. 首帧渲染后：写入 `PlaybackHistoryEntry`；HUD 出现播放控制条；若为平面视频，HUD 同时出现环境切换控件，切换逻辑如上（播放期间可随时切换，不限于开始前）。
4. 点击 HUD 退出 → 暂停/停止播放、`closeStage()`、释放 `CypressMediaPlayer`，回到主窗口（记录该视频最近使用的 `preferredEnvironment`，下次预选）。

## 4. UI 布局

### 主窗口（DefaultWindowContainer）

```
Row
├── 左侧栏（固定分类）
│   ├── 视频资源库
│   ├── 下载
│   ├── 历史
│   └── 其它 —— 非列表项，点击触发系统文件选择对话框
└── 右侧内容区（Column，单屏，无详情页跳转）
    ├── 顶部：当前分类名 + 格式筛选（全部/平面/180/360）
    ├── 中部：垂直滚动的视频列表（缩略图卡片 + 文件名 + 时长 + 格式徽标；点击选中，卡片高亮，不跳转/不遮挡列表）
    │        └── 格式为兜底默认值（未被容器探测或文件名规则识别）时显示"修正格式"小图标，点击弹出轻量弹层修正，不离开当前屏
    └── 底部常驻操作栏（Row）
        ├── 左：环境选择器，横向滚动卡片（电影院/星空/海景），仅当选中项为平面视频时可交互；
        │      选中 180/360 视频时该区域置灰或替换为"全景视频·自动沉浸"提示文案
        └── 右："开始播放" 按钮，作用于当前选中的 VideoItem；未选中任何视频时整个底部操作栏禁用
```

沿用 SeasonsApp 的单平面窗口 + `windowConstraints` 固定最小尺寸模式。

### 沉浸内 HUD（AttachmentPanel，挂在 ScreenEntity/SphereEntity 上，跟随其显隐）

```
播放控制条（贴近银幕下方或用户视野下方固定位置）：
├── 播放/暂停
├── 进度条（拖动预览，松手才 seekTo）
├── 音量
├── 环境切换（仅平面视频显示，180/360 替换为提示文案）
├── 格式修正（2026-08-10 新增）—— 投影 + 立体格式两个下拉，与环境切换同处控制行、中间用分割线分组；
│                                  热切换不中断播放，结果同样写回 `VideoPreferencesStore`，退出后主窗口卡片徽标同步
└── 退出（回到主窗口）
```

HUD 在首帧渲染前隐藏，仅展示 loading/error 的 `AttachmentPanel`。

### 字幕（AttachmentPanel Text 层）

- 独立于 HUD 的另一个 Attachment，锚定在"用户朝向"参考系（而非银幕/球体网格本身），保证 180°/360° 场景下字幕不随视角旋转飘出可视范围；
- V1 仅支持外部 `.srt` 字幕文件（与视频同目录同名，或在格式修正弹层中手动指定），纯文本逐行时间轴渲染，不做内嵌字幕轨道解析、不做 `.ass` 特效样式。

## 5. 非目标（V1 明确不做）

- 网络协议播放：不做 SMB/DLNA/云盘/URL 直链。
- 与 FileSendApp 集成：两者之间目前没有关于接收文件落地位置/访问方式的任何约定（FileSendApp 写入自己的私有目录，SpacePlayer 无法读取），本次不做集成，也不作为既定的后续计划。
- 内嵌字幕轨道解析、`.ass` 特效字幕、字幕样式自定义：仅外部 `.srt` 纯文本。
- AI 2D→3D 实时转换：不在本次范围。
- 电影院环境的高保真美术：V1 用简化几何体 + 基础材质占位，后续可替换精修模型/采购资产。
- 沉浸环境的过渡动画：切换环境是硬切换，不做淡入淡出等过渡效果。

## 6. 参考资料

- PICO Spatial SDK 文档：`spatial-sdk_video_video-overview.md`、`spatial-sdk_video_use-videoplayercomponent.md`、`spatial-sdk_video_sample-play-spatial-video-in-an-app.md`（官方"Play spatial video in an app"示例，演示了平面窗口面板 + 沉浸式视频球体两种展示形式，是本设计容器架构的直接依据）
- PICO Spatial SDK Stage 文档：`spatial-sdk_spatial-container_manage-stages_declare-a-stage.md`（`StageStyle`、环境光照、动态属性切换）
- 参考应用：[Moon Player](https://moonvrplayer.com/zh/moon-player-apple-vision-pro)
