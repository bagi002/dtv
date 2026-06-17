# Broadcast — Service Installation (Scan)

Use-case: **prvo skeniranje / instalacija servisa** iz broadcast izvora. U Cuttlefish-u
nema pravog tunera, pa se broadcast simulira **TS fajlom** (`file:///data/clear_0002.ts`).
Middleware otvara fajl kao izvor, gura TS pakete kroz demux, parsira tabele (PAT/PMT/SDT),
otkriva servise, upisuje ih u **Service DB** i u **Android TV Provider**.

## Fajlovi
- `block.mmd` / `block.puml` — arhitekturni block dijagram kroz slojeve **HAL / PAL / AL**.
- `sequence.mmd` / `sequence.puml` — UML sequence (konceptualno, termini iz PDF-a / Module 7).
- `sequence-liba4tv.mmd` / `sequence-liba4tv.puml` — **isti tok mapiran na konkretan iWedia liba4tv API**.

## Ključ (block dijagram)
- **Zeleno = aktivno** u install route-u: Scan Control, Table parser, Service&Mux DB, Setup, TDAL_DMD/DMX, Tuner SDK, TUNER(file), DEMUX.
- **Sivo (idle)** = postoji u stacku ali se NE koristi pri skeniranju: Service Control/Player/XSERV, MediaCodec/AudioTrack, VDEC/ADEC. Install route je namjerno jednostavniji od live route-a (Module 7).

## Glavni API pozivi (App → MW → CHAL)
| Sloj | Poziv |
|------|-------|
| App → TIS | start scan |
| TIS → Comedia | `startScanByUrl(url, append=false)` |
| Service → MAL | `routeManagerGetInstallRoute()` |
| MAL → MW | `MAL_TUNER_AutoScanURL()` |
| MW | `IPINSTALL_SetCurrentRoute` / `cmIPINSTALL_Initiate` / `cmIPINSTALL_SetScanningMode` |
| MW → CHAL | `TDAL_IP_DMD_Tune()` → `FindProtocol('file')` / `file_open()` |
| CHAL → MW (cb) | `IPINSTALL_DMD_StatusCallback(eDMD_LOCKED)` |
| MW → CHAL | `IPINSTALL_DMD_TdalTsConnect()` → `createChannel(SECTION)` |
| MW (cb) | `IPINSTALL_DMD_PI_ServiceListCallBack(ePI_OK)` (ONID/SID/ime) |
| MW (cb) | `TUNER_EVENT_INSTALL_PROGRESS` / `TUNER_EVENT_INSTALL_COMPLETE` |
| TIS → TV Provider | insert/update channels |

## Identifikatori
Servis je jedinstveno određen **tripletom**: **ONID** (Original Network ID), **TSID** (Transport Stream ID), **SID** (Service ID).

## Tabele koje se parsiraju
- **PAT** (PID 0): lista servisa → PMT PID-ovi.
- **PMT**: elementary streams servisa (video/audio/subtitle PID) + PCR PID + stream types.
- **SDT**: čitljiva imena servisa.

## Render
- Mermaid: VS Code Markdown/Mermaid preview, GitHub, ili https://mermaid.live (izvoz PNG/SVG).
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml (izvoz PNG/SVG).

## Mapiranje na konkretan liba4tv API (dokumentacija/liba4tv/doc)
Potpisi su provjereni u javadoc-u. Kontrole se dobijaju iz `IDTVManager`
(`DtvControl.context().dtvManager()`); paket `iwedia.dtv.*` su kontrole, `com.iwedia.dtv.*` su tipovi.

| Konceptualno (PDF, `sequence.*`) | Konkretno liba4tv (`sequence-liba4tv.*`) |
|---|---|
| dobavljanje kontrola | `IDTVManager` → `getComediaRouteManagerControl()` / `getScanControl()` / `getServiceControl()` * |
| `routeManagerGetInstallRoute()` | `ComediaRouteManagerControl.getInstallRoute(RouteManagerMediumType)` → `int routeID` (MEDIUM_IP za file/URL u Cuttlefish, MEDIUM_TER za terrestrial) |
| (registracija na evente) | `ScanControl.registerListener(ScanListener)` |
| `startScanByUrl(url, append=false)` | `ScanControl.appendList(false)` + `ScanControl.autoScan(int routeID, String url)` |
| `eDMD_LOCKED` | `ScanListener.tunerLocked(int id, boolean locked)` |
| `TUNER_EVENT_INSTALL_PROGRESS` | `ScanListener.scanProgressChanged(int routeId, int value)` + `installStatus(ScanInstallStatus)` |
| `PI_ServiceListCallBack` (servis nađen) | `ScanListener.installServiceTVName(int routeId, String name)` + `installServiceTVNumber(int routeId, int number)` |
| čitanje rezultata / Service DB | `ServiceControl.getServiceListCount(int listIndex)` + `getServiceDescriptor(int listIndex, int serviceIndex)` |
| `TUNER_EVENT_INSTALL_COMPLETE` | `ScanListener.scanFinished(int routeId)` |
| publish u TV Provider | Android `TvContract.Channels` (iz `TvInputService`) |
| prekid skena | `ScanControl.abortScan(int routeID)` / `isScanRunning()` |

\* `getXxxControl()` akcesori su na `IDTVManager` (taj interfejs nije zaseban u ovom javadoc subset-u) — potvrditi u kodu.
