# Arsitektur

## Modul

```text
:androidApp   shell com.android.application
              manifest, ikon, signing, R8, ABI split (arm64-v8a, armeabi-v7a)

:composeApp   KMP library + aplikasi desktop JVM
  commonMain  Compose UI, domain, state, SQLDelight, AppPreferences, expect API
  androidMain actual TDLib JNI, SAF, WorkManager sync, MediaStore, FileProvider,
              battery, back handler
  desktopMain actual TDLib JNA, JFileChooser, clipboard, window
```

AGP 9 tidak mengizinkan `com.android.application` dipasang pada modul Kotlin
Multiplatform, sehingga APK dirakit oleh `:androidApp` dari `:composeApp`.
`MainActivity` sendiri berada di `androidMain` milik `:composeApp`.

## Alur runtime

```text
MainActivity / main()
      │
      ├─ AndroidPlatform.init(context)      (Android saja)
      ├─ startKoin { appModule }
      └─ App()
            ├─ TelegramEngine.start() → TdJsonSession.start()
            ├─ authState: Initializing → WaitingForPhoneNumber → … → Ready
            └─ RootDestination: Splash → Auth → Main
```

`TdLibTelegramEngine` adalah state machine otorisasi. Update TDLib dikumpulkan di
coroutine terpisah dari loop transport, sehingga handler boleh `suspend` memanggil
`TdJsonSession.call` tanpa menghentikan update lain. `TdJsonSession.start()`
memuat native lebih dulu: bila `libtdjson` tidak ada, state langsung menjadi
`Failure` dengan instruksi, bukan menunggu watchdog. Watchdog 20 detik menangani
kasus native ada tetapi jaringan/TDLib tidak merespons.

`TdJsonSession` menyatukan request/response berdasarkan `@extra` (lihat
`tdjson/`), sementara `TdJsonClient` adalah `expect` transport:

- Android: JNA ke `libtdjson.so` di `jniLibs/<abi>/`.
- Desktop: JNA ke `libtdjson`/`tdjson.dll` yang dibundel di resources paket
  (diambil `scripts/fetch-tdlib-desktop.sh`).

## Model penyimpanan

Channel pribadi adalah database sekaligus filesystem:

- Setiap unggahan = satu pesan dokumen.
- Caption pesan = nama folder virtual (`""` berarti root).
- Daftar berkas = `getChatHistory` dengan pagination `from_message_id`.
- Hapus = `deleteMessages` dengan `revoke = true`.

`CloudFileRepository` menangani JSON TDLib secara mentah:

- `sendMessage` dengan `inputMessageDocument` + `inputDocument` +
  `inputFileLocal`. Jangan mengganti `inputDocument` dengan `inputFileLocal` di
  posisi itu; TDLib akan menolak dengan pesan menyesatkan
  `InputFile is not specified`.
- Progres unggah dibaca dari `remote.uploaded_size` / `size`.
- Unduh memanggil `downloadFile`, lalu mem-poll `getFile` sampai
  `local.is_downloading_completed`, dengan timeout 10 menit.

## State dan preferensi

`CloudFilesViewModel` adalah class biasa (bukan ViewModel platform) yang dipegang
Koin sebagai singleton untuk shell signed-in. State-nya satu `StateFlow<ExplorerUiState>`
berisi daftar berkas, filter, transfer, dan status galeri.

`AppPreferences` adalah key/value kecil di
`<appData>/settings.properties` (okio, multiplatform). Isinya:

| Key | Fungsi |
| --- | --- |
| `theme` | `System`, `Light`, `Dark` |
| `changelog.lastSeenVersionCode` | gate pop-up pembaruan |
| `gallery.autoSync` | switch sinkronisasi otomatis |
| `gallery.folderId` / `gallery.folderName` | folder SAF yang dipilih |
| `gallery.syncedKeys` | dokumen yang sudah diunggah |
| `gallery.lastSync` | label waktu sinkron terakhir |
| `gallery.backgroundSync` | switch sync WorkManager saat aplikasi tertutup |
| `gallery.autoSavePhotos` | simpan foto unduhan ke galeri perangkat |

## Navigasi

`SignedInScreen` memodelkan semua halaman sebagai `AppDestination`:

```text
Section(Files | Transfers | Settings)
Diagnostics
Changelog
```

- Satu `AnimatedContent` menangani fade + slide untuk semua perpindahan.
- Bagian aktif disimpan dengan `rememberSaveable`, jadi rotasi tidak mengembalikan
  pengguna ke Berkas.
- Memilih tab selalu menutup overlay (log/changelog), sehingga highlight navigasi
  dan isi layar tidak pernah bertentangan.
- `PlatformBackHandler` menutup overlay di Android; desktop memakai tombol
  "Kembali" di layar overlay.

