# Kontribusi

Terima kasih sudah membantu Unlimited Cloud. Proyek ini Kotlin Multiplatform
(Android + Desktop) dengan TDLib sebagai backend Telegram.

## Sebelum mulai

Baca:

- [README.md](README.md) — gambaran dan mulai cepat.
- [AGENTS.md](AGENTS.md) — aturan lingkungan dan konvensi wajib.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — alur kode.

## Aturan kode

- `commonMain` tidak boleh mengimpor `android.*`, `java.io.File`, `javax.*`, atau
  binding TDLib. Gunakan `okio` atau tambahkan `expect`/`actual`.
- Semua callback TDLib dibungkus `Flow` atau `suspendCancellableCoroutine`.
- UI harus responsif di ponsel portrait dan jendela desktop. Perhatikan breakpoint
  `920.dp` untuk rail vs bottom bar.
- Jangan menulis versi aplikasi di source Kotlin; gunakan `AppBuildInfo`.
- Jangan menambah izin Android tanpa pembahasan; akses berkas lewat SAF.
- Pertahankan teks UI berbahasa Indonesia agar konsisten.

## Lingkungan pengembangan mobile

Jika bekerja dari Termux/Android tanpa Android SDK:

- **Jangan** menjalankan `./gradlew`, `gradle`, atau `adb`.
- Jalankan `python3 scripts/preflight.py` sebelum commit.
- Push, lalu pantau CI: `gh run watch --exit-status`. Jika gagal:
  `gh run view --log-failed`, perbaiki, ulangi.

## Pemeriksaan wajib

```bash
python3 scripts/preflight.py
python3 scripts/check-versions.py
python3 -m py_compile scripts/*.py
```

`preflight.py` memeriksa import terlarang di `commonMain`, bracket tidak seimbang,
dan sisa conflict marker. CI juga memeriksa izin penyimpanan, FileProvider,
kebijakan baterai, dan guard regresi lain.

## Menambah fitur platform

1. Deklarasikan API di `commonMain` dengan `expect`/`interface` kecil.
2. Implementasikan di `androidMain` dan `desktopMain`.
3. Beri nilai `supported = false` / no-op yang jelas di desktop bila fitur memang
   khas Android.
4. Daftarkan di Koin hanya jika dipakai lintas layar; API composable
   (`remember…`) tidak perlu DI.
5. Dokumentasikan di `docs/ARCHITECTURE.md` dan README bila terlihat pengguna.

## Perubahan versi dan changelog

- Fitur/perbaikan yang terlihat pengguna harus masuk `CHANGELOG.md`.
- Naikkan versi dengan `python3 scripts/bump-version.py <versi>` pada rilis.
- Jangan mengubah `versionCode` secara manual; nilainya diturunkan dari versi.

## Pull request

Sertakan:

- Ringkasan masalah dan solusi.
- Perubahan yang terlihat pengguna (screenshot bila relevan).
- Hasil `preflight.py` dan `check-versions.py`.
- Catatan kompatibilitas Android/Desktop.

Jaga PR fokus: satu topik per PR. Hindari refactor besar yang tidak diminta.

## Commit

Gunakan kalimat imperatif yang menjelaskan alasan, misalnya:

```text
Perbaiki tombol urutkan yang terpotong di layar sempit

Toolbar memaksa tiga tombol bulat dalam satu baris; di ponsel 360dp
tombol terakhir terpotong. Sortir dipindah ke baris sendiri untuk compact.
```
