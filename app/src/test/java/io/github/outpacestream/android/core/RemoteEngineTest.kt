package io.github.outpacestream.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEngineTest {
    @Test fun parsesIpv4WithPort() = assertEquals(Endpoint("192.168.1.5", 7000), RemoteEngines.parseAddress("192.168.1.5:7000"))

    @Test fun bareHostGetsDefaultPort() = assertEquals(Endpoint("tv.local", 6878), RemoteEngines.parseAddress("tv.local"))

    @Test fun parsesSchemeAndTrailingSlash() =
        assertEquals(Endpoint("192.168.1.5", 6878), RemoteEngines.parseAddress("  http://192.168.1.5:6878/ "))

    @Test fun parsesHttpsScheme() = assertEquals(Endpoint("tv.local", 7000), RemoteEngines.parseAddress("https://tv.local:7000"))

    @Test fun parsesBracketedIpv6() = assertEquals(Endpoint("fd00::5", 7000), RemoteEngines.parseAddress("[fd00::5]:7000"))

    @Test fun bracketedIpv6WithoutPort() = assertEquals(Endpoint("fd00::5", 6878), RemoteEngines.parseAddress("[fd00::5]"))

    @Test fun rejectsBadPorts() {
        for (t in listOf("192.168.1.5:0", "192.168.1.5:65536", "192.168.1.5:abc", "192.168.1.5:")) assertNull(t, RemoteEngines.parseAddress(t))
    }

    @Test fun rejectsJunk() {
        for (t in listOf("", "   ", "a b", "https://", "host/path", "fd00::5", "[]:6878", "[zz::1]:6878", "a:b:c")) {
            assertNull(t, RemoteEngines.parseAddress(t))
        }
    }

    @Test fun ipv6UrlIsBracketed() = assertEquals("http://[fd00::5]:7000", Endpoint("fd00::5", 7000).base)

    @Test fun ipv4Url() = assertEquals("http://192.168.1.5:6878", Endpoint("192.168.1.5", 6878).base)

    @Test fun displayIpv4() = assertEquals("192.168.1.5:6878", Endpoint("192.168.1.5", 6878).display)

    @Test fun displayIpv6() = assertEquals("[fd00::5]:7000", Endpoint("fd00::5", 7000).display)

    @Test fun loopback() = assertEquals(Endpoint("127.0.0.1", 6878), Endpoint.loopback(6878))

    @Test fun nameRules() {
        assertTrue(RemoteEngines.validName("Living room"))
        assertFalse(RemoteEngines.validName("  "))
        assertFalse(RemoteEngines.validName("x".repeat(41)))
    }

    @Test fun roundTrip() {
        val l = listOf(RemoteEngine("a1", "TV", Endpoint("fd00::5", 7000)), RemoteEngine("b2", "Server", Endpoint("192.168.1.5", 6878)))
        assertEquals(l, RemoteEngines.decode(RemoteEngines.encode(l)))
    }

    @Test fun decodeSkipsInvalidAndCaps() {
        val a = JSONArray()
        a.put(JSONObject().put("id", "x").put("name", "No host").put("port", 6878))
        a.put(JSONObject().put("id", "y").put("name", "Bad port").put("host", "192.168.1.5").put("port", 0))
        a.put(JSONObject().put("id", "z").put("name", " ").put("host", "192.168.1.5").put("port", 6878))
        for (i in 0 until 20) a.put(JSONObject().put("id", "id$i").put("name", "E$i").put("host", "192.168.1.$i").put("port", 6878))
        val decoded = RemoteEngines.decode(a.toString())
        assertEquals(16, decoded.size)
        assertEquals("id0", decoded.first().id)
    }

    @Test fun decodeToleratesGarbage() {
        assertEquals(emptyList<RemoteEngine>(), RemoteEngines.decode("not json"))
        assertEquals(emptyList<RemoteEngine>(), RemoteEngines.decode(null))
    }

    @Test fun newIdsDiffer() = assertTrue(RemoteEngines.newId() != RemoteEngines.newId())
}
