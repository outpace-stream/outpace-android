package io.github.jamezrin.outpace.core

import org.json.JSONArray
import org.json.JSONObject

/** One playable search hit. Metadata is discovery data only, not proof that it plays. */
data class SearchItem(
    val ref: StreamRef,
    val name: String,
    val categories: List<String>,
    val bitrate: Long?,
    val availability: Double?,
    val languages: List<String>,
    val countries: List<String>,
    val disabled: Boolean,
)

/** A channel name with the streams the index groups under it. */
data class SearchGroup(val name: String, val items: List<SearchItem>)

data class SearchPage(val total: Int, val groups: List<SearchGroup>) {
    val itemCount: Int get() = groups.sumOf { it.items.size }
}

/** Why a search did not return a page, from the engine's HTTP status. */
enum class SearchFailure {
    /** `404`: the running engine has search turned off (or predates it). */
    Unavailable,
    Invalid,
    RateLimited,
    Busy,
    Upstream,
    Timeout,
    Other,
    ;

    companion object {
        fun fromStatus(code: Int): SearchFailure = when (code) {
            404 -> Unavailable
            400 -> Invalid
            429 -> RateLimited
            503 -> Busy
            502 -> Upstream
            504 -> Timeout
            else -> Other
        }
    }
}

/** Parses the engine's `/search` envelope. Items without a valid infohash are dropped. */
object SearchJson {
    const val MAX_NAME = 256

    fun parse(json: String): SearchPage {
        val root = JSONObject(json)
        val result = root.optJSONObject("result") ?: throw IllegalArgumentException("no result")
        val groups = result.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<SearchGroup>(groups.length())
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val items = g.optJSONArray("items") ?: continue
            val parsed = (0 until items.length()).mapNotNull { j -> items.optJSONObject(j)?.let(::item) }
            if (parsed.isEmpty()) continue
            out += SearchGroup(text(g, "name") ?: parsed.first().name, parsed)
        }
        return SearchPage(maxOf(result.optInt("total", out.size), out.size), out)
    }

    private fun item(o: JSONObject): SearchItem? {
        val hash = o.optString("infohash").lowercase()
        if (!ContentId.isValid(hash)) return null
        return SearchItem(
            ref = StreamRef.infohash(hash),
            name = text(o, "name") ?: "",
            categories = strings(o, "categories"),
            bitrate = o.takeIf { it.has("bitrate") && !it.isNull("bitrate") }?.optLong("bitrate")?.takeIf { it > 0 },
            availability = o.takeIf { it.has("availability") && !it.isNull("availability") }
                ?.optDouble("availability")?.takeIf { !it.isNaN() && it in 0.0..1.0 },
            languages = strings(o, "languages"),
            countries = strings(o, "countries"),
            disabled = o.optBoolean("disabled", false),
        )
    }

    private fun text(o: JSONObject, key: String): String? =
        o.takeIf { it.has(key) && !it.isNull(key) }?.optString(key)?.trim()?.take(MAX_NAME)?.ifEmpty { null }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }.take(8)
    }
}
