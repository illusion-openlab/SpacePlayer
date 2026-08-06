package tech.illusion.spaceplayer.library.fakes

import tech.illusion.spaceplayer.library.storage.KeyValueStore

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun all(): Map<String, String> = map.toMap()
}
