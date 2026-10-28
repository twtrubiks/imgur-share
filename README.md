# Imgur Share

把圖片匿名上傳到 imgur、拿回連結的 Android App（Android 9 以上）。

以 [minimgur](https://github.com/arthow4n/minimgur) 為功能規格，用 Kotlin + Jetpack Compose 重寫。目前還在骨架階段。

## 開發

全程在 [Claude Code 雲端 session](https://claude.ai/code) 開發，畫面由 GitHub Actions 的模擬器錄影確認。

- 環境設定：[docs/cloud-setup.md](docs/cloud-setup.md)
- 專案約定與開發流程：[CLAUDE.md](CLAUDE.md)

## 使用

上傳功能完成後，需要自己的 imgur Client-ID：到 <https://api.imgur.com/oauth2/addclient> 申請，在 App 第一次開啟時輸入。
