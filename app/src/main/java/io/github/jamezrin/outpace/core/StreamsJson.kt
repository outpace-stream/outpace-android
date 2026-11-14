package io.github.jamezrin.outpace.core

import org.json.JSONObject

/**
 * One entry of `GET /streams`. `id` keeps the engine's prefix (`cid:` or `ih:`). `peers` and
 * `bufferMs` come from the stream's `/status` and are null until it has been fetched.
 */
data class StreamInfo(
    val id: String,
    val title: String?,
    val bitrate: Long?,
    val clients: Int,
    val network: String = "ace",
    val peers: Int? = null,
    val bufferMs: Long? = null,
) {
    /** The bare content id, when the stream is addressed by one. */
    val contentId: String? get() = id.removePrefix("cid:").takeIf { network == "ace" && id.startsWith("cid:") }

    /** The stream as the app can reopen it, for `cid:` and `ih:` streams on the AceStream network. */
    val ref: StreamRef? get() = if (network == "ace") StreamRef.parse(id) else null
}

object StreamsJson {
    fun parse(json: String): List<StreamInfo> {
        val array = JSONObject(json).optJSONArray("streams") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val meta = o.optJSONObject("metadata")
            StreamInfo(
                id = o.getString("id"),
                title = meta?.optString("title")?.takeIf { meta.has("title") && !meta.isNull("title") && it.isNotBlank() },
                bitrate = meta?.takeIf { it.has("bitrate") && !it.isNull("bitrate") }?.getLong("bitrate"),
                clients = o.optInt("clients", 0),
                network = o.optString("network", "ace").ifBlank { "ace" },
            )
        }
    }

    /** Adds the peer count and buffer from `GET /streams/<network>/<id>/status`. */
    fun withStatus(stream: StreamInfo, json: String): StreamInfo {
        val o = JSONObject(json)
        return stream.copy(
            peers = o.takeIf { it.has("peers") && !it.isNull("peers") }?.getInt("peers"),
            bufferMs = o.takeIf { it.has("buffer_ms") && !it.isNull("buffer_ms") }?.getLong("buffer_ms"),
        )
    }
}
