package tech.illusion.spaceplayer.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class RawVideoRecord(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * "视频资源库"= MediaStore 扫描结果里排除 Download 目录的部分；"下载"= 只看 Download 目录。
 * 见设计稿第 2 节"文件库管理"。
 */
class VideoLibraryRepository(private val context: Context) {

    fun queryLibrary(): List<RawVideoRecord> = query(downloadsOnly = false)

    fun queryDownloads(): List<RawVideoRecord> = query(downloadsOnly = true)

    private fun query(downloadsOnly: Boolean): List<RawVideoRecord> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val records = mutableListOf<RawVideoRecord>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathColumn) ?: ""
                val isInDownloads = relativePath.startsWith("Download/")
                if (isInDownloads != downloadsOnly) continue
                val id = cursor.getLong(idColumn)
                records += RawVideoRecord(
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    displayName = cursor.getString(nameColumn) ?: "",
                    durationMs = cursor.getLong(durationColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                )
            }
        }
        return records
    }
}
