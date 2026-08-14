# 主窗口侧边栏调整 + 沉浸 HUD 手部交互 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 主窗口侧边栏加大选项高度、隐藏"历史"分类、跨沉浸播放往返保持选中分类；沉浸 HUD 面板向后仰、首次显示 5 秒后自动隐藏一次、之后用右手拇指/食指捏合手势切换显隐，并给这两个指尖各绑一个可视小球。

**Architecture:** 七项改动分三组，按依赖顺序串行执行：主窗口侧边栏（纯 Compose + 一个极小的 Koin 单例）→ 沉浸 HUD 显隐/倾斜（ECS `TransformComponent` + 已有的每帧 `withFrameNanos` 循环）→ 手部追踪（新的 `HandTrackingProvider`，生命周期完全照抄项目里已验证过的 `HMDTrackingProvider` 模式）+ 捏合判定（独立纯函数，先写单测）。所有新状态要么是 Koin 单例（跨容器销毁存活），要么是 `PlaybackViewModel` 的字段（Koin scoped，同样存活），不引入新的持久化层。

**Tech Stack:** Kotlin, Jetpack Compose (SpatialUI), PICO Spatial SDK 0.13.3（`com.pico.spatial.tracking.hand`/`core.ecs`/`core.math`），Koin DI，JUnit（纯 JVM 单测，无 Robolectric）。

## Global Constraints

- 目标设备/模拟器构建前必须 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（JDK 25 系统默认版本和 Gradle 8.13 的 Kotlin DSL 解析不兼容），详见 `AGENTS.md`"本机环境注意事项"。
- 每个任务完成后跑 `./gradlew :app:assembleDebug :app:testDebugUnitTest`，必须 BUILD SUCCESSFUL 才能进入下一个任务。
- `EulerAngles(pitch, yaw, roll)` 三个字段单位是**度数**，不是弧度（反编译 `foundation-0.13.3-sources.jar` 的 `EulerAngles.kt` 确认：`toQuat()` 内部自己做 `* (PI / 180.0)`）——任何角度值直接传度数字面量，不要调用 `Math.toRadians`。
- `HandTrackingProvider`/`HMDTrackingProvider` 的 `start()` 都是阻塞原生调用（真机 ANR 实测证实过 HMD 那个，`tracking-0.13.3-sources.jar` 的 `BaseTrackingDataProvider.start()` 源码显示两者走同一条 `dataSource.addDataCallback()` 路径，没有理由不同）——**永远不要在主线程/点击回调里直接调用 `.start()`**，必须走 `PlaybackViewModel` 已有的 `backgroundScope.launch { }`。
- SDK 的 `tracking` 包没有内置捏合/手势识别 API，捏合判定必须自己用两个关节的 `Vector3.distance()` 算（`com.pico.spatial.core.math.Vector3` 的伴生对象方法）。
- 自定义 Kotlin 类不能通过 `entity.components.set(SomeCustomComponent())` 挂进 ECS——原生层只认内置 `Component` 子类型（`TransformComponent`/`ModelComponent`/`VideoPlayerComponent`/...），本计划新增的手部状态（`wasPinching`）用 `remember { mutableStateOf(false) }` 存在 Compose 组合里，不进 ECS。
- `SpatialView` 的 `update` 参数不是每帧循环，只在它读取的 Compose 状态变化时才重跑一次——任何需要每帧生效的新逻辑必须写进已有的 `LaunchedEffect(Unit) { withFrameNanos { ... } }` 循环，且同一个 `.enabled` 属性只能有一个写入点，不能一半留在 `update`、一半挪到每帧循环。

---

### Task 1: 侧边栏选项高度加大到 64dp

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt:92-97`（新增常量）、`:271-278`（`SideNavigationItem` 的 `modifier` 链）

**Interfaces:**
- 不涉及跨文件接口，纯本地 UI 改动。

**背景**：`SideNavigationItem` 目前没有显式高度，自然高度来自 SDK 默认值（`LeadingTrailingSize` 32dp 图标盒 + `ContentPadding` 上下各 8dp ≈ 48dp）。SDK 内部先应用调用方 `modifier` 再套 `contentPadding`（反编译 `design-0.13.3-sources.jar` 的 `SideNavigation.kt` 确认），所以在调用方 `modifier` 上加 `.height(...)` 能直接把外层行高设到目标值。

- [ ] **Step 1: 加高度常量**

在 `MainLibraryScreen.kt` 里已有的常量组（`HEADER_ROW_HEIGHT`/`HEADER_ROW_HEIGHT_PADDING`/`FOOTER_HEIGHT`/`SIDEBAR_WIDTH`，第 81-97 行）后面追加一个新常量。当前第 92-97 行是：

```kotlin
private val FOOTER_HEIGHT = 56.dp

// Fixed sidebar width - see the comment at its usage site for why this must be explicit now that
// the "其它" action lives in a plain wrapping Column instead of inside SideNavigation's own
// width-constrained content slot.
private val SIDEBAR_WIDTH = 220.dp
```

改成：

```kotlin
private val FOOTER_HEIGHT = 56.dp

// Fixed sidebar width - see the comment at its usage site for why this must be explicit now that
// the "其它" action lives in a plain wrapping Column instead of inside SideNavigation's own
// width-constrained content slot.
private val SIDEBAR_WIDTH = 220.dp

// SideNavigationItem's natural height is only the SDK default (~48dp: a 32dp icon box plus 8dp top/
// bottom content padding) - explicitly taller per user request. The SDK applies the caller's
// modifier BEFORE its own contentPadding (confirmed by decompiling design-0.13.3-sources.jar's
// SideNavigation.kt), so a plain .height() here sets the outer row height cleanly without being
// squeezed by the internal padding.
private val NAV_ITEM_HEIGHT = 64.dp
```

- [ ] **Step 2: 给 `SideNavigationItem` 的 modifier 加高度**

当前第 271-278 行：

```kotlin
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .clickable(
                                        interactionSource = categoryInteractionSource,
                                        indication = LocalIndication.current,
                                        onClick = { libraryViewModel.selectCategory(category) },
                                    )
                                    .controllerHapticFeedback(interactionSource = categoryInteractionSource),
