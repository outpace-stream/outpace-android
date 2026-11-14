package io.github.outpacestream.android.core

import java.io.File
import java.io.IOException

/** The single-APK download directory for app updates; keeps at most one update on disk. */
object UpdateFiles {
    private val apk = Regex("""^outpace-android-(.+)\.apk$""")

    /** Creates [dir] and empties it, so a new download never sits beside an old or partial one. */
    fun prepareDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Deletes partial downloads and APKs that are not newer than the [installed] version. */
    fun deleteStale(dir: File, installed: String) {
        dir.listFiles()?.forEach { f ->
            val version = apk.matchEntire(f.name)?.groupValues?.get(1)
            if (version == null || !UpdatePolicy.isNewer(installed, version)) f.deleteRecursively()
        }
    }

    /** Throws unless [file] hashes to the `sha256sum` line in [checksumText] for [name]. */
    fun verify(file: File, checksumText: String, name: String) {
        val expected = Sha256.parse(checksumText, name)
        if (Sha256.of(file) != expected) throw IOException("checksum mismatch")
    }
}
