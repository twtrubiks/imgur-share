package com.twtrubiks.imgurshare

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.twtrubiks.imgurshare.imgur.ImgurClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * 錄影用的展示流程：輸入 Client-ID → 從相簿選 3 張上傳 → 從「檔案」App 分享 2 張進來 → 刪除一張 → 設定頁。
 * CI 只跑這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 *
 * CI 沒有 Client-ID，錄影結果也是公開的，所以 imgur 換成 MockWebServer，Client-ID 用假的，
 * 展示用的圖是測試自己畫的。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val container = (context.applicationContext as ImgurShareApplication).container
    private val server = MockWebServer()

    // record.sh 會把這個資料夾 pull 回來，放進 media branch 的 screenshots/
    private val screenshotDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun setUp() {
        server.dispatcher = FakeImgur()
        server.start()
        // 測試跑在 App 的 process 裡，WorkManager 的上傳也會用這個 client
        container.imgurClient = ImgurClient(baseUrl = server.url("/3/"))
        runBlocking {
            container.settings.setClientId("")
            container.settings.setAutoCopy(true)
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun demo() {
        screenshotDir.deleteRecursively()
        screenshotDir.mkdirs()

        // record.sh 已經處理過開機時的 ANR，這裡再保險一次
        dismissAnrDialog()
        createDemoImages()

        ActivityScenario.launch(MainActivity::class.java).use {
            // CI 模擬器剛開機，第一個畫面可能要等比較久
            waitFor(By.text("先設定 imgur Client-ID"), timeout = 30_000)
            pause(2000)
            screenshot("01-setup")

            // 1. 輸入 Client-ID
            waitFor(By.res("client_id_input")).click()
            pause(800)
            waitFor(By.res("client_id_input")).text = "demo-client-id"
            pause()
            // 預設隱藏，按眼睛按鈕切換顯示
            waitFor(By.desc("顯示 Client-ID")).click()
            pause()
            screenshot("01b-client-id-visible")
            waitFor(By.desc("隱藏 Client-ID")).click()
            pause()
            waitFor(By.text("儲存")).click()
            device.wait(Until.gone(By.text("先設定 imgur Client-ID")), 10_000)
            pause(1500)
            screenshot("02-ready")

            // 2. 從相簿選 3 張。CI 的 AOSP 模擬器沒有 Photo Picker，會退回系統的檔案選擇器
            waitFor(By.text("從相簿選擇")).click()
            waitFor(By.pkg(DOCUMENTS_UI), timeout = 15_000)
            pause()
            openDemoFolder()
            selectInDocumentsUi("demo-1.jpg", "demo-2.jpg", "demo-3.jpg")
            screenshot("03-picker")
            waitFor(selectButton()).click()

            waitFor(By.text(Pattern.compile("上傳中.*")), timeout = 15_000)
            pause(800)
            screenshot("04-uploading")
            waitFor(By.text("3 張上傳完成，連結已複製到剪貼簿"), timeout = 30_000)
            pause(1500)
            screenshot("05-uploaded")
            assertEquals(3, clipboardText().lines().size)

            // 3. 從「檔案」App 分享 2 張進來
            device.pressHome()
            pause()
            val files = requireNotNull(context.packageManager.getLaunchIntentForPackage(DOCUMENTS_UI)) { "找不到「檔案」App" }
            // App 已經在背景，新版 Android 不讓背景 App 開 Activity，交給 shell 開
            device.executeShellCommand("am start -n ${files.component!!.flattenToShortString()}")
            waitFor(By.pkg(DOCUMENTS_UI), timeout = 15_000)
            pause()
            openDemoFolder()
            selectInDocumentsUi("demo-4.jpg", "demo-5.jpg")
            waitFor(By.desc(Pattern.compile("(?i)share"))).click()
            waitFor(By.text("Imgur Share"), timeout = 15_000)
            pause()
            screenshot("06-share-sheet")
            waitFor(By.text("Imgur Share")).click()

            waitFor(By.text("2 張上傳完成，連結已複製到剪貼簿"), timeout = 30_000)
            pause(1500)
            screenshot("07-shared")
            assertEquals(2, clipboardText().lines().size)

            // 4. 從 imgur 刪除最新的一張
            waitFor(By.desc("更多動作")).click()
            pause()
            waitFor(By.text("從 imgur 刪除")).click()
            pause()
            screenshot("08-delete-confirm")
            waitFor(By.text("刪除")).click()
            waitFor(By.text("已從 imgur 刪除"))
            pause(1500)
            screenshot("09-deleted")

            // 5. 設定頁：檢查額度、自動複製開關
            waitFor(By.desc("設定")).click()
            waitFor(By.text("上傳完成後自動複製連結"))
            pause()
            waitFor(By.text("檢查額度")).click()
            waitFor(By.res("credits_text"))
            pause(1500)
            screenshot("10-settings")
            waitFor(By.res("auto_copy_switch")).click()
            pause()
            waitFor(By.res("auto_copy_switch")).click()
            pause()
            waitFor(By.desc("返回")).click()
            waitFor(By.text("從相簿選擇"))
            pause(2000)
            screenshot("11-home")
        }
    }

    /** 文件選擇器 / 檔案 App：側邊欄 → 圖片 → ImgurShareDemo */
    private fun openDemoFolder() {
        if (device.wait(Until.hasObject(By.text("demo-1.jpg")), 3_000)) return
        if (!device.hasObject(By.text("Images"))) {
            waitFor(By.desc(Pattern.compile("(?i)show roots|open navigation drawer"))).click()
            pause()
        }
        waitFor(By.text("Images")).click()
        pause()
        waitFor(By.text(DEMO_FOLDER)).click()
        waitFor(By.text("demo-1.jpg"))
        pause()
    }

    /** 長按第一張進入多選，再點其他張 */
    private fun selectInDocumentsUi(vararg names: String) {
        findDocument(names.first()).longClick()
        pause(800)
        for (name in names.drop(1)) {
            findDocument(name).click()
            pause(800)
        }
        pause()
    }

    /** 最後一列的檔名被導覽列擋住時，往上滑到看得到 */
    private fun findDocument(name: String): UiObject2 {
        val selector = By.text(name)
        repeat(3) {
            device.wait(Until.findObject(selector), 2_000)?.let { return it }
            // 用滑動手勢而不是 UiObject2.scroll：DocumentsUI 裡標成可捲動的元件不一定是檔案清單
            val x = device.displayWidth / 2
            device.swipe(x, device.displayHeight * 2 / 3, x, device.displayHeight / 3, 40)
            pause(800)
        }
        return waitFor(selector)
    }

    // Android 9 的文件選擇器是「OPEN」，其他版本可能是「Select」
    private fun selectButton(): BySelector = By.pkg(DOCUMENTS_UI).text(Pattern.compile("(?i)open|select"))

    /**
     * 畫展示用的圖，複製到 /sdcard/Pictures/ImgurShareDemo/ 再請媒體掃描，讓文件選擇器和檔案 App 看得到。
     * App 沒有儲存權限，所以先寫到 App 自己的外部資料夾，再用 shell 複製。
     */
    private fun createDemoImages() {
        val staging = File(context.getExternalFilesDir(null), "demo").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = "/sdcard/Pictures/$DEMO_FOLDER"
        device.executeShellCommand("rm -rf $target")
        device.executeShellCommand("mkdir -p $target")
        for (i in 1..5) {
            val file = File(staging, "demo-$i.jpg")
            file.outputStream().use { demoBitmap(i).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            device.executeShellCommand("cp ${file.path} $target/${file.name}")
            device.executeShellCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$target/${file.name}")
        }
        // 等媒體掃描寫進資料庫
        SystemClock.sleep(3000)
    }

    private fun demoBitmap(index: Int): Bitmap {
        val size = 1080
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (index - 1) * 60f + 200f
        val start = Color.HSVToColor(floatArrayOf(hue % 360, 0.55f, 0.95f))
        val end = Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.75f, 0.7f))
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), start, end, Shader.TileMode.CLAMP) })
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
        canvas.drawCircle(size * 0.75f, size * 0.28f, size * 0.22f, circle)
        canvas.drawCircle(size * 0.2f, size * 0.85f, size * 0.3f, circle)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 380f
            isFakeBoldText = true
        }
        canvas.drawText("$index", size / 2f, size / 2f + 130f, text)
        text.textSize = 72f
        text.isFakeBoldText = false
        canvas.drawText("Imgur Share demo", size / 2f, size * 0.85f, text)
        return bitmap
    }

    private fun clipboardText(): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = context.getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        return text
    }

    private fun waitFor(selector: BySelector, timeout: Long = 10_000): UiObject2 {
        device.wait(Until.findObject(selector), timeout)?.let { return it }
        // 找不到時可能被系統 ANR 對話框擋住，關掉再找一次
        dismissAnrDialog()
        device.wait(Until.findObject(selector), timeout)?.let { return it }
        screenshot("failed-${System.currentTimeMillis()}")
        throw AssertionError("畫面上找不到 $selector")
    }

    // 系統程式 ANR 時按「Close app」讓它重開；按 Wait 的話 5 秒後會再跳出來
    private fun dismissAnrDialog() {
        device.findObject(By.res("android", "aerr_close"))?.click()
    }

    private fun screenshot(name: String) {
        device.takeScreenshot(File(screenshotDir, "$name.png"), 0.5f, 90)
    }

    private fun pause(ms: Long = 1200) {
        device.waitForIdle()
        SystemClock.sleep(ms)
    }

    /** 假的 imgur API：上傳回傳不存在的 id（8 碼，imgur 的 id 是 5 或 7 碼），刪除和額度都回成功 */
    private class FakeImgur : Dispatcher() {
        private val uploads = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                request.method == "POST" && path == "/3/image" -> {
                    val n = uploads.incrementAndGet()
                    val id = "demo%04d".format(n)
                    MockResponse.Builder()
                        // 每張回應時間不同，看得出最多 3 張同時上傳
                        .headersDelay(1200L + (n % 3) * 800L, TimeUnit.MILLISECONDS)
                        .body("""{"data":{"id":"$id","deletehash":"fakeDeletehash$n","link":"https://i.imgur.com/$id.jpg"},"success":true,"status":200}""")
                        .build()
                }
                request.method == "DELETE" && path.startsWith("/3/image/") ->
                    json("""{"data":true,"success":true,"status":200}""")
                path == "/3/credits" -> {
                    val reset = System.currentTimeMillis() / 1000 + 3600
                    json("""{"data":{"UserLimit":500,"UserRemaining":490,"UserReset":$reset,"ClientLimit":12500,"ClientRemaining":12450},"success":true,"status":200}""")
                }
                else -> MockResponse.Builder().code(404).body("<html><title>Not Found</title></html>").build()
            }
        }

        // 讀上傳內容時放慢速度，進度條才看得到變化
        override fun peek(): MockResponse = MockResponse.Builder().throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS).build()

        private fun json(body: String) = MockResponse.Builder().body(body).build()
    }

    private companion object {
        const val DOCUMENTS_UI = "com.android.documentsui"
        const val DEMO_FOLDER = "ImgurShareDemo"
    }
}
