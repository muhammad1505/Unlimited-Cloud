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

## 1.4.3 — 27 November 2026

### Baru

- Pengaturan → Transfer punya sakelar baru "Hapus salinan setelah terunggah". Salinan sementara yang dibuat aplikasi di cache ponsel langsung dihapus begitu unggahan berhasil, jadi penyimpanan tidak penuh oleh sisa unggahan. Berkas asli di folder kamu tidak disentuh.
- Status dan notifikasi unggahan kini menyebut ruang yang dibebaskan, misalnya "240 MB cache dibersihkan".

### Perbaikan

- Berkas tunggal dan beberapa berkas sekaligus tidak lagi meninggalkan salinan di cache sampai 24 jam; pembersihannya sekarang sama dengan unggahan folder dan sinkron galeri.
- Di desktop sakelar ditampilkan nonaktif dengan keterangan, karena berkas diunggah langsung dari lokasi aslinya sehingga tidak ada salinan yang perlu dihapus.

## 1.4.2 — 27 November 2026

### Baru

- Pengaturan → Penyimpanan kini punya tombol "Rapikan berkas lama" untuk memindahkan sisa berkas tanpa topik ke topik jenisnya kapan pun kamu mau. Bila daftar berkas belum termuat penuh, aplikasi memuat seluruh riwayat lebih dulu supaya tidak ada yang terlewat.

### Perbaikan

- Berkas lama yang gagal dirapikan tidak lagi dibiarkan di General: id-nya dicatat, lalu dicoba ulang otomatis pada peluncuran berikutnya atau lewat tombol rapikan di atas.
## 1.4.1 — 27 November 2026

### Baru

- Berkas kini otomatis masuk topik sesuai jenisnya: Foto, Video, Audio, Dokumen, Arsip, Aplikasi, atau Lainnya. Memilih folder tidak lagi wajib, dan berkas yang jenisnya tidak dikenali tetap punya topik sendiri.
- Sinkron galeri ikut memakai topik jenis sekaligus mempertahankan subfolder aslinya, jadi foto dari `DCIM/Camera` masuk ke Foto/DCIM/Camera, bukan menumpuk di satu folder Galeri.
- Sakelar "Kelompokkan otomatis" di Pengaturan → Penyimpanan mematikan pengelompokan bila kamu lebih suka memilih folder sendiri; unggahan kembali ke General dan sinkron galeri kembali ke folder Galeri.

### Perbaikan

- Berkas yang sudah diunggah sebelum fitur ini (tanpa folder) dirapikan sekali per akun: disalin ke topik jenisnya, diberi caption folder, lalu pesan lama dihapus. Penyalinan selalu berjalan sebelum penghapusan, jadi berkas tidak hilang bila penyalinan gagal.
- Berkas lama tanpa folder kini dikelompokkan menurut jenisnya saat dibaca, sehingga tidak ada lagi berkas yang tersembunyi di "Tanpa folder" padahal sudah masuk grup.
- Dialog unggah menyebut "Otomatis" (atau "Tanpa folder" saat sakelar dimatikan) dan menjelaskan bahwa jenis berkas menentukan topiknya.

## 1.4.0 — 27 November 2026

### Baru

- Grup penyimpanan Telegram kini memakai ikon aplikasi sebagai foto profilnya, jadi mudah dikenali di antara chat lain; ikon diterapkan otomatis saat grup dipilih.
- Nama grup penyimpanan bisa diubah langsung dari Pengaturan → Penyimpanan (maksimal 128 karakter), dengan tombol Kembalikan bawaan untuk kembali ke “Unlimited Cloud”.
- Foto kustom untuk grup penyimpanan bisa dipilih dari galeri (Android) atau berkas gambar di komputer (desktop), bila ikon aplikasi kurang cocok.
- Tombol Buka Telegram membuka grup penyimpanan langsung di aplikasi Telegram lewat tautan dalam, dengan cadangan tautan web bila aplikasi tidak terpasang.
- Kartu penyimpanan menampilkan pratinjau grup dan dua sakelar: Bisukan grup dan Arsipkan grup. Grup dibisukan secara bawaan karena setiap unggahan memposting pesan, dan bisa dikeluarkan dari arsip lagi.

