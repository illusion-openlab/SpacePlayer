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
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f

class PlaybackViewModel(context: Context) {
    val manager = PlaybackManager(context)
    val screenEntity = Entity()
    val sphereEntity = Entity()
    val hemisphereEntity = Entity()

    var isImmersive = mutableStateOf(false)
        private set

    private var screenAssembled = false
    private var sphereAssembled = false
    private var hemisphereAssembled = false

    private fun disableAllVideoEntities() {
        screenEntity.enabled = false
        sphereEntity.enabled = false
        hemisphereEntity.enabled = false
    }

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
        disableAllVideoEntities()
        screenEntity.enabled = true
        manager.setup(assetPath)
        isImmersive.value = true
    }

    fun startSphereTestPlayback(assetPath: String, stereoMode: StereoMode) {
        if (!sphereAssembled) {
            PlaybackEntityAssembler.assembleSphereEntity(
                sphereEntity,
                manager.player,
                SPHERE_RADIUS_METERS,
                FULL_SPHERE_FOV_DEGREES,
                stereoMode.toVideoDimensionMode(),
            )
            sphereAssembled = true
        }
        disableAllVideoEntities()
        sphereEntity.enabled = true
        manager.setup(assetPath)
        isImmersive.value = true
    }

    fun startHemisphereTestPlayback(assetPath: String, stereoMode: StereoMode) {
        if (!hemisphereAssembled) {
            PlaybackEntityAssembler.assembleSphereEntity(
                hemisphereEntity,
                manager.player,
                SPHERE_RADIUS_METERS,
                HEMISPHERE_FOV_DEGREES,
                stereoMode.toVideoDimensionMode(),
            )
            hemisphereAssembled = true
        }
        disableAllVideoEntities()
        hemisphereEntity.enabled = true
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
