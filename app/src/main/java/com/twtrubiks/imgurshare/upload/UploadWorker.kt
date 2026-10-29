package com.twtrubiks.imgurshare.upload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.twtrubiks.imgurshare.ImgurShareApplication
import com.twtrubiks.imgurshare.data.UploadRecord
import com.twtrubiks.imgurshare.imgur.ImgurException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上傳一個 batch。交給 WorkManager，使用者切走 App 也會傳完；
 * 同時上傳的張數由 [com.twtrubiks.imgurshare.AppContainer.uploadSlots] 限制，多個 batch 一起跑也不會超過。
 *
 * 網路類的錯誤會整個 batch 重試，已經傳好的檔案在成功時就刪掉了，重試只傳剩下的。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as ImgurShareApplication).container

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val total = inputData.getInt(KEY_TOTAL, 0)
        val dao = container.database.uploadDao()
        val pending = container.batches.pendingFiles(batchId)
        val clientId = container.settings.clientId.first()
        val errors = ConcurrentLinkedQueue<ImgurException>()

        if (clientId.isBlank()) {
            errors += ImgurException("還沒有設定 Client-ID")
        } else if (pending.isNotEmpty()) {
            // 前幾次嘗試已經傳好的張數
            val previouslyDone = total - pending.size
            val fractions = FloatArray(pending.size)
            val succeeded = AtomicInteger(0)
            val failed = AtomicInteger(0)
            fun progress(): Data = synchronized(fractions) {
                workDataOf(
                    KEY_TOTAL to total,
                    KEY_SUCCEEDED to previouslyDone + succeeded.get(),
                    KEY_FAILED to failed.get(),
                    KEY_FRACTION to (previouslyDone + fractions.sum()) / total.coerceAtLeast(1),
                )
            }

            coroutineScope {
                val reporter = launch {
                    while (true) {
                        setProgress(progress())
                        delay(PROGRESS_INTERVAL_MILLIS)
                    }
                }
                pending.mapIndexed { i, item ->
                    async {
                        container.uploadSlots.withPermit {
                            try {
                                upload(clientId, batchId, item) { fraction ->
                                    synchronized(fractions) { fractions[i] = fraction }
                                }
                                synchronized(fractions) { fractions[i] = 1f }
                                succeeded.incrementAndGet()
                            } catch (e: ImgurException) {
                                errors += e
                                failed.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
                setProgress(progress())
                reporter.cancel()
            }
        }

        if (errors.any { it.retryable } && runAttemptCount + 1 < MAX_ATTEMPTS) {
            return Result.retry()
        }

        container.batches.delete(batchId)
        val links = dao.linksOfBatch(batchId)
        val copied = links.isNotEmpty() && container.settings.autoCopy.first()
        if (copied) copyToClipboard(applicationContext, clipboardText(links))

        val output = workDataOf(
            KEY_BATCH_ID to batchId,
            KEY_TOTAL to total,
            KEY_SUCCEEDED to links.size,
            KEY_ERROR to errors.firstOrNull()?.message,
            KEY_COPIED to copied,
            KEY_FINISHED_AT to System.currentTimeMillis(),
        )
        return if (links.isNotEmpty()) Result.success(output) else Result.failure(output)
    }

    private suspend fun upload(clientId: String, batchId: String, item: BatchFile, onProgress: (Float) -> Unit) {
        val size = item.file.length().coerceAtLeast(1)
        val image = container.imgurClient.upload(clientId, item.file, item.mimeType) { sent ->
            // 檔案送完之後 imgur 還要處理一下，留 5% 給回應
            onProgress(sent.coerceAtMost(size) * 0.95f / size)
        }
        container.thumbnails.create(item.file, image.id)
        container.database.uploadDao().insert(
            UploadRecord(
                imgurId = image.id,
                link = image.link,
                deletehash = image.deletehash,
                uploadedAt = System.currentTimeMillis(),
                batchId = batchId,
                batchIndex = item.index,
            ),
        )
        item.file.delete()
    }

    companion object {
        const val TAG = "upload"
        private const val KEY_BATCH_ID = "batch_id"
        private const val KEY_TOTAL = "total"
        private const val KEY_SUCCEEDED = "succeeded"
        private const val KEY_FAILED = "failed"
        private const val KEY_FRACTION = "fraction"
        private const val KEY_ERROR = "error"
        private const val KEY_COPIED = "copied"
        private const val KEY_FINISHED_AT = "finished_at"
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_MILLIS = 300L

        fun enqueue(workManager: WorkManager, batchId: String, total: Int) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_BATCH_ID to batchId, KEY_TOTAL to total))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueue(request)
        }

        /** WorkInfo 轉成 UI 用的狀態；被取消的 batch 回傳 null */
        fun stateOf(info: WorkInfo): BatchState? = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BatchState.Waiting
            WorkInfo.State.RUNNING -> {
                val total = info.progress.getInt(KEY_TOTAL, 0)
                if (total == 0) {
                    BatchState.Waiting
                } else {
                    BatchState.Running(
                        total = total,
                        succeeded = info.progress.getInt(KEY_SUCCEEDED, 0),
                        failed = info.progress.getInt(KEY_FAILED, 0),
                        fraction = info.progress.getFloat(KEY_FRACTION, 0f),
                    )
                }
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                val output = info.outputData
                output.getString(KEY_BATCH_ID)?.let { batchId ->
                    BatchState.Finished(
                        batchId = batchId,
                        total = output.getInt(KEY_TOTAL, 0),
                        succeeded = output.getInt(KEY_SUCCEEDED, 0),
                        error = output.getString(KEY_ERROR),
                        copied = output.getBoolean(KEY_COPIED, false),
                        finishedAt = output.getLong(KEY_FINISHED_AT, 0),
                    )
                }
            }
            WorkInfo.State.CANCELLED -> null
        }
    }
}

suspend fun copyToClipboard(context: Context, text: String) = withContext(Dispatchers.Main) {
    context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("imgur", text))
}
