package io.github.outpacestream.android.core

import org.json.JSONArray
import org.json.JSONObject

/** A stream the user played, with the name it was shown under when one was known. */
data class RecentEntry(val ref: StreamRef, val name: String? = null)

/** Encodes the recent-streams list for preferences. Bounded in length and name size. */
object RecentList {
    const val MAX = 12

    fun encode(entries: List<RecentEntry>): String {
        val a = JSONArray()
        for (e in entries.take(MAX)) {
            val o = JSONObject().put("id", e.ref.selector)
            e.name?.let { o.put("name", it.take(SearchJson.MAX_NAME)) }
            a.put(o)
        }
        return a.toString()
    }

    fun decode(json: String?): List<RecentEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ref = StreamRef.parse(o.optString("id")) ?: return@mapNotNull null
                RecentEntry(ref, o.optString("name").takeIf { o.has("name") && it.isNotBlank() })
            }.distinctBy { it.ref }.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The comma-separated content ids older app versions stored. */
    fun decodeLegacy(csv: String?): List<RecentEntry> =
        csv.orEmpty().split(',').filter(ContentId::isValid).map { RecentEntry(StreamRef.contentId(it)) }

    /** Moves [entry] to the front, keeping a known name when the new entry has none. */
    fun add(list: List<RecentEntry>, entry: RecentEntry): List<RecentEntry> {
        val previous = list.firstOrNull { it.ref == entry.ref }
        val merged = if (entry.name == null && previous?.name != null) entry.copy(name = previous.name) else entry
        return (listOf(merged) + list.filter { it.ref != entry.ref }).take(MAX)
    }

    /** Recent search queries, newest first, case-insensitively unique. */
    fun addQuery(list: List<String>, query: String, max: Int = MAX_QUERIES): List<String> {
        val q = query.trim().take(SearchJson.MAX_NAME)
        if (q.isEmpty()) return list
        return (listOf(q) + list.filter { !it.equals(q, ignoreCase = true) }).take(max)
    }

    const val MAX_QUERIES = 8
}
