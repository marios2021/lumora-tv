# Lumora TV — Architecture

An IPTV player for Android TV. It plays playlists the user supplies (M3U or Xtream Codes) and ships no content.

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Compose for TV (`androidx.tv:tv-material`) |
| Playback | Media3 / ExoPlayer (HLS, DASH, MPEG-TS over HTTP) |
| Storage | Room (playlists, channels, favorites, EPG) + DataStore (settings) |
| Networking | OkHttp |
| Images | Coil (channel logos) |
| DI | Manual: one `AppContainer` created in `LumoraApp` |
| Min / target SDK | 23 / 35 |

Single Gradle module (`:app`) for now. It can be split into `:core` / `:feature-*` modules later if it grows.

## Layers

```
ui/          Compose screens + ViewModels (one ViewModel per screen)
  theme/       colors, typography
  home/        groups column + channel list
  playlists/   list, add (M3U URL / file / Xtream)
  player/      full-screen ExoPlayer, overlay, zapping
  guide/       EPG grid                          (milestone 3)
  settings/                                       (milestone 4)
data/
  db/          Room entities + DAOs
  parser/      M3uParser, XmltvParser (streaming, low memory)
  remote/      XtreamClient (player_api.php)
  repo/        PlaylistRepository, EpgRepository, SettingsRepository
  update/      self-update: update file, GitHub releases, download + PackageInstaller
AppContainer   wires repositories, DB, OkHttp
```

Data flows one way: **Repository → Flow → ViewModel StateFlow → Compose**. User actions go back as ViewModel calls.

## Data model

