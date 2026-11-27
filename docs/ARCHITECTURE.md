# Arsitektur

## Modul

```text
:androidApp   shell com.android.application
              manifest, ikon, signing, R8, ABI split (arm64-v8a, armeabi-v7a)

:composeApp   KMP library + aplikasi desktop JVM
  commonMain  Compose UI, domain, state, AppPreferences, expect API
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
- Caption pesan = nama folder virtual (`""` berarti root). Pesan tanpa caption
  (unggahan lama atau berkas yang dikirim di luar aplikasi) difilingkan dari
  ekstensi namanya, sehingga tidak ada berkas yang tertinggal di "Tanpa folder".
- Unggahan tanpa folder pilihan pengguna otomatis masuk topik sesuai jenis:
  `Foto`, `Video`, `Audio`, `Dokumen`, `Arsip`, `Aplikasi`, `Lainnya`
  (`FileCategories.folderFor`). Folder yang dipilih manual tetap menang.
  Sinkron galeri memakai topik yang sama dan mempertahankan subfolder aslinya,
  misalnya `Foto/DCIM/Camera`. Sakelar "Kelompokkan otomatis" mematikan aturan
  ini: unggahan kembali ke General kebukannya dan sinkron galeri kembali ke
  `Galeri`.
- Pesan lama tanpa caption dirapikan otomatis: `forwardMessages` (`topic_id`
  bertipe `MessageTopic`, tersedia sejak TDLib 1.8.71) menyalin berkas ke topik
  jenisnya, caption folder ditulis ke salinan lewat `editMessageCaption`, lalu
  pesan asli dihapus. Forward selalu berjalan sebelum delete, jadi kegagalan
  tidak pernah menghilangkan berkas. Sapuan penuh pertama ditandai
  `cloud.looseFilesTidied`; id yang gagal disimpan di `cloud.tidyPending` dan
  dicoba lagi pada sapuan berikutnya alih-alih ditinggal di General selamanya.
  Tombol "Rapikan berkas lama" di Pengaturan menjalankan sapuan penuh kapan saja,
  memuat sisa halaman riwayat lebih dulu bila daftar belum lengkap.
- Daftar berkas = `getChatHistory` dengan pagination `from_message_id`.
- Hapus = `deleteMessages` dengan `revoke = true`, dipecah per 100 id karena
  TDLib menolak permintaan yang membawa lebih banyak id sekaligus.
- Ikon grup = ikon aplikasi. `appIconFilePath()` merender launcher icon Android
  menjadi PNG 512 px dan menyalin `icon.png` paket di desktop, lalu
  `setChatPhoto` menerimanya sebagai `inputChatPhotoStatic`.
- Nama grup bisa diubah dari Pengaturan (`setChatTitle`). Karena itu id yang
  tersimpan dianggap bukti kepemilikan: judul hanya dipakai saat grup harus
  dicari lewat pencarian, dan grup yang topiknya dimatikan diaktifkan lagi
  alih-alih dibuat ulang (membuat ulang akan memutus semua berkas lama).
- Presentasi diselaraskan sekali per sesi (`ensurePresentation`): ikon hanya
  dipasang bila `getChat.photo` kosong, grup dibisukan lewat
  `setChatNotificationSettings`, dan dimasukkan ke arsip (`addChatToList`) bila
  saklarnya aktif. Saklar `false` tidak pernah menarik grup keluar dari arsip
  yang diatur pengguna sendiri.
- Deskripsi grup ditulis ulang hanya saat versi aplikasi berubah. Teksnya memuat
  penanda `Unlimited Cloud storage root`, yang dipakai memverifikasi kandidat
  hasil pencarian karena nama bawaan "Unlimited Cloud" terlalu umum.
- Foto kustom dipilih lewat `rememberImagePicker` (Android menyalin ke cache,
  desktop memakai berkas aslinya) lalu diunggah dengan jalur `setChatPhoto` yang
  sama. "Buka Telegram" memakai `tg://openmessage?chat_id` dengan
  `t.me/c/<supergroup id>` sebagai fallback.

`CloudFileRepository` menangani JSON TDLib secara mentah:

- `sendMessage` dengan `inputMessageDocument` + `inputDocument` +
  `inputFileLocal`. Jangan mengganti `inputDocument` dengan `inputFileLocal` di
  posisi itu; TDLib akan menolak dengan pesan menyesatkan
  `InputFile is not specified`.
- Progres unggah dibaca dari `remote.uploaded_size` / `size`.
- Unduh memanggil `downloadFile`, lalu mem-poll `getFile` sampai
  `local.is_downloading_completed`. Tidak ada batas waktu tetap: unduhan yang
  terus bertambah boleh berjalan, dan baru digagalkan bila tidak ada progres
  selama 5 menit, sehingga berkas besar di koneksi lambat tidak ikut gagal.

## State dan preferensi

`CloudFilesViewModel` adalah class biasa (bukan ViewModel platform) yang dipegang
Koin sebagai singleton untuk shell signed-in. State-nya satu `StateFlow<ExplorerUiState>`
berisi daftar berkas, filter, transfer, dan status galeri.

