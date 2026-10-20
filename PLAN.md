# Novafin plan

A Jellyfin client for Android phones and tablets, built to replace the official app for video:
movies, shows and anime. Music, photos and books are out of scope.

Target server: Jellyfin 10.11 (tested against 10.11.11). The library is mostly MKV with AV1 or HEVC
video, Opus/AAC/FLAC audio, dual audio (Japanese + English), and PGS or ASS subtitles, often a
"Signs & Songs" track next to a full one. The player and track memory are designed around that.

---

## 1. Stack

| Concern | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 | One toolkit for the phone and tablet layouts |
| Navigation | Navigation 3 (`NavDisplay` over a back stack we own) | Plain list as back stack; predictive back for free |
| Playback | Media3 ExoPlayer + HLS module | Direct play of MKV/MP4/WebM incl. PGS/ASS/SRT; HLS for server transcodes |
| Background / controls | Media3 `MediaSessionService` | Notification, lock screen, Bluetooth and headset controls; player outlives the screen |
| Player UI | `media3-ui-compose` `PlayerSurface` + our own Compose controls | Full control over gestures and layout |
| Casting | Media3 `CastPlayer` wrapping the local ExoPlayer | Switches local ↔ Chromecast automatically, same session and position |
| HTTP | OkHttp + `org.json` | ~25 endpoints; no SDK, codegen or serialization plugin |
| Images | Coil 3, sharing the API's OkHttp client, 512 MB disk cache | |
| Storage | SharedPreferences (settings, track memory), JSON files (downloads index, pending sync queue) | Small data, no schema or migrations |
| Background work | Foreground service for downloads; `ConnectivityManager` callback for sync | No WorkManager needed |

- Package / applicationId: `xyz.yuuki.novafin`
- minSdk 28 (Android 9), targetSdk / compileSdk 37
- AGP 9.4, Kotlin 2.4, Compose BOM 2026.09, Media3 1.11 (same toolchain as navidroid, known to build here)
- Release builds minified with R8

## 2. Source layout

Flat, one file per concern; split only when a file gets unwieldy.

```
app/src/main/java/xyz/yuuki/novafin/
  App.kt              Application: prefs, api, downloads, network monitor, Coil loader
  Prefs.kt            Settings + per-show/per-movie track memory
  Api.kt              Models (Item, MediaSource, MediaStream, UserData, Segment) + Jellyfin calls
  DeviceProfile.kt    Builds the device profile from MediaCodecList for PlaybackInfo
  Playback.kt         PlaybackService (ExoPlayer + CastPlayer + MediaSession), item → MediaItem,
                      progress reporter, track picking
  Downloads.kt        Download service, offline index, pending-sync queue and reconnect sync
  MainActivity.kt     Shell: bottom bar (phone) / navigation rail (tablet), login gate
  PlayerActivity.kt   Fullscreen player activity, PiP, immersive mode, orientation
  ui/Theme.kt         Colours from the icon, typography
  ui/Common.kt        Cards, posters, progress bars, rows, adaptive helpers
  ui/Login.kt         Server + credentials, Quick Connect
  ui/Home.kt          Home rows, tablet hero
  ui/Library.kt       Library grid, sort/filter, search
  ui/Details.kt       Movie, show, season, episode pages
  ui/Player.kt        Player controls, gestures, track/speed/quality sheets, up-next, skip intro
  ui/DownloadsScreen.kt
  ui/Settings.kt
app/src/test/...      Unit tests for the pure logic (track picking, sync merge, profile, parsing)
```

## 3. Jellyfin API surface

All requests carry
`Authorization: MediaBrowser Client="Novafin", Device="<model>", DeviceId="<uuid>", Version="<ver>", Token="<token>"`.
Media URLs that ExoPlayer or Chromecast fetch carry `api_key=<token>` instead.

