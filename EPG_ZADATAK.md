# Hackaton zadatak: EPG podrška u Broadcast TV Input Servisu

## Zahtev

Proširiti postojeće rešenje Broadcast TV Input servisa podrškom za prikaz **EPG podataka (elektronskog programskog vodiča)**.

## Dve grupe EPG podataka

1. **Present-Following (PF)** — trenutni i naredni program za svaki kanal
2. **Schedule (SC)** — kompletne programske šeme za 7 narednih dana

## Verifikacija

EPG podaci moraju biti vidljivi u **LiveTv aplikaciji** (Android sistem) — to je jedina validacija uspeha.

---

## Kritična napomena: Adaptacija vremena

> Zbog emulacije fizičkog tunera čitanjem `.ts` fajlova, EPG vremena su u **prošlosti** (vreme kreiranja `.ts` fajla). LiveTv aplikacija i TIF prikazuju **samo sadašnje i buduće** programe.

### Rešenje: Time-shift svih evenata

- Izračunati vremenski offset: `offset = System.currentTimeMillis() - startTime_najranijeg_eventa_u_ms`
- Pomeriti **SVE** evente za isti offset unapred
- **Redosled i relativni vremenski razmaci između programa moraju ostati nepromenjeni**
- Konverzija `TimeDate` → ms: `timeDate.getCalendar().getTimeInMillis()`
- Kreiranje pomerenog `TimeDate`: `new TimeDate(new Date(originalMs + offset))`

---

## API referenca

### `iwedia.dtv.epg.EpgControl`

Nasleđuje `DtvControl<com.iwedia.dtv.epg.IEpgControl>`. Kreira se kao `new EpgControl(dtvContext)`.

| Povratni tip | Metoda | Opis |
|---|---|---|
| `int` | `createEventList()` | Kreira filter ID za upite; mora se pozvati pre ostalih poziva. Vraća filterID. |
| `A4TVStatus` | `releaseEventList(int filterID)` | Oslobađa filter po završetku — obavezno! |
| `A4TVStatus` | `setFilter(int filterID, EpgFilter filter)` | Postavlja filter (servis, vreme, žanr...) na filterID |
| `A4TVStatus` | `startAcquisition(int filterID)` | Pokreće akviziciju SC podataka |
| `A4TVStatus` | `stopAcquisition(int filterID)` | Zaustavlja akviziciju |
| `EpgEvent` | `getPresentFollowingEvent(int filterID, int serviceIndex, EpgEventType type)` | Čita PF event (PRESENT ili FOLLOWING) |
| `EpgEvent` | `getRequestedEvent(int filterID, int serviceIndex, int eventIndex)` | Čita SC event po indeksu |
| `int` | `getAvailableEventsNumber(int filterID, int serviceIndex)` | Broj dostupnih SC evenata za servis |
| `A4TVStatus` | `createWindow(EpgMasterList list, TimeDate windowStartTime, int windowDuration)` | Otvara SC vremenski prozor; `list` drži indekse servisa, `windowDuration` u minutima |
| `String` | `getEventExtendedDescription(int filterID, int eventId, int serviceIndex)` | Vraća prošireni opis programa |
| `A4TVStatus` | `registerListener(EpgListener listener, int filterId)` | Registruje callback za EPG promene |
| `void` | `unregisterListener(EpgListener listener)` | Odjavljuje listener |
| `void` | `registerEPGDataCallback()` | (interno; obično nije potrebno direktno zvati) |
| `int` | `collectPresentFollowingCurrentMultiplex(int filterID)` | Skuplja PF sa trenutnog multipleksa |

### `iwedia.dtv.epg.EpgListener` (interface)

```java
void pfEventChanged(int filterID, int serviceIndex)        // PF event promenjen na lajvu
void pfAcquisitionFinished(int filterID, int serviceIndex) // PF akvizicija završena
void scEventChanged(int filterID, int serviceIndex)        // novi SC event pristigao
void scAcquisitionFinished(int filterID, int serviceIndex) // SC akvizicija završena
void tdtChanged(int filterID, int serviceIndex)            // TDT (vreme iz streama) se promenilo
void pvrPfEventChanged(int filterID, int serviceIndex)     // PVR PF promena
void pvrPfAcquisitionFinished(int filterID, int serviceIndex)
```

### `com.iwedia.dtv.epg.EpgEvent` (Parcelable)

Ključni getteri:

```java
String   getName()          // naziv programa
String   getDescription()   // kratak opis
TimeDate getStartTime()     // vreme početka
TimeDate getEndTime()       // vreme kraja
int      getEventId()       // jedinstveni ID eventa
int      getServiceIndex()  // MW serviceIndex
String   getAudioLanguage() // jezik
int      getGenre()         // žanr kod
int      getParentalRate()  // roditeljska zaštita
```

### `com.iwedia.dtv.epg.EpgEventType` (enum)

```java
EpgEventType.PRESENT_EVENT   // trenutni program
EpgEventType.FOLLOWING_EVENT // sledeći program
EpgEventType.SCHEDULE_EVENT  // program iz rasporeda (SC)
```

### `com.iwedia.dtv.epg.EpgServiceFilter`

Nasleđuje `EpgFilter`. Filtrira evente po serviceIndex-u.

```java
EpgServiceFilter filter = new EpgServiceFilter();
filter.setServiceIndex(serviceIndex);
epgControl.setFilter(filterID, filter);
```

### `com.iwedia.dtv.epg.EpgTimeFilter`

