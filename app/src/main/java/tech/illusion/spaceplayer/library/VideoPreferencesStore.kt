package tech.illusion.spaceplayer.library

import android.net.Uri
import tech.illusion.spaceplayer.library.storage.KeyValueStore
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

data class VideoPreferences(
    val projectionOverride: Projection? = null,
    val stereoModeOverride: StereoMode? = null,
    val preferredEnvironment: Environment? = null,
)

/**
 * 每个视频的手动格式覆盖 + 上次使用的沉浸环境，按 `uri.toString()` 做 key。
 *
 * 序列化故意不用 `org.json.JSONObject`：Android 的单元测试 stub jar 没有真实的 `org.json` 实现
 * （不接入 Robolectric 的话会在纯 JVM 单测里抛 `RuntimeException`），这里手写一个不碰任何 Android
 * 框架类的 `key=value;key=value` 格式，纯 Kotlin stdlib，单测和真机行为完全一致。
 */
class VideoPreferencesStore(private val storage: KeyValueStore) {

    fun get(uri: Uri): VideoPreferences {
        val raw = storage.get(uri.toString()) ?: return VideoPreferences()
        val fields = raw.split(FIELD_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(ENTRY_SEPARATOR, limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        return VideoPreferences(
            projectionOverride = fields[KEY_PROJECTION]?.let(Projection::valueOf),
            stereoModeOverride = fields[KEY_STEREO]?.let(StereoMode::valueOf),
            preferredEnvironment = fields[KEY_ENVIRONMENT]?.let(Environment::valueOf),
        )
    }

    fun setFormatOverride(uri: Uri, projection: Projection, stereoMode: StereoMode) {
        save(uri, get(uri).copy(projectionOverride = projection, stereoModeOverride = stereoMode))
    }

    fun setPreferredEnvironment(uri: Uri, environment: Environment) {
        save(uri, get(uri).copy(preferredEnvironment = environment))
    }

    private fun save(uri: Uri, prefs: VideoPreferences) {
        val fields = buildList {
            prefs.projectionOverride?.let { add("$KEY_PROJECTION$ENTRY_SEPARATOR${it.name}") }
            prefs.stereoModeOverride?.let { add("$KEY_STEREO$ENTRY_SEPARATOR${it.name}") }
            prefs.preferredEnvironment?.let { add("$KEY_ENVIRONMENT$ENTRY_SEPARATOR${it.name}") }
        }
        storage.put(uri.toString(), fields.joinToString(FIELD_SEPARATOR))
    }

    private companion object {
        const val FIELD_SEPARATOR = ";"
        const val ENTRY_SEPARATOR = "="
        const val KEY_PROJECTION = "projection"
        const val KEY_STEREO = "stereo"
        const val KEY_ENVIRONMENT = "environment"
    }
}
