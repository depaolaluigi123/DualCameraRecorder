# Dual Camera Recorder

Un'applicazione Android offline e standalone che registra video dalla **fotocamera anteriore e posteriore contemporaneamente** in due file MP4 separati, con una traccia audio sincronizzata catturata dal microfono del dispositivo.

L'app è costruita attorno all'**API Camera2** e a **`MediaRecorder`** e gira come servizio in foreground, così la registrazione non viene interrotta quando l'activity passa in background o lo schermo si spegne.

---

## Funzionalità principali

### Registrazione simultanea da due fotocamere
- Anteprima live di entrambe le fotocamere affiancate (verticale) o sovrapposte (orizzontale).
- Premendo **Registra** partono due sessioni `MediaRecorder` in sincrono, ognuna delle quali scrive il proprio file MP4 in `Movies/DualCameraRecording/<timestamp>/`.
- Le fotocamere vengono scelte tra i `concurrentCameraIds` dichiarati dal dispositivo, per garantire che la coppia possa effettivamente funzionare insieme. Se la combinazione richiesta non è supportata, l'app passa automaticamente a una coppia compatibile e avvisa l'utente.
- Tap-to-focus disponibile su ogni superficie di anteprima.

### Impostazioni video per singola fotocamera
Ogni fotocamera può essere configurata in modo indipendente:
- **Risoluzione** — più preset, commutati automaticamente tra 3:4 (verticale) e 4:3 (orizzontale) al cambio di orientamento.
- **Frame rate (FPS)** — il valore è richiesto al sensore; la fotocamera sceglie il valore supportato più vicino.
- **Bitrate** — diversi livelli di bitrate per bilanciare dimensione del file e qualità.

### Controlli manuali delle fotocamere (indipendenti per fotocamera)
- **Messa a fuoco manuale** con seekbar per la distanza di fuoco e pulsanti ±; il tap-to-focus riapplica la distanza corrente quando la messa a fuoco manuale è attiva.
- **ISO manuale** e **tempo di esposizione** tramite spinner (separati per anteriore e posteriore).
- Controllo del **flash / torcia** sulla fotocamera posteriore, con gestione corretta delle fotocamere logiche multi-camera (la torcia viene instradata sulla sotto-fotocamera fisica che possiede il flash).

### Acquisizione e misurazione audio
- L'audio viene catturato in **AAC** all'interno dello stesso contenitore MP4 del video.
- Bitrate AAC e frequenza di campionamento configurabili.
- Un misuratore audio live è sempre attivo (quando il permesso del microfono è concesso), con due stili visivi:
  - **Digitale (DAW)**
  - **Analogico (stile tape)**
- Il misuratore è presente sia nell'activity principale sia nell'overlay fullscreen, con un canale per fotocamera.

### Esperienza utente
- Modalità **anteprima a tutto schermo** con le due fotocamere affiancate, un interruttore per nascondere i controlli e un pulsante Indietro per tornare alla vista principale.
- Interruttore di orientamento **Verticale / Orizzontale**.
- Tema **Chiaro / Scuro**.
- Lingua **Italiano / Inglese**.
- Un **avviso di compatibilità del dispositivo** viene mostrato al primo avvio e può essere chiuso definitivamente tramite una checkbox "Non mostrare più".
- Tutte le scelte dell'utente (coppia di fotocamere, risoluzione, FPS, bitrate, fuoco, ISO, esposizione, flash, tema, lingua, stile del misuratore) sono salvate tramite DataStore e ripristinate tra un avvio e l'altro.
- Il pulsante Indietro e le conferme di uscita evitano interruzioni accidentali; durante una registrazione il pulsante Indietro è bloccato e i controlli di orientamento sono disabilitati.

### Affidabilità
- Un servizio in foreground mantiene attive le sessioni fotocamera quando l'activity non è visibile.
- Protezioni di rientranza attorno all'avvio/arresto delle fotocamere evitano che i listener di `SurfaceTexture` e di global-layout entrino in gara e provochino `ERROR_CAMERA_IN_USE`.
- I cambi fotocamera pendenti vengono accodati e applicati al termine dell'avvio in corso.
- Il recorder segue un ordine di avvio robusto (preparazione di entrambi i `MediaRecorder`, apertura delle fotocamere con superfici di anteprima + registrazione già nella sessione, attesa della configurazione di entrambe le sessioni e solo dopo `MediaRecorder.start()`) per rispettare il contratto Camera2/MediaRecorder.

