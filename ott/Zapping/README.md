# OTT — Zapping (playback from URL)

Use-case: **promjena kanala / aktivacija OTT servisa** (zap). Korisnik bira OTT servis iz liste;
TIF poziva `onTune`, OTT TvInputService razriješi **stream URL** iz TV Provider-a
(`internalProviderData`), preda ga **open-source plejeru (ExoPlayer / AndroidX Media3)** koji
preko HTTP-a povlači **HLS/DASH** manifest + segmente (adaptivno, ABR), dekoduje preko
**MediaCodec/AudioTrack** i renderuje na surface. Sve je IP — **nema tunera ni demuksa**.

## Open source umjesto Comedia
Comedia (iWedia liba4tv) je broadcast middleware i **ne koristi se za OTT**. Za OTT je dovoljan
**standardni Android playback iz URL-a** (Module 7): **ExoPlayer / AndroidX Media3** (Apache-2.0)
kao plejer + **TV Input Framework** kao middleware. Ovo je open-source pandan broadcast
`sequence-exoplayer-v2` dijagramima.

## Fajlovi
- `block.puml` — arhitekturni block dijagram kroz slojeve **HAL / PAL / AL**.
- `sequence-exoplayer-v2.puml` / `sequence-exoplayer-v2.mmd` — UML sequence: ExoPlayer/Media3 (gore) + Platforma/HAL (demonstration, dole).

## Ključ (block dijagram)
- **Zeleno = aktivno**: TV Input Framework, OTT TvInputService, OTT client, ExoPlayer/Media3, HLS/DASH MediaSource, HTTP DataSource, TLS, Network (Wi-Fi/Ethernet), MediaCodec, AudioTrack, VDEC, ADEC, GPU/Display.
- **Sivo (idle)**: TUNER, DEMUX — RF/broadcast lanac se NE koristi za OTT.

## Glavni API pozivi (App → MW → A/V)
| Sloj | Poziv |
|------|-------|
| App → TIS | `onSetSurface(surface)`, `onSetStreamVolume(0.0)`, `onTune(channelUri)` |
| TIS → App | `notifyVideoUnavailable(VIDEO_UNAVAILABLE_REASON_TUNING)` (na početku tune-a) |
| TIS → TV Provider | pročitaj `internalProviderData` (stream URL) za kanal |
| TIS → ExoPlayer | `new ExoPlayer.Builder(context).build()` |
| TIS → ExoPlayer | `setVideoSurface(surface)` (iz `onSetSurface`), `setVolume(0.0f)` (iz `onSetStreamVolume`, mute) |
| TIS → ExoPlayer | `addListener(playerListener)` |
| TIS → ExoPlayer | `setMediaItem(MediaItem.fromUri(streamUrl))` |
| TIS → ExoPlayer | `prepare()` (ASINHRON) |
| ExoPlayer → mreža | `HTTP GET` manifest (HLS `.m3u8` / DASH `.mpd`) + segmenti, ABR |
| ExoPlayer → A/V | `MediaCodec` (video) + `AudioTrack` (audio), A/V sync po PTS |
| TIS → ExoPlayer | `play()` / `setPlayWhenReady(true)` |
| ExoPlayer → TIS (cb) | `onPlaybackStateChanged(STATE_READY)`, `onRenderedFirstFrame()` |
| TIS → ExoPlayer | `setVolume(volume)` (unmute, restore) |
| TIS → App | `notifyVideoAvailable()` |
| ExoPlayer → TIS | `getCurrentTracks()` → `notifyTracksChanged(List<TvTrackInfo>)` |

## Bitne pouke (za implementaciju)
- **Asinhrono!** `prepare()` se brzo vrati; čekaj `onPlaybackStateChanged(STATE_READY)` /
  `onRenderedFirstFrame()`, ne pretpostavljaj da je playback odmah krenuo (isto kao broadcast).
- **Stream URL** (a ne triplet) je identifikator OTT izvora; URL dolazi iz TV Provider-a.
- **HLS/DASH** se biraju automatski (`DefaultMediaSourceFactory` po MIME/ekstenziji); za HLS/DASH
  treba uključiti odgovarajuće Media3 module (`media3-exoplayer-hls`, `media3-exoplayer-dash`).
