package tech.illusion.spaceplayer.ecs

/**
 * @param isPinching Current pinch state after this update, with hysteresis applied.
 * @param justEngaged True only on the frame the state transitions from not-pinching to pinching -
 *   this is the rising edge that should drive a one-shot action (Task 8: toggling HUD visibility),
 *   not [isPinching] itself, which stays true for every frame the fingers remain close together.
 */
data class PinchResult(val isPinching: Boolean, val justEngaged: Boolean)

/**
 * Pinch detection from raw thumb-tip/index-tip distance - the SDK's `tracking` package exposes
 * only joint positions, no built-in gesture recognition (confirmed against
 * spatial-sdk_interaction_spatial-hand-pose.md and the com.pico.spatial.tracking.hand API
 * reference: only detectSpatialTapGesture()-style Compose pointer-input gestures exist there, not a
 * free-space "is the user pinching" signal).
 *
 * Uses two thresholds instead of one to avoid oscillating near a single cutoff: engage below
 * [ENGAGE_DISTANCE_METERS], release above [RELEASE_DISTANCE_METERS], hold the previous state
 * anywhere in between. The sibling StoryPico project's PlayerSpace.kt hand-menu gesture uses a
 * single 0.05m threshold with only a rising-edge check - fine for a low-frequency menu toggle, but
 * this detector drives a persistently-visible HUD, so the wider margin against jitter matters more
 * here.
 */
object PinchDetector {
    const val ENGAGE_DISTANCE_METERS = 0.025f
    const val RELEASE_DISTANCE_METERS = 0.040f

    fun update(distanceMeters: Float, wasPinching: Boolean): PinchResult {
        val isPinching = when {
            distanceMeters < ENGAGE_DISTANCE_METERS -> true
            distanceMeters > RELEASE_DISTANCE_METERS -> false
            else -> wasPinching
        }
        return PinchResult(isPinching = isPinching, justEngaged = isPinching && !wasPinching)
    }
}
