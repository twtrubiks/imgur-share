package com.twtrubiks.imgurshare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

// BgApp 要和 res/values/colors.xml 的 bg_app 一致（啟動時的 windowBackground）
private val BgApp = Color(0xFFF5F6FB)
private val Ink = Color(0xFF1F2440)
private val InkSoft = Color(0xFF6B7090)

/** 骨架階段的首頁：只確認 App 裝得起來、CI 錄得到畫面，之後換成上傳頁。 */
@Composable
fun ImgurShareApp() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Imgur Share", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        Text("準備中", color = InkSoft, fontSize = 18.sp)
    }
}

@Preview
@Composable
private fun ImgurShareAppPreview() {
    ImgurShareApp()
}