| Use | Endpoint |
|---|---|
| Server check | `GET /System/Info/Public` |
| Login | `POST /Users/AuthenticateByName` |
| Quick Connect | `POST /QuickConnect/Initiate`, `GET /QuickConnect/Connect?secret=`, `POST /Users/AuthenticateWithQuickConnect` |
| Libraries | `GET /UserViews` |
| Continue watching | `GET /UserItems/Resume?mediaTypes=Video` |
| Next up | `GET /Shows/NextUp` |
| Latest | `GET /Items/Latest?parentId=` |
| Browse / search / filter | `GET /Items?parentId=&includeItemTypes=&sortBy=&filters=&searchTerm=&startIndex=&limit=` |
| Item | `GET /Items/{id}` |
| Seasons / episodes | `GET /Shows/{id}/Seasons`, `GET /Shows/{id}/Episodes?seasonId=` |
| Images | `/Items/{id}/Images/{Primary\|Backdrop\|Logo\|Thumb}?fillWidth=&quality=` |
| Playback decision | `POST /Items/{id}/PlaybackInfo` with device profile, max bitrate, audio/subtitle index |
| Direct play | `/Videos/{id}/stream?static=true&mediaSourceId=` |
| Transcode | `TranscodingUrl` from PlaybackInfo (HLS `master.m3u8`) |
| External subtitles | `/Videos/{id}/{mediaSourceId}/Subtitles/{index}/0/Stream.{srt\|vtt\|ass}` |
| Skip intro | `GET /MediaSegments/{id}` |
| Trickplay | `/Videos/{id}/Trickplay/{width}/{index}.jpg` |
| Reporting | `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped` |
| Watched / favourite | `POST\|DELETE /UserPlayedItems/{id}`, `POST\|DELETE /UserFavoriteItems/{id}` |
| Offline sync | `GET /UserItems/{id}/UserData`, `POST /UserItems/{id}/UserData` |
| Download original | `GET /Items/{id}/Download` |

## 4. How the main pieces work

### 4.1 Login and session
1. User enters the server URL; we normalise it (add `http://`, strip `/web/#/...`) and call `/System/Info/Public` to confirm it is Jellyfin.
2. Username/password → `AuthenticateByName`; store server URL, user id and access token. The password is never stored.
3. Quick Connect: show the code, poll `Connect` every 2 s, then exchange the secret for a token.
4. Device id is a random UUID made on first launch, so each phone and tablet shows up separately in the server dashboard.
5. A 401 anywhere logs the user out back to the login screen. Logout calls `/Sessions/Logout` and clears the token.

### 4.2 Browsing and layouts
- **Width classes**: compact (< 600 dp), medium (600–840), expanded (≥ 840), from the window size, so split screen and foldables pick the right layout.
- **Phone**: bottom bar with Home, Library, Search, Downloads; settings behind the avatar in the top bar. Portrait browsing.
- **Tablet**: navigation rail on the left; home opens with a large backdrop hero for the item to resume; grids use adaptive columns (`GridCells.Adaptive(140.dp)` phone, 170 dp tablet); detail pages are two-pane (poster and actions left, overview, cast, seasons and episodes right).
- **Home**: hero (tablet) → Continue watching (16:9 thumbs with progress) → Next up → Latest per library (posters) → Libraries.
- **Library**: paged grid (100 per page, next page loads near the end), sort and filter chips saved per library.
- **Detail pages**: backdrop with gradient, logo or title, metadata line, overview, Play / Resume and Restart, Mark watched, Favourite, Download, audio and subtitle pickers (prefilled from track memory), cast row, then seasons and episodes for shows.
- **Offline**: when the server can't be reached the shell shows an "Offline" banner and opens on Downloads; pages backed by the network show a retry state.

