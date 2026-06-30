 Više audio traka nad istim videom (A4TV / Comedia)

Smjernice za dodavanje opcije odabira i puštanja različitih audio traka
(npr. jedan jezik na jednoj, drugi na drugoj traci — kao što se vidi u VLC-u)
nad **istim video/audio stream-om** koji se već reprodukuje.

> Key ideja: video stream i ruta (`routeId`) ostaju isti.
> Mijenja se samo **koja audio traka svira** na toj istoj ruti, pozivom
> `AudioControl.setCurrentAudioTrack(routeId, index)`. Ne treba ponovo
> startovati kanal ni rekonfigurisati video.

---

## 1. Relevantni paketi i klase

| Klasa | Paket | Uloga |
|-------|-------|-------|
| `AudioControl` | `iwedia.dtv.audio` | Glavni kontroler — broj traka, dohvat, odabir |
| `AudioTrack` | `com.iwedia.dtv.audio` | Model jedne audio trake (jezik, tip, PID, ime) |
| `AudioListener` | `iwedia.dtv.audio` | Callback-ovi kad se audio promijeni |
| `AudioTrackType` | `com.iwedia.dtv.types` | Enum tipa trake (AUDIO, AUDIO_DESCRIPTION...) |
| `AudioDigitalType` | `com.iwedia.dtv.types` | Kodek (AC3, AAC, MP3, DTS, EAC3...) |
| `AudioChannelConfiguration` | `com.iwedia.dtv.types` | MONO / STEREO / MULTICHANNEL... |
| `BroadcastRouteControl` | `iwedia.dtv.route.broadcast` | Daje `routeId` (live route) |
| `StreamComponentControl` | `iwedia.dtv.streamcomponent` | (alternativa) lista SVIH komponenti stream-a |

---

## 2. Glavni pozivi — `AudioControl`

```java
// Dohvatanje kontrolera (preko DTVManager-a iz DTVServiceLocator-a)
AudioControl audio = dtvManager.getAudioControl();   // naziv getter-a uskladiti s tvojim managerom

int getAudioTrackCount(int routeId)                  // koliko traka ima trenutni kanal
AudioTrack getAudioTrack(int routeId, int trackIndex)// opis trake po indeksu
int  getCurrentAudioTrackIndex(int routeId)          // koja je traka trenutno aktivna
A4TVStatus setCurrentAudioTrack(int routeId, int trackIndex)  // <-- PREBACIVANJE TRAKE
A4TVStatus deselectCurrentAudioTrack(int routeId)    // isključi audio

A4TVStatus registerListener(AudioListener listener)
void       unregisterListener(AudioListener listener)
```

> `routeId` je **isti live route** koji već koristiš za prikaz kanala
> (`BroadcastRouteControl.getLiveRoute(frontendId, demuxId, decoderId)`).
> Ne praviš novu rutu za audio.

`setCurrentAudioTrack` vraća `A4TVStatus` — provjeri da li je uspjelo
(npr. `== A4TVStatus.SUCCESS`).

---

## 3. Model trake — `AudioTrack` (šta prikazati korisniku)

```java
int    getIndex()                 // indeks za setCurrentAudioTrack()
String getLanguage()              // npr. "eng", "srp" -> mapiraj u puni naziv jezika
String getName()                  // ime trake ako postoji
AudioTrackType getAudioTrackType()           // vrsta (vidi dolje)
AudioDigitalType getAudioDigitalType()       // kodek: AC3, AAC, MP3...
AudioChannelConfiguration getAudioChannleCfg() // MONO/STEREO/MULTICHANNEL
boolean isSelected()              // da li je ova traka trenutno odabrana
int    getPID()                   // PID u transport stream-u
```

`AudioTrackType` vrijednosti (za ikonicu / oznaku u meniju):
`AUDIO`, `DIALOG`, `COMENTARY`, `VOICEOVER`,
`AUDIO_DESCRIPTION` i `HEARING_IMPAIRED` (pristupačnost), `EMERGENCY`.

Prikaz reda u listi predlog:
`"{puniNazivJezika}  [{kodek}]  {STEREO/5.1}  {oznaka ako je AD/HI}"`
npr. `Engleski  [AC3]  5.1` ili `Bosanski  [AAC]  Stereo`.

---

## 4. Callback — `AudioListener`

```java
void audioChannelChanged(int routeId, int audioChannel);
void sampleRateChanged(int routeId, int sampleRate);
void typeChanged(int routeId, AudioDigitalType audioType);
```

