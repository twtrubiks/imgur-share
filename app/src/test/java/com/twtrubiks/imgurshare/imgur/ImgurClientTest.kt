package com.twtrubiks.imgurshare.imgur

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class ImgurClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var client: ImgurClient

    @Before
    fun setUp() {
        server.start()
        client = ImgurClient(baseUrl = server.url("/3/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun upload_sendsMultipartWithClientId() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"data":{"id":"abc","deletehash":"del","link":"https://i.imgur.com/abc.jpg"},"success":true,"status":200}""")
                .build(),
        )
        val bytes = ByteArray(100_000) { it.toByte() }
        val file = tmp.newFile("photo.jpg").apply { writeBytes(bytes) }
        val progress = CopyOnWriteArrayList<Long>()

        val image = client.upload("my-client-id", file, "image/jpeg") { progress += it }

        assertEquals(UploadedImage("abc", "https://i.imgur.com/abc.jpg", "del"), image)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/3/image", request.url.encodedPath)
        assertEquals("Client-ID my-client-id", request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue(body.contains("""Content-Disposition: form-data; name="image"; filename="photo.jpg""""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test
    fun upload_htmlResponse_throwsImgurException() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("<html><title>Over capacity</title></html>").build())
        val file = tmp.newFile("a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val e = runCatching { client.upload("id", file, "image/png") }.exceptionOrNull()

        assertTrue(e is ImgurException && e.retryable)
    }

    @Test
    fun upload_connectionFailure_isRetryable() = runTest {
        val url = server.url("/3/")
        server.close()
        val file = tmp.newFile("a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val e = runCatching { ImgurClient(baseUrl = url).upload("id", file, "image/png") }.exceptionOrNull()

        assertTrue("$e", e is ImgurException && e.retryable)
    }

    @Test
    fun delete_usesDeletehashInPath() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":true,"success":true,"status":200}""").build())

        client.delete("my-client-id", "del123")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/3/image/del123", request.url.encodedPath)
        assertEquals("Client-ID my-client-id", request.headers["Authorization"])
    }

    @Test
    fun credits() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"data":{"UserLimit":500,"UserRemaining":499,"UserReset":1,"ClientLimit":12500,"ClientRemaining":12490},"success":true,"status":200}""")
                .build(),
        )

        val credits = client.credits("id")

        assertEquals(12490, credits.clientRemaining)
        assertEquals("/3/credits", server.takeRequest().url.encodedPath)
    }

    @Test
    fun delete_failure_throws() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"data":{"error":"not found"},"success":false,"status":404}""").build())

        assertThrows(ImgurException::class.java) {
            kotlinx.coroutines.runBlocking { client.delete("id", "x") }
        }
    }
}
