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
AppContainer   wires repositories, DB, OkHttp
```

Data flows one way: **Repository → Flow → ViewModel StateFlow → Compose**. User actions go back as ViewModel calls.

## Data model

- **playlists**: id, name, type (`M3U_URL`, `M3U_FILE`, `XTREAM`), url, username, password, epgUrl, updatedAt
- **channels**: id, playlistId, key (stable id: stream URL or Xtream stream id), name, groupTitle, logo, streamUrl, tvgId, sortOrder
- **favorites**: playlistId + channelKey. Kept in a separate table so favorites survive a playlist refresh.
- **locked_groups**: playlistId + groupTitle, for the parental lock (milestone 7). Also kept by name, so locks survive a refresh.
- **vod**: movies and series of Xtream playlists (kind, provider id, name, category, poster, rating, year, file type). Details, seasons and episodes are fetched when a title is opened.
- **playback_progress**: playlistId + content key (`movie:<id>` / `episode:<id>`), position, duration, and the series an episode belongs to.
- **programs**: playlistId, epgKey, start, stop, title, description. Only programmes from 6 hours ago to 7 days ahead are kept, and only for channels that matched.

### EPG matching

Each channel has an `epgKey`: its tvg-id (lower-cased), or `n:` + its normalized name when there's no tvg-id. During import, each XMLTV `<channel>` is matched to playlist channels by:

1. Exact tvg-id (case-insensitive).
2. tvg-id without an iptv-org style `@feed` suffix (`BBCNews.uk@HD` matches `BBCNews.uk`).
3. Display name against the normalized channel name (lower-case, no brackets, no HD/FHD/SD/4K, letters and digits only).

Programmes are stored under the matched channels' epg keys, so they survive a playlist refresh. Guides are streamed with a pull parser, `.gz` is detected from the file's first bytes, and the playlist's EPG URL may list several URLs separated by commas. Guides older than 12 hours refresh in the background on app start.

Refreshing a playlist deletes and re-inserts its channels in one transaction. Large playlists (50k+ entries) are parsed line by line from the network stream and inserted in batches, so memory stays flat.

## Navigation (D-pad first)

```
Home (groups | channels) ──OK──▶ Player ──Back──▶ Home
  │ Menu/top bar
  ├─▶ Search ──▶ Player, Guide at a programme, or a movie / series
  ├─▶ Movies / Series (Xtream) ──▶ Details ──▶ VOD player
  ├─▶ Guide (EPG grid)
  ├─▶ Playlists ──▶ Add playlist
  └─▶ Settings
```

- OK on a channel plays it; long-press OK toggles favorite.
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
  - ◀ / Rewind goes back 10 s (up to 30 s), ▶ / Fast-forward goes forward again and rejoins live; Play/Pause pauses live TV. A badge shows how far behind live you are.
- In the TV guide: ▲▼ channels, ◀▶ previous/next programme, CH+/CH- page, 0 jumps to now, OK watches the selected channel. It shows the group you opened it from, or All channels if that group is empty.

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

### Live rewind (milestone 6)

`LivePlayback` (in `ui/player/Timeshift.kt`) sits between the player screen and ExoPlayer:

- **HLS / DASH** already offer a window of recent segments, so rewinding seeks inside it. The load control keeps 35 s of back buffer, so going back is instant.
- **Plain HTTP streams** (MPEG-TS, the IPTV norm) can't seek. A background thread reads the stream into a 32 MB in-memory ring buffer (`TimeshiftBuffer`, about 30 s at 8 Mbit/s), noting when each part arrived, and ExoPlayer plays from that buffer. Rewinding restarts playback from an earlier TS packet in the buffer, so the server sees only one connection (important for Xtream accounts with a connection limit).
- "Behind live" is measured against the delay while watching live, so the badge shows 0 at live and counts up while paused.

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

Later: multi-view, picture-in-picture.