```

改成（只在最前面插入一行 `.height(NAV_ITEM_HEIGHT)`）：

```kotlin
                                modifier = Modifier
                                    .height(NAV_ITEM_HEIGHT)
                                    .padding(bottom = 4.dp)
                                    .clickable(
                                        interactionSource = categoryInteractionSource,
                                        indication = LocalIndication.current,
                                        onClick = { libraryViewModel.selectCategory(category) },
                                    )
                                    .controllerHapticFeedback(interactionSource = categoryInteractionSource),
```

- [ ] **Step 3: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 模拟器截图验证**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
pico-cli capture screenshot --out ./artifacts/task1-sidebar-height.png --device emulator-5554
```

检查截图：三个侧边栏选项（视频资源库/下载/历史）之间的间距明显比改动前（`artifacts/baseline-sidebar-2026-08-14.png`）更宽松，文字/图标仍然垂直居中。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Enlarge sidebar nav item height to 64dp"
```

---

### Task 2: 隐藏"历史"分类

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt:261`（Task 1 完成后的状态）

**Interfaces:**
- 不改变 `LibraryCategory` 枚举本身，`LibraryViewModel.visibleItems()`/`iconRes()` 的穷尽 `when` 分支原样保留。

- [ ] **Step 1: 扩展侧边栏渲染过滤条件**

只改一行（保持在同一行内，不换行拆成链式调用），这样 `forEach` 循环体的缩进完全不受影响，不需要重新缩进下面 30 行。

当前（Task 1 之后）第 261 行：

```kotlin
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT }.forEach { category ->
```

改成（在它上面加一行注释，这一行本身只扩展 `filter` 里的条件）：

```kotlin
                        // HISTORY is intentionally excluded from the sidebar per user request, but the
                        // enum value itself is kept (LibraryViewModel.visibleItems()/iconRes()'s
                        // exhaustive `when` branches still need it to compile) - restoring the entry
                        // point later is a one-line revert of this filter.
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT && it != LibraryCategory.HISTORY }.forEach { category ->
```

这一行比文件里当前最长的行（126 字符）还长（142 字符），但项目没有配置行长 lint 规则，且这样改动范围严格限定在这一行，不会牵动下面 30 行 `forEach` 循环体的缩进——比拆成多行链式调用更安全。

- [ ] **Step 2: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 模拟器截图验证**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
pico-cli capture screenshot --out ./artifacts/task2-hide-history.png --device emulator-5554
```

检查截图：侧边栏只剩"视频资源库"/"下载"两个可选分类（"其它·选择文件"仍在底部，那个是独立的 SAF 入口，不受这次过滤影响）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Hide the history category from the sidebar"
```

---

### Task 3: 退出沉浸播放后保持之前选中的侧边栏分类

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibrarySessionState.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt:19-40`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt:108-109`

**Interfaces:**
- Produces: `class LibrarySessionState { var selectedCategory: LibraryCategory }`，通过 Koin `single` 注册，`GlobalContext.get().get<LibrarySessionState>()` 取实例。
- Consumes（后续任务不依赖这个，本任务是叶子任务）：无。

**背景**：`LibraryViewModel` 目前是纯 `remember` 在 `MainLibraryScreen` 组合里的普通类。主窗口容器在进入沉浸播放时被 `closeWindowContainer` 关闭、退出时重新 `openWindowContainer`（`ImmersiveScene.kt`），这会让整个组合（包括这个 `remember`）重新初始化，`selectedCategory` 因此每次都被重置回默认值。修复思路：把 `selectedCategory` 这一个字段的权威来源挪到一个 Koin `single`（进程生命周期，和已有的 `VideoPreferencesStore`/`PlaybackHistoryStore` 同一类——这两个也是 `single`，不是 `scoped`，`PlaybackModule.kt` 现在只有 `PlaybackViewModel` 一个东西是 `scoped`）。

- [ ] **Step 1: 新建 `LibrarySessionState`**

创建 `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibrarySessionState.kt`：

```kotlin
package tech.illusion.spaceplayer.ui.library

/**
 * Survives the main WindowContainer being torn down and recreated across an immersive-playback
 * round-trip - LibraryViewModel itself does not (see its own KDoc), because it's a plain `remember`
 * in MainLibraryScreen's composition, which gets fully re-initialized every time that container
 * reopens (ImmersiveScene.kt closes/reopens MAIN_WINDOW_ID around each playback session).
 *
 * Registered as a Koin `single` in playbackModule (di/PlaybackModule.kt) - same category as
 * VideoPreferencesStore/PlaybackHistoryStore there: process-lifetime state, reset only on a full
 * app restart, not on every container round-trip.
 *
 * Deliberately holds only this one field, not the rest of LibraryViewModel's state (selectedItem,
 * format filter, ...) - see the design spec's "取舍" note for why the scope is kept this narrow.
 */
class LibrarySessionState {
    var selectedCategory: LibraryCategory = LibraryCategory.LIBRARY
}
```

- [ ] **Step 2: 注册进 Koin**

当前 `app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt` 全文：

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

改成：

```kotlin
package tech.illusion.spaceplayer.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.library.storage.SharedPreferencesKeyValueStore
import tech.illusion.spaceplayer.ui.PlaybackViewModel
import tech.illusion.spaceplayer.ui.library.LibrarySessionState

const val PLAYBACK_SESSION_SCOPE_ID = "playback_session_scope"

val playbackModule = module {
    single { VideoPreferencesStore(SharedPreferencesKeyValueStore(get(), "video_preferences")) }
    single { PlaybackHistoryStore(SharedPreferencesKeyValueStore(get(), "playback_history")) }
    single { LibrarySessionState() }
    scope(named(PLAYBACK_SESSION_SCOPE_ID)) {
        scoped { PlaybackViewModel(get(), get(), get()) }
    }
}
```

- [ ] **Step 3: `LibraryViewModel` 读写 `LibrarySessionState`**

当前 `LibraryViewModel.kt` 第 19-40 行：

```kotlin
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
```

改成：

