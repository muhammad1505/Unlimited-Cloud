# Rilis

## Satu sumber versi

`gradle.properties` memegang satu nilai:

```properties
unlimitedcloud.versionName=1.1.0
```

Dipakai oleh:

- `androidApp` → `versionName`, `versionCode` (turunan)
- `composeApp` → `packageVersion` desktop
- `AppBuildInfo` → kartu Tentang, parameter `application_version` TDLib, gate
  changelog

`versionCode = major*10000 + minor*100 + patch`, jadi `1.1.0` → `10100` dan selalu
naik bersama versi. Jangan menulis versi di source Kotlin.

## Langkah bump

```bash
python3 scripts/bump-version.py 1.2.0
```

Perintah itu:

1. Memastikan versi baru lebih tinggi dan berformat `MAJOR.MINOR.PATCH`.
2. Mengganti `unlimitedcloud.versionName` di `gradle.properties`.
3. Menyisipkan bagian rilis baru di atas `CHANGELOG.md`, lengkap dengan komentar
   pengingat.

Lalu edit `CHANGELOG.md`:

```markdown
## 1.2.0 — 22 November 2026

### Baru

- Fitur yang dilihat pengguna.

### Perbaikan

- Perbaikan yang terasa.
```

Tulis untuk pengguna, bukan commit log. Satu baris = satu perubahan. Bagian tanpa
bullet tidak akan ditampilkan di pop-up (tetapi judul rilis tetap tercatat).

Verifikasi:

```bash
python3 scripts/check-versions.py
python3 scripts/preflight.py
```

## Alur CI

```text
push / PR ke main
  ├─ static-checks : preflight, version+changelog, izin, regression guards
  ├─ android       : kompilasi desktop, fetch TDLib arm64, APK release bertanda
  │                  tangan, verifikasi apksigner + daftar izin APK, artefak APK
  ├─ desktop-linux : packageDeb + packageRpm di Ubuntu
  └─ desktop-windows: packageMsi di Windows

tag vX.Y.Z
  └─ release       : tag harus sama dengan gradle.properties, ambil APK + deb +
                     rpm + msi dari build main yang sukses untuk commit ini, buat
                     GitHub Release
```

Karena job release tidak membangun ulang, commit harus sudah lolos build di `main`
sebelum tag dibuat.

## Langkah rilis

1. Merge perubahan ke `main`, tunggu CI hijau.
2. Bump versi + isi changelog, merge lagi.
3. Buat tag dan push:

   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

4. Job release memverifikasi tag, mengunduh APK hasil build `main`, dan
   mempublikasikan release. Catatan rilis GitHub dibuat otomatis; untuk isi yang
   sama dengan aplikasi, salin bagian teratas `CHANGELOG.md` bila perlu.
5. Unduh APK dari halaman release dan uji instalasi pada perangkat bersih:
   login, unggah, unduh, bagikan, galeri, simpan foto ke galeri perangkat, sync
   latar belakang (buka Pengaturan → Galeri, lalu tutup aplikasi), baterai,
   changelog.

## Checklist Google Play

- [ ] Tidak ada izin penyimpanan di manifest (`READ_MEDIA_*`,
      `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`).
- [ ] Tidak ada `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; panduan baterai hanya
      membuka pengaturan sistem.
- [ ] `python3 scripts/check-apk-permissions.py` lolos: hanya INTERNET,
      ACCESS_NETWORK_STATE, dan izin WorkManager yang tersisa (tanpa `DUMP`, tanpa
      izin penyimpanan).
- [ ] Sync latar belakang tetap memakai WorkManager tanpa layanan latar depan dan
      tanpa izin media/penyimpanan.
- [ ] Data safety: tidak ada data yang dikirim ke server aplikasi; berkas tersimpan
      di akun Telegram pengguna.
- [ ] Kebijakan privasi tertaut (lihat `docs/PRIVACY.md`).
- [ ] `versionCode` lebih besar dari rilis sebelumnya.
- [ ] APK yang diunggah adalah artefak release yang ditandatangani, bukan debug.
- [ ] Deskripsi toko menyebut kebutuhan akun Telegram.

## Rollback

- Play Console: hentikan peluncuran bertahap atau unggah build dengan versionCode
  lebih tinggi yang berisi perbaikan (versionCode tidak bisa diturunkan).
- GitHub: tandai release sebagai pre-release atau hapus aset; jangan menghapus tag
  yang sudah dipakai bila masih diperlukan untuk audit.
- Perbaikan aplikasi didistribusikan lewat changelog yang sama; pop-up hanya muncul
  ketika versionCode meningkat.
