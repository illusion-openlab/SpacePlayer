package tech.illusion.spaceplayer.ui.library

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection

// Fixed warm/bright brand palette from the design mockup - not part of PicoTheme's adaptive
// label/fill hierarchy, so per spatial-ui-design-style's Vibrant guide these are pinned with
// `withVibrant(Vibrant.None)` rather than mapped to a PicoTheme.colorScheme role. The window root
// paints a fixed background (see MainLibraryScreen's opaque-root override), so every other color
// in this screen is pinned too, rather than mixing fixed + system-adaptive colors that could clash
// against a system dark theme.
val SpacePlayerBackground = Color(0xFFFFFBF7).withVibrant(Vibrant.None) // design-style: fixed-figma-color window background (极浅暖白)
val SpacePlayerSurface = Color(0xFFFFFFFF).withVibrant(Vibrant.None) // design-style: fixed-figma-color card surface
val SpacePlayerSurfaceSelected = Color(0xFFFFF3EA).withVibrant(Vibrant.None) // design-style: fixed-figma-color selected surface
val SpacePlayerBorder = Color(0xFFEEEAE0).withVibrant(Vibrant.None) // design-style: fixed-figma-color card border
val SpacePlayerTextPrimary = Color(0xFF1A1A1A).withVibrant(Vibrant.None) // design-style: fixed-figma-color text primary
val SpacePlayerTextSecondary = Color(0xFF5C5347).withVibrant(Vibrant.None) // design-style: fixed-figma-color text secondary
val SpacePlayerTextTertiary = Color(0xFF8C8275).withVibrant(Vibrant.None) // design-style: fixed-figma-color text tertiary
val SpacePlayerTextDisabled = Color(0xFFBDB6AB).withVibrant(Vibrant.None) // design-style: fixed-figma-color text disabled (sidebar locked pre-permission)
val SpacePlayerAccent = Color(0xFFE63946).withVibrant(Vibrant.None) // design-style: fixed-figma-color accent
val SpacePlayerOnAccent = Color(0xFFFFFFFF).withVibrant(Vibrant.None) // design-style: fixed-figma-color on-accent text
val SpacePlayerThumbnailPlaceholder = Color(0xFFF1EEE6).withVibrant(Vibrant.None) // design-style: fixed-figma-color thumbnail placeholder

private val ProjectionNeutral = Color(0xFF8B7355).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge flat
private val ProjectionTeal = Color(0xFF4C8577).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge 180
private val ProjectionPurple = Color(0xFF9A5B8C).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge 360

fun Projection.badgeColor(): Color = when (this) {
    Projection.FLAT -> ProjectionNeutral
    Projection.HEMISPHERE_180 -> ProjectionTeal
    Projection.SPHERE_360 -> ProjectionPurple
}

fun Environment.dotColor(): Color = when (this) {
    Environment.CINEMA -> Color(0xFFB33F3F).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot cinema
    Environment.STARRY_SKY -> Color(0xFF4A5FCF).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot starry sky
    Environment.SEASIDE -> Color(0xFF3FA88C).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot seaside
}
