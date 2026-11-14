# App Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app checks `outpace-stream/outpace-android` GitHub releases, prompts once a day when a newer stable version exists (Changelog / Update / Not now / Never ask again), and has an Updates section in Settings that can always check and update.

**Architecture:** Pure decision and parsing logic lives in `core/` (JVM unit tested): a generalised GitHub repo descriptor shared with engine releases, an update policy, and release-notes cleanup. `net/AppUpdates.kt` does the network and file work (latest redirect, changelog API, verified APK download into one bounded cache directory). `ui/` adds a `PackageInstaller` session installer, the update dialog wired into `MainActivity.onStart`, and an Updates group in `SettingsScreen`.

**Tech Stack:** Kotlin, plain Android Views, `org.json`, JUnit 4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-14-app-updates-design.md`

## Global Constraints

- No new dependencies; no AndroidX, no WebView, no Markdown library, no `FileProvider`.
- `minSdk` 21, `targetSdk` 36.
- App repo is `outpace-stream/outpace-android`; assets `outpace-android-X.Y.Z.apk` and `outpace-android-X.Y.Z.apk.sha256`; tags `vX.Y.Z`.
- Stable releases only. Dev builds (`BuildConfig.VERSION_NAME == "0.0.0"`) never prompt and never offer Update.
- "Is there an update" uses only the `https://github.com/<repo>/releases/latest` redirect. The API (`releases?per_page=30`) is used only when the user opens the changelog.
- Checks at most once per 24 h; failed check retried at most once per hour (in-memory); dialog shown at most once per 24 h (`app_prompted_at`).
- Never install an APK whose SHA-256 does not match the release's `.sha256` file. At most one APK on disk, under `cacheDir/app-update/`.
- Engine release behaviour must not change; existing `ReleasesTest` passes unmodified.
- No em dashes in strings or docs. No content ids, infohashes, stream names; synthetic hex `0123456789abcdef0123456789abcdef01234567` if any is needed.
- Gate before every commit of code: `./gradlew --no-daemon testDebugUnitTest lintDebug` (full `assembleRelease` + hygiene check in the last task).

## Review Focus

1. No app release exists yet, or the redirect lands somewhere unexpected: no dialog, no crash; Check now says it could not find a release. (Task 2 test `latestNullNeverPrompts`.)
2. A download is interrupted, truncated, or the app is killed mid-download: the partial file is never installed and is gone before the next download. (Task 3 test `prepareDirRemovesEverything`.)
3. After a successful update, the old APK lingers and the new version still sees a "newer" cached value: the next start deletes the APK and does not prompt for the version now installed. (Task 3 test `staleApkDeletedWhenInstalledCaughtUp`; Task 2 test `equalVersionNeverPrompts`.)
4. Rotation or leaving and returning to the main screen while the dialog is open or just dismissed: never a second dialog the same day. (Task 2 test `promptedWithinDayDoesNotPrompt`; Task 5 sets `app_prompted_at` before showing.)
5. User comes back from "Install unknown apps" without allowing it: plain explanation, no loop of settings screens. (Task 4 manual step.)

---

### Task 1: Generalise GitHub release helpers

**Files:**
- Modify: `app/src/main/java/io/github/outpacestream/android/core/Releases.kt`
- Create: `app/src/main/java/io/github/outpacestream/android/net/GithubLatest.kt`
- Modify: `app/src/main/java/io/github/outpacestream/android/net/EngineReleases.kt:20-35`
- Test: `app/src/test/java/io/github/outpacestream/android/core/GithubRepoTest.kt`

**Interfaces:**
- Produces:
  - `data class GithubRepo(val slug: String)` in `core/Releases.kt` with `val latestUrl: String` (`https://github.com/$slug/releases/latest`), `val listUrl: String` (`https://api.github.com/repos/$slug/releases?per_page=30`), `fun downloadUrl(version: String, name: String): String` (`https://github.com/$slug/releases/download/v$version/$name`), `fun releasePage(version: String): String` (`https://github.com/$slug/releases/tag/v$version`).
  - `Releases.ENGINE = GithubRepo("outpace-stream/outpace-engine")`; existing `REPO`, `LIST_URL`, `LATEST_URL`, `downloadUrl` keep their values by delegating to it.
  - `Release` gains `val body: String = ""` (last parameter, defaulted); `parseList` fills it from `optString("body", "")`.
  - `object GithubLatest { fun version(repo: GithubRepo): String }` in `net/GithubLatest.kt`: the redirect-hop loop moved out of `EngineReleases.latest`, throws `IOException("unexpected latest-release redirect")` as today.

