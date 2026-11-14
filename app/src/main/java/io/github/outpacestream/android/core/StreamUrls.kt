package io.github.outpacestream.android.core

import java.net.URLEncoder

/** URLs that reach an engine's HTTP API, on this device or on another one. */
object StreamUrls {
    fun live(e: Endpoint, ref: StreamRef): String = "${e.base}/streams/ace/${ref.selector}"

    fun hls(e: Endpoint, ref: StreamRef): String = live(e, ref) + ".m3u8"

    fun health(e: Endpoint): String = "${e.base}/healthz"

    fun streams(e: Endpoint): String = "${e.base}/streams"

    /** One stream's `/status`; `id` is the engine's prefixed id as `/streams` lists it. */
    fun status(e: Endpoint, network: String, id: String): String = "${streams(e)}/${segment(network)}/${segment(id)}/status"

    /** The engine's opt-in stream-name search, `GET /search`. */
    fun search(e: Endpoint, query: String, page: Int, pageSize: Int): String =
        "${e.base}/search?query=${segment(query)}&page=$page&page_size=$pageSize"

    /** The compat `get_version` call; only engines with the AceStream compat API answer it. */
    fun version(e: Endpoint): String = "${e.base}/server/api?method=get_version"

    private fun segment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
