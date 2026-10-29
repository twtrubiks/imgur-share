package com.twtrubiks.imgurshare

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.WorkManager
import com.twtrubiks.imgurshare.data.AppDatabase
import com.twtrubiks.imgurshare.data.Settings
import com.twtrubiks.imgurshare.imgur.ImgurClient
import com.twtrubiks.imgurshare.upload.Thumbnails
import com.twtrubiks.imgurshare.upload.UploadBatches
import kotlinx.coroutines.sync.Semaphore
import java.io.File

/** App 層級的單例，由 [ImgurShareApplication] 建立。 */
class AppContainer(context: Context) {

    val settings = Settings(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") })

    // 用 TRUNCATE 而不是 WAL：Auto Backup 是直接複製 databases/ 裡的檔案，
    // WAL 模式下還沒 checkpoint 的資料在 -wal 檔裡，備份時可能對不上
    val database: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "imgur-share.db")
        .setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()

    /** 錄影測試會換成指向 MockWebServer 的 client */
    @Volatile
    var imgurClient = ImgurClient()

    /** 最多 3 張同時上傳，所有 batch 共用 */
    val uploadSlots = Semaphore(MAX_CONCURRENT_UPLOADS)

    val batches = UploadBatches(context.cacheDir, context.contentResolver)

    val thumbnails = Thumbnails(File(context.noBackupFilesDir, "thumbs"))

    val workManager by lazy { WorkManager.getInstance(context) }

    private companion object {
        const val MAX_CONCURRENT_UPLOADS = 3
    }
}
