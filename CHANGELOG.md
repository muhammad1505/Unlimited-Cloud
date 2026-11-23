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

## 1.2.6 — 23 November 2026

### Perbaikan

- Pratinjau kini satu ketukan: klik kartu berkas langsung membuka pratinjau. Item "Pratinjau" dihapus dari menu ⋮, yang sekarang hanya berisi Bagikan, Unduh, dan Hapus.
- Daftar berkas memuat halaman berikutnya sendiri saat Anda menggulir sampai bawah, jadi tidak perlu menekan "Muat lebih banyak" untuk channel besar. Satu permintaan kini mengambil beberapa halaman sekaligus.
- Unduh benar-benar menyimpan berkas: gambar masuk ke galeri (atau Pictures/Unlimited Cloud), berkas lain ke folder Download/Unlimited Cloud di Android dan ~/Downloads/Unlimited Cloud di desktop. Sebelumnya berkas non-gambar hanya tertinggal di cache aplikasi.
- Tab Transfer sekarang punya aksi: "Coba lagi" untuk unduhan yang gagal dan "Hapus" untuk entri yang sudah selesai. Riwayat transfer dibatasi 200 entri agar tidak terus membengkak.
- Dialog pratinjau menampilkan tombol "Coba lagi" saat pengambilan berkas gagal, tanpa perlu menutup dialog.
- Logout membersihkan state akun: channel penyimpanan, riwayat sinkron galeri, dan daftar berkas tidak lagi terbawa ke akun berikutnya.
- Cover kartu tidak lagi menunggu satu per satu; thumbnail yang lambat tidak memblokir berkas lain.
- Menu Berkas kini punya mode pilih-banyak: tahan satu kartu (atau pilih "Pilih beberapa" di menu ⋮) untuk memilih beberapa berkas, lalu unduh atau hapus sekaligus dengan bilah aksi di atas daftar.
- Pemutar audio desktop berhenti di akhir lagu dan tombol kembali ke "Putar".
- Sesi TDLib membersihkan permintaan yang gagal dikirim, sehingga tidak ada respons yang tertinggal.

## 1.2.5 — 23 November 2026

### Perbaikan

- Menu Berkas kini memuat seluruh riwayat channel secara otomatis, jadi jumlah berkasnya sama dengan yang tertera di Pengaturan (mis. 288 berkas), bukan berhenti di 50 berkas pertama.
- Cover berkas lama ikut muncul: bila berkas tanpa thumbnail sudah ada di cache TDLib (pernah dipratinjau atau diunduh), aplikasi membuat thumbnail dari salinan lokal itu tanpa mengunduh ulang.

### Baru

- Dialog pratinjau menampilkan progres unduhan dengan persentase saat menyiapkan berkas besar, bukan spinner tanpa keterangan.
- Di desktop, berkas audio WAV bisa diputar lewat dialog pratinjau dengan bilah posisi dan tombol putar/jeda.

## 1.2.4 — 23 November 2026

### Baru

- Unggahan gambar kini membawa thumbnail 320 px, jadi cover mini muncul di daftar dan grid sejak berkas pertama diunggah, bukan hanya untuk berkas yang thumbnail-nya sudah ada.
- Berkas audio (mp3, m4a, aac, wav, flac, ogg, opus) bisa diputar di dialog pratinjau Android lengkap dengan bilah progres, tombol putar/jeda, dan durasi berjalan.
- Di desktop, tombol panah kiri/kanan berpindah berkas di dialog pratinjau dan Escape menutup dialog, tanpa perlu klik tombol.

### Perbaikan

- Thumbnail hanya dibuat untuk berkas gambar yang bisa didekode, sehingga unggahan video, arsip, atau dokumen tetap berjalan seperti sebelumnya tanpa biaya tambahan.

## 1.2.3 — 23 November 2026

### Baru

- Pratinjau kini juga memutar video dengan kontrol bawaan dan menampilkan halaman PDF di Android, selain gambar dan teks; semuanya tetap dibaca dari cache TDLib tanpa menyimpan ke perangkat.
- Dialog pratinjau punya tombol sebelumnya/berikutnya dan penghitung berkas (mis. 3/12), jadi bisa berpindah berkas tanpa menutup dan membuka ulang dialog.
- Daftar dan grid menampilkan cover mini dari thumbnail TDLib untuk berkas yang memilikinya, sehingga gambar dan video lebih mudah dikenali dibanding ikon jenis berkas saja.

### Perbaikan

- Di desktop, pratinjau video dan PDF menjelaskan bahwa pemutaran belum tersedia beserta saran mengunduh, bukan pesan umum bahwa jenis berkas tidak bisa dipratinjau.

## 1.2.2 — 23 November 2026

### Baru

- Berkas gambar dan teks bisa dipratinjau langsung di aplikasi lewat menu Pratinjau; isinya dibaca dari cache TDLib, jadi tidak perlu diunduh ke penyimpanan atau galeri perangkat.
- Sinkronisasi galeri mengunggah semua foto baru di folder terpilih, tidak lagi berhenti setelah 20 foto; jadwal latar belakang tetap memakai batch kecil agar muat dalam batas waktu sistem.
- Menu optimasi baterai menampilkan nama dan id aplikasi, ditambah tombol Info aplikasi untuk membuka bagian Baterai di pengaturan sistem.

### Perbaikan

- Daftar berkas tidak lagi kosong setelah aplikasi ditutup: error sementara TDLib saat sesi baru dibuka tidak lagi dianggap channel hilang, dan daftar dimuat ulang otomatis beberapa kali sebelum menyerah.
- Pintasan autostart bisa dibuka lagi di Android 11 ke atas karena paket pengaturan pabrikan didaftarkan di manifest; bila layar itu memang tidak ada, aplikasi membuka Info aplikasi dan menjelaskan tujuannya alih-alih menyebut perangkat tidak mendukung.

## 1.2.1 — 23 November 2026

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
