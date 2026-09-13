package com.lull.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The hand-rolled HTTP parser.
 *
 * It is small, which is the argument for writing it and also the argument for testing it: the
 * failure mode of a parser that is nearly right is an endpoint that works from `curl` and not from
 * whatever the caller actually used.
 */
class HttpTest {

    private fun raw(text: String) = Http.parse(ByteArrayInputStream(text.toByteArray()))!!

    @Test
    fun `a plain GET gives method, path and headers`() {
        val r = raw("GET /api/player HTTP/1.1\r\nHost: 127.0.0.1\r\nX-API-Key: abc\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/api/player", r.path)
        assertEquals("abc", r.header("X-API-Key"))
        // Header lookup must not care about case; senders disagree about it constantly.
        assertEquals("abc", r.header("x-api-key"))
        assertEquals("127.0.0.1", r.header("HOST"))
    }

    /** curl sends CRLF; a shell one-liner or a hand-written client often does not. */
    @Test
    fun `bare LF line endings are accepted`() {
        val r = raw("GET /api/health HTTP/1.1\nHost: x\n\n")
        assertEquals("/api/health", r.path)
    }

    @Test
    fun `a body is read to its Content-Length`() {
        val body = """{"ids":[1,2]}"""
        val r = raw("POST /api/player/play HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals("POST", r.method)
        assertEquals(listOf(1L, 2L), r.idList())
    }

    @Test
    fun `query parameters are split and percent-decoded`() {
        val r = raw("GET /api/tracks?q=boards%20of%20canada&limit=5 HTTP/1.1\r\n\r\n")
        assertEquals("/api/tracks", r.path)
        assertEquals("boards of canada", r.query["q"])
        assertEquals(5, r.queryInt("limit", 200))
        assertEquals(200, r.queryInt("offset", 200))
    }

    /** Group keys hold folder paths, so this is the request that carries a whole path in a value. */
    @Test
    fun `a percent-encoded path lands in a query value intact`() {
        val r = raw("GET /api/group?key=folder%3A%2Fstorage%2Femulated%2F0%2FMusic HTTP/1.1\r\n\r\n")
        assertEquals("folder:/storage/emulated/0/Music", r.query["key"])
    }

    @Test
    fun `a trailing slash addresses the same route`() {
        assertEquals("/api/player", raw("GET /api/player/ HTTP/1.1\r\n\r\n").path)
        assertEquals("/", raw("GET / HTTP/1.1\r\n\r\n").path)
    }

    @Test
    fun `an empty connection is not a request`() {
        assertNull(Http.parse(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a malformed request line is refused with 400`() {
        val e = assertThrows(ApiException::class.java) { raw("GARBAGE\r\n\r\n") }
        assertEquals(400, e.status)
    }

    @Test
    fun `an oversized body is refused rather than read`() {
        val e = assertThrows(ApiException::class.java) {
            raw("POST /api/x HTTP/1.1\r\nContent-Length: ${Http.MAX_BODY + 1}\r\n\r\n")
        }
        assertEquals(413, e.status)
    }

    @Test
    fun `ids arrive as a list, a single id, or a bare array`() {
        fun body(text: String) =
            ApiRequest("POST", "/api/x", body = text.toByteArray())
        assertEquals(listOf(1L, 2L), body("""{"ids":[1,2]}""").idList())
        assertEquals(listOf(7L), body("""{"id":7}""").idList())
        assertEquals(listOf(3L, 4L), body("[3,4]").idList())
        // A writer that quotes its numbers is wrong but common, and easy to be kind about.
        assertEquals(listOf(9L), body("""{"ids":["9"]}""").idList())
        assertEquals(emptyList<Long>(), body("").idList())
        assertEquals(emptyList<Long>(), body("{}").idList())
    }

    @Test
    fun `a body that is not JSON is the caller's fault, not a 500`() {
        val e = assertThrows(ApiException::class.java) {
            ApiRequest("POST", "/api/x", body = "not json".toByteArray()).json()
        }
        assertEquals(400, e.status)
    }

    @Test
    fun `an absent body reads as an empty object`() {
        assertEquals(0, ApiRequest("POST", "/api/x").json().length())
    }

    @Test
    fun `a response carries its own status line wording`() {
        assertEquals("Not Found", ApiResponse.error(404, "nope").reason)
        assertEquals("OK", ApiResponse.text("hi").reason)
        assertTrue(String(ApiResponse.error(404, "nope", "try x").body).contains("try x"))
    }
}
