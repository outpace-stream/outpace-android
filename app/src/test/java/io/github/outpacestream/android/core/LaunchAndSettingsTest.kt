package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchAndSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun api29UsesLinker64() =
        assertEquals(listOf("/system/bin/linker64", "/e/outpace", "serve"), Launch.command(29, true, "/e/outpace"))

    @Test fun api29Uses32BitLinker() =
        assertEquals(listOf("/system/bin/linker", "/e/outpace", "serve"), Launch.command(35, false, "/e/outpace"))

    @Test fun api28ExecsDirectly() = assertEquals(listOf("/e/outpace", "serve"), Launch.command(28, true, "/e/outpace"))

    @Test fun elfClassFromHeader() {
        val f = tmp.newFile("e32")
        f.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1))
        assertFalse(Launch.elfIs64(f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonElf() {
        val f = tmp.newFile("txt")
        f.writeText("#!/bin/sh\n")
        Launch.elfIs64(f)
    }

    @Test fun envLoopbackByDefault() {
        val env = EngineSettings().env("/d", "/c")
        assertEquals("127.0.0.1:6878", env["OUTPACE_BIND"])
        assertEquals("/d", env["OUTPACE_DATA_DIR"])
        assertEquals("/c", env["OUTPACE_CACHE_DIR"])
    }

    @Test fun envLanBind() = assertEquals("0.0.0.0:7000", EngineSettings(lan = true, port = 7000).env("/d", "/c")["OUTPACE_BIND"])

    @Test fun envFlags() {
        val env = EngineSettings(seeding = false, inbound = false, portMapping = true, aceCompat = true).env("/d", "/c")
        assertEquals("false", env["OUTPACE_ENABLE_SEEDING"])
        assertEquals("false", env["OUTPACE_ENABLE_INBOUND"])
        assertEquals("true", env["OUTPACE_ENABLE_PORT_MAPPING"])
        assertEquals("true", env["OUTPACE_ACE_COMPAT"])
        assertEquals("false", env["OUTPACE_COMPAT_ACESTREAM_SERVICES"])
    }

    @Test fun envSearch() =
        assertEquals("true", EngineSettings(search = true).env("/d", "/c")["OUTPACE_COMPAT_ACESTREAM_SERVICES"])

    @Test fun ringDropsOldest() {
        val ring = LogRing(3)
        for (i in 1..5) ring.add("line $i")
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.snapshot())
    }

    @Test fun ringTruncatesLongLines() {
        val ring = LogRing(1)
        ring.add("x".repeat(5000))
        assertEquals(LogRing.MAX_LINE, ring.snapshot().single().length)
    }

    @Test fun parsesStreamsPayload() {
        val id = "0123456789abcdef0123456789abcdef01234567"
        val json = """{"streams":[
            {"network":"ace","id":"cid:$id","clients":2,"metadata":{"title":"News","bitrate":4000000,"categories":[]}},
            {"network":"ace","id":"ih:$id","clients":0,"metadata":{"title":null,"bitrate":null,"categories":[]}}]}"""
        val s = StreamsJson.parse(json)
        assertEquals(StreamInfo("cid:$id", "News", 4_000_000, 2), s[0])
        assertEquals(id, s[0].contentId)
        assertNull(s[1].title)
        assertNull(s[1].bitrate)
        assertNull(s[1].contentId)
        assertEquals(StreamRef.contentId(id), s[0].ref)
        assertEquals(StreamRef.infohash(id), s[1].ref)
    }

    @Test fun addsStatusPeersAndBuffer() {
        val id = "0123456789abcdef0123456789abcdef01234567"
        val base = StreamInfo("cid:$id", "News", null, 1)
        val json = """{"network":"ace","id":"cid:$id","clients":1,"peers":7,"bitrate":4000000,"buffer_ms":3250,"uploaded":0}"""
        val s = StreamsJson.withStatus(base, json)
        assertEquals(7, s.peers)
        assertEquals(3_250L, s.bufferMs)
        assertEquals("News", s.title)
    }

    @Test fun statusWithoutFieldsLeavesThemNull() {
        val s = StreamsJson.withStatus(StreamInfo("cid:x", null, null, 0), """{"peers":null}""")
        assertNull(s.peers)
        assertNull(s.bufferMs)
    }

    @Test fun keepsNetworkFromStreams() {
        val s = StreamsJson.parse("""{"streams":[{"network":"fix","id":"chan","clients":0}]}""")
        assertEquals("fix", s[0].network)
        assertNull(s[0].contentId)
        assertNull(s[0].ref)
    }

    @Test fun parsesEmptyStreams() = assertEquals(emptyList<StreamInfo>(), StreamsJson.parse("""{"streams":[]}"""))
}
