# Imgur Share

把圖片匿名上傳到 imgur、拿回連結的 Android App。Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/android-record.yml`）。

## 功能

- **從其他 App 分享進來上傳**：`MainActivity` 收 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`（`image/*`），只收 `content://`
  （`file://` 可能被惡意 App 拿來指向這個 App 自己的私有檔案）
- **從相簿多選**：Photo Picker（`ActivityResultContracts.PickMultipleVisualMedia`），不需要任何儲存權限。
  沒有 Photo Picker 的裝置會自動退回系統的檔案選擇器（CI 用的 AOSP 模擬器就是這樣）
- **拍照上傳**：`ActivityResultContracts.TakePicture` + `FileProvider`（`res/xml/file_paths.xml` 只開放 `cache/camera/`）。
  manifest **不要**宣告 `CAMERA` 權限：一旦宣告了卻沒取得授權，呼叫系統相機會丟 `SecurityException`
- **上傳**：`UploadWorker`（WorkManager），使用者切走 App 也會傳完。所有 batch 共用 `AppContainer.uploadSlots`，
  最多 3 張同時上傳，首頁顯示合計的進度。網路類錯誤（`ImgurException.retryable`）整個 batch 最多試 3 次，
  成功的檔案當下就刪掉，重試只傳剩下的
- **上傳成功自動複製連結到剪貼簿**（設定裡可以關掉），多張就一行一個，batch 結束時由 Worker 複製。
  Android 13 起系統複製時會自己顯示提示，App 不再重複顯示
- **上傳紀錄**：連結、`deletehash`、上傳時間存在 Room，可以從紀錄裡刪除 imgur 上的圖；
  imgur 刪不掉時（例如圖已經被清掉）讓使用者選擇只移除紀錄。
  縮圖存在 `noBackupFilesDir/thumbs/`：不佔 Auto Backup 的 25 MB 額度（超過額度整個備份會失敗），還原後沒有縮圖就顯示空白
- **設定**：Client-ID、檢查額度（`GET /3/credits`）、自動複製開關。
  Client-ID 輸入框（`ui/ClientIdField.kt`）預設隱藏，用眼睛按鈕切換顯示，避免截圖、分享螢幕時外流；
  鍵盤用密碼類型，輸入法不會把它學進字典或出現在建議字裡
- 還沒做：上傳紀錄匯出成 JSON

## 程式結構

`app/src/main/java/com/twtrubiks/imgurshare/`：

- `imgur/`：`ImgurClient`（OkHttp，所有 imgur API 都從這裡呼叫）、`ImgurResponses`（純 Kotlin 解析，JVM 測試）
- `upload/`：`UploadWorker`、`UploadBatches`（等待上傳的圖放在 `cache/uploads/<batchId>/000.jpg`，序號就是複製連結的順序）、
  `Thumbnails`、`UploadStatus`（純 Kotlin：合併多個 batch 的進度、結果文字、剪貼簿文字）
- `data/`：`Settings`（DataStore）、`UploadRecord` / `UploadDao` / `AppDatabase`（Room）
- `ui/`：`ImgurShareApp`（首頁 / 設定切換、選圖和拍照的 launcher）、`HomeScreen`、`SettingsScreen`、`MainViewModel`
- `AppContainer`：App 層級的單例，由 `ImgurShareApplication` lazy 建立
  （WorkManager 由 ContentProvider 初始化，恢復的上傳工作可能在 `Application.onCreate` 之前就要用到）

Room 用的是 Room 3（package `androidx.room3`）：一定要 `setDriver(AndroidSQLiteDriver())`；
journal mode 用 TRUNCATE，Auto Backup 直接複製 `databases/` 的檔案時才不會漏掉 WAL 裡的資料。
schema 輸出在 `app/schemas/`，要 commit。改資料表一定要升版本並寫 migration，不能用 destructive migration——紀錄裡的 `deletehash` 掉了就永遠刪不掉圖

## imgur API

- 上傳：`POST https://api.imgur.com/3/image`，header `Authorization: Client-ID <id>`，multipart 欄位名稱 `image`。
  回應的 `data.link` 是圖片網址，`data.deletehash` 是刪除憑證
- 刪除：`DELETE https://api.imgur.com/3/image/{deletehash}`，一樣帶 Client-ID
- 額度：`GET https://api.imgur.com/3/credits`
- 回應**不一定是 JSON**：地區封鎖頁、over capacity 頁都是 HTML。解析失敗要當成一般錯誤顯示，不能閃退
- Client-ID 到 <https://api.imgur.com/oauth2/addclient> 申請，選「不需要 callback 的 OAuth 2」

## 約束

- **repo 是 public**：Client-ID 不能寫進 repo 或 APK；`android-media` branch 也是公開的，
  CI 錄影和截圖不能出現真實的 Client-ID 或私人照片
- **Client-ID 由使用者在 App 裡輸入**，存在 DataStore，不加密。它本來就不是秘密（每個匿名上傳 App 都內建一個），
  而且用 Keystore 加密的資料備份還原到新手機會解不開
