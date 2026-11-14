# Android App

> **Early release:** signed APKs are published on the
> [releases page](https://github.com/outpace-stream/outpace-android/releases), starting with v0.1.0.
> Testing on real phones and TV boxes is still in progress. App versions are independent of
> engine versions.

The Outpace Android app runs the engine on a phone or an Android TV box without root and
without Termux, or connects to an Outpace engine running on another device, such as a home
server or a TV box. It downloads the engine release you choose, keeps it running in the
background, and hands streams to the video player you already use, such as VLC, MX Player, Just
Player or Kodi. It has no player of its own.

## Install

1. Download `outpace-android-<version>.apk` from the
   [releases page](https://github.com/outpace-stream/outpace-android/releases) and check it against its
   `.sha256` file.
2. Open it on the device and allow installing from that source when Android asks. On Android TV,
   copy it over with a USB drive or a file-transfer app, or install it from a computer with
   `adb install outpace-android-<version>.apk`.

The app needs Android 5 or newer, on 64-bit ARM (`arm64-v8a`) or 32-bit ARM (`armeabi-v7a`).
Many Android TV devices run 32-bit Android even on 64-bit chips; the app picks the right engine
build for you. 32-bit engine builds are published from the engine release after v1.0.1.

The APK is sideloaded only. It isn't on the Play Store, because Play policy doesn't allow apps
that download programs to run. Once installed, the app can update itself from the same releases
page; see [App updates](#app-updates).

## App updates

The app checks the [releases page](https://github.com/outpace-stream/outpace-android/releases) for
a newer stable version at most once a day, when you open its main screen. Pre-releases are
ignored. A failed check stays silent and is retried later.

When a newer version exists, a dialog shows the version you have, the latest one, and the release
notes of every version newer than yours. It appears at most once a day, never while a stream is
playing. Its buttons:

- **Update** downloads the APK, checks it against its `.sha256` file and opens Android's install
  screen. Nothing installs without your confirmation.
- **Not now** closes the dialog until the next day.
- **Never ask again** turns the dialog off. You can still update from Settings.

The first update asks you to allow **Install unknown apps** for Outpace (Android 8 and newer).
The app explains this and opens that setting; come back to the app and the update continues. If
your device has no such screen, turn it on in the device settings. On Android 5 to 7 the global
**Unknown sources** setting applies instead.

Updating restarts the app, so the engine and any stream playing stop. The app warns you first when
a stream is playing. If you leave the app while the update downloads, the install screen opens
when you come back. If Android can't install the update, the app says why and offers the release
page so you can install it by hand.

**Settings > App updates** works whatever you chose in the dialog. It shows the installed and
latest versions and when the app last checked, and has **Check for updates**, **Changelog**,
**Update**, and the **Notify me about updates** switch that **Never ask again** turns off.

Only signed release builds update this way. A build you compiled yourself is signed with a
different key, so Android refuses the update, and installing the release APK by hand fails the same
way. To switch to the official release, uninstall the app first, then install the APK from the
releases page. Uninstalling deletes the app's data, including the downloaded engine and your
settings. Development builds (version `0.0.0`) never show the dialog
and can't update from Settings.

## First start

The APK contains no engine. On first start Home shows **No engine selected** and a
**Configure** button that opens the **Engines** tab. There, **Add engine** offers two choices:

- **Download to this device** opens **Engine versions** and offers the latest release built for
  your device. The app downloads the archive, checks it against the release's `.sha256`, installs
  it and selects it. After that, **Start engine** runs it.
- **Add a remote engine** uses an Outpace engine on another device instead; see
  [Engines](#engines).

The engine listens on `127.0.0.1:6878`, the same address as on any other platform, so players on
the same device reach it with the usual [URLs](https://github.com/outpace-stream/outpace-engine/wiki/Playing-Streams).

## Finding your way around

The main screen has four tabs: **Home**, **Search**, **Engines** and **Settings**. On a phone held
upright they sit along the bottom; on TV, tablets and landscape phones they form a rail on the
left. Back from any other tab returns to Home.

Home shows which engine the app uses and whether it is running or connected, with a coloured dot,
the engine's name and version, and the address players use. It has one button:

| Selected engine | Button |
| --- | --- |
| None | **Configure** opens the Engines tab. |
| This device | **Start engine** or **Stop engine**. |
| A remote engine | **Connect** or **Disconnect**. |

Below it, Home lists what is playing now and what you played recently. Versions, logs, restarts
and engine settings live in the [Engines](#engines) tab.

## Engines

The **Engines** tab lists every engine the app can use in one list: each engine version downloaded
to this device ("This device, v1.0.1") and each remote engine you added (its name and address).
One of them is **Selected**; Home, Search and playback all use it. Choose a row to use that
engine, to edit a remote engine, or to remove it. Editing the connected remote engine reconnects
to it at once; playback continues. Removing the selected engine leaves nothing selected, and Home
shows **Configure** again.

Switching is free in both directions. Selecting a remote engine stops this device's engine if it
runs, and selecting another engine disconnects from a remote one first.

### This device

When a downloaded version is selected, the tab shows its state with **Start engine** or **Stop
engine**, **Restart**, **Versions** ([Engine versions](#engine-versions)) and **Logs**, a
**vX.Y.Z available** button when a newer release exists, and the engine [settings](#engine-settings).
When you change settings while the engine runs, a banner offers **Restart** to apply them.

### Remote engines

A remote engine is an Outpace engine running on another device of your network. On that device,
make its HTTP API reachable from the network, for example `OUTPACE_BIND=0.0.0.0:6878` (in the
Android app: **Allow LAN access**). Then choose **Add engine > Add a remote engine** and enter a
name and its address, such as `192.168.1.20:6878`. The port defaults to `6878`; a pasted
`http://` prefix is accepted. Up to 16 remote engines can be added.

While the Engines tab is open, each remote engine shows whether it answers: a green dot for
online, a red one for not answering. The app checks each one every 15 seconds, and only while the
tab is open.

**Connect** makes the app check the selected remote engine and list its streams, the same way it
does for its own engine. While connected, the app keeps a notification with the stream count and
a **Disconnect** button, and keeps checking in the background, so a player streaming from that
engine keeps working with the app closed. If the engine stops answering, Home shows **Not
answering** and the app keeps retrying until it answers again or you press **Disconnect**. A
remote engine left connected reconnects when the app opens again or the device restarts.

With a remote engine:

- Players receive the remote engine's address, for example
  `http://192.168.1.20:6878/streams/ace/cid:<id>`, so video goes straight from that device to the
  player. **Copy URL** and **Share URL** give the same address.
- **Now playing** lists every stream the engine is playing, including streams other devices
  started.
- Search works if that engine has search turned on; otherwise the Search tab says it is off on
  that engine.
- The remote engine's own configuration applies. The app does not change its settings, versions,
  restarts or logs. Home shows its version when the engine answers the AceStream-compatible
  `get_version` call (`OUTPACE_ACE_COMPAT=true`).

The engine's HTTP API is plain HTTP with no password, so the app allows unencrypted connections to
any address, which Android cannot limit to the local network. Add only engines on networks you
trust.

## Playing a stream

You can start playback from:

- **A link.** Tap an `acestream://<id>` or `acestream:?content_id=<id>` link in a browser or chat
  app and choose Outpace.
- **The share menu.** Share text that contains a content id or an `acestream:` link to Outpace.
- **Home.** Paste a content id or link into the box and press **Play**. On phones, **Paste**
  fills the box from the clipboard.
- **Search.** Find a stream by name and press its **Play** button; see [Search](#search).
- **Recently played.** Home lists the last 12 streams you played, with their names when known.
  This is the easy way to replay on a TV remote. **Clear** forgets them; long press a row's
  **Play** button to copy its id or remove just that one.
- **Now playing.** While the engine runs, Home lists what it is playing, with the title, the
  number of clients, peers and seconds buffered. **Play** opens it again. Few or no
  peers usually means the stream is hard to find; see
  [Troubleshooting](https://github.com/outpace-stream/outpace-engine/wiki/Troubleshooting).

The app starts the engine (or connects to the selected remote engine) if needed, waits until it
answers, then opens Android's "Play with" chooser for `http://127.0.0.1:6878/streams/ace/cid:<id>` (or `ih:<infohash>` for a
search result). Tick "Always" to go
straight to your favourite player next time. The same screen also has:

- **Open as HLS**, for players that handle `.m3u8` better than a raw MPEG-TS stream.
- **Copy URL** and **Share URL**, for players that don't appear in the chooser or for another
  device. To play on another device, turn on **Allow LAN access** first; the copied URL then
  uses this device's LAN address instead of `127.0.0.1`.

Links, shared text and the paste box take content ids only. Infohashes and magnet links pasted or
shared there are rejected; to play by infohash, use Search. A bare 40-character hex string is
treated as a content id, as the engine does.

The app plays live streams. VOD content ids are not handled yet.

## Search

**Search** looks up streams by name through the engine's
[stream search](https://github.com/outpace-stream/outpace-engine/wiki/Stream-Search), which needs
engine v1.0.0 or newer. It is off by default: your query and this device's IP address go to
AceStream's public index over plain HTTP, where the index and anyone on the network path can see
them. Turn it on with **Turn on search** on the Search tab or **Stream search** in the Engines tab; a
running engine restarts to pick it up. With a remote engine selected, search uses that engine's
own setting.

Type a name and press **Search** (or the keyboard's search key). The app starts the engine if
needed. Results are grouped by channel and show categories, languages and availability where the
index has them. Each stream has a **Play** button; long press it to copy the infohash. **Load more
results** fetches the next 10 channels. Your last 8 searches appear as buttons so you can repeat
one without typing.

Results are discovery data, not a promise that a stream plays. A result plays only if the engine
finds a verified descriptor for its infohash; otherwise the player reports an error (`422`). The
index also limits how often it answers: after a failure, wait about 30 seconds before searching
again.

## Engine versions

**Engine versions** lists Outpace releases, newest first. Each row shows the release date and
badges:

| Badge | Meaning |
| --- | --- |
| running | The engine that is running now. |
| selected | The version that runs the next time the engine starts. |
| installed | Downloaded and ready on this device. |
| latest | The newest release. |
| pre-release | Hidden unless **Show pre-releases** is ticked. |
| not available for this device | That release has no Android build for this device. Releases before v1.0.0 have none, and 32-bit builds (`armv7-linux-androideabi`) start with the release after v1.0.1. |

Choose a row to download it, switch to it or remove it. A download becomes the selected engine
only when no engine is selected; otherwise it joins the Engines list. Switching while the engine
runs restarts it on the new version. Up to 3 versions can be installed at once. The running
version, or one that is starting, can't be removed.

When a newer release exists, the Engines tab shows a **vX.Y.Z available** button. The app checks GitHub when the main screen opens, at most once a day, and never upgrades
the engine by itself.

GitHub limits how often its API can be called without an account, per IP address. Behind a VPN
or a shared mobile network that limit can run out. The full list then shows only the latest
and installed versions, with a short note. Downloads and the latest-version check don't use
that API and keep working.

## Engine settings

Engine settings sit in the Engines tab, below this device's engine, and appear only while a
downloaded version is selected. They save as soon as you change them and apply the next time the
engine starts. While the engine runs with older settings, a banner offers **Restart** to apply
them right away. Playback presets set an engine variable only when they differ from the engine
default. **Start on boot** and **Stop when idle** are in the **Settings** tab.

| Setting | Default | What it sets |
| --- | --- | --- |
| Startup buffer | Smooth (30 s) | [`OUTPACE_PREBUFFER_MS`](https://github.com/outpace-stream/outpace-engine/wiki/Configuration#playback-and-startup-buffering): Balanced sets `15000`, Fast `5000`, Off `0`. Smooth leaves the engine default. A shorter buffer starts sooner and stutters more on weak swarms. |
| HLS start | Default | [`OUTPACE_HLS_STARTUP_SEGMENTS`](https://github.com/outpace-stream/outpace-engine/wiki/HLS-Output) and `OUTPACE_HLS_SEGMENT_DURATION_MS`: Fast sets `2` segments of `4000` ms, the engine's fast-start profile. Affects **Open HLS** only. Segments still cut on keyframes, so streams with long keyframe intervals gain less. |
| Catalog fallback | Off | [`OUTPACE_BEP9_CONTENT_ID_FALLBACK`](https://github.com/outpace-stream/outpace-engine/wiki/Configuration#discovery-trackers-and-resolution): when the catalog can't resolve a content id, ask peers for its details. Needed to play an Outpace broadcast started on another device. |
| Low-memory mode | Off | Keeps seeding pieces in the app's cache directory instead of RAM ([`OUTPACE_CACHE_TYPE`](https://github.com/outpace-stream/outpace-engine/wiki/Configuration#storage-and-cache)`=disk`) and lowers `OUTPACE_SEED_STORE_BYTES` and `OUTPACE_PREBUFFER_BYTES` to 32 MiB and `OUTPACE_SESSION_BUFFER` to `64`. On high-bitrate streams the 32 MiB cap releases playback before the startup buffer target. |
| Allow LAN access | Off | [`OUTPACE_BIND`](https://github.com/outpace-stream/outpace-engine/wiki/Environment-Variables) to `0.0.0.0:<port>` instead of `127.0.0.1:<port>`. Use it only on a trusted network. |
| HTTP port | 6878 | The port in `OUTPACE_BIND`. |
| Seeding | On | [`OUTPACE_ENABLE_SEEDING`](https://github.com/outpace-stream/outpace-engine/wiki/Seeding-and-Peers) |
| Inbound upload limit | Normal | [`OUTPACE_MAX_UNCHOKED`](https://github.com/outpace-stream/outpace-engine/wiki/Configuration#seeding-and-inbound-peers), `OUTPACE_MAX_INBOUND` and `OUTPACE_MAX_INBOUND_PER_STREAM`: Low sets `2`, `16` and `4`; Minimal sets `0`, `4` and `2`. The engine always keeps one rotating optimistic upload slot per stream on top. Uploads to peers this device connected to (reciprocal seeding) are not counted; turn off **Seeding** to stop all uploads. Normal leaves the engine defaults. |
| Accept inbound peers | On | [`OUTPACE_ENABLE_INBOUND`](https://github.com/outpace-stream/outpace-engine/wiki/Seeding-and-Peers) |
| Router port mapping | Off | [`OUTPACE_ENABLE_PORT_MAPPING`](https://github.com/outpace-stream/outpace-engine/wiki/NAT-and-Port-Mapping) |
| Peer port | 8621 | [`OUTPACE_PEER_LISTEN`](https://github.com/outpace-stream/outpace-engine/wiki/NAT-and-Port-Mapping) to `0.0.0.0:<port>`, set only when changed. It is also the announced and router-mapped port. It must differ from the HTTP port and from `1935`, the engine's RTMP port. |
| AceStream-compatible API | Off | [`OUTPACE_ACE_COMPAT`](https://github.com/outpace-stream/outpace-engine/wiki/Playing-Streams#acestream-compatible-api) |
| Stream search | Off | [`OUTPACE_COMPAT_ACESTREAM_SERVICES`](https://github.com/outpace-stream/outpace-engine/wiki/Stream-Search). Sends queries and your IP address to AceStream's public index over plain HTTP. |
| Start on boot (Settings tab) | On for TV, off for phones | Starts this device's engine when the device boots. A remote engine left connected reconnects either way. |
| Stop when idle (Settings tab) | On for phones, off for TV | Stops this device's engine after 10 minutes with no streams, to save battery. A remote connection is never stopped for being idle. |

**App updates** has its own group in Settings; see [App updates](#app-updates). The update
notification switch is on by default.

The engine's data and cache live in the app's private storage (`OUTPACE_DATA_DIR` and
`OUTPACE_CACHE_DIR`). Uninstalling the app removes them. The peer listener uses `0.0.0.0:8621`
unless you change **Peer port**; see [Seeding and Peers](https://github.com/outpace-stream/outpace-engine/wiki/Seeding-and-Peers).

## Background running and battery

While the engine runs, the app shows a notification with the stream count and a **Stop**
button. That notification is what keeps Android from closing the engine. On Android 13 and
newer, allow notifications when asked.

Some devices still stop background apps. If streams stop when the screen turns off, allow the app
to ignore battery optimisation (**Settings > Battery optimisation**). On TV, and when start on
boot is on, the app asks once.

The app holds a wake lock only while this device's engine has an active stream; a remote
connection needs none. It checks the engine, local or remote, every
30 seconds when no app screen is open, and every 2 seconds while one is. Peer counts and buffer on the main
screen are fetched only while it is open, for at most 4 streams.

## Logs and problems

**Logs** (in the Engines tab) shows the engine's last 500 lines of output, plus the app's own lines starting with
`[app]`. **Copy** and **Share** help when you report a problem; remove content ids before posting
logs anywhere public.

- **"The engine exited (code N)"**: the app restarts a crashed engine once. If it exits again,
  it stays stopped so it can't loop. Check **Logs**, then start it again.
- **"No app on this device can play the stream"**: install a video player, or use **Copy URL**.
- **The engine shows up as `linker` in process lists**: on Android 10 and newer, apps can't run
  downloaded programs directly, so the app starts the engine through Android's own program
  loader (`/system/bin/linker64`, or `/system/bin/linker` for 32-bit). That is expected.

See also [Troubleshooting](https://github.com/outpace-stream/outpace-engine/wiki/Troubleshooting) and [Installation](https://github.com/outpace-stream/outpace-engine/wiki/Installation#android-with-termux-best-effort)
for running the engine under Termux instead.
