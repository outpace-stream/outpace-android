# Repository guidelines

Outpace for Android is a thin engine manager for phones and Android TV: it downloads an
unmodified [outpace-engine](https://github.com/outpace-stream/outpace-engine) release, runs
`outpace serve` as a child process from a foreground service, and hands streams to an external
player. Read `docs/android-app-design.md` before changing engine launch, the service, settings
mapping or engine downloads; its goals, non-goals and anti-goals are the scope contract.

## Layout

The Gradle project is at the repository root, with one module, `app/`
(`io.github.outpacestream.android`):

- `core/`: pure Kotlin logic with no Android framework calls (launch command, ABI mapping,
  release and SHA-256 parsing, tar.gz extraction, settings to `OUTPACE_*` mapping, presets,
  content-id parsing, bounded log ring). This is where unit tests live; put new decision logic
  here so it stays testable on the JVM.
- `net/`: HTTP to GitHub and the local engine. Give each new request a timeout; engine status
  requests use `Http.getWithin` for a hard total deadline.
- `engine/`: the foreground service and process supervision.
- `ui/`: Views-based screens and activities. Every screen must be fully usable with a D-pad.

## Dependencies

The app uses only the platform SDK: no AndroidX (`android.useAndroidX=false`), no Compose, no
third-party runtime libraries. Test dependencies are JUnit and `org.json` (android.jar's copy is a
stub on the JVM). Adding any runtime dependency is a maintainer decision; state its APK-size cost
in the PR. The APK should stay well under 5 MB.

## Engine boundary

- Engine binaries, HTTP API changes and archive layout belong in outpace-engine. If the app needs
  something the engine lacks, file an engine issue; the app never patches or bundles the engine.
- The app depends on release asset names `outpace-<ver>-<target>.tar.gz` plus `.sha256`, with
  `outpace-<ver>-<target>/outpace` inside. Targets: `aarch64-linux-android`,
  `armv7-linux-androideabi`, optional `x86_64-linux-android`.
- Engine configuration is environment variables at process start only. Emit only non-default
  values; a change applies on the next engine start.
- Resolve the latest release through the `github.com/<repo>/releases/latest` redirect and
  predictable asset URLs. The unauthenticated GitHub API (60 requests per hour per IP) is for the
  full version list only.

## Launch rules

These are verified on emulators and easy to break:

- On API 29 and later, run the engine through `/system/bin/linker64` (64-bit ELF) or
  `/system/bin/linker` (32-bit ELF), picked from the binary's ELF class. On API 28 and below, exec
  it directly.
- Choose the path from the API level, never by trying: on API 28 the loader prints help and exits
  0, so a failed launch looks like success.
- Offer engines that match `Build.SUPPORTED_ABIS`. 64-bit-only devices have no
  `/system/bin/linker`.
- Below API 24, stop the engine with `android.os.Process.sendSignal` (SIGTERM) and a timed wait,
  since `destroy()` may be SIGKILL there.
- Treat an `IOException` on the engine output reader as end of stream.

## Efficiency

Battery drain is a failure even when features work. Poll engine status only while a screen is
visible; with no screen, the service makes one slow `GET /streams` poll (about 30 s) to drive the
notification, wake lock and idle auto-stop; nothing polls while the engine is stopped. Keep
polling, log buffers, version storage and restart attempts bounded. The engine HTTP API stays on
`127.0.0.1` unless the user explicitly enables LAN access.

## Build and test

Use JDK 21 and Android SDK 36. If Gradle picks up a broken `JAVA_HOME` from the shell, set
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`. Before a PR, run what CI runs:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease
curl -fsSL https://raw.githubusercontent.com/outpace-stream/actions/v1/identifier-hygiene/check_identifiers.py | python3 -
```

Lint aborts on any error. Local release builds are unsigned; signing exists only in
`.github/workflows/release.yml`.

Emulator testing: x86 images cannot run ARM engines. Build debug with
`-PengineTargets=aarch64-linux-android` to force the target, download an engine through the app,
then replace it with a locally NDK-built x86 or x86_64 engine via `adb shell run-as`.

## Identifier hygiene

Content ids, infohashes and stream names never appear in code, tests, docs, commits, issues or
PRs. Where valid hex is required, use `0123456789abcdef0123456789abcdef01234567`. Live checks
resolve a current id at runtime and record nothing. CI enforces this with
`outpace-stream/actions/identifier-hygiene@v1`.

Never commit keystores, signing secrets, private captures or local SDK state
(`local.properties`).

## Branches, commits and PRs

Base work on `main` with exactly one of the prefixes `feat/`, `fix/`, `docs/`, `test/` or
`chore/`, using conventional commit subjects (`fix: bound stream status requests`). Keep the root
checkout on `main` and work in a separate worktree. A user-visible change updates
`docs/Android-App.md` (and the design doc if behavior changed) in the same PR. Write docs and PR
text with colons or commas in place of em dashes.

## Releases

App versions are independent of engine versions. Pushing a `vX.Y.Z` tag runs `release.yml`,
which builds with `-PappVersion=X.Y.Z` (version code `major*1_000_000 + minor*1_000 + patch`),
signs, and publishes `outpace-android-X.Y.Z.apk` with its `.sha256`. Tagging is a maintainer
action.

## Icons

Launcher, themed, notification, logo and TV banner drawables are generated in
[outpace-stream/assets](https://github.com/outpace-stream/assets). Regenerate them there and copy
`android/res` into `app/src/main/res`.
