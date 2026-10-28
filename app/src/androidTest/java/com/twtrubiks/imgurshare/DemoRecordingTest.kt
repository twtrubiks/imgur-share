package com.twtrubiks.imgurshare

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 錄影用的展示流程。骨架階段只打開首頁、存一張截圖，確認 build → 模擬器 → media branch 這條路是通的。
 * CI 只跑這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    // record.sh 會把這個資料夾 pull 回來，放進 media branch 的 screenshots/
    private val screenshotDir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")

    @Test
    fun demo() {
        screenshotDir.deleteRecursively()
        screenshotDir.mkdirs()

        // record.sh 已經處理過開機時的 ANR，這裡再保險一次
        dismissAnrDialog()

        ActivityScenario.launch(MainActivity::class.java).use {
            // CI 模擬器剛開機，第一個畫面可能要等比較久
            waitFor("Imgur Share", timeout = 30_000)
            pause(2000)
            screenshot("01-home")
        }
    }

    private fun waitFor(text: String, timeout: Long = 10_000): UiObject2 {
        val selector = By.text(text)
        device.wait(Until.findObject(selector), timeout)?.let { return it }
        // 找不到時可能被系統 ANR 對話框擋住，關掉再找一次
        dismissAnrDialog()
        return requireNotNull(device.wait(Until.findObject(selector), timeout)) { "畫面上找不到「$text」" }
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
}
