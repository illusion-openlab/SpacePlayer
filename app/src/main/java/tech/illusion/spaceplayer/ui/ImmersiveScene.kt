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
                content.addEntity(viewModel.sphereEntity)

                // Independent of screenEntity/sphereEntity on purpose: screenEntity sits 2m away
                // and sphereEntity is a 10m-radius shell, and only one of the two is `enabled` at
                // a time (children of a disabled entity are hidden too) - so the HUD/loading
                // overlay must NOT be parented to either, or it would disappear in sphere mode.
                // Fixed in front of the default spawn point works for both.
                attachments.entity(LOADING_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 1.5f, -1.5f))
                    }
                    content.addEntity(this)
                }

                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0.9f, -1.5f))
                    }
                    content.addEntity(this)
                }
            },
            update = { _, attachments ->
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
                attachments.entity(HUD_ATTACHMENT_ID)?.enabled = !viewModel.showLoadingOverlay
            },
        )
    }
}
