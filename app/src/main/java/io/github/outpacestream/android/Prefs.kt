package io.github.outpacestream.android

import android.content.Context
import android.content.SharedPreferences
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.RecentEntry
import io.github.outpacestream.android.core.RecentList
import org.json.JSONArray

/** Everything the app remembers. Small values only; engine files live in [EngineFiles]. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("outpace", Context.MODE_PRIVATE)
    private val tv = Device.isTv(context)

    var settings: EngineSettings
        get() = EngineSettings(
            lan = sp.getBoolean("lan", false),
            port = sp.getInt("port", EngineSettings.DEFAULT_PORT),
            seeding = sp.getBoolean("seeding", true),
            inbound = sp.getBoolean("inbound", true),
            portMapping = sp.getBoolean("port_mapping", false),
            aceCompat = sp.getBoolean("ace_compat", false),
            search = sp.getBoolean("search", false),
        )
        set(s) = sp.edit()
            .putBoolean("lan", s.lan).putInt("port", s.port).putBoolean("seeding", s.seeding)
            .putBoolean("inbound", s.inbound).putBoolean("port_mapping", s.portMapping)
            .putBoolean("ace_compat", s.aceCompat).putBoolean("search", s.search).apply()

    var activeVersion: String?
        get() = sp.getString("active_version", null)
        set(v) = sp.edit().putString("active_version", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", tv)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    var idleStop: Boolean
        get() = sp.getBoolean("idle_stop", !tv)
        set(v) = sp.edit().putBoolean("idle_stop", v).apply()

    var showPrereleases: Boolean
        get() = sp.getBoolean("show_prereleases", false)
        set(v) = sp.edit().putBoolean("show_prereleases", v).apply()

    var batteryPromptShown: Boolean
        get() = sp.getBoolean("battery_prompt_shown", false)
        set(v) = sp.edit().putBoolean("battery_prompt_shown", v).apply()

    /** Latest release version from the redirect check, and when it was fetched. */
    var latestVersion: String?
        get() = sp.getString("latest_version", null)
        set(v) = sp.edit().putString("latest_version", v).putLong("latest_checked_at", System.currentTimeMillis()).apply()

    val latestCheckedAt: Long get() = sp.getLong("latest_checked_at", 0)

    var releaseListJson: String?
        get() = sp.getString("release_list", null)
        set(v) = sp.edit().putString("release_list", v).putLong("release_list_at", System.currentTimeMillis()).apply()

    val releaseListAt: Long get() = sp.getLong("release_list_at", 0)

    /** Streams played from this device, newest first. Older versions stored bare content ids. */
    val recent: List<RecentEntry>
        get() = if (sp.contains("recent_streams")) RecentList.decode(sp.getString("recent_streams", null))
        else RecentList.decodeLegacy(sp.getString("recent", null))

    fun addRecent(entry: RecentEntry) = saveRecent(RecentList.add(recent, entry))

    fun removeRecent(entry: RecentEntry) = saveRecent(recent.filter { it.ref != entry.ref })

    fun clearRecent() = saveRecent(emptyList())

    private fun saveRecent(list: List<RecentEntry>) =
        sp.edit().putString("recent_streams", RecentList.encode(list)).remove("recent").apply()

    /** Recent search queries, newest first, so a TV remote rarely has to type. */
    var recentQueries: List<String>
        get() = try {
            val a = JSONArray(sp.getString("recent_queries", "[]"))
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(RecentList.MAX_QUERIES)
        } catch (_: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("recent_queries", JSONArray(v.take(RecentList.MAX_QUERIES)).toString()).apply()

    companion object {
        const val IDLE_STOP_MS = 10 * 60_000L
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
