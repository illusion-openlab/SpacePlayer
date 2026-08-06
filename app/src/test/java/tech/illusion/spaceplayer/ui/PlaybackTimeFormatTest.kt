package tech.illusion.spaceplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatTest {

    @Test
    fun `zero formats as 0-00`() {
        assertEquals("0:00", formatPlaybackTimestamp(0L))
    }

    @Test
    fun `seconds and minutes pad to two digits`() {
        assertEquals("0:05", formatPlaybackTimestamp(5_000L))
        assertEquals("1:05", formatPlaybackTimestamp(65_000L))
        assertEquals("9:59", formatPlaybackTimestamp(599_000L))
    }

    @Test
    fun `under an hour never shows an hour digit`() {
        assertEquals("59:59", formatPlaybackTimestamp(3_599_000L))
    }

    @Test
    fun `an hour or more adds the hour digit`() {
        assertEquals("1:00:00", formatPlaybackTimestamp(3_600_000L))
        assertEquals("1:32:07", formatPlaybackTimestamp(5_527_000L))
    }

    @Test
    fun `negative input clamps to zero instead of crashing`() {
        assertEquals("0:00", formatPlaybackTimestamp(-500L))
    }
}
