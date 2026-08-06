package tech.illusion.spaceplayer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import tech.illusion.spaceplayer.ecs.PlaybackEntityAssembler
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.PlaybackManager
import tech.illusion.spaceplayer.playback.PlaybackState
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.subtitle.SrtParser
import tech.illusion.spaceplayer.subtitle.SubtitleCue
import tech.illusion.spaceplayer.subtitle.SubtitleCueLookup

const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f
const val SPHERE_RADIUS_METERS = 10f
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f
const val ENVIRONMENT_SKYBOX_RADIUS_METERS = 20f

// "Docked" onto a cinema wall: farther away than the default floating position, simulating a
// screen embedded across the room rather than a panel hovering in front of the user.
private val CINEMA_SCREEN_POSITION = Vector3(0f, 1.6f, -4f)
private val FLOATING_SCREEN_POSITION = Vector3(0f, 1.5f, -2f)

class PlaybackViewModel(
    private val context: Context,
    private val historyStore: PlaybackHistoryStore,
    private val preferencesStore: VideoPreferencesStore,
) {
    val manager = PlaybackManager(context)
    val hmdTrackingProvider = HMDTrackingProvider()

    private var subtitleCues: List<SubtitleCue> = emptyList()

    var currentSubtitleText by mutableStateOf("")
        private set

    var currentPositionMs by mutableStateOf(0L)
        private set

    val durationMs: Long
        get() = manager.duration

    /** Set when playback reaches the end - ImmersiveScene observes this to return to the main window. */
    var returnToMainWindowRequested by mutableStateOf(false)
        private set
    val screenEntity = Entity()
    val sphereEntity = Entity()
    val hemisphereEntity = Entity()
    val cinemaEnvironmentEntity = Entity()
    val starrySkyEnvironmentEntity = Entity()
    val seasideEnvironmentEntity = Entity()

    var isImmersive = mutableStateOf(false)
        private set

    var currentEnvironment = mutableStateOf(Environment.CINEMA)
        private set

    // Compose-observable mirror of `screenEntity.enabled` - the HUD reads this to decide whether
    // to show the environment switcher (only meaningful for flat video), and a plain field read
    // of an SDK Entity's `enabled` property wouldn't trigger recomposition on its own.
    var isFlatProjection = mutableStateOf(true)
        private set

    var currentItem: VideoItem? = null
        private set

    private var screenAssembled = false
    private var sphereAssembled = false
    private var hemisphereAssembled = false
    private var environmentsAssembled = false

    private fun disableAllVideoEntities() {
        screenEntity.enabled = false
        sphereEntity.enabled = false
        hemisphereEntity.enabled = false
    }

    private fun assembleEnvironmentsIfNeeded() {
        if (environmentsAssembled) return
        PlaybackEntityAssembler.assembleEnvironmentEntity(
            cinemaEnvironmentEntity, Environment.CINEMA.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
        )
        PlaybackEntityAssembler.assembleEnvironmentEntity(
            starrySkyEnvironmentEntity, Environment.STARRY_SKY.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
        )
        PlaybackEntityAssembler.assembleEnvironmentEntity(
            seasideEnvironmentEntity, Environment.SEASIDE.assetPath, ENVIRONMENT_SKYBOX_RADIUS_METERS,
        )
        environmentsAssembled = true
    }

    // Only meaningful for the flat screen - 180°/360° video doesn't go through EnvironmentLayer,
    // it *is* the environment (see design spec section 3).
    private fun updateEnvironmentVisibility() {
        val showEnvironment = screenEntity.enabled
        cinemaEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.CINEMA
        starrySkyEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.STARRY_SKY
        seasideEnvironmentEntity.enabled = showEnvironment && currentEnvironment.value == Environment.SEASIDE
    }

    private fun repositionScreenForCurrentEnvironment() {
        val target = if (currentEnvironment.value == Environment.CINEMA) {
            CINEMA_SCREEN_POSITION
        } else {
            FLOATING_SCREEN_POSITION
        }
        screenEntity.components[TransformComponent::class.java]?.setPosition(target)
    }

    /** Switchable while playing - does not touch `manager`/`CypressMediaPlayer` at all. */
    fun switchEnvironment(target: Environment) {
        currentEnvironment.value = target
        repositionScreenForCurrentEnvironment()
        updateEnvironmentVisibility()
    }

    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
        hmdTrackingProvider.start()
        val dimensionMode = item.stereoMode.toVideoDimensionMode()
        when (item.projection) {
            Projection.FLAT -> {
                assembleEnvironmentsIfNeeded()
                if (!screenAssembled) {
                    PlaybackEntityAssembler.assembleScreenEntity(
                        screenEntity, manager.player, SCREEN_WIDTH_METERS, SCREEN_HEIGHT_METERS, dimensionMode,
                    )
                    screenAssembled = true
                }
                disableAllVideoEntities()
                screenEntity.enabled = true
                isFlatProjection.value = true
                currentEnvironment.value = item.preferredEnvironment ?: currentEnvironment.value
                repositionScreenForCurrentEnvironment()
                updateEnvironmentVisibility()
            }
            Projection.SPHERE_360 -> {
                if (!sphereAssembled) {
                    PlaybackEntityAssembler.assembleSphereEntity(
                        sphereEntity, manager.player, SPHERE_RADIUS_METERS, FULL_SPHERE_FOV_DEGREES, dimensionMode,
                    )
                    sphereAssembled = true
                }
                disableAllVideoEntities()
                sphereEntity.enabled = true
                isFlatProjection.value = false
                updateEnvironmentVisibility() // screenEntity is now disabled, so this turns all 3 off
            }
            Projection.HEMISPHERE_180 -> {
                if (!hemisphereAssembled) {
                    PlaybackEntityAssembler.assembleSphereEntity(
                        hemisphereEntity, manager.player, SPHERE_RADIUS_METERS, HEMISPHERE_FOV_DEGREES, dimensionMode,
                    )
                    hemisphereAssembled = true
                }
                disableAllVideoEntities()
                hemisphereEntity.enabled = true
                isFlatProjection.value = false
                updateEnvironmentVisibility() // screenEntity is now disabled, so this turns all 3 off
            }
        }
        manager.onFirstFrameRendered = {
            historyStore.recordPlayed(item.uri.toString(), System.currentTimeMillis())
        }
        manager.onPlaybackCompleted = {
            returnToMainWindowRequested = true
        }
        manager.setup(item.uri)
        isImmersive.value = true
    }

    fun exitImmersive() {
        val item = currentItem
        if (item != null && isFlatProjection.value) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        hmdTrackingProvider.stop()
        isImmersive.value = false
        returnToMainWindowRequested = false
    }

    private fun loadSubtitleCues(subtitleUri: Uri?): List<SubtitleCue> {
        if (subtitleUri == null) return emptyList()
        return runCatching {
            context.contentResolver.openInputStream(subtitleUri)?.use { input ->
                SrtParser.parse(input.readBytes().toString(Charsets.UTF_8))
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Called every frame from ImmersiveScene's SpatialView.update block. */
    fun refreshSubtitleText() {
        currentSubtitleText = SubtitleCueLookup.textAt(subtitleCues, manager.player.getCurrentPosition())
    }

    /** Called every frame from ImmersiveScene's SpatialView.update block, drives the HUD's progress bar. */
    fun refreshPlaybackProgress() {
        currentPositionMs = manager.player.getCurrentPosition()
    }

    fun togglePlayPause() {
        when (manager.state) {
            PlaybackState.PLAYING -> manager.pause()
            PlaybackState.PAUSED, PlaybackState.READY -> manager.resume()
            else -> {}
        }
    }

    /** Assigns `currentPositionMs` immediately so the HUD thumb doesn't snap back for the one
     * frame it takes `refreshPlaybackProgress()` to catch up with the player's real position. */
    fun seekTo(ms: Long) {
        manager.seekTo(ms)
        currentPositionMs = ms
    }

    val showLoadingOverlay: Boolean
        get() = manager.state == PlaybackState.PREPARING ||
            manager.state == PlaybackState.ERROR ||
            (manager.state == PlaybackState.PLAYING && !manager.hasFirstFrameRendered)

    fun onCleared() {
        manager.reset()
        hmdTrackingProvider.stop()
    }
}