- **`android:allowBackup="true"`**：匿名上傳的圖不屬於任何帳號，`deletehash` 是唯一的刪除憑證，掉了就永遠刪不掉。
  開著備份，換手機後上傳紀錄還在。之後再加匯出成 JSON
- **分享進來的 `Uri` 先複製到 App 的 cache 再交給 WorkManager**：分享時給的讀取權限跟著 Activity，
  Activity 結束後背景工作可能就讀不到了
- **上傳 API 集中在一個地方**（例如 `ImgurClient`），UI 和 WorkManager 都透過它呼叫。
  imgur 曾經整個退出英國，匿名的舊圖也可能被清除，日後要換圖床時只改這一層
- 權限只要 `INTERNET`，新增任何權限前先想有沒有不需要權限的做法

## 專案約定

- 主要 branch 是 `main`，PR 一律發到 `main`
- Gradle 專案就在 repo 根目錄，所有 `./gradlew` 都在根目錄跑
- applicationId / namespace：`com.twtrubiks.imgurshare`（`scripts/ci/record.sh` 依賴這個值，改了要一起改）。
  不要改：applicationId 一變，手機會當成另一個 App，原本的上傳紀錄就接不回來
- 單一 module `app`，主畫面 `MainActivity`
- **minSdk 28（Android 9）**，compileSdk 37，targetSdk 36，Java / Kotlin target 17
  （`java.time` 在 API 26 就內建，不需要 core library desugaring；新增 API 29+ 的 API 前先跑 `./gradlew lintDebug` 看 NewApi）
  （Compose 1.12 / core 1.19 的 AAR metadata 要求 compileSdk ≥ 37；缺 `platforms;android-37.0` 時 AGP 會自動下載）
- 依賴版本集中在 `gradle/libs.versions.toml`，新增依賴前先查最新穩定版
- release 版開 R8（minify + shrinkResources），不上架所以用 debug 金鑰簽，可以和同一台機器打包的 debug 版互相覆蓋安裝。
  debug 金鑰在 VM 的 `~/.android/debug.keystore`，換 session 會變，之後給使用者的 APK 簽章就對不上，要先移除舊版——
  **移除就會刪掉手機上的上傳紀錄**，有資料之後要先提醒使用者匯出
- 使用者要 APK 時給 release 版（`app/build/outputs/apk/release/app-release.apk`），
  複製到 scratchpad 改名成 `imgur-share-<commit>.apk`，再用 SendUserFile（`display: attach`）傳送
- README 的下載連結是 `releases/latest/download/imgur-share.apk`，GitHub Release 的 APK 檔名要固定是 `imgur-share.apk`。
  雲端 session 不能建立或編輯 Release（proxy 回 403），把 APK 和 release notes 交給使用者在 GitHub 網頁上建立
- README 的展示 GIF 和截圖在 `docs/images/`，完整影片在 `docs/demo.mp4`（GitHub README 不能播放 repo 裡的 mp4，只能放連結）。
  畫面改版後從 `android-media` 重新產生，GIF 跳過開頭的桌面、2 倍速：
  `ffmpeg -ss 9.5 -i demo.mp4 -vf "setpts=PTS/2,fps=8,scale=300:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" -loop 0 demo.gif`
- AGP 9.x 使用 built-in Kotlin：**不要**套 `org.jetbrains.kotlin.android`，Compose 要套 `org.jetbrains.kotlin.plugin.compose`
- 版本起點（2026-10 查的最新穩定版）：AGP 9.4.1、Kotlin / Compose compiler plugin 2.4.21、Gradle wrapper 9.8.2、
  Compose BOM 2026.09.00、activity-compose 1.13.0、core-ktx 1.19.1、lifecycle-runtime-ktx 2.11.0、
  androidx.test runner 1.7.0、core 1.7.0、ext:junit 1.3.0、uiautomator 2.4.0。
  功能依賴（2026-10 查的）：KSP 2.3.12、Room 3.0.3（`androidx.room3`）、sqlite-framework 2.7.1、WorkManager 2.12.0、
  DataStore 1.3.0、lifecycle-viewmodel-compose / runtime-compose 2.11.0、OkHttp / mockwebserver3 5.5.1、
  kotlinx-serialization-json 1.12.0（只用 `JsonElement`，不需要 serialization plugin）、kotlinx-coroutines-test 1.11.0。
  若 build 報版本相容問題，以錯誤訊息為準調整，並更新這段

## 雲端 VM 環境