### Perbaikan

- Nama grup yang diubah tidak lagi membuat grup penyimpanan baru atau meninggalkan grup lama: id grup yang tersimpan tetap menjadi bukti kepemilikan, dan nama hanya dipakai untuk pencarian.
- Deskripsi grup penyimpanan kini memuat penanda versi, sehingga grup dengan nama generik bisa dipastikan milik aplikasi sebelum dipakai, alih-alih dibuat ulang.
- Grup lama yang topiknya dimatikan tidak lagi memicu pembuatan grup baru; topik diaktifkan kembali pada grup milik aplikasi.
- Identitas grup (id, nama, keadaan bisu dan arsip, versi deskripsi) dibersihkan saat akun diganti, sehingga pengaturan akun lama tidak terbawa ke akun baru.

## 1.3.9 — 27 November 2026

### Baru

- Transfer paralel kini bisa diatur lewat Pengaturan → Transfer → Berkas sekaligus (1–6, bawaan 3), jadi antrean besar selesai lebih cepat tanpa membanjiri koneksi.
- Aplikasi desktop kini bisa menyinkronkan folder: pilih folder di komputer, lalu seluruh isinya diunggah langsung dari lokasi aslinya, termasuk subfolder, tanpa menyalin ke cache.
- Tray desktop menampilkan jumlah transfer aktif, keadaan sinkron folder, dan progres di tooltip, serta mengirim balon notifikasi saat transfer selesai atau gagal walau jendela aplikasi sudah ditutup.
- Menu tray memuat baris status dan perintah Sinkronkan folder sekarang, sehingga sinkron bisa dimulai tanpa membuka jendela.

### Perbaikan

- Sinkron folder tidak lagi hanya mengunggah foto dan video: semua jenis berkas ikut, struktur subfolder dipertahankan, dan berkas yang dilewati dilaporkan (lewat status, log, dan notifikasi) alih-alih dibuang diam-diam. Batas 500 berkas pada pemilih folder juga hilang.
- Sinkron folder Android memakai setengah ruang kosong cache sebagai anggaran per batch (minimal 512 MiB bila sisanya tak terbaca), sehingga video besar tetap bisa disinkronkan tanpa memenuhi penyimpanan. Berkas yang tak muat dilewati dan dicoba lagi pada sinkron berikutnya, tidak dianggap gagal permanen.
- Daftar Transfer kini mengikuti baris terbaru saat transfer baru muncul, tetapi hanya bila kamu masih berada di dekat puncak daftar; transfer lama yang sedang dibaca tidak lagi tertarik ke atas.
- Notifikasi transfer menampilkan progres selama unggahan atau unduhan berjalan, lalu berubah menjadi hasil selesai atau gagal.
- Pratinjau berkas langsung menampilkan sampul yang sudah tersimpan sambil versi penuhnya diunduh, jadi tidak ada lagi layar kosong dengan spinner.

## 1.3.8 — 26 November 2026

### Baru

- Menu Berkas kini berupa pohon folder bertingkat yang bisa dilipat. Folder yang punya subfolder menampilkan panah lipat, dan mengetuk barisnya membuka atau menutup cabangnya.
- Dialog Unggah kini menerima banyak berkas sekaligus; berkas diunggah berurutan dalam satu antrean agar daftar transfer tetap mudah dibaca. Memilih satu folder dari perangkat juga menyalin seluruh isinya dan membuat ulang struktur subfoldernya, misalnya Liburan/Pantai.
- Setiap unggahan dan unduhan melapor lewat notifikasi sistem Android saat selesai atau gagal, sehingga hasilnya tetap terlihat walau aplikasi sedang di latar belakang. Ketuk notifikasi untuk membuka aplikasi.

### Perbaikan

- Folder induk yang hanya berisi subfolder kini tetap muncul di pohon, lengkap dengan jumlah berkas dari seluruh isinya, sehingga struktur seperti Liburan/Pantai bisa ditelusuri dari atas.
- Membuka folder kini sekaligus menampilkan berkas di dalam subfoldernya; sebelumnya folder induk terbuka kosong karena hanya berkas dengan path yang sama persis yang dihitung.
- Baris chip folder di atas daftar hanya menampilkan cabang teratas, jadi path panjang tidak lagi memenuhi layar.

