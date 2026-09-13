package com.lull.player.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/**
 * Just enough HTTP/1.1 to serve a local API, and no more.
 *
 * A web server is the wrong dependency for a music player — it would be larger than the feature it
 * serves, and every byte of it ships to every user whether or not they ever enable the API. What is
 * actually needed is: parse a request line, read the headers, read a `Content-Length` body, write a
 * response. That is all this is.
 *
 * Everything here is free of Android types, which is why the whole request/response surface —
 * parsing, routing, auth, the error shapes — is covered by JVM unit tests rather than by hoping.
 */

/** A parsed request. [path] is percent-decoded; [headers] keys are lower-cased. */
data class ApiRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun queryInt(name: String, fallback: Int): Int = query[name]?.toIntOrNull() ?: fallback

    fun queryLong(name: String): Long? = query[name]?.toLongOrNull()

    /** The JSON body, or an empty object — a body-less POST is a legitimate "do it with defaults". */
    fun json(): JSONObject {
        if (body.isEmpty()) return JSONObject()
        val text = String(body, Charsets.UTF_8).trim()
        if (text.isEmpty()) return JSONObject()
        return runCatching { JSONObject(text) }
            .getOrElse { throw ApiException(400, "body is not a JSON object: ${it.message}") }
    }

    /**
     * A list of ids from the body, accepting every spelling a caller might reasonably reach for:
     * `{"ids":[1,2]}`, `{"id":1}`, or a bare `[1,2]`.
     */
    fun idList(): List<Long> {
        if (body.isEmpty()) return emptyList()
        val text = String(body, Charsets.UTF_8).trim()
        if (text.startsWith("[")) {
            val arr = runCatching { JSONArray(text) }
                .getOrElse { throw ApiException(400, "body is not a JSON array: ${it.message}") }
            return (0 until arr.length()).mapNotNull { arr.opt(it).asLongOrNull() }
        }
        val obj = json()
        obj.optJSONArray("ids")?.let { arr ->
            return (0 until arr.length()).mapNotNull { arr.opt(it).asLongOrNull() }
        }
        obj.opt("id")?.asLongOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    // A JSON number may arrive as Integer, Long, Double or String depending on the writer.
    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    // Data classes with an array field need these spelled out; the array is the body bytes.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A response, either fully in memory or streamed.
 *
 * A track can be a hundred megabytes, so [stream] exists to hand the socket a file without first
 * copying it into the heap of a process whose actual job is playing audio.
 */
class ApiResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray = ByteArray(0),
    val stream: InputStream? = null,
    val streamLength: Long = -1,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val reason: String get() = REASONS[status] ?: "OK"

    companion object {
        private val REASONS = mapOf(
            200 to "OK", 201 to "Created", 204 to "No Content", 400 to "Bad Request",
            401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large",
            500 to "Internal Server Error", 503 to "Service Unavailable"
        )

        fun json(obj: JSONObject, status: Int = 200) =
            ApiResponse(status, "application/json; charset=utf-8", obj.toString(2).toByteArray())

        fun json(arr: JSONArray, status: Int = 200) =
            ApiResponse(status, "application/json; charset=utf-8", arr.toString(2).toByteArray())

        fun text(body: String, status: Int = 200, type: String = "text/plain; charset=utf-8") =
            ApiResponse(status, type, body.toByteArray())

        fun ok(vararg pairs: Pair<String, Any?>): ApiResponse {
            val obj = JSONObject().put("ok", true)
            for ((k, v) in pairs) obj.put(k, v ?: JSONObject.NULL)
            return json(obj)
        }

        fun error(status: Int, message: String, hint: String? = null): ApiResponse {
            val obj = JSONObject().put("error", message)
            if (hint != null) obj.put("hint", hint)
            return json(obj, status)
        }

        fun noContent() = ApiResponse(204, "text/plain", ByteArray(0))

        fun stream(input: InputStream, type: String, length: Long, fileName: String?) =
            ApiResponse(
                200, type, stream = input, streamLength = length,
                extraHeaders = if (fileName.isNullOrBlank()) emptyMap()
                else mapOf("Content-Disposition" to "attachment; filename=\"${sanitise(fileName)}\"")
            )

        /** A filename reaches a header here, so quotes and newlines must not travel with it. */
        private fun sanitise(name: String): String =
            name.replace(Regex("[\"\\\\\r\n]"), "_")
    }
}

/** Anything a handler can fail with that the caller should see as a status code, not a 500. */
class ApiException(val status: Int, message: String, val hint: String? = null) : Exception(message)

object Http {

    /** Bodies above this are refused outright: nothing this API accepts is a megabyte of JSON. */
    const val MAX_BODY = 1 shl 20

    /** A request line longer than this is not a request, it is someone probing. */
    private const val MAX_LINE = 8 * 1024

    /**
     * Reads one request off [input].
     *
     * Returns null at a clean end of stream (a closed keep-alive connection, or a port scanner
     * that connected and left), and throws [ApiException] for something that arrived but was not
     * a request this server can answer.
     */
    @Throws(IOException::class)
    fun parse(input: InputStream): ApiRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) throw ApiException(400, "malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw ApiException(413, "body larger than ${MAX_BODY} bytes")
        val body = if (length > 0) readFully(input, length) else ByteArray(0)

        val rawPath = target.substringBefore('?')
        val rawQuery = target.substringAfter('?', "")
        return ApiRequest(
            method = method,
            path = decode(rawPath).trimEnd('/').ifEmpty { "/" },
            query = parseQuery(rawQuery),
            headers = headers,
            body = body
        )
    }

    fun parse(raw: String): ApiRequest? = parse(ByteArrayInputStream(raw.toByteArray()))

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            out[decode(name)] = decode(value)
        }
        return out
    }

    /** A `+` is a space in a query string but a literal `+` in a path, so decode the two apart. */
    fun decode(raw: String): String = runCatching {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(raw)

    /**
     * Reads one CRLF-terminated line as ASCII. Bare LF is accepted too — `curl` sends CRLF, but a
     * hand-rolled test client or a shell one-liner often does not, and refusing those would make
     * the API annoying to poke at, which is the whole point of it.
     */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(b.toChar())
            if (buffer.length > MAX_LINE) throw ApiException(413, "header line too long")
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }
}
