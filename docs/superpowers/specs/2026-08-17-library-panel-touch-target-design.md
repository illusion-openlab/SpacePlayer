# Library Panel Touch-Target & Spacing Redesign

## Context

The user reported that the main library panel's sidebar navigation and bottom format-correction bar are hard to tap accurately, and asked for buttons to have real spacing between them. Investigation (see below) found this isn't primarily a "things are too small" problem — it's a mix of small SDK-default sizes and a specific, mechanical Compose bug pattern repeated across several elements: **padding placed before `.clickable()`/`.toggleable()` in a modifier chain is excluded from the element's own hit-test region**, so what looks like a gap between two elements is actually dead space carved out of one element's own tap area, not real space between two full-sized targets.

Scope: sidebar (`SideNavigation` items + the "其它·选择文件" import trigger) and the bottom bar (`LibraryBottomBar.kt`: environment chips, format/stereo menu pills, subtitle status, 开始播放 button) inside `MainLibraryScreen.kt`. The video grid (`VideoGridCard`/`LazyVerticalGrid`) is explicitly out of scope — it already uses `Arrangement.spacedBy(16.dp)` with no reported click issues.

No PICO-specific numeric minimum touch-target or spacing guideline exists anywhere checked (this project's `AGENTS.md`, every bundled `pico-spatial-agentic-tools` skill reference file, and PICO's own official `spatial-design_pico-design-guidelines.md`). Every size value below is drawn from the SpatialUI SDK's own existing component-default tiers (`ButtonDefaults`, `ChipsDefaults`) rather than an invented number, so the app stays inside sanctioned, already-tested size options.

## Root Causes (current state, verified against decompiled `design-0.13.3-sources.jar` and this project's source)

1. **Sidebar `SideNavigationItem`** (`MainLibraryScreen.kt`): caller modifier is `Modifier.height(NAV_ITEM_HEIGHT).padding(bottom = 4.dp).clickable(...).controllerHapticFeedback(...)`. Because `.padding(bottom = 4.dp)` precedes `.clickable()`, the clickable/ripple/haptics region is only ~60.dp of the nominal 64.dp box — the bottom 4.dp is unclickable dead space, not a gap to the next item. The SDK's own `clip(shape)`/`drawBehind{...}` (which paint the selected-state highlight) sit even further outside this modifier (spliced in before it via `then(modifier)`), so the highlight rectangle is NOT reduced by this padding — only the clickable region is.
2. **`LibraryBottomBar.kt`'s environment `ToggleableChip`s**: same pattern — `Modifier.padding(end = 8.dp)` passed as the chip's `modifier` param is applied before the SDK's internal `.toggleable()`, so it's dead space, not inter-chip gap. Chip height is the SDK default `ChipsDefaults.Small` = 32.dp (no explicit `chipSize` argument is passed).
3. **`LibraryBottomBar.kt`'s subtitle status `Text`**: `Modifier.padding(end = 20.dp).clickable(...).controllerHapticFeedback(...)` — the clickable region here has *zero* internal padding at all (nothing precedes `.clickable()` except the trailing `padding(end=...)`, which — being on the *end* side and before clickable — excludes itself from the hit box entirely). The tap target is the raw `bodyMedium 14sp` text glyph bounds: the smallest and least button-like target on the screen.
4. **`FormatMenuButton.kt`**: no explicit `.height()`/`.heightIn()` anywhere — its footprint is fully intrinsic (`bodyMedium 14sp` label + `▾` caret + `.padding(horizontal = 12.dp, vertical = 8.dp)`), landing at roughly 30.dp tall. Unlike the elements above, this component's `clip`/`hover`/`clickable` chain is already correctly ordered *before* its inner padding, so there's no dead-zone bug here — it's simply too short.
5. **No consistent spacing model**: `LibraryBottomBar`'s outer `Row` has no `Arrangement.spacedBy(...)`; every gap is a manual `.padding(end = Ndp)` on an individual child, several of which (per points 2–3) are excluded from that child's own hit region rather than protecting it.

## Design

### Sidebar navigation items

- **Fix the dead-zone bug, don't just add more padding.** Remove `.padding(bottom = 4.dp)` from each `SideNavigationItem`'s own modifier. Insert a real `Spacer(Modifier.height(8.dp))` between items in the `forEach` loop instead (skip after the last item — the existing `Spacer(Modifier.weight(1f))` already provides the gap to the "其它" trigger below).
  ```kotlin
  LibraryCategory.entries.filter { ... }.forEachIndexed { index, category ->
      if (index > 0) Spacer(modifier = Modifier.height(SIDEBAR_ITEM_GAP))
      SideNavigationItem(
          ...
          modifier = Modifier
              .height(NAV_ITEM_HEIGHT)
              .clickable(...)
              .controllerHapticFeedback(...),
          ...
      )
  }
  ```
  This makes the item's `clip`/`drawBehind`-painted highlight and its `.clickable()` region both exactly `NAV_ITEM_HEIGHT` (64.dp, unchanged) — no dead zone — while the `Spacer` provides a real, click-immune 8.dp gap that the highlight rectangle cannot bleed into (a `Spacer` is a separate sibling, not padding inside either item's own bounds).