## 1.3.7 — 24 November 2026

### Baru

- Menu Berkas kini dibuka sebagai kisi folder yang ringkas: tiap folder tampil sebagai satu kartu berisi nama, jumlah berkas, dan ukuran, termasuk "Tanpa folder" untuk berkas tanpa folder. Ketuk folder untuk melihat isinya, dan tombol kembali mengantar ke daftar folder.
- Daftar berkas dimuat ulang otomatis setiap kali menu Berkas dibuka, jadi berkas yang baru selesai diunggah langsung muncul tanpa perlu menekan muat ulang.
- Setiap proses (unggah, unduh, hapus, sinkronisasi foto) kini melapor lewat satu notifikasi mengambang di dalam aplikasi yang tetap terlihat di tab mana pun, lalu hilang sendiri.

### Perbaikan

- Topik folder tidak lagi terbuat dua kali atau berulang saat beberapa unggahan berjalan bersamaan. Pencarian dan pembuatan topik kini berjalan satu per satu dan dipakai bersama oleh seluruh bagian aplikasi.
- Gagal memuat daftar topik karena koneksi lambat tidak lagi dianggap "topik belum ada", sehingga aplikasi tidak membuat topik kedua.
- Pratinjau audio dipadatkan menjadi satu strip pemutar tipis tanpa ikon besar, sehingga lebih rapi di ponsel maupun jendela desktop.
- Tombol hapus pada setiap baris transfer dihilangkan karena tombol Bersihkan sudah menangani pembersihan daftar.
- Log aplikasi tidak lagi dipenuhi lalu lintas TDLib. Yang dicatat hanya langkah aplikasi, perubahan status sesi dan koneksi, serta kesalahan.
- Kartu "Galeri" di Pengaturan diganti nama menjadi "Sinkron foto" dan "Folder galeri" menjadi "Folder foto", agar tidak tertukar dengan menu Berkas. Nama topik Galeri di Telegram tetap sama supaya berkas lama tidak terpisah.

## 1.3.6 — 24 November 2026

### Baru

- Bagian "Instalasi APK" di README menjelaskan peringatan Play Protect saat sideload: tombol yang dipilih untuk melanjutkan, jalur `adb install`, dan cara menghilangkan peringatan "developer belum terverifikasi" lewat pendaftaran paket di Android Developer Console.

### Perbaikan

- Panduan rilis kini mencatat bahwa catatan rilis GitHub masih memakai catatan otomatis dan harus diganti dengan bagian teratas CHANGELOG, serta memperingatkan agar keystore rilis tidak diganti karena pendaftaran developer terikat pada sertifikatnya.

## 1.3.5 — 24 November 2026

### Perbaikan

- Setelan tidak lagi bisa hilang atau rusak saat aplikasi ditutup di tengah penulisan: berkas setelan ditulis lewat berkas sementara lalu diganti sekaligus, dan id grup penyimpanan punya salinan cadangan. Sebelumnya kegagalan di sini bisa membuat aplikasi lupa grupnya lalu membuat grup baru.
- Menghapus berkas saat daftar masih dimuat tidak lagi ikut menghapus topik folder beserta berkas lama yang belum sempat tampil.
- Seleksi lebih dari 100 berkas kini bisa dihapus sekaligus: permintaan dipecah sesuai batas TDLib, bukan ditolak seluruhnya.
- Folder yang topiknya sudah dihapus atau diganti nama dari aplikasi Telegram tetap bisa diunggah: aplikasi membuat topik baru, lalu memakai General bila topik tetap ditolak.
- Unduhan besar tidak lagi gagal setelah 10 menit. Unduhan hanya dihentikan bila benar-benar berhenti membuat progres selama 5 menit, jadi berkas besar di koneksi lambat tetap selesai.
- Sinkron galeri tidak lagi berjalan dua kali bersamaan, tidak lagi menyalin ratusan foto hanya untuk mengunggah segelintir, dan foto yang gagal tiga kali dilewati agar tidak menghabiskan kuota setiap jadwal.
- Nama berkas non-ASCII (emoji atau huruf beraksen) tidak lagi rusak saat diunggah dari desktop Windows.
- Sesi TDLib ditutup rapi saat keluar dari aplikasi desktop, sehingga basis data lokal tidak ditinggal di tengah penulisan.
- Memilih folder untuk diunggah tidak lagi membekukan layar: penyalinan berjalan di latar belakang, berkas dengan nama sama tidak saling menimpa, dan salinan lama dibersihkan.
- Cover dan berkas bagikan di cache tidak lagi menumpuk tanpa batas: nama berkas stabil per sumber dan salinan lama dipangkas.
- Daftar berkas lebih ringan saat digulir karena filter dan pengurutan dihitung sekali per perubahan, bukan pada setiap penggambaran ulang.
- SQLDelight yang tidak pernah dipakai dihapus dari build sehingga waktu build lebih singkat.

