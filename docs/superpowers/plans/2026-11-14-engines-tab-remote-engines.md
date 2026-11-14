# Engines Tab and Remote Engines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move engine management off Home into a new Engines tab that lists downloaded local versions and user-configured remote engines, with one selected engine that the whole app (Home, Search, Play, notification) uses, local or remote.

**Architecture:** A pure `core` layer models remote engines, addresses (`Endpoint`) and the selection (`EngineChoice`), all unit-tested. `engine/EngineSelection` is the single Android-aware place that reads and changes the selection and enforces the switching rules. `EngineService` gains a remote mode: no process, the same bounded poll loop against `http://host:port`, an `Unreachable` state that keeps retrying. `EngineController` publishes the current `Endpoint` and label so screens never branch on local vs remote for URLs.

**Tech Stack:** Kotlin, plain Android Views, `org.json`, JUnit 4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-14-engines-tab-remote-engines-design.md`

## Global Constraints

- Repository: `/home/jamezrin/dev/outpace-android-engines`, branch `feat/engines-tab-remote-engines`, base `main`. Sources under `app/src/main/java/io/github/outpacestream/android/` (abbreviated `S/` below), tests under `app/src/test/java/io/github/outpacestream/android/` (`T/`).
- Gradle needs `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`. Unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --no-daemon testDebugUnitTest`.
- Full gate before the PR: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease` (lint has `abortOnError = true`) and `curl -fsSL https://raw.githubusercontent.com/outpace-stream/actions/v1/identifier-hygiene/check_identifiers.py | python3 -`.
- No new dependencies; minSdk/targetSdk unchanged; plain Views; D-pad focusable controls everywhere.
- Polling, probes and stored lists stay bounded (AGENTS.md). Remote list cap `MAX_REMOTES = 16`.
- Tests use `0123456789abcdef0123456789abcdef01234567` where a 40-hex id is needed, and only RFC 5737/private example hosts (`192.168.1.5`, `fd00::5`, `tv.local`). No real ids, hosts or stream names.
- No em dashes in strings, docs or commit messages.
- Default engine HTTP port `EngineSettings.DEFAULT_PORT = 6878`.
- Every commit: `git -C /home/jamezrin/dev/outpace-android-engines ...`; conventional subjects (`feat:`, `fix:`, `docs:`).

## Review Focus

1. A remote typed as `http://192.168.1.5:6878/` or with spaces must be accepted and normalised to host `192.168.1.5`, port `6878`; a bare host gets port 6878. Pinned in Task 1 (`parsesSchemeAndTrailingSlash`, `bareHostGetsDefaultPort`).
2. IPv6 remotes (`[fd00::5]:7000`) must produce bracketed URLs, or every request and player hand-off breaks. Pinned in Task 1 (`ipv6UrlIsBracketed`) and Task 2 (`liveUrlForIpv6Remote`).
3. Switching engines while a poll is in flight must not let a result from the old endpoint overwrite the new engine's state or streams. Pinned in Task 5 (generation check, `staleResultIgnored` step).
4. A host that answers HTTP but is not an Outpace engine (no `ok` from `/healthz`) is unreachable, not connected. Pinned in Task 5 (`RemoteHealth.isOk` test).
5. Deleting the remote you are connected to must disconnect and leave nothing selected, and deleting the selected local version must leave nothing selected rather than silently falling back. Pinned in Task 3 (`afterRemoveClearsSelected`, `noFallbackToNewest`).

---

### Task 1: Remote engine model and address parsing

**Files:**
- Create: `S/core/RemoteEngine.kt`
- Test: `T/core/RemoteEngineTest.kt`

**Interfaces:**
- Produces:
  - `data class Endpoint(val host: String, val port: Int)` with `val base: String` (`http://host:port`, IPv6 hosts bracketed: a host containing `:` is written `[host]`), `val display: String` (`host:port`, IPv6 bracketed). `companion val LOOPBACK_HOST = "127.0.0.1"`, `fun loopback(port: Int) = Endpoint(LOOPBACK_HOST, port)`.
  - `data class RemoteEngine(val id: String, val name: String, val endpoint: Endpoint)`.
  - `object RemoteEngines { const val MAX_REMOTES = 16; const val MAX_NAME = 40; fun parseAddress(text: String): Endpoint?; fun validName(name: String): Boolean; fun encode(list: List<RemoteEngine>): String; fun decode(json: String?): List<RemoteEngine>; fun newId(): String }`.