`AppPreferences` adalah key/value kecil di
`<appData>/settings.properties` (okio, multiplatform). Setiap tulis memakai lock
bersama dan berkas sementara yang di-`atomicMove`, dengan `.bak` sebagai cadangan
bila berkas utama terpotong. Isinya:

| Key | Fungsi |
| --- | --- |
| `theme` | `System`, `Light`, `Dark` |
| `cloud.rootChatId` | id grup penyimpanan, agar grup tidak dibuat ulang tiap buka |
| `cloud.rootTitle` | nama kustom grup penyimpanan (kosong = judul bawaan) |
| `cloud.muted` | grup penyimpanan dibisukan (bawaan `true`) |
| `cloud.archived` | grup penyimpanan disimpan di arsip Telegram |
| `cloud.descriptionVersion` | versi aplikasi yang tertulis di deskripsi grup |
| `cloud.autoTopics` | unggahan difilingkan ke topik jenis (bawaan `true`) |
| `cloud.looseFilesTidied` | penanda sapuan penuh berkas lama sudah dijalankan |
| `cloud.tidyPending` | id pesan lama yang gagal dirapikan, dicoba lagi nanti |
| `cloud.deleteUploadedCopies` | hapus salinan cache setelah unggahan berhasil (bawaan aktif; hanya menyentuh salinan milik aplikasi) |
| `changelog.lastSeenVersionCode` | gate pop-up pembaruan |
| `gallery.autoSync` | switch sinkronisasi otomatis |
| `gallery.folderId` / `gallery.folderName` | folder SAF yang dipilih |
| `gallery.syncedKeys` | dokumen yang sudah diunggah |
| `gallery.failedKeys` | jumlah kegagalan per dokumen (dilewati setelah 3×) |
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
| `GallerySyncSource` | SAF tree + `DocumentsContract`, semua jenis berkas dan subfolder, tanpa izin media | `supported = false` |
| `GalleryBackgroundSync` | WorkManager periodik 6 jam, 8 berkas per folder, network + baterai | `supported = false` |
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
menjalankan hingga 8 berkas per folder (parameter `perFolderLimit`), lalu berhenti.
Sinkronisasi dari layar Pengaturan memakai batas tak terhingga sehingga semua berkas
baru di folder terpilih terunggah dalam satu sesi. Kunci sinkron dicatat per dokumen,
jadi sisa batch dilanjutkan pada jadwal berikutnya. Satu batch Android menyalin
paling banyak setengah ruang cache yang kosong; dokumen yang tidak
muat atau gagal disalin masuk ke `GalleryBatch.skippedKeys`, dilaporkan di status dan
notifikasi, lalu dicoba lagi pada sinkron berikutnya. Yang disalin adalah semua jenis
berkas (bukan hanya gambar) beserta struktur subfoldernya: `Galeri/Liburan/Pantai`.
Tidak ada layanan latar depan; progres dan hasil transfer muncul sebagai notifikasi
bila izinnya diberikan, dan `FOREGROUND_SERVICE` bawaan WorkManager tetap dihapus
dari manifest bersama receiver diagnostik/profile-installer yang membawa penanda
`DUMP`.

Desktop memakai kontrak `GallerySyncSource` yang sama: folder yang dipilih adalah
direktori biasa, `id`-nya path absolut, kuncinya path absolut setiap berkas, dan
berkas diunggah langsung dari tempatnya (tanpa salinan cache, jadi `release()`
no-op). Pemindaian dibatasi 4.000 berkas dan 16 tingkat kedalaman per batch, sama
seperti Android. `GalleryBackgroundSync` tetap `supported = false` di desktop karena
tidak ada penjadwal sistem; sinkron otomatis berjalan saat aplikasi dibuka.

Transfer berjalan lewat kumpulan worker (`Channel` + `coroutineScope`). Jumlahnya
diambil dari `AppPreferences.transferParallelism()` (1–6, bawaan 3) dan dapat
diubah di Pengaturan; batch yang sedang berjalan memakai jumlah saat batch dimulai.
Di desktop, `TransferNotifier` diteruskan ke ikon tray: tooltip mengikuti batch yang
berjalan dan hasilnya tampil sebagai balon notifikasi. Menu tray membaca
`CloudFilesViewModel.uiState` yang sama dengan jendela: satu baris status (transfer
aktif atau folder siap), tombol **Sinkronkan folder sekarang** yang aktif hanya bila
ada folder terpilih dan tidak ada sinkron berjalan, serta buka/keluar.

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
- Folder sinkron dicabut izinnya → `pendingFiles` mengembalikan daftar kosong;
  pengguna memilih folder lagi.
- Sinkron latar belakang menemukan sesi yang butuh kode/password → worker berhenti
  tanpa retry sampai pengguna membuka aplikasi dan menyelesaikan login.
- Share tanpa unduhan lokal → unduhan dijalankan lebih dulu, chooser dibuka
  setelah path tersedia.
- Log dibatasi 400 baris agar polling transfer tidak menumbuhkan memori tanpa
  batas.
