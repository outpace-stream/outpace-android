package io.github.outpacestream.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Where an engine's HTTP API answers. An IPv6 [host] is stored without brackets. */
data class Endpoint(val host: String, val port: Int) {
    private val hostPart: String get() = if (':' in host) "[$host]" else host

    /** `host:port`, as the user types it. */
    val display: String get() = "$hostPart:$port"

    /** The URL prefix requests and players use. */
    val base: String get() = "http://$display"

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        fun loopback(port: Int) = Endpoint(LOOPBACK_HOST, port)
    }
}

/** An Outpace engine on another device, added by the user. */
data class RemoteEngine(val id: String, val name: String, val endpoint: Endpoint)

object RemoteEngines {
    const val MAX_REMOTES = 16
    const val MAX_NAME = 40

    private val hostName = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")
    private val ipv6 = Regex("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*")

    /**
     * Reads `host[:port]` or `[ipv6][:port]`, tolerating a leading `http://` or `https://` and one
     * trailing slash. The port defaults to the engine's. Returns null for anything else.
     */
    fun parseAddress(text: String): Endpoint? {
        var s = text.trim()
        for (scheme in listOf("http://", "https://")) {
            if (s.startsWith(scheme, ignoreCase = true)) {
                s = s.substring(scheme.length)
                break
            }
        }
        s = s.removeSuffix("/")
        if (s.isEmpty()) return null
        val host: String
        val portText: String?
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            host = s.substring(1, close)
            val rest = s.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            if (!ipv6.matches(host)) return null
        } else {
            val parts = s.split(':')
            if (parts.size > 2) return null
            host = parts[0]
            portText = parts.getOrNull(1)
            if (!hostName.matches(host)) return null
        }
        val port = if (portText == null) EngineSettings.DEFAULT_PORT else portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Endpoint(host, port)
    }

    fun validName(name: String): Boolean = name.isNotBlank() && name.trim().length <= MAX_NAME

    fun encode(list: List<RemoteEngine>): String = JSONArray().apply {
        for (r in list) {
            put(JSONObject().put("id", r.id).put("name", r.name).put("host", r.endpoint.host).put("port", r.endpoint.port))
        }
    }.toString()

    /** Drops entries that would not pass validation now and keeps at most [MAX_REMOTES]. */
    fun decode(json: String?): List<RemoteEngine> {
        if (json == null) return emptyList()
        val array = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<RemoteEngine>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = o.optString("name").takeIf(::validName)?.trim() ?: continue
            val host = o.optString("host").takeIf { it.isNotBlank() } ?: continue
            val endpoint = parseAddress(Endpoint(host, o.optInt("port", 0)).display) ?: continue
            out += RemoteEngine(id, name, endpoint)
            if (out.size == MAX_REMOTES) break
        }
        return out
    }

    fun newId(): String = UUID.randomUUID().toString()
}
