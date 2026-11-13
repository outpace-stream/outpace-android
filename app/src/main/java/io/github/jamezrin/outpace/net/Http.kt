package io.github.jamezrin.outpace.net

import io.github.jamezrin.outpace.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Blocking HTTP helpers. Call from a background thread only. */
object Http {
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000
    private const val MAX_TEXT = 4 * 1024 * 1024

    /** Closes connections whose total deadline passed; one daemon thread, idle unless a deadline is armed. */
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "http-deadline").apply { isDaemon = true } }

    private fun open(url: String, readTimeout: Int = READ_TIMEOUT, connectTimeout: Int = CONNECT_TIMEOUT): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("User-Agent", "outpace-android/${BuildConfig.VERSION_NAME}")
        }

    fun get(url: String, accept: String? = null, readTimeout: Int = READ_TIMEOUT): String {
        val c = open(url, readTimeout)
        try {
            if (accept != null) c.setRequestProperty("Accept", accept)
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode, url)
            val bytes = c.inputStream.use { it.readBytesBounded(MAX_TEXT) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /**
     * A GET that finishes within [totalMs] overall. Connect and read timeouts apply per step, and a
     * response that trickles in resets the read timer on every read, so a watchdog closes the
     * connection when the deadline passes, which aborts any blocked connect or read.
     */
    fun getWithin(url: String, totalMs: Int): String {
        val deadline = System.nanoTime() + totalMs * 1_000_000L
        val c = open(url, readTimeout = totalMs, connectTimeout = totalMs)
        // On Android, disconnecting cancels a blocked connect or header read. The body loop below
        // also checks the deadline between reads, which works on any runtime.
        val timer = deadlines.schedule({ c.disconnect() }, totalMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode, url)
            val bytes = c.inputStream.use { it.readBytesBounded(MAX_TEXT, deadline) }
            if (timer.isDone) throw InterruptedIOException("deadline of $totalMs ms exceeded")
            return String(bytes, Charsets.UTF_8)
        } catch (e: IOException) {
            if (timer.isDone && e !is InterruptedIOException) throw InterruptedIOException("deadline of $totalMs ms exceeded")
            throw e
        } finally {
            timer.cancel(false)
            c.disconnect()
        }
    }

    /** The `Location` of a redirect, without following it. */
    fun redirectLocation(url: String): String? {
        val c = open(url)
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            val code = c.responseCode
            if (code !in 300..399) throw HttpStatusException(code, url)
            return c.getHeaderField("Location")
        } finally {
            c.disconnect()
        }
    }

    /** Streams [url] to [dest], reporting (done, total) bytes; total is -1 when unknown. */
    fun download(url: String, dest: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode, url)
            val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) {
                            progress(done, total)
                            lastReport = done
                        }
                    }
                    progress(done, total)
                    if (total >= 0 && done != total) throw IOException("download truncated")
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Reads at most [limit] bytes; with a [deadline] (System.nanoTime), stops once it passes. */
    private fun java.io.InputStream.readBytesBounded(limit: Int, deadline: Long = Long.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            if (System.nanoTime() - deadline > 0) throw InterruptedIOException("deadline exceeded")
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("response too large")
        }
        return out.toByteArray()
    }
}
