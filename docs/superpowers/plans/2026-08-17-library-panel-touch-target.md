# Library Panel Touch-Target & Spacing Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the sidebar and bottom-bar's tap-target and spacing problems in SpacePlayer's main library panel: several elements have padding-before-clickable dead zones that make their real hit area smaller than they look, several are simply too small (SDK "Small" size tiers), and there is no consistent spacing model between adjacent clickable elements.

**Architecture:** Sidebar: replace the per-item `.padding(bottom = 4.dp)` (applied before `.clickable()`, so it's dead space carved out of the item's own hit region) with a real sibling `Spacer` between items, so the full item height stays clickable and the gap is genuine space no item's hit box can absorb. Bottom bar: bump undersized elements to existing SDK size tiers (`ChipsDefaults.Regular`, `ButtonDefaults.Max`), give the `FormatMenuButton` pill an explicit minimum height, give the subtitle-status text the same pill chrome as the format buttons via a new small `PillButton` composable, and replace all manual per-child `.padding(end = Ndp)` spacing with a single `Arrangement.spacedBy(16.dp)` on the bar's own `Row` (spacing that lives at the parent level can never be absorbed into or excluded from a child's own hit region).

**Tech Stack:** Kotlin, Jetpack Compose, PICO SpatialUI (`com.pico.spatial.ui.design.*`).

## Global Constraints

- Every task must build clean: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- No PICO-specific numeric minimum touch-target or spacing guideline exists anywhere (checked `AGENTS.md`, every bundled `pico-spatial-agentic-tools` skill reference file, and PICO's own `spatial-design_pico-design-guidelines.md`). Every size value in this plan comes from the SpatialUI SDK's own existing component-default tiers (`ButtonDefaults.Max`/`Regular`, `ChipsDefaults.Regular`/`Small`) rather than an invented number.
- Scope is exactly: sidebar `SideNavigationItem`s + the bottom bar (`LibraryBottomBar.kt`, `FormatMenuButton.kt`). The video grid (`VideoGridCard`/`LazyVerticalGrid`) and the sidebar's "其它·选择文件" import trigger are explicitly unchanged — the former was excluded by the user, the latter already has a correct click region (chrome modifiers already precede its inner padding).
- This is a pure layout/sizing change with no new business logic - no new JVM-testable behavior is expected. Verification is `assembleDebug`/`testDebugUnitTest` passing (confirms nothing broke) plus on-device manual tap-testing, since Compose modifier-chain hit-test correctness for a VR pointer is not exercised by a JVM unit test, and this project's device automation (`adb`/`uiautomator`) is documented as unreliable against this app's spatial windows.

---

### Task 1: Sidebar nav item click-region fix + real inter-item gap

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt`

**Interfaces:** None - this task only touches file-local constants and one `forEach` loop's structure inside `MainLibraryScreen`. Nothing outside this file depends on it.

- [ ] **Step 1: Add the new `SIDEBAR_ITEM_GAP` constant**

Current, at `MainLibraryScreen.kt` lines 100-105:

```kotlin
// SideNavigationItem's natural height is only the SDK default (~48dp: a 32dp icon box plus 8dp top/
// bottom content padding) - explicitly taller per user request. The SDK applies the caller's
// modifier BEFORE its own contentPadding (confirmed by decompiling design-0.13.3-sources.jar's
// SideNavigation.kt), so a plain .height() here sets the outer row height cleanly without being
// squeezed by the internal padding.
private val NAV_ITEM_HEIGHT = 64.dp
```

Change to:

```kotlin
// SideNavigationItem's natural height is only the SDK default (~48dp: a 32dp icon box plus 8dp top/
// bottom content padding) - explicitly taller per user request. The SDK applies the caller's
// modifier BEFORE its own contentPadding (confirmed by decompiling design-0.13.3-sources.jar's
// SideNavigation.kt), so a plain .height() here sets the outer row height cleanly without being
// squeezed by the internal padding.
private val NAV_ITEM_HEIGHT = 64.dp

// Real gap between sidebar items, inserted as a sibling Spacer rather than padding on either
// item's own modifier - see the comment at its usage site for why padding-before-clickable would
// make this dead space carved out of an item's own tap area instead of a real gap between two
// full-sized ones.
private val SIDEBAR_ITEM_GAP = 8.dp
```

- [ ] **Step 2: Fix the sidebar item loop**

Current, at `MainLibraryScreen.kt` (inside the `SideNavigation { ... }` trailing lambda):

```kotlin
                        // HISTORY is intentionally excluded from the sidebar per user request, but the
                        // enum value itself is kept (LibraryViewModel.visibleItems()/iconRes()'s
                        // exhaustive `when` branches still need it to compile) - restoring the entry
                        // point later is a one-line revert of this filter.
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT && it != LibraryCategory.HISTORY }.forEach { category ->
                            val categoryInteractionSource = remember(category) { MutableInteractionSource() }
                            SideNavigationItem(
                                selected = libraryViewModel.selectedCategory == category,
                                colors = SideNavigationItemDefaults.colors(
                                    unselectedContentColor = SpacePlayerTextSecondary,
                                    unselectedContainerColor = Color.Transparent,
                                    selectedContentColor = SpacePlayerTextPrimary,
                                    selectedContainerColor = SpacePlayerSurfaceSelected,
                                ),
                                modifier = Modifier
                                    .height(NAV_ITEM_HEIGHT)
                                    .padding(bottom = 4.dp)
                                    .clickable(
                                        interactionSource = categoryInteractionSource,
                                        indication = LocalIndication.current,
                                        onClick = { libraryViewModel.selectCategory(category) },
                                    )
                                    .controllerHapticFeedback(interactionSource = categoryInteractionSource),
                                leading = {
                                    Icon(
                                        painter = painterResource(id = category.iconRes()),
                                        contentDescription = null,
                                    )
                                },
                                content = {
                                    Text(
                                        text = category.label(),
                                        style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                    )
                                },
                            )
                        }
```

Change to:

```kotlin
                        // HISTORY is intentionally excluded from the sidebar per user request, but the
                        // enum value itself is kept (LibraryViewModel.visibleItems()/iconRes()'s
                        // exhaustive `when` branches still need it to compile) - restoring the entry
                        // point later is a one-line revert of this filter.
                        LibraryCategory.entries.filter { it != LibraryCategory.IMPORT && it != LibraryCategory.HISTORY }
                            .forEachIndexed { index, category ->
                                // A real sibling Spacer, not padding inside either item's own modifier -
                                // SideNavigationItem's clip()/drawBehind{} highlight paint sit outside
                                // the caller's modifier (confirmed by decompiling
                                // design-0.13.3-sources.jar), so a trailing padding on one item would
                                // shrink only what's clickable on that item, not what's painted, and
                                // would still leave the highlight rectangle extending into the "gap" -
                                // a Spacer can't be absorbed by either neighbor's hit box or paint area.
                                if (index > 0) {
                                    Spacer(modifier = Modifier.height(SIDEBAR_ITEM_GAP))
                                }
                                val categoryInteractionSource = remember(category) { MutableInteractionSource() }
                                SideNavigationItem(
                                    selected = libraryViewModel.selectedCategory == category,
                                    colors = SideNavigationItemDefaults.colors(
                                        unselectedContentColor = SpacePlayerTextSecondary,
                                        unselectedContainerColor = Color.Transparent,
                                        selectedContentColor = SpacePlayerTextPrimary,
                                        selectedContainerColor = SpacePlayerSurfaceSelected,
                                    ),
                                    modifier = Modifier
                                        .height(NAV_ITEM_HEIGHT)
                                        .clickable(
                                            interactionSource = categoryInteractionSource,
                                            indication = LocalIndication.current,
                                            onClick = { libraryViewModel.selectCategory(category) },
                                        )
                                        .controllerHapticFeedback(interactionSource = categoryInteractionSource),
                                    leading = {
                                        Icon(
                                            painter = painterResource(id = category.iconRes()),
                                            contentDescription = null,
                                        )
                                    },
                                    content = {
                                        Text(
                                            text = category.label(),
                                            style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                        )
                                    },
                                )
                            }
```

Note exactly one thing changed in `SideNavigationItem`'s own `modifier` chain: `.padding(bottom = 4.dp)` was deleted from between `.height(NAV_ITEM_HEIGHT)` and `.clickable(...)`. Everything else in that chain is unchanged.

- [ ] **Step 3: Bump `FOOTER_HEIGHT` for the taller bottom-bar elements Tasks 2-3 introduce**

Current, at `MainLibraryScreen.kt` lines 88-93:

```kotlin
// Shared height for the sidebar's "其它" action and the content column's bottom bar
// (environment selector + "开始播放"), so the two vertically center-align across the Row's two
// independent Columns the same way HEADER_ROW_HEIGHT does at the top. Applied to the wrapping Box
// at each call site; LibraryBottomBar's own Row just fillMaxSize()s to adopt whatever height its
// parent Box is given, rather than duplicating this constant into that file.
private val FOOTER_HEIGHT = 56.dp
```

Change to:

```kotlin
// Shared height for the sidebar's "其它" action and the content column's bottom bar
// (environment selector + "开始播放"), so the two vertically center-align across the Row's two
// independent Columns the same way HEADER_ROW_HEIGHT does at the top. Applied to the wrapping Box
// at each call site; LibraryBottomBar's own Row just fillMaxSize()s to adopt whatever height its
// parent Box is given, rather than duplicating this constant into that file. Bumped from 56.dp to
// give the bottom bar's enlarged elements (44.dp-min format/subtitle pills, 56.dp-min "开始播放")
// comfortable vertical room instead of nearly touching the box's edges - this also makes the
// sidebar's "其它" trigger taller, consistent with (not a regression of) this constant's own
// documented purpose of keeping the two vertically aligned.
private val FOOTER_HEIGHT = 64.dp
```

- [ ] **Step 4: Build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/MainLibraryScreen.kt
git commit -m "Fix sidebar nav item click-region dead zone, add real inter-item gap"
```

---

### Task 2: `FormatMenuButton` minimum height

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/FormatMenuButton.kt`

**Interfaces:**
- Produces: `FormatMenuButton`'s public signature is unchanged (no new/removed/renamed parameters) - only its internal minimum rendered height changes. Task 3 doesn't need to know anything new about this component; it just benefits from it now being taller.

- [ ] **Step 1: Add the import**

Current, at `FormatMenuButton.kt` lines 8-10:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
```

Change to:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
```

- [ ] **Step 2: Add the minimum-height modifier**

Current, at `FormatMenuButton.kt` lines 80-95:

```kotlin
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(shape)
                .spatialHoverEffect()
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = { expanded = true },
                )
                .controllerHapticFeedback(interactionSource = interactionSource)
                .border(1.dp, colors.borderColor, shape)
                .background(colors.containerColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
```

Change to:

```kotlin
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(shape)
                .spatialHoverEffect()
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = { expanded = true },
                )
                .controllerHapticFeedback(interactionSource = interactionSource)
                .border(1.dp, colors.borderColor, shape)
                .background(colors.containerColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
```

`.heightIn(min = 44.dp)` is placed first (before `.clip()`), so it grows the whole clip/border/background/clickable region to at least 44.dp while the label+caret content stays centered (`verticalAlignment = Alignment.CenterVertically` is already set on this `Row`) - it does not change the click-region-correctness of this component (its `clip`/`hover`/`clickable` were already ordered correctly before the inner padding; this task only makes the whole thing taller).

- [ ] **Step 3: Build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/FormatMenuButton.kt
git commit -m "Give FormatMenuButton an explicit 44.dp minimum height"
```

---

### Task 3: Bottom bar restructure - `PillButton`, real spacing, bigger chips/button

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryBottomBar.kt`

**Interfaces:**
- Consumes: `FormatMenuButton` (Task 2, signature unchanged), `FormatMenuButtonColors`/`FormatMenuButtonDefaults.libraryColors()` from `tech.illusion.spaceplayer.ui` (already exist, defined in `FormatMenuButton.kt`).
- Produces: a new private composable `PillButton(text: String, onClick: () -> Unit, contentColor: Color, colors: FormatMenuButtonColors = FormatMenuButtonDefaults.libraryColors(), modifier: Modifier = Modifier)`, private to this file - no other file calls it.

- [ ] **Step 1: Replace the whole file**

Current, full file (`LibraryBottomBar.kt`):

```kotlin
package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.FormatMenuButton
import tech.illusion.spaceplayer.ui.label
import tech.illusion.spaceplayer.ui.shortLabel

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onFormatChange: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
    onStartPlayback: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedItem?.projection == Projection.FLAT) {
            val envChipColors = ChipsDefaults.toggleableChipColors(
                contentColor = SpacePlayerTextPrimary,
                backgroundColor = SpacePlayerSurface,
                activeContentColor = SpacePlayerOnAccent,
                activeBackgroundColor = SpacePlayerAccent,
            )
            Environment.entries.forEach { env ->
                ToggleableChip(
                    label = { Text(env.label()) },
                    isToggleOn = env == selectedEnvironment,
                    onClick = { onSelectEnvironment(env) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(env.dotColor(), CircleShape),
                        )
                    },
                    colors = envChipColors,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        } else if (selectedItem != null) {
            Text(
                text = stringResource(R.string.playback_panorama_auto_immersive),
                color = SpacePlayerTextSecondary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 投影/立体格式各自封装成一个菜单按钮，始终显示（不再按 formatSource == DEFAULT 隐藏）——
        // 之前用 SegmentControl 内联展开选项时，选中任何一项都会把 formatSource 从 DEFAULT 改成
        // MANUAL_OVERRIDE，导致这一整块选项随之消失，用户反馈这是不需要的"选完就关闭修正模式"的
        // 副作用。菜单按钮只是显示当前值，选值不会让按钮本身消失，天然规避了这个问题。
        if (selectedItem != null) {
            FormatMenuButton(
                label = selectedItem.projection.label(),
                modifier = Modifier.padding(end = 8.dp),
            ) { collapse ->
                Projection.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.label()) },
                        onClick = {
                            onFormatChange(candidate, selectedItem.stereoMode)
                            collapse()
                        },
                    )
                }
            }
            FormatMenuButton(
                label = selectedItem.stereoMode.shortLabel(),
                modifier = Modifier.padding(end = 8.dp),
            ) { collapse ->
                StereoMode.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.shortLabel()) },
                        onClick = {
                            onFormatChange(selectedItem.projection, candidate)
                            collapse()
                        },
                    )
                }
            }
            val subtitleInteractionSource = remember { MutableInteractionSource() }
            Text(
                text = stringResource(
                    if (selectedItem.subtitleUri != null) {
                        R.string.subtitle_status_set
                    } else {
                        R.string.subtitle_status_unset
                    },
                ),
                color = if (selectedItem.subtitleUri != null) {
                    SpacePlayerAccent
                } else {
                    SpacePlayerTextSecondary
                },
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                modifier = Modifier
                    .padding(end = 20.dp)
                    .clickable(
                        interactionSource = subtitleInteractionSource,
                        indication = LocalIndication.current,
                        onClick = onPickSubtitle,
                    )
                    .controllerHapticFeedback(interactionSource = subtitleInteractionSource),
            )
        }

        Button(
            onClick = onStartPlayback,
            colors = ButtonDefaults.buttonColors(
                containerColor = SpacePlayerAccent,
                contentColor = SpacePlayerOnAccent,
            ),
        ) {
            Text(
                text = stringResource(R.string.library_start_playback),
                color = SpacePlayerOnAccent,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
        }
    }
}
```

Replace the whole file with:

```kotlin
package tech.illusion.spaceplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.FormatMenuButton
import tech.illusion.spaceplayer.ui.FormatMenuButtonColors
import tech.illusion.spaceplayer.ui.FormatMenuButtonDefaults
import tech.illusion.spaceplayer.ui.label
import tech.illusion.spaceplayer.ui.shortLabel

