package com.twtrubiks.imgurshare.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// BgApp 要和 res/values/colors.xml 的 bg_app 一致（啟動時的 windowBackground）
val BgApp = Color(0xFFF5F6FB)
val Ink = Color(0xFF1F2440)
val InkSoft = Color(0xFF6B7090)

// App 主色，和 res/values/colors.xml 的 ic_launcher_background（App 圖示底色）一致
private val Indigo = Color(0xFF3F51B5)

private val colors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FA),
    onPrimaryContainer = Color(0xFF1A237E),
    secondaryContainer = Color(0xFFE3E7FA),
    onSecondaryContainer = Color(0xFF1A237E),
    background = BgApp,
    onBackground = Ink,
    surface = BgApp,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E8F2),
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE6E8F2),
    outline = Color(0xFFB7BBD1),
    outlineVariant = Color(0xFFDADDEA),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8E1B1B),
)

@Composable
fun ImgurShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
