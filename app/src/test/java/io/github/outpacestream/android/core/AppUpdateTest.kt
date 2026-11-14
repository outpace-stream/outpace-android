package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.UpdatePolicy.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private fun inputs(
        installed: String = "0.1.0",
        latest: String? = "0.2.0",
        notify: Boolean = true,
        streamActive: Boolean = false,
        otherPromptShown: Boolean = false,
        promptedAt: Long = 0,
        now: Long = 10 * DAY_MS,
    ) = UpdatePolicy.PromptInputs(installed, latest, notify, streamActive, otherPromptShown, promptedAt, now)

    private fun rel(version: String, body: String, pre: Boolean = false) =
        Release("v$version", version, "", pre, emptySet(), body)

    @Test fun newerPrompts() = assertTrue(UpdatePolicy.shouldPrompt(inputs()))
    @Test fun latestNullNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(latest = null)))
    @Test fun equalVersionNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(latest = "0.1.0")))
    @Test fun olderNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(latest = "0.0.9")))
    @Test fun prereleaseNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(latest = "0.2.0-rc.1")))
    @Test fun devBuildNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(installed = "0.0.0")))
    @Test fun notifyOffNeverPrompts() = assertFalse(UpdatePolicy.shouldPrompt(inputs(notify = false)))
    @Test fun activeStreamHoldsBack() = assertFalse(UpdatePolicy.shouldPrompt(inputs(streamActive = true)))
    @Test fun otherPromptHoldsBack() = assertFalse(UpdatePolicy.shouldPrompt(inputs(otherPromptShown = true)))

    @Test fun promptedWithinDayDoesNotPrompt() =
        assertFalse(UpdatePolicy.shouldPrompt(inputs(promptedAt = 10 * DAY_MS - 1000)))

    @Test fun promptedOverADayAgoPromptsAgain() =
        assertTrue(UpdatePolicy.shouldPrompt(inputs(promptedAt = 9 * DAY_MS)))

    @Test fun minorTenBeatsNine() = assertTrue(UpdatePolicy.isNewer("0.9.0", "0.10.0"))

    @Test fun checkDueAfterDay() = assertTrue(UpdatePolicy.shouldCheck(now = DAY_MS, lastCheckedAt = 0, lastFailedAt = 0))

    @Test fun checkNotDueWithinDay() = assertFalse(UpdatePolicy.shouldCheck(now = DAY_MS - 1, lastCheckedAt = 0, lastFailedAt = 0))

    @Test fun failedCheckWaitsAnHour() =
        assertFalse(UpdatePolicy.shouldCheck(now = 2 * DAY_MS, lastCheckedAt = 0, lastFailedAt = 2 * DAY_MS - 1000))

    @Test fun failedCheckRetriesAfterAnHour() =
        assertTrue(UpdatePolicy.shouldCheck(now = 2 * DAY_MS, lastCheckedAt = 0, lastFailedAt = 2 * DAY_MS - UpdatePolicy.RETRY_MS))

    @Test fun cleansGeneratedNotes() {
        val body = "## What's Changed\r\n* Add updates by @someone in https://github.com/o/r/pull/20\r\n\r\n" +
            "## New Contributors\r\n* @x made their first contribution in https://github.com/o/r/pull/2\r\n\r\n" +
            "**Full Changelog**: https://github.com/o/r/compare/v0.1.0...v0.2.0"
        assertEquals("What's Changed\n• Add updates", ReleaseNotes.clean(body))
    }

    @Test fun stripsLinks() = assertEquals("• See docs", ReleaseNotes.clean("* See [docs](https://example.invalid)"))

    @Test fun collapsesBlankRuns() = assertEquals("a\n\nb", ReleaseNotes.clean("a\n\n\n\nb\n\n"))

    @Test fun sinceListsNewerStableNewestFirst() {
        val rs = listOf(rel("0.3.0", "* C"), rel("0.2.0", "* B"), rel("0.2.0-rc.1", "* R", pre = true), rel("0.1.0", "* A"))
        assertEquals("0.3.0\n• C\n\n0.2.0\n• B", ReleaseNotes.since(rs, "0.1.0"))
    }

    @Test fun sinceEmptyWhenUpToDate() = assertEquals("", ReleaseNotes.since(listOf(rel("0.1.0", "* A")), "0.1.0"))

    @Test fun appAssetNames() {
        assertEquals("outpace-android-0.2.0.apk", AppRelease.apkName("0.2.0"))
        assertEquals("outpace-android-0.2.0.apk.sha256", AppRelease.checksumName("0.2.0"))
        assertEquals("outpace-stream/outpace-android", AppRelease.REPO.slug)
    }
}
