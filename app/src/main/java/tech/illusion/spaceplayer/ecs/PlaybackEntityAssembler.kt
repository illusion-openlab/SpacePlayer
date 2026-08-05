package tech.illusion.spaceplayer.ecs

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
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
}
