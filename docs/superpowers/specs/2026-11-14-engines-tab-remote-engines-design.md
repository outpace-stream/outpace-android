# Engines tab and remote engines

## Intent

Home currently carries the whole engine control surface: state, address, Start/Stop, Versions,
Logs, an update button and a restart banner. The app also assumes the engine always runs on this
device at `127.0.0.1`. Two changes:

1. Slim Home down to "am I connected, to which engine, which version" plus one action button, and
   move engine management into its own **Engines** tab.
2. Let the user add **remote engines** (an Outpace engine running on another device, reached by
   `ip:port`) next to downloaded local versions, and switch between them freely. A user can then
   either install a local engine or point the phone at, for example, a home server or a TV box.

## Goals

- One list in the Engines tab holding every downloaded local version and every configured remote
  engine. Exactly one entry is **selected** (the active engine) at a time, or none.
- An **Add engine** button with two paths: "Download to this device" (the existing release picker)
  and "Add remote" (name plus `ip:port`).
- Every remote entry shows a health indicator (alive / unreachable / checking).
- With a remote engine selected and connected, everything the app does today works against it:
  active streams (with peers and buffer), recent, play by id, search, and the player hand-off.
  The player receives the remote engine's URL directly; video never passes through the phone.
- Home shows connection state, engine name and version, and a single context button:
  - **Configure**: nothing configured or nothing selected; opens the Engines tab.
  - **Start / Stop**: a local entry is selected.
  - **Connect / Disconnect**: a remote entry is selected.
- Home keeps the play box, now playing and recent sections unchanged.

## Non-goals

- Metrics collection from remote engines (CPU and similar). "Connect" is designed so a metrics
  crawl can be added later, but none is built now.
- Network discovery of engines (mDNS, scanning). Remotes are typed in.
- Controlling a remote engine's settings, versions, restarts or logs. The phone does not own that
  process.
- Authentication or TLS to remote engines. The engine serves plain HTTP with no auth.
- Any engine-side change. This lives entirely in outpace-android (AGENTS.md: engine changes
  belong in outpace-engine).