// Real, uniform gap between every clickable element in this bar, applied once at the Row level
// instead of as manual per-child trailing padding - a per-child .padding(end = Ndp) is only a
// real gap if it comes AFTER that child's own .clickable()/.toggleable() in its modifier chain;
// several elements here had it BEFORE (dead space excluded from that child's own hit region, not
// space protecting it from its neighbor - confirmed for ToggleableChip and the old subtitle Text
// by decompiling design-0.13.3-sources.jar). Arrangement.spacedBy lives at the parent Row, so it
// can never be absorbed into or excluded from any child's own hit-test region.
private val BAR_ITEM_GAP = 16.dp

@Composable
fun LibraryBottomBar(
    selectedItem: VideoItem?,
    selectedEnvironment: Environment,
    onSelectEnvironment: (Environment) -> Unit,
    onFormatChange: (Projection, StereoMode) -> Unit,
    onPickSubtitle: () -> Unit,
    onStartPlayback: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_ITEM_GAP),
    ) {
        if (selectedItem?.projection == Projection.FLAT) {
            val envChipColors = ChipsDefaults.toggleableChipColors(
                contentColor = SpacePlayerTextPrimary,
                backgroundColor = SpacePlayerSurface,
                activeContentColor = SpacePlayerOnAccent,
                activeBackgroundColor = SpacePlayerAccent,
            )
            Environment.entries.forEach { env ->
                ToggleableChip(
                    label = { Text(env.label()) },
                    isToggleOn = env == selectedEnvironment,
                    onClick = { onSelectEnvironment(env) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(env.dotColor(), CircleShape),
                        )
                    },
                    colors = envChipColors,
                    // Regular (40.dp), up from the default Small (32.dp) - the chip's own
                    // .toggleable() already wraps its full chipSize-tall box, so growing this
                    // tier grows the real tap target, not just the visual size.
                    chipSize = ChipsDefaults.Regular,
                )
            }
        } else if (selectedItem != null) {
            Text(
                text = stringResource(R.string.playback_panorama_auto_immersive),
                color = SpacePlayerTextSecondary,
                style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 投影/立体格式各自封装成一个菜单按钮，始终显示（不再按 formatSource == DEFAULT 隐藏）——
        // 之前用 SegmentControl 内联展开选项时，选中任何一项都会把 formatSource 从 DEFAULT 改成
        // MANUAL_OVERRIDE，导致这一整块选项随之消失，用户反馈这是不需要的"选完就关闭修正模式"的
        // 副作用。菜单按钮只是显示当前值，选值不会让按钮本身消失，天然规避了这个问题。
        if (selectedItem != null) {
            FormatMenuButton(label = selectedItem.projection.label()) { collapse ->
                Projection.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.label()) },
                        onClick = {
                            onFormatChange(candidate, selectedItem.stereoMode)
                            collapse()
                        },
                    )
                }
            }
            FormatMenuButton(label = selectedItem.stereoMode.shortLabel()) { collapse ->
                StereoMode.entries.forEach { candidate ->
                    MenuItem(
                        title = { Text(candidate.shortLabel()) },
                        onClick = {
                            onFormatChange(selectedItem.projection, candidate)
                            collapse()
                        },
                    )
                }
            }
            PillButton(
                text = stringResource(
                    if (selectedItem.subtitleUri != null) {
                        R.string.subtitle_status_set
                    } else {
                        R.string.subtitle_status_unset
                    },
                ),
                onClick = onPickSubtitle,
                contentColor = if (selectedItem.subtitleUri != null) {
                    SpacePlayerAccent
                } else {
                    SpacePlayerTextSecondary
                },
            )
        }

        Button(
            onClick = onStartPlayback,
            // Max (56.dp minHeight), up from the default Regular (48.dp) - the primary action in
            // this bar, now comparably-or-more prominent than the secondary controls beside it.
            size = ButtonDefaults.Max,
            colors = ButtonDefaults.buttonColors(
                containerColor = SpacePlayerAccent,
                contentColor = SpacePlayerOnAccent,
            ),
        ) {
            Text(
                text = stringResource(R.string.library_start_playback),
                color = SpacePlayerOnAccent,
                style = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp),
            )
        }
    }
}

