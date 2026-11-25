# Secreto Space 0.1.0 - Rencana Produk

Dokumen ini menjawab satu pertanyaan: apa yang harus diubah supaya FrameBoost
berhenti menjadi "modul tweak dengan UI" dan benar-benar menjadi game space.

Konteks hasil riset dan probe perangkat ada di bagian 1 dan 2. Rencana kerja ada
di bagian 5.

---

## 1. Apa yang komunitas root sebenarnya buat

Tiga pola yang berulang di modul game space komunitas:

**Pola A - Session enforcer (Faa-GameCore dan sejenisnya)**
Inti produknya bukan clock, tapi **kebijakan app selama sesi game**: blacklist,
whitelist, deteksi game otomatis, freeze reversibel, profil per game, jurnal
yang ditulis sebelum app dibekukan, empat titik pemulihan (akhir sesi, boot,
status, core mati), WebUI, dan capability probe runtime tanpa hardcode device.

**Pola B - Restore stack OEM (oneui-gamebooster dan sejenisnya)**
Mengembalikan Game Booster / GOS / Game Launcher Samsung sebagai priv-app.
Syaratnya berat: ROM harus Samsung-signed dan masih membawa `gamemanager.jar`.
Di GSI, jalur ini **tidak mungkin**, karena signature permission tidak akan
pernah diberikan.

**Pola C - Kernel/sysfs tweak murni (LKT, NFS, FDE, GPU floor booster)**
Menyentuh cpuset, uclamp, IO, zram, lmkd, GPU floor. Dampak nyata di benchmark,
tapi tanpa UI sesi, tanpa restore yang terlihat, dan sering tanpa laporan.

Kesimpulan: game space yang dianggap "benar" oleh komunitas adalah **A + sedikit
C**, bukan C saja. FrameBoost sekarang 80 persen C dan hanya 20 persen A.

---

## 2. Fakta perangkat (hasil probe, bukan asumsi)

- `ro.product.device = a50`, `ro.product.model = SM-A505F`
- `ro.build.flavor = gsi-user`, `ro.product.system.name = a50dd`, API 36 (Android 16)
- RAM total 3.65 GB, zram 2.8 GB (terpakai 346 MB)
- GOS tidak ada (`has_gos=false`), jadi jalur Pola B tertutup
- `am set-inactive` tersedia (app standby, reversibel, tanpa disable paket)
- `/dev/cpuset` ada, tapi `cgroup.controllers` hanya `memory`, jadi cpuset
  tidak bisa diandalkan sebagai fitur utama
- `wm size` 1080x2340, density 420
- 36 app user terpasang, jadi daftar freeze realistis dan kecil

Artinya positioning yang jujur: **A50 + GSI + root, di mana Game Booster Samsung
hilang dan tidak bisa dipulihkan.** FrameBoost sudah mengisi kekosongan itu,
tapi belum dalam bentuk game space.

---

## 3. Definisi kerja "game space"

Sebuah game space punya enam lapisan. Urutannya penting.

| Lapisan | Isi | Status sekarang |
|---|---|---|
| 1. Entry | library game, config per game, auto session saat game foreground | sebagian |
| 2. Mode policy | satu paket perubahan yang berlaku selama sesi, atomik | sebagian (sysfs saja) |
| 3. Overlay | status + kontrol cepat in-game | kuat |
| 4. Exit/restore | jaminan balik ke stock, terlihat dan bisa diaudit | kuat |
| 5. Laporan sesi | durasi, FPS avg/1% low, jank, suhu puncak, baterai, app yang ditidurkan | **belum ada** |
| 6. Capability probe | tahu apa yang didukung ROM dan jujur kalau tidak | sebagian |

Yang paling kurang adalah lapisan 2 dan 5. Lapisan 3 dan 4 justru sudah lebih
baik dari kebanyakan modul komunitas, jadi jangan diutak-atik dulu selain
penambahan kecil.

---

## 4. Keputusan produk untuk 0.1.0

1. **Sesi adalah objek utama, bukan preset.** Preset jadi properti sesi.
2. **Manajemen app latar adalah fitur unggulan**, bukan bonus. Di RAM 3.65 GB
   ini dampaknya lebih besar daripada menaikkan min clock.
3. **Primitif freeze default `am set-inactive`**, bukan `pm disable-user`.
   Disable hanya sebagai opsi advanced, karena itu yang bikin orang bootloop.
4. **Tidak ada angka palsu.** FPS tetap dari SurfaceFlinger layer.
5. **A50 saja, dan itu ditegakkan di kode**, bukan hanya di deskripsi.
6. **Laporan sesi** jadi pembeda yang tidak dimiliki modul lain maupun OEM.
7. **Buang yang mati di ROM ini.** Toggle GOS hilang otomatis saat `has_gos=false`.

---

## 5. Rencana kerja

### Phase 0 - Identitas dan batas perangkat (S)

- `module.prop`: id `secreto_space`, name `Secreto Space`, version `v0.1.0`,
  description menyebut A50 dan GSI secara eksplisit.
- `versionName 0.1.0`, `versionCode 1000` (skema: 0.1.1 -> 1001, 0.2.0 -> 2000).
- Guard di `customize.sh` dan di daemon: `ro.product.device` harus `a50`.
  Kalau bukan, abort dengan pesan jelas, jangan setengah terpasang.
- Migrasi: baca `/data/adb/a50_frameboost/`, salin `game_profiles.json` ke
  lokasi baru, hapus modul lama, hapus APK lama (`com.a50.frameboost.hud`).
