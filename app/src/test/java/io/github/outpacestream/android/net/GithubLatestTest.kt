package io.github.outpacestream.android.net

import io.github.outpacestream.android.core.GithubRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubLatestTest {
    private val repo = GithubRepo("o/r")

    @Test fun followsToTag() {
        val hops = mapOf(repo.latestUrl to "https://github.com/o/r/releases/tag/v0.2.0")
        assertEquals("0.2.0", GithubLatest.version(repo) { hops[it] })
    }

    @Test fun noReleaseWhenLatestLandsOnReleaseList() {
        // A repository without releases redirects /releases/latest to /releases, which answers 200.
        val redirect: (String) -> String? = { url ->
            if (url == repo.latestUrl) "https://github.com/o/r/releases" else throw HttpStatusException(200, url)
        }
        assertThrows(NoReleaseException::class.java) { GithubLatest.version(repo, redirect) }
    }

    @Test fun describesNoRelease() =
        assertEquals("no release published yet", EngineReleases.describe(NoReleaseException()))
}
