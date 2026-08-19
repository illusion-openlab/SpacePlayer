package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tech.illusion.spaceplayer.MAIN_WINDOW_ID
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.ecs.PinchDetector
import tech.illusion.spaceplayer.ecs.SubtitleFollowComponent
import tech.illusion.spaceplayer.ecs.applySubtitleFollow
import tech.illusion.spaceplayer.playback.PlaybackState

private const val LOADING_ATTACHMENT_ID = "loading"
private const val HUD_ATTACHMENT_ID = "hud"
private const val SUBTITLE_ATTACHMENT_ID = "subtitle"

// Fixed HUD tilt, in DEGREES (EulerAngles fields are degrees, not radians - confirmed by
// decompiling foundation-0.13.3-sources.jar's EulerAngles.toQuat(), which applies its own
// `* (PI / 180.0)`). The panel sits below eye level and is angled face-up to be read from above.
// Magnitude set to 30 by the user after seeing 60 in a headset.
//
// The sign comes from the only two observations that ever constrained it. Every report predating
// the move of this assignment into the frame loop is void as evidence, because the rotation was
// inert then - each "the tilt is backwards" report was describing the same untouched default.
// With the rotation actually live:
//   pitch = 0 (the untouched default): top edge leans toward the viewer, moderately.
//   pitch = +60: panel edge-on, visible only as a thin line.
// Edge-on is 90 degrees off facing, so the default already sits ~30 degrees tilted toward the
// viewer and positive pitch deepens that lean; negative is the face-up direction.
//
// Not verifiable locally: the HUD is an AttachmentPanel, and attachment panels do not appear in
// compositor screenshots (the video screen that does is an ECS model entity, a different render
// path), so this can only be judged in a headset.
private const val HUD_PITCH_DEGREES = -30f