- [ ] **Step 1: Write the failing tests** in `RemoteEngineTest`:

```kotlin
@Test fun parsesIpv4WithPort() = assertEquals(Endpoint("192.168.1.5", 7000), RemoteEngines.parseAddress("192.168.1.5:7000"))
@Test fun bareHostGetsDefaultPort() = assertEquals(Endpoint("tv.local", 6878), RemoteEngines.parseAddress("tv.local"))
@Test fun parsesSchemeAndTrailingSlash() = assertEquals(Endpoint("192.168.1.5", 6878), RemoteEngines.parseAddress("  http://192.168.1.5:6878/ "))
@Test fun parsesBracketedIpv6() = assertEquals(Endpoint("fd00::5", 7000), RemoteEngines.parseAddress("[fd00::5]:7000"))
@Test fun rejectsBadPorts() { for (t in listOf("192.168.1.5:0", "192.168.1.5:65536", "192.168.1.5:abc", "192.168.1.5:")) assertNull(t, RemoteEngines.parseAddress(t)) }
@Test fun rejectsJunk() { for (t in listOf("", "   ", "a b", "https://", "host/path", "fd00::5")) assertNull(t, RemoteEngines.parseAddress(t)) }
@Test fun ipv6UrlIsBracketed() = assertEquals("http://[fd00::5]:7000", Endpoint("fd00::5", 7000).base)
@Test fun displayIpv4() = assertEquals("192.168.1.5:6878", Endpoint("192.168.1.5", 6878).display)
@Test fun nameRules() { assertTrue(RemoteEngines.validName("Living room")); assertFalse(RemoteEngines.validName("  ")); assertFalse(RemoteEngines.validName("x".repeat(41))) }
@Test fun roundTrip() { val l = listOf(RemoteEngine("a1", "TV", Endpoint("fd00::5", 7000))); assertEquals(l, RemoteEngines.decode(RemoteEngines.encode(l))) }
@Test fun decodeSkipsInvalidAndCaps() { /* JSON with one entry missing host, one with port 0, and 20 valid ones */ assertEquals(16, decoded.size) }
@Test fun decodeToleratesGarbage() { assertEquals(emptyList<RemoteEngine>(), RemoteEngines.decode("not json")); assertEquals(emptyList<RemoteEngine>(), RemoteEngines.decode(null)) }
```

`"https://"` and bare unbracketed IPv6 are rejected (an unbracketed `fd00::5` is ambiguous with a port). Only `http://` is stripped; `https://` with a host is also accepted and stripped (the engine has no TLS, but a typed scheme should not be an error). Paths after the host (`host/path`) are rejected, a single trailing `/` is not.

- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*RemoteEngineTest'`. Expected: compile failure (types missing).

- [ ] **Step 3: Implement `RemoteEngine.kt`.** Hosts: trim, strip one `http://`/`https://` prefix and one trailing `/`; `[v6]:port` or `[v6]`; otherwise split on the single `:`; host chars limited to letters, digits, `.`, `-`, and (inside brackets) hex and `:`. Port via `toIntOrNull()` in `1..65535`. JSON: array of `{"id","name","host","port"}`; `decode` drops entries that fail `validName` or `parseAddress("$host:$port")` equivalence, then `take(MAX_REMOTES)`. `newId()` = `java.util.UUID.randomUUID().toString()`.

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Commit** `feat: model remote engines and their addresses`.

---

### Task 2: Endpoint-based stream URLs

**Files:**
- Modify: `S/core/StreamUrls.kt`, `S/core/EngineSettings.kt:22` (use `Endpoint.LOOPBACK_HOST`)
- Test: `T/core/StreamUrlsTest.kt` (rewrite to the new signatures)

