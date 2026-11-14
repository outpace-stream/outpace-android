package io.github.outpacestream.android.net

import io.github.outpacestream.android.core.GithubRepo
import io.github.outpacestream.android.core.Releases
import java.io.IOException
import java.net.URL

/** The latest stable release of a repository, read from the github.com redirect (no API quota). */
object GithubLatest {
    private const val MAX_REDIRECTS = 4

    /** Blocking; call from a background thread. Throws when no `vX.Y.Z` release is found. */
    fun version(repo: GithubRepo): String {
        // A renamed or transferred repository answers with an extra redirect first; follow a few hops.
        var url = repo.latestUrl
        for (hop in 0 until MAX_REDIRECTS) {
            val location = Http.redirectLocation(url) ?: break
            Releases.versionFromLatestRedirect(location)?.let { return it }
            url = URL(URL(url), location).toString()
        }
        throw IOException("unexpected latest-release redirect")
    }
}
