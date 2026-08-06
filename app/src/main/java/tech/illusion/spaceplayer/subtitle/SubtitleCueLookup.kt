package tech.illusion.spaceplayer.subtitle

object SubtitleCueLookup {
    fun textAt(cues: List<SubtitleCue>, positionMs: Long): String {
        val cue = cues.firstOrNull { positionMs in it.startMs..it.endMs }
        return cue?.text ?: ""
    }
}
