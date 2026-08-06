package tech.illusion.spaceplayer.library

import tech.illusion.spaceplayer.library.storage.KeyValueStore

/**
 * 去重取最新一条：同一 uriKey 多次播放，[KeyValueStore.put] 用同一个 key 覆盖，天然去重。
 *
 * 故意全程用 `String` 而不是 `Uri`：`Uri.parse()` 是 Android 静态方法，在纯 JVM 单元测试环境
 * （这个项目没有接入 Robolectric）里会抛 `RuntimeException`。把 `uriKey`（调用方传入
 * `uri.toString()`）↔ 真实 [PlaybackHistoryEntry.videoUri] 的转换留给调用方（UI 层，不参与单测，
 * 和 [VideoLibraryRepository]/`PlaybackManager` 触碰 Android 框架但不写单测是同一个约定）。
 */
class PlaybackHistoryStore(private val storage: KeyValueStore) {

    fun recordPlayed(uriKey: String, timestampMs: Long) {
        storage.put(uriKey, timestampMs.toString())
    }

    /** 按最近播放时间倒序返回 (uriKey, lastPlayedAt)；uriKey → 真实 `Uri` 的转换交给调用方。 */
    fun recentEntriesDescending(): List<Pair<String, Long>> =
        storage.all()
            .mapNotNull { (uriKey, value) -> value.toLongOrNull()?.let { uriKey to it } }
            .sortedByDescending { it.second }
}
