package tech.illusion.spaceplayer.ui.library

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection

// Fixed brand colors from the design mockup - not part of PicoTheme's adaptive label/fill
// hierarchy, so per spatial-ui-design-style's Vibrant guide these are pinned with
// `withVibrant(Vibrant.None)` rather than mapped to a PicoTheme.colorScheme role (none of the
// scheme's 4 fixed semantic roles - error/alert/passable/interaction - mean "video format" or
// "environment").
val SpacePlayerAccent = Color(0xFFFF7A33).withVibrant(Vibrant.None) // design-style: fixed-figma-color accent
private val ProjectionNeutral = Color(0xFF2A2A2E).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge flat
private val ProjectionTeal = Color(0xFF1F6F6B).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge 180
private val ProjectionPurple = Color(0xFF6B3FA0).withVibrant(Vibrant.None) // design-style: fixed-figma-color format badge 360

fun Projection.badgeColor(): Color = when (this) {
    Projection.FLAT -> ProjectionNeutral
    Projection.HEMISPHERE_180 -> ProjectionTeal
    Projection.SPHERE_360 -> ProjectionPurple
}

fun Environment.dotColor(): Color = when (this) {
    Environment.CINEMA -> Color(0xFF8B2E2E).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot cinema
    Environment.STARRY_SKY -> Color(0xFF3A5FCD).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot starry sky
    Environment.SEASIDE -> Color(0xFF2FA88F).withVibrant(Vibrant.None) // design-style: fixed-figma-color env dot seaside
}
