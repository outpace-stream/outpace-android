package io.github.jamezrin.outpace.core

import java.net.URLEncoder

/** URLs a player uses to reach a stream on the local engine. */
object StreamUrls {
    const val LOOPBACK = "127.0.0.1"

    fun base(host: String, port: Int): String = "http://$host:$port"

    fun live(host: String, port: Int, ref: StreamRef): String = "${base(host, port)}/streams/ace/${ref.selector}"

    fun hls(host: String, port: Int, ref: StreamRef): String = live(host, port, ref) + ".m3u8"

    fun health(port: Int): String = "${base(LOOPBACK, port)}/healthz"

    fun streams(port: Int): String = "${base(LOOPBACK, port)}/streams"

    /** One stream's `/status`; `id` is the engine's prefixed id as `/streams` lists it. */
    fun status(port: Int, network: String, id: String): String = "${streams(port)}/${segment(network)}/${segment(id)}/status"

    /** The engine's opt-in stream-name search, `GET /search`. */
    fun search(port: Int, query: String, page: Int, pageSize: Int): String =
        "${base(LOOPBACK, port)}/search?query=${segment(query)}&page=$page&page_size=$pageSize"

    private fun segment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