- [ ] **Step 1: Write failing tests** in `GithubRepoTest.kt`:

```kotlin
@Test fun appRepoUrls() {
    val r = GithubRepo("outpace-stream/outpace-android")
    assertEquals("https://github.com/outpace-stream/outpace-android/releases/latest", r.latestUrl)
    assertEquals("https://api.github.com/repos/outpace-stream/outpace-android/releases?per_page=30", r.listUrl)
    assertEquals("https://github.com/outpace-stream/outpace-android/releases/download/v0.2.0/outpace-android-0.2.0.apk",
        r.downloadUrl("0.2.0", "outpace-android-0.2.0.apk"))
    assertEquals("https://github.com/outpace-stream/outpace-android/releases/tag/v0.2.0", r.releasePage("0.2.0"))
}
@Test fun engineConstantsUnchanged() {
    assertEquals("outpace-stream/outpace-engine", Releases.REPO)
    assertEquals(Releases.ENGINE.latestUrl, Releases.LATEST_URL)
    assertEquals(Releases.ENGINE.listUrl, Releases.LIST_URL)
}
@Test fun parsesBody() {
    val json = """[{"tag_name":"v0.2.0","body":"* Add updates by @someone","assets":[]}]"""
    assertEquals("* Add updates by @someone", Releases.parseList(json)[0].body)
}
@Test fun appRedirectParses() =
    assertEquals("0.2.0", Releases.versionFromLatestRedirect("https://github.com/outpace-stream/outpace-android/releases/tag/v0.2.0"))
```

- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*GithubRepoTest'`. Expected: FAIL (unresolved `GithubRepo`).
- [ ] **Step 3: Implement** the Produces list. `EngineReleases.latest` keeps its caching and calls `GithubLatest.version(Releases.ENGINE)`.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS, including unmodified `ReleasesTest`.
- [ ] **Step 5: Commit** `refactor: share GitHub release helpers between engine and app`.

### Task 2: Update policy and release notes

**Files:**
- Create: `app/src/main/java/io/github/outpacestream/android/core/AppUpdate.kt`
- Test: `app/src/test/java/io/github/outpacestream/android/core/AppUpdateTest.kt`

**Interfaces:**
- Consumes: `Releases.compareVersions`, `Release` (with `body`) from Task 1.
- Produces (all in `core/AppUpdate.kt`):
  - `object AppRelease { val REPO = GithubRepo("outpace-stream/outpace-android"); const val DEV_VERSION = "0.0.0"; fun apkName(v: String) = "outpace-android-$v.apk"; fun checksumName(v: String) = apkName(v) + ".sha256" }`
  - `object UpdatePolicy` with `const val DAY_MS`, `const val RETRY_MS = 60 * 60_000L`, and:
    - `fun isNewer(installed: String, latest: String?): Boolean`: false when `latest` is null, contains `-`, or `installed == DEV_VERSION`; otherwise `compareVersions(latest, installed) > 0`.
    - `fun shouldCheck(now: Long, lastCheckedAt: Long, lastFailedAt: Long): Boolean`: `now - lastCheckedAt >= DAY_MS && now - lastFailedAt >= RETRY_MS`.
    - `data class PromptInputs(val installed: String, val latest: String?, val notify: Boolean, val streamActive: Boolean, val otherPromptShown: Boolean, val promptedAt: Long, val now: Long)` and `fun shouldPrompt(i: PromptInputs): Boolean`: all of `notify`, `!streamActive`, `!otherPromptShown`, `now - promptedAt >= DAY_MS`, `isNewer(installed, latest)`.
  - `object ReleaseNotes`:
    - `fun clean(body: String): String`: drops the `## New Contributors` section and the `**Full Changelog**` line, turns `[text](url)` into `text`, removes ` by @user in https://...` suffixes, turns `## Heading` into `Heading`, keeps `* ` / `- ` bullets as `• `, trims blank-line runs to one.
    - `fun since(releases: List<Release>, installed: String): String`: stable releases newer than `installed`, newest first, each as `"<version>\n<clean(body)>"`, joined by a blank line; empty string if none.

- [ ] **Step 1: Write failing tests** in `AppUpdateTest.kt`. Use a helper `inputs(...)` with defaults `installed="0.1.0", latest="0.2.0", notify=true, streamActive=false, otherPromptShown=false, promptedAt=0, now=10*DAY_MS`.

