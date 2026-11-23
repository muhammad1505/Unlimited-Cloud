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

Manifest aplikasi mendeklarasikan:

- `INTERNET`
- `ACCESS_NETWORK_STATE`

WorkManager menambahkan izin normal seperti `WAKE_LOCK` dan
`RECEIVE_BOOT_COMPLETED` agar jadwal sinkron yang diaktifkan pengguna dapat
dilanjutkan setelah perangkat restart. Tidak ada izin penyimpanan, kamera, lokasi,
atau media. Semua akses berkas memakai Storage Access Framework:

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