**Interfaces:**
- Consumes: `Endpoint` (Task 1).
- Produces (replacing every current `StreamUrls` function; `LOOPBACK` is removed):
  `fun live(e: Endpoint, ref: StreamRef): String`, `fun hls(e: Endpoint, ref: StreamRef)`, `fun health(e: Endpoint)`, `fun streams(e: Endpoint)`, `fun status(e: Endpoint, network: String, id: String)`, `fun search(e: Endpoint, query: String, page: Int, pageSize: Int)`, `fun version(e: Endpoint)` = `${e.base}/server/api?method=get_version`.
  Also `object VersionJson { fun parse(json: String): String? }` in `S/core/VersionJson.kt`, returning `result.version` when `error` is null and the version is non-blank.

- [ ] **Step 1: Rewrite `StreamUrlsTest`** keeping every existing assertion's expected string, now called with `Endpoint.loopback(6878)` / `Endpoint("192.168.1.5", 7000)`, plus:

```kotlin
@Test fun liveUrlForIpv6Remote() = assertEquals("http://[fd00::5]:7000/streams/ace/cid:$id", StreamUrls.live(Endpoint("fd00::5", 7000), StreamRef.contentId(id)))
@Test fun versionUrl() = assertEquals("http://192.168.1.5:7000/server/api?method=get_version", StreamUrls.version(Endpoint("192.168.1.5", 7000)))
```

and `T/core/VersionJsonTest.kt`:

```kotlin
@Test fun readsVersion() = assertEquals("1.0.2", VersionJson.parse("""{"result":{"version":"1.0.2","code":10002,"platform":"linux"},"error":null}"""))
@Test fun errorGivesNull() = assertNull(VersionJson.parse("""{"result":null,"error":"unknown method"}"""))
@Test fun garbageGivesNull() = assertNull(VersionJson.parse("<html>"))
```

- [ ] **Step 2: Run** the two test classes. Expected: compile failure.
- [ ] **Step 3: Implement** the new `StreamUrls` and `VersionJson`, and update callers (`EngineService`, `PlayActivity`, `SearchScreen`, `HomeScreen`) mechanically to `Endpoint.loopback(port)` so the app still compiles with today's behavior. Later tasks swap in the real endpoint.
- [ ] **Step 4: Run** `testDebugUnitTest`. Expected: PASS (whole suite).
- [ ] **Step 5: Commit** `refactor: address engines by endpoint`.

---

### Task 3: Engine selection model and storage

**Files:**
- Create: `S/core/EngineChoice.kt`, `S/engine/EngineSelection.kt`
- Modify: `S/Prefs.kt`, `S/Device.kt` (`EngineFiles.activeVersion` deleted), `S/BootReceiver.kt`, `S/ui/VersionsActivity.kt`, `S/ui/PlayActivity.kt`, `S/ui/SearchScreen.kt`, `S/ui/HomeScreen.kt`, `S/engine/EngineService.kt` (callers of `activeVersion`)
- Test: `T/core/EngineChoiceTest.kt`