### 4.3 Playback decision
1. Build a **device profile** once from `MediaCodecList`: video codecs (h264, hevc, av1, vp9, plus 10-bit / HDR where the decoder reports it), max resolution, audio codecs (aac, mp3, opus, flac, ac3/eac3 when a decoder or passthrough exists), containers ExoPlayer reads (mkv, mp4, webm, ts, mov), subtitle formats (srt, ass, ssa, pgs, vobsub, vtt as embedded; srt, ass, vtt as external).
2. Transcoding profile: HLS fMP4 with h264/hevc + aac, stereo down-mix on phones.
3. `POST /Items/{id}/PlaybackInfo` with the profile, the quality preset for the current network (§4.4), the chosen `AudioStreamIndex` and `SubtitleStreamIndex`, and `StartTimeTicks`.
4. If the source `SupportsDirectPlay` → `/Videos/{id}/stream?static=true`; else if `SupportsDirectStream` → the remux URL; otherwise play the `TranscodingUrl` (HLS).
5. If ExoPlayer fails to decode a direct-play file (some AV1 / Hi10P on weak phones), retry once with direct play disabled so the server transcodes, from the same position.
6. Downloaded items skip all of this and play the local file.

### 4.4 Compression and quality

Goal: start fast and stall less on weak connections, and keep downloads small, without wasting the
server's CPU or making files look worse than they need to.

**What the server can do today** (`/System/Configuration/encoding` on goonhouse): no hardware
acceleration, HEVC and AV1 encoding disabled, so every transcode is software H.264. Consequences:
- Avoid transcoding when it isn't needed: direct play first, then **remux** (direct stream: same video,
  new container and/or audio only, almost free for the server), full transcode last.
- Many files are already AV1 at ~2 Mbps. Re-encoding them to H.264 at the same bitrate would look
  worse, so the original is often the smallest good-looking option (see "smart original" below).
- The app reads the codecs the server will produce from `TranscodingUrl` and adapts. Turning on
  hardware acceleration and "Allow encoding in HEVC" in the dashboard makes the app use HEVC
  automatically (same quality at roughly half the size), with no app change. Settings shows a hint
  when only H.264 is available.

**Quality presets** (shared by streaming and downloads):

| Preset | Max resolution | Video bitrate | Audio | ~Size per hour |
|---|---|---|---|---|
| Original | source | source | source | source |
| High | 1080p | 8 Mbps (HEVC 5) | stereo 192 kbps | 3.6 GB (HEVC 2.3) |
| Medium | 720p | 3 Mbps (HEVC 2) | stereo 160 kbps | 1.4 GB (HEVC 0.9) |
| Low | 480p | 1.2 Mbps (HEVC 0.8) | stereo 128 kbps | 0.6 GB (HEVC 0.4) |
| Data saver | 360p | 0.6 Mbps | stereo 96 kbps | 0.3 GB |

Requests set `MaxStreamingBitrate`, `VideoBitrate`, `MaxWidth`/`MaxHeight`, `AudioBitrate`,
`TranscodingMaxAudioChannels=2` and `VideoCodec` (`hevc,h264` when the device decodes HEVC, else `h264`).

**Streaming**
- Separate presets for Wi-Fi and mobile data (defaults: Original on Wi-Fi, Medium on mobile); metered
  detection via `NetworkCapabilities`, re-evaluated at each new item.
- **Smart original**: if the source bitrate is already at or below the preset's bitrate and the device
  can decode it, direct play the original even when a lower preset is selected.
- **Auto quality**: ExoPlayer's bandwidth meter decides the starting preset when "Auto" is chosen; if
  playback rebuffers twice within a minute, step down one preset at the same position and show a
  small "Lowered quality" note; never step back up mid-item.
- Stereo downmix of 5.1/7.1 on phones and tablets (unless an HDMI/USB output reports multichannel),
  which saves 300–600 kbps on transcodes.
- Faster start: request PlaybackInfo and start the stream in parallel with loading the player
  screen, start playback after 1.5 s of buffer instead of ExoPlayer's 2.5 s, bigger forward buffer
  (60 s) on Wi-Fi, smaller (30 s) on mobile to avoid wasting data on abandoned episodes.
