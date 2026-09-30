package roro.stellar.yuehong.payloadbuilder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 将构建产物保存到系统 Download；Android 9 退回应用可访问的下载目录。 */
object DownloadStore {

    fun save(context: Context, name: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(directory, name)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return file.absolutePath
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建 Download 文件：$name")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("无法写入 Download 文件：$name")
        } finally {
            val completed = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, completed, null, null)
        }
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            name,
        ).absolutePath
    }

    private fun mimeType(name: String): String = when {
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".h", ignoreCase = true) -> "text/plain"
        else -> "application/octet-stream"
    }
}
