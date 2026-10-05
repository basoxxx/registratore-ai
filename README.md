# Registratore Lezioni 🎙️📝

App per **Android, macOS e Windows** per **registrare le lezioni universitarie** e
**trascriverle automaticamente sul dispositivo, offline**, con esportazione in **Markdown** e testo.

## Cosa fa

- **Registrazione sempre completa**: l'audio viene salvato in un WAV che resta valido anche se
  l'app viene chiusa, il telefono si spegne o la batteria finisce (l'header viene aggiornato ogni
  5 secondi e riparato automaticamente al riavvio). Funziona a schermo spento, con pausa/ripresa
  e controlli nella notifica.
- **Trascrizione locale con Whisper** (whisper.cpp): nessun audio esce dal telefono.
  - **In tempo reale**: durante la lezione il testo si aggiorna da solo ogni ~30 secondi.
  - **Dopo**: al termine della registrazione, oppure quando vuoi, anche su file audio importati
    (mp3, m4a, ogg, wav… anche condivisi da altre app, es. WhatsApp/Telegram).
  - Interrompibile e **riprendibile** dal punto in cui si era fermata.
  - **Tempo stimato** di fine trascrizione, per la singola lezione e per l'intera coda
    (misurato sulla velocità reale del dispositivo e ricordato per ogni modello).
  - I blocchi audio sono tagliati nelle pause; il nome del corso viene usato come contesto per
    migliorare la precisione; le tipiche "allucinazioni" di Whisper sul silenzio vengono filtrate.
- **Esportazione** in Markdown (`.md`) o testo (`.txt`), con timestamp per paragrafo:
  condividi, salva dove vuoi, copia negli appunti.
- **Esportazione automatica**: scegli una cartella (es. il vault di Obsidian o una cartella
  sincronizzata con Drive/Syncthing) e l'app ci tiene **sempre aggiornato** il file `.md` di ogni
  lezione, anche mentre la trascrizione procede.
- Player integrato: tocca una frase per riascoltarla; la frase in riproduzione è evidenziata.
- Ricerca nelle lezioni e dentro la trascrizione.
- Compressione opzionale dell'audio in M4A dopo la trascrizione (~10 volte più piccolo).
- **Aggiornamenti automatici dell'app**: ogni nuova versione pubblicata qui su GitHub viene
  proposta direttamente nell'app.

## Installazione

Sito: **https://basoxxx.github.io/registratore-ai/**


