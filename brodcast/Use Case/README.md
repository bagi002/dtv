# Broadcast — Subtitles (Use Case)

Use-case: **prikaz DVB titlova (subtitle) tokom broadcast playback-a**. Ovo je nastavak
na `Zapping` use-case — pretpostavka je da je servis već aktivan (zap je završen, A/V
kanali su otvoreni, `MediaCodec`/`AudioTrack` rade). Subtitle tok se nadovezuje na već
postojeću PID-routing infrastrukturu (vidi `Zapping/README.md`): isti `Table`/`TDAL_DMX`
mehanizam koji parsira PAT/PMT otkriva i **subtitle elementary stream (PID)** iz PMT-a, a
zatim middleware otvara dodatni PID channel, parsira DVB subtitle segmente (PES → bitmap),
sinhronizuje ih sa PTS-om i prosleđuje GPU-u/display-u na composition (layering preko videa).

`sequence.puml` / `sequence.mmd` su urađeni u istom stilu kao
`Zapping/sequence-liba4tv-v2.puml` / `.mmd`: tri vizuelno razdvojene **box** grupe
(Android app / Comedia liba4tv / Platform-HAL demonstration), gde je gornji dio
(Comedia kontrole) implementation-level — svaka strelica je tačan potpis metode iz
liba4tv javadoc-a — a donji dio (Tuner SDK/Demux, GPU/Display) je **demonstration-level**:
eksplicitno markiran napomenama `demonstration:` i ne predstavlja stvarne liba4tv
pozive, već samo kontekst kako se to mapira na HAL/Platform iz PDF lekcija (Module 6/3).

## Fajlovi
- `block.puml` — arhitekturni block dijagram kroz slojeve **HAL / PAL / AL**, sa
  legendom aktivno/idle za subtitle use-case (vidi "Ključ" ispod).
- `sequence.puml` / `sequence.mmd` — UML sequence dijagram sa tri **box** grupe:
  - **Android app** (plavo) — `Live TV App`, `Broadcast TvInputService`
  - **Comedia - iWedia liba4tv** (zeleno) — `SubtitleControl`, `StreamComponentControl`,
    `DisplayControl`, `Comedia CHAL - TDAL`. Pozivi prema/od `SubtitleControl`,
    `StreamComponentControl` i `DisplayControl` su tačni potpisi iz javadoc-a
    (ime + parametri). `Comedia CHAL - TDAL` je granica iza koje liba4tv API prestaje
    da bude javno dokumentovan.
  - **Ispod Comedije - Platform i HAL - demonstration** (narandžasto) — `Tuner SDK /
    Demux Filter`, `GPU / Display`. Ovo NIJE liba4tv API, već demonstration-level
    kontekst (PDF Module 6/3) koji pokazuje gde subtitle PID filtering i bitmap
    rendering fizički završavaju.

## Tri ključne liba4tv kontrole — šta predstavljaju

**`SubtitleControl`** (`iwedia.dtv.subtitle`) — javni API za **upravljanje titlom kao
korisničkom funkcijom**: koliko traka postoji i koje su (`getSubtitleTrackCount` /
`getSubtitleTrack`), koja je trenutno aktivna (`getCurrentSubtitleTrackIndex`), biranje
trake (`setCurrentSubtitleTrack` / `deselectCurrentSubtitleTrack`), tip titla
DVB/TTX/CC/SIMP/TTML (`setSubtitleType`), mod prikaza — prevod vs. za slušno oštećene
(`setSubtitleMode`), i automatski prikaz čim podaci stignu (`enableAutomaticSubtitleDisplay`,
`enableSuperimpose`). Ovo je kontrola koju `TIS` direktno zove kad korisnik traži da
uključi/promijeni/isključi titl — odgovara na pitanje **"šta korisnik želi"** (analogno
`ServiceControl` u Zapping use-case-u).

