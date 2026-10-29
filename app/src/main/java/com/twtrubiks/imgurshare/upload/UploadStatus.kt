package com.twtrubiks.imgurshare.upload

/** 單一 batch（一次選取 / 分享的圖）目前的狀態，從 WorkManager 的 WorkInfo 轉過來。 */
sealed interface BatchState {
    /** 還沒開始，或是網路斷線後等待重試 */
    data object Waiting : BatchState

    data class Running(val total: Int, val succeeded: Int, val failed: Int, val fraction: Float) : BatchState

    data class Finished(
        val batchId: String,
        val total: Int,
        val succeeded: Int,
        val error: String?,
        val copied: Boolean,
        val finishedAt: Long,
    ) : BatchState
}

/** 首頁上方的上傳狀態卡片要顯示的內容。 */
sealed interface UploadStatus {
    /** [fraction] 為 null 表示還沒有任何 batch 開始跑，進度條顯示成不確定的樣子 */
    data class Uploading(val total: Int, val succeeded: Int, val failed: Int, val fraction: Float?) : UploadStatus

    data class Done(val batch: BatchState.Finished) : UploadStatus
}

/** 上傳完成的結果卡片保留多久 */
const val RECENT_RESULT_MILLIS = 10 * 60 * 1000L

/**
 * 把所有 batch 合併成一張卡片：有任何 batch 在跑就顯示合計的進度，
 * 都跑完了就顯示最近一個完成、而且使用者沒有關掉的結果。
 */
fun summarize(batches: List<BatchState>, now: Long, dismissed: Set<String>): UploadStatus? {
    val running = batches.filterIsInstance<BatchState.Running>()
    if (running.isNotEmpty() || BatchState.Waiting in batches) {
        val total = running.sumOf { it.total }
        val fraction = if (total == 0) null else running.sumOf { it.fraction.toDouble() * it.total }.toFloat() / total
        return UploadStatus.Uploading(
            total = total,
            succeeded = running.sumOf { it.succeeded },
            failed = running.sumOf { it.failed },
            fraction = fraction,
        )
    }
    return batches.filterIsInstance<BatchState.Finished>()
        .filter { now - it.finishedAt in 0..RECENT_RESULT_MILLIS && it.batchId !in dismissed }
        .maxByOrNull { it.finishedAt }
        ?.let(UploadStatus::Done)
}

/** 結果卡片的文字 */
fun resultMessage(result: BatchState.Finished): String {
    val failed = result.total - result.succeeded
    return when {
        result.succeeded == 0 -> "上傳失敗：${result.error ?: "未知的錯誤"}"
        failed == 0 && result.copied -> "${result.succeeded} 張上傳完成，連結已複製到剪貼簿"
        failed == 0 -> "${result.succeeded} 張上傳完成"
        else -> buildString {
            append("${result.succeeded} 張上傳完成")
            if (result.copied) append("（連結已複製）")
            append("，$failed 張失敗：${result.error ?: "未知的錯誤"}")
        }
    }
}

/** 複製到剪貼簿的文字：多張就一行一個 */
fun clipboardText(links: List<String>): String = links.joinToString("\n")
