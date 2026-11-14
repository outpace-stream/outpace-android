package io.github.outpacestream.android.core

import org.json.JSONObject

/** Reads the version from a `get_version` reply: `{"result":{"version":"..."},"error":null}`. */
object VersionJson {
    fun parse(json: String): String? = try {
        val o = JSONObject(json)
        if (!o.isNull("error")) null else o.optJSONObject("result")?.optString("version")?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
