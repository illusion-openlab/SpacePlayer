package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Component
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDPose
import kotlin.math.sqrt

/**
 * Ported from StoryPico's `MoveWithCameraComponent` (same spatialBom 0.13.3, see
 * /Users/zohar/WorkSpace/Project/StoryProjects/StoryPico) - a plain state holder, *not* driven by
 * the ECS scheduler. [applySubtitleFollow] is called manually every frame from
 * `ImmersiveScene.kt`'s `SpatialView.update` block, reading the HMD pose from
 * `HMDTrackingProvider.latestData`.
 *
 * Deliberately simpler than StoryPico's original: that project has a general `TrackingManager`
 * driving many head-anchored entities with position and rotation updated by two separate call
 * sites. SpacePlayer only ever follows one entity (the subtitle panel), so position + rotation are
 * combined into the single [applySubtitleFollow] function below.
 */
class SubtitleFollowComponent(
    val relativePosition: Vector3 = Vector3(0f, -0.3f, -1.0f),
    val speed: Float = 5f,
    val minDistance: Float = 0.1f,
) : Component() {
    /** World-space position the entity is currently lerping toward. */
    var targetPosition: Vector3 = Vector3.ZERO

    /** Entity world position at the moment the target was last refreshed. */
    var lastPosition: Vector3 = Vector3.ZERO

    /** False until the first valid HMD frame has snapped the entity to its initial position. */
    var isInitialized: Boolean = false
}

/**
 * Lagged position (dead-zone gated lerp) + eased rotation toward the HMD pose. Mirrors StoryPico's
 * `TrackingManager.applyMoveWithCamera` (position) and the subtitle-specific rotation block in
 * `TrackingManager.processHMDTracking`, merged into one call since this project only follows one
 * entity.
 */
fun applySubtitleFollow(
    entity: Entity,
    component: SubtitleFollowComponent,
    pose: HMDPose,
    deltaTime: Float,
) {
    val transform = entity.components[TransformComponent::class.java] ?: return
    val parent = entity.getParent()

    val newWorldPos = pose.rotation.rotateVector(component.relativePosition) + pose.position
    val isFirstFrame = !component.isInitialized
    val currentWorldPos = if (isFirstFrame) {
        newWorldPos
    } else {
        parent?.convertPositionTo(transform.position, null) ?: transform.position
    }

    if (isFirstFrame || distanceBetween(newWorldPos, component.lastPosition) > component.minDistance) {
        component.targetPosition = newWorldPos
        component.lastPosition = currentWorldPos
    }

    val t = if (isFirstFrame) 1f else (component.speed * deltaTime).coerceIn(0f, 1f)
    val target = component.targetPosition
    val lerped = Vector3(
        currentWorldPos.x + (target.x - currentWorldPos.x) * t,
        currentWorldPos.y + (target.y - currentWorldPos.y) * t,
        currentWorldPos.z + (target.z - currentWorldPos.z) * t,
    )
    transform.setPosition(parent?.convertPositionFrom(lerped, null) ?: lerped)
    if (isFirstFrame) component.isInitialized = true

    val targetRotation = parent?.convertRotationFrom(pose.rotation, null) ?: pose.rotation
    transform.setQuaternion(slerpQuat(transform.quaternion, targetRotation, t))
}

/**
 * Normalised-lerp between two quaternions along the shortest arc. Adequate for the small per-frame
 * steps used here and avoids the trig of a full slerp - ported verbatim from StoryPico's
 * `TrackingManager.slerpQuat`. [t] is expected pre-clamped to [0,1]; `t = 1` yields (normalised)
 * [b], i.e. a snap.
 */
private fun slerpQuat(a: Quat, b: Quat, t: Float): Quat {
    val dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
    val bx: Float
    val by: Float
    val bz: Float
    val bw: Float
    if (dot < 0f) {
        bx = -b.x
        by = -b.y
        bz = -b.z
        bw = -b.w
    } else {
        bx = b.x
        by = b.y
        bz = b.z
        bw = b.w
    }
    val rx = a.x + t * (bx - a.x)
    val ry = a.y + t * (by - a.y)
    val rz = a.z + t * (bz - a.z)
    val rw = a.w + t * (bw - a.w)
    val len = sqrt((rx * rx + ry * ry + rz * rz + rw * rw).toDouble()).toFloat()
    return if (len > 0f) Quat(rx / len, ry / len, rz / len, rw / len) else a
}

private fun distanceBetween(a: Vector3, b: Vector3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
}
