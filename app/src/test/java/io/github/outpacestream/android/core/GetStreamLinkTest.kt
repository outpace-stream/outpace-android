package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.ContentId.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetStreamLinkTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"
    private val base = "http://127.0.0.1:6878/ace/getstream"

    @Test fun readsId() = assertEquals(Result.Id(id), GetStreamLink.parse("$base?id=$id"))

    @Test fun readsContentId() = assertEquals(Result.Id(id), GetStreamLink.parse("$base?content_id=$id"))

    @Test fun acceptsLocalhost() = assertEquals(Result.Id(id), GetStreamLink.parse("http://localhost:6878/ace/getstream?id=$id"))

    @Test fun ignoresPort() = assertEquals(Result.Id(id), GetStreamLink.parse("http://127.0.0.1:7000/ace/getstream?id=$id"))

    @Test fun acceptsMissingPort() = assertEquals(Result.Id(id), GetStreamLink.parse("http://127.0.0.1/ace/getstream?id=$id"))

    @Test fun acceptsTrailingSlash() = assertEquals(Result.Id(id), GetStreamLink.parse("$base/?id=$id"))

    @Test fun ignoresExtraParams() =
        assertEquals(Result.Id(id), GetStreamLink.parse("$base?format=json&id=$id&pid=1&hlc=1#frag"))

    @Test fun normalisesCase() = assertEquals(Result.Id(id), GetStreamLink.parse("HTTP://LOCALHOST:6878/ace/getstream?ID=${id.uppercase()}"))

    @Test fun decodesEscapes() = assertEquals(Result.Id(id), GetStreamLink.parse("$base?id=%20$id"))

    @Test fun refusesInfohash() = assertEquals(Result.Infohash, GetStreamLink.parse("$base?infohash=$id"))

    @Test fun contentIdWinsOverInfohash() = assertEquals(Result.Id(id), GetStreamLink.parse("$base?infohash=$id&id=$id"))

    @Test fun noneForMissingId() = assertEquals(Result.None, GetStreamLink.parse(base))

    @Test fun noneForShortId() = assertEquals(Result.None, GetStreamLink.parse("$base?id=${id.drop(1)}"))

    @Test fun noneForLongerHex() = assertEquals(Result.None, GetStreamLink.parse("$base?id=${id}0"))

    @Test fun noneForNonHex() = assertEquals(Result.None, GetStreamLink.parse("$base?id=${id.dropLast(1)}g"))

    @Test fun noneForTorrentUrl() = assertEquals(Result.None, GetStreamLink.parse("$base?url=http%3A%2F%2Fexample.org%2Fa.torrent"))

    @Test fun noneForBadEscape() = assertEquals(Result.None, GetStreamLink.parse("$base?id=%zz"))

    @Test fun nullForOtherHost() = assertNull(GetStreamLink.parse("http://192.168.1.5:6878/ace/getstream?id=$id"))

    @Test fun nullForOtherPath() {
        assertNull(GetStreamLink.parse("http://127.0.0.1:6878/ace/getstreams?id=$id"))
        assertNull(GetStreamLink.parse("http://127.0.0.1:6878/streams/ace/cid:$id"))
    }

    @Test fun nullForOtherScheme() {
        assertNull(GetStreamLink.parse("https://127.0.0.1:6878/ace/getstream?id=$id"))
        assertNull(GetStreamLink.parse("acestream://$id"))
    }

    @Test fun toleratesLooseEscapingElsewhere() =
        assertEquals(Result.Id(id), GetStreamLink.parse("$base?title=a b|c&id=$id"))

    @Test fun nullForNonLinks() {
        assertNull(GetStreamLink.parse(""))
        assertNull(GetStreamLink.parse(id))
        assertNull(GetStreamLink.parse("http://127.0.0.1.example.org/ace/getstream?id=$id"))
        assertNull(GetStreamLink.parse("http://127.0.0.1@example.org/ace/getstream?id=$id"))
    }

    @Test fun matchesOnlyGetstream() {
        assertTrue(GetStreamLink.matches("$base?id=$id"))
        assertFalse(GetStreamLink.matches("http://127.0.0.1:6878/streams/ace/cid:$id"))
    }

    /** The native hand-off URL must never look like a getstream link, or Outpace could catch its own intent. */
    @Test fun handOffUrlsAreNotGetstreamLinks() {
        val ref = StreamRef.contentId(id)
        assertFalse(GetStreamLink.matches(StreamUrls.live(Endpoint.loopback(6878), ref)))
        assertFalse(GetStreamLink.matches(StreamUrls.hls(Endpoint.loopback(6878), ref)))
    }
}
