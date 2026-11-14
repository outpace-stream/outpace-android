package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndRecentTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"
    private val ih = StreamRef.infohash(id)
    private val cid = StreamRef.contentId(id)

    @Test fun refSelectors() {
        assertEquals("cid:$id", cid.selector)
        assertEquals("ih:$id", ih.selector)
        assertEquals(ih, StreamRef.parse("ih:$id"))
        assertEquals(cid, StreamRef.parse("cid:$id"))
    }

    @Test fun refParseRejectsBareAndMalformed() {
        assertNull(StreamRef.parse(id))
        assertNull(StreamRef.parse("ih:${id.dropLast(1)}"))
        assertNull(StreamRef.parse("xx:$id"))
        assertNull(StreamRef.parse("ih:${id.uppercase()}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refRejectsInvalidHex() {
        StreamRef(StreamRef.Kind.Infohash, "zz")
    }

    @Test fun parsesSearchPage() {
        val json = """{"result":{"total":3,"results":[
            {"name":"Synthetic Channel","items":[
              {"infohash":"${id.uppercase()}","name":"Synthetic Stream","categories":["tv","news"],
               "bitrate":1234,"availability":0.5,"languages":["eng"],"countries":["xx"],"disabled":false},
              {"infohash":"not-hex","name":"Dropped"}]},
            {"name":"Empty Group","items":[]}]},"error":null}"""
        val page = SearchJson.parse(json)
        assertEquals(3, page.total)
        assertEquals(1, page.groups.size)
        val g = page.groups.single()
        assertEquals("Synthetic Channel", g.name)
        val item = g.items.single()
        assertEquals(ih, item.ref)
        assertEquals("Synthetic Stream", item.name)
        assertEquals(listOf("tv", "news"), item.categories)
        assertEquals(1234L, item.bitrate)
        assertEquals(0.5, item.availability!!, 0.0)
        assertEquals(listOf("eng"), item.languages)
        assertFalse(item.disabled)
    }

    @Test fun rawGroupCountSurvivesFiltering() {
        val json = """{"result":{"total":30,"results":[
            {"name":"Synthetic A","items":[{"infohash":"$id"}]},
            {"name":"Synthetic B","items":[{"infohash":"not-hex"}]}]},"error":null}"""
        val page = SearchJson.parse(json)
        assertEquals(1, page.groups.size)
        assertEquals(2, page.rawGroups)
    }

    @Test fun parsesEmptySearch() {
        val page = SearchJson.parse("""{"result":{"total":0,"results":[]},"error":null}""")
        assertEquals(0, page.total)
        assertTrue(page.groups.isEmpty())
    }

    @Test fun searchOptionalFieldsMissing() {
        val page = SearchJson.parse("""{"result":{"total":1,"results":[{"items":[{"infohash":"$id","disabled":true}]}]}}""")
        val item = page.groups.single().items.single()
        assertNull(item.bitrate)
        assertNull(item.availability)
        assertTrue(item.disabled)
        assertEquals("", page.groups.single().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun searchErrorEnvelopeThrows() {
        SearchJson.parse("""{"result":null,"error":"search_timeout"}""")
    }

    @Test fun failureFromStatus() {
        assertEquals(SearchFailure.Unavailable, SearchFailure.fromStatus(404))
        assertEquals(SearchFailure.RateLimited, SearchFailure.fromStatus(429))
        assertEquals(SearchFailure.Busy, SearchFailure.fromStatus(503))
        assertEquals(SearchFailure.Upstream, SearchFailure.fromStatus(502))
        assertEquals(SearchFailure.Timeout, SearchFailure.fromStatus(504))
        assertEquals(SearchFailure.Other, SearchFailure.fromStatus(500))
    }

    @Test fun recentRoundTripKeepsKind() {
        val list = listOf(RecentEntry(ih, "Synthetic Stream"), RecentEntry(StreamRef.contentId("0".repeat(40))))
        assertEquals(list, RecentList.decode(RecentList.encode(list)))
    }

    @Test fun recentLegacyIdsAreContentIds() =
        assertEquals(listOf(RecentEntry(cid)), RecentList.decodeLegacy("$id,garbage"))

    @Test fun recentDecodeToleratesGarbage() {
        assertEquals(emptyList<RecentEntry>(), RecentList.decode("not json"))
        assertEquals(emptyList<RecentEntry>(), RecentList.decode("""[{"id":"$id"}]"""))
    }

    @Test fun recentAddMovesToFrontAndKeepsName() {
        val other = RecentEntry(StreamRef.contentId("1".repeat(40)))
        val list = listOf(other, RecentEntry(ih, "Synthetic Stream"))
        val out = RecentList.add(list, RecentEntry(ih))
        assertEquals(listOf(RecentEntry(ih, "Synthetic Stream"), other), out)
    }

    @Test fun recentAddIsBounded() {
        var list = emptyList<RecentEntry>()
        for (i in 0 until 20) list = RecentList.add(list, RecentEntry(StreamRef.contentId(i.toString(16).padStart(40, '0'))))
        assertEquals(RecentList.MAX, list.size)
    }

    @Test fun recentAndMixedKindsStayDistinct() {
        val out = RecentList.add(listOf(RecentEntry(cid)), RecentEntry(ih))
        assertEquals(2, out.size)
    }

    @Test fun queriesAreUniqueAndBounded() {
        var q = emptyList<String>()
        for (i in 0 until 12) q = RecentList.addQuery(q, "query $i")
        q = RecentList.addQuery(q, "QUERY 11")
        assertEquals(RecentList.MAX_QUERIES, q.size)
        assertEquals("QUERY 11", q.first())
        assertEquals(1, q.count { it.equals("query 11", ignoreCase = true) })
        assertEquals(q, RecentList.addQuery(q, "   "))
    }
}
