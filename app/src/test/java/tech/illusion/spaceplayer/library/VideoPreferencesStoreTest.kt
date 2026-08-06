package tech.illusion.spaceplayer.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tech.illusion.spaceplayer.library.fakes.InMemoryKeyValueStore
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class VideoPreferencesStoreTest {

    private fun fakeUri(value: String): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(value)
        return uri
    }

    @Test
    fun `unknown uri returns all-null defaults`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val prefs = store.get(fakeUri("content://media/1"))
        assertNull(prefs.projectionOverride)
        assertNull(prefs.stereoModeOverride)
        assertNull(prefs.preferredEnvironment)
    }

    @Test
    fun `format override round-trips`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/2")
        store.setFormatOverride(uri, Projection.SPHERE_360, StereoMode.SIDE_BY_SIDE)
        val prefs = store.get(uri)
        assertEquals(Projection.SPHERE_360, prefs.projectionOverride)
        assertEquals(StereoMode.SIDE_BY_SIDE, prefs.stereoModeOverride)
    }

    @Test
    fun `setting preferred environment preserves prior format override`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/3")
        store.setFormatOverride(uri, Projection.HEMISPHERE_180, StereoMode.TOP_AND_DOWN)
        store.setPreferredEnvironment(uri, Environment.SEASIDE)
        val prefs = store.get(uri)
        assertEquals(Projection.HEMISPHERE_180, prefs.projectionOverride)
        assertEquals(Environment.SEASIDE, prefs.preferredEnvironment)
    }

    @Test
    fun `subtitle uri round-trips as a string`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/4")
        val subtitleUri = fakeUri("content://com.android.externalstorage.documents/document/primary%3Amovie.srt")
        store.setSubtitleUri(uri, subtitleUri)
        val prefs = store.get(uri)
        assertEquals("content://com.android.externalstorage.documents/document/primary%3Amovie.srt", prefs.subtitleUri)
    }

    @Test
    fun `setting subtitle uri preserves prior format override`() {
        val store = VideoPreferencesStore(InMemoryKeyValueStore())
        val uri = fakeUri("content://media/5")
        store.setFormatOverride(uri, Projection.FLAT, StereoMode.MONO)
        store.setSubtitleUri(uri, fakeUri("content://media/subtitle.srt"))
        val prefs = store.get(uri)
        assertEquals(Projection.FLAT, prefs.projectionOverride)
        assertEquals("content://media/subtitle.srt", prefs.subtitleUri)
    }
}
