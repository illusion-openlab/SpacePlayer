package tech.illusion.spaceplayer.library.storage

import android.content.Context

class SharedPreferencesKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}
