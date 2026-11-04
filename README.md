# Gestore file

Gestore file per Android (Kotlin, Jetpack Compose).

## Funzioni incluse (v0.1)

**Base**
- Navigazione di tutta la memoria, ordinamento, file nascosti
- Crea cartella/file, rinomina, copia, taglia, incolla, selezione multipla
- Cestino interno (eliminazione automatica dopo 30 giorni, ripristino)
- Ricerca con filtri (nome, estensione, dimensione minima, ultimi N giorni)

**Archivi**
- ZIP, RAR, 7Z, TAR, TAR.GZ/BZ2/XZ, GZ, CBZ/CBR
- Anteprima contenuto senza estrarre, estrazione di un singolo file
- Estrai "qui" oppure in una cartella scelta
- Password in apertura (ZIP, RAR, 7Z)
- Crea ZIP (livello compressione, password AES 256) e TAR.GZ

**Viewer**
- Immagini (JPG, PNG, GIF, WebP, HEIC), galleria, zoom, rotazione
- PDF (PdfRenderer), CSV, editor di testo (txt, json, xml, log, py...)
- Audio e video (ExoPlayer)
- Altri formati (Word, Excel, ecc.): apertura con l'app di sistema

**Condivisione**
- Chooser di Android: mail, WhatsApp, Dropbox, Facebook, Instagram, Bluetooth, Quick Share (Wi-Fi Direct)

**Extra**
- Analizzatore di spazio (cartelle e file più grandi)
- Ricerca duplicati (SHA-256) con cestino
- Rimozione GPS dai JPEG
- Vault protetto da biometria/PIN del dispositivo
- Server FTP integrato (porta 2121)
- Tema dinamico Material You, nero AMOLED

## Ancora da fare

Doppio pannello, tag, OCR, regole di smistamento (logica pronta in `AutoOrganizer`, manca la UI), scanner documenti, firma PDF, unione/divisione PDF, lettore EPUB/CBZ, SMB/SFTP, cloud, link temporanei, terminale, root, installer APK multiplo, statistiche, accessibilità.

## Build

Aprire la cartella in Android Studio (Ladybug o successivo, JDK 17) e lanciare `app`. Al primo avvio concedere "Accesso a tutti i file".

Nota: il progetto non è ancora stato compilato, possono servire piccole correzioni.
