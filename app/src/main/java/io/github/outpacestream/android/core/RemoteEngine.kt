package io.github.outpacestream.android.core

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an engine's HTTP API answers. An IPv6 [host] is stored without brackets. [secure] is set
 * for an engine an operator serves through an HTTPS reverse proxy; engines themselves speak HTTP.
 */
data class Endpoint(val host: String, val port: Int, val secure: Boolean = false) {
    private val authority: String get() = (if (':' in host) "[$host]" else host) + ":$port"

    /** `host:port`, or `https://host:port`, as the user types it; [RemoteEngines.parseAddress] reads it back. */
    val display: String get() = if (secure) "https://$authority" else authority

    /** The URL prefix requests and players use. */
    val base: String get() = (if (secure) "https://" else "http://") + authority

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

    /** The port an `https://` address without one uses: a reverse proxy's, not the engine's. */
    const val HTTPS_PORT = 443

    private val hostName = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")
    private val hexGroup = Regex("[0-9A-Fa-f]{1,4}")
    private val ipv4 = Regex("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")

    /**
     * A literal IPv6 address (RFC 4291 text form, no zone id): eight hex groups, or fewer with one
     * `::`, optionally ending in a dotted IPv4 address that counts as two groups. Pure parsing, so
     * an invalid literal is never handed to a resolver.
     */
    internal fun isIpv6(text: String): Boolean {
        val halves = text.split("::")
        if (halves.size > 2) return false
        fun groups(part: String, last: Boolean): Int? {
            if (part.isEmpty()) return 0
            val items = part.split(':')
            var n = 0
            for ((i, g) in items.withIndex()) {
                n += when {
                    hexGroup.matches(g) -> 1
                    last && i == items.lastIndex && ipv4.matches(g) -> 2
                    else -> return null
                }
            }
            return n
        }
        val compressed = halves.size == 2
        val head = groups(halves[0], last = !compressed) ?: return false
        val tail = if (compressed) groups(halves[1], last = true) ?: return false else 0
        val total = head + tail
        return if (compressed) total <= 7 else total == 8
    }

    /**
     * Reads `host[:port]` or `[ipv6][:port]`, with an optional `http://` or `https://` and one
     * trailing slash. `https://` keeps HTTPS (an engine behind a reverse proxy) and defaults to
     * port 443; otherwise the port defaults to the engine's. Returns null for anything else.
     */
    fun parseAddress(text: String): Endpoint? {
        var s = text.trim()
        var secure = false
        if (s.startsWith("https://", ignoreCase = true)) {
            secure = true
            s = s.substring("https://".length)
        } else if (s.startsWith("http://", ignoreCase = true)) {
            s = s.substring("http://".length)
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
            if (!isIpv6(host)) return null
        } else {
            val parts = s.split(':')
            if (parts.size > 2) return null
            host = parts[0]
            portText = parts.getOrNull(1)
            if (!hostName.matches(host)) return null
        }
        val defaultPort = if (secure) HTTPS_PORT else EngineSettings.DEFAULT_PORT
        val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Endpoint(host, port, secure)
    }

    fun validName(name: String): Boolean = name.isNotBlank() && name.trim().length <= MAX_NAME

    fun encode(list: List<RemoteEngine>): String = JSONArray().apply {
        for (r in list) {
            put(JSONObject().put("id", r.id).put("name", r.name).put("host", r.endpoint.host).put("port", r.endpoint.port).put("secure", r.endpoint.secure))
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
            val endpoint = parseAddress(Endpoint(host, o.optInt("port", 0), o.optBoolean("secure", false)).display) ?: continue
            out += RemoteEngine(id, name, endpoint)
            if (out.size == MAX_REMOTES) break
        }
        return out
    }

    fun newId(): String = UUID.randomUUID().toString()
}