Filtrira SC evente po vremenskom opsegu.

```java
EpgTimeFilter filter = new EpgTimeFilter();
filter.setTime(startTimeDate, endTimeDate);
```

### `com.iwedia.dtv.epg.EpgMasterList`

```java
// Prima ArrayList<Integer> indeksa servisa za koje se otvara SC prozor
EpgMasterList masterList = new EpgMasterList(new ArrayList<>(Arrays.asList(serviceIndex)));
epgControl.createWindow(masterList, windowStartTime, durationMinutes);
```

### `com.iwedia.dtv.types.TimeDate`

**Pažnja: meseci 1-12 (ne Java 0-11)!**

```java
// Konstruktori
new TimeDate(int sec, int min, int hour, int day, int month, int year)
new TimeDate(Date date)              // iz Java Date
new TimeDate(Date date, long offset) // offset u ms
new TimeDate(TimeDate date, long offset)

// Getteri
getSec(), getMin(), getHour(), getDay(), getMonth(), getYear()
getCalendar()    // Calendar (lokalno vreme)
getCalendarUTC() // Calendar (UTC)

// Statika
TimeDate.fromCalendar(Calendar calendar)
```

---

## Integracija sa Android TvProvider

EPG podaci se upisuju u `TvContract.Programs`.

```java
ContentValues values = new ContentValues();
values.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
values.put(TvContract.Programs.COLUMN_TITLE, event.getName());
values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, event.getDescription());
values.put(TvContract.Programs.COLUMN_LONG_DESCRIPTION, extendedDesc); // getEventExtendedDescription(...)
values.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, shiftedStartMs);
values.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, shiftedEndMs);
// Insert:
contentResolver.insert(TvContract.Programs.CONTENT_URI, values);
```

Query programa za kanal: `TvContract.buildProgramsUriForChannel(channelId)`

---

## Postojeća arhitektura projekta

| Fajl | Uloga |
|------|-------|
| `DtvTvInputService.java` | TIS koordinator; kreira sesije, MW konekciju, scanner |
| `DtvTvInputSession.java` | Zapping sesija; prima `onTune`, kontroliše audio trake |
| `ComediaMiddlewareConnection.java` | Drži sve Comedia Control instance; **ovde dodati `EpgControl`** |
| `ChannelPublisher.java` | Upisuje kanale u `TvContract.Channels`; EPG analogno → `TvContract.Programs` |
| `ChannelZapper.java` | Orkestrira zapping |
| `ChannelScanner.java` | AutoScan po izvorima |
| `ChannelLookup.java` | Traženje serviceIndex u MW listi |

MW paketi:
- `iwedia.dtv.epg.EpgControl` — kontroler (dodati u `ComediaMiddlewareConnection`)
- `com.iwedia.dtv.epg.*` — data klase (EpgEvent, EpgEventType, EpgFilter podklase, EpgMasterList)
- `com.iwedia.dtv.types.TimeDate` — vreme

---

## Plan implementacije

### Korak 1 — `ComediaMiddlewareConnection`: dodati `EpgControl`

```java
// novo polje
@Nullable private EpgControl mEpgControl;

// u onDtvAvailable():
mEpgControl = createOrNull("EpgControl", () -> new EpgControl(mDtvContext));

// u onDtvUnavailable():
mEpgControl = null;

// novi getter:
@Nullable EpgControl getEpgControl() { return mEpgControl; }

// isAvailable() — EpgControl nije critical path za zapping, ne blokirati ga
```

### Korak 2 — nova klasa `EpgPublisher`

Analogna `ChannelPublisher`-u. Prima:
- `EpgControl epgControl`
- `ContentResolver contentResolver`
- Mapu `Map<Integer, Long> serviceIndexToChannelId` (MW serviceIndex → TvContract channelId)

**Metoda `publishAllChannels()`:**
1. Za svaki servis: `filterID = epgControl.createEventList()`
2. Postavi `EpgServiceFilter` sa serviceIndex-om
3. Pročitaj PF evente (PRESENT + FOLLOWING)
4. Pokreni SC akviziciju (`startAcquisition`), sačekaj `scAcquisitionFinished` callback
5. Pročitaj sve SC evente (`getAvailableEventsNumber` + `getRequestedEvent` u petlji)
6. Primeni time-shift na sve evente
7. Upiši u `TvContract.Programs`
8. `releaseEventList(filterID)`

**Time-shift logika:**
```java
// Skupi sve startTime-ove, nađi najraniji
long earliestMs = Long.MAX_VALUE;
for (EpgEvent e : allEvents) {
    long ms = e.getStartTime().getCalendar().getTimeInMillis();
    if (ms < earliestMs) earliestMs = ms;
}
long offset = System.currentTimeMillis() - earliestMs;
// Primeni na svaki event
long shiftedStart = e.getStartTime().getCalendar().getTimeInMillis() + offset;
long shiftedEnd   = e.getEndTime().getCalendar().getTimeInMillis()   + offset;
```

### Korak 3 — Okidanje EPG-a iz `DtvTvInputService`

Nakon što scan završi i kanali se upišu (`publishAll`), pokrenuti EPG publish:
- Izgraditi mapu `serviceIndex → channelId` čitanjem iz `TvContract.Channels`
- Instancirati `EpgPublisher` i pozvati `publishAllChannels()` (asinhrono, ne na glavnom threadu)

### Korak 4 — Verifikacija

Otvoriti LiveTv aplikaciju → izabrati kanal → trebalo bi prikazivati EPG podatke.
