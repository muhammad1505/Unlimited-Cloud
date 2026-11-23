# Unlimited Cloud

Penyimpanan tanpa batas di dalam Telegram, dibangun dengan **Kotlin Multiplatform +
Compose Multiplatform** dan **TDLib**. Berkas disimpan sebagai pesan dokumen di
channel pribadi akun Telegram Anda; aplikasi tidak menjalankan server perantara
dan tidak menyimpan berkas di tempat lain.

Android dan Desktop memakai kode UI, domain, dan state management yang sama.

## Fitur

- **Login Telegram langsung** lewat TDLib: nomor telepon, kode verifikasi, password
  dua langkah, dan pendaftaran akun baru.
- **Channel pribadi sebagai cloud**: unggah, unduh, hapus, pencarian, pengurutan,
  dan folder virtual (disimpan sebagai caption pesan).
- **Transfer berprogres** untuk unggahan dan unduhan, dengan riwayat selesai/gagal.
- **Bagikan berkas** ke aplikasi lain: chooser Android lewat FileProvider, clipboard
  file-list di desktop.
- **Sinkronisasi galeri otomatis** (Android): pilih satu folder lewat pemilih folder
  sistem, foto baru diunggah ke folder `Galeri` — tanpa izin penyimpanan. Bisa
  dijadwalkan lewat WorkManager saat aplikasi tertutup.
- **Simpan foto ke galeri perangkat** (Android 10+): foto yang diunduh disalin ke
  `Pictures/Unlimited Cloud` lewat MediaStore, juga tanpa izin penyimpanan.
- **Panduan optimasi baterai** yang mengikuti kebijakan Google Play/OEM: membuka
  pengaturan sistem dan autostart, tanpa meminta pengecualian otomatis.
- **Changelog di aplikasi** dan pop-up "Yang baru" satu kali setelah pembaruan.
- **Tema** Sistem / Terang / Gelap, tersimpan di perangkat.
- **Log diagnostik** di perangkat dengan salin dan ekspor.
- **Versi dari satu sumber** (`gradle.properties`), dipakai APK, paket desktop,
  kartu Tentang, parameter TDLib, dan gate changelog.

## Tangkapan layar alur

1. Splash bermerek → login Telegram.
2. **Berkas** — ringkasan penyimpanan, pencarian, filter folder, kartu berkas.
3. **Transfer** — unggahan/unduhan aktif dan riwayat.
4. **Pengaturan** — profil, tema, penyimpanan, galeri, baterai, pembaruan,
   diagnostik, dan keluar akun.
5. **Changelog** — riwayat rilis yang sama dengan isi `CHANGELOG.md`.

## Kebutuhan

| Komponen | Versi |
| --- | --- |
| JDK | 17 |
| Android compile SDK | 37 |
| Android min SDK | 26 (Android 8.0) |
| Android target SDK | 36 |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| AGP | 9.5.2 |
| TDLib | 1.8.65 (natives Android), TDLight 4.0.308 (desktop) |

## Mulai cepat

1. **Isi kredensial Telegram.** Ambil `api_id` dan `api_hash` dari
   <https://my.telegram.org>, lalu simpan di `local.properties` (tidak di-commit):

   ```properties
   TELEGRAM_API_ID=12345678
   TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
   ```

   Alternatif: Gradle property `-PTELEGRAM_API_ID=...` atau environment variable
   `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`. Nilai di-generate menjadi
   `TelegramCredentials` saat build; kredensial asli tidak pernah masuk repository.

2. **Unduh native TDLib Android** (rilis APK butuh ini):

   ```bash
   bash scripts/fetch-tdlib-android.sh
   ```

   Skrip mengambil arsip `libtdjson.so` yang sudah dipin versi dan SHA-256-nya,
   lalu menaruhnya di `androidApp/src/main/jniLibs/<abi>/`.

