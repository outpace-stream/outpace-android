# Outpace for Android

Companion app for phones and Android TV. It downloads a user-selected
[Outpace engine](https://github.com/outpace-stream/outpace-engine) release, runs it locally,
and opens streams in an external player. It bundles neither an engine nor a player.

**Status:** development snapshot migrated from the engine's Android draft PR. No signed APK
release is published yet; real-device validation and release signing remain required.

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
an app version. Release signing uses `OUTPACE_ANDROID_KEYSTORE`,
`OUTPACE_ANDROID_KEYSTORE_PASSWORD`, `OUTPACE_ANDROID_KEY_ALIAS` and
`OUTPACE_ANDROID_KEY_PASSWORD`; never commit a keystore or secret.
The application ID remains unchanged to preserve the existing app identity.