---

## Output

Ogni registrazione crea una cartella nella directory `Movies/DualCameraRecording/` del dispositivo:

```
Movies/DualCameraRecording/
  2025-09-01_14-22-08/
    front.mp4
    rear.mp4
```

I due file condividono una traccia audio AAC sincronizzata (una traccia per file, entrambe catturate dalla stessa sessione di acquisizione del microfono).

---

## Requisiti

- **Android 8.0 (API 26)** o successivo
- Un dispositivo con **almeno due fotocamere fisiche** (anteriore e posteriore)
- Un microfono (quello integrato è sufficiente)
- Permessi richiesti a runtime:
  - `CAMERA`
  - `RECORD_AUDIO`
  - `POST_NOTIFICATIONS` (Android 13+)
  - `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (legacy)

---

## Stack tecnologico

- **Kotlin 2.1.0** con **Coroutines**
- **Android Gradle Plugin 8.7.3**, `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`
- **Java 17** / **JVM target 17**
- **Camera2 API** per il controllo delle fotocamere
- **`MediaRecorder`** per la codifica
- **ViewBinding**, Material Components, ConstraintLayout
- **DataStore (Preferences)** per la persistenza delle impostazioni
- **Servizio in foreground** di tipo `camera | microphone` per la registrazione in background

---

## Build

```bash
./gradlew assembleDebug
```

Il build `release` non è firmato di default; configura la tua signing config in `app/build.gradle.kts` prima della pubblicazione.

---

## Struttura del progetto

```
app/src/main/
├── java/com/dualcamerarecording/
│   ├── MainActivity.kt                  # UI, orchestrazione fotocamere, impostazioni
│   ├── MainViewModel.kt
│   ├── DualCameraRecorderApp.kt         # Application: possiede il MicStateStore
│   ├── audio/                           # Acquisizione microfono, gain math, state store
│   ├── camera/                          # Wrapper Camera2 + MediaRecorder, controller per fotocamera
│   ├── data/                            # Repository preferenze basato su DataStore
│   ├── locale/                          # Gestore della lingua
│   ├── model/                           # Modelli di configurazione
│   ├── service/                         # Servizio foreground di registrazione
│   ├── settings/                        # Store reattivo delle impostazioni fotocamera
│   ├── theme/                           # Gestore del tema Chiaro/Scuro
│   └── ui/                              # Bottom sheet impostazioni, dialog, reticolo di fuoco
└── res/
    ├── layout/                          # Activity principale + bottom sheet + dialog
    ├── values/                          # Stringhe EN, colori, temi
    ├── values-it/                       # Stringhe IT
    ├── values-night/                    # Colori tema scuro
    └── drawable/                        # Icone vettoriali
```

---

## Note sulla compatibilità dei dispositivi

Poiché l'app gestisce due fotocamere contemporaneamente e utilizza direttamente l'encoder hardware, il comportamento varia da dispositivo a dispositivo. Risoluzioni, frame rate e impostazioni audio supportate dipendono dall'hardware del telefono. Su alcuni dispositivi la combinazione dual-camera potrebbe non funzionare, l'anteprima potrebbe bloccarsi, la registrazione potrebbe fallire o l'audio potrebbe mancare. Esistono inoltre limiti hardware (numero di fotocamere eseguibili in contemporanea, risoluzione massima per fotocamera, frequenza di campionamento audio massima) che l'app non può aggirare. Se qualcosa non funziona come previsto, prova a cambiare la fotocamera anteriore/posteriore, la risoluzione o gli FPS — l'avviso di compatibilità del dispositivo che appare al primo avvio lo spiega più nel dettaglio.


---

## Licenza

Copyright (C) 2026 Luigi De Paola

Dual Camera Recorder è software libero: puoi redistribuirlo e/o modificarlo secondo i termini della GNU General Public License v3.0 (GPL-3.0).


