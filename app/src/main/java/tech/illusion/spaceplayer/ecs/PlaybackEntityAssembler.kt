package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector3

object PlaybackEntityAssembler {

    fun assembleScreenEntity(
        entity: Entity,
        player: CypressMediaPlayer,
        widthMeters: Float,
        heightMeters: Float,
        dimensionMode: VideoDimensionMode,
    ) {
        val mesh = MeshResource.createVideoPanel(widthMeters, heightMeters, 0.05f)
        check(mesh.valid) { "createVideoPanel returned an invalid mesh" }
        val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.BACK)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
        // Entity() already carries a default TransformComponent - components.set() with a new
        // instance is rejected ("component already exists") and silently no-ops. Mutate the
        // existing one instead, the same way the pico-cli stage template's HomeStage.kt does it
        // for its box model. Without this the panel sits at the world origin, which is at/behind
        // the user's spawn point inside a Stage (unlike a WindowContainer, which the system
        // positions for readability automatically) - and is therefore invisible.
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(Vector3(0f, 1.5f, -2f))
        }
    }

    /**
     * @param horizontalFovDegrees 360f for full 360° panoramic video, 180f for 180° hemisphere.
     * `MeshResource` has no `createHemisphere`, so both cases go through the same hand-built
     * mesh generator (ported from the sibling StoryPico project's `MeshGenerator`) parameterized
     * by horizontal sweep - see `MeshGenerator.generateVideoSphere` for how the 180 case reduces
     * to roughly half the vertices/triangles of the 360 case, not a full sphere with half the
     * texture blacked out.
     */
    fun assembleSphereEntity(
        entity: Entity,
        player: CypressMediaPlayer,
        radiusMeters: Float,
        horizontalFovDegrees: Float,
        dimensionMode: VideoDimensionMode,
    ) {
        val mesh = MeshGenerator.generateVideoSphere(
            radius = radiusMeters,
            horizontalFov = horizontalFovDegrees,
        )
        checkNotNull(mesh) { "generateVideoSphere failed, see logcat tag MeshGenerator" }
        check(mesh.valid) { "generateVideoSphere returned an invalid mesh" }
        // NONE: MeshGenerator's vertex normals already point inward (toward the sphere centre),
        // matching StoryPico's proven-working combination - no face culling needed.
        val material = VideoMaterial(BlendingMode.OPAQUE, dimensionMode, MaterialCullingMode.NONE)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
        // Sphere is centered on the user by design (radiusMeters chosen so the surface surrounds
        // the default spawn point) - world origin is correct here, unlike the flat screen panel.
    }

    /**
     * Environment skybox: same "big inward-facing sphere" mesh as a video sphere, but textured
     * as a static image via `UnlitMaterial` instead of `VideoMaterial` + `CypressMediaPlayer` -
     * ported from the sibling StoryPico project's `SkyboxPlayableEntity` (`MaterialCullingMode.BACK`
     * there, not `NONE` - StoryPico's proven combination for `UnlitMaterial` skyboxes specifically,
     * kept as-is rather than reusing the video sphere's culling mode).
     */
    fun assembleEnvironmentEntity(
        entity: Entity,
        textureAssetPath: String,
        radiusMeters: Float,
    ) {
        val mesh = MeshGenerator.generateVideoSphere(radius = radiusMeters, horizontalFov = 360f)
        checkNotNull(mesh) { "generateVideoSphere failed, see logcat tag MeshGenerator" }
        check(mesh.valid) { "generateVideoSphere returned an invalid mesh" }
        val texture = TextureResource.load(textureAssetPath, LoadType.FROM_ASSETS)
        val material = UnlitMaterial.create().apply {
            setBaseColorTexture(texture)
            setCullingMode(MaterialCullingMode.BACK)
        }
        entity.components.set(ModelComponent(mesh, material))
    }
}
