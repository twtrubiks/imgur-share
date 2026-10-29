package com.twtrubiks.imgurshare.imgur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImgurResponsesTest {

    @Test
    fun parseUpload_success() {
        val body = """
            {"data":{"id":"aBc1234","deletehash":"xyzDeleteHash","link":"https://i.imgur.com/aBc1234.png","type":"image/png"},
             "success":true,"status":200}
        """.trimIndent()

        assertEquals(
            UploadedImage(id = "aBc1234", link = "https://i.imgur.com/aBc1234.png", deletehash = "xyzDeleteHash"),
            ImgurResponses.parseUpload(200, body),
        )
    }

    @Test
    fun parseUpload_htmlPage_isRetryableError() {
        val html = "<html><head><title>Imgur is over capacity!</title></head><body>...</body></html>"

        val e = assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(503, html) }

        assertTrue(e.message!!, e.message!!.contains("不是 JSON"))
        assertTrue(e.message!!, e.message!!.contains("Imgur is over capacity!"))
        assertTrue(e.retryable)
    }

    @Test
    fun parseUpload_htmlWith200_isError() {
        // 地區封鎖頁可能是 200 的 HTML
        val e = assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(200, "<!doctype html><p>blocked</p>") }

        assertTrue(e.message!!.contains("HTTP 200"))
        assertFalse(e.retryable)
    }

    @Test
    fun parseUpload_emptyBody_isError() {
        assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(502, "") }
    }

    @Test
    fun parseUpload_jsonArray_isError() {
        assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(200, "[]") }
    }

    @Test
    fun parseUpload_invalidClientId() {
        val body = """{"data":{"error":"Invalid client_id","request":"\/3\/image","method":"POST"},"success":false,"status":403}"""

        val e = assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(403, body) }

        assertTrue(e.message!!, e.message!!.contains("Invalid client_id"))
        assertTrue(e.message!!, e.message!!.contains("Client-ID"))
        assertFalse(e.retryable)
    }

    @Test
    fun parseUpload_errorObject() {
        val body = """{"data":{"error":{"code":1003,"message":"File type invalid (1)","type":"ImgurException"}},"success":false,"status":400}"""

        val e = assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(400, body) }

        assertEquals("imgur 錯誤（HTTP 400）：File type invalid (1)", e.message)
    }

    @Test
    fun parseUpload_rateLimited_isRetryable() {
        val body = """{"data":{"error":"Too Many Requests"},"success":false,"status":429}"""

        val e = assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(429, body) }

        assertTrue(e.retryable)
    }

    @Test
    fun parseUpload_missingDeletehash_isError() {
        val body = """{"data":{"id":"a","link":"https://i.imgur.com/a.png"},"success":true,"status":200}"""

        assertThrows(ImgurException::class.java) { ImgurResponses.parseUpload(200, body) }
    }

    @Test
    fun parseCredits() {
        val body = """
            {"data":{"UserLimit":500,"UserRemaining":480,"UserReset":1793000000,"ClientLimit":12500,"ClientRemaining":12000},
             "success":true,"status":200}
        """.trimIndent()

        assertEquals(
            Credits(clientRemaining = 12000, clientLimit = 12500, userRemaining = 480, userLimit = 500, userReset = 1793000000),
            ImgurResponses.parseCredits(200, body),
        )
    }

    @Test
    fun checkDeleted() {
        ImgurResponses.checkDeleted(200, """{"data":true,"success":true,"status":200}""")

        assertThrows(ImgurException::class.java) {
            ImgurResponses.checkDeleted(404, """{"data":{"error":"Unable to find an image with the id"},"success":false,"status":404}""")
        }
    }
}