- `NAV_ITEM_HEIGHT` stays `64.dp` — already above every SDK size tier, no complaint was specifically about its size, only about the dead-zone bug reducing the *real* tappable height below the nominal one.
- New constant: `SIDEBAR_ITEM_GAP = 8.dp`.

### Sidebar "其它·选择文件" import trigger

- No change. Its existing modifier chain already applies `dashedBorder`/`spatialHoverEffect`/`clickable` before the final inner `.padding(horizontal = 12.dp)`, so the clickable region already equals its full visible `FOOTER_HEIGHT`-tall box — no dead-zone bug here.

### `FOOTER_HEIGHT` (shared by the sidebar's "其它" trigger and the bottom bar)

- `56.dp` → `64.dp`. Needed so the enlarged bottom-bar elements below (56.dp play button, 44.dp pills) have comfortable vertical breathing room instead of nearly touching the box's edges; as a side effect this also makes the sidebar's "其它" trigger taller, which is consistent with — not a regression of — this constant's existing documented purpose (vertically aligning the sidebar's bottom element with the content column's bottom bar).

### Bottom bar (`LibraryBottomBar.kt`)

- **Environment `ToggleableChip`s**: pass `chipSize = ChipsDefaults.Regular` explicitly (40.dp, up from the default `ChipsDefaults.Small` = 32.dp). Group them in their own `Row(horizontalArrangement = Arrangement.spacedBy(16.dp))`, removing the per-chip `.padding(end = 8.dp)` — `Arrangement.spacedBy` adds real space between siblings at the parent level, so it can never be absorbed into (or excluded from) any child's own hit region.
- **`FormatMenuButton.kt`**: add `.heightIn(min = 44.dp)` as the *first* modifier in its internal `Row`'s chain (before `.clip(shape)`), growing the whole clip/border/background/clickable region to at least 44.dp while the label+caret stay centered (`verticalAlignment = Alignment.CenterVertically` is already set).
- **Subtitle status**: replace the bare clickable `Text` with a new small private composable, `PillButton`, defined directly in `LibraryBottomBar.kt` (its only call site — no shared file needed for a single usage) using the *same visual chrome* as `FormatMenuButton` (rounded shape, 1.dp border, background fill, `.heightIn(min = 44.dp)`, `spatialHoverEffect()`, `clickable()` before any inner padding, `controllerHapticFeedback()`) but without a caret or `Menu` — it's a single action (open the subtitle-file picker), not a value-plus-options control. Reuses `FormatMenuButtonDefaults.libraryColors()` for color consistency with the two menu pills beside it.
- **The two `FormatMenuButton`s + the new subtitle `PillButton`**: grouped in their own `Row(horizontalArrangement = Arrangement.spacedBy(16.dp))`, removing their individual `.padding(end = 8.dp)`/`.padding(end = 20.dp)`.
- **"开始播放" `Button`**: pass `size = ButtonDefaults.Max` explicitly (56.dp minHeight, up from the default `ButtonDefaults.Regular` = 48.dp) — reinforces it as the primary action, and it's now comparably-or-more prominent than the secondary controls beside it rather than smaller than some of them.
- **Overall `Row`**: keep the existing `Spacer(Modifier.weight(1f))` split between the left (environment chips) and right (format/subtitle/play) groups unchanged — that flexible gap isn't a click-adjacency concern, only the tight groupings within each side are.

### Explicitly unchanged

- Video grid (`VideoGridCard`, `LazyVerticalGrid`) — out of scope per user decision.
- Category title text, permission-rationale card — not flagged as click targets (title isn't clickable at all; the permission card's button already uses `ButtonDefaults` defaults and isn't part of this complaint).
- `SIDEBAR_WIDTH` (220.dp) — this redesign is about height/spacing, not width.

## Testing

- Pure layout/sizing changes with no new business logic — no new JVM-testable behavior. Verification is: clean `assembleDebug`/`testDebugUnitTest` (confirms nothing broke), then on-device install + manual interaction check (tap each sidebar item near its edges, tap the format/subtitle pills, tap 开始播放) since Compose modifier-chain hit-test correctness for a VR pointer isn't something a JVM unit test exercises, and this project's device automation (`adb`/`uiautomator`) is documented as unreliable against this app's spatial windows — manual on-device tapping is the real verification here, same as for prior UI-only changes this session.
