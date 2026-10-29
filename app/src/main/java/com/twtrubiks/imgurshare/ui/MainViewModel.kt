package com.twtrubiks.imgurshare.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twtrubiks.imgurshare.ImgurShareApplication
import com.twtrubiks.imgurshare.data.UploadRecord
import com.twtrubiks.imgurshare.imgur.Credits
import com.twtrubiks.imgurshare.imgur.ImgurException
import com.twtrubiks.imgurshare.upload.UploadStatus
import com.twtrubiks.imgurshare.upload.UploadWorker
import com.twtrubiks.imgurshare.upload.clipboardText
import com.twtrubiks.imgurshare.upload.copyToClipboard
import com.twtrubiks.imgurshare.upload.summarize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface DeleteState {
    val record: UploadRecord

    data class Confirm(override val record: UploadRecord) : DeleteState
    data class Deleting(override val record: UploadRecord) : DeleteState
    data class Failed(override val record: UploadRecord, val message: String) : DeleteState
}

sealed interface CreditsState {
    data object Loading : CreditsState
    data class Loaded(val credits: Credits) : CreditsState
    data class Failed(val message: String) : CreditsState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ImgurShareApplication).container
    private val dao = container.database.uploadDao()

    /** null 表示還在讀取，空字串表示還沒設定 */
    val clientId: StateFlow<String?> = container.settings.clientId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoCopy: StateFlow<Boolean> = container.settings.autoCopy
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val uploads: StateFlow<List<UploadRecord>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dismissedResults = MutableStateFlow(emptySet<String>())

    val uploadStatus: StateFlow<UploadStatus?> = combine(
        container.workManager.getWorkInfosByTagFlow(UploadWorker.TAG),
        dismissedResults,
    ) { infos, dismissed ->
        summarize(infos.mapNotNull(UploadWorker::stateOf), System.currentTimeMillis(), dismissed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 還沒設定 Client-ID 時收到的圖：batch id 到張數。設定好之後才開始上傳 */
    private val heldBatches = MutableStateFlow(emptyMap<String, Int>())
    val heldCount: StateFlow<Int> = heldBatches.map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 正在把分享進來的圖複製到 cache 的工作數 */
    var importing by mutableIntStateOf(0)
        private set

    var deleteState by mutableStateOf<DeleteState?>(null)
        private set

    var credits by mutableStateOf<CreditsState?>(null)
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun thumbnailFile(record: UploadRecord): File = container.thumbnails.file(record.imgurId)

    fun showMessage(message: String) {
        messageChannel.trySend(message)
    }

    /** 從相簿選取或從其他 App 分享進來的圖 */
    fun onImagesReceived(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing++
        viewModelScope.launch {
            try {
                val batch = container.batches.create(uris)
                if (batch == null) {
                    showMessage("讀不到選取的圖片")
                    return@launch
                }
                val (batchId, count) = batch
                if (count < uris.size) showMessage("有 ${uris.size - count} 張讀不到，已略過")
                startOrHold(batchId, count)
            } finally {
                importing--
            }
        }
    }

    fun newCameraFile(): File = container.batches.newCameraFile()

    fun onPhotoTaken(photo: File, saved: Boolean) {
        if (!saved) {
            photo.delete()
            return
        }
        viewModelScope.launch {
            val batchId = container.batches.createFromCamera(photo)
            if (batchId == null) showMessage("沒有拿到照片") else startOrHold(batchId, 1)
        }
    }

    private suspend fun startOrHold(batchId: String, count: Int) {
        if (container.settings.clientId.first().isBlank()) {
            heldBatches.update { it + (batchId to count) }
        } else {
            dismissedResults.value = emptySet()
            UploadWorker.enqueue(container.workManager, batchId, count)
        }
    }

    fun saveClientId(clientId: String) {
        viewModelScope.launch {
            container.settings.setClientId(clientId)
            if (clientId.isBlank()) return@launch
            val held = heldBatches.value
            heldBatches.value = emptyMap()
            held.forEach { (batchId, count) -> UploadWorker.enqueue(container.workManager, batchId, count) }
            showMessage("已儲存 Client-ID")
        }
    }

    fun setAutoCopy(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoCopy(enabled) }
    }

    fun dismissResult(batchId: String) {
        dismissedResults.update { it + batchId }
    }

    fun copyBatch(batchId: String) {
        viewModelScope.launch {
            val links = dao.linksOfBatch(batchId)
            if (links.isEmpty()) return@launch
            copyToClipboard(getApplication(), clipboardText(links))
            showCopied("已複製 ${links.size} 個連結")
        }
    }

    fun copyLink(record: UploadRecord) {
        viewModelScope.launch {
            copyToClipboard(getApplication(), record.link)
            showCopied("已複製連結")
        }
    }

    // Android 13 起系統複製時會自己顯示提示，App 不要再重複顯示
    private fun showCopied(message: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showMessage(message)
    }

    fun requestDelete(record: UploadRecord) {
        deleteState = DeleteState.Confirm(record)
    }

    fun cancelDelete() {
        deleteState = null
    }

    fun confirmDelete() {
        val record = deleteState?.record ?: return
        deleteState = DeleteState.Deleting(record)
        viewModelScope.launch {
            deleteState = try {
                val clientId = container.settings.clientId.first()
                if (clientId.isBlank()) throw ImgurException("還沒有設定 Client-ID")
                container.imgurClient.delete(clientId, record.deletehash)
                removeRecord(record)
                showMessage("已從 imgur 刪除")
                null
            } catch (e: ImgurException) {
                DeleteState.Failed(record, e.message.orEmpty())
            }
        }
    }

    /** imgur 上刪不掉（例如圖已經被 imgur 清掉）時，讓使用者選擇只移除紀錄 */
    fun removeRecordOnly() {
        val record = deleteState?.record ?: return
        deleteState = null
        viewModelScope.launch {
            removeRecord(record)
            showMessage("已從紀錄移除")
        }
    }

    private suspend fun removeRecord(record: UploadRecord) {
        dao.delete(record)
        container.thumbnails.delete(record.imgurId)
    }

    fun checkCredits(clientId: String) {
        if (clientId.isBlank()) return
        credits = CreditsState.Loading
        viewModelScope.launch {
            credits = try {
                CreditsState.Loaded(container.imgurClient.credits(clientId.trim()))
            } catch (e: ImgurException) {
                CreditsState.Failed(e.message.orEmpty())
            }
        }
    }
}
