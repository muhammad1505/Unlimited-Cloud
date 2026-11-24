# Privasi

Unlimited Cloud adalah klien Telegram. Tidak ada server aplikasi, tidak ada
analitik, dan tidak ada iklan. Berkas Anda berada di akun Telegram Anda sendiri.

## Data yang disimpan

| Data | Lokasi | Keterangan |
| --- | --- | --- |
| Berkas yang diunggah | Channel pribadi Telegram | disimpan sebagai pesan dokumen |
| Sesi Telegram | database TDLib di penyimpanan privat aplikasi | dihapus saat logout |
| Preferensi | `settings.properties` di penyimpanan privat | tema, folder galeri, switch sync latar belakang, versi terakhir |
| Log diagnostik | memori (400 baris) | hanya nama event/error, tidak pernah kredensial |

Nomor telepon, kode verifikasi, dan password dua langkah dikirim langsung ke
Telegram lewat TDLib. Aplikasi tidak mencatatnya ke log dan tidak menyimpannya
selain yang dikelola TDLib untuk sesi.

## Izin Android

APK jadi hanya meminta:

- `INTERNET` dan `ACCESS_NETWORK_STATE` untuk TDLib.
- `WAKE_LOCK` dan `RECEIVE_BOOT_COMPLETED` dari WorkManager, agar jadwal sinkron
  yang diaktifkan pengguna dapat dilanjutkan setelah perangkat restart.
- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, izin signature milik aplikasi
  sendiri yang menjaga receiver runtime.

Izin `FOREGROUND_SERVICE` yang biasanya ikut dari WorkManager dihapus lewat
manifest merger: worker galeri adalah periodic worker biasa, tidak pernah menjadi
foreground service, dan tidak menampilkan notifikasi. Receiver diagnostik
WorkManager dan profile installer juga dihapus karena membawa penanda
`android.permission.DUMP` meskipun tidak meminta izin apa pun.

Tidak ada izin penyimpanan, kamera, lokasi, media, notifikasi, atau aksesibilitas.
Semua akses berkas memakai Storage Access Framework:

- Unggah: pemilih dokumen (`GetContent`), file disalin ke cache aplikasi agar TDLib
  bisa membacanya.
- Ekspor log: `CreateDocument`.
- Sinkronisasi galeri: `OpenDocumentTree`, satu folder yang dipilih pengguna.
  Izin tree disimpan (`takePersistableUriPermission`) sehingga sinkronisasi
  berikutnya tidak meminta ulang. Aplikasi hanya membaca isi folder itu.
- Sinkronisasi latar belakang memakai WorkManager (AndroidX, berjalan lokal) dan
  hanya aktif bila pengguna menyalakannya. Tidak ada izin tambahan, layanan latar
  depan, atau notifikasi.
- Foto yang diunduh dapat disalin ke `Pictures/Unlimited Cloud` melalui MediaStore
  pada Android 10 ke atas. Aplikasi hanya menulis entri miliknya sendiri, jadi
  tidak meminta izin penyimpanan atau media.

## Peringatan Play Protect saat sideload

APK rilis ditandatangani sendiri dan tidak didistribusikan lewat Google Play.
Play Protect dapat menampilkan "App scan recommended" atau "This app is unknown
to Play Protect" setiap kali versi baru dipasang. Itu adalah peringatan bahwa
Google belum mengenal APK tersebut, **bukan** permintaan izin penyimpanan.

Aplikasi tidak mendeklarasikan izin penyimpanan/media apa pun; akses berkas selalu
lewat SAF. Untuk memverifikasi APK yang diunduh:

```bash
python3 scripts/check-apk-permissions.py path/ke/UnlimitedCloud-<versi>-arm64-v8a.apk
```

Skrip itu membaca manifest APK jadi dan gagal bila menemukan izin penyimpanan,
media, foreground service, notifikasi, atau penanda `DUMP`. Jika ingin dialog
Play Protect tidak muncul, distribusikan aplikasi lewat Google Play (termasuk
jalur internal testing) agar Play Protect dapat memindai dan mengenali paketnya.

## Optimasi baterai

Aplikasi **tidak** meminta `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Menu Optimasi
baterai hanya:

1. Menampilkan status `isIgnoringBatteryOptimizations` dari sistem.
2. Membuka `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` atau detail aplikasi.
3. Menyediakan pintasan ke layar autostart OEM bila perangkat memilikinya.

Keputusan memberi pengecualian tetap milik pengguna di pengaturan sistem. Ini
sesuai kebijakan Play yang membatasi permintaan pengecualian otomatis.

## Berbagi berkas

Saat pengguna memilih **Bagikan**, aplikasi mengunduh berkas (bila perlu), menyalin
satu file ke `cache/shared/`, lalu membuka chooser dengan URI FileProvider dan izin
baca sementara. Aplikasi lain hanya menerima file itu, bukan akses ke database
TDLib atau penyimpanan aplikasi.

## Log dan diagnostik

Log berisi transisi login (jenis state, bukan isi), pesan error TDLib, dan event
transfer. Tidak ada isi pesan, nomor telepon, kode, atau password. Log hanya
tersimpan di memori perangkat dan dapat dibersihkan/diekspor sendiri oleh pengguna.

## Pihak ketiga

- **Telegram** menerima berkas dan metadata akun sesuai kebijakan Telegram.
- Tidak ada SDK iklan/analitik, crash reporter, atau backend lain.

## Penghapusan data

- Menghapus berkas di aplikasi menghapus pesan di channel Telegram.
- Logout menutup sesi Telegram dan TDLib membersihkan database lokalnya.
- Menghapus aplikasi menghapus preferensi, cache, dan database TDLib di perangkat.
- Berkas yang sudah diunggah tetap berada di akun Telegram sampai dihapus pengguna.

## Kontak

Untuk pertanyaan privasi, buka issue di repositori proyek atau hubungi pemelihara
melalui kontak yang tercantum di halaman rilis.
