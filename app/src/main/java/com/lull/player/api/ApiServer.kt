package com.lull.player.api

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The socket half of the API: accept a connection, read one request, write one response, close.
 *
 * **No keep-alive.** Every response says `Connection: close`. A pool of idle sockets held open
 * against a phone is a cost with no matching benefit here — the callers are scripts making a call
 * every few seconds, not a browser fetching forty assets, and a connection that is always closed is
 * a connection that cannot be left half-read.
 *
 * The accept loop runs on its own thread and hands each connection to a small pool, so one slow
 * request (a hundred-megabyte track being drained by a caller on a bad wifi) cannot stop the next
 * one being served.
 */
class ApiServer(
    private val router: ApiRouter,
    private val bindAddress: String,
    private val port: Int
) {

    companion object {
        private const val TAG = "LullApi"

        /** Enough to serve a few callers at once; small enough that nothing can fan out on us. */
        private const val WORKERS = 4

        /** A client that connects and then says nothing gets dropped rather than held. */
        private const val SOCKET_TIMEOUT_MS = 15_000

        private const val BACKLOG = 16
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    val isRunning: Boolean get() = running.get()

    /** The address the API is actually reachable on, for the UI and the notification to show. */
    val address: String get() = "$bindAddress:$port"

    /** @throws IOException when the port is taken or the address cannot be bound. */
    @Throws(IOException::class)
    fun start() {
        if (running.get()) return
        val socket = ServerSocket(port, BACKLOG, InetAddress.getByName(bindAddress))
        socket.reuseAddress = true
        serverSocket = socket
        workers = Executors.newFixedThreadPool(WORKERS)
        running.set(true)

        acceptThread = Thread({ acceptLoop(socket) }, "lull-api-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "listening on $address")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        workers?.shutdownNow()
        serverSocket = null
        workers = null
        acceptThread = null
        Log.i(TAG, "stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // A close() while we are blocked in accept() lands here; that is a stop, not a fault.
                if (running.get()) Log.w(TAG, "accept failed", e)
                return
            }
            val pool = workers
            if (pool == null || pool.isShutdown) { runCatching { client.close() }; return }
            runCatching { pool.execute { serve(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            runCatching { socket.soTimeout = SOCKET_TIMEOUT_MS }
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val response = try {
                val request = Http.parse(input) ?: return
                router.handle(request)
            } catch (e: ApiException) {
                ApiResponse.error(e.status, e.message ?: "bad request", e.hint)
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: IOException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                ApiResponse.error(500, "${e.javaClass.simpleName}: ${e.message}")
            }

            runCatching { write(output, response) }
                .onFailure { Log.w(TAG, "response failed", it) }
        }
    }

    private fun write(output: OutputStream, response: ApiResponse) {
        val length = if (response.stream != null) response.streamLength else response.body.size.toLong()
        val head = StringBuilder()
            .append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            .append("Content-Type: ${response.contentType}\r\n")
            .apply { if (length >= 0) append("Content-Length: $length\r\n") }
            .append("Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
            // A browser on this device may well be how someone pokes at it; the key is still needed.
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Headers: X-API-Key, Authorization, Content-Type\r\n")
            .append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            .apply {
                for ((name, value) in response.extraHeaders) append("$name: $value\r\n")
            }
            .append("\r\n")
            .toString()

        output.write(head.toByteArray(Charsets.US_ASCII))
        val stream = response.stream
        if (stream != null) {
            stream.use { it.copyTo(output, 64 * 1024) }
        } else {
            output.write(response.body)
        }
        output.flush()
    }
}
