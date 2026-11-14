# Android App

> **Unreleased:** this is development documentation. APKs will be published in this repository
> after signing and device validation; app versions are independent of engine versions.

The Outpace Android app runs the engine on a phone or an Android TV box without root and
without Termux. It downloads the engine release you choose, keeps it running in the background,
and hands streams to the video player you already use, such as VLC, MX Player, Just Player or
Kodi. It has no player of its own.

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
that download programs to run.

## First start

The APK contains no engine. On first start the main screen shows **Download engine**. It opens
**Engine versions** and offers the latest release built for your device. The app downloads the
archive, checks it against the release's `.sha256`, and installs it. After that, **Start engine**
runs it.

The engine listens on `127.0.0.1:6878`, the same address as on any other platform, so players on
the same device reach it with the usual [URLs](https://github.com/outpace-stream/outpace-engine/wiki/Playing-Streams).

## Finding your way around

The main screen has three tabs: **Home**, **Search** and **Settings**. On a phone held upright
they sit along the bottom; on TV, tablets and landscape phones they form a rail on the left.
Back from Search or Settings returns to Home. **Versions** (Engine versions) and **Logs** sit next
to the start button on Home.

Home shows the engine state with a coloured dot, the start/stop button, the address players use,
what is playing now and what you played recently. When you change settings while the engine runs,
a banner offers **Restart** to apply them.

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

The app starts the engine if it isn't running, waits until it answers, then opens Android's
"Play with" chooser for `http://127.0.0.1:6878/streams/ace/cid:<id>` (or `ih:<infohash>` for a
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
them. Turn it on with **Turn on search** on the Search tab or **Stream search** in Settings; a
running engine restarts to pick it up.

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

Choose a row to download it, switch to it or remove it. Switching while the engine runs restarts
it on the new version. Up to 3 versions can be installed at once. The running version can't be
removed.

The main screen shows the engine version and, when a newer release exists, a **vX.Y.Z available**
button. The app checks GitHub when the main screen opens, at most once a day, and never upgrades
the engine by itself.

GitHub limits how often its API can be called without an account, per IP address. Behind a VPN
or a shared mobile network that limit can run out. The full list then shows only the latest
and installed versions, with a short note. Downloads and the latest-version check don't use
that API and keep working.

## Settings

Settings save as soon as you change them and apply the next time the engine starts. While the
engine runs with older settings, a banner offers **Restart** to apply them right away.
Playback presets set an engine variable only when they differ from the engine default.

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
| Start on boot | On for TV, off for phones | Starts the engine when the device boots. |
| Stop when idle | On for phones, off for TV | Stops the engine after 10 minutes with no streams, to save battery. |

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

The app holds a wake lock only while a stream is active. It checks the engine every 30 seconds
when no app screen is open, and every 2 seconds while one is. Peer counts and buffer on the main
screen are fetched only while it is open, for at most 4 streams.

## Logs and problems

**Logs** shows the engine's last 500 lines of output, plus the app's own lines starting with
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
