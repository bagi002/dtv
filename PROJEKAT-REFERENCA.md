# Projekat 2 – Referenca (izvučeno iz PDF modula 3–7)

> Ovaj fajl je sažetak svih bitnih informacija iz `PDF/` foldera (Module 3–7),
> povezan sa zahtjevima iz `zahtjevi.md` i uputstvom iz `upustvo.md`.
> Cilj: imati jedno mjesto sa svim što treba za izradu **Part 1 deliverable-a**
> (2 block dijagrama + UML sequence dijagrami) i kasniju implementaciju.
>
> Izvori po temama:
> - **Broadcast** detalji → `dokumentacija/` folder (Comedia / iWedia middleware)
> - **OTT** detalji → open-source Android dokumentacija (TIF, MediaPlayer/MediaCodec)

---

## 0. Šta je projekat (suština)

Treba napraviti **Android Live TV aplikaciju** koja radi u **Cuttlefish emulatoru (Android 16)**.
Aplikacija mora podržavati **zapping** (prelazak sa servisa na servis) preko liste servisa,
prikaz osnovnih info o servisu, i to za **dvije vrste servisa**:

1. **Broadcast servis** – simuliran preko **Transport Stream (.ts) fajla** (nema pravog tunera u Cuttlefish-u).
2. **OTT servis** – standardni Android playback sa URL-a; metapodaci se povlače sa servera (JSON/XML).

Tehnički, prave se **dva TV Input-a** (oba po TIF modelu):
- **Broadcast TV Input** → koristi **Comedia DTV Middleware** (scan, otkrivanje servisa, izbor servisa, kontrola playbacka).
- **OTT TV Input** → custom; Android media playback za stream + povlačenje/parsiranje metapodataka u logici samog TV Input-a.

### Tri glavna use-case-a (iz `zahtjevi.md`)
1. **Preuzimanje kanala** = *service installation / scan* (first-time install iz svih izvora).
2. **Mijenjanje kanala** = *zapping* (broadcast zap + OTT zap).
3. **Jedan dodatni use-case po izboru** – npr. **prikazivanje titlova (subtitles)** ili metadata handling.

---

## 1. Zahtjevi (high-level) — kontrolna lista

**Funkcionalni:**
- [ ] Zapper sa listom servisa i osnovnim info o servisu
- [ ] Podrška za Broadcast **i** OTT servise
- [ ] First-time instalacija servisa iz svih izvora
- [ ] Integrisano u Android Live TV App workflow

**Tehnički:**
- [ ] Dva TV Input-a – Broadcast i OTT
- [ ] Svaki TV Input potpuno podržava **TIF** arhitekturu
- [ ] Broadcast TV Input koristi **Comedia DTV MW** servis
- [ ] OTT TV Input je custom implementacija sa **MediaPlayer**-om
- [ ] Metapodaci se prikupljaju i koriste za osnovne info (ime servisa; opis za OTT)
- [ ] Demo kao Android 16 app u **Cuttlefish** emulatoru

---

## 2. Part 1 – ŠTA SE TAČNO PREDAJE (deliverables)

Iz Module 7 ("Project Deliverables") + `upustvo.md`:

**Part 1 (dizajn / arhitektura):**
1. **Architecture block diagram** – SW blokovi kroz **sve slojeve: HAL, PAL, AL**, popunjeni blokovima koji se stvarno koriste u rješenju.
   - Po `upustvo.md`: **2 block dijagrama → jedan za Broadcast, jedan za OTT.**
2. **UML sequence dijagrami** – fokus na **API pozive** (ne više samo dataflow kao u Projektu 1).
   - HAL i platform sloj: **demonstration-level** (ne treba svaki low-level poziv).
   - App i middleware dio: **implementation-level** (to se stvarno kodira).
   - **Minimum dijagrama:**
     - Service installation (scan)
     - Broadcast zapping
     - OTT zapping
     - **+1 po izboru** (npr. titlovi / metadata handling)

**Part 2 (implementacija):** ažuriran dizajn + isti dijagrami + izvorni kod usklađen sa dizajnom + radni **APK** + **živi demo**.
> Pravilo: arhitektura nije dekorativna — **ona vodi kod**.

---

## 3. Kompletni Host SW stack (osnova za OBA block dijagrama)

Ovo je "master" stack (Module 5, Figure 3 + Module 7, Figure 1). Iz njega se izvlače
i Broadcast i OTT block dijagram – samo se popune blokovi koji se za taj slučaj koriste.

