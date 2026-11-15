package io.github.outpacestream.android.core

/** When the local engine sends its peer, tracker and catalog traffic through [SocksProxy]. */
enum class ProxyMode(val key: String, val wire: String?) {
    OFF("off", null),

    /** Everything through the proxy from the start (`on`); the engine then refuses inbound peers and port mapping. */
    ALWAYS("always", "on"),

    /** Direct first, through the proxy once a live start finds no peers (`fallback`). */
    FALLBACK("fallback", "fallback"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * A SOCKS5 proxy for `OUTPACE_PROXY`. An IPv6 [host] is stored without brackets. [username] and
 * [password] are both empty or both set, as the engine requires. The password is a secret:
 * [toString] leaves it out, so the object can never leak it through a log line.
 */
data class SocksProxy(val host: String, val port: Int, val username: String = "", val password: String = "") {
    val hasCredentials: Boolean get() = username.isNotEmpty()

    /** `host:port` or `[ipv6]:port`, as the user types it; [parseAddress] reads it back. */
    val address: String get() = (if (':' in host) "[$host]" else host) + ":$port"

    /** The value of `OUTPACE_PROXY`, with the credentials percent-encoded. Never log it. */
    fun url(): String {
        val auth = if (hasCredentials) "${encode(username)}:${encode(password)}@" else ""
        return "socks5://$auth$address"
    }

    /** Whether the proxy runs on this device, so RFC 1929 credentials never cross a network. */
    val isLoopback: Boolean get() {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h.matches(LOOPBACK_V4) ||
            (RemoteEngines.isIpv6(h) && h.startsWith("::ffff:") && h.removePrefix("::ffff:").matches(LOOPBACK_V4))
    }

    override fun toString(): String = "SocksProxy($address, auth=$hasCredentials)"

    /** Why a proxy cannot be saved; null when it can. */
    enum class Problem { ADDRESS, CREDENTIALS_INCOMPLETE, CREDENTIALS_TOO_LONG }

    companion object {
        /** The engine caps each credential at 255 bytes (RFC 1929). */
        const val MAX_CREDENTIAL_BYTES = 255

        private val LOOPBACK_V4 = Regex("127(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")
        private val hostName = Regex("[A-Za-z0-9_]([A-Za-z0-9_.-]*[A-Za-z0-9_])?")
        private val ipv4 = Regex("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")
        private const val MAX_HOST = 253
        private const val HEX = "0123456789ABCDEF"

        /**
         * Reads `host:port` or `[ipv6]:port`, with an optional `socks5://` or `socks5h://` and one
         * trailing slash. The port is required. Credentials have their own fields, so an `@` is
         * refused here. Returns null for anything the engine would not accept.
         */
        fun parseAddress(text: String): Pair<String, Int>? {
            var s = text.trim()
            for (scheme in listOf("socks5://", "socks5h://")) {
                if (s.startsWith(scheme, ignoreCase = true)) s = s.substring(scheme.length)
            }
            s = s.removeSuffix("/")
            if (s.isEmpty() || s.any { it in "@/?#" || it.isWhitespace() }) return null
            val host: String
            val portText: String
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close < 0 || !s.startsWith(":", close + 1)) return null
                host = s.substring(1, close)
                portText = s.substring(close + 2)
                if (!RemoteEngines.isIpv6(host)) return null
            } else {
                val parts = s.split(':')
                if (parts.size != 2) return null
                host = parts[0]
                portText = parts[1]
                val dotted = host.all { it.isDigit() || it == '.' }
                if (host.length > MAX_HOST || !(if (dotted) ipv4.matches(host) else hostName.matches(host))) return null
            }
            if (portText.isEmpty() || !portText.all { it in '0'..'9' } || portText.length > 5) return null
            val port = portText.toInt()
            if (port !in 1..65535) return null
            return host to port
        }

        /** Checks what the user entered; a valid result is what [SocksProxy] gets. */
        fun validate(address: String, username: String, password: String): Problem? {
            if (parseAddress(address) == null) return Problem.ADDRESS
            if (username.isEmpty() != password.isEmpty()) return Problem.CREDENTIALS_INCOMPLETE
            if (utf8(username).size > MAX_CREDENTIAL_BYTES || utf8(password).size > MAX_CREDENTIAL_BYTES) {
                return Problem.CREDENTIALS_TOO_LONG
            }
            return null
        }

        /** The proxy for valid input, or null; see [validate]. */
        fun of(address: String, username: String, password: String): SocksProxy? {
            if (validate(address, username, password) != null) return null
            val (host, port) = parseAddress(address) ?: return null
            return SocksProxy(host, port, username, password)
        }

        /**
         * Percent-encodes everything outside RFC 3986's unreserved set, as UTF-8. That covers the
         * `@`, `:` and `%` the engine requires and the `/`, `?` and `#` it rejects in the URL.
         */
        internal fun encode(text: String): String = buildString {
            for (b in utf8(text)) {
                val c = b.toInt() and 0xff
                if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                    append(c.toChar())
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
        }

        private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)
    }
}
