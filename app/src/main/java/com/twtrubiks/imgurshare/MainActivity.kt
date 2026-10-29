package com.twtrubiks.imgurshare

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.twtrubiks.imgurshare.ui.ImgurShareApp
import com.twtrubiks.imgurshare.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 背景是淺色，系統列圖示固定用深色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 旋轉螢幕重建時不要再上傳一次
        if (savedInstanceState == null) handleShare(intent)
        setContent { ImgurShareApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 從其他 App 分享進來的圖：ViewModel 會先複製到 cache，這個 Activity 的讀取權限還在的時候就要讀完 */
    private fun handleShare(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        // 有些 App 只放在 ClipData
        val clip = intent.clipData
        val fromClip = if (uris.isEmpty() && clip != null) List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull() else uris
        viewModel.onImagesReceived(fromClip.distinct())
    }
}
