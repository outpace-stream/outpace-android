package io.github.outpacestream.android.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A loopback server that answers with headers, then the body one byte every `dripMs`. */
class HttpDeadlineTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val url = "http://127.0.0.1:${server.localPort}/status"

    private fun serve(body: String, dripMs: Long) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                s.getInputStream().read(ByteArray(4096))
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                for (b in body.toByteArray()) {
                    if (dripMs > 0) Thread.sleep(dripMs)
                    out.write(b.toInt())
                    out.flush()
                }
            }
        }
    }

    @After fun close() = server.close()

    @Test fun fastResponseReturnsBody() {
        serve("""{"peers":3}""", dripMs = 0)
        assertEquals("""{"peers":3}""", Http.getWithin(url, 1_000))
    }

    @Test fun tricklingResponseStopsAtTheDeadline() {
        // 40 bytes at 100 ms each would take 4 s; every read arrives well inside the read timeout.
        serve("x".repeat(40), dripMs = 100)
        val started = System.nanoTime()
        try {
            Http.getWithin(url, 500)
            fail("expected the deadline to abort the request")
        } catch (_: InterruptedIOException) {
            // expected
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 1_500)
    }

    @Test fun requestWithinReturnsTheErrorBody() {
        val body = """{"error":"loopback_bind","message":"m"}"""
        val request = StringBuilder()
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val buf = ByteArray(4096)
                    val n = s.getInputStream().read(buf)
                    request.append(String(buf, 0, maxOf(n, 0)))
                    s.getOutputStream().apply {
                        write("HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        flush()
                    }
                }
            }
        }
        val r = Http.requestWithin("POST", url, 2_000, """{"network":"ace"}""")
        assertEquals(409, r.code)
        assertEquals(body, r.body)
        assertTrue(request.toString(), request.startsWith("POST /status"))
        assertTrue(request.toString(), request.contains("Content-Type: application/json"))
    }
}
