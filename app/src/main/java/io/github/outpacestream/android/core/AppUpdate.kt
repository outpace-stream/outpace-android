package io.github.outpacestream.android.core

/** Where the app's own releases are published, and how their assets are named (see release.yml). */
object AppRelease {
    val REPO = GithubRepo("outpace-stream/outpace-android")

    /** The version local and CI test builds carry; they are never updated in place. */
    const val DEV_VERSION = "0.0.0"

    fun apkName(version: String) = "outpace-android-$version.apk"

    fun checksumName(version: String) = apkName(version) + ".sha256"
}

/** When to check for, and when to offer, an app update. Pure, so the rules are unit tested. */
object UpdatePolicy {
    const val DAY_MS = 24 * 60 * 60_000L
    const val RETRY_MS = 60 * 60_000L

    /** True when [latest] is a stable version above [installed], and [installed] is a release build. */
    fun isNewer(installed: String, latest: String?): Boolean {
        if (latest == null || '-' in latest || installed == AppRelease.DEV_VERSION) return false
        return Releases.compareVersions(latest, installed) > 0
    }

    /** A daily refresh, with failed attempts retried no more than hourly. */
    fun shouldCheck(now: Long, lastCheckedAt: Long, lastFailedAt: Long): Boolean =
        now - lastCheckedAt >= DAY_MS && now - lastFailedAt >= RETRY_MS

    data class PromptInputs(
        val installed: String,
        val latest: String?,
        val notify: Boolean,
        val streamActive: Boolean,
        val otherPromptShown: Boolean,
        val promptedAt: Long,
        val now: Long,
    )

    /** Whether the main screen shows the update dialog now; at most once a day. */
    fun shouldPrompt(i: PromptInputs): Boolean =
        i.notify && !i.streamActive && !i.otherPromptShown && i.now - i.promptedAt >= DAY_MS && isNewer(i.installed, i.latest)
}

/** Turns GitHub's generated release notes into plain text a TV can show. */
object ReleaseNotes {
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val attribution = Regex("""\s+by @\S+ in https?://\S+$""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val bullet = Regex("""^[*-]\s+""")

    fun clean(body: String): String {
        val out = ArrayList<String>()
        var skipping = false
        for (raw in body.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (heading.containsMatchIn(line)) {
                skipping = line.contains("New Contributors", ignoreCase = true)
                if (skipping) continue
            }
            if (skipping || line.startsWith("**Full Changelog**")) continue
            var text = link.replace(line) { it.groupValues[1] }
            text = attribution.replace(text, "")
            text = heading.replace(text, "")
            text = bullet.replace(text, "• ")
            if (text.isEmpty() && out.lastOrNull()?.isEmpty() != false) continue
            out += text
        }
        while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    /** Notes of every stable release newer than [installed], newest first, under version headings. */
    fun since(releases: List<Release>, installed: String): String = releases
        .filter { !it.prerelease && UpdatePolicy.isNewer(installed, it.version) }
        .sortedWith { a, b -> Releases.compareVersions(b.version, a.version) }
        .joinToString("\n\n") { r -> clean(r.body).let { if (it.isEmpty()) r.version else "${r.version}\n$it" } }
}
