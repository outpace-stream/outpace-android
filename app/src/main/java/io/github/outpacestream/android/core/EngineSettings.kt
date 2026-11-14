package io.github.outpacestream.android.core

data class EngineSettings(
    val lan: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val seeding: Boolean = true,
    val inbound: Boolean = true,
    val portMapping: Boolean = false,
    val aceCompat: Boolean = false,
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
) {
    fun env(dataDir: String, cacheDir: String): Map<String, String> = linkedMapOf<String, String>(
        "OUTPACE_BIND" to "${if (lan) "0.0.0.0" else StreamUrls.LOOPBACK}:$port",
        "OUTPACE_DATA_DIR" to dataDir,
        "OUTPACE_CACHE_DIR" to cacheDir,
        "OUTPACE_ENABLE_SEEDING" to seeding.toString(),
        "OUTPACE_ENABLE_INBOUND" to inbound.toString(),
        "OUTPACE_ENABLE_PORT_MAPPING" to portMapping.toString(),
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
    }

    companion object {
        const val DEFAULT_PORT = 6878
        const val DEFAULT_PEER_PORT = 8621

        /** The engine's RTMP ingest stays on its default loopback port, so neither port may take it. */
        const val RTMP_PORT = 1935

        /** The first engine release with `GET /search`. */
        const val SEARCH_MIN_VERSION = "1.0.0"

        fun validPort(p: Int) = p in 1024..65535

        /**
         * Whether [http] and [peer] can both be bound. The peer listener binds every address, so it
         * collides with the HTTP port even when HTTP is loopback-only.
         */
        fun validPorts(http: Int, peer: Int) =
            validPort(http) && validPort(peer) && http != peer && http != RTMP_PORT && peer != RTMP_PORT
    }
}
