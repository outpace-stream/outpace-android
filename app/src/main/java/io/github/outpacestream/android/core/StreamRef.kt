package io.github.outpacestream.android.core

/**
 * A stream the engine can play, with an explicit selector kind. A content id plays as `cid:`,
 * an infohash (from search results) as `ih:`; the two are never guessed from bare hex.
 */
data class StreamRef(val kind: Kind, val hex: String) {
    enum class Kind(val prefix: String) {
        ContentId("cid"),
        Infohash("ih"),
    }

    init {
        require(ContentId.isValid(hex)) { "not a 40-character lowercase hex id" }
    }

    /** The engine's selector, as `/streams` lists it and the stream URL path uses it. */
    val selector: String get() = "${kind.prefix}:$hex"

    /**
     * An `acestream://` link that opens this stream in a player app or in Outpace on another
     * device. Null for an infohash: links carry content ids only, and the app rejects
     * infohash links on the way in.
     */
    val link: String? get() = if (kind == Kind.ContentId) "acestream://$hex" else null

    /** A short form for screens: the first and last few characters. */
    val short: String get() = "${hex.take(6)}…${hex.takeLast(4)}"

    companion object {
        fun contentId(hex: String) = StreamRef(Kind.ContentId, hex.lowercase())

        fun infohash(hex: String) = StreamRef(Kind.Infohash, hex.lowercase())

        /** Reads a prefixed selector. Only `cid:` and `ih:` are accepted; bare hex is not. */
        fun parse(selector: String): StreamRef? {
            val kind = Kind.entries.firstOrNull { selector.startsWith(it.prefix + ":") } ?: return null
            val hex = selector.substring(kind.prefix.length + 1)
            return if (ContentId.isValid(hex)) StreamRef(kind, hex) else null
        }
    }
}