```kotlin
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
@Test fun minorTenBeatsNine() = assertTrue(UpdatePolicy.isNewer("0.9.0", "0.10.0"))
@Test fun checkDueAfterDay() = assertTrue(UpdatePolicy.shouldCheck(now = DAY_MS, lastCheckedAt = 0, lastFailedAt = 0))
@Test fun checkNotDueWithinDay() = assertFalse(UpdatePolicy.shouldCheck(now = DAY_MS - 1, lastCheckedAt = 0, lastFailedAt = 0))
@Test fun failedCheckWaitsAnHour() =
    assertFalse(UpdatePolicy.shouldCheck(now = 2 * DAY_MS, lastCheckedAt = 0, lastFailedAt = 2 * DAY_MS - 1000))
@Test fun cleansGeneratedNotes() {
    val body = "## What's Changed\n* Add updates by @someone in https://github.com/o/r/pull/20\n\n" +
        "## New Contributors\n* @x made their first contribution in https://github.com/o/r/pull/2\n\n" +
        "**Full Changelog**: https://github.com/o/r/compare/v0.1.0...v0.2.0"
    assertEquals("What's Changed\n• Add updates", ReleaseNotes.clean(body))
}
@Test fun stripsLinks() = assertEquals("• See docs", ReleaseNotes.clean("* See [docs](https://example.invalid)"))
@Test fun sinceListsNewerStableNewestFirst() {
    val rs = listOf(rel("0.3.0", "* C"), rel("0.2.0", "* B"), rel("0.2.0-rc.1", "* R", pre = true), rel("0.1.0", "* A"))
    assertEquals("0.3.0\n• C\n\n0.2.0\n• B", ReleaseNotes.since(rs, "0.1.0"))
}
```

(`rel(version, body, pre=false)` builds `Release("v$version", version, "", pre, emptySet(), body)`.)

- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*AppUpdateTest'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** the Produces list in `core/AppUpdate.kt`.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: decide when to offer app updates`.

### Task 3: Prefs, checking and verified download

**Files:**
- Modify: `app/src/main/java/io/github/outpacestream/android/Prefs.kt`
- Create: `app/src/main/java/io/github/outpacestream/android/net/AppUpdates.kt`
- Create: `app/src/main/java/io/github/outpacestream/android/core/UpdateFiles.kt`
- Test: `app/src/test/java/io/github/outpacestream/android/core/UpdateFilesTest.kt`

**Interfaces:**
- Consumes: `AppRelease`, `UpdatePolicy`, `ReleaseNotes` (Task 2); `GithubLatest.version`, `GithubRepo` (Task 1); `Http.get`, `Http.download`, `Sha256.parse`, `Sha256.of`.
- Produces:
  - `Prefs`: `var appUpdateNotify: Boolean` (key `app_update_notify`, default true); `var appLatestVersion: String?` (key `app_latest_version`, setter also writes `app_latest_checked_at = now`); `val appLatestCheckedAt: Long`; `var appPromptedAt: Long` (key `app_prompted_at`).
  - `object UpdateFiles` (pure, JVM-testable, `core/UpdateFiles.kt`):
    - `fun prepareDir(dir: File)`: creates `dir`, deletes every file inside it.
    - `fun deleteStale(dir: File, installed: String)`: deletes `outpace-android-<v>.apk` files whose `v` is not newer than `installed`, and any `*.part`.
    - `fun verify(file: File, checksumText: String, name: String)`: throws `IOException("checksum mismatch")` unless `Sha256.of(file) == Sha256.parse(checksumText, name)`.
  - `class AppUpdates(context: Context)` (`net/AppUpdates.kt`, blocking, background thread only):
    - `fun latest(force: Boolean): String?`: if `force || UpdatePolicy.shouldCheck(...)`, calls `GithubLatest.version(AppRelease.REPO)`, stores `appLatestVersion`; on failure records an in-memory (companion `@Volatile`) `lastFailedAt` and rethrows only when `force`; returns the cached value otherwise.
    - `fun changelog(installed: String): String`: `Http.get(REPO.listUrl, accept = "application/vnd.github+json")` → `Releases.parseList` → `ReleaseNotes.since`.
    - `fun download(version: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File`: `prepareDir(cacheDir/app-update)`, fetch checksum text, download to `<apk>.part`, `verify`, rename to `<apk>`, return it. On any failure delete the dir contents and rethrow.
    - `fun cleanUp()`: `deleteStale(dir, BuildConfig.VERSION_NAME)`.

