package com.twtrubiks.imgurshare

import android.app.Application

class ImgurShareApplication : Application() {
    // lazy 而不是在 onCreate 建立：WorkManager 由 ContentProvider 初始化，
    // 重開機後恢復的上傳工作有可能在 Application.onCreate 之前就要用到
    val container by lazy { AppContainer(this) }
}
