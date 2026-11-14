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
}