- Ganti path runtime: socket `/data/local/tmp/secreto.sock`, snapshot
  `/data/local/tmp/secreto-snapshot.json`, log `/data/local/tmp/secretod.log`.
  SnapshotReader membaca path lama sebagai fallback satu versi.

Rename menyentuh 30 file. Daftar lengkap: `module.prop`, `customize.sh`,
`service.sh`, `uninstall.sh`, `hud-app/AndroidManifest.xml`, direktori
`src/com/a50/frameboost/hud/`, `res/values/strings.xml`, `res/layout/`,
`Cargo.toml`, semua modul Rust, `build-hud-apk.ps1`, `deploy-device.ps1`,
`make-zip.py`, `smoke-test.ps1`, `README.md`, `PROJECT_REPORT.md`.

### Phase 1 - Session engine (M)

- `session.rs`: IDLE -> PREPARING -> ACTIVE -> RESTORING -> IDLE, dengan jurnal
  JSON ditulis **sebelum** setiap perubahan yang berisiko.
- `apps.rs`: whitelist/blacklist, `am set-inactive true/false`, opsi
  `pm disable-user` untuk mode dalam. Perlindungan wajib: diri sendiri,
  launcher, dialer, SMS default, IME, alarm, paket sistem, dan whitelist.
- Pemulihan di empat titik: akhir sesi, start daemon, boot, dan saat jurnal
  ditemukan dalam keadaan tidak selesai.
- Policy sesi yang diterapkan atomik: DND, kunci brightness, kunci rotasi, BBR,
  preset, mode monitor.
- Panel: tombol `RESTORE NOW` untuk panik satu tap.

### Phase 2 - Laporan sesi (M)

- Setelah sesi: durasi, FPS rata-rata, 1% low, jank %, suhu puncak, delta
  baterai, jumlah app yang ditidurkan, preset, jumlah event throttle.
- Disimpan per game, 10 sesi terakhir, tampil di halaman detail game.
- Dipakai untuk saran: kalau FPS turun > 8 persen setelah 10 menit, sarankan
  preset lebih rendah untuk game itu.

### Phase 3 - Kontrol display (M, eksperimental)

- Skala render per game (720p / 900p / native) lewat `wm size` hanya selama
  sesi, dengan restore yang dijamin oleh jurnal dan cek boot.
- Ini lever FPS terbesar di Mali G72 MP3, tapi juga yang paling mungkin bikin
  layout GSI aneh. Default mati, label `Experimental`.
- Spike wajib dulu: ukur FPS dan cek layout di Auto Chess dan MLBB sebelum
  dijadikan fitur. Hasil 2026-11-25: mekanisme `wm size` dan reset terbukti,
  tetapi menu Auto Chess kena vsync cap 60 FPS di native maupun 720p, jadi gain
  in-match belum terukur. Fitur tetap default Native dan berlabel `EXPERIMENTAL`.

### Phase 4 - Overlay jadi kokpit (S)

- Tambah timer sesi, indikator jank, jumlah app tidur, dan status throttle.
- Pertahankan 4 ukuran dan snap bezel yang sudah ada.

### Phase 5 - Rapikan produk (S)

- Tab jadi empat: **Games / Session / Policy / System**.
- Tab Network dilebur ke Policy. BBR jadi satu toggle sesi, bukan halaman sendiri.
- Capability probe di System: GSI atau OneUI, GOS ada atau tidak, primitif freeze,
  dukungan `wm size`, hasil cek cpuset.
- Diagnostics tetap terlipat.

---

## 6. Yang sengaja tidak dikerjakan di 0.1.0

- Port Game Booster/GOS Samsung: mustahil di GSI.
- Disable thermal HAL: bukan game space, itu bunuh diri termal.
- Permissive SELinux: tidak.
- Frame cap paksa: butuh hook surface, terlalu mahal untuk 0.1.0.
- Voice changer, screen record, quick reply: di luar cakupan dan butuh
  MediaProjection, bukan nilai jual utama.
- Dukungan device lain: justru dibuang, itu inti positioning-nya.

---

## 7. Satu rilis, satu nomor

Semua fase masuk ke **0.1.0**. Tidak ada 0.2.0, 0.3.0, 0.4.0. Yang ada hanya
milestone internal di dalam 0.1.0.

| Milestone internal | Isi | Kriteria selesai |
|---|---|---|
| M0 | Phase 0 | modul terpasang sebagai `secreto_space`, guard A50 menolak device lain, data lama termigrasi, APK lama hilang |
| M1 | Phase 1 | sesi jalan, freeze reversibel, restore terbukti lewat reboot |
| M2 | Phase 2 + 4 | laporan sesi tampil, overlay punya timer dan jank |
| M3 | Phase 3 + 5 | skala render terukur, empat tab, probe, copy English konsisten |

Selama M0 sampai M3 belum selesai, `module.prop` tetap `v0.1.0`.
Nomor kecil menyatakan bahwa ini produk baru dengan janji yang lebih sempit,
dan itu justru jujur.

## 8. Status implementasi

| Milestone | Status | Bukti |
|---|---|---|
| M0 | Selesai | Modul `secreto_space`, guard A50, migrasi data, APK baru |
| M1 | Selesai | 33 app tidur, journal, restore setelah reboot, smoke test |
| M2 | Selesai | Riwayat 10 sesi per game, kartu detail, saran preset, strip overlay |
| M3 | Selesai, satu catatan | Empat tab, probe, skala render dengan reset berjurnal; gain FPS in-match belum diukur |

Catatan M3: skala render terbukti bekerja dan aman, tetapi belum terbukti menaikkan FPS
di dalam match. Jangan memasarkannya sebagai peningkatan FPS sampai ada pengukuran
in-match di Auto Chess atau MLBB.
