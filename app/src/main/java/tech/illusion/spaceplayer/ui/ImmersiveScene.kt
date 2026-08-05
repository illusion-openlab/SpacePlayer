package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()

    PicoTheme {
        SpatialView(
            initial = { content, _ ->
                content.addEntity(viewModel.screenEntity)
            },
        )
    }
}
