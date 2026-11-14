package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.WhatsNew.Decision
import io.github.outpacestream.android.core.WhatsNew.Seen
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTest {
    private val v010 = Seen(1_000, "0.1.0")

    private fun rel(version: String, body: String, pre: Boolean = false) =
        Release("v$version", version, "", pre, emptySet(), body)

    @Test fun freshInstallSkips() = assertEquals(Decision.Skip, WhatsNew.decide(null, 2_000, "0.2.0", updated = false))

    @Test fun updateFromBuildWithoutRecordShowsCurrentOnly() =
        assertEquals(Decision.Show(null), WhatsNew.decide(null, 2_000, "0.2.0", updated = true))

    @Test fun higherVersionCodeShowsSincePrevious() =
        assertEquals(Decision.Show("0.1.0"), WhatsNew.decide(v010, 2_000, "0.2.0", updated = true))

    @Test fun sameVersionCodeSkips() = assertEquals(Decision.Skip, WhatsNew.decide(v010, 1_000, "0.1.0", updated = true))

    @Test fun downgradeSkips() = assertEquals(Decision.Skip, WhatsNew.decide(Seen(2_000, "0.2.0"), 1_000, "0.1.0", updated = true))

    @Test fun devBuildNeverShows() {
        assertEquals(Decision.Skip, WhatsNew.decide(null, 1, AppRelease.DEV_VERSION, updated = true))
        assertEquals(Decision.Skip, WhatsNew.decide(v010, 1_001, AppRelease.DEV_VERSION, updated = true))
    }

    @Test fun updateFromDevBuildShowsCurrentOnly() =
        assertEquals(Decision.Show(null), WhatsNew.decide(Seen(1, AppRelease.DEV_VERSION), 2_000, "0.2.0", updated = true))

    @Test fun betweenListsSkippedVersionsNewestFirst() {
        val rs = listOf(rel("0.4.0", "* D"), rel("0.3.0", "* C"), rel("0.3.0-rc.1", "* R", pre = true), rel("0.2.0", "* B"), rel("0.1.0", "* A"))
        assertEquals("0.3.0\n• C\n\n0.2.0\n• B", ReleaseNotes.between(rs, "0.1.0", "0.3.0"))
    }

    @Test fun betweenWithoutPreviousIsCurrentOnly() {
        val rs = listOf(rel("0.3.0", "* C"), rel("0.2.0", "* B"), rel("0.1.0", "* A"))
        assertEquals("0.2.0\n• B", ReleaseNotes.between(rs, null, "0.2.0"))
    }

    @Test fun betweenEmptyWhenCurrentIsNotListed() =
        assertEquals("", ReleaseNotes.between(listOf(rel("0.1.0", "* A")), null, "0.2.0"))
}
