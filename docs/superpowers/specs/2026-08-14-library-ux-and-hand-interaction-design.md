# 主窗口侧边栏调整 + 沉浸 HUD 手部交互设计

## 背景

用户提出七项独立的体验改进，分三组：

1. **主窗口侧边栏**：选项高度加大、隐藏"历史"分类、退出沉浸播放返回主窗口时保持之前选中的分类。
2. **沉浸 HUD 面板**：进入播放 5 秒后自动隐藏一次；面板整体向后仰一个角度方便俯视；改用右手拇指/食指捏合手势切换显隐（在此之前是纯粹自动隐藏，隐藏后无手动方式再显示）。
3. **手部追踪可视化**：右手拇指尖、食指尖各绑一个小球作为视觉反馈，捏合手势就是靠这两个点的距离判定。

第二组和第三组是同一条交互链路的两端（球给用户看手指位置，捏合驱动 HUD 显隐），必须一起交付才有意义。

## 现状（探索确认的事实）

- 侧边栏 `SideNavigationItem`（`MainLibraryScreen.kt:261-292`）没有显式高度，自然高度来自 SDK 默认值：`LeadingTrailingSize`（32dp 图标盒）+ `ContentPadding`（上下各 8dp）≈ 48dp。SDK 内部先应用调用方 `modifier` 再套 `contentPadding`，所以在调用方 `modifier` 上加 `Modifier.height(...)` 可以直接把外层行高设到目标值，不会被内部内边距抵消。
- `LibraryCategory`（`LibraryCategory.kt`）有四个值：`LIBRARY / DOWNLOADS / HISTORY / IMPORT`。侧边栏只在 `MainLibraryScreen.kt:261` 一处用 `.filter { it != IMPORT }` 过滤渲染哪些分类，`LibraryViewModel`/`iconRes()` 的穷尽 `when` 分支不受过滤影响。
- `LibraryViewModel`（`ui/library/LibraryViewModel.kt`）是纯 `remember` 在 `MainLibraryScreen` 组合里的普通类，其 KDoc 明确写着"不需要跨容器共享，所以不入 Koin"。但主窗口容器在进入沉浸播放时会被 `closeWindowContainer` 关闭、退出时重新 `openWindowContainer`（`ImmersiveScene.kt`），这会让整个组合（包括这个 `remember`）重新初始化——`selectedCategory` 因此每次都被重置回默认值 `LIBRARY`。
- 沉浸 HUD 是一个独立于三个视频实体之外的 `AttachmentPanel` 实体（`HUD_ATTACHMENT_ID`），只设置了 `setPosition(Vector3(0f, 0.9f, -1.5f))`，没有设置任何旋转；显隐目前由 `SpatialView` 的 `update` 块写 `.enabled = !viewModel.showLoadingOverlay`（`ImmersiveScene.kt`）。**`update` 不是每帧循环**，只在它读取的 Compose 状态变化时才重新执行一次——这意味着任何要叠加到 `.enabled` 上的新逻辑，如果也想每帧生效，必须搬到已有的 `LaunchedEffect(Unit) { withFrameNanos { ... } }` 每帧循环里，不能留在 `update` 里跟新逻辑各写一半（否则两者会互相覆盖）。这个每帧循环已经是字幕跟随、播放进度刷新的驱动位置。
- `TransformComponent` 提供 `setQuaternion(Quat)` 和 `setEulerAngles(EulerAngles)`，没有单独的 `setRotation`。`EulerAngles(pitch, yaw, roll)` 单位是弧度。
- `HMDTrackingProvider` 在本项目里的现有用法（`PlaybackViewModel.kt`）是这条交互设计的直接模板：每个播放会话新建实例、`start()` 通过 `backgroundScope.launch` 放到后台协程（因为它是阻塞原生调用，8/14 当天的真机 ANR 就是直接在主线程调用它触发的）、`exitImmersive()`/`onCleared()` 里 `stop()` 并置空。`HandTrackingProvider` 是同一个 `tracking` artifact 下的姊妹 API（`app/build.gradle.kts` 已经依赖着），构造/生命周期/线程注意事项完全对称，不需要新增依赖或权限（SDK 文档和 StoryPico 的 manifest 都印证手部追踪不需要运行时权限）。
- SDK 的 `tracking` 包只提供裸的关节 `position`/`rotation`（`HandJoint.Index.THUMB_TIP` / `INDEX_TIP`），**没有内置捏合/手势识别 API**——`spatial-sdk_interaction_spatial-hand-pose.md` 里的 `detectSpatialTapGesture` 等是 Compose pointer-input 层的点击手势，不是"空中捏合"信号，捏合判定必须自己用两点距离算。
- 同工作区 `StoryPico`（`PlayerSpace.kt`）已经实现过类似判定：右手食指尖到左手中指掌骨距离 < 0.05m 触发，但只有单阈值 + 上升沿，没有滞回区间，在临界距离附近理论上会有抖动风险（该项目的判定用于低频菜单开合，抖动影响小；本项目要驱动的是持续可见的 HUD，需要更稳的判定）。