- Android SDK 由環境的 setup script（`scripts/cloud-setup.sh`）裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供
- SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central 回 `429 Too Many Requests`（共用出口 IP 被限流），
  隔 20 秒左右重跑 `./gradlew` 即可，依賴進快取後就不會再遇到；不要為此改 repository 設定（CI 不受影響）

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest
```

`assembleRelease` 會跑 R8，可以先抓到缺 class 之類的錯誤；執行時才會出現的問題要看 CI 的 release 冒煙測試。
和 Android 無關的邏輯（解析 imgur 回應、組剪貼簿文字）寫成純 Kotlin，放在 JVM 單元測試裡測，幾秒就有結果。

## 錄影測試：DemoRecordingTest

`app/src/androidTest/java/com/twtrubiks/imgurshare/DemoRecordingTest.kt`，CI 只跑這個 class，
`record.sh` 在它執行期間用 `screenrecord` 錄整個螢幕。

- 用 UiAutomator 操作（真實時間驅動，動畫會正常播放），不要用 Compose test rule
- 一個 `@Test` 跑完整的展示流程，每個操作之間停 1～1.5 秒讓影片看得清楚
- 全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- 用 `By.text(...)` 找元件；要用 testTag 時，在 root 加 `Modifier.semantics { testTagsAsResourceId = true }` 再用 `By.res(...)`
- 要確認細節的畫面用測試裡的 `screenshot("名稱")` 另存截圖
- CI 沒有 Client-ID，也不能真的上傳到 imgur（而且錄影結果是公開的）。
  測試跑在 App 的 process 裡，`@Before` 把 `AppContainer.imgurClient` 換成指向 MockWebServer 的 client（`FakeImgur`），
  WorkManager 的上傳也會用它；Client-ID 輸入假的 `demo-client-id`。
  debug 版的 `src/debug/res/xml/network_security_config.xml` 允許明文連到 `localhost`，release 版不受影響
- 假的圖片 id 用 8 碼（`demo0001`），imgur 的 id 是 5 或 7 碼，不會剛好對到別人的圖
- 展示用的圖由測試自己畫，寫到 App 的外部資料夾後用 shell 複製到 `/sdcard/Pictures/ImgurShareDemo/` 再請媒體掃描
  （App 沒有儲存權限）。相簿選取和「檔案」App 的分享都在 DocumentsUI（`com.android.documentsui`）裡操作：
  側邊欄 → Images → ImgurShareDemo，長按第一張進入多選

## 錄影流程（GitHub Actions）

1. push 到目前的 branch；改到 `app/**`、`gradle/**`、`*.gradle.kts`、`gradle.properties` 會自動觸發。
   只改 `scripts/ci/**` 或 workflow 時要手動觸發：
   `gh api -X POST 'repos/{owner}/{repo}/actions/workflows/android-record.yml/dispatches' -f ref=<branch>`
   錄影一律用 API 28（最低支援的 Android 9）的模擬器，push 和手動觸發都是；不要另外再跑其他 API level。
   使用者明確要求其他版本時才在手動觸發加上 `-f "inputs[api-level]=<level>"`（例如 Android 14 是 34），
   不同 API level 會同時跑，但都寫到同一個 `android-media` branch，用 `info.txt` 的 `api level` 分辨。
   只改文件（`CLAUDE.md`、`README.md`、`docs/**`）不會觸發；萬一只改文件卻觸發了 build 或錄影，不用問使用者，直接取消：
   `gh api -X POST 'repos/{owner}/{repo}/actions/runs/<id>/cancel'`
2. 查進度：`gh api "repos/{owner}/{repo}/actions/runs?branch=<branch>&per_page=1" --jq '.workflow_runs[0] | {id, status, conclusion, head_sha}'`
   （雲端 session 的 GitHub proxy 擋 GraphQL，`gh pr` / `gh issue` 不能用，一律用 `gh api` 打 REST）
3. 看哪一步失敗：`gh api 'repos/{owner}/{repo}/actions/runs/<id>/jobs' --jq '.jobs[] | {name, conclusion, steps: [.steps[] | select(.conclusion=="failure") | .name]}'`
4. 不論成功或失敗，run 結束後都讀 `android-media` branch（每次會被覆蓋，先核對 `info.txt` 的 commit 是不是自己這次的）：

   ```bash
   git fetch origin android-media
   rm -rf /tmp/media && mkdir -p /tmp/media
   git archive origin/android-media | tar -x -C /tmp/media
   ```

   - `/tmp/media/frames/*.png`：每 2 秒一張影格，用 Read 看圖確認畫面
   - `/tmp/media/screenshots/*.png`：測試在關鍵畫面存的截圖（半解析度）
   - `/tmp/media/screenshots/release-*.png`：錄影結束後覆蓋安裝 release 版拍的截圖；
     `release-smoke.txt` 是啟動時間，`logcat-release.txt` 有 `FATAL EXCEPTION` 時 job 會失敗
   - `/tmp/media/info.txt`：branch、commit、api level、run 網址、job status
   - `/tmp/media/gradle-build.txt`：CI 的 Gradle build 輸出
   - `/tmp/media/instrument.txt`、`logcat.txt`：測試輸出與 log
   - `/tmp/media/demo.mp4`：壓縮後的影片（給使用者看）

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。
傳之前先核對 `info.txt` 的 commit 是這次的。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