- Images: request posters and backdrops at the size actually drawn (`fillWidth`), `format=Webp`,
  `quality=85`; one notch smaller on mobile data. Cuts page load bytes several times over.

**Downloads**
- Download preset chosen in settings (default Medium), with a per-download override in the download
  dialog that shows the **estimated size** (bitrate × runtime) for the item or the whole season and
  the free space left.
- Smart original applies here too: if the original is smaller than the preset's estimate and
  playable on the device, download the original.
- Transcoded downloads keep **only the chosen audio language** (from track memory) in stereo; an
  option keeps both original and dub audio.
- Text subtitles (SRT/ASS) are fetched as small sidecar files for the remembered languages; image
  subtitles (PGS/VobSub) are burned in when the remembered choice is one of them, since they can't be
  carried in a transcoded stream.
- Transcoded downloads are fetched as HLS segments through Media3's `DownloadManager`: resumable per
  segment, and the server encodes them as fast as its CPU allows. Originals are a plain file with
  HTTP range resume.

**Storage**
- Storage limit (e.g. 10 / 25 / 50 GB / unlimited) and a warning before a download would exceed it
  or the free space.
- Auto-delete watched downloads (off / immediately / after 1 day / after 7 days).
- Smart episodes per show: keep the next N unwatched episodes downloaded; watching one deletes it
  (if auto-delete is on) and queues the next, on Wi-Fi only.
- Storage location: internal or SD card (`getExternalFilesDirs`).
- Image cache capped (256 MB default), clearable in settings.