```
┌─────────────────────────────────────────────────────────────────────┐
│ APPLICATIONS:  Live TV App | VoD | Netflix | HBO Max | ExoPlayer ...  │
├─────────────────────────────────────────────────────────────────────┤
│ MIDDLEWARE / AL:                                                      │
│   App runtime (ART/Android framework: Activity Mgr, Content Provider, │
│                Resource/Package/Notif Mgr, Jetpack)                   │
│   DTV Middleware (TV Input Framework, TV Input Manager, TV Provider,  │
│                   Comedia / OTT client / IPTV client)                 │
│   Media Player (Android MediaPlayer)                                  │
│   UI Frameworks (Jetpack Compose + Kotlin, React, Flutter ...)        │
├─────────────────────────────────────────────────────────────────────┤
│ PLATFORM / PAL / OS:                                                  │
│   OS-in-the-small (Android Runtime / Linux Kernel)                    │
│   Common libs (OpenGL, DirectFB, SSL, FreeType, WebKit)              │
│   DTV SDK (Android Tuner SDK; vendor: Nexus / RDK / Airoha)           │
│   A/V SDK (MediaCodec, AudioTrack; Linux: FFmpeg, GStreamer)         │
│   CA SDK (MediaCAS), Other SDKs                                       │
├─────────────────────────────────────────────────────────────────────┤
│ DRIVERS / HAL:                                                        │
│   TUNER | DEMUX | CAS | VDEC | ADEC | GPU | HMI | ACCEL | I/O         │
└─────────────────────────────────────────────────────────────────────┘
```

**Ključno pitanje koje svaki sloj odgovara** (koristi za opravdanje dizajna):
- **HAL**: "Šta hardver MOŽE da radi?" (tune, filter PID, decode, compose)
- **PAL / SDK**: "Šta PROGRAMER želi?" (objekti: InputBand, ParserBand, PidChannel, VideoDecoder...)
- **Middleware (AL)**: "Šta APLIKACIJA / KORISNIK želi?" (changeChannel, startScan, startPlayback)
- **Application**: krajnji user experience.

Za Android konkretno (Module 6, Figure 3): svaki framework ima Java API → (JNI) → Native API → HAL → vendor SDK:
- **Tuner SDK**: Tuner Java API → Tuner Native API → (TRM, Tuner HAL) → Nexus
- **MediaCodec**: MediaCodec Java API → MediaCodec Native API
- **AudioTrack**: AudioTrack Java API → AudioTrack Native API
- **MediaCAS**: MediaCAS Java API → CAS Client Native API → CAS Client Plugin → CA SDK
- **JNI** = most između Java/Kotlin gornjeg svijeta i C/C++ donjeg (NDK).
- **TRM (Tuner Resource Manager)** = postoji jer je Android multi-app: više klijenata traži tuner resurs.

---

## 4. BLOCK DIJAGRAM #1 — BROADCAST (koji blok u kom sloju)

| Sloj | Blokovi za Broadcast slučaj |
|------|------------------------------|
| **Application** | Live TV App |
| **AL / Middleware** | TV Input Framework (TIF), TV Input Manager, **TV Provider** (DB kanala/programa), **Broadcast TV Input (TIS)**, **Comedia** (DTV MW): Comedia API → Comedia MW (core) → Comedia CHAL (glue) |
| **PAL / Platform** | **Android Tuner SDK** (Tuner Java/Native API, TRM, Tuner HAL), **MediaCodec**, **AudioTrack**, (vendor) **Nexus**, CA SDK |
| **HAL / Drivers** | TUNER (u Cuttlefish: simuliran iz .ts fajla), DEMUX, VDEC, ADEC, GPU, (CAS) |

**Comedia interna struktura (Module 7, Figure 3) — bitno za middleware dio dijagrama:**
- **Comedia API**: Scan Control, Service Control (+ Player), EPG Control, Reminder, PVR Control, App Engines, **Route (XSERV)**
- **Comedia MW (core)**: Setup, **Service & Mux Database (SList + PIDB)**, Event Info (EIM), Carousel (DSM-CC), **Table** (PSI/SI parser), **TTX & Sub** (teletekst/titlovi)
- **Comedia CHAL (glue/TDAL)**: TDAL FLA, **TDAL DMD** (demod/frontend), **TDAL DMX** (demux), **TDAL AV**, TDAL DISP, TDAL GFX, **TDAL PTSM** (PTS/sync)
- Comedia je **Android Service** → Broadcast TV Input se veže preko **Binder**-a.
- **Xserv** = orkestrator: apstraktni izbor servisa pretvara u konkretan signal path (tune→demux→PIDs→A/V dekoderi→display→sync).

