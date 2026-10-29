package com.twtrubiks.imgurshare.imgur

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 給使用者看的錯誤。[retryable] 表示稍後重試可能會成功（網路斷線、imgur 暫時掛掉、限流）；
 * Client-ID 錯誤、檔案格式不支援之類的重試也沒用。
 */
class ImgurException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

data class UploadedImage(val id: String, val link: String, val deletehash: String)

/** `GET /3/credits` 的結果。User* 是以發出請求的 IP 計算的額度。 */
data class Credits(
    val clientRemaining: Long,
    val clientLimit: Long,
    val userRemaining: Long,
    val userLimit: Long,
    /** 使用者額度重置的時間（epoch 秒） */
    val userReset: Long,
)

/**
 * 解析 imgur 的回應，純 Kotlin，在 JVM 單元測試裡測。
 * imgur 的回應不一定是 JSON（地區封鎖頁、over capacity 頁都是 HTML），解析失敗一律轉成 [ImgurException]。
 */
internal object ImgurResponses {

    fun parseUpload(code: Int, body: String): UploadedImage {
        val data = parseSuccess(code, body)["data"] as? JsonObject
        val id = data?.string("id")
        val link = data?.string("link")
        val deletehash = data?.string("deletehash")
        if (id == null || link == null || deletehash == null) {
            throw ImgurException("imgur 的回應缺少圖片網址或 deletehash")
        }
        return UploadedImage(id = id, link = link, deletehash = deletehash)
    }

    fun parseCredits(code: Int, body: String): Credits {
        val data = parseSuccess(code, body)["data"] as? JsonObject
            ?: throw ImgurException("imgur 的回應缺少額度資料")
        fun number(name: String) = (data[name] as? JsonPrimitive)?.longOrNull
            ?: throw ImgurException("imgur 的回應缺少 $name")
        return Credits(
            clientRemaining = number("ClientRemaining"),
            clientLimit = number("ClientLimit"),
            userRemaining = number("UserRemaining"),
            userLimit = number("UserLimit"),
            userReset = number("UserReset"),
        )
    }

    /** 刪除只需要確認成功，`data` 是 `true`。 */
    fun checkDeleted(code: Int, body: String) {
        parseSuccess(code, body)
    }

    private fun parseSuccess(code: Int, body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val retryable = code == 429 || code >= 500
        if (root == null) throw ImgurException(notJsonMessage(code, body), retryable)
        val success = (root["success"] as? JsonPrimitive)?.booleanOrNull
        if (code !in 200..299 || success == false) throw ImgurException(errorMessage(code, root), retryable)
        return root
    }

    private fun errorMessage(code: Int, root: JsonObject): String {
        // data.error 可能是字串，也可能是 {"code": ..., "message": "..."}
        val error = (root["data"] as? JsonObject)?.get("error")
        val detail = error?.asString() ?: (error as? JsonObject)?.string("message")
        return when (code) {
            429 -> "imgur 的上傳額度用完了，請稍後再試"
            401, 403 -> "imgur 拒絕請求（HTTP $code）${detail?.let { "：$it" }.orEmpty()}，請確認設定裡的 Client-ID"
            else -> "imgur 錯誤（HTTP $code）${detail?.let { "：$it" }.orEmpty()}"
        }
    }

    private fun notJsonMessage(code: Int, body: String): String {
        val title = TITLE.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("imgur 回應的不是 JSON（HTTP $code")
            if (title != null) append("：").append(title.take(80))
            append("），可能是服務暫時無法使用，或所在地區被 imgur 封鎖")
        }
    }

    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private fun JsonObject.string(name: String): String? = get(name)?.asString()

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
