package tech.illusion.spaceplayer.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCueLookupTest {
    private val cues = listOf(
        SubtitleCue(1000L, 4000L, "First"),
        SubtitleCue(5000L, 8000L, "Second"),
    )

    @Test
    fun `before first cue returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 500L))
    }

    @Test
    fun `during a cue returns its text`() {
        assertEquals("First", SubtitleCueLookup.textAt(cues, 2000L))
    }

    @Test
    fun `exactly at cue boundaries is inclusive`() {
        assertEquals("First", SubtitleCueLookup.textAt(cues, 1000L))
        assertEquals("First", SubtitleCueLookup.textAt(cues, 4000L))
    }

    @Test
    fun `gap between cues returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 4500L))
    }

    @Test
    fun `after last cue returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(cues, 9000L))
    }

    @Test
    fun `empty cue list always returns empty string`() {
        assertEquals("", SubtitleCueLookup.textAt(emptyList(), 1000L))
    }
}