**Tok podataka (broadcast):** TS fajl/tuner → DEMUX (filtriranje PID-ova) → [CAS descrambling ako treba] → VDEC + ADEC → GPU/compose → ekran/zvučnici.

---

## 5. BLOCK DIJAGRAM #2 — OTT (koji blok u kom sloju)

| Sloj | Blokovi za OTT slučaj |
|------|------------------------|
| **Application** | Live TV App (isti unified UI) |
| **AL / Middleware** | TV Input Framework (TIF), TV Input Manager, TV Provider, **OTT TV Input** (custom), **OTT client** (povlači metadata: REST/JSON/XML, gradi listu servisa), **Android MediaPlayer** |
| **PAL / Platform** | **HTTP/TCP** stack (+QUIC), **MediaCodec**, **AudioTrack**, OS common libs (SSL), (DRM: Widevine/PlayReady/FairPlay) |
| **HAL / Drivers** | VDEC, ADEC, GPU, I/O (mreža) — **nema tunera/demuxa** |

**Razlike u odnosu na broadcast (Module 4):**
- Nema TUNER/DEMUX/PSI-SI; umjesto toga **HTTP adaptive streaming**.
- Metapodaci dolaze **odvojeno** sa servera (REST API: GET SERVICES, GET SERVICE INFO, LOG IN/OUT) kao **JSON/XML**, i kroz **manifest/playlist**:
  - **HLS → M3U8 playlist**
  - **MPEG-DASH → MPD** (Periods → Adaptation sets → Representations)
- Container: **fMP4 / CMAF**, WebM/Matroska (a moguć i MPEG-TS u fajl formi).
- Zaštita: **DRM + CENC** (Common Encryption), licenca sa license servera.
- Titlovi: **WebVTT / TTML** (tekstualni, ne bitmap).
- Za projekat: Android već radi adaptive streaming — **ne treba ga sami implementirati**; fokus je na metapodacima i integraciji u TIF.

**Tok podataka (OTT):** HTTP(S) GET (MPD/M3U8 + fragmenti) → [DRM decrypt CENC] → MediaCodec/AudioTrack → GPU → ekran/zvučnici.

---

## 6. UML SEQUENCE DIJAGRAMI (sa konkretnim API pozivima)

> Lifelines i pozivi su preuzeti iz Module 6 (Figure 4–7) i Module 7 (Figure 4–5).
> Ne moraju biti 1:1 kopija — bitno je razumjeti tok i mapirati na svoje rješenje.

### 6.1 Service Installation / Scan (broadcast) — Module 7, Fig. 4
Lifelines: **LiveTV App → Broadcast TV Input (TIS) → Comedia API (Service) → Comedia API (MAL) → Comedia MW → Comedia CHAL**

```
User: Initiate scan
TIS:  startScanByUrl("file:///data/clear_0002.ts", append:false)   // append:false = obriši staru listu
MAL:  routeManagerGetInstallRoute()                                 // install route (input+demux, bez A/V)
MAL:  MAL_TUNER_AutoScanURL()
MW:   IPINSTALL_SetCurrentRoute(...) → cmIPINSTALL_Initiate() → cmIPINSTALL_SetScanningMode()
CHAL: TDAL_IP_DMD_Tune() → FindProtocol('file') → file_setup() → file_open('/data/clear_0002.ts')
CHAL→MW: IPINSTALL_DMD_StatusCallback(eDMD_LOCKED)                  // "lock" iako nema RF
MW→TIS→App: TUNER_EVENT_INSTALL_PROGRESS
MW:   IPINSTALL_DMD_GetLocalServiceInfo()
MW:   IPINSTALL_DMD_TdalTsConnect() → createChannel(SECTION) (SoftwareDmxChannel)
MW:   parse PAT → PMT → SDT                                         // koje usluge, koji PIDs, imena
MW→TIS→App: PI_ServiceListCallBack(ePI_OK)  (ONID, SID, ime servisa)
MW:   upiši servis u Service Database
...   IPINSTALL_DMD_ContinueInstall / ScanContinue (ima li još servisa)
CHAL: file_close() → file_teardown() → Interface UNLOCKED → TdalTsDisconnect()
MW:   routeManagerReleaseRoute()
MW→TIS→App: TUNER_EVENT_INSTALL_COMPLETE → "Scan complete"
```
Bitno: po otkrivanju, TIS upisuje servise i u **Android TV Provider**.