3. **Build**:

   ```bash
   ./gradlew :androidApp:assembleDebug     # APK debug
   ./gradlew :androidApp:assembleRelease   # APK release (butuh keystore)
   ./gradlew :composeApp:run               # Desktop
   ```

   Build Android release memerlukan variabel signing (`RELEASE_STORE_FILE`,
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).
   Detail lengkap ada di [docs/BUILDING.md](docs/BUILDING.md).

> **Catatan lingkungan mobile.** Repo ini dikembangkan dari Termux/Android tanpa
> Android SDK dan RAM cukup untuk Gradle. Di lingkungan seperti itu **jangan**
> menjalankan `./gradlew`; jalankan `python3 scripts/preflight.py`, push, lalu
> pantau CI dengan `gh run watch --exit-status`. Aturan ini juga tercatat di
> [AGENTS.md](AGENTS.md).

## Struktur proyek

```text
androidApp/                     Shell com.android.application (manifest, ikon, signing)
composeApp/
  src/commonMain/               UI Compose, domain, state, preferences, expect API
  src/androidMain/              actual: TDLib JNI, SAF, WorkManager, MediaStore,
                                FileProvider, battery, back
  src/desktopMain/              actual: TDLib JNA, JFileChooser, clipboard, window
scripts/                       Preflight, fetch TDLib, bump/check versi, ikon
docs/                          Arsitektur, build, rilis, privasi
CHANGELOG.md                   Sumber changelog aplikasi + catatan rilis
gradle.properties              Sumber tunggal versi aplikasi
```

Penjelasan modul dan alur data: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privasi dan kebijakan Google Play

- Tidak ada izin penyimpanan di manifest: berkas diakses lewat **Storage Access
  Framework**, termasuk sinkronisasi galeri (satu folder yang dipilih pengguna).
  Foto yang diunduh ditulis ke MediaStore milik aplikasi sendiri (Android 10+),
  yang juga tidak memerlukan izin.
- Sinkronisasi latar belakang memakai WorkManager dan hanya berjalan bila pengguna
  mengaktifkannya; tidak ada layanan latar depan atau notifikasi.
- Tidak meminta `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; aplikasi hanya membuka
  layar pengaturan baterai/autostart milik sistem.
- FileProvider hanya membuka `cache/shared/`, tempat salinan sementara untuk fitur
  bagikan.
- Tidak ada backend pihak ketiga; semua trafik menuju Telegram lewat TDLib.

Rincian: [docs/PRIVACY.md](docs/PRIVACY.md).

## Versi dan changelog

Satu angka versi di `gradle.properties` menjadi sumber untuk semua permukaan:

```properties
unlimitedcloud.versionName=1.2.0
```

`versionCode` diturunkan otomatis sebagai `major*10000 + minor*100 + patch`.
Naikkan versi dan mulai catatan rilis dengan satu perintah:

```bash
python3 scripts/bump-version.py 1.2.0
```

Edit bagian **Baru** / **Perbaikan** di `CHANGELOG.md`. File itu dibundel saat build,
jadi Changelog di aplikasi, pop-up setelah pembaruan, dan catatan rilis GitHub
selalu sama. CI menjalankan `python3 scripts/check-versions.py` untuk memastikan
versi, changelog, dan kode tidak berbeda. Alur rilis lengkap ada di
[docs/RELEASING.md](docs/RELEASING.md).

## Kontribusi

Baca [CONTRIBUTING.md](CONTRIBUTING.md) dan [AGENTS.md](AGENTS.md). Sebelum commit:

```bash
python3 scripts/preflight.py
python3 scripts/check-versions.py
```

## Keterbatasan yang diketahui

- Unggahan besar tetap bergantung pada koneksi dan kebijakan baterai perangkat;
  panduan baterai membantu, tetapi tidak menjamin proses latar belakang.
- Sinkronisasi galeri memproses maksimal 20 foto per sesi dan tidak berjalan saat
  aplikasi ditutup (belum memakai WorkManager).
- Folder galeri di desktop tidak tersedia; gunakan dialog berkas biasa.
- Aplikasi belum menyertakan pengujian otomatis; verifikasi utama ada di CI
  (kompilasi, preflight, konsistensi versi, dan build release bertanda tangan).