- [ ] **Step 1: Write failing tests** in `UpdateFilesTest.kt` using `TemporaryFolder`:
  - `prepareDirRemovesEverything`: dir holding `outpace-android-0.2.0.apk.part` and `x` → empty after `prepareDir`.
  - `staleApkDeletedWhenInstalledCaughtUp`: files `outpace-android-0.2.0.apk`, `outpace-android-0.3.0.apk`, `a.part`; `deleteStale(dir, "0.2.0")` leaves only `outpace-android-0.3.0.apk`.
  - `verifyAcceptsMatchingChecksum`: file with bytes `"hello"`, checksum text `"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824  outpace-android-0.2.0.apk"` → no throw.
  - `verifyRejectsMismatch`: same file, hash of 64 `0`s → `IOException` with message `checksum mismatch`.
  - `verifyRejectsWrongName`: correct hash, name `other.apk` → `IllegalArgumentException`.
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*UpdateFilesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `UpdateFiles`, the `Prefs` keys, then `AppUpdates` per Produces.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: check and download verified app releases`.

### Task 4: Install through PackageInstaller

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/outpacestream/android/ui/AppInstaller.kt`
- Create: `app/src/main/java/io/github/outpacestream/android/ui/InstallResultReceiver.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: the verified `File` from `AppUpdates.download` (Task 3).
- Produces:
  - Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`; `<receiver android:name=".ui.InstallResultReceiver" android:exported="false" />`.
  - `object AppInstaller`:
    - `fun canInstall(context: Context): Boolean`: `SDK_INT < 26 || packageManager.canRequestPackageInstalls()`.
    - `fun openInstallPermission(activity: Activity): Boolean`: starts `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:<pkg>`; returns false (no throw) when the screen is missing, like `BatteryPrompt.request`.
    - `fun install(context: Context, apk: File)`: opens a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName(packageName)`), copies the APK in, commits with a `PendingIntent.getBroadcast` to `InstallResultReceiver` (flags `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` on API 31+, the system fills the extras).
    - `var onResult: ((ok: Boolean, message: String?) -> Unit)?`: set by the visible UI to show failures.
  - `InstallResultReceiver.onReceive`: on `STATUS_PENDING_USER_ACTION` start `EXTRA_INTENT` with `FLAG_ACTIVITY_NEW_TASK`; on `STATUS_SUCCESS` nothing (the process is replaced); on any other status call `AppInstaller.onResult(false, EXTRA_STATUS_MESSAGE)`, mapping `STATUS_FAILURE_CONFLICT` / `STATUS_FAILURE_INCOMPATIBLE` to string `update_failed_signature` ("This build can't be updated in place. Install the new version from the release page.").
- [ ] **Step 1: Implement** the Produces list. (No JVM test: `PackageInstaller` is framework-only; covered by lint and the manual run in Task 7.)
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS with no new lint warnings (lint flags a mutable `PendingIntent` without the API guard).
- [ ] **Step 3: Commit** `feat: install app updates with PackageInstaller`.

### Task 5: Update dialog on the main screen

**Files:**
- Create: `app/src/main/java/io/github/outpacestream/android/ui/UpdateFlow.kt`
- Modify: `app/src/main/java/io/github/outpacestream/android/ui/MainActivity.kt:80-86,164-176`
- Modify: `app/src/main/java/io/github/outpacestream/android/ui/BatteryPrompt.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AppUpdates` (Task 3), `AppInstaller` (Task 4), `UpdatePolicy.shouldPrompt` (Task 2), `EngineController.streams`.
- Produces (shared with Task 6):
  - `BatteryPrompt.maybeShow(activity): Boolean` returns whether it showed. `MainActivity` keeps `private var otherPromptShown = false`, set from it.
  - `class UpdateFlow(activity: Activity)` with:
    - `fun showPrompt(installed: String, latest: String)`: `AlertDialog` with message `update_available` ("Outpace %2$s is available. You have %1$s."), buttons Update (positive), Not now (negative), Never ask again (neutral), and a Changelog button inside the dialog body (a `Button` in a custom view, so all four are reachable with a D-pad). Never ask again sets `prefs.appUpdateNotify = false` and shows a short toast `update_never_toast` ("Update prompts are off. You can still update from Settings.").
    - `fun showChangelog(installed: String, latest: String)`: background `AppUpdates.changelog`, then a scrollable dialog (focusable `ScrollView` + `TextView`) titled `update_changelog_title`; on failure message `update_changelog_failed` plus a button that opens `REPO.releasePage(latest)` via `ACTION_VIEW` inside `try/catch`, and shows the URL text when no browser exists.
    - `fun startUpdate(latest: String)`: if `EngineController.streams.isNotEmpty()`, confirm `update_stops_playback` first. If `!AppInstaller.canInstall`, explain `update_permission_needed` and call `openInstallPermission`; remember `pendingVersion` so `onResumed()` continues the update only if permission is now granted, otherwise shows `update_permission_denied` once (no reopening of settings). Otherwise show a progress dialog (horizontal `ProgressBar` + Cancel) while `AppUpdates.download` runs on `background`, then `AppInstaller.install`. Errors show `update_download_failed` with the message.
    - `fun onResumed()`: see above; `MainActivity.onResume` calls it.
  - `MainActivity.checkLatest()` also runs `AppUpdates.cleanUp()` and `AppUpdates.latest(force = false)` on `background`, then on main thread evaluates `shouldPrompt(PromptInputs(BuildConfig.VERSION_NAME, prefs.appLatestVersion, prefs.appUpdateNotify, EngineController.streams.isNotEmpty(), otherPromptShown, prefs.appPromptedAt, now))`; if true, set `prefs.appPromptedAt = now` **before** `showPrompt`. Skip if the activity `isFinishing`.
- [ ] **Step 1: Implement** the Produces list. Only `MainActivity` triggers the prompt; `PlayActivity` is untouched.
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS.
- [ ] **Step 3: Commit** `feat: prompt for app updates on the main screen`.

### Task 6: Updates section in Settings

**Files:**
- Modify: `app/src/main/res/layout/screen_settings.xml` (new `group_updates` with a `Section` header `settings_section_updates`, placed before the About section)
- Modify: `app/src/main/java/io/github/outpacestream/android/ui/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `UpdateFlow` (Task 5), `AppUpdates.latest(force = true)`, `Prefs` keys (Task 3), existing `row` / `toggle` helpers.
- Produces, in `group_updates`:
  - status row: title `settings_update_status`, summary built by `render()`: installed version, then one of: "Up to date (checked <relative time>)", "Version X available", "Not checked yet", "Development build, updates are off", or the last Check now error.
  - `Check now` row: runs `latest(force = true)` on `background`, shows "Checking…" in the summary while running, then `render()`. Errors go into the status summary, no dialog.
  - `Changelog` and `Update` rows, enabled only when `UpdatePolicy.isNewer(VERSION_NAME, prefs.appLatestVersion)`; they call `UpdateFlow.showChangelog` / `startUpdate`.
  - toggle `settings_update_notify` ("Notify me about updates") bound to `prefs.appUpdateNotify`; disabled on dev builds.
  - About text keeps the engine repo line and adds "App releases come from github.com/outpace-stream/outpace-android".