Registruj listener na ekranu reprodukcije; kad stigne promjena,
ponovo učitaj listu (`getAudioTrackCount` + `getAudioTrack`) i osvježi UI
i markicu trenutne trake (`getCurrentAudioTrackIndex`).

Bitno: lista traka se mijenja **pri promjeni kanala**, pa listu uvijek
gradi nakon što se kanal stabilizuje (ili na otvaranje audio menija).

---

## 5. Tok implementacije (preporuka)

1. **Imaš routeId** — onaj koji već koristiš za live video/audio. Sačuvaj ga.
2. **Izgradi listu traka** (na otvaranje "Audio" menija):
   ```java
   List<AudioTrack> tracks = new ArrayList<>();
   int count = audio.getAudioTrackCount(routeId);
   for (int i = 0; i < count; i++) {
       tracks.add(audio.getAudioTrack(routeId, i));
   }
   int current = audio.getCurrentAudioTrackIndex(routeId);
   ```
3. **Prikaži listu** (RecyclerView / dijalog), označi `current` kao aktivnu;
   za svaki red prikaži jezik + kodek + kanale (poglavlje 3).
4. **Na odabir korisnika** prebaci traku:
   ```java
   AudioTrack chosen = tracks.get(position);
   A4TVStatus st = audio.setCurrentAudioTrack(routeId, chosen.getIndex());
   if (st == A4TVStatus.SUCCESS) {
       // osvježi markicu aktivne trake; video se NE prekida
   }
   ```
5. **Listener** registruj radi osvježavanja kad se audio promijeni ili
   promijeni kanal; odjavi ga u onStop/onDestroy.
6. **Persistencija (opciono):** zapamti `getLanguage()` zadnje odabrane trake
   i na novom kanalu auto-odaberi traku s istim jezikom ako postoji
   (ili iskoristi `setFirstAudioLanguage` / `setSecondAudioLanguage` za
   preferirani jezik na nivou sistema).

---

## 6. Skica koda (Android, sve na jednom mjestu)

```java
public class AudioTrackManager {

    private final AudioControl audio;
    private final int routeId;            // live route koji već koristiš

    public AudioTrackManager(AudioControl audio, int routeId) {
        this.audio = audio;
        this.routeId = routeId;
        audio.registerListener(listener);
    }

    /** Lista svih dostupnih audio traka trenutnog kanala. */
    public List<AudioTrack> getTracks() {
        List<AudioTrack> list = new ArrayList<>();
        int count = audio.getAudioTrackCount(routeId);
        for (int i = 0; i < count; i++) {
            list.add(audio.getAudioTrack(routeId, i));
        }
        return list;
    }

    public int getCurrentIndex() {
        return audio.getCurrentAudioTrackIndex(routeId);
    }

    /** Prebaci na drugu traku — video se ne prekida. */
    public boolean selectTrack(int trackIndex) {
        return audio.setCurrentAudioTrack(routeId, trackIndex) == A4TVStatus.SUCCESS;
    }

    private final AudioListener listener = new AudioListener() {
        @Override public void audioChannelChanged(int routeId, int audioChannel) { /* refresh UI */ }
        @Override public void sampleRateChanged(int routeId, int sampleRate)     { /* no-op */ }
        @Override public void typeChanged(int routeId, AudioDigitalType type)    { /* refresh UI */ }
    };

    public void release() {
        audio.unregisterListener(listener);
    }
}

/* Prikaz labela za jedan red u meniju: */
static String label(AudioTrack t) {
    StringBuilder sb = new StringBuilder();
    sb.append(t.getLanguage());                 // mapiraj ISO -> pun naziv
    sb.append("  [").append(t.getAudioDigitalType()).append("]");
    sb.append("  ").append(t.getAudioChannleCfg());
    if (t.getAudioTrackType() == AudioTrackType.AUDIO_DESCRIPTION) sb.append("  (AD)");
    if (t.getAudioTrackType() == AudioTrackType.HEARING_IMPAIRED)  sb.append("  (HI)");
    return sb.toString();
}
```

---

## 7. Alternativa: `StreamComponentControl` (kao VLC prikaz svih traka)

Ako želiš jedinstven prikaz SVIH komponenti stream-a odjednom
(video + sve audio + svi titlovi), koristi:

- `StreamComponentControl` + `StreamComponentListener`
- `StreamComponentType`: `VIDEO`, `AUDIO`, `SUBTITLE`, `TELETEXT`, `TTML`,
  `CLOSECAPTION`, `HBBTV`, `MHEG`, `BML`, `SUPERIMPOSE`
