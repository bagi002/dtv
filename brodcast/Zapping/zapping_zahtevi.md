# PROJECT: Live TV TIF Application (Android TV / Cuttlefish)

## 1. IMMUTABLE CONSTRAINTS

- **Service installation kod i dijagram** su fiksni i rade ispravno
  - Prilagođavanje ide samo jednosmeran: zapping → service-installation
  - Service-installation se NIKADA ne menja
- **Glavna referenca**: `zapping_broadcast.puml` dijagram
  - Inicijalno je skoro dobro napisan
  - Može da se koriguje u skladu sa konkretnom implementacijom API poziva, klasa i logike
  - MORA biti nastavak na service-installation strukturu
- **Target**: Android 16, Cuttlefish emulator, Broadcast (iWedia Comedia middleware) + OTT inputs

---

## 2. DESIGN PRINCIPLES

### Kod struktura
- **Modularnost**: Logičke celine razvojene u odvojene klase
- **Čist kod**: Bez guranja previše koda u jedan fajl
- **Proširivost**: Nova funkcionalnost mora da se doda bez refaktorovanja postojećeg koda
- **Čitljivost**: Jasna struktura i nomen klatura

### Granularnost razvoja
- Pisati **metodu po metodu** (ne celu klasu odjednom)
- Za svaku metodu: 
  - Objasnjenje zašto nam je potrebna
  - Zašto je takva odluka doneta (alternativni pristupi ako postoje)
  - Detaljno objašnjenje kako radi

---

## 3. DEVELOPMENT WORKFLOW

### Red izvršavanja
1. **Definiši šta trebamo** - arhitekturalna odluka (čeka tvoj unos)
2. **Implementiraj** - metodu po metodu sa objašnjenjima
3. **Verifikuj implementaciju na cuttlefish-u** - potvrdi da je implementacija ispravna (ne slučajna) 
4. **Prelazi na sledeće** - tek nakon što je prethodna funkcionalnost validirana

### Test-first pristup
- Svaka funkcionalnost koja se može testirati ide PRVO kroz test
- Test mora biti specifičan - provera korektnog ponašanja, ne samo da li se pokreće
- Tek nakon što test prođe → nastavljamo sa sledećom funkcionalnosti
- Kada kazem test, to se ne odnosi da pises test kod vec da testiras funkcionalno da li radi sta treba
---

## 4. DECISION GATES
### Gde se konsultuje Luka

Sve **arhitekturalne odluke** moraju biti odobrene:
- Struktura klasa i njihove odgovornosti
- API design između modula
- Trade-offi između različitih pristupa (npr. modularna vs performantna)
- Kako se prilagođava zapping logika service-installation referentnoj arhitekturi

---

## 5. REFERENCE MATERIALS

### Primarni sources
- **TIF API dokumentacija**: https://developer.android.com/training/tv/tif
- https://developer.android.com/media/media3/ui/surface
  - Sve relevantno iz ove dokumentacije skladiš u memoriju i koristiš kao izvor istine
- **Dijagram**: `zapping_broadcast.puml`
  - Startna tačka, koriguje se tokom implementacije
- **Service installation**: Referentna arhitektura (fiksna)

### Tehnički stack
- Android TV (API 16+)
- Broadcast: iWedia Comedia middleware
- Deployment: Cuttlefish emulator
- Package management: ADB, priv-app paths

---

## 6. COMMUNICATION PROTOCOL

- **Fokus**: Konkretne tehnicke odluke, ne opšte diskusije
- **Format**: Struktuirano - predlog, alternativa, rizik
- **Iteracija**: Brza povratna sprega između test-a i koda