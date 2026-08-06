package tech.illusion.spaceplayer.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    @Test
    fun `single cue with single line of text`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
    }

    @Test
    fun `multi-line cue text is joined with newline`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Line one
            Line two
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Line one\nLine two", cues[0].text)
    }

    @Test
    fun `multiple cues separated by blank lines`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            First

            2
            00:00:05,500 --> 00:00:08,200
            Second
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(5500L, cues[1].startMs)
        assertEquals(8200L, cues[1].endMs)
        assertEquals("Second", cues[1].text)
    }

    @Test
    fun `CRLF line endings are handled`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:04,000\r\nHello\r\n"
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Hello", cues[0].text)
    }

    @Test
    fun `leading UTF-8 BOM is stripped`() {
        val srt = "﻿1\n00:00:01,000 --> 00:00:04,000\nHello"
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
    }

    @Test
    fun `block without a timestamp line is skipped`() {
        val srt = """
            not a real cue
            just some text

            1
            00:00:01,000 --> 00:00:04,000
            Real cue
        """.trimIndent()
        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Real cue", cues[0].text)
    }

    @Test
    fun `empty input produces no cues`() {
        assertTrue(SrtParser.parse("").isEmpty())
    }

    @Test
    fun `timestamps convert hours minutes seconds milliseconds correctly`() {
        val srt = "1\n01:02:03,456 --> 01:02:05,000\nText"
        val cues = SrtParser.parse(srt)
        // 1h2m3.456s = 3723456 ms
        assertEquals(3_723_456L, cues[0].startMs)
    }
}
