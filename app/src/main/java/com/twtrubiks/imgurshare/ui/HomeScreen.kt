package com.twtrubiks.imgurshare.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twtrubiks.imgurshare.R
import com.twtrubiks.imgurshare.data.UploadRecord
import com.twtrubiks.imgurshare.upload.UploadStatus
import com.twtrubiks.imgurshare.upload.resultMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val CLIENT_REGISTRATION_URL = "https://api.imgur.com/oauth2/addclient"

private val timeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val clientId by viewModel.clientId.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val status by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val heldCount by viewModel.heldCount.collectAsStateWithLifecycle()
    val ready = !clientId.isNullOrBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Imgur Share", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgApp),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgApp,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (clientId == "") {
                item(key = "setup") {
                    ClientIdSetupCard(heldCount = heldCount, onSave = viewModel::saveClientId)
                }
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPickImages,
                        enabled = ready,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("從相簿選擇", fontSize = 16.sp)
                    }
                    FilledTonalButton(
                        onClick = onTakePhoto,
                        enabled = ready,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("拍照", fontSize = 16.sp)
                    }
                }
            }
            if (viewModel.importing > 0) {
                item(key = "importing") {
                    StatusCard {
                        Text("正在讀取圖片…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            status?.let { current ->
                item(key = "status") {
                    UploadStatusCard(
                        status = current,
                        onCopy = viewModel::copyBatch,
                        onDismiss = viewModel::dismissResult,
                    )
                }
            }
            item(key = "history-title") {
                Text(
                    text = if (uploads.isEmpty()) "上傳紀錄" else "上傳紀錄（${uploads.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkSoft,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            if (uploads.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (ready) "上傳的圖會列在這裡，點一下就能複製連結。也可以從其他 App 分享圖片到 Imgur Share。" else "設定好 Client-ID 之後就能開始上傳。",
                        color = InkSoft,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(uploads, key = { it.id }) { record ->
                UploadRow(
                    record = record,
                    thumbnail = viewModel.thumbnailFile(record),
                    onCopy = { viewModel.copyLink(record) },
                    onDelete = { viewModel.requestDelete(record) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    viewModel.deleteState?.let { state ->
        DeleteDialog(
            state = state,
            onConfirm = viewModel::confirmDelete,
            onRemoveRecordOnly = viewModel::removeRecordOnly,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun ClientIdSetupCard(heldCount: Int, onSave: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    StatusCard {
        Text("先設定 imgur Client-ID", style = MaterialTheme.typography.titleLarge)
        Text(
            "匿名上傳需要一組自己的 Client-ID。到 imgur 註冊應用程式，" +
                "Authorization type 選「OAuth 2 authorization without a callback URL」。",
            color = InkSoft,
        )
        TextButton(
            onClick = { runCatching { uriHandler.openUri(CLIENT_REGISTRATION_URL) } },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text("前往 api.imgur.com 申請")
        }
        ClientIdField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("client_id_input"),
        )
        if (heldCount > 0) {
            Text("儲存之後會開始上傳分享進來的 $heldCount 張圖", color = MaterialTheme.colorScheme.primary)
        }
        Button(
            onClick = { onSave(input) },
            enabled = input.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("儲存")
        }
    }
}

@Composable
private fun StatusCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun UploadStatusCard(status: UploadStatus, onCopy: (String) -> Unit, onDismiss: (String) -> Unit) {
    StatusCard {
        when (status) {
            is UploadStatus.Uploading -> {
                Text(
                    text = if (status.total == 0) "等待上傳…" else "上傳中 ${status.succeeded} / ${status.total}",
                    style = MaterialTheme.typography.titleMedium,
                )
                val fraction = status.fraction
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = buildString {
                        if (status.failed > 0) append("${status.failed} 張失敗。")
                        append("最多同時上傳 3 張，切到其他 App 也會繼續上傳")
                    },
                    color = InkSoft,
                    fontSize = 13.sp,
                )
            }
            is UploadStatus.Done -> {
                val result = status.batch
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = resultMessage(result),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (result.succeeded == 0) MaterialTheme.colorScheme.error else Ink,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    IconButton(onClick = { onDismiss(result.batchId) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "關閉", tint = InkSoft)
                    }
                }
                if (result.succeeded > 0) {
                    FilledTonalButton(onClick = { onCopy(result.batchId) }) {
                        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (result.copied) "再複製一次" else "複製連結")
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadRow(
    record: UploadRecord,
    thumbnail: File,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onCopy)
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(thumbnail)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = record.link.removePrefix("https://"),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeFormatter.format(Instant.ofEpochMilli(record.uploadedAt).atZone(ZoneId.systemDefault())),
                color = InkSoft,
                fontSize = 13.sp,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多動作", tint = InkSoft)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("複製連結") }, onClick = { menuOpen = false; onCopy() })
                DropdownMenuItem(
                    text = { Text("在瀏覽器開啟") },
                    onClick = {
                        menuOpen = false
                        runCatching { uriHandler.openUri(record.link) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("分享連結") },
                    onClick = {
                        menuOpen = false
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, record.link)
                        try {
                            context.startActivity(Intent.createChooser(send, null))
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("從 imgur 刪除", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun DeleteDialog(
    state: DeleteState,
    onConfirm: () -> Unit,
    onRemoveRecordOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is DeleteState.Confirm, is DeleteState.Deleting -> AlertDialog(
            onDismissRequest = { if (state is DeleteState.Confirm) onDismiss() },
            title = { Text("從 imgur 刪除這張圖？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.record.link.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text("刪除後連結就會失效，無法復原。")
                }
            },
            confirmButton = {
                if (state is DeleteState.Deleting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("刪除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = state is DeleteState.Confirm) { Text("取消") }
            },
        )
        is DeleteState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("刪除失敗") },
            text = {
                Text(
                    "${state.message}\n\n如果這張圖在 imgur 上已經不存在，可以只從紀錄移除。" +
                        "移除後 deletehash 就沒了，之後無法再從 imgur 刪除這張圖。",
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("保留") } },
            dismissButton = {
                TextButton(
                    onClick = onRemoveRecordOnly,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("只移除紀錄")
                }
            },
        )
    }
}