/**
 * A plain clickable pill with the same visual chrome as [FormatMenuButton] (rounded shape, 1.dp
 * border, background fill, 44.dp minimum height) but a single direct [onClick] instead of a
 * caret-plus-[com.pico.spatial.ui.design.menu.Menu] - used for the subtitle-picker trigger, which
 * previously had no chrome or dedicated minimum tap-target size at all (a bare clickable [Text]
 * with zero padding inside its hit region).
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    contentColor: Color,
    colors: FormatMenuButtonColors = FormatMenuButtonDefaults.libraryColors(),
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .spatialHoverEffect()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interactionSource)
            .border(1.dp, colors.borderColor, shape)
            .background(colors.containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        )
    }
}
```

- [ ] **Step 2: Build verification**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tech/illusion/spaceplayer/ui/library/LibraryBottomBar.kt
git commit -m "Restructure bottom bar spacing and give the subtitle trigger proper pill chrome"
```

---

### Task 4: Full build/test + on-device verification + AGENTS.md record

**Files:**
- Modify: `AGENTS.md` (append a new dated section)

**Interfaces:** None.

- [ ] **Step 1: Full clean build and test**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew clean assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all unit tests passing (report the actual before/after total - this plan adds no new tests, so the total should be unchanged from before Task 1).

- [ ] **Step 2: Install and launch on the real device**

```bash
pico-cli device list
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device PB311XKGL4160087B
pico-cli app launch tech.illusion.spaceplayer --device PB311XKGL4160087B
sleep 6
pico-cli app logcat --lines 200 --level W --device PB311XKGL4160087B
```

Confirm no `FATAL`/`AndroidRuntime` errors and the app is running (`pico-cli app info tech.illusion.spaceplayer --device PB311XKGL4160087B --format json` shows `"running": true`).

- [ ] **Step 3: Manual on-device tap check**

This project's device automation (`adb`/`uiautomator`) is documented as unreliable against this app's spatial windows, and `adb screencap` does not work against this app's windows either (confirmed repeatedly earlier this session) - report this honestly rather than attempting to fake automated visual verification. State plainly in the report to the user which of the following remain unverified pending the user physically trying them on-device:
  1. Each sidebar item (资源库/下载) is comfortably tappable across its full visible height, including near its top/bottom edges, with a clearly visible gap to its neighbor.
  2. The environment chips, format/stereo-mode pills, and subtitle-status pill in the bottom bar (select a video first so the bar has content) are all comfortably tappable and visually separated with real gaps.
  3. "开始播放" is now visually more prominent (taller) than before.
  4. Tapping the subtitle pill still opens the file picker (`onPickSubtitle` wiring unchanged - this task did not touch that callback, only the visual chrome around it).

- [ ] **Step 4: Record in `AGENTS.md`**

Append a new dated section to `AGENTS.md` (follow the file's existing per-change dated-section convention - read the last few sections for tone/format before writing). Content must cover: what changed (sidebar dead-zone fix + real gap, `FormatMenuButton`/environment-chip/subtitle-pill/play-button size bumps, `Arrangement.spacedBy` replacing manual padding in the bottom bar), why (padding-before-clickable is a real, repeated Compose bug pattern found across multiple elements, not just "things were too small"), and the honest verification-status split from Step 3 (what's self-verified via build/logcat vs. what needs the user to physically try on-device).

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md
git commit -m "Record library panel touch-target/spacing redesign in AGENTS.md"
```