- `RouteStreamComponentsStatus` (status komponenti po ruti)

Za samo prebacivanje audia ovo nije nužno — `AudioControl` je jednostavniji
i namjenski put. `StreamComponent*` koristi kad praviš objedinjeni
"Tracks" panel (audio + titlovi + video info).

---

## 8. Uklapanje u POSTOJEĆI kod (TIF + Comedia connection)

Aplikacija je Android **TIF** (TvInputService) omotač oko Comedia/liba4tv
middleware-a. Middleware svira cijeli DVB servis; audio "krene sam" na
`liveRoute`. Track izbora trenutno NEMA. Konkretne tačke za kačenje:

**a) `liveRoute` = `routeId` iz ovog dokumenta.** Već se čuva kao
`mLiveRoute` u `ChannelZapper` (oko linije 32). Svi `AudioControl` pozivi
idu po toj ruti.

**b) Dodati `AudioControl` u `ComediaMiddlewareConnection`** po istom
obrascu kao postojeće kontrole (`ServiceControl`, `DisplayControl`,
`ComediaRouteManagerControl`): kreiranje u `onDtvAvailable`, nuliranje u
`onDtvUnavailable`, getter, i dopuniti `isAvailable()`. (Trenutno audio
kontrola NE postoji u konekciji.)

**c) Tajming — enumerisati tek kad su audio komponente parsovane.**
Listu traka gradi tek POSLE uspješnog `startServiceByTriplet` i, sigurnije,
nakon `safeToUnblank` / `channelChangeStatus` callback-a u `ChannelZapper`
(oko linije 90). Prije toga audio komponente možda nisu spremne.

**d) Track info NE postoji u bazi/scan-u.** `ScannedService` /
`ServiceDescriptor` nose samo onid/tsid/serviceId/name/sourceUrl — NEMA
audio PID-ova ni jezika. Trake se dobijaju isključivo **runtime-om** iz
`AudioControl` po `liveRoute`, ne iz baze.

### TIF integracija (ispravan UI put — umjesto custom menija)

Da se trake vide u standardnom Android TV UI-ju, `DtvTvInputSession` treba:

```java
// 1) Kad je kanal spreman (onSafeToUnblank / channelChanged):
List<TvTrackInfo> infos = new ArrayList<>();
int count = audio.getAudioTrackCount(liveRoute);
for (int i = 0; i < count; i++) {
    AudioTrack t = audio.getAudioTrack(liveRoute, i);
    infos.add(new TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, String.valueOf(t.getIndex()))
            .setAudioChannelCount(channelCount(t.getAudioChannleCfg()))
            .setLanguage(t.getLanguage())          // ISO kod
            .build());
}
notifyTracksChanged(infos);
notifyTrackSelected(TvTrackInfo.TYPE_AUDIO,
        String.valueOf(audio.getCurrentAudioTrackIndex(liveRoute)));

// 2) Kad korisnik izabere traku iz sistemskog UI-ja:
@Override
public boolean onSelectTrack(int type, String trackId) {
    if (type == TvTrackInfo.TYPE_AUDIO && trackId != null) {
        int idx = Integer.parseInt(trackId);
        if (zapper.selectAudioTrack(idx)) {        // delegira na AudioControl.setCurrentAudioTrack(liveRoute, idx)
            notifyTrackSelected(type, trackId);
            return true;
        }
    }
    return false;
}
```

`trackId` koristi `AudioTrack.getIndex()` kao stabilan ID — isti taj indeks
ide nazad u `setCurrentAudioTrack(liveRoute, idx)`.

`onSetStreamVolume` u sesiji je već "rezervisan ali neimplementiran"
(samo čuva `mStreamVolume`) — isti sloj je prirodno mjesto za audio izbor.

### Predložene izmjene po fajlu

| Fajl | Izmjena |
|------|---------|
| `ComediaMiddlewareConnection` | dodati `AudioControl` (kreiraj/nuliraj/getter/`isAvailable`) |
| `ChannelZapper` | izložiti `mLiveRoute`; dodati `selectAudioTrack(idx)` i enumeraciju traka; pozvati je na `safeToUnblank` |
| `DtvTvInputSession` | `notifyTracksChanged` + `notifyTrackSelected` na channelChanged; implementirati `onSelectTrack` |

### Preostalo provjeriti
- Threading: pozivi prema middleware-u van UI threada; `notify*` na glavnom.
- Mapiranje ISO jezik koda (`getLanguage()`) u pun naziv za prikaz.
- `AudioChannelConfiguration` → broj kanala za `setAudioChannelCount`.
