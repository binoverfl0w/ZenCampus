package io.github.binoverfl0w.zencampus.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MutationTransportTest {
    @Test fun aRetryAfterZero503CannotReplayAPostOrPatchBody() {
        for (method in listOf("POST", "PATCH")) {
            val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
            // Client initialization can take longer than a short accept timeout on a busy build host.
            server.soTimeout = 10_000
            val requests = AtomicInteger()
            val worker = thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { break } catch (_: Exception) { break }
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                        }
                        repeat(contentLength) { input.read() }
                        val n = requests.incrementAndGet()
                        val status = if (n == 1) "503 Service Unavailable" else "200 OK"
                        it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nRetry-After: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }
            val client = OkHttpClient.Builder().singleAttemptMutations().build()
            try {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/write").method(method, "work".toRequestBody()).build()
                client.newCall(request).execute().use { assertEquals(503, it.code) }
                // execute() includes synchronous HTTP follow-ups; a replay would return the second 200.
                assertEquals("$method must not be replayed by an HTTP follow-up", 1, requests.get())
                assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
            } finally {
                server.close(); worker.join(2_000); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }
}
