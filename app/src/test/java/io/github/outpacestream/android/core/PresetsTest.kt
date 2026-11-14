package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private val base = EngineSettings().env("/d", "/c")

    @Test fun defaultsEmitNoTuning() {
        assertEquals(8, base.size)
        assertTrue(base.keys.none { it.startsWith("OUTPACE_PREBUFFER") || it.startsWith("OUTPACE_HLS") })
        assertFalse(base.containsKey("OUTPACE_BEP9_CONTENT_ID_FALLBACK"))
    }

    @Test fun startupBufferValues() {
        fun ms(b: StartupBuffer) = EngineSettings(startupBuffer = b).env("/d", "/c")["OUTPACE_PREBUFFER_MS"]
        assertEquals(null, ms(StartupBuffer.SMOOTH))
        assertEquals("15000", ms(StartupBuffer.BALANCED))
        assertEquals("5000", ms(StartupBuffer.FAST))
        assertEquals("0", ms(StartupBuffer.OFF))
    }

    /** The engine refuses to start unless startup segments fit the window and the timeout covers a segment. */
    @Test fun hlsPresetsSatisfyEngineConstraints() {
        for (p in HlsStart.entries) {
            val env = EngineSettings(hlsStart = p).env("/d", "/c")
            val window = env["OUTPACE_HLS_WINDOW_SEGMENTS"]?.toInt() ?: 8
            val startup = env["OUTPACE_HLS_STARTUP_SEGMENTS"]?.toInt() ?: 6
            val duration = env["OUTPACE_HLS_SEGMENT_DURATION_MS"]?.toLong() ?: 5000
            val timeout = env["OUTPACE_HLS_STARTUP_TIMEOUT_MS"]?.toLong() ?: 45000
            assertTrue(p.key, startup in 0..window)
            assertTrue(p.key, duration >= 1 && timeout >= duration)
        }
    }

    @Test fun prebufferPresetsAreNonNegative() {
        for (p in StartupBuffer.entries) p.env["OUTPACE_PREBUFFER_MS"]?.let { assertTrue(p.key, it.toLong() >= 0) }
    }

    @Test fun catalogFallback() =
        assertEquals("true", EngineSettings(catalogFallback = true).env("/d", "/c")["OUTPACE_BEP9_CONTENT_ID_FALLBACK"])

    @Test fun keysAreStableAndUnknownReadsDefault() {
        assertEquals(listOf("smooth", "balanced", "fast", "off"), StartupBuffer.entries.map { it.key })
        assertEquals(listOf("default", "fast"), HlsStart.entries.map { it.key })
        assertEquals(StartupBuffer.SMOOTH, StartupBuffer.fromKey(null))
        assertEquals(StartupBuffer.SMOOTH, StartupBuffer.fromKey("turbo"))
        assertEquals(HlsStart.DEFAULT, HlsStart.fromKey("nope"))
        for (p in StartupBuffer.entries) assertEquals(p, StartupBuffer.fromKey(p.key))
        for (p in HlsStart.entries) assertEquals(p, HlsStart.fromKey(p.key))
    }

    @Test fun lowMemoryEmitsDiskCacheAndSmallerBuffers() {
        val env = EngineSettings(lowMemory = true).env("/d", "/c")
        assertEquals("disk", env["OUTPACE_CACHE_TYPE"])
        assertEquals("33554432", env["OUTPACE_SEED_STORE_BYTES"])
        assertEquals("33554432", env["OUTPACE_PREBUFFER_BYTES"])
        assertEquals("64", env["OUTPACE_SESSION_BUFFER"])
        assertEquals("/c", env["OUTPACE_CACHE_DIR"])
    }

    @Test fun uploadLimitValues() {
        assertTrue(UploadLimit.NORMAL.env.isEmpty())
        val low = EngineSettings(uploadLimit = UploadLimit.LOW).env("/d", "/c")
        assertEquals("16", low["OUTPACE_MAX_INBOUND"])
        assertEquals("4", low["OUTPACE_MAX_INBOUND_PER_STREAM"])
        assertEquals("2", low["OUTPACE_MAX_UNCHOKED"])
        assertEquals("0", UploadLimit.MINIMAL.env["OUTPACE_MAX_UNCHOKED"])
        for (p in UploadLimit.entries) {
            val total = p.env["OUTPACE_MAX_INBOUND"]?.toInt() ?: 64
            val perStream = p.env["OUTPACE_MAX_INBOUND_PER_STREAM"]?.toInt() ?: 16
            assertTrue(p.key, perStream in 1..total)
        }
        assertEquals(listOf("normal", "low", "minimal"), UploadLimit.entries.map { it.key })
        assertEquals(UploadLimit.NORMAL, UploadLimit.fromKey("bogus"))
    }

    @Test fun peerPortOnlyWhenChanged() {
        assertFalse(base.containsKey("OUTPACE_PEER_LISTEN"))
        assertEquals("0.0.0.0:9000", EngineSettings(peerPort = 9000).env("/d", "/c")["OUTPACE_PEER_LISTEN"])
    }

    @Test fun portsMustNotCollide() {
        assertTrue(EngineSettings.validPorts(EngineSettings.DEFAULT_PORT, EngineSettings.DEFAULT_PEER_PORT))
        assertFalse(EngineSettings.validPorts(7000, 7000))
        assertFalse(EngineSettings.validPorts(1935, 8621))
        assertFalse(EngineSettings.validPorts(6878, 1935))
        assertFalse(EngineSettings.validPorts(6878, 80))
    }
}