### 6.2 Broadcast Zapping — Module 7, Fig. 5 (+ Module 6 Fig. 4 i 5)
Lifelines: **LiveTV App → Broadcast TV Input (TIS) → Comedia (Service/MAL/MW/CHAL) → MediaCodec/AudioTrack**

```
User: Play channel 1
TIS:  onSetSurface(surface)            // app daje video surface (gdje se crta)
TIS:  onSetStreamVolume(0.0)           // mute tokom switcha dok servis ne postane stabilan
TIS:  onTune(content://.../channels/1) // "zap"/aktivacija servisa (NIJE samo RF tune)
TIS:  setCurrentServiceByTriplet(ONID, TSID, SID)   // pouzdan identifikator = triplet
MAL:  routeManagerGetLiveRoute()       // live route = input+demux+A/V+sync+display
MAL:  MAL_SZ_StartTripletService(idx)  // ASINHRONO → MAL_SZ_MainZapperTask (svoj thread)
CHAL: TDAL_IP_DMD_Tune(file://...) → file_open(...)   // otvori izvor (TS fajl)
MW:   PI_EnableTsInformationAcquisition()
MW:   createChannel(SECTION) za PAT/PMT (+ EIT, + CAS/EMM ako scrambled)
MW:   XSERV_Instance setup → SourceCallback(TSINFO_OK)  // Xserv orkestrira
MW:   "components ready (PCR pid: 49)" → Create AV channels
MW:   createChannel(PCR) → AVChannel setup (output: DECODER)
MW:   createChannel(VIDEO, H.264) → MediaCodec: allocate AVC video decoder
MW:   createChannel(AUDIO) → AudioTrack
MW→...→App: MAL_SZ_NotifyServiceStarted → "Service started" → Playback active
```
Bitne pouke za kod:
- **Asinhrono!** Poziv se brzo vrati, rezultat stiže preko callback-a; zap traje 2–3 s → čekaj **service-started event**.
- **PCR PID** = timing referenca; **STC channel** sinhroniše audio/video (lipsync) — Module 6: `NEXUS_StcChannel_SetSettings`.
- Na nivou Nexus-a (demo-level, Module 6 Fig.5): `NEXUS_Frontend_Tune` → `NEXUS_InputBand_SetSettings` → `NEXUS_ParserBand_SetSettings` → `NEXUS_PidChannel_Open` → `NEXUS_VideoDecoder_Start` / `NEXUS_AudioDecoder_Start`.

### 6.3 OTT Zapping — izvedeno iz Module 4 (+ TIF onTune)
Lifelines: **LiveTV App → OTT TV Input → OTT client → MediaPlayer → (HTTP server / DRM license server)**

```
User: izaberi OTT servis
TIS:  onSetSurface(surface) → onTune(ottServiceUri)
OTT client: GET service metadata (REST) → parse JSON/XML → ime + opis servisa
OTT client: download manifest (MPD / M3U8)
MediaPlayer: setDataSource(url) / setSurface(surface)
MediaPlayer: izbor Representation-a po bandwidth-u → HTTP GET fragmenti (CMAF/fMP4)
(opц.) DRM: getKeyRequest → license server → provideKeyResponse → decrypt CENC
MediaPlayer: prepare() → start()  → MediaCodec/AudioTrack dekodiraju
MediaPlayer→TIS→App: onPrepared / "Playback active"
```
Napomena: Android sam radi adaptive streaming; ti radiš metadata + integraciju u TIF.

### 6.4 +1 Use-case po izboru — PRIJEDLOG: Titlovi (Subtitles)
- **Broadcast:** DVB subtitles su **bitmap** (renderuju se preko slike). U Comedia core: modul **TTX & Sub** (selective PES parsing) + **PTS management (TDAL PTSM)** za sync.
  - Sekvenca: onTune (servis aktivan) → korisnik odabere subtitle track → MW: `createChannel(SECTION/PES)` za subtitle PID (iz PMT) → parse subtitle PES → render bitmap preko GPU/compose, sinhronizovan po PTS.