## 设计

### 一、侧边栏选项高度加大到 64dp

`MainLibraryScreen.kt` 新增常量 `NAV_ITEM_HEIGHT = 64.dp`（与已有的 `HEADER_ROW_HEIGHT`/`FOOTER_HEIGHT` 同一组常量放在一起），在 `SideNavigationItem` 的 `modifier` 链上加 `.height(NAV_ITEM_HEIGHT)`。不改内部图标/文字大小，只加高整行、内容保持垂直居中（`SideNavigationItem` 内部 `Row` 已经是 `verticalAlignment = CenterVertically`）。

### 二、隐藏"历史"分类

`MainLibraryScreen.kt:261` 的过滤条件从 `filter { it != LibraryCategory.IMPORT }` 扩展为 `filter { it != LibraryCategory.IMPORT && it != LibraryCategory.HISTORY }`。

**枚举值本身不删除**，`LibraryViewModel.visibleItems()`/`iconRes()` 的穷尽 `when` 分支原样保留（去掉分支会导致这两处编译不过，且没有必要——保留分支让将来恢复入口只需要改回这一行过滤条件）。播放历史的记录逻辑（`PlaybackHistoryStore`，在首帧渲染时写入）不受影响，继续正常记录，只是没有入口展示。

### 三、退出沉浸播放后保持之前选中的分类

新增一个只有一个字段的极小状态持有者 `LibrarySessionState`（新文件 `ui/library/LibrarySessionState.kt`），注册进已有的 Koin `playback_session_scope`（`di/PlaybackModule.kt`）——这个作用域本来就是为"活过主窗口容器销毁重建"这类状态设计的，`PlaybackViewModel` 已经是这么用的。

```kotlin
class LibrarySessionState {
    var selectedCategory: LibraryCategory = LibraryCategory.LIBRARY
}
```

`LibraryViewModel` 构造时从这个共享状态读初始值，`selectCategory()` 时同步写回去：

```kotlin
class LibraryViewModel(
    context: Context,
    private val sessionState: LibrarySessionState,
) {
    var selectedCategory by mutableStateOf(sessionState.selectedCategory)
        private set

    fun selectCategory(category: LibraryCategory) {
        selectedCategory = category
        sessionState.selectedCategory = category
        selectedItem = null
    }
}
```

`MainLibraryScreen.kt` 里 `remember { LibraryViewModel(context) }` 改成从 Koin scope 取 `LibrarySessionState` 再传入构造函数。

**取舍（有意为之，不是遗漏）**：只搬 `selectedCategory` 这一个字段进 Koin 作用域，`selectedItem`、格式筛选、环境选择等其它 `remember` 状态**不在这次修复范围内**，退出沉浸播放后仍会重置。这是刻意控制改动面——用户只要求"保持之前的侧边栏选项"，没有要求保持选中的具体视频或筛选条件；如果以后需要，可以用同一个模式（往 `LibrarySessionState` 加字段）逐项扩展，不需要现在一次性把整个 `LibraryViewModel` 搬进 Koin。

