# Changelog

Semua perubahan yang dirasakan pengguna dicatat di sini. Format setiap rilis:

```text
## <versi> — <tanggal>

### Baru
- satu perubahan per baris

### Perbaikan
- Channel penyimpanan tidak lagi dibuat ulang setiap aplikasi dibuka: id channel disimpan dan divalidasi sebelum mencari atau membuat yang baru.
- Izin DUMP tidak lagi masuk ke APK sehingga peringatan izin Play Protect hilang, dan CI memeriksa daftar izin APK hasil build.
- Pop-up changelog muncul tepat setelah splash saat aplikasi diperbarui, tanpa menunggu sesi Telegram siap.
- APK rilis hanya untuk arm64 sehingga build lebih cepat dan unduhan lebih kecil.
- CI memisahkan build Android arm64 dan paket desktop; rilis tag memakai artefak build yang sudah lulus.
- satu perbaikan per baris
```

`composeApp` membundel file ini saat build, jadi Changelog di aplikasi, pop-up
setelah pembaruan, dan catatan rilis GitHub selalu berasal dari teks yang sama.
Bump versi dengan `python3 scripts/bump-version.py <versi>`.

## 1.3.0 — 23 November 2026

### Baru

- Unggah folder: pilih satu folder di perangkat dan semua berkas di dalamnya diunggah ke folder tujuan sekaligus.
- Sinkronisasi galeri kini menerima beberapa folder sekaligus, masing-masing dengan batch inkremental sendiri.
- Menu optimasi baterai menampilkan versi Android dan menyesuaikan saran dengan aturan Doze/App Standby versi tersebut.
- Rilis kini juga menyertakan paket desktop: installer Linux (deb, rpm) dan Windows (msi).

### Perbaikan

- Channel penyimpanan tidak lagi dibuat ulang setiap aplikasi dibuka: id channel disimpan dan divalidasi sebelum mencari atau membuat yang baru.
- Izin DUMP tidak lagi masuk ke APK sehingga peringatan izin Play Protect hilang, dan CI memeriksa daftar izin APK yang dibangun.
- Pop-up changelog muncul tepat setelah splash saat aplikasi diperbarui, tanpa menunggu sesi Telegram siap.
- APK rilis hanya untuk arm64 sehingga build lebih cepat dan unduhan lebih kecil.
- CI memisahkan build Android arm64 dan paket desktop; rilis tag memakai artefak build yang sudah lulus.
## 1.2.0 — 23 November 2026

### Baru

- Sinkronisasi galeri di latar belakang lewat WorkManager: batch kecil berjalan kira-kira tiap 6 jam saat ada internet dan baterai cukup, tanpa layanan latar depan atau notifikasi.
- Simpan otomatis foto yang diunduh ke galeri perangkat: foto disalin ke `Pictures/Unlimited Cloud` melalui MediaStore, tanpa izin penyimpanan di Android 10 ke atas.

### Perbaikan

- Kartu galeri kini membedakan batas unggahan saat aplikasi dibuka (20 foto) dan saat latar belakang (8 foto), serta menjelaskan izin dan jadwal yang dipakai.

## 1.1.0 — 22 November 2026

### Baru

- Menu Bagikan pada setiap berkas: unduhan disalin ke folder aman aplikasi lalu dikirim lewat chooser Android atau clipboard desktop.
- Sinkronisasi galeri otomatis berbasis Storage Access Framework, tanpa izin penyimpanan, dengan pilihan folder, batas batch, dan status sinkron terakhir.
- Menu Optimasi baterai yang mengikuti kebijakan Google Play: hanya membuka pengaturan sistem, tanpa meminta `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus pintasan autostart untuk OEM populer.
- Menu Changelog di Pengaturan dan pop-up "Yang baru" yang muncul sekali setelah aplikasi diperbarui.
- Versi aplikasi kini berasal dari satu sumber di `gradle.properties`, dipakai APK, paket desktop, kartu Tentang, dan parameter TDLib.

### Perbaikan

- Perpindahan menu Berkas, Transfer, dan Pengaturan memakai animasi yang sama, posisi menu tersimpan saat rotasi, dan log/changelog menjadi tujuan penuh dengan tombol kembali yang konsisten.
- Tombol pencarian, muat ulang, dan urutkan tidak lagi berdesakan hingga terpotong pada layar sempit.
- Ikon Tune dan Archive diganti dengan path resmi Material Symbols agar tidak tampak terpotong.
- Badge transfer aktif kini juga tampil di navigation rail desktop, bukan hanya bottom bar Android.

## 1.0.1 — 2 November 2026

### Baru

- Login Telegram langsung lewat TDLib dengan verifikasi kode dan password dua langkah.
- Channel penyimpanan pribadi dengan folder virtual, pencarian, pengurutan, serta unggah dan unduh berprogres.
- Layar Diagnostik dengan log di perangkat, salin ke clipboard, dan ekspor berkas.
- Tema Sistem, Terang, dan Gelap untuk Android dan Desktop.