- **playlists**: id, name, type (`M3U_URL`, `M3U_FILE`, `XTREAM`), url, username, password, epgUrl, updatedAt
- **channels**: id, playlistId, key (stable id: stream URL or Xtream stream id), name, groupTitle, logo, streamUrl, tvgId, sortOrder, catch-up info (days, type, source template), and `guideOverride` (a copy of the channel's row in guide_overrides, if any)
- **favorites**: playlistId + channelKey. Kept in a separate table so favorites survive a playlist refresh.
- **locked_groups**: playlistId + groupTitle, for the parental lock (milestone 7). Also kept by name, so locks survive a refresh.
- **vod**: movies and series of Xtream playlists (kind, provider id, name, category, poster, rating, year, file type). Details, seasons and episodes are fetched when a title is opened.
- **recordings**: channel, title, stream URL, recording window, status (scheduled, recording, done, failed), file and size. Not tied to a playlist, so recordings outlive it.
- **playback_progress**: playlistId + content key (`movie:<id>` / `episode:<id>`), position, duration, and the series an episode belongs to.
- **programs**: playlistId, epgKey, start, stop, title, description. Only programmes from 6 hours ago to 7 days ahead are kept, and only for channels that matched.
- **guide_overrides** (DB v7): playlistId + channelKey → guideChannelId, a TV guide channel picked by hand ("" = no guide). Kept apart from channels, like favorites, so it survives a playlist refresh; each time channels are stored, the overrides are copied onto their `guideOverride` column.
- **guide_channels** (DB v7): playlistId + guideChannelId, display names. The guide's `<channel>` list from its last refresh, replaced with the programmes, to pick from.

### EPG matching

Each channel has an `epgKey`: its tvg-id (lower-cased), or `n:` + its normalized name when there's no tvg-id. During import, each XMLTV `<channel>` is matched to playlist channels by:

1. Exact tvg-id (case-insensitive).
2. tvg-id without an iptv-org style `@feed` suffix (`BBCNews.uk@HD` matches `BBCNews.uk`).
3. Display name against the normalized channel name (lower-case, no brackets, no HD/FHD/SD/4K, letters and digits only), with or without a country prefix: `UK: ITV HD` goes by both `ukitv` and `itv`.

Countries keep namesakes apart (a UK and a Ukrainian "ITV" in one guide). A channel's country comes from its tvg-id suffix (`ITV.uk`, `BBCNews.uk@HD`), else from a name prefix (`UK:`, `[PL]`, `DE |`, `FR -`) or a bracketed code (`TF1 (FR)`). Codes are ISO 3166, and `uk` means Great Britain, as in IPTV lists (Ukraine is `ua`). Rules:

- A match is dropped when the playlist channel and the guide channel name different countries, id matches included.
- A name match needs the guide channel to be the only one of that name for the playlist channel: the only one in the same country, or failing that, the only one with no other country. Several candidates that can't be told apart (two "ITV"s and a playlist channel with no country) match nothing rather than the wrong one.
- A playlist channel that some guide channel matches by id takes no name matches.

Name matches are decided after the guide's `<channel>` list has been read, so the order of namesakes doesn't matter.

#### Choosing the guide channel by hand

Providers often tag channels with the wrong guide id (one tagged "UK: ITV HD" with `srf1`, a Swiss channel), and matching follows the id. So, like TiviMate's EPG mapping, a channel's guide channel can be picked by hand: hold OK (or long-press) on a channel, then **Choose TV guide channel**. The picker lists the guide's channels (from guide_channels) with a search box, channels named like the playlist channel first under "Suggested", plus **Automatic** (remove the override) and **None**.

- A channel with an override has the epg key `g:` + the guide channel id, lower-cased (`g:` alone for None), instead of its tvg-id or name. `ChannelEntity.epgKey` reads the `guideOverride` column, so lists, the guide, the player and search all follow it.
- `EpgMatcher` leaves overridden channels out of id and name matching, and gives each picked guide channel's programmes to its `g:` key as well as to any channels it matches automatically. Other channels sharing the overridden channel's tvg-id keep their match.
- Choosing saves the override, updates the channel rows and restarts the guide refresh for that playlist (cancelling one in progress), so it applies straight away. Guides refreshed before v7 kept no channel list, so opening the picker with none loads the guide once.
- **Add a free TV guide** (in the picker) lists free community XMLTV guides (`GuideSources`: epgshare01 UK, about 490 channels; Freeview-EPG, UK free-to-air with regions). Selecting one appends its URL to the playlist's EPG URLs (selecting it again removes it) and reloads the guide, so channels the provider's guide lacks (or files under the wrong id) can be picked. The picker shows when the guide is loading or failed to load.

Programmes are stored under the matched channels' epg keys, so they survive a playlist refresh. Guides are streamed with a pull parser, `.gz` is detected from the file's first bytes, and the playlist's EPG URL may list several URLs separated by commas. Guides older than 12 hours refresh in the background on app start.

Refreshing a playlist deletes and re-inserts its channels in one transaction. Large playlists (50k+ entries) are parsed line by line from the network stream and inserted in batches, so memory stays flat.

## Navigation (D-pad first)

```
Home (groups | channels) ──OK──▶ Player ──Back──▶ Home
  │ Menu/top bar
  ├─▶ Search ──▶ Player, Guide at a programme, or a movie / series
  ├─▶ Movies / Series (Xtream) ──▶ Details ──▶ VOD player
  ├─▶ Recordings ──▶ VOD player
  ├─▶ Guide (EPG grid)
  ├─▶ Playlists ──▶ Add playlist
  └─▶ Settings
```

- OK on a channel plays it; long-press OK opens a menu: add or remove the favorite, or choose the channel's TV guide channel.
- Back in the channel list moves to the groups; Back again shows "Press Back again to exit"; a third press within 2.5 s exits.
- On every visit (app start included) the list focuses the last channel watched.
- Focusing a group selects it after a 250 ms pause. Leaving the groups upward (to Guide, Playlists or Settings) without entering a channel list puts back the group that was showing, so passing over Favorites doesn't switch to it.
- In the player:
  - Up / CH+ = next channel, Down / CH- = previous, within the group the channel was opened from (wraps around).
  - Number keys jump to a channel number (tunes after 1.5 s, or straight away on OK or at 4 digits). Numbers are positions in the whole playlist.
  - A lone `0` (or the remote's Last-channel key) flips back to the previous channel.
  - OK or Info toggles the info overlay; it also appears for 5 s on every channel change.
  - Back closes the overlay first, then leaves the player. The list re-focuses the last channel watched.
  - Menu (or Guide, where the system doesn't reserve it) opens the TV guide on the current channel.
  - Hold OK (or the Record key) records the channel until the current programme ends; hold again to stop.
  - ◀ / Rewind goes back 10 s (up to 30 s), ▶ / Fast-forward goes forward again and rejoins live; Play/Pause pauses live TV. A badge shows how far behind live you are.
- In the TV guide: ▲▼ channels, ◀▶ previous/next programme, CH+/CH- page, 0 jumps to now, OK watches the selected channel, or plays an ended programme from the archive on channels with catch-up. Hold OK records the programme (or cancels its recording). It shows the group you opened it from, or All channels if that group is empty.

## Milestones

1. **Shell, playlists, channels.** Add M3U URL / M3U file / Xtream, groups + channel list, favorites, basic full-screen playback.
2. **Player.** Channel up/down, info overlay (logo, name, now/next), number entry, last-channel recall, error/retry handling.
3. **EPG.** XMLTV download + parse (gzip supported), auto-mapped by `tvg-id`, TV-guide grid, now/next in lists and overlay.
4. **Settings and polish.** Auto-refresh interval, Xtream stream format (TS / HLS), buffer size, startup on last channel, delete/edit playlist, theme tweaks.

### Settings (milestone 4)

Stored in DataStore (`SettingsRepository`), with an always-loaded `current` copy for code that can't suspend:

- Channel up/down direction, buffering profile (Fast start / Balanced / Smooth), Xtream stream format (TS / HLS), start on last channel.
- Auto-refresh interval (off, 6, 12, 24 or 48 h). `AutoRefresher` checks 5 s after start and then every 30 min while the app is open, and refreshes playlists and guides older than the interval. Settings also has "Refresh everything now".
- The last channel is remembered by playlist + channel key, never by row id: refreshing a playlist recreates its channel rows.
- The Xtream format is applied when a channel is tuned (`XtreamClient.playableUrl`), so changing it needs no refresh.

Playlists can be edited (name, source, login, EPG URL). If the source changes, the channels are downloaded again before anything is saved; if the EPG URL changes or is removed, the guide is reloaded or cleared.

5. **Phone-friendly layout.** Layouts are designed for a 960×540 dp TV screen; shorter screens (phones in landscape) get a proportionally smaller density so the same layout fits. Touch devices get touch wording in hints and Back / Guide buttons in the player.
6. **Live rewind.** Go back up to 30 s on a live channel, pause, and catch up again (see below).
7. **Search and parental PIN.** Search channels and upcoming programmes. A 4-digit PIN locks chosen groups: their channels are hidden everywhere (lists, guide, zapping, numbers, search) until unlocked for the session.
8. **Movies and series (Xtream).** VOD and series catalogues by category, details pages, seasons and episodes, and a seekable player that remembers where you stopped.
9. **Catch-up.** On channels the provider archives (Xtream `tv_archive`, or M3U `catchup` attributes), past programmes in the guide play from the archive.
10. **Recording.** Record the channel you're watching or schedule programmes from the guide. A foreground service saves the stream to app storage; a Recordings screen plays and deletes them.
11. **Updates without a cable.** Settings checks an update address, downloads the new APK, verifies it and installs it with PackageInstaller.

### Live rewind (milestone 6)

`LivePlayback` (in `ui/player/Timeshift.kt`) sits between the player screen and ExoPlayer:

- **HLS / DASH** already offer a window of recent segments, so rewinding seeks inside it. The load control keeps 35 s of back buffer, so going back is instant.
- **Plain HTTP streams** (MPEG-TS, the IPTV norm) can't seek. A background thread reads the stream into a 32 MB in-memory ring buffer (`TimeshiftBuffer`, about 30 s at 8 Mbit/s), noting when each part arrived, and ExoPlayer plays from that buffer. Rewinding restarts playback from an earlier TS packet in the buffer, so the server sees only one connection (important for Xtream accounts with a connection limit).
- "Behind live" is measured against the delay while watching live, so the badge shows 0 at live and counts up while paused.

**Buffering on live TS.** A plain live stream arrives in real time, so the player only ever has the few seconds it waited before starting (2.5 s on Balanced): any network dropout longer than that stalls. `LiveLoadControl` doubles the buffer a resume waits for after each stall on the same channel (Balanced: 5, 10, 20, then 30 s), leaving that much headroom for the next dropout. HLS keeps its usual rules (it already plays several segments behind live), and the back buffer is kept only for HLS, which saves memory on TS. The info overlay shows resolution, how fast the stream arrives, the buffer, stalls, whether the decoder is hardware or software, and dropped frames, to tell network trouble from a device that can't keep up.

### Search and parental lock (milestone 7)

- **Search** (magnifier next to Settings) looks through the active playlist's channel names and the titles of programmes that haven't finished, 300 ms after typing stops. A programme on now plays its channel; a later one opens the TV guide at that time and channel.
- **Parental lock.** Hold OK (or long-press) on a group to lock it; the first time, this asks you to choose a 4-digit PIN. Only a salted SHA-256 hash of the PIN is stored (DataStore).
- While locked, a group's channels are left out of every list: All channels, Favorites, the guide, zapping, channel numbers, search and "start on last channel". The group itself stays in the sidebar with a lock, and selecting it offers Unlock.
- Entering the PIN unlocks all locked groups until the app is closed or Settings → Locked groups → Hidden. Holding OK on a locked group removes its lock (with the PIN). Removing the PIN removes every lock.
- `ParentalControl` holds the session state; `PlaylistRepository` passes the hidden groups into each channel query (`groupTitle NOT IN (:hidden)`).

### Movies and series (milestone 8)

- Xtream playlists load their movie and series catalogues (`get_vod_*`, `get_series*`) in the background after the live channels, on add, refresh and edit. A panel without them just shows none. Catalogues are streamed from JSON and inserted in batches, like channels.
- Home shows **Movies** and **Series** buttons for Xtream playlists. Each opens shelves (Continue watching, All, categories) beside a poster grid.
- A **movie** page shows the poster, backdrop, year, length, rating, genre, plot and credits (`get_vod_info`), with Play, or Resume / Play from start. A **series** page lists seasons and episodes (`get_series_info`) and starts on the episode watched last.
- The **VOD player** is seekable: OK play/pause, ◀ ▶ 10 s (held: 30 s), Rewind / Fast-forward 30 s; on touch, tap for controls and tap the bar to seek. It saves the position every 10 s and on exit; past 95 % counts as watched.
- Search also finds movies and series.
- URLs: `{server}/movie/{user}/{pass}/{id}.{ext}` and `{server}/series/{user}/{pass}/{episodeId}.{ext}`.

### Catch-up (milestone 9)

- Xtream streams with `tv_archive` keep their archive length (`tv_archive_duration` days); the panel's time zone comes from `server_info.timezone` at login. M3U channels read `catchup`, `catchup-days` (or `timeshift` / `tvg-rec`) and `catchup-source`.
- `Catchup` builds the archive URL: **xc** (`/timeshift/{user}/{pass}/{minutes}/{YYYY-MM-DD:HH-MM}/{id}.ts`, time in the panel's zone), **default** (template), **append**, **shift** (`utc` / `lutc`) and **flussonic**. Templates understand `{utc}` `{start}` `{end}` `{lutc}` `{now}` `{duration}` `{duration:60}` `{offset}` `{Y}{m}{d}{H}{M}{S}`, with or without `$`.
- The guide keeps programmes as far back as the longest archive (up to 7 days) and lets you scroll back that far. Ended programmes on archived channels show a history icon; OK plays them in the seekable player, and Back returns to the guide.

### Recording (milestone 10)

- **Record now** from the player (hold OK, the Record key, or the record button on touch screens): until the current programme ends plus 2 minutes, or for an hour without guide data. A REC badge shows while it runs.
- **Schedule** from the guide (hold OK, or long-press on touch): from 1 minute before the programme to 2 minutes after. Programmes set to record get a red dot. A programme already on starts recording at once.
- `RecordingService` is a foreground service (type dataSync) with a notification, so recordings carry on in the background. Several can run at once.
- Scheduled recordings are started by exact alarms (`USE_EXACT_ALARM` on Android 13+, `SCHEDULE_EXACT_ALARM` before), which also lets the service start while the app is in the background. Alarms are set again after a reboot or app update; recordings cut off by a restart keep what was saved.
- `Recorder` copies plain HTTP streams byte for byte (reconnecting if the stream drops) and records HLS by polling the media playlist and appending each new TS segment. Encrypted or fMP4 HLS can't be recorded.
- Files are MPEG-TS in the app's storage (`Android/data/com.lumora.tv/files/Recordings`, no storage permission). The Recordings screen plays, stops, cancels and deletes them; playback uses the seekable player.
- Live MPEG-TS streams go through `StreamHub`, which keeps one connection per stream URL for everyone reading it. Recording the channel you're watching therefore shares the player's connection, and the player joins a recording's connection when you tune to a channel being recorded. IPTV accounts often allow one connection, and a second one gets the provider's "connection limit reached" clip, which a recording would otherwise save.
- Recording one channel while watching another still needs two connections; the player warns about it when you tune away from a channel being recorded. HLS streams (Xtream format set to HLS) don't go through the hub.

### Updates without a cable (milestone 11)

- Settings → Updates holds the address of an **update file** (`update.json`: `versionCode`, `versionName`, `url`, `notes`), an optional GitHub token, "Check for updates" and "Check automatically". `url` may be relative to the update file, so the two files are uploaded side by side. Publishing is described in the README.
- `assembleRelease` finishes with the `writeUpdateJson` task (`app/build.gradle.kts`), which writes `update.json` next to the APK from the same version code and name the APK gets.
- `UpdateManifest` parses the file; an update is a strictly higher `versionCode` than the installed one (numbers, never version names). `GitHubReleases` turns `github.com/<owner>/<repo>/releases/…/download/<file>` into the REST API calls a private repository needs (release by tag or latest, then the file as `application/octet-stream`); without a token, addresses are downloaded as they are.
- `Updater` (in `AppContainer`) checks 20 s after start and then hourly, but reads the address at most once a day; an automatic check that finds a version shows a toast once. Its `UpdateState` drives the Settings rows.
- **Install:** on Android 8+ it first needs "Install unknown apps" for Lumora TV (`REQUEST_INSTALL_PACKAGES`, `canRequestPackageInstalls`). Without it, a dialog explains and opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (or the security settings on devices without that screen); coming back resumes the install. The APK is downloaded with the app's OkHttp client into `cache/update/`, then checked with `getPackageArchiveInfo`: same package, higher version code, same signing certificates as the installed app. It is written into a `PackageInstaller` session; `UpdateInstallReceiver` gets the result, opens Android's confirmation when the session asks for it, and reports failures back to Settings. On success Android replaces the app and restarts it (`MY_PACKAGE_REPLACED` re-arms scheduled recordings).
- Sessions ask for `USER_ACTION_NOT_REQUIRED` on Android 12+ (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`), which lets Android skip the confirmation once Lumora TV is its own installer of record.

Later: multi-view, picture-in-picture.
