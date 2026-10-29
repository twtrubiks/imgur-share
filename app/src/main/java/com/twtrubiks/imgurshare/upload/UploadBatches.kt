package com.twtrubiks.imgurshare.upload

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** batch 資料夾裡的一張圖。檔名是 `000.jpg` 這種序號，上傳時也用這個檔名，不會把原始檔名送到 imgur。 */
data class BatchFile(val index: Int, val file: File) {
    val mimeType: String? get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
}

/**
 * 等待上傳的圖放在 `cache/uploads/<batchId>/`。
 *
 * 分享進來的 `Uri` 讀取權限跟著 Activity，Activity 結束後背景工作可能就讀不到了，
 * 所以先複製到這裡再交給 WorkManager。上傳成功的檔案會刪掉，重試時只要傳剩下的。
 */
class UploadBatches(cacheDir: File, private val resolver: ContentResolver) {

    private val root = File(cacheDir, "uploads")
    private val cameraDir = File(cacheDir, "camera")

    /** 把 [uris] 複製成一個新的 batch。回傳 batch id 和成功複製的張數；一張都沒複製到時回傳 null。 */
    suspend fun create(uris: List<Uri>): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val batchId = UUID.randomUUID().toString()
        val dir = dir(batchId).apply { mkdirs() }
        var count = 0
        for (uri in uris) {
            // 只收 content://。file:// 可能被惡意 App 拿來指向這個 App 自己的私有檔案
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) continue
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "jpg"
            val target = File(dir, "%03d.%s".format(Locale.ROOT, count, ext))
            try {
                val input = resolver.openInputStream(uri) ?: continue
                input.use { target.outputStream().use(it::copyTo) }
                count++
            } catch (_: IOException) {
                target.delete()
            } catch (_: SecurityException) {
                target.delete()
            }
        }
        if (count == 0) {
            dir.deleteRecursively()
            null
        } else {
            batchId to count
        }
    }

    /** 拍照的暫存檔，透過 FileProvider（res/xml/file_paths.xml 的 camera/）交給系統相機寫入 */
    fun newCameraFile(): File {
        cameraDir.mkdirs()
        return File(cameraDir, "${System.currentTimeMillis()}.jpg")
    }

    /** 把拍好的照片搬進一個新的 batch */
    fun createFromCamera(photo: File): String? {
        if (!photo.exists() || photo.length() == 0L) {
            photo.delete()
            return null
        }
        val batchId = UUID.randomUUID().toString()
        val dir = dir(batchId).apply { mkdirs() }
        return if (photo.renameTo(File(dir, "000.jpg"))) batchId else null
    }

    /** 還沒上傳的檔案，依序號排序 */
    fun pendingFiles(batchId: String): List<BatchFile> =
        dir(batchId).listFiles().orEmpty()
            .mapNotNull { file -> file.nameWithoutExtension.toIntOrNull()?.let { BatchFile(it, file) } }
            .sortedBy { it.index }

    fun delete(batchId: String) {
        dir(batchId).deleteRecursively()
    }

    private fun dir(batchId: String) = File(root, batchId)
}
