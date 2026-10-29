package com.twtrubiks.imgurshare.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 一張上傳成功的圖。匿名上傳的圖不屬於任何帳號，[deletehash] 是唯一的刪除憑證，掉了就永遠刪不掉。
 */
@Entity(tableName = "uploads", indices = [Index("batchId")])
data class UploadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** imgur 的圖片 id，也是本機縮圖的檔名 */
    val imgurId: String,
    val link: String,
    val deletehash: String,
    /** epoch 毫秒 */
    val uploadedAt: Long,
    /** 同一次選取 / 分享的圖屬於同一個 batch，複製連結時照 [batchIndex] 排序 */
    val batchId: String,
    val batchIndex: Int,
)

@Dao
interface UploadDao {
    /**
     * 新的 batch 在前，同一個 batch 照選取順序（和複製連結的順序一致），不照完成的先後。
     * Android 9 的 SQLite 還不支援 window function，用子查詢取每個 batch 第一張完成的時間
     */
    @Query(
        """
        SELECT * FROM uploads
        ORDER BY (SELECT MIN(u.uploadedAt) FROM uploads AS u WHERE u.batchId = uploads.batchId) DESC, batchId, batchIndex
        """,
    )
    fun observeAll(): Flow<List<UploadRecord>>

    @Query("SELECT link FROM uploads WHERE batchId = :batchId ORDER BY batchIndex")
    suspend fun linksOfBatch(batchId: String): List<String>

    @Query("SELECT COUNT(*) FROM uploads WHERE batchId = :batchId")
    suspend fun countOfBatch(batchId: String): Int

    @Insert
    suspend fun insert(record: UploadRecord): Long

    @Delete
    suspend fun delete(record: UploadRecord)
}

@Database(entities = [UploadRecord::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
}
