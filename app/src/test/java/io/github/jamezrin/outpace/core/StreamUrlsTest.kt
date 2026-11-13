package io.github.jamezrin.outpace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlsTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"

    @Test fun liveUrl() = assertEquals("http://127.0.0.1:6878/streams/ace/cid:$id", StreamUrls.live("127.0.0.1", 6878, id))

    @Test fun hlsUrl() = assertEquals("http://192.168.1.5:7000/streams/ace/cid:$id.m3u8", StreamUrls.hls("192.168.1.5", 7000, id))

    @Test fun statusUrlKeepsPrefixedId() =
        assertEquals("http://127.0.0.1:6878/streams/ace/cid%3A$id/status", StreamUrls.status(6878, "ace", "cid:$id"))

    @Test fun statusUrlEncodesPathSegments() =
        assertEquals("http://127.0.0.1:6878/streams/fix/a%2Fb%20c/status", StreamUrls.status(6878, "fix", "a/b c"))

    @Test fun healthUsesLoopback() = assertEquals("http://127.0.0.1:6878/healthz", StreamUrls.health(6878))
}