- [ ] **Step 1: Implement** the Produces list; `SettingsScreen` creates its `UpdateFlow` from `host` and `MainActivity.onResume` forwards to the same flow instance (expose `MainActivity.updateFlow`).
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest lintDebug`. Expected: PASS.
- [ ] **Step 3: Commit** `feat: add an Updates section to settings`.

### Task 7: Docs and end-to-end check

**Files:**
- Modify: `docs/Android-App.md` (new `## App updates` section after `## Install`; one line in Install pointing to it; Settings section lists the Updates group)
- Modify: `docs/android-app-design.md` (feature entry "10. App updates" linking the spec)

- [ ] **Step 1: Write the docs.** Cover: daily check, the four dialog buttons, "Never ask again" vs. the Settings switch, the "Install unknown apps" step, that playback stops during an update, that only release builds update this way, and the fallback release-page link.
- [ ] **Step 2: Run the full gate:** `./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease` and `curl -fsSL https://raw.githubusercontent.com/outpace-stream/actions/v1/identifier-hygiene/check_identifiers.py | python3 -`. Expected: both succeed.
- [ ] **Step 3: Manual run on an emulator** (phone image and a TV image, via the T3 device tools). Build a release APK at `-PappVersion=0.0.1`, signed with a throwaway local keystore (`OUTPACE_ANDROID_KEYSTORE*` env vars). It sees the published `v0.1.0` as newer. Check:
  - the dialog appears once, D-pad reaches all four buttons, Changelog scrolls and shows cleaned notes;
  - rotating during the dialog does not show a second one (Review Focus 4);
  - Update downloads, verifies, and reaches the system install step; because the keystore differs from CI's, it must end with the `update_failed_signature` message, not a crash;
  - denying "Install unknown apps" gives the explanation once and no loop (Review Focus 5);
  - "Never ask again" turns the Settings switch off; Settings Check now / Changelog / Update still work.
  A successful in-place update can only be proven with two CI-signed releases that both contain this feature: after this ships in a release, verify the next release updates over it, and note that in the PR as pending.
- [ ] **Step 4: Commit** `docs: describe app updates`.