**只跨播放往返保持，不跨 App 完全重启保持**：`LibrarySessionState` 是 Koin scope 里的普通单例，进程重启后重新创建、回到 `LibraryCategory.LIBRARY` 默认值。这是用户在澄清问题里确认过的范围。

### 四、HUD 面板向后仰 22°

`ImmersiveScene.kt` 的 `initial` 块里，给 HUD 实体的 `TransformComponent` 除了现有的 `setPosition` 之外，追加：

```kotlin
setEulerAngles(EulerAngles(pitch = 22f, yaw = 0f, roll = 0f))
```

（反编译 `foundation-0.13.3-sources.jar` 里的 `EulerAngles.kt` 确认：三个角度字段的单位是**度数**，不是弧度——`toQuat()` 内部自己做 `pitch * (PI / 180.0)` 转换。设计草稿阶段口头假设过弧度，这里改正。）

**已知不确定项，如实标注**：`pitch` 为正是让面板顶部远离用户（后仰）还是让面板顶部靠近用户（前俯），SDK 文档没有给出符号约定，项目里目前没有任何实体设置过 `pitch`，找不到参照。计划里会把这一步标注为"先按 `+22°` 装机看效果，如果方向反了直接改成 `-22°`，这是运行时才能确认的一行数值，不影响其余实现"。

### 五、HUD 首次显示 5 秒后自动隐藏一次，之后捏合切换

**触发计时的起点是首帧渲染完成，不是进入播放的那一刻**——如果从 `startPlayback()` 起算，视频加载慢时 5 秒可能被 loading 遮罩吃掉，面板等于没让用户看清楚就消失了。`PlaybackViewModel` 新增：

```kotlin
var isHudVisible by mutableStateOf(true)
    private set

fun toggleHudVisibility() {
    isHudVisible = !isHudVisible
}
```

`ImmersiveScene.kt` 新增一个 `LaunchedEffect(viewModel.hasFirstFrameRendered)`（复用已有的 `manager.hasFirstFrameRendered` 状态）：首帧渲染完成后 `delay(5000)`，到时把 `isHudVisible` 置 `false`。这是一次性效果，不会在后续捏合把面板重新显示后再次触发——`LaunchedEffect` 的 key 是 `hasFirstFrameRendered`（一个会话内只从 `false` 变 `true` 一次），不会重新进入协程体第二次。

HUD 的 `.enabled` 写入点从 `update` 块搬到已有的每帧 `LaunchedEffect(Unit) { withFrameNanos { ... } }` 循环里，和字幕实体的 `.enabled` 写在同一处，改成同时看两个条件：`!viewModel.showLoadingOverlay && viewModel.isHudVisible`。`update` 块里原来那行 `attachments.entity(HUD_ATTACHMENT_ID)?.enabled = ...` 删除，避免两个写入点互相覆盖。

### 六、右手拇指尖/食指尖两个跟手小球

`PlaybackViewModel` 新增 `handTrackingProvider: HandTrackingProvider?`，生命周期和现有的 `hmdTrackingProvider` 完全对称：`startPlayback()` 里创建、`backgroundScope.launch { it.start() }`；`exitImmersive()`/`onCleared()` 里 `stop()` 并置空。

`ImmersiveScene.kt` 的 `initial` 块新增两个 `Entity`（`thumbTipEntity`/`indexTipEntity`），`MeshResource.createSphere(radius = 0.008f)` + `UnlitMaterial`（颜色用 `SpacePlayerAccent`，和其它强调色元素一致），直接加入 `content`（不挂父实体，和 HUD/loading 面板同样理由——不依赖任何会被禁用的视频实体）。

每帧循环里新增：

```kotlin
val handPose = viewModel.handTrackingProvider?.latestData?.right
if (handPose != null) {
    val thumbTip = handPose.joint(HandJoint.Index.THUMB_TIP)
    val indexTip = handPose.joint(HandJoint.Index.INDEX_TIP)
    // 关节坐标在 Stage 坐标系,两个球没有父实体,等价于 world space,直接 setPosition
    thumbTipEntity.components[TransformComponent::class.java]?.setPosition(thumbTip.position)
    indexTipEntity.components[TransformComponent::class.java]?.setPosition(indexTip.position)
    thumbTipEntity.enabled = true
    indexTipEntity.enabled = true
} else {
    thumbTipEntity.enabled = false
    indexTipEntity.enabled = false
}
```