## Platform capability (`expect`/`actual`)

| API | Android | Desktop |
| --- | --- | --- |
| `FilePicker` | SAF `GetContent`, salin ke cache | `JFileChooser` |
| `FileSharer` | salin ke `cache/shared`, FileProvider, chooser | clipboard file-list |
| `GallerySyncSource` | SAF tree + `DocumentsContract`, tanpa izin media | `supported = false` |
| `GalleryBackgroundSync` | WorkManager periodik 6 jam, 8 foto per folder, network + baterai | `supported = false` |
| `MediaSaver` | MediaStore `Pictures/Unlimited Cloud` (API 29+) | `supported = false` |
| `BatteryOptimizationController` | `PowerManager`, settings sistem, pintasan OEM | tidak didukung |
| `LogFileSaver` | SAF `CreateDocument` | `JFileChooser` |
| `PlatformBackHandler` | `androidx.activity.compose.BackHandler` | no-op |

Aturan: `commonMain` tidak boleh mengimpor `android.*`, `java.io.File`, `javax.*`,
atau binding TDLib. Penggunaan platform spesifik harus lewat `expect`/`actual`.

## Sinkronisasi latar belakang dan galeri perangkat

`GalleryBackgroundSync` menjadwalkan `GallerySyncWorker` (WorkManager) setiap
enam jam dengan constraint jaringan dan baterai tidak rendah. Worker memakai
`AppPreferences`, `TelegramEngine`, dan `CloudFilesViewModel` dari Koin yang sama;
`MainActivity` hanya memulai Koin bila belum berjalan, sehingga proses yang
dibangunkan WorkManager tetap aman. Worker menunggu sesi TDLib tersimpan siap,
menjalankan hingga 8 foto per folder (parameter `perFolderLimit`), lalu berhenti.
Sinkronisasi dari layar Pengaturan memakai batas tak terhingga sehingga semua foto
baru di folder terpilih terunggah dalam satu sesi. Kunci sinkron dicatat per foto,
jadi sisa batch dilanjutkan pada jadwal berikutnya. Tidak ada layanan latar depan atau
notifikasi; manifest menghapus izin `FOREGROUND_SERVICE` bawaan WorkManager dan
receiver diagnostik/profile-installer yang membawa penanda `DUMP`.

`MediaSaver` menulis foto unduhan ke MediaStore dengan `IS_PENDING`: entri baru
tampil di galeri setelah salinan selesai, dan entri dihapus bila penyalinan gagal.
Di Android 9 ke bawah fitur disembunyikan karena MediaStore belum bisa dipakai
tanpa izin penyimpanan; desktop melaporkan `supported = false`.

## Kode yang di-generate saat build

| Task | Keluaran | Sumber |
| --- | --- | --- |
| `generateTelegramCredentials` | `config/TelegramCredentials.kt` | Gradle property / env / `local.properties` |
| `generateAppBuildInfo` | `AppBuildInfo.kt` | `unlimitedcloud.versionName` |
| `generateChangelog` | `changelog/ChangelogData.kt` | `CHANGELOG.md` |

File tersebut berada di `build/generated/…` dan tidak di-commit. Semua kompilasi
Kotlin bergantung pada ketiga task.

## Alur changelog

```text
CHANGELOG.md
   │ generateChangelog (build)
   ▼
ChangelogData.kt  →  AppChangelog.parseChangelog()
   ├─ ChangelogScreen (menu Pengaturan → Tentang & bantuan → Apa yang baru)
   └─ ChangelogDialog (setelah versionCode naik)
```

Pop-up muncul sekali per versionCode: `AppPreferences.lastSeenVersionCode`
dibandingkan dengan `AppBuildInfo.VERSION_CODE`. Instalasi baru mencatat versi
saat ini secara diam-diam, sedangkan pembaruan menampilkan rilis terbaru.

## Kegagalan yang sudah ditangani

- Native TDLib tidak ada / kredensial dummy / jaringan diam → layar kegagalan
  dengan instruksi yang sesuai, bukan splash tanpa akhir.
- Koneksi putus saat transfer → status `Failed` dengan pesan, data lain tetap.
- Folder galeri dicabut izinnya → `pendingPhotos` mengembalikan daftar kosong;
  pengguna memilih folder lagi.
- Sinkron latar belakang menemukan sesi yang butuh kode/password → worker berhenti
  tanpa retry sampai pengguna membuka aplikasi dan menyelesaikan login.
- Share tanpa unduhan lokal → unduhan dijalankan lebih dulu, chooser dibuka
  setelah path tersedia.
- Log dibatasi 400 baris agar polling transfer tidak menumbuhkan memori tanpa
  batas.