## 1.3.4 — 24 November 2026

### Perbaikan

- Paket desktop deb/rpm/msi kini menyertakan native TDLib yang versinya dipin dan checksum-nya diverifikasi, sehingga login tidak lagi berhenti karena pustaka TDLib tidak ditemukan.
- Ikon Unlimited Cloud kini juga tampil di launcher dan tray desktop; entri menu Linux punya kategori Utility, dan menutup jendela menyisakan ikon tray untuk membuka lagi atau keluar.
- Pesan kegagalan login tidak lagi selalu menyalahkan kredensial API: native yang hilang, build tanpa kredensial, dan jaringan yang diam punya penjelasan masing-masing.
- Berkas APK rilis kini bernama `UnlimitedCloud-<versi>-arm64-v8a.apk`, seragam dengan paket desktop, bukan nama modul `androidApp`.
- Pemeriksaan paket desktop di CI kini ikut membuka cab di dalam installer msi, jadi pemeriksaan isi paket tidak lagi gagal hanya karena app image msi masih terbungkus.

## 1.3.3 — 24 November 2026

### Perbaikan

- Navigasi bawah tidak lagi naik ke tengah layar saat kolom pencarian dipilih: bilahnya menempel di dasar, dan selama papan ketik terbuka bilah itu menyelinap pergi sementara daftar berkas yang bergeser memberi ruang untuk mengetik.
- Topik folder yang berkasnya sudah habis kini terhapus sendiri dari grup Telegram, jadi tidak ada lagi tab kosong yang menumpuk. Topik hanya dihapus setelah seluruh riwayat folder selesai dimuat supaya berkas di halaman lama tidak ikut hilang.

## 1.3.2 — 23 November 2026

### Perbaikan

- Penyimpanan dibuat sebagai supergroup privat biasa lalu topiknya diaktifkan, bukan dengan meminta forum saat pembuatan. Log perangkat menunjukkan permintaan forum justru menghasilkan channel yang menolak diaktifkan sebagai forum, jadi penyimpanan kini tidak pernah jatuh ke channel.
- Chat hasil pembuatan diperiksa dulu: kalau bukan supergroup privat, chat itu dihapus lagi dan aplikasi melaporkan alasannya di Pengaturan → Penyimpanan.
- Setelah pembuatan gagal, aplikasi tidak lagi membuat grup/channel baru berulang kali dalam satu menit, sehingga tidak ada tumpukan chat penyimpanan yang tertinggal.

## 1.3.1 — 23 November 2026

### Perbaikan

- Chat penyimpanan hanya dipakai kalau Telegram melaporkan supergroup forum dan bukan channel. Channel sisa versi 1.2.x tidak bisa lagi dianggap sebagai penyimpanan, jadi aplikasi tidak pernah mengunggah berkas ke channel.
- Grup topik punya jalur cadangan: kalau pembuatan forum ditolak server, aplikasi membuat supergroup biasa lalu mengaktifkan topik lewat `toggleSupergroupIsForum`, dan chat yang tetap bukan forum dihapus lagi.
- Channel penyimpanan lama dibersihkan semua, termasuk yang tertinggal dari beberapa kali pemasangan, dan alasan penolakan dari TDLib sekarang tampil di Pengaturan → Penyimpanan.

