package com.twtrubiks.imgurshare.upload

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 上傳紀錄的本機縮圖。放在 noBackupFilesDir：不佔 Auto Backup 的 25 MB 額度
 * （超過額度整個備份會失敗，連 deletehash 都備份不到），還原後沒有縮圖就顯示空白。
 */
class Thumbnails(private val dir: File) {

    fun file(imgurId: String) = File(dir, "${imgurId.filter(Char::isLetterOrDigit)}.jpg")

    /** 盡力而為：解不開的格式就不產生縮圖 */
    fun create(source: File, imgurId: String) {
        try {
            // ImageDecoder 會套用 EXIF 的旋轉方向（API 28 起）
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val scale = SIZE.toFloat() / min(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            dir.mkdirs()
            file(imgurId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun delete(imgurId: String) {
        file(imgurId).delete()
    }

    private companion object {
        const val SIZE = 240
    }
}