**`StreamComponentControl`** (`iwedia.dtv.streamcomponent`) — opštiji nivo **iznad**
subtitle-a: ne zna ništa specifično o titlu, samo prati koje komponente (AUDIO, VIDEO,
SUBTITLE, TELETEXT, MHEG, HBBTV, CLOSECAPTION, SUPERIMPOSE, BML, TTML) postoje na
trenutnoj ruti i javlja promjenu kroz `StreamComponentListener.componentChanged(routeID,
StreamComponentType)`. To je mehanizam kojim middleware (nakon parsiranja PMT-a u Table
modulu) **obaveštava** `TIS` da je subtitle PID otkriven — taj event mora stići PRE nego
što ima smisla pitati `SubtitleControl` za broj/listu traka. Dakle:
`StreamComponentControl` = "šta postoji i kad se promijeni", `SubtitleControl` = "kako
da upravljam izabranom komponentom".

**`DisplayControl`** (`iwedia.dtv.display`) — nivo **prezentacije/rendera**, bez veze sa
parsiranjem ili biranjem trake; odgovara na pitanje **"gde se nešto crta na ekranu"**.
`setVideoSurface()` je za video plane (koristi se u Zapping use-case-u), a
`setGraficSurface()` je za graphics/bitmap plane — upravo tu se DVB subtitle bitmap
(dekodiran ispod, u CHAL `TDAL_GFX`) prikazuje preko videa. Bez ovog poziva, titl bi bio
dekodiran ali ne bi imao gdje da se renderuje.

## Implementation-level vs. demonstration-level (zašto su CHAL/Platform/HAL "ispod crte")
Raniju verziju ovog dijagrama sam pravio sa MAL/MW/CHAL lifeline-ovima koji su imali
**izmišljene** interne nazive poziva (npr. `notifyComponentChanged(...)`,
`activateTrack(...)`, `configureGraphicsPlane(...)`) — ti nazivi ne postoje nigde u
liba4tv javadoc-u, već su bili pretpostavka o tome kako Comedia *vjerovatno*
implementira kontrolu iznad CHAL-a. To je bilo neprecizno za nešto što treba 1:1 da se
prepiše u kod.

Sada (v2 stil, isti kao `Zapping/sequence-liba4tv-v2.puml`) je razlika jasno povučena
kroz tri **box** grupe i eksplicitne napomene:
- Sve strelice **prema/od `SubtitleControl`, `StreamComponentControl`, `DisplayControl`**
  (zelena box grupa) su implementation-level — tačan potpis iz javadoc-a, spreman za
  direktno prepisivanje u kod.
- Sve što je **ispod `Comedia CHAL - TDAL`** (narandžasta box grupa — Tuner SDK/Demux
  Filter, GPU/Display) je markirano kao **demonstration** — to nisu liba4tv pozivi, već
  kontekst iz PDF lekcija (Module 6, Module 3) koji objašnjava gde subtitle PID filtering
  i PTS-sinhronizovani bitmap rendering fizički završavaju, ispod javnog API-ja.

## Preduslov (state pre ulaska u ovaj use-case)
Servis je već "zapovan" (vidi `Zapping/README.md` i `Zapping/sequence-liba4tv.puml`,
koji u Zapping folderu i dalje postoji kao odvojen fajl):
- `BroadcastRouteControl.getLiveRoute(...)` je već vraćeno `liveRoute`.
- `ServiceControl.startServiceByTriplet(...)` / `zapURL(...)` je pozvano i
  `ServiceListener.channelChangeStatus(liveRoute, true, NO_ERROR)` je već stiglo.
