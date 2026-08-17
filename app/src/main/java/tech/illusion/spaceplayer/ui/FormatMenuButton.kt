package tech.illusion.spaceplayer.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import tech.illusion.spaceplayer.ui.library.SpacePlayerBorder
import tech.illusion.spaceplayer.ui.library.SpacePlayerSurface
import tech.illusion.spaceplayer.ui.library.SpacePlayerTextPrimary
import tech.illusion.spaceplayer.ui.library.SpacePlayerTextSecondary

/** Color set for [FormatMenuButton]; see [FormatMenuButtonDefaults] for the two call sites' sets. */
data class FormatMenuButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
    val trailingContentColor: Color,
)

object FormatMenuButtonDefaults {
    /**
     * Library window palette: an opaque card surface on the window's fixed warm-white root
     * (see SpacePlayerPalette.kt for why that screen pins fixed colors instead of PicoTheme roles).
     */
    fun libraryColors(): FormatMenuButtonColors = FormatMenuButtonColors(
        containerColor = SpacePlayerSurface,
        contentColor = SpacePlayerTextPrimary,
        borderColor = SpacePlayerBorder,
        trailingContentColor = SpacePlayerTextSecondary,
    )
}

/**
 * A compact pill that shows the current format value and opens a [Menu] of choices on click -
 * shared by the library bottom bar and the immersive HUD so both format-correction entry points
 * behave identically and only differ by [colors] (opaque card vs. glass panel).
 *
 * [Menu] positions itself relative to its parent [Box] (its own KDoc: "Commonly a Menu will be
 * placed in a Box with a sibling that will be used as the 'anchor'"), so the trigger Row and the
 * conditionally-shown Menu both live inside the same Box here.
 *
 * No built-in fits: [Menu] needs an anchor the caller draws itself, and the value-plus-caret
 * trigger is not one of the built-in chip/button shapes - so this is a legitimate custom
 * component per spatial-ui-design-style's builtins guidance, and carries the required
 * spatialHoverEffect + shared-interactionSource indication + haptics.
 */
@Composable
fun FormatMenuButton(
    label: String,
    modifier: Modifier = Modifier,
    colors: FormatMenuButtonColors = FormatMenuButtonDefaults.libraryColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    menuItems: @Composable (collapse: () -> Unit) -> Unit,
) {
    // rememberSaveable, not remember: menu visibility is exactly the popup-style state the
    // spatial-ui-design-style checklist wants restored if this panel's composition is recreated.
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
            Text(
                text = label,
                color = colors.contentColor,
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            )
            Text(
                text = "▾",
                color = colors.trailingContentColor,
                style = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            Menu(onDismissRequest = { expanded = false }) {
                menuItems { expanded = false }
            }
        }
    }
}
