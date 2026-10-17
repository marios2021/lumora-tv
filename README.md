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
- Updates itself from an address you publish new versions to (no cable or sideloading).
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
adb install -r app/build/outputs/apk/release/LumoraTV-<version>-release.apk
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

The version comes from the git history, so it goes up with every commit: `versionCode` is the number of commits on the current branch, and `versionName` is `0.1.<that number>` (for example `LumoraTV-0.1.19-release.apk`). Android only installs an update with a higher version code than the copy already installed, so build what you share from a commit on `main`. A build with uncommitted changes keeps the last commit's number.

For a bigger release, raise `VERSION_PREFIX` in `app/build.gradle.kts` (say to `0.2`). Without git (for example from a source zip), the build uses version code 1 and prints a warning.

## Updating without a cable

Lumora TV can update itself from **Settings → Updates**: publish each new version once, and every tablet and TV that has the update address installs it with a couple of presses. Playlists and settings are kept.

### Publish a version (two steps)

1. **Commit, then build the release:** `./gradlew assembleRelease`. Next to the APK, this writes `app/build/outputs/apk/release/update.json`:

   ```json
   {
     "versionCode": 20,
     "versionName": "0.1.20",
     "url": "LumoraTV-0.1.20-release.apk",
     "notes": "Subject of the last commit"
   }
   ```

   Add `-PupdateNotes="What's new"` to write your own notes (they show in Settings), or `-PupdateApkUrl=https://…` if the APK is hosted somewhere other than next to `update.json`. The build warns when the APK is signed with the debug key or the working tree has uncommitted changes (the version code wouldn't go up).

2. **Upload `update.json` and the APK side by side**, for example as a GitHub release:

   ```bash
   gh release create v0.1.20 app/build/outputs/apk/release/LumoraTV-0.1.20-release.apk app/build/outputs/apk/release/update.json --title 0.1.20 --notes "What's new"
   ```

   The update address is then `https://github.com/<you>/<repo>/releases/latest/download/update.json`. It always points at the newest release, so it never needs changing on the devices.

Where the files can live:

- **GitHub release, public repository:** the address above, nothing else needed.
- **GitHub release, private repository:** the same address, plus an access token in the Update address dialog. Create a fine-grained token (GitHub → Settings → Developer settings → Fine-grained tokens) with access to that one repository and *Repository permissions → Contents: Read-only*. With a token set, the app reads the release through the GitHub API (`api.github.com`), which is the only way to download a private repository's files. The token is stored in the app's settings on each device and is only sent to `api.github.com`.
- **Any web server or file host with direct download links** (no sign-in and no "download" page in between): put both files in the same folder and use the address of `update.json`. A local test works too, for example `python -m http.server 8000` in the release folder and `http://<pc-ip>:8000/update.json`. Google Drive share links don't work, because they open a web page instead of the file.

### On each device (once)

1. Settings → Updates → **Update address**, and enter the address (a touch keyboard is easiest; on a TV, OK opens the on-screen keyboard).
2. **Check for updates**, then **Install** on "Update available: 0.1.x".
3. The first time, Android has to allow Lumora TV to install apps. The app explains this and opens the right screen: turn on **Allow from this source**, press Back, and the update continues.
4. Android may ask you to confirm the update (Android 8 always does; Android 12 and later usually installs it without asking). It then replaces the app, and Lumora TV opens again as the new version.

With an address set, the app also checks by itself at most once a day while it's open, and shows a message when a new version is out (turn it off with **Check automatically**).

### What gets installed

The app only installs an APK that is Lumora TV (`com.lumora.tv`), has a higher version code than the installed copy, and is signed with the same key. So publish release builds signed with your key (`keystore.properties`, see Release signing): a debug build, or a release built without `keystore.properties`, is refused. A device that has a debug build installed needs one last install by cable or sideloading (uninstall first, which loses its playlists) before it can update itself. A recording in progress stops when the app restarts for an update.