- **OTT:** titlovi su **tekstualni — WebVTT / TTML**, poseban fajl koji se download-uje i renderuje lokalno.
  - Sekvenca: OTT client uzme subtitle URL iz manifesta (MPD/M3U8) → HTTP GET .vtt → parse cue (tekst+timing) → render preko playera po vremenu.
- Alternativa za +1: **metadata handling** (povlačenje EPG/EIT za broadcast ili service descriptions za OTT).

---

## 7. Ključne tehničke činjenice po temama (reference za callouts na dijagramima)

### 7.1 Broadcast lanac (Module 3)
- **Transmisija**: Terrestrial DVB-T/T2, ATSC, ISDB-T, DTMB; Satellite DVB-S/S2; Cable DVB-C/C2, OpenCable.
- **Container**: **MPEG-TS** (TS paket = **188 bajta**, sync byte **0x47**, **13-bit PID**; 204 sa Reed-Solomon) i **PES** (Packetized Elementary Stream, decodable unit).
- **Timing**: **PCR** (Program Clock Reference, u adaptation field, PCR PID); **PTS** (Presentation Time Stamp), **DTS** (Decoding Time Stamp). Sync prihvatljiv ~150 ms.
- **Metapodaci (PSI/SI tabele)** — kako receiver sazna šta je u streamu:
  - **PAT** (PID = **0**): index, lista servisa → pokazuje na PMT PID-ove (+ NIT PID).
  - **PMT**: detalji jednog servisa → PCR PID + elementary streams (video/audio/subtitle PID + stream types) + CA descriptor.
  - **SDT**: čitljiva imena servisa.
  - **EIT**: raspored programa → EPG.
  - **CAT** (PID = **1**): pokazuje na EMM (conditional access).
  - **NIT**: info o frekvencijama/mreži. **AIT**: interaktivne app (HbbTV).
- **Video codec**: MPEG-2, H.264/AVC, H.265/HEVC, H.266/VVC (GOP → I/P/B frame → macroblock → motion vector + residual).
- **Audio codec**: AAC, HE-AAC, AC-3, Dolby, DTS.
- **Subtitles**: DVB bitmap subtitles, teletext; ATSC closed captions (tekst).
- **Conditional Access**: DVB-CSA; proprietary (NAGRA, Irdeto, Verimatrix...). Lanac: **User key → (EMM) Service key → (ECM) Control word → descrambler**. ECM/EMM u PSI sekcijama.
- **SoC**: Broadcom, MediaTek, Amlogic (HW demux, decode, descramble).

### 7.2 Broadband / OTT lanac (Module 4)
- **Managed IPTV** vs **OTT (non-managed)**.
- **IPTV protokoli**: IP multicast + **IGMP**, **UDP** (live, nepouzdan), **TCP** (VoD, pouzdan), **RTP** (timing/sync nad UDP), **RTSP** (play/pause/resume), **SNMP/CWMP(TR-069)** (management).
- **OTT protokoli**: **HTTP(S)** nad TCP (ili QUIC nad UDP); **HLS** i **MPEG-DASH**.
- **Manifest**: HLS→**M3U8**, DASH→**MPD** (Period → Adaptation set → Representation = quality varijante).
- **Container OTT**: **fMP4/CMAF**, WebM/Matroska.
- **Codec OTT**: AVC, HEVC, VVC + web: **VP9, AV1**; audio AAC/HE-AAC/AC-3.
- **Subtitles OTT**: **WebVTT, TTML/IMSC**.
- **Zaštita OTT**: **DRM** (Widevine, PlayReady, FairPlay) + **CENC**.
- **Metapodaci**: REST API (GET SERVICES, GET SERVICE INFO, LOG IN/OUT) → **JSON/XML**.

### 7.3 SW slojevi (Module 5 & 6)
- **HAL/Drivers**: izlažu hardver (memory-mapped registri; eksterni preko I2C/SPI). Linux: `open/close/ioctl/read/write`. Naziv modula = naziv hardvera (tuner driver, demux driver...).
- **PAL/Platform**: "OS in the small" (threads, procesi, memorija, fajlovi) + common libs + **DTV SDK** + **A/V SDK** + CA SDK.
  - **DTV SDK = Nexus** (Broadcom); ispod njega **Magnum** (hidden). Objekti čine **filter graph**: **InputBand → ParserBand → PidChannel → VideoDecoder/AudioDecoder → Display/AudioDac/SPDIF**, + **StcChannel** za timing.
  - **Linux DVB API** postoji ali je slaba apstrakcija (bliža HAL-u).
  - **A/V SDK**: Android **MediaCodec** (video) + **AudioTrack** (audio); Linux **FFmpeg/GStreamer**.
