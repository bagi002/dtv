# OTT — Service Installation (catalog sync)

Use-case: **prvo punjenje / osvježavanje liste OTT servisa**. Za razliku od broadcast-a,
ovdje **nema tunera ni TS skeniranja** (nema PAT/PMT/SDT). OTT lista se **povlači sa servera**
kao **JSON/XML**, parsira u OTT klijentu i upisuje u **Android TV Provider**. Stream URL svakog
servisa se čuva uz kanal (`internalProviderData`) i koristi se kasnije pri zap-u.

## Open source umjesto Comedia
Comedia (iWedia liba4tv) je **broadcast** middleware i **ne koristi se za OTT**. OTT je IP izvor,
pa je cijeli tok mapiran na **open-source Android** komponente:
**TV Input Framework + HTTP klijent (OkHttp/Cronet/HttpURLConnection) + JSON/XML parser + Android TV Provider**.
Plejer (**ExoPlayer / AndroidX Media3**) se ovdje NE koristi — nema reprodukcije pri instalaciji
(vidi [Zapping](../Zapping/README.md), gdje je ExoPlayer centralni).

## Fajlovi
- `block.puml` — arhitekturni block dijagram kroz slojeve **HAL / PAL / AL**.
- `sequence-exoplayer-v2.puml` / `sequence-exoplayer-v2.mmd` — UML sequence: OTT client (gore) + Platforma/HAL (demonstration, dole).

## Ključ (block dijagram)
- **Zeleno = aktivno**: TV Input Framework, OTT TvInputService, OTT client, Metadata parser (JSON/XML), HTTP stack, TLS/SSL, Network (Wi-Fi/Ethernet), TV Provider.
- **Sivo (idle)**: ExoPlayer/Media3, MediaCodec/AudioTrack, TUNER, DEMUX, VDEC, ADEC — A/V lanac i tuner se NE koriste pri instalaciji.

## Glavni API pozivi (App → MW → mreža)
| Sloj | Poziv |
|------|-------|
| App → TIS | start install (npr. `EpgSyncJobService.requestImmediateSync(...)`) |
| TIS → OTT client | `fetchServiceCatalog(catalogUrl)` |
| OTT → HTTP | `HTTP GET https://.../services.json` (TLS) |
| HTTP → mreža | TCP/TLS preko Network HAL (Wi-Fi/Ethernet) |
| OTT → parser | `parse(body)` → `List<OttService>` (id, name, streamUrl, logo, opis) |
| TIS → TV Provider | `insert/update` u `TvContractCompat.Channels`; stream URL preko `InternalProviderData.setVideoUrl(streamUrl)` (→ `COLUMN_INTERNAL_PROVIDER_DATA`) |
| TIS → TV Provider | (opciono) `insert` u `TvContractCompat.Programs` (EPG) |
| TIS → App | install complete |

## Identifikatori
OTT servis se identifikuje **ID-jem iz kataloga servera** (npr. `serviceId` / `channelId`) i
**stream URL-om**, a ne broadcast tripletom (ONID/TSID/SID). Triplet ovdje ne postoji.

## Razlika u odnosu na broadcast scan
- **Broadcast**: tuner lock → section filteri (PAT/PMT/SDT) → otkrivanje servisa iz TS-a.
- **OTT**: HTTP GET kataloga → JSON/XML parse → lista servisa. Mreža zamjenjuje tuner/demux.

## Render
- Mermaid: VS Code preview, GitHub, ili https://mermaid.live.
- PlantUML: PlantUML ekstenzija u VS Code, ili https://www.plantuml.com/plantuml.

## Mapiranje na open-source Android API
| Konceptualno | Konkretno (open source) |
|---|---|
| pokretanje sync-a | `EpgSyncJobService.requestImmediateSync(...)` (TIF Companion Library, `com.google.android.media.tv.companionlibrary.sync`) / custom `JobService` |
| vraćanje liste kanala | `EpgSyncJobService.getChannels()` → `List<Channel>` (po kanalu `InternalProviderData.setVideoUrl(streamUrl)`) |
| vraćanje programa | `EpgSyncJobService.getProgramsForChannel(...)` → `List<Program>` |
| HTTP GET kataloga | `OkHttpClient.newCall(Request).execute()` / `Cronet` / `HttpURLConnection`; (alt. Media3 `DefaultHttpDataSource`) |
| TLS | platform SSL/TLS (HTTPS) |
| JSON parse | `Gson` / `Moshi` / `org.json.JSONObject` |
| XML parse | `XmlPullParser` |
| upis kanala | `ContentResolver.insert(TvContractCompat.Channels.CONTENT_URI, values)` |
| upis EPG-a | `ContentResolver.bulkInsert(TvContractCompat.Programs.CONTENT_URI, ...)` |
| stream URL uz kanal | `InternalProviderData.setVideoUrl(streamUrl)` → `TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_DATA` |

## Reference (Android open source dokumentacija)

Svi potpisi su provjereni na zvaničnoj Android/AOSP dokumentaciji (jun 2026):

- [Develop a TV input service](https://developer.android.com/training/tv/tif/tvinput) — `TvInputService`, `Session`, `onTune`, sync kanala.
- [Work with channel data](https://developer.android.com/training/tv/tif/channel) — `EpgSyncJobService.getChannels()`/`getProgramsForChannel()`, `InternalProviderData.setVideoUrl(...)`, mapiranje na `TvContractCompat.Channels` (`COLUMN_DISPLAY_NAME`, `COLUMN_DISPLAY_NUMBER`).
- [TvContractCompat](https://developer.android.com/reference/androidx/tvprovider/media/tv/TvContractCompat) i [TvContractCompat.Channels](https://developer.android.com/reference/androidx/tvprovider/media/tv/TvContractCompat.Channels) — `COLUMN_INTERNAL_PROVIDER_DATA` (stream URL uz kanal). AndroidX, open source.
- [TIF Companion Library — `EpgSyncJobService`](https://github.com/googlesamples/androidtv-sample-inputs/blob/master/library/src/main/java/com/google/android/media/tv/companionlibrary/sync/EpgSyncJobService.java) — paket `com.google.android.media.tv.companionlibrary.sync`, Apache-2.0.
- [Media3 — Media sources / DataSource](https://developer.android.com/media/media3/exoplayer/media-sources) — `DefaultHttpDataSource` (alternativa OkHttp/Cronet za povlačenje kataloga).
