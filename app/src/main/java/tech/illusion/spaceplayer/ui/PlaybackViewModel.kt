package tech.illusion.spaceplayer.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.illusion.spaceplayer.ecs.PlaybackEntityAssembler
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.VideoItem
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.playback.DEFAULT_VOLUME
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.PlaybackManager
import tech.illusion.spaceplayer.playback.PlaybackState
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.subtitle.SrtParser
import tech.illusion.spaceplayer.subtitle.SubtitleCue
import tech.illusion.spaceplayer.subtitle.SubtitleCueLookup

private const val TAG = "PlaybackViewModel"

const val SCREEN_WIDTH_METERS = 1.6f
const val SCREEN_HEIGHT_METERS = 0.9f
const val SPHERE_RADIUS_METERS = 10f
const val FULL_SPHERE_FOV_DEGREES = 360f
const val HEMISPHERE_FOV_DEGREES = 180f
const val ENVIRONMENT_SKYBOX_RADIUS_METERS = 20f
const val HAND_MARKER_RADIUS_METERS = 0.008f

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

    // A fresh instance per playback session, not a single instance reused for the process's whole
    // lifetime - the SDK's own HMD-tracking sample scopes HMDTrackingProvider to one start()/stop()
    // pair per remember/DisposableEffect lifecycle. Reusing one instance across many back-to-back
    // start()/stop() cycles (auto-return -> tap next video -> start() again) is what produced a real
    // ANR on device: the main thread blocked for 5+ seconds inside the native tracking-start call
    // (confirmed via `dumpsys dropbox --print`'s ANR stack trace, ~2026-08-13, ending in
    // spatial::jobs::JobWaiter::wait beneath HMDTrackingDataSource.nativeStartHMDTrackingDataSource).
    var hmdTrackingProvider: HMDTrackingProvider? = null
        private set

    // Same ANR risk as HMDTrackingProvider.start() below - both go through the same
    // BaseTrackingDataProvider.start() -> dataSource.addDataCallback() blocking path (confirmed in
    // tracking-0.13.3-sources.jar), so this must also run on backgroundScope, never the main thread.
    var handTrackingProvider: HandTrackingProvider? = null
        private set

    val thumbTipEntity = Entity()
    val indexTipEntity = Entity()
    private var handMarkersAssembled = false

    // HMDTrackingProvider.start() is a synchronous native call (blocks on the SDK's own
    // spatial::jobs::JobWaiter::wait) - confirmed via a real ANR (`dumpsys dropbox --print`,
    // "Input dispatching timed out ... Waited 5000ms", stack ending in
    // PlaybackViewModel.startPlayback -> HMDTrackingProvider.start -> nativeStartHMDTrackingDataSource)
    // when it was called directly from the "开始播放" click handler on the main thread. Neither the
    // SDK docs nor the decompiled 0.13.3 source (BaseTrackingDataProvider.start()) declare a
    // main-thread requirement, so dispatching the call itself to a background thread is safe and
    // keeps the click handler from blocking input dispatch long enough to trip the ANR watchdog.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun assembleHandMarkersIfNeeded() {
        if (!handMarkersAssembled) {
            PlaybackEntityAssembler.assembleHandMarkerEntity(thumbTipEntity, HAND_MARKER_RADIUS_METERS)
            PlaybackEntityAssembler.assembleHandMarkerEntity(indexTipEntity, HAND_MARKER_RADIUS_METERS)
            handMarkersAssembled = true
        }
        // Hidden until the first frame of real hand-tracking data arrives - see the per-frame loop
        // in ImmersiveScene.kt (Task 8) that flips these back on/off based on tracking availability.
        // Reset unconditionally (not just on first assembly) so the second and later videos played in
        // the same session don't start with markers visually enabled/at a stale position left over
        // from the previous video - this call runs once per startPlayback(), not just once per
        // PlaybackViewModel instance.
        thumbTipEntity.enabled = false
        indexTipEntity.enabled = false
    }

    private var subtitleCues: List<SubtitleCue> = emptyList()

    var currentSubtitleText by mutableStateOf("")
        private set

    var currentPositionMs by mutableStateOf(0L)
        private set

    val durationMs: Long
        get() = manager.duration

    var isMuted by mutableStateOf(false)
        private set

    fun toggleMute() {
        isMuted = !isMuted
        manager.setVolume(if (isMuted) 0f else DEFAULT_VOLUME)
    }

    /** Set when playback reaches the end - ImmersiveScene observes this to return to the main window. */
    var returnToMainWindowRequested by mutableStateOf(false)
        private set

    /** Drives the HUD AttachmentPanel's `.enabled` from ImmersiveScene's per-frame loop - starts
     * visible each session, auto-hides once 5s after the first frame renders, then only changes via
     * a pinch gesture (Task 8's [toggleHudVisibility] call). */
    var isHudVisible by mutableStateOf(true)
        private set

    fun toggleHudVisibility() {
        isHudVisible = !isHudVisible
    }

    /** One-directional, not a toggle - called once from the 5s auto-hide timer so it can't
     * accidentally re-show a panel the user already pinched back on within that window. */
    fun hideHud() {
        isHudVisible = false
    }
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

    // Compose-observable format state: the HUD's two format-correction menus show these as their
    // current values, and `currentProjection == FLAT` also gates the environment switcher (only
    // meaningful for flat video). Kept as state rather than read off the entities, since a plain
    // field read of an SDK Entity's `enabled` property wouldn't trigger recomposition on its own.
    var currentProjection = mutableStateOf(Projection.FLAT)
        private set

    var currentStereoMode = mutableStateOf(StereoMode.MONO)
        private set

    var currentItem: VideoItem? = null
        private set

    // Each assembled video entity owns its own VideoMaterial instance; these references are how
    // stereo-format correction reaches them later (VideoPlayerComponent has setMaterial but no
    // getter). Non-null also means "this entity is already assembled", so there are no separate
    // assembled flags to keep in sync.
    private var screenMaterial: VideoMaterial? = null
    private var sphereMaterial: VideoMaterial? = null
    private var hemisphereMaterial: VideoMaterial? = null
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

    /**
     * Shows the entity matching [projection] (assembling it on first use) and hides the other two,
     * then applies [dimensionMode] to that entity's material. All three entities are driven by the
     * same [PlaybackManager.player], so this never touches the player itself - which is what makes
     * it safe to call both at playback start and mid-playback from [correctFormat] without
     * restarting or rebuffering.
     *
     * The dimension mode is (re)applied on every call rather than only at assembly time: entities
     * are assembled once and outlive a single playback session, so a second video with a different
     * stereo format would otherwise keep the first video's mode.
     */
    private fun applyProjection(projection: Projection, dimensionMode: VideoDimensionMode) {
        when (projection) {
            Projection.FLAT -> {
                assembleEnvironmentsIfNeeded()
                val material = screenMaterial ?: PlaybackEntityAssembler.assembleScreenEntity(
                    screenEntity, manager.player, SCREEN_WIDTH_METERS, SCREEN_HEIGHT_METERS, dimensionMode,
                ).also { screenMaterial = it }
                material.setDimensionMode(dimensionMode)
                disableAllVideoEntities()
                screenEntity.enabled = true
            }
            Projection.SPHERE_360 -> {
                val material = sphereMaterial ?: PlaybackEntityAssembler.assembleSphereEntity(
                    sphereEntity, manager.player, SPHERE_RADIUS_METERS, FULL_SPHERE_FOV_DEGREES, dimensionMode,
                ).also { sphereMaterial = it }
                material.setDimensionMode(dimensionMode)
                disableAllVideoEntities()
                sphereEntity.enabled = true
            }
            Projection.HEMISPHERE_180 -> {
                val material = hemisphereMaterial ?: PlaybackEntityAssembler.assembleSphereEntity(
                    hemisphereEntity, manager.player, SPHERE_RADIUS_METERS, HEMISPHERE_FOV_DEGREES, dimensionMode,
                ).also { hemisphereMaterial = it }
                material.setDimensionMode(dimensionMode)
                disableAllVideoEntities()
                hemisphereEntity.enabled = true
            }
        }
        currentProjection.value = projection
        repositionScreenForCurrentEnvironment()
        // Reads screenEntity.enabled, so for 180°/360° this turns all three skyboxes off.
        updateEnvironmentVisibility()
    }

    /**
     * Format correction from inside the immersive HUD - the same correction the library's bottom bar
     * makes before playback, applied live: stereo format goes straight to the already-assembled
     * materials, projection swaps which video entity is visible, and neither path recreates the
     * `CypressMediaPlayer`, so position, volume and buffered data all survive.
     *
     * Persisted through the same [VideoPreferencesStore.setFormatOverride] path as the library
     * entry point, so the correction outlives this session and the library card's badge picks it up
     * after the refresh triggered on return to the main window.
     */
    fun correctFormat(projection: Projection, stereoMode: StereoMode) {
        val item = currentItem ?: return
        if (projection == currentProjection.value && stereoMode == currentStereoMode.value) return
        val dimensionMode = stereoMode.toVideoDimensionMode()
        currentStereoMode.value = stereoMode
        // Update every assembled material, not just the visible one, so switching projection later
        // doesn't fall back to the stereo mode captured when that entity happened to be assembled.
        listOfNotNull(screenMaterial, sphereMaterial, hemisphereMaterial)
            .forEach { it.setDimensionMode(dimensionMode) }
        if (projection != currentProjection.value) {
            applyProjection(projection, dimensionMode)
        }
        preferencesStore.setFormatOverride(item.uri, projection, stereoMode)
        currentItem = item.copy(
            projection = projection,
            stereoMode = stereoMode,
            formatSource = FormatSource.MANUAL_OVERRIDE,
        )
    }

    fun startPlayback(item: VideoItem) {
        currentItem = item
        subtitleCues = loadSubtitleCues(item.subtitleUri)
        returnToMainWindowRequested = false
        isHudVisible = true
        assembleHandMarkersIfNeeded()
        // Assign the provider immediately (ImmersiveScene's per-frame loop reads it null-safely and
        // just sees a default zero pose until start() actually finishes) - only the blocking start()
        // call itself is pushed off the main thread, so the click handler returns right away.
        val provider = HMDTrackingProvider()
        hmdTrackingProvider = provider
        backgroundScope.launch { provider.start() }
        val handProvider = HandTrackingProvider()
        handTrackingProvider = handProvider
        backgroundScope.launch {
            val result = handProvider.start()
            Log.i(TAG, "HandTrackingProvider start result=$result supportState=${handProvider.supportState}")
        }
        currentStereoMode.value = item.stereoMode
        if (item.projection == Projection.FLAT) {
            // Applied before applyProjection so the screen lands on the right anchor straight away.
            currentEnvironment.value = item.preferredEnvironment ?: currentEnvironment.value
        }
        applyProjection(item.projection, item.stereoMode.toVideoDimensionMode())
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
        if (item != null && currentProjection.value == Projection.FLAT) {
            preferencesStore.setPreferredEnvironment(item.uri, currentEnvironment.value)
        }
        manager.pause()
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        handTrackingProvider?.stop()
        handTrackingProvider = null
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

    /** Called every frame from ImmersiveScene's per-frame LaunchedEffect loop - drives subtitle
     * lookup and the HUD's progress bar from a single position read (CypressMediaPlayer's methods
     * each hop through `runOnScheduleThread`'s `runBlocking`, so reading position twice per frame
     * would double that per-frame block for no reason). */
    fun refreshPlaybackFrame() {
        val positionMs = manager.player.getCurrentPosition()
        currentSubtitleText = SubtitleCueLookup.textAt(subtitleCues, positionMs)
        currentPositionMs = positionMs
    }

    fun togglePlayPause() {
        when (manager.state) {
            PlaybackState.PLAYING -> manager.pause()
            PlaybackState.PAUSED, PlaybackState.READY -> manager.resume()
            else -> {}
        }
    }

    /** Assigns `currentPositionMs` immediately so the HUD thumb doesn't snap back for the one
     * frame it takes `refreshPlaybackFrame()` to catch up with the player's real position. */
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
        hmdTrackingProvider?.stop()
        hmdTrackingProvider = null
        handTrackingProvider?.stop()
        handTrackingProvider = null
        backgroundScope.cancel()
    }
}