```kotlin
/**
 * Most of this state (libraryItems/selectedItem/format filter) only lives inside the main window
 * and doesn't need to survive it being torn down, so it stays a plain `remember` in
 * [MainLibraryScreen] rather than going into Koin. `selectedCategory` is the one exception - its
 * initial value and every write go through [sessionState], because it's the one field that must
 * survive the main WindowContainer being closed and reopened around each immersive playback
 * session. See docs/superpowers/specs/2026-08-14-library-ux-and-hand-interaction-design.md section
 * 3 for why only this one field moved and not the rest of this class.
 */
class LibraryViewModel(
    private val context: Context,
    private val sessionState: LibrarySessionState,
) {
    private val repository = VideoLibraryRepository(context)
    private val formatDetector = FormatDetector(MediaExtractorMultiviewProbe())
    val preferencesStore =
        VideoPreferencesStore(SharedPreferencesKeyValueStore(context, "video_preferences"))

    var selectedCategory by mutableStateOf(sessionState.selectedCategory)
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
        sessionState.selectedCategory = category
        selectedItem = null
    }
```

`LibrarySessionState` 和 `LibraryViewModel` 在同一个包（`tech.illusion.spaceplayer.ui.library`），不需要新增 import。

- [ ] **Step 4: `MainLibraryScreen.kt` 从 Koin 取 `LibrarySessionState` 传给 `LibraryViewModel`**

当前第 108-109 行：

```kotlin
    val context = LocalContext.current
    val libraryViewModel = remember { LibraryViewModel(context) }
```

改成：

```kotlin
    val context = LocalContext.current
    val librarySessionState: LibrarySessionState = GlobalContext.get().get()
    val libraryViewModel = remember { LibraryViewModel(context, librarySessionState) }
```

`GlobalContext` 已经在文件顶部导入（`import org.koin.core.context.GlobalContext`），`LibrarySessionState` 和这个文件同包，都不需要新增 import。

- [ ] **Step 5: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 模拟器验证跨往返保持**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
sleep 10
```

用临时 `LaunchedEffect` 或直接观察：选中"下载"分类 → 选一个视频进入沉浸播放 → 通过 HUD"返回主窗口"退出 → 截图确认侧边栏仍然高亮"下载"而不是回到"视频资源库"。如果模拟器上没有可播放的下载分类视频，改用"视频资源库"分类下的任意条目（重点是验证"退出后侧边栏选中态不回到默认值"，具体是哪个分类不重要，只要不是初始的 LIBRARY 且验证前特意选了别的分类）。

```bash
pico-cli capture screenshot --out ./artifacts/task3-before-immersive.png --device emulator-5554
# ... 进入沉浸播放、退出 ...
pico-cli capture screenshot --out ./artifacts/task3-after-immersive.png --device emulator-5554
```

对比两张截图侧边栏高亮的分类一致。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/LibrarySessionState.kt \
        app/src/main/java/tech/illusion/spaceplayer/di/PlaybackModule.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryViewModel.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Persist the selected sidebar category across immersive playback round-trips"
```

---

### Task 4: HUD 面板向后仰 22 度

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt:1-14`（imports）、`:154-159`（HUD 实体定位）

**Interfaces:**
- 不涉及跨文件接口。

**已知不确定项**：`pitch` 为正是让面板顶部远离用户还是靠近用户，SDK 文档没有给出符号约定，项目里没有先例。本任务先按 `+22f` 装机看效果；如果方向反了，Step 3 会指出怎么改成 `-22f`，这是运行时才能确认的一行数值,不影响其余实现。

- [ ] **Step 1: 加 `EulerAngles` import**

当前 `ImmersiveScene.kt` 第 12-13 行：

```kotlin
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
```

改成：

```kotlin
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
```

- [ ] **Step 2: 给 HUD 实体加旋转**

当前第 154-159 行：

```kotlin
                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0.9f, -1.5f))
                    }
                    content.addEntity(this)
                }
```

改成：

```kotlin
                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0.9f, -1.5f))
                        // Tilted back so the panel faces more toward the user's downward gaze -
                        // EulerAngles fields are DEGREES, not radians (confirmed by decompiling
                        // foundation-0.13.3-sources.jar's EulerAngles.toQuat(), which does its own
                        // `* (PI / 180.0)` conversion internally). Sign direction is unverified - the
                        // SDK docs don't state which way positive pitch tilts, and no other entity in
                        // this project sets pitch. If this reads backwards on device, flip to -22f.
                        setEulerAngles(EulerAngles(pitch = 22f, yaw = 0f, roll = 0f))
                    }
                    content.addEntity(this)
                }
```

- [ ] **Step 3: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机验证倾斜方向（模拟器截图拿不到沉浸态 HUD 的正确视角，且真机屏幕本身有 `FLAG_SECURE` 截不了图，这一步只能装机后请用户目视确认）**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device <真机 device id>
pico-cli app launch tech.illusion.spaceplayer --device <真机 device id>
```

请用户戴上设备进入任意视频的沉浸播放，回答：HUD 面板是"顶部往后仰、更方便往下看"，还是"顶部往前扣、更难看清"？如果是后者，把 Step 2 里的 `pitch = 22f` 改成 `pitch = -22f`，重新走 Step 3-4。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt
git commit -m "Tilt the immersive HUD panel back for easier downward viewing"
```

---

### Task 5: HUD 首次显示 5 秒后自动隐藏一次，`.enabled` 单一写入点

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt:92-94`（新增状态+方法）、`:250-274`（`startPlayback` 里重置状态）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`（Task 4 完成后的状态，导入、新增 `LaunchedEffect`、每帧循环新增一行、`update` 块删一行）

**Interfaces:**
- Produces: `PlaybackViewModel.isHudVisible: Boolean`（只读，`by mutableStateOf`）、`fun toggleHudVisibility()`、`fun hideHud()`。后续 Task 8 会调用 `toggleHudVisibility()`。

**背景**：HUD 的 `.enabled` 目前由 `SpatialView` 的 `update` 块写（`= !viewModel.showLoadingOverlay`），但 `update` 不是每帧循环。要叠加"5 秒后自动隐藏"和后续任务的"捏合切换"，必须把这个写入点搬到已有的每帧 `LaunchedEffect(Unit) { withFrameNanos { ... } }` 循环里，且只能有一个写入点——`update` 块里原来那一行要删掉。

- [ ] **Step 1: `PlaybackViewModel` 新增 HUD 显隐状态**

当前 `PlaybackViewModel.kt` 第 92-94 行：

```kotlin
    /** Set when playback reaches the end - ImmersiveScene observes this to return to the main window. */
    var returnToMainWindowRequested by mutableStateOf(false)
        private set
