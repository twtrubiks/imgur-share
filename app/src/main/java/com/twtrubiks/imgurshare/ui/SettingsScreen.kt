package com.twtrubiks.imgurshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twtrubiks.imgurshare.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val resetFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val clientId by viewModel.clientId.collectAsStateWithLifecycle()
    val autoCopy by viewModel.autoCopy.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var input by rememberSaveable { mutableStateOf<String?>(null) }
    // DataStore 讀到之後才填入目前的值
    LaunchedEffect(clientId) {
        if (input == null && clientId != null) input = clientId
    }
    val text = input.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgApp),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgApp,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard {
                Text("imgur Client-ID", style = MaterialTheme.typography.titleMedium)
                ClientIdField(
                    value = text,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_client_id"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.saveClientId(text) }, enabled = text.trim() != clientId) {
                        Text("儲存")
                    }
                    OutlinedButton(onClick = { viewModel.checkCredits(text) }, enabled = text.isNotBlank()) {
                        Text("檢查額度")
                    }
                }
                CreditsText(viewModel.credits)
                Text(
                    "Client-ID 只存在這支手機，不會加密：它本來就不是秘密，每個匿名上傳的 App 都內建一組。",
                    color = InkSoft,
                    fontSize = 13.sp,
                )
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(CLIENT_REGISTRATION_URL) } },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text("前往 api.imgur.com 申請 Client-ID")
                }
            }

            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("上傳完成後自動複製連結", style = MaterialTheme.typography.titleMedium)
                        Text("多張圖時一行一個連結", color = InkSoft, fontSize = 13.sp)
                    }
                    Switch(
                        checked = autoCopy,
                        onCheckedChange = viewModel::setAutoCopy,
                        modifier = Modifier.testTag("auto_copy_switch"),
                    )
                }
            }

            SettingsCard {
                Text("上傳紀錄", style = MaterialTheme.typography.titleMedium)
                Text(
                    "匿名上傳的圖不屬於任何帳號，紀錄裡的 deletehash 是唯一能刪除它的憑證。" +
                        "紀錄會跟著 Android 的備份轉移到新手機；解除安裝 App 會一起刪掉。",
                    color = InkSoft,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun CreditsText(state: CreditsState?) {
    when (state) {
        null -> {}
        CreditsState.Loading -> Text("查詢中…", color = InkSoft)
        is CreditsState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is CreditsState.Loaded -> {
            val credits = state.credits
            val reset = resetFormatter.format(Instant.ofEpochSecond(credits.userReset).atZone(ZoneId.systemDefault()))
            Text(
                "這組 Client-ID 今天還剩 ${credits.clientRemaining} / ${credits.clientLimit} 點；" +
                    "這個 IP 還剩 ${credits.userRemaining} / ${credits.userLimit} 點，$reset 重置",
                modifier = Modifier.testTag("credits_text"),
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}
