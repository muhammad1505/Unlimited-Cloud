# Build

## Prasyarat

- JDK 17 (CI memakai Temurin 17).
- Android SDK dengan `platforms;android-37` dan `build-tools;36.0.0`.
- Python 3 untuk skrip pemeriksaan.
- Desktop: Linux x86_64 dengan GTK/X11, atau Windows x86_64. Native TDLib
  diunduh oleh skrip di bawah; macOS belum punya aset terpin.

Di Termux/Android tanpa SDK dan RAM memadai, jangan menjalankan Gradle. Gunakan
`scripts/preflight.py` + CI seperti dijelaskan di [AGENTS.md](../AGENTS.md).

## Kredensial Telegram

Ambil `api_id`/`api_hash` dari <https://my.telegram.org>. Urutan pembacaan Gradle:

1. `-PTELEGRAM_API_ID=...` / `-PTELEGRAM_API_HASH=...`
2. environment variable `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`
3. `local.properties` (di-ignore Git)
4. fallback dummy `0` / `000…` agar proyek tetap bisa dikompilasi

Contoh `local.properties`:

```properties
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
```

Build dengan kredensial dummy akan login gagal; aplikasi menampilkan pesan agar
kredensial diisi.

## Native TDLib Android

```bash
bash scripts/fetch-tdlib-android.sh              # arm64-v8a (satu-satunya ABI rilis)
bash scripts/fetch-tdlib-android.sh x86_64       # tambahan untuk emulator 64-bit
```

Skrip mengunduh arsip dari rilis `up9cloud/android-libtdjson` versi 1.8.65,
memverifikasi SHA-256, lalu menyalin `libtdjson.so` ke
`androidApp/src/main/jniLibs/<abi>/`. Direktori itu di-ignore Git karena ukurannya
20–30 MB per ABI.

## Native TDLib Desktop

```bash
bash scripts/fetch-tdlib-desktop.sh
```

Skrip mengunduh TDLib 1.8.71 dari rilis `AlexXanderGrib/prebuilt-tdlib`
(`libtdjson-x64-glibc.so` di Linux x86_64, `tdjson-x64.dll` di Windows x86_64),
memverifikasi SHA-256, lalu menyalinnya sebagai `libtdjson.so` / `tdjson.dll` ke
`composeApp/src/desktopMain/resources/`. Folder itu di-ignore Git.

Task `createDistributable`, `packageDeb`, `packageRpm`, dan `packageMsi`
berhenti dengan pesan yang jelas bila native belum ada; CI menjalankan skrip ini
sebelum packaging. Paket deb/rpm/msi membundel native tersebut sehingga instalasi
tidak perlu memasang TDLib terpisah. `:composeApp:run` juga memerlukannya.
macOS bukan target paket; untuk menjalankan lokal, tunjuk library TDLib sendiri
lewat `-Djna.library.path`.

## Perintah umum

```bash
# Desktop (ambil native sekali setelah clone)
bash scripts/fetch-tdlib-desktop.sh
./gradlew :composeApp:run

# APK debug
./gradlew :androidApp:assembleDebug

# APK release bertanda tangan (butuh signing)
./gradlew :androidApp:assembleRelease

# Paket desktop: deb + rpm di Linux, msi di Windows
./gradlew :composeApp:packageDeb :composeApp:packageRpm
./gradlew.bat :composeApp:packageMsi
```

APK release dinamai seperti paket desktop:
`UnlimitedCloud-<versi>-arm64-v8a.apk`, bukan `androidApp-release.apk`.

Paket desktop membundel native TDLib dari `scripts/fetch-tdlib-desktop.sh`.
`it.tdlight` tidak dipakai karena hanya menerbitkan wrapper JNI (`tdjni`), bukan
`tdjson`; transport desktop memakai JSON C API yang sama dengan Android.

## Signing Android

Build release memakai variabel environment atau `local.properties`:

```text
RELEASE_STORE_FILE       path ke .jks
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Tanpa keempatnya, build release tetap berjalan tetapi tidak ditandatangani, jadi
tidak bisa dipasang/dirilis. CI menyediakan `RELEASE_KEYSTORE_BASE64` yang
di-decode menjadi `androidApp/release.keystore`, lalu menghapusnya di akhir job.

## Secret CI

| Secret | Fungsi |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore release (base64) |
| `RELEASE_STORE_PASSWORD` | password keystore |
| `RELEASE_KEY_ALIAS` | alias kunci |
| `RELEASE_KEY_PASSWORD` | password kunci |
| `TELEGRAM_API_ID` | kredensial Telegram untuk build |
| `TELEGRAM_API_HASH` | kredensial Telegram untuk build |

## Pemeriksaan sebelum push

```bash
python3 scripts/preflight.py        # import terlarang, bracket, conflict marker
python3 scripts/check-versions.py   # versi vs changelog vs kode
python3 -m py_compile scripts/*.py

# Isi paket desktop (native TDLib + ikon merek); jalankan di OS target.
python3 scripts/check-desktop-package.py \
  composeApp/build/compose/binaries/main/deb/*.deb \
  composeApp/build/compose/binaries/main/rpm/*.rpm
```

CI menjalankan keduanya plus build release bertanda tangan dan verifikasi
`apksigner`.

## Troubleshooting

| Gejala | Penyebab umum | Solusi |
| --- | --- | --- |
| `UnsatisfiedLinkError: libtdjson.so` | natives Android belum diunduh | jalankan `scripts/fetch-tdlib-android.sh`, build ulang |
| Desktop: "Pustaka TDLib (libtdjson) tidak ditemukan" | native desktop belum diambil atau gagal dimuat | jalankan `scripts/fetch-tdlib-desktop.sh`, build ulang; di macOS set `-Djna.library.path` |
| "Kredensial Telegram belum tertanam di build ini" | build memakai fallback dummy | isi `TELEGRAM_API_ID`/`TELEGRAM_API_HASH`, build ulang |
| Splash berhenti dengan "belum merespons" | native ada tetapi jaringan/TDLib tidak merespons | periksa koneksi, lalu buka ulang aplikasi |
| `InputFile is not specified` saat unggah | `inputDocument` diganti `inputFileLocal` | pertahankan wrapper `inputDocument` di repository |
| `Could not find com.android…` | SDK/AGP tidak cocok | pasang `platforms;android-37`, JDK 17 |
| "Berkas belum tersedia" saat bagikan | unduhan belum selesai / file dihapus | ulangi, tunggu transfer selesai |
| Folder galeri tidak menghasilkan foto | izin SAF dicabut atau folder kosong | pilih ulang folder di Pengaturan → Galeri |
| Sync latar belakang belum jalan | WorkManager menunda sampai jaringan/baterai memenuhi syarat | aktifkan switch-nya di Pengaturan → Galeri, lalu buka aplikasi sekali |
| FileProvider `IllegalArgumentException` | authority tidak cocok | authority adalah `${applicationId}.fileprovider` |
| Play Protect "App scan recommended" saat install APK | APK self-signed dan belum dikenal Play Protect | bukan izin penyimpanan; pilih "Install anyway" atau distribusikan lewat Play. Verifikasi dengan `python3 scripts/check-apk-permissions.py <apk>` |
| CI gagal "version mismatch" | changelog/versi tidak disinkronkan | `python3 scripts/bump-version.py` atau edit entri teratas |

## Catatan rilis dan ukuran APK

APK di-split per ABI. `libtdjson.so` ±31 MB (arm64) dan ±20 MB (v7a); `jniLibs`
dipakai dengan `useLegacyPackaging = true` agar terkompresi saat distribusi.
R8 mengikuti aturan di `androidApp/proguard-rules.pro` yang menjaga JNA dan
`kotlinx.serialization`.
