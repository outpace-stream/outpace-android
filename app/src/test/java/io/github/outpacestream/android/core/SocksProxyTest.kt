package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksProxyTest {
    private val local = SocksProxy("127.0.0.1", 1080)
    private val withAuth = SocksProxy("proxy.example.com", 1080, "user", "p@ss:w%rd")

    private fun env(s: EngineSettings, supported: Boolean = true) = s.env("/d", "/c", supported)

    @Test fun offEmitsNoProxyVariables() {
        for (s in listOf(EngineSettings(), EngineSettings(proxy = local), EngineSettings(proxyMode = ProxyMode.ALWAYS))) {
            val e = env(s)
            assertNull(e["OUTPACE_PROXY"])
            assertNull(e["OUTPACE_PROXY_MODE"])
            assertEquals("true", e["OUTPACE_ENABLE_INBOUND"])
        }
    }

    @Test fun alwaysMapsToOnAndForcesInboundAndMappingOff() {
        val e = env(EngineSettings(inbound = true, portMapping = true, proxyMode = ProxyMode.ALWAYS, proxy = local))
        assertEquals("socks5://127.0.0.1:1080", e["OUTPACE_PROXY"])
        assertEquals("on", e["OUTPACE_PROXY_MODE"])
        // The engine refuses to start with either explicitly true next to OUTPACE_PROXY_MODE=on.
        assertEquals("false", e["OUTPACE_ENABLE_INBOUND"])
        assertEquals("false", e["OUTPACE_ENABLE_PORT_MAPPING"])
    }

    @Test fun fallbackKeepsInboundAndMapping() {
        val e = env(EngineSettings(inbound = true, portMapping = true, proxyMode = ProxyMode.FALLBACK, proxy = local))
        assertEquals("fallback", e["OUTPACE_PROXY_MODE"])
        assertEquals("true", e["OUTPACE_ENABLE_INBOUND"])
        assertEquals("true", e["OUTPACE_ENABLE_PORT_MAPPING"])
    }

    @Test fun olderEnginesGetNothingAndKeepInbound() {
        val s = EngineSettings(portMapping = true, proxyMode = ProxyMode.ALWAYS, proxy = local)
        val e = env(s, supported = false)
        assertNull(e["OUTPACE_PROXY"])
        assertNull(e["OUTPACE_PROXY_MODE"])
        assertEquals("true", e["OUTPACE_ENABLE_INBOUND"])
        assertEquals("true", e["OUTPACE_ENABLE_PORT_MAPPING"])
        assertFalse(s.alwaysProxied(false))
        assertTrue(s.alwaysProxied(true))
    }

    @Test fun versionGate() {
        assertEquals("1.2.0", EngineSettings.PROXY_MIN_VERSION)
        assertTrue(EngineSettings.proxySupported("1.2.0"))
        assertTrue(EngineSettings.proxySupported("1.10.0"))
        assertFalse(EngineSettings.proxySupported("1.1.9"))
        assertFalse(EngineSettings.proxySupported("1.2.0-rc.1"))
    }

    @Test fun credentialsArePercentEncoded() {
        assertEquals("socks5://user:p%40ss%3Aw%25rd@proxy.example.com:1080", withAuth.url())
        assertEquals("a%2Fb%3Fc%23d%20e", SocksProxy.encode("a/b?c#d e"))
        assertEquals("%C3%A9-._~", SocksProxy.encode("é-._~"))
    }

    @Test fun ipv6HostsAreBracketed() {
        val p = SocksProxy("::1", 1080)
        assertEquals("[::1]:1080", p.address)
        assertEquals("socks5://[::1]:1080", p.url())
        assertTrue(p.isLoopback)
    }

    @Test fun toStringNeverShowsThePassword() {
        val text = EngineSettings(proxyMode = ProxyMode.FALLBACK, proxy = withAuth).toString()
        assertFalse(text.contains("p@ss"))
        assertFalse(text.contains("w%rd"))
        assertTrue(text.contains("auth=true"))
    }

    @Test fun parsesAddresses() {
        assertEquals("127.0.0.1" to 1080, SocksProxy.parseAddress(" 127.0.0.1:1080 "))
        assertEquals("proxy.example.com" to 9050, SocksProxy.parseAddress("socks5://proxy.example.com:9050/"))
        assertEquals("localhost" to 1080, SocksProxy.parseAddress("SOCKS5H://localhost:1080"))
        assertEquals("2001:db8::1" to 1080, SocksProxy.parseAddress("[2001:db8::1]:1080"))
        for (bad in listOf(
            "", "127.0.0.1", "127.0.0.1:", "127.0.0.1:0", "127.0.0.1:65536", "host:12a", "::1:1080",
            "[::1]", "[nothex]:1080", "user:pw@host:1080", "host:1080/path", "300.1.1.1:1080", "http://host:1080",
            "-bad:1080", "a b:1080",
        )) {
            assertNull(bad, SocksProxy.parseAddress(bad))
        }
    }

    @Test fun validatesCredentials() {
        assertNull(SocksProxy.validate("127.0.0.1:1080", "", ""))
        assertNull(SocksProxy.validate("127.0.0.1:1080", "u", "p"))
        assertEquals(SocksProxy.Problem.ADDRESS, SocksProxy.validate("nope", "", ""))
        assertEquals(SocksProxy.Problem.CREDENTIALS_INCOMPLETE, SocksProxy.validate("127.0.0.1:1080", "u", ""))
        assertEquals(SocksProxy.Problem.CREDENTIALS_INCOMPLETE, SocksProxy.validate("127.0.0.1:1080", "", "p"))
        assertEquals(SocksProxy.Problem.CREDENTIALS_TOO_LONG, SocksProxy.validate("127.0.0.1:1080", "u", "x".repeat(256)))
        // 255 bytes is the limit, counted in UTF-8 bytes rather than characters.
        assertNull(SocksProxy.validate("127.0.0.1:1080", "u", "x".repeat(255)))
        assertEquals(SocksProxy.Problem.CREDENTIALS_TOO_LONG, SocksProxy.validate("127.0.0.1:1080", "é".repeat(128), "p"))
        assertEquals(SocksProxy("10.0.0.2", 1080, "u", "p"), SocksProxy.of("socks5://10.0.0.2:1080", "u", "p"))
    }

    @Test fun loopbackDetection() {
        assertTrue(SocksProxy("localhost", 1).isLoopback)
        assertTrue(SocksProxy("127.4.5.6", 1).isLoopback)
        assertTrue(SocksProxy("::ffff:127.0.0.1", 1).isLoopback)
        assertFalse(SocksProxy("192.168.1.2", 1).isLoopback)
        assertFalse(SocksProxy("127.example.com", 1).isLoopback)
    }

    @Test fun modeKeys() {
        assertEquals(ProxyMode.OFF, ProxyMode.fromKey(null))
        assertEquals(ProxyMode.OFF, ProxyMode.fromKey("bogus"))
        for (m in ProxyMode.entries) assertEquals(m, ProxyMode.fromKey(m.key))
    }

    @Test fun statusReportsTheNetworkPath() {
        val base = StreamInfo("cid:0123456789abcdef0123456789abcdef01234567", null, null, 1)
        assertTrue(StreamsJson.withStatus(base, """{"peers":2,"network_path":"proxy"}""").viaProxy)
        assertFalse(StreamsJson.withStatus(base, """{"peers":2,"network_path":"direct"}""").viaProxy)
        assertFalse(StreamsJson.withStatus(base, """{"peers":2}""").viaProxy)
    }
}
