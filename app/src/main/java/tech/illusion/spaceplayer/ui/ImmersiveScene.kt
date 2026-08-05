package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID

private const val LOADING_ATTACHMENT_ID = "loading"
private const val HUD_ATTACHMENT_ID = "hud"

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()

    PicoTheme {
        SpatialView(
            attachments = {
                AttachmentPanel(id = LOADING_ATTACHMENT_ID) {
                    LoadingErrorAttachment(viewModel.manager.state)
                }
                AttachmentPanel(id = HUD_ATTACHMENT_ID) {
                    PlaybackHud(
                        state = viewModel.manager.state,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onExit = {
                            viewModel.exitImmersive()
                            coroutineScope.launch { navigator.closeStage() }
                        },
                    )
                }
            },
            initial = { content, attachments ->
                content.addEntity(viewModel.screenEntity)

                attachments.entity(LOADING_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0f, 0.05f))
                    }
                    viewModel.screenEntity.addChild(this)
                }

                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, -0.55f, 0.05f))
                    }
                    viewModel.screenEntity.addChild(this)
                }
            },
            update = { _, attachments ->
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay
            },
        )
    }
}
