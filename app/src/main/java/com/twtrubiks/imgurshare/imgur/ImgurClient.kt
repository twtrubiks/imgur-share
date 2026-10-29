package com.twtrubiks.imgurshare.imgur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 所有 imgur API 都從這裡呼叫，UI 和 WorkManager 都用它。日後換圖床只要改這一層。
 *
 * [baseUrl] 可以換成 MockWebServer：錄影測試不能真的上傳到 imgur，也沒有真的 Client-ID。
 */
class ImgurClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val http: OkHttpClient = sharedHttpClient,
) {

    /** 匿名上傳一張圖。[onProgress] 回報已經送出的 byte 數，會在 OkHttp 的執行緒上呼叫。 */
    suspend fun upload(
        clientId: String,
        file: File,
        mimeType: String?,
        onProgress: (sentBytes: Long) -> Unit = {},
    ): UploadedImage {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", file.name, ProgressFileBody(file, mimeType?.toMediaTypeOrNull(), onProgress))
            .build()
        val request = Request.Builder()
            .url(baseUrl.resolve("image")!!)
            .header("Authorization", "Client-ID $clientId")
            .post(body)
            .build()
        return call(request, ImgurResponses::parseUpload)
    }

    suspend fun delete(clientId: String, deletehash: String) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("image").addPathSegment(deletehash).build())
            .header("Authorization", "Client-ID $clientId")
            .delete()
            .build()
        call(request, ImgurResponses::checkDeleted)
    }

    suspend fun credits(clientId: String): Credits {
        val request = Request.Builder()
            .url(baseUrl.resolve("credits")!!)
            .header("Authorization", "Client-ID $clientId")
            .build()
        return call(request, ImgurResponses::parseCredits)
    }

    private suspend fun <T> call(request: Request, parse: (code: Int, body: String) -> T): T {
        val (code, body) = try {
            http.newCall(request).await().use { response ->
                withContext(Dispatchers.IO) { response.code to response.body.string() }
            }
        } catch (e: IOException) {
            throw ImgurException("連不上 imgur：${e.message ?: e.javaClass.simpleName}", retryable = true, cause = e)
        }
        return parse(code, body)
    }

    companion object {
        val DEFAULT_BASE_URL = "https://api.imgur.com/3/".toHttpUrl()

        // 共用連線池；上傳大圖時 imgur 收完檔案還要處理一段時間，讀取逾時放寬
        private val sharedHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** 邊寫邊回報進度的檔案 body。重送時 OkHttp 會再呼叫一次 [writeTo]，進度會從 0 重新算。 */
private class ProgressFileBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.source().use { source ->
            var sent = 0L
            while (true) {
                val read = source.read(sink.buffer, SEGMENT_SIZE)
                if (read == -1L) break
                // flush 之後才算真的送出去，進度才不會一開始就跳到 100%
                sink.flush()
                sent += read
                onProgress(sent)
            }
        }
    }

    private companion object {
        const val SEGMENT_SIZE = 16 * 1024L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