### 4.5 Player
- Separate `PlayerActivity` in its own task (`taskAffinity`, `singleTask`, `supportsPictureInPicture`), so the user can browse the app while PiP keeps playing.
- The ExoPlayer lives in `PlaybackService`; the activity attaches its `PlayerSurface` to it. Leaving the screen doesn't kill playback; the notification brings it back.
- Fullscreen: edge-to-edge, system bars hidden (swipe to reveal), display cutout used (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`), screen kept on while playing, sensor landscape by default with a rotate-lock button.
- Controls (auto-hide after 3 s): back + title/episode, cast, tracks, settings; centre play/pause and ±10 s; seek bar with buffered range, chapter ticks and trickplay thumbnail while dragging; next episode, lock.
- Gestures: double-tap left/right to seek ±10 s (accumulating), vertical swipe on the left half for brightness and right half for volume, horizontal swipe to scrub, pinch for fit ↔ fill/crop (remembered).
- Tracks sheet lists audio and subtitles with Jellyfin's display titles ("Japanese – Opus 2.0", "English – Signs & Songs"). Direct play: switch in ExoPlayer with a `TrackSelectionOverride`, mapping Jellyfin streams to ExoPlayer track groups by order within type. Transcode: request new PlaybackInfo with the new index and swap the URL at the same position.
- Subtitle style: size, colour/outline, background box, bottom margin, applied with `CaptionStyleCompat`; ASS styling kept where ExoPlayer supports it.
- Speed 0.5–2×, reset per item; quality sheet (Auto / Original / High / Medium / Low / Data saver, §4.4) shows the current mode (direct play, remux or transcode, codec and bitrate).
- **Skip intro**: fetch media segments; while inside an Intro or Recap segment show a "Skip intro" button (or skip automatically if enabled).
- **Up next**: at the Outro segment, or 30 s before the end, show the next episode card with a countdown; autoplay if enabled. The next episode is fetched from `/Shows/{id}/Episodes` once the current one starts.
- **PiP**: `setAutoEnterEnabled(true)` on Android 12+ while playing, `onUserLeaveHint` before that; aspect ratio from the video; play/pause and ±10 s remote actions; controls hidden in PiP; closing PiP stops playback and reports the position.
- **Background audio** (setting): if PiP is off and the user leaves, keep playing audio only; otherwise pause.
- **Resume**: start position is the newer of the server's `PlaybackPositionTicks` and the local offline position; "Resume / Start over" when both are possible.
- Audio focus and becoming-noisy handled by ExoPlayer; headset and Bluetooth buttons through the media session.

### 4.6 Progress reporting
- `Sessions/Playing` on start, `Progress` every 10 s and on pause, seek and track change, `Stopped` on end, item change or close, all with the `PlaySessionId` from PlaybackInfo.
- Jellyfin marks an item played past 90 %; we do the same locally for downloads.
- If a report fails (offline, or playing a download with no connection) the latest position for that item goes into the pending-sync queue instead.

### 4.7 Remembered audio and subtitle choices
- Stored per **series id** (episodes) or per **item id** (movies) in Prefs:
  `{ audioLang, audioTitle, subLang, subTitle, subForced, subOff, updatedAt }`.
- Saved whenever the user picks a track in the player or on the detail page.
- Picking for a new episode (pure function, unit tested):
  1. Audio: same language; tie-break by title similarity, then by channel count closest to the remembered one.
  2. Subtitles: if remembered as off → off. Else same language, then same "kind" (forced / signs & songs vs full, from the forced flag and title keywords), then title similarity.
  3. If nothing matches → the defaults from settings → the server user's `AudioLanguagePreference` / `SubtitleLanguagePreference` / `SubtitleMode` → the file's default flags.
- Used both for PlaybackInfo (indices) and to prefill the detail page pickers, so the next episode, a new session, or a downloaded episode all start with the right tracks.

### 4.8 Downloads
- Foreground service (`dataSync`) with a queue; one download at a time, progress notification, pause/cancel.
- **Original**: `/Items/{id}/Download` with HTTP range resume. **Transcoded**: HLS at the download preset through Media3's `DownloadManager` (§4.4), with the remembered audio track only.
- "Wi-Fi only" (default on) pauses on mobile data; storage limit and free space checked before queueing.
- Saved alongside: item JSON (metadata + media streams), external subtitle files, primary / backdrop / thumb images, the show and season JSON for offline browsing.
- Files in the app's external files dir (`getExternalFilesDir("downloads")`), not shared storage, so no storage permission is needed.
- Entry points: movie, episode, season ("download season / unwatched only"), and an index screen with sizes, storage used and delete.
- Downloaded items show a badge everywhere and always play from disk, online or not.

### 4.9 Offline sync on reconnect
- Pending queue (`pending.json`) keyed by item id: `{ positionTicks, played, favourite?, at }`; newest entry per item wins locally.
- Triggers: the network callback reports a validated network, the app comes to the foreground, or a download finishes.
- For each entry: `GET /UserItems/{id}/UserData`; if the server's `LastPlayedDate` is newer than our `at`, drop ours (the other device wins); else `POST /UserItems/{id}/UserData` with position, played flag and `LastPlayedDate = at`. Remove the entry on success, keep it on failure.
- The merge decision is a pure function with unit tests.

### 4.10 Chromecast
- `CastPlayer.Builder(context).setLocalPlayer(exoPlayer)` in the service, with the default media receiver (`DefaultCastOptionsProvider`) and the Compose `MediaRouteButton` in the player and app bar.
- A custom `MediaItemConverter` turns each item into a Jellyfin HLS URL the Chromecast can always play: `/Videos/{id}/master.m3u8` with `VideoCodec=h264`, `AudioCodec=aac`, the current audio index, `SubtitleMethod=Encode` for the chosen subtitle (works for PGS too), max bitrate and `api_key`.
- Switching to the TV continues from the current position; disconnecting resumes locally.
- The progress reporter reads the cast player's position, so watch progress keeps syncing while casting.

### 4.11 Settings
- Account: server, user, log out
- Playback: prefer direct play, autoplay next episode, auto skip intro, auto PiP, background audio, default zoom
- Languages: default audio and subtitle language, subtitle mode (default / always / only forced / off)
- Subtitles: size, colour, background, position
- Streaming quality: preset on Wi-Fi and on mobile data (incl. Auto), smart original, stereo downmix, load smaller images on mobile data
- Downloads: preset, keep both audio languages, Wi-Fi only, storage limit, storage location, auto-delete watched, smart episodes per show (N), storage used, delete all, clear image cache
- About: version, server version

### 4.12 Look and feel
- Dark theme from the icon: deep indigo background (`#16132B`), raised surfaces (`#221D3D`), violet accent (`#8F7BFF`), warm moon highlight (`#FFE9D2`) for progress and primary buttons, dusk pink (`#D8A0AE`) for secondary accents.
- Figtree font, posters with 8 dp corners, backdrops fading into the background, shimmer placeholders while loading.
- Adaptive launcher icon from `assets/icon.png` (generated mipmaps in `app/src/main/res/mipmap-*`), plus a monochrome crescent for themed icons.

## 5. Implementation order

Each step ends with a building app, tested on the phone emulator, a tablet emulator and against the real server, then committed.

1. **Scaffold**: Gradle project, theme, adaptive icon, manifest, App/Prefs, CI build of the debug APK.
2. **API + login**: models, auth header, login and Quick Connect screens, logout on 401.
3. **Shell + home**: phone bar / tablet rail, home rows, tablet hero, image loading.
4. **Library + search + details**: paged grid, sort/filter, search, movie/show/season/episode pages, watched and favourite toggles.
5. **Player core**: PlaybackService, device profile, PlaybackInfo decision, PlayerActivity, controls, fullscreen, resume, progress reporting, transcode fallback.
6. **Tracks and quality**: audio/subtitle/quality/speed sheets, external subtitles, subtitle styling, track memory and the picking function (+ tests); quality presets, smart original, auto step-down, downmix, sized WebP images.
7. **Player extras**: gestures, chapters, skip intro, up next and autoplay, PiP, background audio, trickplay thumbnails.
8. **Downloads**: service, original + HLS transcoded via DownloadManager, size estimates, season downloads, storage limit, auto-delete, smart episodes, downloads screen, offline playback.
9. **Offline mode + sync**: offline shell, pending queue, reconnect sync (+ tests).
10. **Chromecast**: CastPlayer, converter, route button, reporting while casting.
11. **Settings + polish**: settings screen, tablet two-pane details, empty/error states, accessibility pass (content descriptions, touch targets, TalkBack on the player).
12. **Release**: R8 rules check, signed release APK attached to GitHub releases.

## 6. Testing

- Unit tests (JVM) for: track picking, offline sync merge, device-profile JSON, URL normalisation, API parsing of real responses.
- Manual checklist per release on a phone and a tablet emulator plus a real device: login, browse, direct play AV1/HEVC MKV, forced transcode, switching audio and PGS/ASS subtitles, next-episode keeps the tracks, PiP enter/exit, background and resume from notification, download a season, airplane-mode playback, reconnect sync shows the progress in Jellyfin web, Chromecast.

## 7. Risks

- **AV1 / HEVC 10-bit on older phones**: software AV1 decoding can stutter at 1080p; the decode-error fallback to transcode covers it, and a "prefer transcode for AV1" setting is the escape hatch.
- **Chromecast codecs**: the default receiver can't play MKV/AV1/Opus, hence always casting a transcoded HLS stream.
- **PGS / VobSub with transcoding**: image subtitles can't be delivered as text; they are burned in when transcoding or casting.
- **CPU-only H.264 transcoding on the server**: transcodes cost server CPU and are bigger than HEVC; the app prefers direct play, remux and smart original, and uses HEVC as soon as the server allows it. Several simultaneous transcodes may be slow.
- **Track mapping in direct play**: ExoPlayer may skip a track it can't decode, breaking order-based mapping; we also match on language and channel count before trusting the index.

## 8. Not planned

- Music, photos, books, live TV
- SyncPlay, multiple saved servers, Android TV leanback UI
- Syncing remembered track choices across devices through the server's display preferences (possible later)
