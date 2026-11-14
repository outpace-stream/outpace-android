package io.github.outpacestream.android.core

import java.net.URLDecoder

/**
 * Reads the AceStream-compatible `http://127.0.0.1:<port>/ace/getstream?id=<content id>` links that
 * other apps fire at a local engine. Only `id=` and `content_id=` are read; an `infohash=` link is
 * reported as [ContentId.Result.Infohash] so the exported entry point keeps accepting content ids
 * only. The port is ignored: the app plays through its own engine whatever port the link names.
 */
object GetStreamLink {
    private const val PATH = "/ace/getstream"
    private val loopbackHosts = setOf("127.0.0.1", "localhost")
    private val idParams = listOf("id", "content_id")

    /** The content id a getstream link names, or null when [link] is not a getstream link at all. */
    fun parse(link: String): ContentId.Result? {
        val params = query(rawQuery(link) ?: return null)
        for (name in idParams) {
            val value = params[name]?.firstOrNull { ContentId.isValid(it) } ?: continue
            return ContentId.Result.Id(value)
        }
        return if (params.containsKey("infohash")) ContentId.Result.Infohash else ContentId.Result.None
    }

    /**
     * The raw query ("" when absent) of a loopback getstream link, else null. Split by hand rather
     * than with [java.net.URI], which rejects a whole link over one loosely escaped parameter.
     */
    private fun rawQuery(link: String): String? {
        val text = link.trim()
        val sep = text.indexOf("://")
        if (sep < 0 || !text.substring(0, sep).equals("http", ignoreCase = true)) return null
        val rest = text.substring(sep + 3).substringBefore('#')
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, authorityEnd).substringAfterLast('@').substringBefore(':')
        if (host.lowercase() !in loopbackHosts) return null
        val pathAndQuery = rest.substring(authorityEnd)
        if (pathAndQuery.substringBefore('?').trimEnd('/') != PATH) return null
        return pathAndQuery.substringAfter('?', "")
    }

    /**
     * Every value of each parameter in link order, names and values lowercased (hex ids are
     * case-insensitive); malformed escapes drop that one value.
     */
    private fun query(raw: String): Map<String, List<String>> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<String>>()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = decode(pair.substring(0, eq))?.lowercase() ?: continue
            val value = decode(pair.substring(eq + 1))?.trim()?.lowercase() ?: continue
            out.getOrPut(name) { ArrayList(1) }.add(value)
        }
        return out
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}