**Interfaces:**
- Consumes: `RemoteEngine`, `RemoteEngines` (Task 1).
- Produces:
  - `sealed interface EngineChoice { data class Local(val version: String); data class Remote(val id: String) }` and `object EngineChoices`:
    - `fun encode(c: EngineChoice?): String` (`"local:<v>"`, `"remote:<id>"`, `"none"`), `fun decode(s: String?): EngineChoice?`
    - `fun migrate(legacyActive: String?, installed: List<String>): EngineChoice?` = `Local(legacyActive)` if installed, else `Local(installed.first())` if any (list is newest first), else `null`.
    - `fun resolve(c: EngineChoice?, installed: List<String>, remotes: List<RemoteEngine>): EngineChoice?` = `c` only if its version is installed / its id exists; otherwise `null` (no fallback).
    - `fun afterInstall(c: EngineChoice?, version: String): EngineChoice?` = `c ?: Local(version)`.
    - `fun afterRemove(c: EngineChoice?, removed: EngineChoice): EngineChoice?` = `null` if `c == removed`, else `c`.
  - `Prefs`: `var engineChoiceRaw: String?` (key `engine_selection`, null when absent), `var remotes: List<RemoteEngine>` (key `remote_engines`), `var remoteConnected: Boolean` (key `remote_connected`, default false). `activeVersion` stays only as the legacy migration source.
  - `object EngineSelection` (Android-aware, main thread):
    - `fun current(ctx): EngineChoice?` (migrates once: when `engineChoiceRaw == null`, stores `encode(migrate(prefs.activeVersion, installed))`; then returns `resolve(...)`).
    - `fun localVersion(ctx): String?` = version of `current` when `Local`.
    - `fun remote(ctx): RemoteEngine?` = the selected remote.
    - `fun select(ctx, choice: EngineChoice?)`: apply the switching rules: if a remote session is active (`EngineController.target` is remote) and the choice differs, call `EngineController.stop`; if the local engine is running and the choice is a remote or `null`, stop it; if the local engine is running a different version and the choice is `Local`, restart it (today's `activate`). Sets `remoteConnected = false` whenever the choice changes away from a remote.
    - `fun installed(ctx, version)`: `select`-free store of `afterInstall`.
    - `fun removeLocal(ctx, version)` (refuses while that version runs, as today, returning false), `fun removeRemote(ctx, id)` (stops the session first if it is that remote), both applying `afterRemove`.
    - `fun saveRemote(ctx, r: RemoteEngine): Boolean` (add or replace by id, false when the cap is hit). Editing the remote of the active session to a new endpoint restarts the session (`EngineController.restart`) once Task 5 exists.

- [ ] **Step 1: Write `EngineChoiceTest`:**

```kotlin
@Test fun roundTrips() { for (c in listOf(EngineChoice.Local("1.0.1"), EngineChoice.Remote("a1"), null)) assertEquals(c, EngineChoices.decode(EngineChoices.encode(c))) }
@Test fun decodeGarbageIsNone() = assertNull(EngineChoices.decode("weird"))
@Test fun migrateKeepsStoredActive() = assertEquals(EngineChoice.Local("1.0.0"), EngineChoices.migrate("1.0.0", listOf("1.0.1", "1.0.0")))
@Test fun migrateFallsBackToNewestOnce() = assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.migrate(null, listOf("1.0.1", "1.0.0")))
@Test fun migrateFreshInstallIsNone() = assertNull(EngineChoices.migrate(null, emptyList()))
@Test fun noFallbackToNewest() = assertNull(EngineChoices.resolve(EngineChoice.Local("0.9.0"), listOf("1.0.1"), emptyList()))
@Test fun resolveMissingRemoteIsNone() = assertNull(EngineChoices.resolve(EngineChoice.Remote("gone"), listOf("1.0.1"), emptyList()))
@Test fun installSelectsOnlyWhenNone() { assertEquals(EngineChoice.Local("1.0.2"), EngineChoices.afterInstall(null, "1.0.2")); assertEquals(EngineChoice.Remote("a1"), EngineChoices.afterInstall(EngineChoice.Remote("a1"), "1.0.2")) }
@Test fun afterRemoveClearsSelected() { assertNull(EngineChoices.afterRemove(EngineChoice.Remote("a1"), EngineChoice.Remote("a1"))); assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.afterRemove(EngineChoice.Local("1.0.1"), EngineChoice.Remote("a1"))) }
```

- [ ] **Step 2: Run** `--tests '*EngineChoiceTest'`. Expected: compile failure.
- [ ] **Step 3: Implement** `EngineChoice.kt`, the `Prefs` fields and `EngineSelection`. Delete `EngineFiles.activeVersion` and point its callers at `EngineSelection.localVersion` (service, boot, play, search, home). In `VersionsActivity`: "Use" calls `EngineSelection.select(ctx, Local(v))`; a finished install calls `EngineSelection.installed(ctx, v)` instead of `activate`; "Remove" calls `removeLocal`. The "active" badge reads `EngineSelection.current`. `EngineController.target` is introduced in Task 5; until then `select` checks only the local state, and Task 5 adds the remote branch.
- [ ] **Step 4: Run** `testDebugUnitTest` and `assembleDebug`. Expected: PASS and builds.
- [ ] **Step 5: Commit** `feat: store the selected engine without falling back`.

---

### Task 4: Cleartext to remote hosts

**Files:**
- Modify: `app/src/main/res/xml/network_security_config.xml`

- [ ] **Step 1:** Replace the config with `<base-config cleartextTrafficPermitted="true" />` and a comment: remote Outpace engines serve plain HTTP on LAN hosts, which Android cannot whitelist by range; release downloads use HTTPS URLs. Drop the now-redundant loopback `domain-config`.
- [ ] **Step 2: Run** `./gradlew --no-daemon lintDebug`. Expected: PASS. If lint reports `InsecureBaseConfiguration`, add `tools:ignore="InsecureBaseConfiguration"` (with `xmlns:tools`) on `base-config` and rerun until PASS.
- [ ] **Step 3: Commit** `feat: allow plain HTTP to remote engines`.

---

### Task 5: Remote mode in the engine service and controller

**Files:**
- Create: `S/core/RemoteHealth.kt`
- Modify: `S/engine/EngineController.kt`, `S/engine/EngineService.kt`, `S/engine/EngineSelection.kt`, `S/BootReceiver.kt`, `S/ui/MainActivity.kt`, `app/src/main/res/values/strings.xml`
- Test: `T/core/RemoteHealthTest.kt`

**Interfaces:**
- Consumes: `Endpoint`, `StreamUrls.*`, `VersionJson` (Task 2), `EngineSelection` (Task 3).
- Produces:
  - `EngineState.Unreachable(val reason: String)`: a remote session that is still connected and retrying.
  - `sealed interface EngineTarget { data class Local(val version: String) ; data class Remote(val engine: RemoteEngine) }`.
  - `EngineController`: `@Volatile var target: EngineTarget?` (what the service is running or connected to; null when stopped), `@Volatile var endpoint: Endpoint?` (where it is reachable now), `@Volatile var remoteVersion: String?`; `fun endpointFor(ctx): Endpoint?` = `endpoint` while a session exists, else the selection's endpoint (`Endpoint.loopback(prefs.settings.port)` for local, the remote's endpoint for remote, `null` for none). `awaitRunning` returns false on `Crashed` **or** `Unreachable`. `start(ctx)` is unchanged in shape; the service decides the mode from `EngineSelection.current`.
  - `object RemoteHealth { fun isOk(body: String): Boolean = body.trim() == "ok" }`.

