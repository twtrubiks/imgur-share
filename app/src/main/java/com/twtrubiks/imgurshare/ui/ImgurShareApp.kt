package com.twtrubiks.imgurshare.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.FileProvider
import java.io.File

private enum class Screen { Home, Settings }

@Composable
fun ImgurShareApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // 有 Photo Picker 就用 Photo Picker，沒有的裝置會自動退回系統的檔案選擇器，兩者都不需要儲存權限
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.onImagesReceived(uris)
    }

    // 相機 App 可能讓這個 Activity 被回收，暫存檔路徑要存進 saved state
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraFile?.let { viewModel.onPhotoTaken(File(it), saved) }
        cameraFile = null
    }

    ImgurShareTheme {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            // 讓 UiAutomator 可以用 By.res(...) 找到 testTag
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onOpenSettings = { screen = Screen.Settings },
                    onPickImages = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhoto = {
                        val file = viewModel.newCameraFile()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraFile = file.path
                        try {
                            takePicture.launch(uri)
                        } catch (_: ActivityNotFoundException) {
                            cameraFile = null
                            viewModel.showMessage("找不到相機 App")
                        }
                    },
                )
                Screen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onBack = { screen = Screen.Home },
                )
            }
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
}
