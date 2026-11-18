# Android App

> Signed APKs are published on the
> [releases page](https://github.com/outpace-stream/outpace-android/releases). 1.0.0 is the first
> stable release; the 0.x builds were previews. App versions are independent of engine versions.

The Outpace Android app runs the engine on a phone or an Android TV box without root and
without Termux, or connects to an Outpace engine running on another device, such as a home
server or a TV box. It downloads the engine release you choose, keeps it running in the
background, and hands streams to the video player you already use, such as VLC, MX Player, Just
Player or Kodi. It has no player of its own. VLC is the recommended player: some TVs' built-in
players stall on live streams that VLC plays without problems. Any player still works.

## Install

1. Download `outpace-android-<version>.apk` from the
   [releases page](https://github.com/outpace-stream/outpace-android/releases) and check it against its
   `.sha256` file.
2. Open it on the device and allow installing from that source when Android asks. On Android TV,
   copy it over with a USB drive or a file-transfer app, or install it from a computer with
   `adb install outpace-android-<version>.apk`.

The app needs Android 5 or newer, on 64-bit ARM (`arm64-v8a`), 32-bit ARM (`armeabi-v7a`) or
x86_64 (emulator images, ChromeOS and Intel or AMD TV boxes). Many Android TV devices run 32-bit
Android even on 64-bit chips; the app picks the right engine build for you. 32-bit ARM engine
builds are published from engine v1.0.2, and x86_64 builds from engine v1.2.1.
There is no 32-bit x86 engine build.

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

The first time you open the app after it updates, a **What's new in X.Y.Z** dialog shows the
release notes of every version since the one you had. It appears once per version, never after a
fresh install, and not at all when the notes can't be loaded (for example offline). **OK**, the
remote's center button or Back closes it.

**Settings > App updates** works whatever you chose in the dialog. It has three rows:

- The status, with one button. Up to date, it shows your version, when the app last checked and
  **Check**. When a newer version exists, it shows **Update available: X.Y.Z**, the version you
  have, and **Update**.
- **What's new in X.Y.Z**: the release notes of the version you have, or, once an update exists,
  of every version newer than yours.
- **Notify me about updates**, the switch that **Never ask again** turns off.

Only signed release builds update this way. A build you compiled yourself is signed with a
different key, so Android refuses the update, and installing the release APK by hand fails the same
way. To switch to the official release, uninstall the app first, then install the APK from the
releases page. Uninstalling deletes the app's data, including the downloaded engine and your
settings. Development builds (version `0.0.0`) never show the dialog
and can't update from Settings.

## First start

The APK contains no engine. On first start Home shows **Local engine not set up** and a
**Configure** button that opens **Settings**. There you have two choices:

- Under **Local engine**, **Manage versions** opens [Engine versions](#engine-versions). Choose
  **Install** on the release you want; nothing downloads until you do. The app downloads the
  archive, checks it against the release's `.sha256`, installs it and selects it. After that,
  **Start engine** runs it.
- Under **Engine connection**, **Add a remote engine** uses an Outpace engine on another device
  instead; see [Remote engines](#remote-engines).

The engine listens on `127.0.0.1:6878`, the same address as on any other platform, so players on
the same device reach it with the usual [URLs](https://github.com/outpace-stream/outpace-engine/wiki/Playing-Streams).

## Finding your way around

The main screen has three tabs: **Home**, **Search** and **Settings**. On a phone held
upright they sit along the bottom; on TV, tablets and landscape phones they form a rail on the
left. Back from any other tab returns to Home, and Back from Home offers to exit; see
[Background running and battery](#background-running-and-battery).

Home shows which engine the app uses and whether it is running or connected, with a coloured dot,
the engine's name and version, and the address players use. One button sits beside the status:

| Selected engine | Button |
| --- | --- |
| This device, no version chosen yet | **Configure** opens Settings. |
| This device | **Start engine** or **Stop engine**. |
| A remote engine | None: the app connects on its own. |

Below it, Home lists what is playing now and what you played recently. Choosing the engine,
versions, logs and engine settings live in [Settings](#engine-connection).

**Settings > About** shows where app and engine releases come from; the app version is under
**App updates**.
**Open-source licenses** lists the app, the engine it downloads, and the components the app
includes, with each one's version where known, copyright, license and source; select one to read its full license.
The app is under the GNU AGPL v3.0, the Kotlin standard library and Material Design icons under
the Apache License 2.0.

## Engine connection

**Settings > Engine connection** lists the engines the app can use. **This device** always comes
first and is the default: it is the engine downloaded to this device, set up under
[Local engine](#local-engine) below. Each remote engine you added follows, with its name and
address. One of them is **Selected**; Home, Search and playback all use it. Choose **This
device** to use it, or choose a remote engine to use, edit or remove it. Editing the connected
remote engine reconnects to it at once; playback continues. Removing the selected remote engine
switches back to this device.

Switching is free in both directions. Selecting a remote engine stops this device's engine if it
runs, and selecting this device disconnects from a remote one first. Switching back to this
device runs the version it used before, when that version is still installed.

## Local engine

**Settings > Local engine** manages this device's engine. Its controls work only while **This
device** is selected under Engine connection; with a remote engine selected the whole section is
dimmed and disabled. It shows the engine's state and version, then one action per row:

- **Manage versions** opens [Engine versions](#engine-versions) to install, switch or remove a
  version.
- **Start engine** or **Stop engine**.
- **Reinstall** downloads the selected version again over the installed copy, with progress and
  **Cancel** on the Engine versions screen. A failed or cancelled reinstall leaves the installed
  copy untouched. Reinstalling the running version restarts the engine on the new copy.
- **View logs** opens [Logs](#logs-and-problems).

A **vX.Y.Z available** button appears when a newer release exists. The engine
[settings](#engine-settings) follow. When you change settings while the engine runs, a banner
offers **Restart** to apply them.

### Remote engines

A remote engine is an Outpace engine running on another device of your network. On that device,
make its HTTP API reachable from the network, for example `OUTPACE_BIND=0.0.0.0:6878` (in the
Android app: **Allow LAN access**). Then choose **Settings > Engine connection > Add a remote
engine** and enter a
name and its address, such as `192.168.1.20:6878`. The port defaults to `6878`; a pasted
`http://` prefix is accepted. For an engine served through an HTTPS reverse proxy, enter its
address with `https://`, such as `https://engine.example.com`; requests and players then use HTTPS,
and the port defaults to `443`. Up to 16 remote engines can be added.

While Settings is open, each remote engine shows whether it answers: a green dot for online, a
red one for not answering. The app checks each one every 15 seconds, and only while Settings is
open.

There is no connect step. While the app is open it stays connected to the selected remote engine:
it checks the engine and lists its streams, the same way it does for its own engine, and selecting
another engine switches over at once. If the engine stops answering, Home shows **Not answering**
and the app keeps retrying until it answers again.

Leaving the app with the Home button keeps the connection, with a notification showing the
stream count. Exiting the app ends it; see
[Background running and battery](#background-running-and-battery). Opening the app connects
again. The app does not connect to a remote engine when the device starts.

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
- **Another app.** Apps and add-ons that open streams through a local AceStream-compatible engine
  (`http://127.0.0.1:6878/ace/getstream?id=<id>`, or `localhost`) can open them in Outpace: pick
  Outpace when Android asks which app to use. Outpace reads the content id (`id=` or
  `content_id=`), starts its engine if needed and hands the stream to your player as usual, so
  this works with the AceStream-compatible API turned off. The port in the link does not matter.

  On Android 12 and later, Android treats a `127.0.0.1` link as a web link. It offers that link
  only to apps approved for the address, and Outpace cannot be verified for it. Unless Outpace is
  the only app that can open the link, Android skips Outpace and opens it in your browser. If
  the link has a `video/*` type, Android may open it in a player that accepts any web address.
  The type does not exempt the link. The same links with `localhost` are not affected, because
  Android does not treat `localhost` as a web domain. A link that names Outpace's package or
  activity directly is also unaffected. Outpace does not yet have a confirmed setting that
  approves it for `127.0.0.1`.
- **The share menu.** Share text that contains a content id or an `acestream:` link to Outpace.
- **Home.** Paste a content id or link into the box and press the play button beside it. On
  phones, the clipboard button fills the box from the clipboard.
- **Search.** Find a stream by name and press its **Play** button; see [Search](#search).
- **Recently played.** Home lists the last 12 streams you played, with their names when known.
  This is the easy way to replay on a TV remote. **Clear** forgets them. Long press a row's
  **Play** button for more: **Copy link** copies an `acestream://` link that opens the stream in
  another app or in Outpace on another device, **Copy id** copies the bare 40-hex id, and
  **Remove from recent** forgets just that one. Streams played from search have no link, only
  their infohash to copy.
- **Now playing.** While the engine runs, Home lists what it is playing, with the title, the
  number of clients, peers and seconds buffered, and "through the proxy" once new peer
  connections go through the [proxy](#proxy). **Play** opens it again. Few or no
  peers usually means the stream is hard to find; see
  [Troubleshooting](https://github.com/outpace-stream/outpace-engine/wiki/Troubleshooting).

The app starts the engine (or connects to the selected remote engine) if needed, waits until it
answers, then opens Android's "Play with" chooser for `http://127.0.0.1:6878/streams/ace/cid:<id>` (or `ih:<infohash>` for a
search result). Tick "Always" to go
straight to your favourite player next time. Outpace never lists itself in that chooser. The same screen also has:

- **Open in VLC (recommended)**, shown first and focused when VLC is installed. It sends the same
  URL straight to VLC. **Open in player** still lists every player, VLC included. On Android 6
  and older, VLC is also listed first in the chooser.
- When VLC is not installed, a short hint suggests it, with **Get VLC** when an app store is
  available. It never blocks playback; **Don't show again** hides it for good.
- **Open as HLS**, for players that handle `.m3u8` better than a raw MPEG-TS stream.
- **Copy URL** and **Share URL**, for players that don't appear in the chooser or for another
  device. To play on another device, turn on **Allow LAN access** first; the copied URL then
  uses this device's LAN address instead of `127.0.0.1`.

Links, shared text and the paste box take content ids only. Infohashes and magnet links pasted or
shared there, and `getstream` links with `infohash=`, are rejected; to play by infohash, use Search. A bare 40-character hex string is
treated as a content id, as the engine does.

After the hand-off, the app follows the start for up to 90 seconds and shows how far it got, on
the play screen and in the engine notification: "Looking up the stream", "Finding peers (3)",
then "Buffering". It stops once the stream plays. If the start fails, it shows why, with a
suggested action where there is one:

| Engine error | Message |
| --- | --- |
| `no_peers` | No one is sharing this stream right now. Try again later. |
| `network` | Can't reach the peer network. Check your connection or try another network. |
| `not_found`, `unresolvable` | This id isn't a stream Outpace can open. |
| `protected` | This stream is protected, so Outpace can't play it. |
| `capacity` | The engine is already playing the maximum number of streams. |
| `no_space` | Not enough free storage for this download. |

A failure stays in the notification for a minute. Start phases and error codes need an engine
that reports them on `/status` (v1.2.0 or newer); with an older engine, the app shows the peer
count and, when it stays at zero for 20 seconds, "No peers yet. This stream may be offline." The
same works with a remote engine.

The app plays live streams. VOD content ids are not handled yet.

## Play on TV (DLNA)

Many smart TVs, AV receivers and set-top boxes are DLNA/UPnP media renderers: they can play a
stream without any app installed. With engine v1.2.0 or newer, the engine can find them and tell
one to play a live stream. The engine casts; the app is only the remote control. See the engine's
[Casting](https://github.com/outpace-stream/outpace-engine/wiki/Casting) page for how it works.

**Turn it on.** For this device's engine, turn on **Cast to TVs (DLNA)** in Settings > Local engine
settings > Network. The setting appears only when the selected version is v1.2.0 or newer and
sets `OUTPACE_ENABLE_CAST=true`. The TV fetches the stream from this device, so casting also
needs **Allow LAN access**: when it is off, the app explains why and offers to turn it on, with
the usual warning to use it only on a trusted network. It never turns LAN access on by itself.
Restart the engine to apply both. For a remote engine, its operator sets `OUTPACE_ENABLE_CAST`
and a LAN `OUTPACE_BIND` on that device.

**Cast.** Open a stream as usual and choose **Play on TV** on the play screen, or long press a
**Now playing** row's **Play** button on Home and choose **Play on TV**. The app lists the TVs
the engine finds (a search takes about 2 seconds, and the engine reuses its result for a
minute); **Search again** looks again. Pick a TV: the engine starts the stream and the TV opens
it, which can take several seconds. One TV at a time: picking another TV stops the first.

**While casting.** Home and the engine notification show "Playing on *TV*" with **Stop
casting**. Stopping the engine, or disconnecting from a remote engine, stops the TV too. The
app reports what the TV does:

| Situation | Message |
| --- | --- |
| The TV stopped playback | *TV* stopped playback. |
| The TV still says it plays but no longer reads the stream | *TV* stopped fetching the stream. Pick the TV again to restart it. |
| The TV does not answer the engine | *TV* is not answering. (After three checks in a row the cast ends.) |
| The engine no longer has the cast (it restarted) | The engine is no longer casting to *TV*. |
| **Stop casting** got no answer from the engine | Could not stop casting to *TV*: the engine did not answer. Try again. (The cast stays, so you can.) |

Requests the engine refuses get their own message: casting off on the engine (`404`; for a
remote engine, the message names it), the engine only reachable from its own device
(`loopback_bind`), a TV that left the list (`unknown_target`), no peers, a protected or HLS
stream, too many streams or TVs at once (`capacity_exhausted`), and a TV that refused the
stream (with its UPnP error code), did not answer, or timed out.

The list is empty when the TV is off, on another network or VLAN, on guest Wi-Fi, or has its
DLNA or screen sharing setting off. Casting covers live streams only, and renderer addresses
are IPv4 only, like the engine.

## Search

**Search** looks up streams by name through the engine's
[stream search](https://github.com/outpace-stream/outpace-engine/wiki/Stream-Search), which needs
engine v1.0.0 or newer. It is off by default: your query and this device's IP address go to
AceStream's public index over plain HTTP, where the index and anyone on the network path can see
them. Turn it on with **Turn on search** on the Search tab or **Stream search** under Settings > Local engine; a
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
| not available for this device | That release has no Android build for this device. Releases before v1.0.0 have none, 32-bit ARM builds (`armv7-linux-androideabi`) start with v1.0.2, x86_64 builds (`x86_64-linux-android`) with v1.2.1, and there is no 32-bit x86 build. |

Each row has its own buttons: **Install** downloads that release, **Use** switches to an
installed one, and **Remove** deletes it. Nothing downloads on its own; you choose the release.
While a download runs, it shows its progress with **Cancel** beside it, and the other buttons wait.
A download becomes the selected version only when this device has none selected yet. Switching
while the engine runs restarts it on the new version. Up to 3 versions can be installed at once. The running
version, or one that is starting, can't be removed.

When a newer release exists, Settings > Local engine shows a **vX.Y.Z available** button. The app checks GitHub when the main screen opens, at most once a day, and never upgrades
the engine by itself.

GitHub limits how often its API can be called without an account, per IP address. Behind a VPN
or a shared mobile network that limit can run out. The full list then shows only the latest
and installed versions, with a short note. Downloads and the latest-version check don't use
that API and keep working.

## Engine settings

Engine settings sit in **Settings > Local engine**, below the engine's controls, and can be
changed only while **This device** is selected. They save as soon as you change them and apply
the next time the engine starts. While the engine runs with older settings, a banner offers **Restart** to apply
them right away. Playback presets set an engine variable only when they differ from the engine
default. **Start on boot** and **Stop when idle** are further down Settings, under **Background**.

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
| SOCKS5 proxy | Off | [`OUTPACE_PROXY_MODE`](https://github.com/outpace-stream/outpace-engine/wiki/Outbound-Proxy): Always sets `on`, Only when no peers sets `fallback`, Off sets nothing. Engine 1.2.0 and later; see [Proxy](#proxy). |
| Proxy server | Not set | [`OUTPACE_PROXY`](https://github.com/outpace-stream/outpace-engine/wiki/Outbound-Proxy) to `socks5://[user:password@]host:port`, set only while the proxy is not Off. |
| Accept inbound peers | On | [`OUTPACE_ENABLE_INBOUND`](https://github.com/outpace-stream/outpace-engine/wiki/Seeding-and-Peers). Sent as `false` while the proxy is Always. |
| Router port mapping | Off | [`OUTPACE_ENABLE_PORT_MAPPING`](https://github.com/outpace-stream/outpace-engine/wiki/NAT-and-Port-Mapping). Sent as `false` while the proxy is Always. |
| Peer port | 8621 | [`OUTPACE_PEER_LISTEN`](https://github.com/outpace-stream/outpace-engine/wiki/NAT-and-Port-Mapping) to `0.0.0.0:<port>`, set only when changed. It is also the announced and router-mapped port. It must differ from the HTTP port and from `1935`, the engine's RTMP port. |
| AceStream-compatible API | Off | [`OUTPACE_ACE_COMPAT`](https://github.com/outpace-stream/outpace-engine/wiki/Playing-Streams#acestream-compatible-api) |
| Cast to TVs (DLNA) | Off | [`OUTPACE_ENABLE_CAST`](https://github.com/outpace-stream/outpace-engine/wiki/Casting)`=true`, set only when on. Shown for engine v1.2.0 or newer. Needs **Allow LAN access**; see [Play on TV](#play-on-tv-dlna). |
| Stream search | Off | [`OUTPACE_COMPAT_ACESTREAM_SERVICES`](https://github.com/outpace-stream/outpace-engine/wiki/Stream-Search). Sends queries and your IP address to AceStream's public index over plain HTTP. |
| AceStream identity | Off | [`OUTPACE_ACESTREAM_IDENTITY`](https://github.com/outpace-stream/outpace-engine/wiki/Engine-Camouflage)`=true`, set only when on. Shown only for engine v1.1.0 or newer. Peers, trackers, the catalog and (with **Stream search** on) search see the official AceStream Linux engine profile instead of Outpace. The first start with it on stores a device id (`acestream-device-id`) in the app's private data, which uninstalling removes. The engine makes one HTTPS lookup to RIPEstat (`stat.ripe.net`) for this device's network and country after two peers report the same public address. If the engine cannot derive its hardware key, it stops at startup and the error shows in Logs. |
| Start on boot (Settings tab) | On for TV, off for phones | Starts this device's engine when the device boots. A remote engine connects when the app opens instead. |
| Stop when idle (Settings tab) | On for phones, off for TV | Stops this device's engine after 10 minutes with no streams, to save battery. A remote connection is never stopped for being idle. |

**App updates** has its own group in Settings; see [App updates](#app-updates). The update
notification switch is on by default.

The engine's data and cache live in the app's private storage (`OUTPACE_DATA_DIR` and
`OUTPACE_CACHE_DIR`). Uninstalling the app removes them. The peer listener uses `0.0.0.0:8621`
unless you change **Peer port**; see [Seeding and Peers](https://github.com/outpace-stream/outpace-engine/wiki/Seeding-and-Peers).

### Proxy

Some mobile carriers and campus, hotel or workplace networks block peer-to-peer traffic, so a
stream finds no peers there even though it plays elsewhere. The **Proxy** group sends this
device's engine's peer, tracker and catalog connections through a SOCKS5 proxy instead of the
whole device through a VPN. It appears only while engine 1.2.0 or newer is selected, and only
for this device's engine: a remote engine is configured on the device that runs it. See
[Outbound Proxy](https://github.com/outpace-stream/outpace-engine/wiki/Outbound-Proxy) for what
goes through the proxy.

- **SOCKS5 proxy**: **Off** (the default), **Always**, or **Only when no peers**, which connects
  directly first and switches to the proxy, until the engine restarts, the first time a stream
  start finds no peers. Choosing Always or Only when no peers before a server is set asks for one.
- **Proxy server**: the address as `host:port` (`[ipv6]:port` for IPv6; a leading `socks5://`
  is fine) and an optional username and password, both or neither. The app percent-encodes them
  in `OUTPACE_PROXY`. Leave the address empty and save to remove the proxy, which also turns it
  off. When a username and password are set and the proxy is not on this device (`localhost` or
  `127.x.x.x`), a note warns that the proxy receives the password unencrypted: use a proxy on
  this device or on a network you trust.
- Nobody can connect to this device through a proxy, so with **Always** the engine refuses inbound
  peers and router port mapping. Those two settings then show as off and dimmed, with the reason,
  and come back as you left them when the proxy is no longer Always.

The password is stored only in the app's private preferences, which are excluded from backups.
The app never writes it to **Logs**, and the engine leaves it out of its own log lines. Changes
apply on the next engine start, through the usual **Restart** banner.

## Languages

The app is available in English, Spanish, Portuguese, French, Italian, German and Russian. It
follows the device language and falls back to English for any other. On Android 13 and newer you
can pick a language for this app alone in **Android Settings > Apps > Outpace > Language**, without
changing the device language; some TV devices do not show that screen. Messages that come from the
engine itself, such as log lines and error details in parentheses, stay in English.

The translations were machine-written and may read stiffly in places. Corrections from native
speakers are welcome as issues or pull requests against `app/src/main/res/values-xx/strings.xml`.

## Background running and battery

While the engine runs or a remote engine is connected, the app shows a notification with the
stream count and an **Exit** button. That notification is what keeps Android from closing the
engine. On Android 13 and newer, allow notifications when asked.

Leaving the app with the Home button or the app switcher keeps everything running in the
background. Exiting stops it all:

- **Exit** in the notification closes the app at once: it stops this device's engine or ends the
  remote connection, and the notification goes away.
- **Back** from Home asks first while something runs. **Exit** closes the app, **Keep in
  background** leaves it running, and **Cancel** stays. When this device's engine is in use, the
  question warns that players and other devices using it will stop working. With nothing
  running, Back closes the app without asking.

Some phones and tablets still stop background apps. If streams stop when the screen turns off,
allow the app to ignore battery optimisation (**Settings > Battery optimisation**). When start on
boot is on, the app asks once. The row is hidden once the app is exempt, on TV and other devices
without a battery (Android only applies these limits on battery power), and when the device has
no battery optimisation screen.

The app holds a wake lock only while this device's engine has an active stream; a remote
connection needs none. It checks the engine, local or remote, every
30 seconds when no app screen is open, and every 2 seconds while one is. Peer counts and buffer on the main
screen are fetched only while it is open, for at most 4 streams. After you hand a stream to a
player, the app also checks that one stream's start once a second, and stops when it plays,
fails, or after 90 seconds at most. While a TV plays a stream from Play on TV, the app checks
that cast every 30 seconds, or every 3 seconds while the Play on TV screen is open; nothing
searches for TVs unless you open that screen or press **Search again**.

## Logs and problems

**View logs** (in Settings > Local engine) shows the engine's last 500 lines of output, plus the app's own lines starting with
`[app]`. New lines appear while the view is at the bottom; scroll up, or select text, and it holds
still until you return to the bottom. **Copy** and **Share** help when you report a problem;
remove content ids before posting logs anywhere public.

- **"The engine exited (code N)"**: the app restarts a crashed engine once. If it exits again,
  it stays stopped so it can't loop. Check **Logs**, then start it again.
- **"No app on this device can play the stream"**: install a video player, or use **Copy URL**.
- **A black screen, then back to Outpace, with a TV's built-in player**: some stock TV players
  stall on live MPEG-TS streams, even while the engine delivers data. Use VLC, or try
  **Open as HLS**.
- **The engine shows up as `linker` in process lists**: on Android 10 and newer, apps can't run
  downloaded programs directly, so the app starts the engine through Android's own program
  loader (`/system/bin/linker64`, or `/system/bin/linker` for 32-bit). That is expected.

See also [Troubleshooting](https://github.com/outpace-stream/outpace-engine/wiki/Troubleshooting) and [Installation](https://github.com/outpace-stream/outpace-engine/wiki/Installation#android-with-termux-best-effort)
for running the engine under Termux instead.
