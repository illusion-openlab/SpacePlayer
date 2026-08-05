package tech.illusion.spaceplayer.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.ecs.Entity
import tech.illusion.spaceplayer.ecs.PlaybackEntityAssembler
import tech.illusion.spaceplayer.playback.PlaybackManager
import tech.illusion.spaceplayer.playback.PlaybackState
import tech.illusion.spaceplayer.playback.StereoMode

const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f
const val SPHERE_RADIUS_METERS = 10f

class PlaybackViewModel(context: Context) {
    val manager = PlaybackManager(context)
    val screenEntity = Entity()
    val sphereEntity = Entity()

    var isImmersive = mutableStateOf(false)
        private set

    private var screenAssembled = false
    private var sphereAssembled = false

    fun startTestPlayback(assetPath: String, stereoMode: StereoMode) {
        if (!screenAssembled) {
            PlaybackEntityAssembler.assembleScreenEntity(
                screenEntity,
                manager.player,
                SCREEN_WIDTH_METERS,
                SCREEN_HEIGHT_METERS,
                stereoMode.toVideoDimensionMode(),
            )
            screenAssembled = true
        }
        screenEntity.enabled = true
        sphereEntity.enabled = false
        manager.setup(assetPath)
        isImmersive.value = true
    }

    fun startSphereTestPlayback(assetPath: String, stereoMode: StereoMode) {
        if (!sphereAssembled) {
            PlaybackEntityAssembler.assembleSphereEntity(
                sphereEntity,
                manager.player,
                SPHERE_RADIUS_METERS,
                stereoMode.toVideoDimensionMode(),
            )
            sphereAssembled = true
        }
        screenEntity.enabled = false
        sphereEntity.enabled = true
        manager.setup(assetPath)
        isImmersive.value = true
    }

    fun exitImmersive() {
        manager.pause()
        isImmersive.value = false
    }

    fun togglePlayPause() {
        when (manager.state) {
            PlaybackState.PLAYING -> manager.pause()
            PlaybackState.PAUSED, PlaybackState.READY -> manager.resume()
            else -> {}
        }
    }

    val showLoadingOverlay: Boolean
        get() = manager.state == PlaybackState.PREPARING ||
            manager.state == PlaybackState.ERROR ||
            (manager.state == PlaybackState.PLAYING && !manager.hasFirstFrameRendered)

    fun onCleared() {
        manager.reset()
    }
}