- **ABR** (adaptive bitrate) bira kvalitet prema propusnosti — nema RF locka, nego mrežni uslovi.
- `onSetStreamVolume(0.0)` → `ExoPlayer.setVolume(0.0f)` = mute; tek poslije `onRenderedFirstFrame()` ide `setVolume(volume)` (unmute).
- Na početku `onTune` pozovi `notifyVideoUnavailable(VIDEO_UNAVAILABLE_REASON_TUNING)` (da se ne vidi zalutali frame), pa `notifyVideoAvailable()` kad krene prvi frame.
- **Napomena o dokumentaciji:** zvanična TIF stranica ([Manage TV user interaction](https://developer.android.com/training/tv/tif/ui)) prikazuje **stari** ExoPlayer obrazac (`createMessage(videoRenderer)`/`MSG_SET_SURFACE`, `createMessage(audioRenderer)`/`MSG_SET_VOLUME`); u **Media3** se koristi javni API `setVideoSurface(Surface)` i `setVolume(float)`.

## Razlika u odnosu na broadcast zapping
- **Broadcast**: tuner tune + demux PID filteri → MediaCodec/AudioTrack (PCR/STC lipsync).
- **OTT**: HTTP GET (HLS/DASH) → ExoPlayer demuksuje segmente → MediaCodec/AudioTrack (PTS sync).
  Mreža + ExoPlayer zamjenjuju tuner + demux + DTV middleware.

## Render
- Mermaid: VS Code preview, GitHub, ili https://mermaid.live.
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml.

## Mapiranje na open-source Android API (ExoPlayer / AndroidX Media3)
| Konceptualno | Konkretno (androidx.media3) |
|---|---|
| kreiranje plejera | `ExoPlayer.Builder(context).build()` |
| gdje se crta video | `ExoPlayer.setVideoSurface(Surface)` / `setVideoSurfaceView/Holder/TextureView(...)` (iz `onSetSurface`) |
| jačina zvuka / mute | `ExoPlayer.setVolume(float)` 0.0–1.0 (iz `onSetStreamVolume`) |
| registracija na evente | `ExoPlayer.addListener(Player.Listener)` |
| zadavanje izvora | `setMediaItem(MediaItem.fromUri(url))` (auto HLS/DASH/Progressive) |
| eksplicitan HLS izvor | `HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(MediaItem.fromUri(m3u8))` |
| eksplicitan DASH izvor | `DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(MediaItem.fromUri(mpd))` |
| HTTP transport | `DefaultHttpDataSource.Factory()` / `OkHttpDataSource.Factory` / `CronetDataSource.Factory` |
| ABR selekcija | `DefaultTrackSelector` + `AdaptiveTrackSelection.Factory` |
| start/priprema | `prepare()` + `play()` / `setPlayWhenReady(true)` |
| „spreman/safe to show" | `Player.Listener.onPlaybackStateChanged(STATE_READY)` + `onRenderedFirstFrame()` |
| greška | `Player.Listener.onPlayerError(PlaybackException)` |
| tracks ka TIF-u | `getCurrentTracks()` → `Session.notifyTracksChanged(List<TvTrackInfo>)` |
| video dostupan | `Session.notifyVideoAvailable()` (poslije prvog frame-a) / `notifyVideoUnavailable(VIDEO_UNAVAILABLE_REASON_TUNING)` (pri tune-u) |
| izbor track-a | `Session.onSelectTrack(int, String)` → `notifyTrackSelected(int, String)` (mapira na Media3 `TrackSelectionParameters`) |
| ispod plejera (demo-level) | `MediaCodec` (VDEC) + `AudioTrack` (ADEC) + GPU/Display |

## Reference (Android open source dokumentacija)

Svi potpisi su provjereni na zvaničnoj Android/AOSP dokumentaciji (jun 2026):

- [Media3 ExoPlayer — Hello world](https://developer.android.com/media/media3/exoplayer/hello-world) — `ExoPlayer.Builder(context).build()`, `MediaItem.fromUri`, `setMediaItem`, `prepare()`, `play()`. Paket `androidx.media3`.
- [Media3 — Player events / `Player.Listener`](https://developer.android.com/media/media3/exoplayer/listening-to-player-events) — `onPlaybackStateChanged(@State int)`, stanja `STATE_IDLE/BUFFERING/READY/ENDED`.
- [Media3 — HLS](https://developer.android.com/media/media3/exoplayer/hls) i [DASH](https://developer.android.com/media/media3/exoplayer/dash) — `HlsMediaSource.Factory` / `DashMediaSource.Factory`, automatski ABR.
- [Media3 — Media sources](https://developer.android.com/media/media3/exoplayer/media-sources) — `DefaultMediaSourceFactory` (auto izbor HLS/DASH/Progressive), `DefaultHttpDataSource.Factory`.
- [Manage TV user interaction](https://developer.android.com/training/tv/tif/ui) — `onTune`, `onSetSurface`, `onSetStreamVolume`, `notifyVideoAvailable`/`notifyVideoUnavailable(VIDEO_UNAVAILABLE_REASON_TUNING)`, `notifyTracksChanged` (TV Input Framework).
- [ExoPlayer → Media3 mappings](https://developer.android.com/media/media3/exoplayer/mappings) — stari `SimpleExoPlayer`/renderer-message obrazac vs. moderni `setVideoSurface`/`setVolume`.
- [Media3 izvorni kod](https://github.com/androidx/media) — Apache-2.0 (ExoPlayer/Media3 je open source).