```

改成：

```kotlin
    /** Set when playback reaches the end - ImmersiveScene observes this to return to the main window. */
    var returnToMainWindowRequested by mutableStateOf(false)
        private set

    /** Drives the HUD AttachmentPanel's `.enabled` from ImmersiveScene's per-frame loop - starts
     * visible each session, auto-hides once 5s after the first frame renders, then only changes via
     * a pinch gesture (Task 8's [toggleHudVisibility] call). */
    var isHudVisible by mutableStateOf(true)
        private set

    fun toggleHudVisibility() {
        isHudVisible = !isHudVisible
    }

    /** One-directional, not a toggle - called once from the 5s auto-hide timer so it can't
     * accidentally re-show a panel the user already pinched back on within that window. */
    fun hideHud() {
        isHudVisible = false
    }
```

- [ ] **Step 2: `startPlayback()` 里重置 `isHudVisible`**

每个新播放会话都应该从"可见"开始，而不是继承上一个视频结束时的显隐状态。当前（Task 3 之前就存在，本任务不涉及 Task 3 的改动）`startPlayback()` 开头几行：

```kotlin
    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
```

改成：

```kotlin
    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
        isHudVisible = true
```

- [ ] **Step 3: `ImmersiveScene.kt` 加 `delay` import**

当前（Task 4 完成后）第 18-19 行：

```kotlin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

改成：

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

- [ ] **Step 4: 新增 5 秒自动隐藏的 `LaunchedEffect`**

当前（Task 4 完成后）第 68-74 行左右：

```kotlin
    // Auto-return to the main window once playback reaches the end, same path as the HUD's
    // "返回主窗口" button.
    LaunchedEffect(viewModel.returnToMainWindowRequested) {
        if (viewModel.returnToMainWindowRequested) {
            returnToMainWindow()
        }
    }
```

在这个 `LaunchedEffect` 后面（下一个 `LaunchedEffect(Unit)` 每帧循环之前）插入一个新的 `LaunchedEffect`：

```kotlin
    // Auto-return to the main window once playback reaches the end, same path as the HUD's
    // "返回主窗口" button.
    LaunchedEffect(viewModel.returnToMainWindowRequested) {
        if (viewModel.returnToMainWindowRequested) {
            returnToMainWindow()
        }
    }

    // Auto-hide the HUD once, 5s after the first frame renders (not 5s after startPlayback() - a
    // slow-loading video would otherwise burn part or all of that window under the loading overlay).
    // hasFirstFrameRendered only goes false->true once per session, so this LaunchedEffect body only
    // runs once per playback - it does not re-fire if the user pinches the HUD back on afterward.
    LaunchedEffect(viewModel.manager.hasFirstFrameRendered) {
        if (viewModel.manager.hasFirstFrameRendered) {
            delay(5000)
            viewModel.hideHud()
        }
    }
```

- [ ] **Step 5: 把 HUD 的 `.enabled` 写入点从 `update` 搬到每帧循环**

当前（Task 4 完成后）每帧循环末尾部分：

```kotlin
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()
            }
        }
    }
```

改成：

```kotlin
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.isHudVisible
            }
        }
    }
```

然后删掉 `update` 块里原来那一行。当前 `update` 块：

```kotlin
            update = { _, attachments ->
                // Event-driven, not per-frame - this re-runs whenever showLoadingOverlay's own
                // reads (manager.state / hasFirstFrameRendered) change, which is exactly when
                // these two visibility flags need to flip. See the LaunchedEffect above for the
                // genuinely-continuous per-frame work.
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay
            },
```

改成（删掉 HUD 那一行，注释也要改，因为现在只剩一个 flag 在这里写）：

```kotlin
            update = { _, attachments ->
                // Event-driven, not per-frame - re-runs whenever showLoadingOverlay's own reads
                // (manager.state / hasFirstFrameRendered) change. HUD's `.enabled` used to be written
                // here too, but it now has a second condition (isHudVisible) that must be re-checked
                // every frame (5s auto-hide timer, pinch toggle) - moved to the per-frame
                // LaunchedEffect loop above so there's exactly one writer for that property.
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
            },
```

- [ ] **Step 6: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 真机验证计时行为**

模拟器没有真实的沉浸交互验证价值有限（HUD attachment panel 在沉浸 Stage 里的可见性变化，靠截图时间点很难卡准），改用 `adb logcat` 或临时诊断文件确认计时器行为，参照 `AGENTS.md`"这次改用直接写文件...绕开这个问题"那条经验：

临时在 `PlaybackViewModel.hideHud()` 里加一行诊断（验证完删除）：

```kotlin
    fun hideHud() {
        isHudVisible = false
        runCatching {
            java.io.File(context.getExternalFilesDir(null), "hud_hide_debug.txt").appendText(
                "hideHud invoked t=${System.currentTimeMillis()}\n",
            )
        }
    }
```

装机播放一个视频，记录开始播放的时间戳，5 秒后 `pico-cli files pull` 这个文件确认 `hideHud invoked` 的时间戳确实在首帧渲染后约 5 秒（不是从点击"开始播放"那一刻算起）。确认后删掉这段临时诊断代码，重新编译验证 `BUILD SUCCESSFUL`。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt
git commit -m "Auto-hide the HUD once 5s after first frame, single .enabled writer"
```

---

### Task 6: 捏合判定纯函数 + 单元测试

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceplayer/ecs/PinchDetector.kt`
- Test: `app/src/test/java/tech/illusion/spaceplayer/ecs/PinchDetectorTest.kt`

