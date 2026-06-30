# Broadcast — Više audio traka (Multi audio)

Use-case: **odabir i puštanje različitih audio traka** (npr. više jezika) nad **istim**
live servisom koji se već reprodukuje. Ključ: video stream i **live route (`liveRoute`)**
ostaju isti — mijenja se samo **koja audio traka svira**, pozivom
`AudioControl.setCurrentAudioTrack(liveRoute, index)`. Ne radi se novi zap ni rekonfiguracija videa.

Funkcija je nadgradnja na [Zapping](../Zapping/README.md) tok: tek **nakon** što MW potvrdi
promjenu kanala (`channelChangeStatus` / `safeToUnblank`) audio komponente su parsirane i
trake se mogu enumerisati.

## Fajlovi
- `audio_tracks_broadcast.puml` — UML sequence: inicijalizacija `AudioControl`, enumeracija i
  objava traka kroz TIF, izbor trake, te osvježavanje na MW callback. Mapirano na konkretan
  iWedia liba4tv API i na klase aplikacije (`DtvTvInputSession`, `ChannelZapper`).

## Tok (sažeto)
1. **Init** — `ComediaMiddlewareConnection.onDtvAvailable()` napravi `new AudioControl(dtvContext)`
   (isti obrazac kao `ServiceControl`/`DisplayControl`).
2. **Zap + listener** — u `ChannelZapper.startZap(...)`, nakon `startServiceByTriplet`, registruje se
   `AudioControl.registerListener(audioListener)`.
3. **Objava traka** — kad MW javi spreman kanal, `DtvTvInputSession.revealVideo()` →
   `publishAudioTracks()` pročita trake i pošalje ih kao **jednu** `TvTrackInfo` listu.
4. **Izbor** — `onSelectTrack(TYPE_AUDIO, trackId)` delegira na `setCurrentAudioTrack(...)`; video se ne dira.
5. **Osvježavanje** — `AudioListener.typeChanged(...)` → ponovna enumeracija + `notifyTracksChanged`.

## Glavni API pozivi (App ↔ TIS ↔ liba4tv ↔ MW)
| Sloj | Poziv |
|------|-------|
| init: MW → liba4tv | `new AudioControl(dtvContext)` (u `onDtvAvailable`) |
| zap: TIS → AudioControl | `registerListener(AudioListener)` (poslije `startServiceByTriplet`) |
| enumeracija: TIS → AudioControl | `getAudioTrackCount(liveRoute)` → `int count` |
| enumeracija: TIS → AudioControl | `getAudioTrack(liveRoute, i)` → `AudioTrack` (`getIndex`, `getLanguage`, `getAudioChannleCfg`) |
| enumeracija: TIS → AudioControl | `getCurrentAudioTrackIndex(liveRoute)` → `int` |
| objava: TIS → App | `notifyTracksChanged(List<TvTrackInfo>)`, `notifyTrackSelected(TYPE_AUDIO, id)` |
| izbor: App → TIS | `onSelectTrack(TYPE_AUDIO, trackId)` |
| izbor: TIS → AudioControl | `setCurrentAudioTrack(liveRoute, index)` → `A4TVStatus` |
| callback: MW → TIS | `AudioListener.typeChanged(liveRoute, AudioDigitalType)` → re-enumeracija |
| stop: TIS → AudioControl | `unregisterListener(AudioListener)` (u `stopZap`) |

## Bitne pouke (za implementaciju)
- **Ista ruta!** Svi `AudioControl` pozivi idu po postojećem `liveRoute` (čuva se kao `mLiveRoute`
  u `ChannelZapper`); ne pravi se nova ruta za audio.
- **Tajming** — trake gradi tek **poslije** `channelChangeStatus`/`safeToUnblank`; ranije audio
  komponente nisu spremne.
- **Jedna lista** — `notifyTracksChanged` zamjenjuje **cijelu** listu traka (ako se ikad doda i
  titl/druge vrste, sve ide u istu listu).
- **Threading** — `onTune`/`onSelectTrack` se vraćaju odmah; sav middleware IPC ide na pozadinski
  `DtvTune` `HandlerThread` (TIF ubija proces ako `onTune` blokira glavni thread > 2 s).
  `notify*` su thread-safe.
- **„Multi audio" u sistemskom UI-ju** se otključa kad sesija prijavi **≥ 2** `TYPE_AUDIO` trake;
  kanal s jednom trakom ispravno ostaje siv.
- `trackId` = `AudioTrack.getIndex()` — isti indeks se vraća u `setCurrentAudioTrack`.

## Render
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml.

## Mapiranje na liba4tv API (paket)
Kontrole: `iwedia.dtv.audio.AudioControl`, callback `iwedia.dtv.audio.AudioListener`.
Tipovi: `com.iwedia.dtv.audio.AudioTrack`, `com.iwedia.dtv.types.AudioChannelConfiguration`,
`com.iwedia.dtv.types.AudioDigitalType`, `com.iwedia.dtv.types.AudioTrackType`.
