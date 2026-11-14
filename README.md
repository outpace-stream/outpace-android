# Outpace for Android

Companion app for phones and Android TV. It downloads a user-selected
[Outpace engine](https://github.com/outpace-stream/outpace-engine) release, runs it locally,
and opens streams in an external player. It bundles neither an engine nor a player.

**Status:** early release. Signed APKs are published on the
[releases page](https://github.com/outpace-stream/outpace-android/releases), starting with
v0.1.0; real-device validation is still in progress.

- [User guide](docs/Android-App.md)
- [Design](docs/android-app-design.md)
- [Implementation issue](https://github.com/outpace-stream/outpace-android/issues/1)
- [App releases](https://github.com/outpace-stream/outpace-android/releases)

## Build

Use JDK 21 and Android SDK 36:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease
```

App releases are versioned independently of engine releases. `-PappVersion=X.Y.Z` selects
an app version. Pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`, which builds,
signs and publishes `outpace-android-X.Y.Z.apk` with its `.sha256`. Release signing uses `OUTPACE_ANDROID_KEYSTORE`,
`OUTPACE_ANDROID_KEYSTORE_PASSWORD`, `OUTPACE_ANDROID_KEY_ALIAS` and
`OUTPACE_ANDROID_KEY_PASSWORD`; never commit a keystore or secret.
The application ID is `io.github.outpacestream.android`.

## Icons

The launcher icon, themed icon, notification icon, in-app logo and TV banner are generated in
[outpace-stream/assets](https://github.com/outpace-stream/assets) from the canonical logo SVG.
Regenerate them there and copy its `android/res` into `app/src/main/res`; do not hand-edit them.