**Interfaces:**
- Produces: `data class PinchResult(val isPinching: Boolean, val justEngaged: Boolean)`，`object PinchDetector { const val ENGAGE_DISTANCE_METERS: Float; const val RELEASE_DISTANCE_METERS: Float; fun update(distanceMeters: Float, wasPinching: Boolean): PinchResult }`。Task 8 会用这个 `update()` 函数。
- 不依赖任何 SDK 类型（纯 `Float`/`Boolean` 输入输出），可以在纯 JVM 单测里跑，不需要 Robolectric——和项目里 `AspectRatioFormatDetector`（`app/src/main/java/tech/illusion/spaceplayer/library/AspectRatioFormatDetector.kt`）同一类写法。

**背景**：SDK 的 `tracking` 包只提供裸的关节坐标，没有内置捏合识别 API,必须自己用两点距离判定。单阈值在临界距离附近会抖动（同工作区 StoryPico 的 `PlayerSpace.kt` 用的就是单阈值 + 上升沿,没有滞回区间）,这里用带滞回的双阈值：25mm 以内判定为捏合、40mm 以外判定为松开、中间维持上一帧状态。

先写测试,确认失败,再写实现。

- [ ] **Step 1: 写失败的单元测试**

创建 `app/src/test/java/tech/illusion/spaceplayer/ecs/PinchDetectorTest.kt`：

```kotlin
package tech.illusion.spaceplayer.ecs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchDetectorTest {

    @Test
    fun `distance well inside engage threshold from not-pinching enters pinch with rising edge`() {
        val result = PinchDetector.update(distanceMeters = 0.010f, wasPinching = false)
        assertTrue(result.isPinching)
        assertTrue(result.justEngaged)
    }

    @Test
    fun `distance well inside engage threshold while already pinching has no rising edge`() {
        val result = PinchDetector.update(distanceMeters = 0.010f, wasPinching = true)
        assertTrue(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance well outside release threshold releases the pinch`() {
        val result = PinchDetector.update(distanceMeters = 0.060f, wasPinching = true)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance well outside release threshold while already not pinching stays not pinching`() {
        val result = PinchDetector.update(distanceMeters = 0.060f, wasPinching = false)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance in the hysteresis band holds the previous not-pinching state`() {
        val result = PinchDetector.update(distanceMeters = 0.032f, wasPinching = false)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance in the hysteresis band holds the previous pinching state`() {
        val result = PinchDetector.update(distanceMeters = 0.032f, wasPinching = true)
        assertTrue(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance exactly at the engage threshold does not engage (strict less-than)`() {
        val result = PinchDetector.update(
            distanceMeters = PinchDetector.ENGAGE_DISTANCE_METERS,
            wasPinching = false,
        )
        assertFalse(result.isPinching)
    }

    @Test
    fun `distance exactly at the release threshold does not release (strict greater-than)`() {
        val result = PinchDetector.update(
            distanceMeters = PinchDetector.RELEASE_DISTANCE_METERS,
            wasPinching = true,
        )
        assertTrue(result.isPinching)
    }

    @Test
    fun `two consecutive frames inside engage distance only report one rising edge`() {
        val first = PinchDetector.update(distanceMeters = 0.010f, wasPinching = false)
        val second = PinchDetector.update(distanceMeters = 0.010f, wasPinching = first.isPinching)
        assertTrue(first.justEngaged)
        assertFalse(second.justEngaged)
    }

    @Test
    fun `thresholds are ordered engage below release`() {
        assertTrue(PinchDetector.ENGAGE_DISTANCE_METERS < PinchDetector.RELEASE_DISTANCE_METERS)
    }
}
```

- [ ] **Step 2: 跑测试确认失败（`PinchDetector` 还不存在，编译就会失败）**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.ecs.PinchDetectorTest"`
Expected: FAIL（编译错误，`Unresolved reference: PinchDetector`）

- [ ] **Step 3: 实现 `PinchDetector`**

创建 `app/src/main/java/tech/illusion/spaceplayer/ecs/PinchDetector.kt`：

```kotlin
package tech.illusion.spaceplayer.ecs

/**
 * @param isPinching Current pinch state after this update, with hysteresis applied.
 * @param justEngaged True only on the frame the state transitions from not-pinching to pinching -
 *   this is the rising edge that should drive a one-shot action (Task 8: toggling HUD visibility),
 *   not [isPinching] itself, which stays true for every frame the fingers remain close together.
 */
data class PinchResult(val isPinching: Boolean, val justEngaged: Boolean)

/**
 * Pinch detection from raw thumb-tip/index-tip distance - the SDK's `tracking` package exposes
 * only joint positions, no built-in gesture recognition (confirmed against
 * spatial-sdk_interaction_spatial-hand-pose.md and the com.pico.spatial.tracking.hand API
 * reference: only detectSpatialTapGesture()-style Compose pointer-input gestures exist there, not a
 * free-space "is the user pinching" signal).
 *
 * Uses two thresholds instead of one to avoid oscillating near a single cutoff: engage below
 * [ENGAGE_DISTANCE_METERS], release above [RELEASE_DISTANCE_METERS], hold the previous state
 * anywhere in between. The sibling StoryPico project's PlayerSpace.kt hand-menu gesture uses a
 * single 0.05m threshold with only a rising-edge check - fine for a low-frequency menu toggle, but
 * this detector drives a persistently-visible HUD, so the wider margin against jitter matters more
 * here.
 */
object PinchDetector {
    const val ENGAGE_DISTANCE_METERS = 0.025f
    const val RELEASE_DISTANCE_METERS = 0.040f

    fun update(distanceMeters: Float, wasPinching: Boolean): PinchResult {
        val isPinching = when {
            distanceMeters < ENGAGE_DISTANCE_METERS -> true
            distanceMeters > RELEASE_DISTANCE_METERS -> false
            else -> wasPinching
        }
        return PinchResult(isPinching = isPinching, justEngaged = isPinching && !wasPinching)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceplayer.ecs.PinchDetectorTest"`
Expected: PASS，10/10 测试通过

- [ ] **Step 5: 全量编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ecs/PinchDetector.kt \
        app/src/test/java/tech/illusion/spaceplayer/ecs/PinchDetectorTest.kt
git commit -m "Add hysteresis-based pinch detector with unit tests"
```

---

### Task 7: 右手拇指尖/食指尖跟手小球 + `HandTrackingProvider` 生命周期

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt`
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt`（Task 5 完成后的状态）
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`（Task 5 完成后的状态）

**Interfaces:**
- Produces: `PlaybackEntityAssembler.assembleHandMarkerEntity(entity: Entity, radiusMeters: Float)`；`PlaybackViewModel.thumbTipEntity: Entity`、`PlaybackViewModel.indexTipEntity: Entity`、`PlaybackViewModel.handTrackingProvider: HandTrackingProvider?`（只读）。Task 8 会读这三个字段。
- Consumes: 无跨任务依赖（`HandTrackingProvider`/`Entity`/`ModelComponent`/`MeshResource`/`UnlitMaterial`/`Color4` 都是 SDK 类型）。

**背景**：`HandTrackingProvider` 是和已验证过的 `HMDTrackingProvider` 同一个 `tracking` artifact 下的姊妹 API,生命周期/线程注意事项完全对称——每个播放会话新建实例、`start()` 走 `backgroundScope.launch`（阻塞原生调用,不能在主线程调）、`exitImmersive()`/`onCleared()` 里 `stop()` 并置空。小球用 `MeshResource.createSphere` + `UnlitMaterial`（纯色,不需要贴图),不需要新增依赖或权限。