@Composable
fun ImmersiveScene() {
    val scope = GlobalContext.get().getScope(PLAYBACK_SESSION_SCOPE_ID)
    val viewModel: PlaybackViewModel = scope.get()
    val navigator = LocalSpatialNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val subtitleFollow = remember { SubtitleFollowComponent() }
    var spatialAttachments by remember { mutableStateOf<SpatialViewAttachments?>(null) }
    // Plain Compose state, not an ECS Component - same reasoning as subtitleFollow above: the
    // native ECS layer silently rejects custom Component subtypes.
    var wasPinching by remember { mutableStateOf(false) }
    // Same reasoning as wasPinching above (plain Compose state, not an ECS Component). Tracks
    // whether hand tracking has ever produced a real frame this session - see the 5s auto-hide
    // LaunchedEffect below for why this gates the only escape hatch out of immersive playback.
    var hasEverTrackedHand by remember { mutableStateOf(false) }
    // True while the user's ray/pointer is over the HUD panel - reported by PlaybackHud below.
    // Gives the panel first claim on a pinch; see the pinch block in the frame loop.
    var isHudPointerOver by remember { mutableStateOf(false) }
    // Whether playback was actually PLAYING at the moment the app backgrounded - only resume
    // automatically on foreground if this is true, so a manual pause survives a background trip.
    var wasPlayingBeforeBackground by remember { mutableStateOf(false) }

    fun returnToMainWindow() {
        viewModel.exitImmersive()
        // Reopen the main window BEFORE tearing the Stage down, never after. Doing it the other way
        // round (closeStage() here, openWindowContainer() from onDispose) leaves a window - measured
        // at ~520ms in thread_debug.txt - during which this app owns NO container at all: the main
        // window was closed on entry and the Stage is already gone. The system tears the app's
        // window session down inside that gap ("Failed looking up window session" /
        // "No window state for package:tech.illusion.spaceplayer" in logcat), and an
        // openWindowContainer() request that lands after that teardown is silently dropped - the
        // process stays alive with no window ever coming back, which looks exactly like a crash.
        // Whether the request or the teardown wins is a race, which is why it only reproduced every
        // few rounds. StoryPico never hits this: all 11 of its exit sites open the next container
        // first and close the current one second, and its onDispose does resource cleanup only,
        // never navigation.
        navigator.openWindowContainer(MAIN_WINDOW_ID)
        coroutineScope.launch { navigator.closeStage() }
    }

    // The main window disappears while the immersive Stage is open (Full space visually occludes
    // it) - close it explicitly on entry, per the SDK's own documented "expand to immersive"
    // pattern. Reopening deliberately does NOT happen in onDispose: by the time the composition is
    // being torn down the Stage is already closed, which is precisely the lost-window race
    // documented in returnToMainWindow() above. onDispose is kept for cleanup-only concerns, the
    // same split StoryPico's PlayerSpace uses.
    DisposableEffect(Unit) {
        navigator.closeWindowContainer(MAIN_WINDOW_ID)
        onDispose { }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (viewModel.manager.state == PlaybackState.PLAYING) {
                        wasPlayingBeforeBackground = true
                        viewModel.manager.pause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingBeforeBackground) {
                        wasPlayingBeforeBackground = false
                        viewModel.manager.resume()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-return to the main window once playback reaches the end, same path as the HUD's
    // "返回主窗口" button.
    LaunchedEffect(viewModel.returnToMainWindowRequested) {
        if (viewModel.returnToMainWindowRequested) {
            returnToMainWindow()
        }
    }

    // Auto-hide the HUD once, 5s after the first frame renders (not 5s after startPlayback() - a
    // slow-loading video would otherwise burn part or all of that window under the loading overlay).
    // hasFirstFrameRendered only goes false->true once per session, so this LaunchedEffect body only
    // runs once per playback - it does not re-fire if the user pinches the HUD back on afterward.
    LaunchedEffect(viewModel.manager.hasFirstFrameRendered) {
        if (viewModel.manager.hasFirstFrameRendered) {
            delay(5000)
            // Only auto-hide once hand tracking has actually proven itself functional at least once
            // this session (hasEverTrackedHand, set from the per-frame hand-tracking block below).
            // The HUD is the ONLY way back to the main window ("返回主窗口") plus every playback
            // control, and toggleHudVisibility() has exactly one caller: the pinch rising edge. If
            // hand tracking never produces a frame - no controller support on the emulator, a
            // permission not yet granted this session, or a device that simply lacks hand tracking -
            // auto-hiding anyway would strand the user with no visible controls and no way back short
            // of the video ending or an OS-level home press. Deliberately doing nothing here (HUD
            // stays visible) is the same safe behavior the app had before this feature existed.
            if (hasEverTrackedHand) {
                viewModel.hideHud()
            }
        }
    }

    // SpatialView's own `update` parameter is NOT a per-frame render loop, despite reading like
    // one - its KDoc says it fires once after `initial`, then again only when a Compose state
    // value read inside that lambda changes. Position polling, subtitle lookup, and the lagged
    // subtitle-follow lerp all need a genuine continuous per-frame driver, so they run in their
    // own coroutine here instead, paced by withFrameNanos (Compose's own frame clock, tied to the
    // real display refresh). Confirmed via a frame counter: the old code (calling this from
    // `update`) logged exactly one invocation total for an entire 60-second video playback.
    LaunchedEffect(Unit) {
        var lastFrameNs = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { nowNs ->
                val deltaTime = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameNs = nowNs

                viewModel.refreshPlaybackFrame()

                // Right-hand fingertip markers + pinch detection. Runs before the `attachments`
                // null-check below because it only touches viewModel.thumbTipEntity/indexTipEntity
                // (added directly to `content`, not attachment panels) and viewModel state - none of
                // it needs `attachments` to be non-null.
                //
                // Wrapped in runCatching: HandPose.joint(index) is implemented in the SDK as
                // `handJoints.first { it.index == index }` (first, not firstOrNull), so a
                // malformed/partial joint list throws NoSuchElementException. This block runs inside
                // the app's only per-frame driver (this same withFrameNanos loop), which also drives
                // playback position, subtitle lookup, subtitle follow, and the HUD's `.enabled` write -
                // an uncaught exception here would kill all of those together, not just hand tracking.
                // On failure, fail safe: hide the markers and clear any latched pinch state, same as
                // the tracking-unavailable `else` branch below.
                runCatching {
                    val handPose = viewModel.handTrackingProvider?.latestData?.right
                    if (handPose != null) {
                        val thumbTip = handPose.joint(HandJoint.Index.THUMB_TIP)
                        val indexTip = handPose.joint(HandJoint.Index.INDEX_TIP)
                        viewModel.thumbTipEntity.components[TransformComponent::class.java]
                            ?.setPosition(thumbTip.position)
                        viewModel.indexTipEntity.components[TransformComponent::class.java]
                            ?.setPosition(indexTip.position)
                        viewModel.thumbTipEntity.enabled = true
                        viewModel.indexTipEntity.enabled = true
                        // Hand tracking has proven itself functional at least once this session - see
                        // the 5s auto-hide LaunchedEffect above, which refuses to hide the HUD (the
                        // only way back to the main window) until this has happened at least once.
                        hasEverTrackedHand = true

                        val distance = Vector3.distance(thumbTip.position, indexTip.position)
                        val pinch = PinchDetector.update(distance, wasPinching)
                        wasPinching = pinch.isPinching
                        // Priority rule, per user request: the control panel gets first claim on a
                        // pinch, and only a pinch the panel has no use for falls through to
                        // show/hide. PICO hand input is "ray + pinch = click", so without this every
                        // press of a HUD button also fired this toggle and the panel vanished
                        // mid-interaction.
                        //
                        // "The panel wants it" is read as "the pointer is currently over the panel"
                        // (isHudPointerOver, reported by PlaybackHud's root hoverable). Pointing at
                        // the panel and pinching therefore presses whatever is under the ray and
                        // leaves visibility alone; pinching anywhere else still toggles, which also
                        // gives an easy way to dismiss the panel without hunting for a button.
                        //
                        // Failure mode is deliberately the safe one: if this hover signal never
                        // arrives, isHudPointerOver stays false and behaviour is exactly what it was
                        // before this change, rather than a HUD that can no longer be summoned.
                        if (pinch.justEngaged && !isHudPointerOver) {
                            viewModel.toggleHudVisibility()
                        }
                    } else {
                        // Right hand not currently tracked (out of frame, tracking lost, or
                        // HandTrackingProvider.start() hasn't completed on the background thread yet) -
                        // hide the markers rather than leaving them at a stale position, and reset
                        // wasPinching so a stale pinch state can't produce a spurious rising edge the
                        // instant tracking resumes.
                        viewModel.thumbTipEntity.enabled = false
                        viewModel.indexTipEntity.enabled = false
                        wasPinching = false
                    }
                }.onFailure {
                    viewModel.thumbTipEntity.enabled = false
                    viewModel.indexTipEntity.enabled = false
                    wasPinching = false
                }

                val attachments = spatialAttachments ?: return@withFrameNanos
                val subtitleEntity = attachments.entity(SUBTITLE_ATTACHMENT_ID)
                val hmdPose = viewModel.hmdTrackingProvider?.latestData?.hmdPose
                if (subtitleEntity != null && hmdPose != null) {
                    applySubtitleFollow(subtitleEntity, subtitleFollow, hmdPose, deltaTime)
                }
                subtitleEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.currentSubtitleText.isNotEmpty()

                val hudEntity = attachments.entity(HUD_ATTACHMENT_ID)
                // A FIXED tilt, deliberately not following the head: per user request the panel must
                // hold one orientation while it is up, angled so it is read from above looking down.
                //
                // Applied here, per frame, rather than once in `initial` next to this panel's
                // setPosition - that is the part that actually matters. A rotation set in `initial`
                // never took effect at all: on device the top edge leaned toward the user at
                // pitch = -22f AND at +22f, and two opposite X rotations cannot look identical, so
                // both reports were the same untouched default orientation and each "sign fix" was
                // a no-op. The subtitle panel, which has always been oriented correctly, likewise
                // sets its rotation from this loop after the entity is live (applySubtitleFollow).
                // Keep this assignment in the frame loop even though the value is constant.
                if (hudEntity != null) {
                    hudEntity.components[TransformComponent::class.java]
                        ?.setEulerAngles(EulerAngles(pitch = HUD_PITCH_DEGREES, yaw = 0f, roll = 0f))
                }
                hudEntity?.enabled =
                    !viewModel.showLoadingOverlay && viewModel.isHudVisible
            }
        }
    }

    PicoTheme {
        SpatialView(
            attachments = {
                AttachmentPanel(id = LOADING_ATTACHMENT_ID) {
                    LoadingErrorAttachment(viewModel.manager.state)
                }
                AttachmentPanel(id = HUD_ATTACHMENT_ID) {
                    PlaybackHud(
                        state = viewModel.manager.state,
                        currentProjection = viewModel.currentProjection.value,
                        currentStereoMode = viewModel.currentStereoMode.value,
                        currentEnvironment = viewModel.currentEnvironment.value,
                        currentPositionMs = viewModel.currentPositionMs,
                        durationMs = viewModel.durationMs,
                        isMuted = viewModel.isMuted,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSelectEnvironment = { viewModel.switchEnvironment(it) },
                        onCorrectFormat = { projection, stereoMode ->
                            viewModel.correctFormat(projection, stereoMode)
                        },
                        onSeek = { viewModel.seekTo(it) },
                        onToggleMute = { viewModel.toggleMute() },
                        onReturnToMainWindow = { returnToMainWindow() },
                        onPointerOverChange = { isHudPointerOver = it },
                    )
                }
                AttachmentPanel(id = SUBTITLE_ATTACHMENT_ID) {
                    SubtitleAttachment(text = viewModel.currentSubtitleText)
                }
            },
            initial = { content, attachments ->
                spatialAttachments = attachments
                content.addEntity(viewModel.screenEntity)
                content.addEntity(viewModel.sphereEntity)
                content.addEntity(viewModel.hemisphereEntity)
                content.addEntity(viewModel.cinemaEnvironmentEntity)
                content.addEntity(viewModel.starrySkyEnvironmentEntity)
                content.addEntity(viewModel.seasideEnvironmentEntity)
                // No parent, positioned fresh every frame from live hand-tracking data (Task 8) -
                // same reasoning as the HUD/loading panel not being parented to a video entity.
                content.addEntity(viewModel.thumbTipEntity)
                content.addEntity(viewModel.indexTipEntity)

                // Independent of screenEntity/sphereEntity/hemisphereEntity on purpose: only one
                // of the three is `enabled` at a time (children of a disabled entity are hidden
                // too) - so the HUD/loading overlay must NOT be parented to any of them, or it
                // would disappear whenever that one is switched off. Fixed in front of the
                // default spawn point works for all three.
                attachments.entity(LOADING_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 1.5f, -1.5f))
                    }
                    content.addEntity(this)
                }

                attachments.entity(HUD_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 0.9f, -1.5f))
                        // Rotation is deliberately NOT set here - see the frame loop above, which
                        // orients this panel toward the live HMD pose every frame. Setting it here
                        // (the previous approach) had no observable effect at all: on device the
                        // panel's top edge leaned toward the user at pitch = -22f AND at pitch =
                        // +22f, which two opposite X rotations cannot both produce. Both "wrong
                        // sign" reports were really the same untouched default orientation, which
                        // is why flipping the sign twice never changed anything.
                        //
                        // The old approach set EulerAngles(pitch = ±22f) right here, and the sign
                        // was "fixed" twice (once by derivation, once by on-device observation)
                        // without ever changing what the user saw. Rather than guess a third time,
                        // the panel now derives its orientation from the live HMD pose using the
                        // exact call that already works for the subtitle panel - so it self-corrects
                        // for whichever local axis this quad type actually treats as front, a fact
                        // that could never be confirmed from the SDK (the native
                        // android.view.ViewLink/ViewAttachment path has no available source).
                    }
                    content.addEntity(this)
                }

                // Subtitle panel: position is driven every frame by applySubtitleFollow() in the
                // LaunchedEffect loop above (lagged position + rotation follow, ported from
                // StoryPico's MoveWithCameraComponent), not a fixed TransformComponent position
                // like loading/hud - see ecs/SubtitleFollowComponent.kt. The fixed position set
                // here is only a fallback for the frames before the first real HMD pose arrives
                // (or for when HMDTrackingProvider.start() fails entirely, e.g. on the emulator,
                // which lacks real head-pose tracking) - applySubtitleFollow() takes over as soon
                // as hmdTrackingProvider.latestData is non-null.
                //
                // SubtitleFollowComponent is a plain Kotlin state holder, NOT registered through
                // components.set() - the native ECS layer only recognizes its own built-in
                // Component subtypes (TransformComponent, ModelComponent, ...) and silently
                // rejects arbitrary custom ones ("component Component is not supported" in
                // logcat), which would make a components.get() lookup return null every frame.
                attachments.entity(SUBTITLE_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]?.apply {
                        setPosition(Vector3(0f, 1.2f, -1.5f))
                    }
                    content.addEntity(this)
                }
            },
            update = { _, attachments ->
                // Event-driven, not per-frame - re-runs whenever showLoadingOverlay's own reads
                // (manager.state / hasFirstFrameRendered) change. HUD's `.enabled` used to be written
                // here too, but it now has a second condition (isHudVisible) that must be re-checked
                // every frame (5s auto-hide timer, pinch toggle) - moved to the per-frame
                // LaunchedEffect loop above so there's exactly one writer for that property.
                attachments.entity(LOADING_ATTACHMENT_ID)?.enabled = viewModel.showLoadingOverlay
            },
        )
    }
}
