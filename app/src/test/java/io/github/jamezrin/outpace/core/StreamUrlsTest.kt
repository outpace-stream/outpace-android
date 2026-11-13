package io.github.jamezrin.outpace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlsTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"

    @Test fun liveUrl() = assertEquals("http://127.0.0.1:6878/streams/ace/cid:$id", StreamUrls.live("127.0.0.1", 6878, StreamRef.contentId(id)))

    @Test fun hlsUrl() = assertEquals("http://192.168.1.5:7000/streams/ace/cid:$id.m3u8", StreamUrls.hls("192.168.1.5", 7000, StreamRef.contentId(id)))

    @Test fun statusUrlKeepsPrefixedId() =
        assertEquals("http://127.0.0.1:6878/streams/ace/cid%3A$id/status", StreamUrls.status(6878, "ace", "cid:$id"))

    @Test fun statusUrlEncodesPathSegments() =
        assertEquals("http://127.0.0.1:6878/streams/fix/a%2Fb%20c/status", StreamUrls.status(6878, "fix", "a/b c"))

    @Test fun infohashUsesIhSelector() =
        assertEquals("http://127.0.0.1:6878/streams/ace/ih:$id", StreamUrls.live("127.0.0.1", 6878, StreamRef.infohash(id)))

    @Test fun searchEncodesQuery() =
        assertEquals("http://127.0.0.1:6878/search?query=a%20b%26c&page=2&page_size=15", StreamUrls.search(6878, "a b&c", 2, 15))

    @Test fun healthUsesLoopback() = assertEquals("http://127.0.0.1:6878/healthz", StreamUrls.health(6878))
}
