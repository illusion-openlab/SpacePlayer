package tech.illusion.spaceplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.illusion.spaceplayer.library.fakes.InMemoryKeyValueStore

class PlaybackHistoryStoreTest {

    @Test
    fun `entries sorted by most recently played first`() {
        val store = PlaybackHistoryStore(InMemoryKeyValueStore())
        store.recordPlayed("content://media/1", timestampMs = 1000L)
        store.recordPlayed("content://media/2", timestampMs = 2000L)
        val entries = store.recentEntriesDescending()
        assertEquals(2, entries.size)
        assertEquals(2000L, entries[0].second)
        assertEquals(1000L, entries[1].second)
    }

    @Test
    fun `replaying the same uri dedups to the latest timestamp`() {
        val store = PlaybackHistoryStore(InMemoryKeyValueStore())
        store.recordPlayed("content://media/1", timestampMs = 1000L)
        store.recordPlayed("content://media/1", timestampMs = 5000L)
        val entries = store.recentEntriesDescending()
        assertEquals(1, entries.size)
        assertEquals(5000L, entries[0].second)
    }
}
