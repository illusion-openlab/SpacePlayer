package tech.illusion.spaceplayer.ecs

import android.util.Log
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ResourceLoadingException
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "MeshGenerator"

/**
 * Builds a sphere mesh suitable for VR180 / VR360 playback, ported from StoryPico's
 * (sibling project, same spatialBom 0.13.3) `MeshGenerator.generateVideoSphere` - the PICO
 * Spatial SDK's `MeshResource` has no `createHemisphere`, only full-primitive factories
 * (createSphere/createBox/.../createVideoPanel), so a 180° hemisphere has to be hand-built via
 * `createWithMeshModel` with a longitude sweep scaled by `horizontalFov`.
 *
 * Vertex normals point toward the sphere centre (inverse of the position vector) so the inside
 * of the sphere renders; combined with `MaterialCullingMode.NONE` on the VideoMaterial, both
 * triangle winding orders draw and no face culling is needed.
 */
object MeshGenerator {

    fun generateVideoSphere(
        radius: Float = 10f,
        horizontalFov: Float,
        verticalFov: Float = 180f,
        segment: Int = 60,
    ): MeshResource? {
        val ringCount = segment + 1
        val vertexCount = ringCount * ringCount

        val verticalScale = verticalFov / 180f
        val verticalOffset = (1f - verticalScale) / 2f
        val horizontalScale = horizontalFov / 360f
        // +0.25 makes the sphere "open toward the front" so VR180 faces +Z.
        val horizontalOffset = (1f - horizontalScale) / 2f + 0.25f

        val positions = ArrayList<Vector3>(vertexCount)
        val normals = ArrayList<Vector3>(vertexCount)
        val uvs = ArrayList<Vector2>(vertexCount)

        val pi = PI.toFloat()
        for (y in 0..segment) {
            val angle1 = (pi * (y.toFloat() / segment)) * verticalScale + verticalOffset * pi
            val sin1 = sin(angle1)
            val cos1 = cos(angle1)
            for (x in 0..segment) {
                val angle2 = (pi * 2f * (x.toFloat() / segment)) * horizontalScale +
                    horizontalOffset * pi * 2f
                val sin2 = sin(angle2)
                val cos2 = cos(angle2)

                val px = sin1 * cos2 * radius
                val py = cos1 * radius
                val pz = sin1 * sin2 * radius
                positions.add(Vector3(px, py, pz))

                val len = sqrt(px * px + py * py + pz * pz)
                if (len > 0f) {
                    normals.add(Vector3(-px / len, -py / len, -pz / len))
                } else {
                    normals.add(Vector3(0f, 0f, 0f))
                }

                uvs.add(Vector2(x.toFloat() / segment, 1f - y.toFloat() / segment))
            }
        }

        val triangles = ArrayList<Int>(segment * segment * 6)
        for (y in 0 until segment) {
            for (x in 0 until segment) {
                val current = x + y * ringCount
                val next = current + ringCount
                triangles.add(current + 1)
                triangles.add(current)
                triangles.add(next + 1)
                triangles.add(next + 1)
                triangles.add(current)
                triangles.add(next)
            }
        }

        return createMeshFromModel(
            MeshModel(
                positions = positions,
                triangleIndices = triangles,
                normals = normals,
                uv0 = uvs,
            ),
            "videoPlayerSphere",
        )
    }

    private fun createMeshFromModel(model: MeshModel, name: String): MeshResource? {
        return try {
            MeshResource.createWithMeshModel(model, name = name)
        } catch (e: ResourceLoadingException) {
            Log.e(TAG, "$name failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$name invalid: ${e.message}")
            null
        }
    }
}