- Showing which device watches each stream. The user wants this eventually ("this device" or the
  client's IP), but the engine reports only a `clients` count today. Tracked in
  outpace-stream/outpace-engine#473; the app keeps showing the count for now.

## Anti-goals

- A remote engine must never be able to start, stop or reconfigure the local engine, and switching
  must never leave two things polling at once.
- No unbounded polling: health checks run only while they are useful (see below), with short
  timeouts, and nothing new runs in the background just to show a list.
- Do not regress the existing local flow: first start, download, start/stop, idle stop, start on
  boot and the restart banner must behave as today when a local entry is selected.

## Decided behavior

### Engine entries and selection

- Local entries are derived from the versions installed in `EngineStore`, one per version,
  labelled "This device" with the version (for example "This device, v1.0.1"). Selecting one is
  today's "switch active version". Deleting one deletes its files, as today.
- Remote entries are stored in `Prefs` (name, host, port, a stable id). They can be added, edited
  and deleted. Validation: non-empty name, a host (IPv4, IPv6 or hostname), port 1..65535.
- The selection is persisted and can be "none". Today `EngineFiles.activeVersion()` falls back
  to the newest installed version; that fallback goes away and every caller (service, boot,
  play, search, versions) handles "no selection". (My call.)
- Migration (my call): an existing install keeps what it runs today, that is the stored active
  version, or the newest installed version when none was stored. A fresh install starts with
  nothing selected.
- Deleting the selected entry, local or remote, leaves nothing selected; Home then shows
  Configure. (My call.)
- A finished download selects the new version only when nothing is selected. Otherwise it just
  appears in the list, so downloading never silently disconnects a remote. With a local version
  selected, the existing "switch to it" choice in the picker still works. (My call.)
- Selecting a remote while the local engine runs stops the local engine.
- Selecting a different entry while connected to a remote disconnects from it first.

### Local engine (selected)

- Unchanged from today: the foreground `EngineService` owns the process, health check and
  `/streams` polling, idle stop and wake lock.
- Start on boot applies only when a local entry is selected.

### Remote engine (selected)

- **Connect** starts a periodic `GET /healthz` (expects `ok`) plus the same `/streams` and
  per-stream `/status` polling the local engine gets, against `http://host:port`. Polling uses the
  existing bounded intervals and per-poll time budget. **Disconnect** stops it.
- The connection runs in the foreground service, decided by the user: while connected, polling
  continues with the app in the background, and the notification shows the remote engine and its
  stream count, as it does for the local engine. This matters most while a player is streaming
  from it. The notification's Stop action becomes Disconnect. The connection is remembered, so
  the app reconnects on the next launch or boot when the remote is still selected and was
  connected. (Reconnect on launch and boot is my call.)
- Connection states: connecting, connected, unreachable, disconnected. A connected remote that
  fails its health check becomes "unreachable" (red, with the reason) but stays connected and
  keeps retrying at the normal poll interval, returning to connected when it answers. Only the
  user disconnects. A brief Wi-Fi drop must not end the session. (My call.)
- "Stop when idle" and the wake lock apply only to the local engine. A remote connection is never
  idle-disconnected. A shared engine may always show other devices' streams, and the phone holds
  no process that needs keeping awake. (My call.)
- Now playing lists every stream the remote engine reports, including other devices' streams
  (user's decision). Per-device attribution is out of scope (see Non-goals).
- Version: read from `GET /server/api?method=get_version` when the engine answers it; omitted from
  the UI otherwise. It is fetched once per connect, not on every poll.
- Play while a remote is selected but disconnected connects automatically, as play starts a
  stopped local engine today. If the remote does not answer within the start timeout, play fails
  with the reason, as today.
- Search with a remote engine: if the engine reports search disabled or the endpoint is missing,
  the Search tab says search is off on that engine. It does not offer a restart.
- The play and share URLs use the remote host and port. The LAN-address hint does not apply.

### Health indicator in the list

- While the Engines tab is visible, each remote entry is probed with `GET /healthz` using a short
  timeout, then re-probed at a modest interval. Probing stops when the tab is hidden.
- The connected remote's dot reflects the connection's own health check instead.

### Tabs

- Navigation becomes Home / Search / Engines / Settings.
- **Engines tab** contents: the engine list with Add engine; for the selected local engine,
  Start/Stop, Restart, Logs, the update-available hint, the restart-needed banner and the engine
  settings (playback, network, peers sections that live in Settings today).
- With a remote selected, the local-only controls and engine settings are hidden. A short note
  explains that the remote engine's own configuration applies.
- **Settings** keeps app-level items: start on boot, stop when idle, battery optimisation, about.
- Home's card loses Versions, Logs, the update button and the restart banner. The address line
  stays only while connected or running.

### Network security

Remote engines are plain HTTP on arbitrary LAN hosts, so the network security config must allow
cleartext beyond loopback. Android cannot whitelist IP ranges, so `base-config` becomes
cleartext-permitted. Release downloads already use HTTPS URLs, so nothing the app fetches today
changes transport. The PR states this change explicitly.

## Constraints

- Plain Android Views, no new dependencies, minSdk and targetSdk unchanged (AGENTS.md: keep polling,
  buffers and restart attempts bounded).
- D-pad and TV navigation must work on every new control, including the add/edit remote dialog and
  the nav rail with four items.
- Identifier hygiene: tests use the synthetic placeholder id; no real hosts or ids in tests or docs.
- Docs: `docs/Android-App.md` updated in the same PR (Home, Engines tab, remote engines, cleartext
  note).
- Verification: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease`, the shared
  identifier check, and a manual emulator pass covering local, remote and switching.

## Left to the builder

- Where the connection logic lives (extend `EngineService`/`EngineController` with a target, or a
  separate remote poller sharing the state and streams model). Requirement: one source of truth
  for "current engine state and streams" that Home, Search and Play read without caring whether
  the engine is local or remote.
- Search with an unknown remote version: skip the minimum-version check and rely on the
  endpoint's 404 mapping.
- Exact layout, strings and icons; whether "Download to this device" opens `VersionsActivity` as
  is or a trimmed picker.
- Health probe interval and timeout values, within the bounded-polling rule.