## 1.3.0 — 23 November 2026

### Baru

- Folder kini benar-benar terlihat di Telegram: penyimpanan pindah ke grup privat dengan topik forum dan setiap folder menjadi satu topik. Unggahan ke folder baru membuat topiknya otomatis, sedangkan berkas tanpa folder masuk ke topik General.

### Perbaikan

- Grup topik kini benar-benar dibuat: permintaan ke TDLib menyertakan lokasi yang wajib, sehingga aplikasi berhenti di channel lama dan tidak lagi membuat channel pribadi sebagai penyimpanan.
- Channel penyimpanan versi 1.2.x dihapus otomatis lalu diganti grup topik. Berkas di channel lama tidak disalin, jadi unggah ulang berkas yang masih dibutuhkan.
- Pratinjau audio memakai satu kartu pemutar yang ringkas dan rapi: tombol putar bulat, bilah waktu tipis, dan durasi berjalan sejajar.

## 1.2.9 — 23 November 2026

### Baru

- Kontrol pemutar audio di pratinjau kini tampil sebagai tombol putar bulat dengan bilah geser dan waktu berjalan; posisi baru dicari saat jari atau mouse dilepas sehingga pemutaran tidak tersendat.

## 1.2.8 — 23 November 2026

### Baru

- Pratinjau audio di desktop kini memutar MP3 dan FLAC, bukan hanya WAV. Dekode berjalan di latar belakang dan kontrol pemutar muncul begitu trek siap, lengkap dengan pencarian posisi.

### Perbaikan

- Daftar berkas tampil secepat halaman pertama dimuat: sisa riwayat menyusul di latar belakang sambil Anda menggulir, bukan menunggu semua halaman selesai. Membuka berkas juga tetap terasa instan.
- Menu Berkas tidak lagi hanya menampilkan satu berkas saat aplikasi baru dibuka. Riwayat dibaca setelah chat disiapkan, dan halaman pendek tidak lagi dianggap akhir riwayat, jadi tidak perlu menekan muat ulang untuk melihat semuanya.
- Setiap kartu hanya mencari status transfernya sekali, sehingga menggulir daftar panjang tidak memindai seluruh riwayat transfer per kartu.

## 1.2.7 — 23 November 2026

### Perbaikan

- Menu Pengaturan dirapikan: kartu Pembaruan dan Tentang digabung, versi aplikasi hanya muncul sekali, dan baris "Apa yang baru" serta "Log diagnostik" langsung bisa diklik tanpa tombol ganda di bawahnya.
- Pengaturan galeri disatukan dalam satu kartu: folder, unggah otomatis, sinkron latar belakang, simpan foto yang diunduh, dan aksi sinkron kini berada di tempat yang sama.
- Logout hanya punya satu alur konfirmasi. Ikon keluar duplikat di rail desktop dihapus; aksi keluar tetap tersedia di Pengaturan dan dialognya dikelola oleh shell aplikasi.
- "Atur ulang riwayat" tidak lagi langsung mengunggah ulang seluruh galeri. Konfirmasi menjelaskan dampaknya, dan foto lama baru diproses saat Anda menekan "Sinkronkan sekarang".
- Kartu baterai lebih ringkas: status, saran sesuai versi Android, dan pintasan ke pengaturan sistem tersusun dalam satu tempat.
- Aksi "Muat ulang daftar" di Penyimpanan diberi nama yang jelas agar tidak tertukar dengan sinkronisasi galeri.
- APK tidak lagi membawa izin foreground service bawaan WorkManager maupun penanda DUMP dari receiver diagnostik/profile installer. Tidak ada izin penyimpanan atau media; galeri tetap memakai SAF. CI kini memeriksa manifest APK jadi, jadi izin penyimpanan tidak bisa muncul tanpa sengaja.
- Peringatan Play Protect saat memasang APK sideload dijelaskan di README dan docs/PRIVACY.md: itu peringatan aplikasi belum dikenal, bukan permintaan akses penyimpanan.

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
