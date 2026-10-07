# Dual Camera Recorder

Un'applicazione Android offline e standalone che registra video dalla **fotocamera anteriore e posteriore contemporaneamente** in due file MP4 separati, con una traccia audio sincronizzata catturata dal microfono del dispositivo.

L'app è costruita attorno all'**API Camera2** e a **`MediaCodec` + `MediaMuxer`** (due encoder H.264 e un encoder AAC stereo condiviso). Durante la registrazione gira un servizio in foreground, così la registrazione non viene interrotta quando l'activity passa in background o lo schermo si spegne.

---

## Funzionalità principali

### Registrazione simultanea da due fotocamere
- Anteprima live di entrambe le fotocamere affiancate (verticale) o sovrapposte (orizzontale).
- Premendo **Registra** le fotocamere già aperte ricevono una nuova sessione con l'encoder H.264 di ciascuna: i due file MP4 in `Movies/DualCameraRecording/<timestamp>/` partono dallo stesso istante e finiscono insieme.
- Molti telefoni non possono usare tutte le coppie anteriore + posteriore insieme (conflitti dichiarati dall'hardware o configurazioni rifiutate dall'HAL). Una coppia che non funziona viene riconosciuta, memorizzata e segnata nelle tendine come "non utilizzabile con l'altra fotocamera"; la fotocamera anteriore resta attiva da sola e, all'avvio, viene proposta una coppia compatibile.
- Le anteprime si usano come nell'app fotocamera: un tocco mette a fuoco in quel punto (convertito in coordinate del sensore: rotazione, specchio della frontale, zoom, inquadratura 4:3/16:9), il pizzico con due dita ingrandisce o rimpicciolisce (gli slider dello zoom si aggiornano). Il pizzico non avvia mai la messa a fuoco.

### Impostazioni video per singola fotocamera
Ogni fotocamera può essere configurata in modo indipendente:
- **Risoluzione** — preset 4:3 da 640x480 a 4032x3024, oppure preset 16:9 da 640x360 a 3840x2160 (4K) con la spunta **Inquadratura 16:9** della fotocamera. Ogni fotocamera ha la sua spunta, quindi una può registrare in 4:3 e l'altra in 16:9; l'anteprima di ciascuna passa alla stessa inquadratura. La spunta indica 16:9 / 4:3 in orizzontale e 9:16 / 3:4 in verticale. Ogni formato ricorda la propria risoluzione per ciascuna fotocamera. In verticale le dimensioni sono invertite. Sono elencati solo quelli supportati dalla fotocamera selezionata e dall'encoder.
- **Frame rate (FPS)** — l'elenco viene letto dalla fotocamera selezionata: sono elencati solo i frame rate che può mantenere costanti alla risoluzione scelta; il valore viene imposto al sensore e il file è a frame rate costante. Alcune fotocamere sono un po' più veloci del valore scelto (ad esempio 30,2 fps): l'app fa loro saltare un fotogramma ogni tanto, così il video resta sincronizzato con l'audio (con l'app in background i fotogrammi in più vengono invece tenuti, leggermente fuori dalla griglia costante ma sempre sincronizzati).
- **Bitrate** — l'encoder lavora in CBR e il flusso viene completato con filler data H.264 quando la scena è troppo semplice, quindi il bitrate del file corrisponde a quello scelto. "Auto" è calcolato da risoluzione e FPS ed è mostrato nell'etichetta. I preset arrivano a 500 Mbps, ma sono elencati solo i valori fino al massimo dell'encoder (100 Mbps sul telefono di prova). Alle risoluzioni più alte l'encoder può non reggere in tempo reale i bitrate più alti e il frame rate scende (telefono di prova: 2592x1944 a 30 fps regge 60 Mbps, non 80–100).

### Controlli manuali delle fotocamere (indipendenti per fotocamera)
- **Messa a fuoco manuale** (solo sulle fotocamere che possono mettere a fuoco; sugli obiettivi a fuoco fisso la spunta è attenuata e lo indica) con seekbar per la distanza di fuoco (copre l'intero intervallo della lente, da infinito alla distanza minima) e pulsanti ±; il tap-to-focus riapplica la distanza corrente quando la messa a fuoco manuale è attiva. È disabilitata sulle fotocamere a fuoco fisso.
- **ISO manuale** e **tempo di esposizione** tramite spinner (separati per anteriore e posteriore).
- Controllo del **flash / torcia** sulla fotocamera posteriore, utilizzabile insieme al tap-to-focus (lo stato della torcia è in ogni richiesta alla fotocamera, comprese quelle di messa a fuoco), con gestione corretta delle fotocamere logiche multi-camera (la torcia viene instradata sulla sotto-fotocamera fisica che possiede il flash).

### Acquisizione e misurazione audio
- L'audio viene catturato **una sola volta in stereo** (la stessa cattura dei misuratori) e codificato in **AAC-LC stereo**; la stessa traccia viene scritta in entrambi i file MP4.
- Bitrate AAC e frequenza di campionamento configurabili: sono elencati solo i bitrate che l'AAC può davvero produrre alla frequenza scelta.
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
- Risoluzione, FPS, bitrate, impostazioni audio, tema, lingua e stile del misuratore sono salvati (SharedPreferences) e ripristinati tra un avvio e l'altro; la scelta delle fotocamere non viene salvata.
- Il pulsante Indietro e le conferme di uscita evitano interruzioni accidentali; durante una registrazione il pulsante Indietro è bloccato, i controlli di orientamento sono disabilitati e tema e lingua non si possono cambiare.

### Affidabilità
- Durante la registrazione un servizio in foreground (tipo `camera | microphone`) mantiene attive fotocamere e microfono quando l'activity non è visibile, e lo schermo resta acceso. Senza registrazione, fotocamere e microfono vengono rilasciati in background e riaperti al ritorno.
- Protezioni di rientranza attorno all'avvio/arresto delle fotocamere evitano che i listener di `SurfaceTexture` e di global-layout entrino in gara e provochino `ERROR_CAMERA_IN_USE`.
- I cambi fotocamera pendenti vengono accodati e applicati al termine dell'avvio in corso.
- La registrazione parte quando entrambe le fotocamere forniscono fotogrammi: i due file iniziano dallo stesso istante (stessa linea temporale e stessa traccia audio). Se una fotocamera non parte, l'altra registra comunque e l'utente viene avvisato; se non parte nessuna, l'interfaccia torna allo stato normale con un messaggio d'errore.

---

## Output

Ogni registrazione crea una cartella nella directory `Movies/DualCameraRecording/` del dispositivo:

```
Movies/DualCameraRecording/
  2025_09_01_14_22_08/
    front.mp4
    rear.mp4
```

I due file contengono la stessa traccia audio AAC stereo, codificata una sola volta dalla stessa cattura del microfono.

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
- **`MediaCodec` + `MediaMuxer`** per la codifica (H.264 CBR, AAC-LC stereo)
- **ViewBinding**, Material Components, ConstraintLayout
- **SharedPreferences** per la persistenza delle impostazioni
- **Servizio in foreground** di tipo `camera | microphone`, attivo durante la registrazione

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
│   ├── camera/                          # Camera2: recorder doppio, controller per fotocamera, capacità
│   ├── data/                            # Repository preferenze (SharedPreferences)
│   ├── locale/                          # Gestore della lingua
│   ├── model/                           # Modelli di configurazione
│   ├── recording/                       # Encoder H.264/AAC, muxer MP4, sessione di registrazione
│   ├── service/                         # Servizio foreground durante la registrazione
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


