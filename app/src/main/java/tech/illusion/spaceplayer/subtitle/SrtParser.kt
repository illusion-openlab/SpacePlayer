package tech.illusion.spaceplayer.subtitle

private val TIMESTAMP_LINE = Regex(
    """(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})""",
)

/**
 * Minimal SRT (SubRip) parser. Handles standard numbered blocks, CRLF/LF line endings, and a
 * leading UTF-8 BOM. Text is kept as-is including any embedded styling tags (<i>, {\an8}, etc.) -
 * this project does not parse or render such tags, matching the "no .ass-style effects" non-goal.
 */
object SrtParser {
    fun parse(content: String): List<SubtitleCue> {
        val normalized = content.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        for (block in blocks) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timestampLineIndex = lines.indexOfFirst { TIMESTAMP_LINE.containsMatchIn(it) }
            if (timestampLineIndex == -1) continue
            val match = TIMESTAMP_LINE.find(lines[timestampLineIndex]) ?: continue
            val groups = match.groupValues
            val startMs = toMillis(groups[1], groups[2], groups[3], groups[4])
            val endMs = toMillis(groups[5], groups[6], groups[7], groups[8])
            val text = lines.drop(timestampLineIndex + 1).joinToString("\n")
            if (text.isNotEmpty()) {
                cues += SubtitleCue(startMs, endMs, text)
            }
        }
        return cues
    }

    private fun toMillis(hours: String, minutes: String, seconds: String, millis: String): Long =
        hours.toLong() * 3_600_000L +
            minutes.toLong() * 60_000L +
            seconds.toLong() * 1_000L +
            millis.toLong()
}
