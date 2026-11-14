package com.twtrubiks.imgurshare.upload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * 上傳紀錄的本機縮圖。放在 noBackupFilesDir：不佔 Auto Backup 的 25 MB 額度
 * （超過額度整個備份會失敗，連 deletehash 都備份不到），還原後沒有縮圖就顯示空白。
 *
 * 不用 ImageDecoder：Android 9 上 EXIF 要轉 90°/270° 的圖（手機直拍的照片幾乎都是）
 * 指定 setTargetSize 會丟 `IOException: getPixels failed with error invalid scale`。
 * 改用 BitmapFactory 縮小解碼（它不管 EXIF），再依 EXIF 的 Orientation 自己轉。
 */
class Thumbnails(private val dir: File) {

    fun file(imgurId: String) = File(dir, "${imgurId.filter(Char::isLetterOrDigit)}.jpg")

    /** 盡力而為：解不開的格式就不產生縮圖，原因寫在 logcat 的 [TAG]。回傳有沒有產生 */
    fun create(source: File, imgurId: String): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val shortSide = min(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) {
            Log.w(TAG, "無法產生縮圖：$imgurId，解不開的格式（${bounds.outMimeType}）")
            return false
        }
        // 2 的次方：JPEG 可以直接在解碼時縮小，不用先解出整張圖
        var sampleSize = 1
        while (shortSide / (sampleSize * 2) >= SIZE) sampleSize *= 2
        val sampled = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        if (sampled == null) {
            Log.w(TAG, "無法產生縮圖：$imgurId，解碼失敗（${bounds.outMimeType}）")
            return false
        }
        return try {
            val matrix = orientation(source)
            val scale = SIZE.toFloat() / min(sampled.width, sampled.height)
            if (scale < 1f) matrix.postScale(scale, scale)
            val thumb = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
            dir.mkdirs()
            file(imgurId).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumb.recycle()
            true
        } catch (e: IOException) {
            Log.w(TAG, "無法產生縮圖：$imgurId", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "無法產生縮圖：$imgurId", e)
            false
        } finally {
            sampled.recycle()
        }
    }

    fun delete(imgurId: String) {
        file(imgurId).delete()
    }

    /** EXIF 的 Orientation 轉成把像素轉正的 Matrix；讀不到就當成不用轉 */
    private fun orientation(source: File): Matrix {
        val orientation = try {
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }

    private companion object {
        const val TAG = "Thumbnails"
        const val SIZE = 240
    }
}
