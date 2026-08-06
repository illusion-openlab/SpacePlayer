package tech.illusion.spaceplayer.library

import android.net.Uri

data class PlaybackHistoryEntry(val videoUri: Uri, val lastPlayedAt: Long)