- [ ] **Step 1: `PlaybackEntityAssembler` 新增小球装配函数**

当前 `PlaybackEntityAssembler.kt` 顶部 import（第 1-16 行）：

```kotlin
package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector3

object PlaybackEntityAssembler {
```

改成（加一个 `Color4` import）：

```kotlin
package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3

// Matches SpacePlayerAccent (#E63946, see ui/library/SpacePlayerPalette.kt) - written as a plain
// Color4 literal rather than importing that Compose Color into this ECS-only file. Color4's
// components are 0..1 floats: 0xE6/255, 0x39/255, 0x46/255.
private val HAND_MARKER_COLOR = Color4(0.902f, 0.224f, 0.275f, 1f)

object PlaybackEntityAssembler {
```

在 `object PlaybackEntityAssembler { ... }` 内部末尾（`assembleEnvironmentEntity` 函数后面,右花括号之前)追加新函数。当前文件末尾：

```kotlin
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
}
```

改成（在 `assembleEnvironmentEntity` 后面加新函数,`}` 移到新函数后面）：

```kotlin
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

    /**
     * A small solid-color sphere used as a visual marker for a tracked fingertip position (see
     * PlaybackViewModel.thumbTipEntity/indexTipEntity) - just a ModelComponent, no VideoPlayerComponent,
     * since it has nothing to do with video playback. Position is set every frame by the caller from
     * live hand-tracking data, not here.
     */
    fun assembleHandMarkerEntity(entity: Entity, radiusMeters: Float) {
        val mesh = MeshResource.createSphere(radiusMeters)
        check(mesh.valid) { "createSphere returned an invalid mesh" }
        val material = UnlitMaterial.create().apply {
            setBaseColor(HAND_MARKER_COLOR)
        }
        entity.components.set(ModelComponent(mesh, material))
    }
}
```

- [ ] **Step 2: `PlaybackViewModel` 加常量、字段、装配方法**

当前（Task 5 完成后）`PlaybackViewModel.kt` 顶部 import 里已有：

```kotlin
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
```

改成（加一行 `HandTrackingProvider` import,紧跟在它后面按字母序排列会插到它前面——`hand` < `hmd`,所以实际顺序是）：

```kotlin
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
```

当前第 34-39 行的常量组：

```kotlin
const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f
const val SPHERE_RADIUS_METERS = 10f
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f
const val ENVIRONMENT_SKYBOX_RADIUS_METERS = 20f
```

改成（追加一个常量）：

```kotlin
const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f
const val SPHERE_RADIUS_METERS = 10f
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f
const val ENVIRONMENT_SKYBOX_RADIUS_METERS = 20f
const val HAND_MARKER_RADIUS_METERS = 0.008f
```

当前（Task 5 完成后）`hmdTrackingProvider` 字段和 `backgroundScope` 声明：

```kotlin
    var hmdTrackingProvider: HMDTrackingProvider? = null
        private set

    // HMDTrackingProvider.start() is a synchronous native call (blocks on the SDK's own
    // spatial::jobs::JobWaiter::wait) - confirmed via a real ANR (`dumpsys dropbox --print`,
    // "Input dispatching timed out ... Waited 5000ms", stack ending in
    // PlaybackViewModel.startPlayback -> HMDTrackingProvider.start -> nativeStartHMDTrackingDataSource)
    // when it was called directly from the "开始播放" click handler on the main thread. Neither the
    // SDK docs nor the decompiled 0.13.3 source (BaseTrackingDataProvider.start()) declare a
    // main-thread requirement, so dispatching the call itself to a background thread is safe and
    // keeps the click handler from blocking input dispatch long enough to trip the ANR watchdog.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

改成（加 `handTrackingProvider` 字段和两个跟手小球实体,复用同一个 `backgroundScope`,同一条 ANR 风险适用于 `HandTrackingProvider.start()`——`tracking-0.13.3-sources.jar` 里 `BaseTrackingDataProvider.start()` 是两者共用的基类,同一条阻塞路径）：

```kotlin
    var hmdTrackingProvider: HMDTrackingProvider? = null
        private set

    // Same ANR risk as HMDTrackingProvider.start() below - both go through the same
    // BaseTrackingDataProvider.start() -> dataSource.addDataCallback() blocking path (confirmed in
    // tracking-0.13.3-sources.jar), so this must also run on backgroundScope, never the main thread.
    var handTrackingProvider: HandTrackingProvider? = null
        private set

    val thumbTipEntity = Entity()
    val indexTipEntity = Entity()
    private var handMarkersAssembled = false

    // HMDTrackingProvider.start() is a synchronous native call (blocks on the SDK's own
    // spatial::jobs::JobWaiter::wait) - confirmed via a real ANR (`dumpsys dropbox --print`,
    // "Input dispatching timed out ... Waited 5000ms", stack ending in
    // PlaybackViewModel.startPlayback -> HMDTrackingProvider.start -> nativeStartHMDTrackingDataSource)
    // when it was called directly from the "开始播放" click handler on the main thread. Neither the
    // SDK docs nor the decompiled 0.13.3 source (BaseTrackingDataProvider.start()) declare a
    // main-thread requirement, so dispatching the call itself to a background thread is safe and
    // keeps the click handler from blocking input dispatch long enough to trip the ANR watchdog.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun assembleHandMarkersIfNeeded() {
        if (handMarkersAssembled) return
        PlaybackEntityAssembler.assembleHandMarkerEntity(thumbTipEntity, HAND_MARKER_RADIUS_METERS)
        PlaybackEntityAssembler.assembleHandMarkerEntity(indexTipEntity, HAND_MARKER_RADIUS_METERS)
        // Hidden until the first frame of real hand-tracking data arrives - see the per-frame loop
        // in ImmersiveScene.kt (Task 8) that flips these back on/off based on tracking availability.
        thumbTipEntity.enabled = false
        indexTipEntity.enabled = false
        handMarkersAssembled = true
    }
