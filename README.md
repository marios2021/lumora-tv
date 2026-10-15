# Lumora TV

An IPTV player for Android TV, in the spirit of TiviMate. It plays M3U playlists and Xtream Codes accounts that you provide, with an XMLTV TV guide. It ships no channels of its own.

Design and milestones: [ARCHITECTURE.md](ARCHITECTURE.md).

## Features

- Playlists from an M3U URL, a local M3U file, or an Xtream Codes login. Channels by group, favorites, channel numbers.
- Full-screen live TV: channel up/down, number entry, previous channel, info overlay with now/next.
- Live rewind up to 30 seconds, pause and catch up again.
- XMLTV TV guide grid, and search across channels, programmes, movies and series.
- Catch-up: watch ended programmes from the provider's archive (Xtream and M3U `catchup`).
- Recording: record what's on, or schedule programmes from the guide; recordings play back in the app.
- Movies and series for Xtream accounts, with resume and Continue watching.
- Parental PIN to hide chosen groups.
- Made for the TV remote, and also works with touch on phones and tablets.

## Build

Needs JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`, or open the project in Android Studio).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/LumoraTV-<version>-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/LumoraTV-<version>-release.apk
./gradlew testDebugUnitTest    # unit tests
```

Install on a TV with developer options and ADB debugging turned on:

```bash
adb connect <tv-ip-address>
adb install -r app/build/outputs/apk/release/LumoraTV-0.1.0-release.apk
```

## Release signing

Release builds are signed with the key described in `keystore.properties` at the project root. That file and the `.jks` key are git-ignored. Without them, release builds fall back to the debug key and Gradle prints a warning.

1. Create the key (you'll be asked to choose a password):

   ```bash
   keytool -genkeypair -v -keystore lumora-release.jks -alias lumora -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Lumora TV"
   ```

2. Copy `keystore.properties.example` to `keystore.properties` and fill in the passwords.

**Back up `lumora-release.jks` and its password somewhere safe, outside this folder.** Android only installs an update over an existing copy if both are signed with the same key. Losing the key means users must uninstall (and lose their playlists and settings) before installing a newer version.

## Versioning

`versionCode` and `versionName` are in `app/build.gradle.kts`. Increase `versionCode` for every APK you share: Android won't install an update with a lower version code than the copy already installed.