1. Apri la pagina [Releases](https://github.com/basoxxx/registratore-ai/releases/latest) dal telefono.
2. Scarica `RegistratoreLezioni-x.y.z.apk` e aprilo (consenti l'installazione da questa sorgente).
3. Al primo avvio concedi microfono e notifiche, poi scarica il modello di trascrizione.

Requisiti: Android 8.0+, telefono a 64 bit (arm64).

### Versione per computer

Dalla stessa pagina Releases:

- **macOS** (Apple Silicon M1 e successivi): `RegistratoreLezioni-macOS.dmg`. Trascina l'app in
  Applicazioni. Non è firmata da Apple: al primo avvio, se viene bloccata, vai in
  *Impostazioni di Sistema → Privacy e sicurezza → Apri comunque*. Usa la GPU (Metal).
- **Windows 10/11** a 64 bit: `RegistratoreLezioni-Windows.msi` (installazione per utente).
  Se compare SmartScreen: *Ulteriori informazioni → Esegui comunque*.

Le lezioni vengono salvate in `Documenti/Registratore Lezioni`, una cartella per lezione con
`audio.wav`, `lezione.json` e il file `.md` sempre aggiornato durante la trascrizione.
Sul computer si possono importare file WAV/AIFF.

## Quale modello scegliere

I modelli vengono scaricati dalla release [`models`](https://github.com/basoxxx/registratore-ai/releases/tag/models)
di questo repository (pubblicata dal workflow *Modelli Whisper*: i file superano il limite di
100 MB di git, ma gli allegati delle release arrivano a 2 GB).

| Modello | Dimensione | Uso consigliato |
|---|---|---|
| Tiny | 32 MB | Telefoni datati, solo bozze |
| **Base** (predefinito) | 60 MB | Tempo reale su quasi tutti i telefoni |
| **Small** | 190 MB | Molto più preciso in italiano; tempo reale sui telefoni recenti |
| Large v3 Turbo | 474 MB | Massima qualità, ottimizzato per trascrivere "dopo" la lezione |

### Ottimizzazioni della trascrizione "dopo"

Misurate con Large v3 Turbo su 4,5 minuti di lezione (CPU a 4 core):

| Configurazione | Tempo | Errori (WER) |
|---|---|---|
| Prima: Q5_0, blocchi da 30 s | 366 s | 12,3% |
| + VAD: si salta il silenzio e il parlato viene impacchettato in finestre piene | 233 s | 11,0% |
| + modello Q4_0 (routine "repack" di ggml) | **95 s** | **10,8%** |

Inoltre il backend CPU è compilato in più varianti (Android: ARMv8.0 / 8.2 dotprod / 8.2 fp16 /
8.6 i8mm; Windows/Linux: da SSE4.2 ad AVX-512) e all'avvio viene caricata la più veloce supportata
dal processore. Sui Mac la flash attention è attiva sulla GPU (su CPU rallenta, quindi lì è spenta).
Elaborare due blocchi in parallelo non dà vantaggi misurabili (la CPU è già sfruttata al massimo).

Suggerimento: usa *Base* in tempo reale durante la lezione e, per le lezioni importanti,
"Ritrascrivi" con *Small* o *Large v3 Turbo* la sera mentre il telefono è in carica.

Spazio: un'ora di audio WAV occupa circa 115 MB (circa 11 MB se attivi la compressione M4A).

## Sviluppo

```bash
git clone --recursive https://github.com/basoxxx/registratore-ai.git
./gradlew assembleDebug        # richiede Android SDK + NDK 27
./gradlew testDebugUnitTest
```

Versione desktop (serve CMake e un compilatore C++):

```bash
cmake -S desktop/native -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native --config Release
mkdir -p desktop/resources/<os>-<arch>   # es. macos-arm64, windows-x64, linux-x64
cp build-native/<libwhisper_jni.*> desktop/resources/<os>-<arch>/
SKIP_ANDROID=1 ./gradlew :desktop:run
```

Struttura:

- `core/` – codice condiviso (WAV, suddivisione in blocchi, modelli, Markdown, binding JNI) + test
- `desktop/` – app Compose Desktop per macOS/Windows (`desktop/native/` compila la libreria nativa)
- `app/src/main/cpp/` – ponte JNI verso whisper.cpp (`third_party/whisper.cpp`, submodule), usato anche dal desktop
- `service/CaptureService.kt` – servizio in primo piano: registrazione + coda di trascrizione
- `transcription/Transcriber.kt` – trascrizione a blocchi, in tempo reale o differita, riprendibile
- `audio/` – WAV robusto, conversioni (import e compressione) con MediaCodec
- `export/Exporter.kt` – Markdown/TXT, condivisione, esportazione automatica
- `update/UpdateChecker.kt` – aggiornamenti dall'ultima GitHub Release
- `site/` – sito di presentazione pubblicato su GitHub Pages

### Release e firma

Ogni push su `main` esegue i test, compila APK, `.dmg` e `.msi` e pubblica la release
`v1.0.<N>` che le app usano per aggiornarsi. Gli aggiornamenti funzionano solo se ogni APK ha la **stessa firma**:
di default si usa `keystore/registratore.keystore` incluso nel repository. Per una firma privata
crea i secret `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(cambiando firma bisogna disinstallare e reinstallare l'app una volta).
