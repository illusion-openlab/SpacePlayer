package tech.illusion.spaceplayer.library.storage

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun all(): Map<String, String>
}
