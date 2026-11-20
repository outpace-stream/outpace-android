package io.github.outpacestream.android.core

data class EngineSettings(
    val lan: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val seeding: Boolean = true,
    val inbound: Boolean = true,
    val portMapping: Boolean = false,
    /** Serve the engine's AceStream-compatible routes (`/ace/getstream`, `/server/api`); on by default, like the engine. */
    val aceCompat: Boolean = true,
    /** Opt-in stream-name search through AceStream's public index (engine 1.0.0 and later). */
    val search: Boolean = false,
    val startupBuffer: StartupBuffer = StartupBuffer.SMOOTH,
    val hlsStart: HlsStart = HlsStart.DEFAULT,
    /** Resolve content ids from peer metadata when the catalog lookup fails. */
    val catalogFallback: Boolean = false,
    val lowMemory: Boolean = false,
    val uploadLimit: UploadLimit = UploadLimit.NORMAL,
    /** TCP peer listener port; also the port announced to peers and mapped on the router. */
    val peerPort: Int = DEFAULT_PEER_PORT,
    /** Present the official Linux AceStream engine profile on the public network (engine 1.1.0 and later). */
    val acestreamIdentity: Boolean = false,
    /** Outbound SOCKS5 proxy (engine 1.2.0 and later); [proxyMode] applies only with a [proxy]. */
    val proxyMode: ProxyMode = ProxyMode.OFF,
    val proxy: SocksProxy? = null,
    /** DLNA casting through the engine's `/cast/...` routes (engine 1.2.0 and later); needs [lan]. */
    val cast: Boolean = false,
) {
    /** Whether the next start sends peer traffic through [proxy] at all, on an engine that supports it. */
    fun proxyOn(proxySupported: Boolean): Boolean = proxySupported && proxy != null && proxyMode != ProxyMode.OFF

    /**
     * Whether the proxy is used from the start. The engine then refuses to start with inbound peers or
     * port mapping explicitly on, so both are emitted off and their rows say why.
     */
    fun alwaysProxied(proxySupported: Boolean): Boolean = proxyOn(proxySupported) && proxyMode == ProxyMode.ALWAYS

    /**
     * The engine's environment. [proxySupported] is false for an engine older than
     * [PROXY_MIN_VERSION], which then gets no proxy variables and keeps inbound and port mapping as set.
     */
    fun env(dataDir: String, cacheDir: String, proxySupported: Boolean = true): Map<String, String> = linkedMapOf<String, String>(
        "OUTPACE_BIND" to "${if (lan) "0.0.0.0" else Endpoint.LOOPBACK_HOST}:$port",
        "OUTPACE_DATA_DIR" to dataDir,
        "OUTPACE_CACHE_DIR" to cacheDir,
        "OUTPACE_ENABLE_SEEDING" to seeding.toString(),
        "OUTPACE_ENABLE_INBOUND" to (inbound && !alwaysProxied(proxySupported)).toString(),
        "OUTPACE_ENABLE_PORT_MAPPING" to (portMapping && !alwaysProxied(proxySupported)).toString(),
        "OUTPACE_ACE_COMPAT" to aceCompat.toString(),
        "OUTPACE_COMPAT_ACESTREAM_SERVICES" to search.toString(),
    ).apply {
        // Tuning variables are set only when they differ from the engine default.
        putAll(startupBuffer.env)
        putAll(hlsStart.env)
        if (catalogFallback) put("OUTPACE_BEP9_CONTENT_ID_FALLBACK", "true")
        if (lowMemory) putAll(LowMemory.env)
        putAll(uploadLimit.env)
        if (peerPort != DEFAULT_PEER_PORT) put("OUTPACE_PEER_LISTEN", "0.0.0.0:$peerPort")
        // Every OUTPACE_ACESTREAM_IDENTITY_* override stays unset: the engine's defaults are the profile.
        if (acestreamIdentity) put("OUTPACE_ACESTREAM_IDENTITY", "true")
        if (proxyOn(proxySupported)) {
            // Holds the password; the app never logs the environment.
            put("OUTPACE_PROXY", proxy!!.url())
            put("OUTPACE_PROXY_MODE", proxyMode.wire!!)
        }
        if (cast) put("OUTPACE_ENABLE_CAST", "true")
    }

    companion object {
        const val DEFAULT_PORT = 6878
        const val DEFAULT_PEER_PORT = 8621

        /** The engine's RTMP ingest stays on its default loopback port, so neither port may take it. */
        const val RTMP_PORT = 1935

        /** The first engine release with `GET /search`. */
        const val SEARCH_MIN_VERSION = "1.0.0"

        /** The first engine release with `OUTPACE_ACESTREAM_IDENTITY`. */
        const val ACESTREAM_IDENTITY_MIN_VERSION = "1.1.0"

        /** Whether an engine at [version] reads `OUTPACE_ACESTREAM_IDENTITY`, so the toggle can show. */
        fun supportsAcestreamIdentity(version: String) =
            Releases.compareVersions(version, ACESTREAM_IDENTITY_MIN_VERSION) >= 0

        /** The first engine release with `OUTPACE_PROXY` and `OUTPACE_PROXY_MODE`. */
        const val PROXY_MIN_VERSION = "1.2.0"

        fun proxySupported(version: String): Boolean = Releases.compareVersions(version, PROXY_MIN_VERSION) >= 0
        /** The first engine release with the `/cast/...` routes. */
        const val CAST_MIN_VERSION = "1.2.0"

        fun validPort(p: Int) = p in 1024..65535

        /**
         * Whether [http] and [peer] can both be bound. The peer listener binds every address, so it
         * collides with the HTTP port even when HTTP is loopback-only.
         */
        fun validPorts(http: Int, peer: Int) =
            validPort(http) && validPort(peer) && http != peer && http != RTMP_PORT && peer != RTMP_PORT
    }
}
