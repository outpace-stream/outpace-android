package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlsTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"
    private val local = Endpoint.loopback(6878)

    @Test fun liveUrl() = assertEquals("http://127.0.0.1:6878/streams/ace/cid:$id", StreamUrls.live(local, StreamRef.contentId(id)))

    @Test fun hlsUrl() =
        assertEquals("http://192.168.1.5:7000/streams/ace/cid:$id.m3u8", StreamUrls.hls(Endpoint("192.168.1.5", 7000), StreamRef.contentId(id)))

    @Test fun statusUrlKeepsPrefixedId() =
        assertEquals("http://127.0.0.1:6878/streams/ace/cid%3A$id/status", StreamUrls.status(local, "ace", "cid:$id"))

    @Test fun statusUrlEncodesPathSegments() =
        assertEquals("http://127.0.0.1:6878/streams/fix/a%2Fb%20c/status", StreamUrls.status(local, "fix", "a/b c"))

    @Test fun infohashUsesIhSelector() =
        assertEquals("http://127.0.0.1:6878/streams/ace/ih:$id", StreamUrls.live(local, StreamRef.infohash(id)))

    @Test fun searchEncodesQuery() =
        assertEquals("http://127.0.0.1:6878/search?query=a%20b%26c&page=2&page_size=15", StreamUrls.search(local, "a b&c", 2, 15))

    @Test fun healthUrl() = assertEquals("http://127.0.0.1:6878/healthz", StreamUrls.health(local))

    @Test fun streamsUrl() = assertEquals("http://192.168.1.5:7000/streams", StreamUrls.streams(Endpoint("192.168.1.5", 7000)))

    @Test fun liveUrlForIpv6Remote() =
        assertEquals("http://[fd00::5]:7000/streams/ace/cid:$id", StreamUrls.live(Endpoint("fd00::5", 7000), StreamRef.contentId(id)))

    @Test fun versionUrl() =
        assertEquals("http://192.168.1.5:7000/server/api?method=get_version", StreamUrls.version(Endpoint("192.168.1.5", 7000)))
}
