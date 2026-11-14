package io.github.outpacestream.android.net

import io.github.outpacestream.android.core.GithubRepo
import io.github.outpacestream.android.core.Releases
import java.io.IOException
import java.net.URL

/** The repository has no published release, so "latest" leads to the release list. */
class NoReleaseException : IOException("no release published yet")

/** The latest stable release of a repository, read from the github.com redirect (no API quota). */
object GithubLatest {
    private const val MAX_REDIRECTS = 4

    /** Blocking; call from a background thread. Throws when no `vX.Y.Z` release is found. */
    fun version(repo: GithubRepo, redirect: (String) -> String? = Http::redirectLocation): String {
        // A renamed or transferred repository answers with an extra redirect first; follow a few hops.
        var url = repo.latestUrl
        for (hop in 0 until MAX_REDIRECTS) {
            val location = try {
                redirect(url)
            } catch (e: HttpStatusException) {
                // A page instead of a redirect: GitHub sent us to the release list, so nothing is published.
                if (hop > 0 && e.code in 200..299) throw NoReleaseException()
                throw e
            } ?: break
            Releases.versionFromLatestRedirect(location)?.let { return it }
            url = URL(URL(url), location).toString()
        }
        throw IOException("unexpected latest-release redirect")
    }
}
