package tech.illusion.spaceplayer.subtitle

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Looks for a same-directory, same-base-name `.srt` file next to a MediaStore-backed video, using
 * `MediaStore.Video.Media.DATA` to get a real filesystem path. `DATA` is deprecated since API 29
 * but still populated for rows this app itself queries from MediaStore - only verified against the
 * PICO emulator's local storage in this project, not confirmed reliable across all devices/storage
 * providers. SAF-imported videos (Stage 2's "其它" category) have no reliable sibling-file access
 * and always resolve to null here - those rely entirely on the manual override in
 * [tech.illusion.spaceplayer.library.VideoPreferencesStore].
 */
object SubtitleDiscovery {
    fun findSiblingSrt(context: Context, videoUri: Uri): Uri? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        val path = context.contentResolver.query(videoUri, projection, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (columnIndex == -1) null else cursor.getString(columnIndex)
            } ?: return null
        val videoFile = File(path)
        val parent = videoFile.parentFile ?: return null
        val srtFile = File(parent, "${videoFile.nameWithoutExtension}.srt")
        return if (srtFile.exists()) Uri.fromFile(srtFile) else null
    }
}
