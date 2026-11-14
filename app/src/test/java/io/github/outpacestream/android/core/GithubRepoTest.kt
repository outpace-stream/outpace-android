package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubRepoTest {
    @Test fun appRepoUrls() {
        val r = GithubRepo("outpace-stream/outpace-android")
        assertEquals("https://github.com/outpace-stream/outpace-android/releases/latest", r.latestUrl)
        assertEquals("https://api.github.com/repos/outpace-stream/outpace-android/releases?per_page=30", r.listUrl)
        assertEquals(
            "https://github.com/outpace-stream/outpace-android/releases/download/v0.2.0/outpace-android-0.2.0.apk",
            r.downloadUrl("0.2.0", "outpace-android-0.2.0.apk"),
        )
        assertEquals("https://github.com/outpace-stream/outpace-android/releases/tag/v0.2.0", r.releasePage("0.2.0"))
    }

    @Test fun engineConstantsUnchanged() {
        assertEquals("outpace-stream/outpace-engine", Releases.REPO)
        assertEquals(Releases.ENGINE.latestUrl, Releases.LATEST_URL)
        assertEquals(Releases.ENGINE.listUrl, Releases.LIST_URL)
        assertEquals("https://github.com/outpace-stream/outpace-engine/releases/latest", Releases.LATEST_URL)
    }

    @Test fun parsesBody() {
        val json = """[{"tag_name":"v0.2.0","body":"* Add updates by @someone","assets":[]}]"""
        assertEquals("* Add updates by @someone", Releases.parseList(json)[0].body)
    }

    @Test fun missingBodyIsEmpty() {
        val json = """[{"tag_name":"v0.2.0","body":null,"assets":[]}]"""
        assertEquals("", Releases.parseList(json)[0].body)
    }

    @Test fun appRedirectParses() =
        assertEquals("0.2.0", Releases.versionFromLatestRedirect("https://github.com/outpace-stream/outpace-android/releases/tag/v0.2.0"))
}