- PAT/PMT su već parsirani (Table modul), pa su PID-ovi za video/audio/**subtitle**
  poznati iz PMT elementary-stream loop-a (Module 3, "PMT: Program Map Table").

## Ključ (block dijagram) — predlog
- **Zeleno = aktivno** u subtitle toku: Table (PMT subtitle deskriptor), CHAL TDAL_DMX
  (subtitle PID channel), Comedia `SubtitleControl`/`SubtitleListener`,
  `StreamComponentControl` (componentChanged za SUBTITLE), CHAL `TDAL_GFX` (graphics
  plane), `DisplayControl.setGraficSurface()`, GPU/Display (composition/layering).
- **Sivo (idle)**: Scan Control (samo install), VDEC/ADEC dekodiranje A/V (već rade iz
  zapping use-case-a, ali nisu fokus ovog dijagrama), PVR/Reminder/App Engines.

## Glavni API pozivi (App → TIS → liba4tv kontrole)
Svaki red je tačan potpis iz javadoc-a (`dokumentacija/liba4tv/doc`), istim redosledom
kao u `sequence.puml`.

| Kontrola | Poziv |
|------|-------|
| `SubtitleControl` | `registerListener(SubtitleListener listener)` |
| `StreamComponentControl` | `registerListener(StreamComponentListener listener)` |
| `StreamComponentListener` (callback) | `componentChanged(int routeID, StreamComponentType componentType)` |
| `SubtitleListener` (callback) | `updateTrackCount(SubtitleType type, int cnt)` |
| `SubtitleControl` | `getSubtitleTrackCount(int routeID)` |
| `SubtitleControl` | `getSubtitleTrack(int routeID, int trackIndex)` |
| `SubtitleControl` | `setSubtitleType(SubtitleType type)` — bez routeID |
| `SubtitleControl` | `setCurrentSubtitleTrack(int routeID, int trackIndex)` |
| `SubtitleControl` (opciono) | `setSubtitleMode(SubtitleMode mode)` — bez routeID, jedan mod po pozivu |
| `SubtitleControl` | `enableAutomaticSubtitleDisplay(boolean enable)` |
| `SubtitleControl` (opciono) | `enableSuperimpose()` |
| `DisplayControl` | `setGraficSurface(SurfaceBundle surface)` — bez routeID/layer |
| `SubtitleControl` | `getCurrentSubtitleTrackIndex(int routeID)` |
| `SubtitleControl` | `deselectCurrentSubtitleTrack(int routeID)` |
| `SubtitleControl` | `unregisterListener(SubtitleListener listener)` |
| `StreamComponentControl` | `unregisterListener(StreamComponentListener listener)` |

## Identifikatori i tipovi (iz PDF-ova, Module 3)
- DVB subtitle = **bitmap** subtitle (renderovan i layered preko videa), za razliku od
  ATSC closed caption koji je **text-based** (receiver interpretira tekst).
- DVB titl ima **composition page ID** i **ancillary page ID** (vidi
  `StreamComponentDesc.getCompositionPageID()` / `getAncillaryPageID()`).
- Teletext (TTX) je odvojen tip (`SubtitleType.TTX`), takođe podržan kroz isti `SubtitleControl`,
  ali sa drugim parsing modulom (`TeletextControl`/`TeletextTrack`) — van fokusa ovog use-case-a,
  ali pomenuto za kompletnost arhitekture.
- U OTT/broadband svetu (Module 4) titl je drugačiji: **WebVTT/TTML** (tekstualni fajlovi
  preuzeti preko HTTP-a), ne bitmap PES. Ovaj use-case (liba4tv/Comedia) pokriva **samo
  broadcast (DVB) slučaj** — to je i eksplicitan zahtev za ovaj folder.

## Render
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml (izvoz PNG/SVG).

---

## Mapiranje na konkretan liba4tv API (dokumentacija/liba4tv/doc)

Isti obrazac kao u `Service_instalation/README.md` i `Zapping/README.md`: kontrole se
dobijaju iz `IDTVManager` (`DtvControl.context().dtvManager()`); `iwedia.dtv.*` su
**kontrole** (npr. `SubtitleControl`), `com.iwedia.dtv.*` su **tipovi/DTO** (npr.
`SubtitleTrack`, `SubtitleType`). Potpisi su provereni u javadoc-u
(`dokumentacija/liba4tv/doc`).

\* `getXxxControl()` akcesori (npr. `getSubtitleControl()`, `getStreamComponentControl()`,
`getDisplayControl()`) su (po analogiji sa Service_instalation/Zapping) na `IDTVManager` —
taj interfejs nije zaseban dokumentovan u ovom javadoc subset-u (samo se pominje kao
povratni tip), pa nazive metoda treba **potvrditi u kodu**. Sve niže navedene klase i
metode SU direktno provjerene u javadoc-u.

### Klase potrebne za subtitle use-case

| Klasa (paket) | Vrsta | Šta predstavlja |
|---|---|---|
| `iwedia.dtv.subtitle.SubtitleControl` | kontrola (`DtvControl<ISubtitleControl>`) | glavni API za biranje/konfiguraciju DVB/TTX/CC/SIMP/TTML titla |
| `iwedia.dtv.subtitle.SubtitleListener` | listener (interfejs) | callback kad se promijeni broj raspoloživih titl traka |
| `com.iwedia.dtv.subtitle.SubtitleTrack` | tip (Parcelable) | opis jedne titl trake: index, name, language, languageCnt, type, selected |
| `com.iwedia.dtv.subtitle.SubtitleType` | enum | `DVB`, `TTX`, `CC`, `SIMP`, `TTML` |
| `com.iwedia.dtv.subtitle.SubtitleMode` | enum | `TRANSLATION`, `HEARING_IMPAIRED` |
| `iwedia.dtv.streamcomponent.StreamComponentControl` | kontrola | registracija listenera za promjene A/V/subtitle komponenti na ruti |
| `iwedia.dtv.streamcomponent.StreamComponentListener` | listener (interfejs) | callback `componentChanged(routeID, StreamComponentType)` |
| `com.iwedia.dtv.streamcomponent.StreamComponentType` | enum | `AUDIO`, `VIDEO`, `SUBTITLE`, `TELETEXT`, `MHEG`, `HBBTV`, `CLOSECAPTION`, `SUPERIMPOSE`, `BML`, `TTML` |
| `com.iwedia.dtv.streamcomponent.StreamComponentDesc` | tip | deskriptor komponente: PID, componentTag, language, `getSubType()` (SubtitleType), `getCompositionPageID()`, `getAncillaryPageID()` |
| `iwedia.dtv.display.DisplayControl` | kontrola | `setGraficSurface()` (subtitle/graphics plane), `setVideoSurface()`, `scaleWindow()` |
| `iwedia.dtv.table.TableControl` | kontrola | nizak nivo — direktan request/parse PSI/SI sekcija (alternativa ako se ne ide kroz `ScanControl`/auto-discovery) |
| `iwedia.dtv.route.broadcast.BroadcastRouteControl` | kontrola | `getLiveRouteConfiguration(routeID)` — provjera da je ruta već aktivna (preduslov iz Zapping use-case-a) |

### Napomena (otklonjena neusklađenost)
Ranija verzija ovog dijagrama je sadržala i
korak "detalji PID-a / composition/ancillary page preko `StreamComponentControl.getStreamComponentDesc(...)`"
— ta metoda **ne postoji** u `iwedia.dtv.streamcomponent.StreamComponentControl`
(javadoc potvrđuje samo `registerListener`/`unregisterListener`). Korak je uklonjen iz
`sequence.puml` jer nije pokriven dokumentacijom; ako se u budućnosti pokaže da
deskriptor komponente postoji na nekoj drugoj kontroli, dodati ga uz potvrdu u kodu/SDK-u.

### Napomena: Teletext kao alternativni/paralelni tip
`SubtitleType.TTX` postoji u istom enumu kao DVB, ali se u praksi ruta razlikuje:
DVB bitmap titl ide kroz `SubtitleControl`/`TDAL_GFX`, dok klasični teletext (Module 3:
"DVB also defines traditional teletext") ima svoju posebnu kontrolu
`iwedia.dtv.teletext.TeletextControl` i tip `com.iwedia.dtv.teletext.TeletextTrack`.
Za ovaj use-case (DVB subtitle) Teletext NIJE u fokusu, ali se referencira u dijagramu
kao "idle"/sused blok radi konzistentnosti sa Module 3 (Figure 6: "Subtitles: DVB SU, ATSC CC"
i "Interactive: TTX, MHEG-5, BML, HbbTV").

## Veza sa PDF lekcijama (kontekst, ne API)
- **Module 3** (Broadcast Standards): DVB subtitle = bitmap, layered preko videa,
  sinhronizovan sa PTS-om; razlika prema ATSC CC (text-based); PMT elementary-stream
  loop nosi i subtitle PID (uz video/audio PID-ove).
- **Module 5/6** (Host SW, HAL/PAL): subtitle decoding/rendering modul je hardverski
  blok (GFX/graphics accelerator) iza HAL-a; DTV SDK (Nexus-style) bi imao poseban
  `Subtitle`/`Graphics` interfejs analogan `VideoDecoder`/`Display`.
- **Module 7** (Middleware): u Comedia internoj arhitekturi (Figure 3), "TTX & Sub"
  je poseban blok koji koristi `TDAL GFX` i `TDAL PTSM` (PTS sync) — isti blok koji je
  ovde mapiran na `SubtitleControl`/`StreamComponentControl` na javnom liba4tv API nivou.

## Status dijagrama
- `block.puml` — urađeno. Reuse blokova iz `Zapping` (oni su <<idle>> jer su već
  aktivni od ranije: Service Control/Player/XSERV, MediaCodec/AudioTrack, VDEC/ADEC),
  dodati novi <<active>> blokovi: `Table` (PMT subtitle deskriptor), `TTX & Sub`,
  `TDAL_DMX` (subtitle PID channel), `TDAL_PTSM` (PTS sync, reuse iz Zapping),
  `TDAL_GFX` (graphics/bitmap plane), GPU/Display (composition/layering).
- `sequence.puml` / `sequence.mmd` — urađeno, u **v2 stilu** (isti izgled kao
  `Zapping/sequence-liba4tv-v2.puml`/`.mmd`): tri box grupe (Android app / Comedia
  liba4tv / Platform-HAL demonstration), `autonumber`. Implementation-level dio
  (`SubtitleControl`, `StreamComponentControl`, `DisplayControl`) sadrži samo tačne
  potpise iz javadoc-a. Demonstration-level dio (`Comedia CHAL - TDAL`, `Tuner SDK /
  Demux Filter`, `GPU / Display`) je eksplicitno markiran napomenama `demonstration:`.
  Tok: registracija listenera → `componentChanged(SUBTITLE)` callback → izlistavanje
  traka (`updateTrackCount`, `getSubtitleTrackCount`, `getSubtitleTrack`) → izbor trake
  (`setSubtitleType`, `setCurrentSubtitleTrack`) → opciono `setSubtitleMode` →
  `enableAutomaticSubtitleDisplay` → `DisplayControl.setGraficSurface` (+ demonstration
  rendering preko CHAL/Tuner-Demux/GPU) → opciono `deselectCurrentSubtitleTrack` →
  cleanup (`unregisterListener` x2).

## Mogući sledeći koraci
- Potvrditi u kodu stvarne nazive `getXxxControl()` akcesora na `IDTVManager`
  (trenutno pretpostavljeno po analogiji sa Service_instalation/Zapping).
- Ako zatreba, dodati zaseban use-case/dijagram za **Teletext** (`TeletextControl`,
  `TeletextTrack`) — pominje se ovde samo kao sused/idle blok.
- Ako zatreba OTT/broadband varijanta titla (WebVTT/TTML), to je poseban use-case
  van liba4tv/Comedia steka (vidi Module 4) i nije pokriven ovim folderom.