```

- [ ] **Step 3: `startPlayback()` 装配小球 + 启动 `HandTrackingProvider`**

当前（Task 5 完成后）`startPlayback()`：

```kotlin
    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
        isHudVisible = true
        // Assign the provider immediately (ImmersiveScene's per-frame loop reads it null-safely and
        // just sees a default zero pose until start() actually finishes) - only the blocking start()
        // call itself is pushed off the main thread, so the click handler returns right away.
        val provider = HMDTrackingProvider()
        hmdTrackingProvider = provider
        backgroundScope.launch { provider.start() }
        currentStereoMode.value = item.stereoMode
```

改成：

```kotlin
    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
        isHudVisible = true
        assembleHandMarkersIfNeeded()
        // Assign the provider immediately (ImmersiveScene's per-frame loop reads it null-safely and
        // just sees a default zero pose until start() actually finishes) - only the blocking start()
        // call itself is pushed off the main thread, so the click handler returns right away.
        val provider = HMDTrackingProvider()
        hmdTrackingProvider = provider
        backgroundScope.launch { provider.start() }
        val handProvider = HandTrackingProvider()
        handTrackingProvider = handProvider
        backgroundScope.launch { handProvider.start() }
        currentStereoMode.value = item.stereoMode
```

- [ ] **Step 4: `exitImmersive()`/`onCleared()` 停止 `HandTrackingProvider`**

当前 `exitImmersive()`：

```kotlin
    fun exitImmersive() {
        val item = currentItem
        if (item != null && currentProjection.value == Projection.FLAT) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        isImmersive.value = false
        returnToMainWindowRequested = false
    }
```

改成：

```kotlin
    fun exitImmersive() {
        val item = currentItem
        if (item != null && currentProjection.value == Projection.FLAT) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        handTrackingProvider?.stop()
        handTrackingProvider = null
        isImmersive.value = false
        returnToMainWindowRequested = false
    }
```

当前 `onCleared()`：

```kotlin
    fun onCleared() {
        manager.reset()
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        backgroundScope.cancel()
    }
```

改成：

```kotlin
    fun onCleared() {
        manager.reset()
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        handTrackingProvider?.stop()
        handTrackingProvider = null
        backgroundScope.cancel()
    }
