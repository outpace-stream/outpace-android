package io.github.outpacestream.android.net

import android.content.Context
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.AppRelease
import io.github.outpacestream.android.core.ReleaseNotes
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.UpdateFiles
import io.github.outpacestream.android.core.UpdatePolicy
import java.io.File
import java.io.IOException

/** Checks for, describes and downloads app releases. Blocking: call from a background thread. */
class AppUpdates(context: Context) {
    private val prefs = Prefs(context)
    private val dir = File(context.cacheDir, "app-update")

    /**
     * The latest app version. Refreshes once a day (always when [force]); otherwise returns the
     * cached value. A failed automatic refresh is silent and retried at most hourly; a forced one throws.
     */
    fun latest(force: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!force && !UpdatePolicy.shouldCheck(now, prefs.appLatestCheckedAt, lastFailedAt)) return prefs.appLatestVersion
        return try {
            GithubLatest.version(AppRelease.REPO).also { prefs.appLatestVersion = it }
        } catch (e: IOException) {
            lastFailedAt = now
            if (force) throw e
            prefs.appLatestVersion
        }
    }

    /** Cleaned notes of every stable release newer than [installed], newest first. Uses the API. */
    fun changelog(installed: String): String {
        val json = Http.get(AppRelease.REPO.listUrl, accept = "application/vnd.github+json")
        return ReleaseNotes.since(Releases.parseList(json), installed)
    }

    /** Downloads and verifies the APK of [version]; returns the verified file. */
    fun download(version: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val name = AppRelease.apkName(version)
        UpdateFiles.prepareDir(dir)
        try {
            val checksum = Http.get(AppRelease.REPO.downloadUrl(version, AppRelease.checksumName(version)))
            val part = File(dir, "$name.part")
            Http.download(AppRelease.REPO.downloadUrl(version, name), part, cancelled, progress)
            UpdateFiles.verify(part, checksum, name)
            val apk = File(dir, name)
            if (!part.renameTo(apk)) throw IOException("cannot store the download")
            return apk
        } catch (e: Exception) {
            runCatching { UpdateFiles.prepareDir(dir) }
            throw e
        }
    }

    /** Removes partial downloads and APKs this build already caught up with. */
    fun cleanUp() = UpdateFiles.deleteStale(dir, BuildConfig.VERSION_NAME)

    /** Drops any downloaded APK, used after a failed or cancelled install. */
    fun discard() = UpdateFiles.prepareDir(dir)

    companion object {
        @Volatile private var lastFailedAt = 0L
    }
}
