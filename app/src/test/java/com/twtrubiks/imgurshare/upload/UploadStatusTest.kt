package com.twtrubiks.imgurshare.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadStatusTest {
    private val now = 1_800_000_000_000L

    private fun finished(batchId: String, finishedAt: Long = now, total: Int = 2, succeeded: Int = 2, copied: Boolean = true) =
        BatchState.Finished(batchId, total, succeeded, error = null, copied = copied, finishedAt = finishedAt)

    @Test
    fun nothing_returnsNull() {
        assertNull(summarize(emptyList(), now, emptySet()))
    }

    @Test
    fun running_mergesProgressWeightedByCount() {
        val status = summarize(
            listOf(
                BatchState.Running(total = 1, succeeded = 1, failed = 0, fraction = 1f),
                BatchState.Running(total = 3, succeeded = 0, failed = 1, fraction = 0f),
                finished("old"),
            ),
            now,
            emptySet(),
        )

        assertEquals(UploadStatus.Uploading(total = 4, succeeded = 1, failed = 1, fraction = 0.25f), status)
    }

    @Test
    fun onlyWaiting_hasIndeterminateProgress() {
        assertEquals(
            UploadStatus.Uploading(total = 0, succeeded = 0, failed = 0, fraction = null),
            summarize(listOf(BatchState.Waiting), now, emptySet()),
        )
    }

    @Test
    fun finished_showsLatestNotDismissed() {
        val batches = listOf(finished("a", now - 2000), finished("b", now - 1000), finished("c", now - 500))

        assertEquals(UploadStatus.Done(finished("b", now - 1000)), summarize(batches, now, setOf("c")))
    }

    @Test
    fun finished_tooOld_isHidden() {
        assertNull(summarize(listOf(finished("a", now - RECENT_RESULT_MILLIS - 1)), now, emptySet()))
    }

    @Test
    fun resultMessages() {
        assertEquals("2 張上傳完成，連結已複製到剪貼簿", resultMessage(finished("a")))
        assertEquals("2 張上傳完成", resultMessage(finished("a", copied = false)))
        assertEquals(
            "1 張上傳完成（連結已複製），1 張失敗：壞掉了",
            resultMessage(finished("a", succeeded = 1).copy(error = "壞掉了")),
        )
        assertEquals("上傳失敗：壞掉了", resultMessage(finished("a", succeeded = 0, copied = false).copy(error = "壞掉了")))
    }

    @Test
    fun clipboardText_oneLinkPerLine() {
        assertEquals("https://i.imgur.com/a.png\nhttps://i.imgur.com/b.jpg", clipboardText(listOf("https://i.imgur.com/a.png", "https://i.imgur.com/b.jpg")))
    }
}
