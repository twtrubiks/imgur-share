package com.twtrubiks.imgurshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twtrubiks.imgurshare.upload.Thumbnails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 用和手機相機照片一樣的圖產生縮圖：4032x3024，直拍時像素是橫的、靠 EXIF 的 Orientation 轉正。
 * DemoRecordingTest 的展示圖是沒有 EXIF 的小正方形，測不到這些情況
 * （Android 9 的 ImageDecoder 遇到轉 90°/270° 的圖會解碼失敗，就是這樣漏掉的）。
 *
 * 原圖四個角塗不同顏色（左上紅、右上綠、左下藍、右下黃），從縮圖四個角的顏色就看得出有沒有轉對。
 * 縮圖另存到 screenshots/thumb-*.png，`scripts/ci/record.sh` 會一起 pull 回來。
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "thumbnails-test")
    private val thumbnails = Thumbnails(File(workDir, "thumbs"))

    // DemoRecordingTest 先跑，開頭會清空這個資料夾，所以這裡不清
    private val outputDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        workDir.mkdirs()
        outputDir.mkdirs()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun landscapeWithoutExif() = check("landscape", 4032, 3024, null, 320, 240, listOf(RED, GREEN, BLUE, YELLOW))

    @Test
    fun portraitPixelsWithoutExif() = check("portrait", 3024, 4032, null, 240, 320, listOf(RED, GREEN, BLUE, YELLOW))

    /** 直拍最常見的情況：像素是橫的，EXIF 說要順時針轉 90° */
    @Test
    fun rotate90() = check("rotate90", 4032, 3024, ExifInterface.ORIENTATION_ROTATE_90, 240, 320, listOf(BLUE, RED, YELLOW, GREEN))

    @Test
    fun rotate180() = check("rotate180", 4032, 3024, ExifInterface.ORIENTATION_ROTATE_180, 320, 240, listOf(YELLOW, BLUE, GREEN, RED))

    @Test
    fun rotate270() = check("rotate270", 4032, 3024, ExifInterface.ORIENTATION_ROTATE_270, 240, 320, listOf(GREEN, YELLOW, RED, BLUE))

    // 鏡像的方向：有些前鏡頭會寫這些值
    @Test
    fun flipHorizontal() = check("flipH", 4032, 3024, ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 320, 240, listOf(GREEN, RED, YELLOW, BLUE))

    @Test
    fun flipVertical() = check("flipV", 4032, 3024, ExifInterface.ORIENTATION_FLIP_VERTICAL, 320, 240, listOf(BLUE, YELLOW, RED, GREEN))

    @Test
    fun transpose() = check("transpose", 4032, 3024, ExifInterface.ORIENTATION_TRANSPOSE, 240, 320, listOf(RED, BLUE, GREEN, YELLOW))

    @Test
    fun transverse() = check("transverse", 4032, 3024, ExifInterface.ORIENTATION_TRANSVERSE, 240, 320, listOf(YELLOW, GREEN, BLUE, RED))

    /** 截圖之類的 PNG，沒有 EXIF */
    @Test
    fun png() = check("png", 1080, 1440, null, 240, 320, listOf(RED, GREEN, BLUE, YELLOW), Bitmap.CompressFormat.PNG)

    /** [expectedCorners]：縮圖左上、右上、左下、右下的顏色 */
    private fun check(
        name: String,
        width: Int,
        height: Int,
        orientation: Int?,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedCorners: List<String>,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ) {
        val source = File(workDir, if (format == Bitmap.CompressFormat.PNG) "$name.png" else "$name.jpg")
        val bitmap = quadrants(width, height)
        source.outputStream().use { bitmap.compress(format, 95, it) }
        bitmap.recycle()
        if (orientation != null) {
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }

        val id = "test$name"
        val started = SystemClock.elapsedRealtime()
        val created = thumbnails.create(source, id)
        Log.i(TAG, "$name：原圖 ${source.length()} bytes，產生縮圖 ${SystemClock.elapsedRealtime() - started} ms")
        assertTrue("$name：沒有產生縮圖，原因看 logcat-thumbnails.txt 的 Thumbnails tag", created)

        // 和首頁一樣用 BitmapFactory 讀
        val thumb = checkNotNull(BitmapFactory.decodeFile(thumbnails.file(id).path)) { "$name：讀不到縮圖檔" }
        File(outputDir, "thumb-$name.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertEquals("$name：縮圖尺寸", "${expectedWidth}x$expectedHeight", "${thumb.width}x${thumb.height}")
        val corners = listOf(
            thumb.getPixel(thumb.width / 4, thumb.height / 4),
            thumb.getPixel(thumb.width * 3 / 4, thumb.height / 4),
            thumb.getPixel(thumb.width / 4, thumb.height * 3 / 4),
            thumb.getPixel(thumb.width * 3 / 4, thumb.height * 3 / 4),
        ).map(::colorName)
        assertEquals("$name：縮圖四個角的顏色（左上、右上、左下、右下）", expectedCorners, corners)
    }

    private fun quadrants(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val w = width / 2f
        val h = height / 2f
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.GREEN
        canvas.drawRect(w, 0f, width.toFloat(), h, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, h, w, height.toFloat(), paint)
        paint.color = Color.YELLOW
        canvas.drawRect(w, h, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun colorName(pixel: Int): String {
        val high = { c: Int -> c > 180 }
        val low = { c: Int -> c < 80 }
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when {
            high(r) && low(g) && low(b) -> RED
            low(r) && high(g) && low(b) -> GREEN
            low(r) && low(g) && high(b) -> BLUE
            high(r) && high(g) && low(b) -> YELLOW
            else -> "rgb($r,$g,$b)"
        }
    }

    private companion object {
        const val TAG = "ThumbnailsTest"
        const val RED = "紅"
        const val GREEN = "綠"
        const val BLUE = "藍"
        const val YELLOW = "黃"
    }
}