- **AL/Middleware**: "šta korisnik želi". DTV MW radi: channel scan, service DB, parsiranje tabela, state, playback. Android: **TIF**; iWedia: **Comedia**. OTT/IPTV: client-side middleware.
- **Producer-consumer pattern** (važno za sekvence): konfiguriši pipeline → start producer → `onFilterEvent` → `read`/`queue` → release/ack. HAL↔framework razmjena preko **FMQ (Fast Message Queue)**.

### 7.4 Android TIF specifično (Module 7)
- **TV Input Framework (TIF)**: svi TV servisi izgledaju ujedinjeno; svaki TV Input daje svoje servise i reaguje na komande **TV Input Manager**-a.
- **onTune** = aktivacija servisa (ne nužno RF tune) — radi za broadcast freq / IPTV stream / OTT URL.
- **TV Provider**: Android DB kanala/programa; TV Input mora upisati otkrivene servise tu.
- **onSetSurface** (gdje crtati video), **onSetStreamVolume** (mute tokom zap-a).
- Comedia se veže kao **Android Service preko Binder-a**; Java API → **JNI** → native (MAL) → core → **CHAL** (glue) → platforma.

---

## 8. Cheat-sheet: API pozivi po sloju (za "callouts" na UML dijagramima)

| Sloj | Broadcast | OTT |
|------|-----------|-----|
| **App → MW (AL)** | `startScanByUrl`, `onTune`, `setCurrentServiceByTriplet`, `onSetSurface`, `onSetStreamVolume` | `onTune(uri)`, `onSetSurface`, REST: `getServices`, `getServiceInfo`, `login` |
| **MW core** | `routeManagerGet{Install,Live}Route`, `MAL_SZ_StartTripletService`, `PI_EnableTsInformationAcquisition`, `createChannel(PAT/PMT/PCR/VIDEO/AUDIO)`, `XSERV_*` | parse JSON/XML, parse MPD/M3U8, izbor Representation |
| **PAL / SDK** | Tuner SDK: `Tuner.tune`, `Tuner.openFilter`, `Filter.configure/start/read`; Nexus: `NEXUS_Frontend_Tune`, `NEXUS_InputBand/ParserBand_SetSettings`, `NEXUS_PidChannel_Open`, `NEXUS_StcChannel_SetSettings`, `NEXUS_{Video,Audio}Decoder_Start`; `MediaCodec`, `AudioTrack` | `MediaPlayer.setDataSource/prepare/start`, `MediaCodec`, `AudioTrack`, DRM `getKeyRequest/provideKeyResponse` |
| **HAL** | `IFrontend.tune`, `IDemux.openFilter`, `IFilter.configure/start`, `onFilterEvent`, FMQ `read` | `MediaCodec`/`AudioTrack` native, mrežni I/O |

---

## 9. Mapiranje izvora (gdje tražiti dalje)

- **Broadcast** (Comedia API/MW/CHAL, TDAL\*, IPINSTALL\*, MAL\_SZ\*, XSERV, service triplet) → **`dokumentacija/` folder** (za sada NE gledati po uputstvu; tu su detalji Comedia/iWedia).
- **OTT** (TIF, TvInputService, MediaPlayer/MediaCodec, MediaDrm, MPD/M3U8, WebVTT) → **open-source Android dokumentacija**.

---

## 10. Sljedeći koraci (predloženi redoslijed rada)

1. Nacrtati **block dijagram BROADCAST** (sekcija 4) i **block dijagram OTT** (sekcija 5) — popuniti HAL/PAL/AL.
2. Nacrtati 4 **UML sequence** dijagrama (sekcija 6): install, broadcast zap, OTT zap, +titlovi.
3. Na strelicama dodati **callouts** sa standardom/formatom/API pozivom (sekcija 8) — to traži uputstvo.
4. Tek onda implementacija (Part 2): 2 TV Input-a (Broadcast→Comedia, OTT→custom+MediaPlayer), TV Provider upis, demo u Cuttlefish.
