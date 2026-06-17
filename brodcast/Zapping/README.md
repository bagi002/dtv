# Broadcast — Zapping

Use-case: **promjena kanala / aktivacija servisa** (zap). Korisnik bira servis iz liste;
TIF poziva `onTune`, Comedia uzima **live route**, asinhrono pokreće zapper task, otvara
izvor (TS fajl), parsira PAT/PMT da nađe PID-ove, otvara A/V kanale, alocira **MediaCodec**
(AVC/H.264) i **AudioTrack**, sinhronizuje preko **PCR/STC** i javlja „service started“.

## Fajlovi
- `block.mmd` / `block.puml` — arhitekturni block dijagram kroz slojeve **HAL / PAL / AL**.
- `sequence.mmd` / `sequence.puml` — UML sequence (konceptualno, termini iz PDF-a / Module 6 i 7).
- `sequence-liba4tv.mmd` / `sequence-liba4tv.puml` — **isti tok mapiran na konkretan iWedia liba4tv API**.

## Ključ (block dijagram)
- **Zeleno = aktivno** u live route-u: Service Control, Player, XSERV, Table, Service&Mux DB, TDAL_DMD/DMX/AV/DISP/PTSM, Tuner SDK, MediaCodec, AudioTrack, TUNER(file), DEMUX, VDEC, ADEC, GPU/Display.
- **Sivo (idle)**: Scan Control (koristi se samo pri instalaciji). Live route je „bogatiji“ od install route-a (Module 7).

## Glavni API pozivi (App → MW → A/V)
| Sloj | Poziv |
|------|-------|
| App → TIS | `onSetSurface(surface)`, `onSetStreamVolume(0.0)`, `onTune(uri)` |
| TIS → Comedia | `setCurrentServiceByTriplet(ONID, TSID, SID)` |
| Service → MAL | `routeManagerGetLiveRoute()` |
| MAL → MW | `MAL_SZ_StartTripletService(idx)` (async → `MAL_SZ_MainZapperTask`) |
| MW → CHAL | `TDAL_IP_DMD_Tune(file://...)` → `file_open()` |
| MW | `PI_EnableTsInformationAcquisition()`, `createChannel(SECTION)` (PAT/PMT) |
| MW | `XSERV_Instance setup` → `XSERV_Instance_SourceCallback(TSINFO_OK)` |
| MW → CHAL | `createChannel(PCR)` → STC/AVChannel (lipsync) |
| MW → MediaCodec | `createChannel(VIDEO, H.264)` → allocate AVC decoder |
| MW → AudioTrack | `createChannel(AUDIO)` → configure + start |
| MW → App (cb) | `MAL_SZ_NotifyServiceStarted` → „Service started“ |

## Bitne pouke (za implementaciju)
- **Asinhrono!** Poziv se brzo vrati; zap traje 2–3 s. App mora čekati **service-started event**, ne pretpostaviti da je playback odmah krenuo.
- **Triplet** (ONID/TSID/SID) je pouzdan identifikator servisa, ne ime kanala.
- **PCR PID** je timing referenca; **STC channel** poravnava PTS audija i videa (**lipsync**).
- `onSetStreamVolume(0.0)` na početku = mute dok se servis ne stabilizuje.
- Na demo (Nexus) nivou tok je: `NEXUS_Frontend_Tune` → `NEXUS_InputBand/ParserBand_SetSettings` → `NEXUS_PidChannel_Open` → `NEXUS_{Video,Audio}Decoder_Start`.

## Render
- Mermaid: VS Code preview, GitHub, ili https://mermaid.live.
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml.

## Mapiranje na konkretan liba4tv API (dokumentacija/liba4tv/doc)
Potpisi su provjereni u javadoc-u. Kontrole se dobijaju iz `IDTVManager`
(`DtvControl.context().dtvManager()`); paket `iwedia.dtv.*` su kontrole, `com.iwedia.dtv.*` su tipovi.

| Konceptualno (PDF, `sequence.*`) | Konkretno liba4tv (`sequence-liba4tv.*`) |
|---|---|
| dobavljanje kontrola | `IDTVManager` → `getComediaRouteManagerControl()` / `getServiceControl()` / `getStreamComponentControl()` / `getDisplayControl()` * |
| `routeManagerGetLiveRoute()` | `ComediaRouteManagerControl.getLiveRoute(int listIndex, int serviceIndex, int prevRouteId)` → `int liveRoute` |
| (opciono) detaljna konfiguracija rute | `BroadcastRouteControl.configureLiveRoute(int routeID, RouteLiveSettings settings)` — niži route control, nije dio routeManager toka |
| `onSetSurface(surface)` (gdje se crta video) | `DisplayControl.setVideoSurface(int layer, SurfaceBundle surface)` |
| (registracija na evente) | `ServiceControl.registerListener(ServiceListener)` |
| `setCurrentServiceByTriplet(ONID,TSID,SID)` | `ServiceControl.startServiceByTriplet(int routeID, int listIndex, int serviceIndex, int networkId, int transportStreamId, int serviceId)` |
| varijanta za file/IP izvor (Cuttlefish) | `ServiceControl.zapURL(int routeID, String url)` |
| `MAL_SZ_NotifyServiceStarted` / „service started" | `ServiceListener.channelChangeStatus(int liveRoute, boolean channelChanged, ServiceStateChangeError reason)` |
| „safe to show video" / unmute | `ServiceListener.safeToUnblank(int liveRoute)` |
| osnovne info o servisu (ime) | `ServiceControl.getServiceDescriptor(int listIndex, int serviceIndex)` / `getActiveService(int routeID)` |
| A/V komponente (promjena audio/titl track-a) | `StreamComponentControl.registerListener(StreamComponentListener)` → callback `StreamComponentListener.componentChanged(int, StreamComponentType)` |
| stop servisa | `ServiceControl.stopService(int routeID)` |
| ispod liba4tv (demo-level) | route → demux + dekoder (`TDAL_AV` → MediaCodec/AudioTrack) |

\* `getXxxControl()` akcesori su na `IDTVManager` (taj interfejs nije zaseban u ovom javadoc subset-u) — potvrditi u kodu.