右手未被追踪到（摘下手套出画面、追踪丢失）时两个球隐藏，不会停留在世界原点造成视觉噪音——参照 `AGENTS.md` 里"给跟随实体一个固定兜底位置，而不是任由它停在世界原点"那条经验反过来用：这里没有兜底位置的需求，直接隐藏更合理。

### 七、捏合切换 HUD 显隐（带滞回，避免临界抖动）

新增纯函数文件 `ecs/PinchDetector.kt`，不依赖任何 SDK 类型，输入两个 `Vector3`（或直接传 `Float` 距离，取决于计划阶段的接口打磨）和上一帧状态，输出新状态 + 是否发生"非捏合→捏合"的上升沿：

```kotlin
private const val PINCH_ENGAGE_DISTANCE = 0.025f   // 25mm 以内 → 判定为捏合
private const val PINCH_RELEASE_DISTANCE = 0.040f  // 40mm 以外 → 判定为松开
// 25~40mm 之间维持上一帧状态,这是滞回区间,防止在单一阈值附近来回抖动

fun updatePinchState(distance: Float, wasPinching: Boolean): PinchState {
    val isPinching = when {
        distance < PINCH_ENGAGE_DISTANCE -> true
        distance > PINCH_RELEASE_DISTANCE -> false
        else -> wasPinching
    }
    return PinchState(isPinching = isPinching, justEngaged = isPinching && !wasPinching)
}
```

这是七项里唯一能在没有设备的情况下用纯 JVM 单元测试完整验证正确性的部分（参照项目里 `AspectRatioFormatDetectorTest` 的先例），计划阶段会要求先写测试再实现，覆盖：两个阈值内外的判定、滞回区间内维持原状态、上升沿只在真正的"进入捏合"那一帧为真、连续两帧都是捏合态时第二帧不再算上升沿。

每帧循环里，`justEngaged` 为真时调用 `viewModel.toggleHudVisibility()`——只在上升沿翻转一次，而不是"捏合期间持续显示、松开就消失"（用户已经在澄清问题里确认过是切换语义，不是按住语义）。上一帧的 `wasPinching` 状态用 `remember { mutableStateOf(false) }` 存在 `ImmersiveScene` 组合里（和 `subtitleFollow`/`spatialAttachments` 同样的模式，不进 ECS `components` 系统）。

## 已知限制与验证边界（如实列出，不假装都能验证）

| 项目 | 我能验证的方式 | 无法验证的部分 |
|---|---|---|
| 侧边栏高度、隐藏历史、分类保持 | 模拟器截图 + `uiautomator dump` | 模拟器上如果有残留 App 抢占画面需要先清理，`AGENTS.md` 已有处理方法 |
| HUD 倾斜角度 | 装机后能截图确认面板确实倾斜了 | 真机截图被 `FLAG_SECURE` 挡死；`pitch` 符号方向、倾斜角度"好不好看"这种主观判断只能用户戴上设备看 |
| HUD 5 秒自动隐藏 | 可以用 `adb logcat`/临时诊断文件记录 `isHudVisible` 随时间变化的时间戳，证明计时器行为符合预期 | — |
| 捏合切换、小球跟手 | `PinchDetector` 纯函数单元测试可以完整验证判定逻辑本身 | 模拟器没有手部追踪，小球是否真的跟手、捏合手势是否顺手，只能真机 + 真手验收 |

七项里第三组（手部追踪、捏合）本质上是"我写完、你戴设备验收"的关系，不是我可以自证完成的工作。

## 范围外（明确不做）

- 不处理"Stage 被系统关闭（摘头显/按 home 键）"时的 HUD/手部追踪清理路径以外的场景——`exitImmersive()`/`onCleared()` 的清理路径已经覆盖这条，不新增专门处理。
- 不做左手镜像支持（只绑右手，是用户在澄清问题里明确选择的范围）。
- 不改变现有的 HUD 内容或按钮布局，只加显隐控制和整体旋转。