```

- [ ] **Step 5: `ImmersiveScene.kt` 把两个小球加进 content**

当前（Task 5 完成后）`initial` 块开头：

```kotlin
            initial = { content, attachments ->
                spatialAttachments = attachments
                content.addEntity(viewModel.screenEntity)
                content.addEntity(viewModel.sphereEntity)
                content.addEntity(viewModel.hemisphereEntity)
                content.addEntity(viewModel.cinemaEnvironmentEntity)
                content.addEntity(viewModel.starrySkyEnvironmentEntity)
                content.addEntity(viewModel.seasideEnvironmentEntity)
```

改成：

```kotlin
            initial = { content, attachments ->
                spatialAttachments = attachments
                content.addEntity(viewModel.screenEntity)
                content.addEntity(viewModel.sphereEntity)
                content.addEntity(viewModel.hemisphereEntity)
                content.addEntity(viewModel.cinemaEnvironmentEntity)
                content.addEntity(viewModel.starrySkyEnvironmentEntity)
                content.addEntity(viewModel.seasideEnvironmentEntity)
                // No parent, positioned fresh every frame from live hand-tracking data (Task 8) -
                // same reasoning as the HUD/loading panel not being parented to a video entity.
                content.addEntity(viewModel.thumbTipEntity)
                content.addEntity(viewModel.indexTipEntity)
```

- [ ] **Step 6: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

（这一步小球还不会跟手——没有任何代码往 `thumbTipEntity`/`indexTipEntity` 写入位置,它们创建后一直是 `enabled = false`。跟手逻辑在 Task 8。这一步只验证编译通过、`HandTrackingProvider` 生命周期不崩溃即可,可以装机启动看一次 logcat 确认没有 `FATAL`/ANR。）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ecs/PlaybackEntityAssembler.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/PlaybackViewModel.kt \
        app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt
git commit -m "Add right-hand fingertip marker entities and HandTrackingProvider lifecycle"
```

---

### Task 8: 每帧更新跟手小球位置 + 捏合切换 HUD 显隐

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt`（Task 7 完成后的状态）

**Interfaces:**
- Consumes: `PinchDetector.update()`（Task 6）、`PlaybackViewModel.toggleHudVisibility()`（Task 5）、`PlaybackViewModel.handTrackingProvider`/`thumbTipEntity`/`indexTipEntity`（Task 7）。
- 本任务不产出新接口,是这条链路的最后一环。

- [ ] **Step 1: 加 imports**

当前（Task 7 完成后）`ImmersiveScene.kt` 顶部 import 部分：

```kotlin
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.MAIN_WINDOW_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.ecs.SubtitleFollowComponent
import tech.illusion.spaceplayer.ecs.applySubtitleFollow
```

改成：

```kotlin
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.MAIN_WINDOW_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.ecs.PinchDetector
import tech.illusion.spaceplayer.ecs.SubtitleFollowComponent
import tech.illusion.spaceplayer.ecs.applySubtitleFollow
```

- [ ] **Step 2: 加 `wasPinching` 组合内状态**

当前（Task 4/5 完成后）函数体开头：

```kotlin
    val subtitleFollow = remember { SubtitleFollowComponent() }
    var spatialAttachments by remember { mutableStateOf<SpatialViewAttachments?>(null) }
```

改成：

```kotlin
    val subtitleFollow = remember { SubtitleFollowComponent() }
    var spatialAttachments by remember { mutableStateOf<SpatialViewAttachments?>(null) }
    // Plain Compose state, not an ECS Component - same reasoning as subtitleFollow above: the
    // native ECS layer silently rejects custom Component subtypes.
    var wasPinching by remember { mutableStateOf(false) }
```

- [ ] **Step 3: 每帧循环里更新小球位置 + 判定捏合**

当前（Task 5 完成后）每帧循环：

```kotlin
                viewModel.refreshPlaybackFrame()

                val attachments = spatialAttachments ?: return@withFrameNanos
                val subtitleEntity = attachments.entity(SUBTITLE_ATTACHMENT_ID)
                val hmdPose = viewModel.hmdTrackingProvider?.latestData?.hmdPose
                if (subtitleEntity != null && hmdPose != null) {
                    applySubtitleFollow(subtitleEntity, subtitleFollow, hmdPose, deltaTime)
                }
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.isHudVisible
```

改成（在 `refreshPlaybackFrame()` 之后、`attachments` 那行之前插入手部追踪 + 捏合判定块——这段不依赖 `attachments`,可以在它之前跑）：

```kotlin
                viewModel.refreshPlaybackFrame()

                // Right-hand fingertip markers + pinch detection. Runs before the `attachments`
                // null-check below because it only touches viewModel.thumbTipEntity/indexTipEntity
                // (added directly to `content`, not attachment panels) and viewModel state - none of
                // it needs `attachments` to be non-null.
                val handPose = viewModel.handTrackingProvider?.latestData?.right
                if (handPose != null) {
                    val thumbTip = handPose.joint(HandJoint.Index.THUMB_TIP)
                    val indexTip = handPose.joint(HandJoint.Index.INDEX_TIP)
                    viewModel.thumbTipEntity.components[TransformComponent::class.java]
                        ?.setPosition(thumbTip.position)
                    viewModel.indexTipEntity.components[TransformComponent::class.java]
                        ?.setPosition(indexTip.position)
                    viewModel.thumbTipEntity.enabled = true
                    viewModel.indexTipEntity.enabled = true

                    val distance = Vector3.distance(thumbTip.position, indexTip.position)
                    val pinch = PinchDetector.update(distance, wasPinching)
                    wasPinching = pinch.isPinching
                    if (pinch.justEngaged) {
                        viewModel.toggleHudVisibility()
                    }
                } else {
                    // Right hand not currently tracked (out of frame, tracking lost, or
                    // HandTrackingProvider.start() hasn't completed on the background thread yet) -
                    // hide the markers rather than leaving them at a stale position, and reset
                    // wasPinching so a stale pinch state can't produce a spurious rising edge the
                    // instant tracking resumes.
                    viewModel.thumbTipEntity.enabled = false
                    viewModel.indexTipEntity.enabled = false
                    wasPinching = false
                }

                val attachments = spatialAttachments ?: return@withFrameNanos
                val subtitleEntity = attachments.entity(SUBTITLE_ATTACHMENT_ID)
                val hmdPose = viewModel.hmdTrackingProvider?.latestData?.hmdPose
                if (subtitleEntity != null && hmdPose != null) {
                    applySubtitleFollow(subtitleEntity, subtitleFollow, hmdPose, deltaTime)
                }
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.isHudVisible
```

- [ ] **Step 4: 编译验证**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/ImmersiveScene.kt
git commit -m "Drive fingertip markers from live hand tracking and wire pinch to HUD visibility"
```

---

### Task 9: 全量验证 + AGENTS.md 记录

**Files:**
- Modify: `AGENTS.md`（追加一节记录这次改动和验证状态）

**Interfaces:** 无。

- [ ] **Step 1: 全量编译 + 单测**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew clean assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL,`PinchDetectorTest` 10 个用例全部通过,加上项目原有的单测总数（改动前是 48 个,这次新增 10 个,预期 58/58）。

- [ ] **Step 2: 装机（模拟器 + 真机都装,分别验证各自能验证的部分）**

```bash
pico-cli device list
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device <真机 device id>
pico-cli app launch tech.illusion.spaceplayer --device emulator-5554
pico-cli app launch tech.illusion.spaceplayer --device <真机 device id>
```

模拟器验证（可自证）：
- 侧边栏三个/两个分类高度加大、"历史"不再显示（截图对比 `artifacts/baseline-sidebar-2026-08-14.png`）。
- 选中一个非默认分类 → 进入沉浸播放 → 退出 → 侧边栏选中态保持。
- `pico-cli shell pidof tech.illusion.spaceplayer` 确认进程存活,`pico-cli app logcat --level E` 确认无 `FATAL`。

真机验证（需要用户戴设备,如实告知这部分我自己验证不了）：
- HUD 面板倾斜方向是否符合预期（Task 4 Step 4 已经跑过一次,这里是最终回归）。
- HUD 首次显示 5 秒后是否自动隐藏。
- 右手拇指/食指尖是否有可见小球、是否跟手。
- 捏合是否能切换 HUD 显隐,连续捏合几次确认没有抖动（滞回区间是否生效）。
- 播放到自然结束、连续切换几个视频,确认这批改动没有引入新的 ANR/崩溃（`dumpsys dropbox --print` 走一遍,参照之前排查崩溃时的方法）。

- [ ] **Step 3: 在 `AGENTS.md` 追加记录**

在 `AGENTS.md` 文件末尾追加一节,格式参照文件里已有的其它按日期分节的记录（标题、改动内容、验证状态、已知限制),内容基于本次改动的设计文档
（`docs/superpowers/specs/2026-08-14-library-ux-and-hand-interaction-design.md`）和 Step 2 的真实验证结果——**具体文字要等 Step 2 真机验证结果出来之后再写,如实反映哪些验证过、哪些还没有,不要在验证之前就先写"已确认"**。

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "Record sidebar UX and hand-interaction HUD changes in AGENTS.md"
```