- [ ] **Step 1: Write `RemoteHealthTest`:** `isOk("ok\n")` true; `isOk("<html>ok</html>")` false; `isOk("")` false.
- [ ] **Step 2: Run it.** Expected: compile failure.
- [ ] **Step 3: Implement remote mode in `EngineService`.**
  - `startEngine()` reads `EngineSelection.current`: `Local` keeps today's path (and sets `target`/`endpoint` loopback); `Remote` calls `startRemote(engine)`; `null` fails with `R.string.no_engine_selected` ("No engine selected").
  - `startRemote`: `target = Remote`, `endpoint = engine.endpoint`, state `Starting`, notification; one health probe (`Http.getWithin(StreamUrls.health(e), 3_000)` + `RemoteHealth.isOk`); on success fetch `VersionJson.parse` once (failure leaves `remoteVersion = null`), state `Running`; on failure state `Unreachable(reason)`. Either way `prefs.remoteConnected = true` and schedule polling.
  - Polling in remote mode: each poll probes health then `/streams` (+ `/status` budget as today); a failure sets `Unreachable(reason)` and a success after `Unreachable` sets `Running` (fetch version again on that transition). Interval is the existing `UI_POLL_MS`/`BACKGROUND_POLL_MS`. No idle stop and no wake lock in remote mode. `schedulePoll` must also run in `Unreachable`.
  - Stale results: keep an `Int generation` incremented on every start, stop and restart; a poll captures it at schedule time and drops its result if it changed.
  - `stopEngine()` in remote mode: cancel poll, clear `target`/`endpoint`/`remoteVersion`, state `Stopped`, and `prefs.remoteConnected = false` (so the notification's action really disconnects).
  - Notification: remote text `"Connected to %1$s, %2$d streams"` (plural `notification_remote`) or `"%1$s is not answering"` when unreachable; the action label is `action_disconnect` ("Disconnect") in remote mode.
  - `EngineSelection.select`: add the remote branch from Task 3 (stop when the active target is a remote and the choice differs).
  - Reconnect: `MainActivity.onStart` and `BootReceiver` call `EngineController.start` when the selection is a remote, `prefs.remoteConnected` is true and the state is `Stopped`. `BootReceiver` keeps the start-on-boot rule for local selections only.
- [ ] **Step 4: Run** `testDebugUnitTest assembleDebug`. Expected: PASS.
- [ ] **Step 5: Manual check on the emulator** (local x86_64 engine from `outpace_spike`, plus a remote: run `cargo run -p ace-engine --bin outpace -- serve` on the host with `OUTPACE_BIND=0.0.0.0:6878` and add `10.0.2.2:6878`): connect shows Running with version (version only if `OUTPACE_ACE_COMPAT=true`); killing the host engine turns the state Unreachable within one poll and back to Running after restarting it; Disconnect from the notification stops polling and clears `remoteConnected`.
- [ ] **Step 6: Commit** `feat: connect to remote engines from the engine service`.

---

### Task 6: Engines tab and slimmer Home and Settings

**Files:**
- Create: `S/ui/EnginesScreen.kt`, `S/ui/EngineSettingsPanel.kt`, `S/ui/RemoteDialog.kt`, `app/src/main/res/layout/screen_engines.xml`, `app/src/main/res/layout/item_engine.xml`, `app/src/main/res/layout/dialog_remote.xml`, `app/src/main/res/drawable/ic_engine.xml`
- Modify: `S/ui/MainActivity.kt`, `S/ui/HomeScreen.kt`, `S/ui/SettingsScreen.kt`, `S/ui/SearchScreen.kt`, `S/ui/PlayActivity.kt`, `app/src/main/res/layout/screen_home.xml`, `app/src/main/res/layout/screen_settings.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: everything above.
- Produces: `MainActivity.TAB_HOME = 0`, `TAB_SEARCH = 1`, `TAB_ENGINES = 2`, `TAB_SETTINGS = 3` (update `tabFrom` range); `EnginesScreen(host, parent) : Screen`; `EngineSettingsPanel(host: MainActivity, search: LinearLayout, playback: LinearLayout, network: LinearLayout, peers: LinearLayout)` with `fun onShown()` and `fun render()`, holding the engine-setting rows and port fields moved verbatim out of `SettingsScreen`.

- [ ] **Step 1: Navigation.** Four tabs: Home (`ic_home`), Search (`ic_search`), Engines (new `ic_engine`, a 24dp vector in the same style as `ic_tune`), Settings (`ic_tune`). Check the side rail (TV/landscape) still fits four 76dp items.
- [ ] **Step 2: Engines tab** (`screen_engines.xml`, a `ScrollView` like the others):
  - Title "Engines". Then the list: one `item_engine` per installed local version ("This device", meta `v<version>`) then per remote (name, meta `host:port`), each with a status dot and a "Selected" badge on the selected row. Rows are focusable and clickable.
  - Row click opens an `AlertDialog` menu: "Use this engine" (hidden when selected) → `EngineSelection.select`; for remotes "Edit" → `RemoteDialog`; "Remove" → `removeLocal`/`removeRemote` (toast when refused because it runs).
  - "Add engine" button → menu: "Download to this device" → `VersionsActivity`; "Add a remote engine" → `RemoteDialog` (toast `remote_full` when `MAX_REMOTES` is hit).
  - Remote dots: while the tab is visible, a dedicated single-thread executor probes every remote except the active session's with `Http.getWithin(StreamUrls.health(e), 2_000)` + `RemoteHealth.isOk`, then again every 15s; it stops in an `onHidden` hook (add `open fun onHidden() = Unit` to `Screen`, called by `MainActivity.select` for the previously shown tab and from `onStop`). Dot colors: `ok` alive, `err` unreachable, `text_muted` not checked yet. The active remote's dot mirrors `EngineController.state`.
  - Local section, visible only when a local version is selected: Start/Stop, Restart, Versions, Logs, the update-available button and the restart-needed banner (all moved from Home), then the `EngineSettingsPanel` groups (search, playback, network, peers, moved from Settings).
  - Remote section, visible only when a remote is selected: Connect/Disconnect and the note `remote_settings_note` ("This engine uses its own configuration. Change it on the device that runs it.").
- [ ] **Step 3: `RemoteDialog`:** name and address fields (`dialog_remote.xml`, address hint `192.168.1.20:6878`), Save validates with `RemoteEngines.validName` / `parseAddress` and shows `setError` inline, without dismissing, on failure. Saving a new remote does not select it.
- [ ] **Step 4: Home card** (`screen_home.xml`): dot, state line, detail line, address line, one button. Remove Versions, Logs, update and restart banner from Home. State/button table:

  | Selection / state | State line | Detail line | Button |
  |---|---|---|---|
  | none | "No engine selected" | "Download one or add a remote engine" | Configure → Engines tab |
  | local, stopped | "Engine stopped" | "This device, v1.0.1" | Start |
  | local, starting/running | "Starting engine" / "Engine running" | "This device, v1.0.1" | Stop |
  | local, crashed | "Engine error" | reason | Start |
  | remote, disconnected | "Not connected" | "<name>, host:port" | Connect |
  | remote, connecting | "Connecting" | "<name>, host:port" | Disconnect |
  | remote, running | "Connected" | "<name>, v1.0.2" (omit version when null) | Disconnect |
  | remote, unreachable | "Not answering" | reason | Disconnect |

  The address line shows `EngineController.endpoint` (for local, LAN address when `lan`, as today) only while Running.
- [ ] **Step 5: Settings tab** keeps the title, autosave note, the background group (start on boot, now summarised as "Start this device's engine when the device boots", idle stop, battery) and about. Drop its restart banner.
- [ ] **Step 6: Search and Play.**
  - `SearchScreen.block()`: `null` selection → `NoEngine` with action "Configure" opening the Engines tab; for a remote selection skip the `Off`/`Old`/`NeedsRestart` checks; a 404 from a remote shows `search_err_remote_off` ("Search is off on this engine. Turn it on in that engine's configuration."). `fetch` uses `EngineController.endpointFor(host)`; the start text says "Connecting to the engine" for a remote.
  - `PlayActivity`: `null` selection → `no_engine_selected`; player and share URLs use `EngineController.endpointFor(this)`; for local keep loopback for the player and the LAN rule for sharing; for a remote both use the remote endpoint and the LAN hint is skipped.
- [ ] **Step 7: Run** `testDebugUnitTest lintDebug assembleDebug`. Expected: PASS.
- [ ] **Step 8: Manual check on the emulator,** phone and TV (D-pad) layouts: fresh install shows Configure; download selects the version; add the host remote, use it (local engine stops), connect, play a stream through VLC with the remote URL, search shows the remote-off message when the host engine has search off; switch back to the local version (remote disconnects); delete the selected remote → Home shows Configure.
- [ ] **Step 9: Commit** `feat: add the engines tab with remote engines`.

---

### Task 7: Docs and final gate

**Files:**
- Modify: `docs/Android-App.md`, `README.md` (only if it describes Home controls)

- [ ] **Step 1:** Update `docs/Android-App.md`: Home now shows the connected engine and one button (table above, in prose); new "Engines" section (local versions and remote engines in one list, adding, health dots, selecting, connect behavior in the background, unreachable retries, the reconnect after relaunch or boot, remote settings are not editable from the app, every stream on a shared engine is listed); Settings keeps app-level items; the plain-HTTP note. Use example host `192.168.1.20:6878`. No em dashes.
- [ ] **Step 2: Run** the full gate: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease` and the identifier check. Expected: both pass.
- [ ] **Step 3: Commit** `docs: describe the engines tab and remote engines`.
