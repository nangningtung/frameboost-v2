# Secreto Space 0.1.0 - Project Report

Status: **M0 sampai M3 selesai di kode dan terverifikasi di device; pipeline FPS, recovery, dan single-instance sudah diuji dengan data; gain render scale in-match belum terukur**  
Commit terakhir sebelum rebrand: `0221973`  
Device uji: `RR8M50C0W8T` (Samsung Galaxy A50, GSI API 36)

---

## 1. Ringkasan

Secreto Space 0.1.0 adalah game space untuk Samsung A50 yang terdiri dari:

- **Rust daemon** (`secretod`) sebagai pemilik konfigurasi kernel, jaringan, profil, snapshot, dan restore.
- **Android app** (`com.secreto.space`) untuk library game, konfigurasi sesi, dan overlay in-game.
- **WebUI** (`module-src/webroot`) untuk kontrol utama.

Prinsip utama: **ringan, cepat, rapi, dan jujur terhadap data**.

---

## 2. Fitur Saat Ini

### 2.1 Game Library

- Hanya game terpasang yang ditampilkan.
- Dua mode tampilan: carousel cover-flow (default) dan grid 2 kolom, bisa diganti
  lewat tombol di toolbar. Mode carousel punya dots dan swipe tanpa loop di ujung.
- Tap card → halaman detail terpisah.
- Card berisi:
  - **artwork asli**: banner 1024x500 dari Play Store sebagai latar, ikon 512x512
    di tengah, plus warna dominan artwork sebagai tint gradient
  - nama game
  - package
  - badge preset berwarna
  - tombol PLAY
- Tanpa jaringan, card tetap memakai ikon launcher asli dari `PackageManager`
  di atas gradient (bukan huruf inisial), jadi tidak pernah ada kotak kosong.
- Artwork di-cache di `cache/art/` per package dan dipakai ulang saat offline.
- Tombol toolbar: search, refresh, dan tambah game (ikon saja).
- Game bisa dihapus dari library lewat halaman detail.

### 2.2 Halaman Detail Game

- 4 preset:
  - Battery - hijau
  - Balanced - biru
  - Performance - kuning
  - Esports - pink
- BBR ON/OFF.
- Riwayat 10 sesi terakhir untuk game itu: waktu, durasi, rata-rata FPS, 1% low, jank,
  suhu puncak, jumlah app tidur, preset, dan event throttle.
  Bagian ini dilipat secara default; header menampilkan jumlah sesi dan bisa ditap
  untuk membuka (target sentuh 44 px). Setiap membuka halaman detail, bagian ini
  kembali tertutup supaya halaman tetap pendek.
- Saran preset muncul hanya kalau ada minimal 3 sesi berdurasi 5 menit atau lebih dan
  rata-rata terbaru turun lebih dari 8% dari sesi terbaik.
- Render scale (eksperimental): Native / 900p / 720p, default Native.
- Apply Profile dengan notifikasi:
  - `✓ PROFILE APPLIED`
  - `✕ APPLY FAILED`

### 2.3 Overlay In-Game

**Floating monitor (edge handle)** punya 4 mode, semuanya menempel seamless ke bezel:

| Mode | Ukuran | Isi |
|---|---|---|
| OFF | 14 × 48 dp | grip bar cyan (tap target 48 dp) |
| FPS (COMPACT) | 36 × 44 dp terlihat, 44 dp tap | angka FPS + label kecil `FPS` |
| + TEMP (DETAIL) | 36 × 48 dp terlihat, 44 dp tap | FPS + suhu |
| + GPU (FULL) | 36 × 54 dp terlihat, 44 dp tap | FPS + suhu + GPU clock |

Detail perilaku:

- Sisi bezel memakai layer-list dengan inset negatif `-3dp`, sehingga permukaan menempel tepat di tepi layar tanpa garis tepi ganda.
- Permukaan memakai gradient gelap halus dan hairline 1 dp; bukan lagi border cyan penuh.
- Lebar permukaan 36 dp, tetapi window sentuh tetap 44 dp. Sisa 8 dp di sisi dalam transparan, jadi bubble terlihat lebih tipis tanpa mengorbankan tap target.
- Rail status 2 dp di sisi dalam (menghadap layar) mengikuti warna FPS, dengan inset 9 dp dari atas dan bawah.
- Sudut sisi dalam rounded 12 dp; konten selalu center (vertikal dan horizontal).
- Teks bertingkat: angka FPS dominan, suhu dan GPU clock memakai `RelativeSizeSpan` yang lebih kecil.
- Warna FPS: cyan ≥55, hijau ≥40, amber ≥25, merah <25; rail ikut warna yang sama.
- Warna suhu: amber ≥78 °C, merah ≥85 °C; GPU clock abu-abu.
- Drag bebas, lepas → snap ke sisi terdekat; posisi X/Y tersimpan.
- Posisi edge, Y, dan mode HUD disimpan per game: setiap game punya layout
  sendiri, dan game baru mewarisi layout global. Berganti game memulihkan
  layout masing-masing tanpa saling menimpa.
- Tap vs drag dipisah touch slop dinamis (`max(12dp, getScaledTouchSlop())`), disertai press state (alpha 0.72 + scale 0.95).
- Tap atau fling inward → side panel.

**Side panel** (cockpit GameSpace, lebar 306 dp):

- Header fixed: hero FPS + frametime, GPU, CPU big, network, battery.
- Strip sesi: SESSION (timer), JANK (%), ASLEEP (jumlah app), THERMAL (OK / HOT / LIMIT).
- Aksi scrollable: Boost RAM, Notifs (heads-up), Bypass CHG, No GOS.
- Preset switcher 4 tombol (ECO / BAL / PERF / ESPORTS).
- Segmen Floating Monitor: OFF / FPS / + TEMP / + GPU.
- Muncul dari sisi tempat handle menempel; tutup lewat tombol X atau swipe.
- Di layar pendek (720p render scale), blok status tetap bergabung ke area
  scroll sehingga preset dan mode tetap terjangkau jari.

### 2.4 Telemetry

Sumber:

```text
/data/local/tmp/secreto-snapshot.json
```

atau IPC:

```text
snapshot
```

Data:

- FPS (SurfaceFlinger `--timestats`: delta `totalFrames` per layer, prioritas SurfaceView, P1 low dari histogram present2present)
- P1 low FPS
- suhu
- GPU clock
- CPU big clock
- congestion control / BBR
- baterai (termasuk `bypass_charging`)
- GOS (`gos_disabled`, `has_gos`)

Aturan:

```text
Tidak ada angka palsu.
Kosong = -- / N/A.
```

FPS self-healing: TimeStats di-enable otomatis dan layer basi di-clear + enable ulang.
Kalau SurfaceFlinger tidak punya layer untuk paket itu, daemon memakai `dumpsys gfxinfo
framestats` sebagai delta per window. Tidak ada fallback ke rata-rata kumulatif dan tidak
ada Choreographer, karena keduanya menghasilkan angka yang terlihat hidup padahal bukan
laju frame saat itu. Kalau tidak ada frame baru, hasilnya kosong, bukan angka lama.

Integritas pengukuran FPS (diuji 2026-11-25):

- Window minimal 0,2 detik dan maksimal 3 detik. Di bawah 0,2 detik frame delta belum
  membentuk laju; di atas 3 detik layer dianggap hilang lalu di-rebaseline, supaya satu
  frame setelah jeda panjang tidak dilaporkan sebagai `0,0 FPS`.
- Poll yang datang terlalu cepat **tidak** menimpa baseline. Sebelumnya setiap panggilan
  mereset jam, sehingga aliran panggilan `fps` tidak pernah mengumpulkan window dan angka
  sempat jatuh ke `null` walau game masih menggambar.
- Bacaan yang dipakai ulang membawa `age_ms`; hanya laporan dengan `age_ms = 0` yang masuk
  statistik sesi, jadi frame yang sama tidak dihitung dua kali.
- Verifikasi device: 24 panggilan `fps` beruntun saat MLBB merender = 24/24 berisi angka,
  12 segar + 12 cache, umur cache maksimum 149 ms, `total_frames` naik monoton 208 → 325.
  Pengukuran 10 detik di luar daemon: 598 frame = 59,8 FPS; snapshot daemon saat itu 59,8
  sampai 60,4 FPS.

Jank dihitung terhadap irama frame game itu sendiri: frame yang lebih dari 1,5x frame
time median dianggap jank, dengan periode vsync panel sebagai batas bawah. Definisi lama
(vsync panel x 1,5) melaporkan game 30 FPS yang stabil sebagai 97% jank; definisi baru
melaporkan game yang sama sekitar 2%.

### 2.5 Safety

- Snapshot & restore stock config (termasuk `charging_enabled=1` saat rollback).
- Thermal safety dengan hysteresis lebar (Warn 87 °C, kembali 82 °C, Crit 92 °C).
- Keep-alive profil tiap 5 detik untuk menahan reset clock dari layanan Samsung.
- Auto-hide overlay di luar game; deteksi keluar game instan saat pindah ke launcher / system UI (poll 250 ms, debounce 2).
- Stale-daemon guard.
- BBR per game.
- Multi-target ping.
- Quick Boost.
- Preset dan Apply boost di Policy hanya aktif kalau ada game yang sedang terdeteksi.
  Saat idle keduanya dinonaktifkan dan ada catatan `Select a game first to change its
  preset or apply a boost.`, karena `apply_preset` tidak menyimpan apa pun saat tidak
  ada game dan `apply_game` hanya menulis ulang nilai BAL yang sudah aktif.
- GOS bypass dengan deteksi paket: ROM tanpa GOS dianggap sudah bypass.
- Bypass charging dan toggle heads-up notifikasi lewat daemon.

### 2.6 Sesi game & background apps

Sesi adalah unit yang dilaporkan console, bukan sekadar kumpulan tweak.

- Sesi mulai otomatis saat game masuk foreground, berakhir saat game ditinggalkan.
- Aplikasi user yang tidak dipakai ditidurkan: `cmd appops set <pkg> RUN_IN_BACKGROUND ignore`
  lalu `am force-stop <pkg>`. `am set-inactive` tidak dipakai karena no-op di GSI ini.
- Yang tidak pernah ditidurkan: game aktif, semua game di library, launcher, keyboard aktif,
  app telepon/dialer, GMS, Secreto sendiri, dan whitelist user.
- Whitelist dan toggle on/off disimpan di `/data/adb/secreto_space/`.
- Journal `sleeping.list` berisi `pkg mode` ditulis **sebelum** aksi pertama, sehingga crash,
  reboot, atau uninstall selalu bisa mengembalikan mode appops semula.
- Batch tidur berjalan di worker thread dengan `APPLY_LOCK`, jadi tick loop termal tidak
  berhenti beberapa detik saat app ditidurkan.
- Laporan sesi: durasi, rata-rata FPS, 1% low dari p99 seluruh sesi, jank terhadap irama
  frame game, suhu puncak, baterai awal/akhir, jumlah app yang tidur, event throttle, dan
  alasan berakhir.
- Riwayat `session_history.json`: maksimal 10 sesi per game dan 120 total. Perintah
  `session_history <pkg>` mengembalikan daftar plus saran preset.
- `End session & restore` dan `RESTORE NOW` mengakhiri sesi, membangunkan app, memulihkan
  clock stock, lalu mem-pause Secreto untuk game itu sampai game ditutup.

### 2.7 Render scale (eksperimental)

- Mode per game: `native` (default), `900p`, `720p`; disimpan di
  `/data/adb/secreto_space/render_scale.json`.
- Ukuran mengikuti rasio panel: 720p = 720x1560, 900p = 900x1950 pada panel 1080x2340.
- Diterapkan lewat `wm size` hanya saat sesi game itu dimulai. Journal
  `wm_size.journal` ditulis sebelum perintah, lalu dihapus setelah reset berhasil.
- Reset terjadi di akhir sesi, saat daemon start, di `service.sh`, dan di `uninstall.sh`,
  jadi reboot atau crash tidak bisa meninggalkan layar dalam mode scaled.
- Spike 2026-11-25 di Auto Chess: native 60,1 FPS / jank 2,0% / 71 °C; 720p 59,4 FPS /
  jank 1,7% / 67 °C. Menu kena vsync cap, jadi selisih FPS di menu tidak membuktikan gain.
- Uji in-match MLBB (scene berjalan, `gpu_busy` valid): native 79,6% / 49,1 FPS,
  720p 68,5% / 58,9 FPS, lalu native lagi 58,5% / 56,4 FPS pada 82,8-84,5 °C. Arahnya
  mendukung 720p, tetapi scene match berubah selama pengukuran, jadi ini indikasi dan
  bukan A/B terkontrol. Klaim resmi tetap: mekanisme terbukti, gain in-match belum
  dipisahkan dari variasi scene.
- Bubble HUD tetap utuh di 720p: `frame=[1444,188][1560,304]` pada display 1560x720.

### 2.8 Hasil uji device (2026-11-25)

Semua angka di bawah diambil dari device, bukan estimasi.

- **Recovery crash**: sesi MLBB aktif dengan 720p, app tidur, dan knob preset. Daemon
  di-`kill -9`; watchdog menyalakan ulang dalam hitungan detik. Hasil: display kembali
  1080x2340, `wm_size.journal` dan `sleeping.list` hilang, appops kembali ke mode semula,
  governor GPU/CPU kembali stock, log menulis `session: recovered 33 apps, all knobs
  restored to stock`.
- **Single-instance**: daemon kedua dijalankan di tengah sesi aktif. Ia keluar dengan
  `another daemon is already listening` sebelum menyentuh device; PID daemon pertama tidak
  berubah, journal tetap 33 baris, 720p tetap aktif, dan tidak ada baris recovery baru.
- **FPS beruntun**: 24 panggilan `fps` beruntun = 24/24 berisi angka, tanpa `null`, tanpa
  `0,0`; umur cache maksimum 149 ms.
- **Sesi MLBB 168 detik** (binary final): rata-rata 50,6 FPS, 1% low 30,3, jank 3,1%,
  suhu puncak 87 °C, 1 throttle event, 33 app tidur.
- **Smoke test**: 17/17 PASS, termasuk `fps live`, `p1 low <= fps`, journal tidur, dan
  game aktif tidak ikut ditidurkan.
- **Unit test Rust**: 20 test dijalankan di A50 (`aarch64-linux-android`), 20 lulus.

### 2.9 Capability probe

Perintah `probe` dan kartu System menampilkan device, model, Android, ROM (GSI / One UI /
AOSP), ada tidaknya GOS, primitif freeze yang benar-benar bekerja, dukungan `wm size`,
cpuset dan controller-nya, root, serta status SELinux. Di A50 GSI hasilnya: `a50`,
Android 16, GSI, GOS absent, `appops + force-stop`, `wm size` didukung, cpuset hanya
`memory`.

### 2.10 Perbandingan dengan proyek komunitas (2026-11-25)

Audit lengkap ada di `docs/COMMUNITY-COMPARISON.md`. Ringkasnya:

- Proyek yang dicek: `uperf` (3.408 bintang), `fas-rs` (1.124), `AZenith` (151),
  `GameSpace` (44), `zkmnext-gamespace` (26), `PerfGame` (10), `ROR` (5).
- `uperf` dan `fas-rs` adalah engine performa. `GameSpace` adalah UI Android Game Mode
  yang butuh patch ROM, bukan modul root.
- Game Mode downscale resmi diuji di MLBB: `Scaling:0.667` bekerja dan SurfaceFlinger
  menunjukkan transform 1.4088, tetapi `cmd game reset` tidak mengembalikan layer sampai
  game membuat surface baru. Karena itu `wm size` tetap dipakai.
- Game Mode `performance` ditolak MLBB (`performance not supported`); hanya
  `standard` dan `custom` yang tersedia.
- `RenderThread` MLBB sudah di big core (`4-7`, mask `f0`), jadi affinity bukan gap.
- Saat dingin `cpu4` max 2.314 GHz; thermal HAL menurunkannya ke 1.768-1.898 GHz saat
  83-84 °C. Preset tidak melawan thermal.
- I/O dan VM stock sudah `cfq`, readahead 128, dirty 20/10; tidak ada knob baru.
- Render scale di lobby MLBB: native 59,3 FPS vs 720p 59,0 FPS, keduanya kena vsync cap.
  Mekanisme dan HUD terbukti; in-match ada indikasi gain, tetapi belum A/B terkontrol.

Keputusan: 0.1.0 tetap memakai preset statis + keep-alive + thermal, tanpa injeksi
SurfaceFlinger dan tanpa controller per frame. Frame aware scheduling ala `fas-rs`
dicatat sebagai kandidat v0.2, bukan ditambahkan diam-diam ke 0.1.0.

---

## 3. Arsitektur

```text
WebUI (module-src/webroot)
        │ Unix socket IPC
        ▼
Rust daemon (secretod)
        │ snapshot JSON
        ▼
Android HUD app
        │ WindowManager overlay
        ▼
Edge handle + side panel
```

File penting:

| File | Fungsi |
|---|---|
| `secretod/src/main.rs` | IPC, snapshot, command router, probe |
| `secretod/src/session.rs` | Sesi, journal, laporan, riwayat, saran preset |
| `secretod/src/render.rs` | Skala render per game, journal `wm size` |
| `secretod/src/apps.rs` | Sleep/wake appops, whitelist, proteksi library |
| `secretod/src/profile.rs` | Preset & per-game config |
| `secretod/src/net.rs` | Ping, BBR, network |
| `hud-app/src/.../OverlayService.java` | Overlay, drag, panel, preset IPC |
| `hud-app/src/.../SnapshotReader.java` | Parse snapshot |
| `hud-app/res/layout/overlay_layout.xml` | Handle + side panel |
| `module-src/webroot/index.html` | WebUI struktur |
| `module-src/webroot/app.js` | WebUI controller |
| `module-src/webroot/style.css` | Theme & layout |
| `deploy-device.ps1` | Build + install + deploy |

---

## 4. Sinkronisasi Preset

Alur WebUI → overlay:

```text
WebUI pilih preset
→ set_game_profile <pkg> <preset>
→ daemon simpan config
→ snapshot.game_preset
→ OverlayService.highlightPreset()
```

Alur overlay → WebUI:

```text
Side panel pilih preset
→ IpcClient.sendCommand("set_game_profile ...")
→ daemon simpan config
→ WebUI list_games membaca preset baru
```

BBR dipertahankan saat ganti preset.

---

## 5. Build & Deploy

Perintah utama:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1
```

Output:

- `hud-app/SecretoSpace.apk`
- `module-src/system/bin/secretod`
- `module-src/system/priv-app/SecretoSpace/SecretoSpace.apk`
- `SecretoSpace-v0.1.0-A50.zip`

Catatan deploy:

- `deploy-device.ps1` melakukan `adb install -r`, push binary daemon ke modul, lalu restart daemon + watchdog.
- `build-hud-apk.ps1` menyalin APK ke payload ZIP dan ke `system/priv-app/SecretoSpace`.
- `customize.sh` memasang APK sebagai priv-app (`pm install -r -d -g`) dan menambahkan `deviceidle whitelist`.
- Update payload modul di device dilakukan lewat `ksud module install <zip>` lalu reboot, karena `hybrid-mount` mengunci file modul agar tidak bisa ditimpa langsung.

Status:

```text
Build daemon: tanpa warning
Deploy: sukses
WebUI: tidak ada error JS
```

---

## 6. Catatan Penting

- `module-src/webroot` dan `hud-app/assets/webroot` harus selalu identik.
- Jangan menambahkan angka fallback palsu.
- Jangan memindahkan tuning kernel ke Java; tetap di Rust daemon.
- Riset ROR/FrameX diambil sebagai pola, bukan copy kode.
- Lisensi ROR tidak konsisten; hindari fork langsung.

---

## 7. Batasan Saat Ini

Belum termasuk fase ini:

- [x] Per-app layout side panel
- [x] Fallback FPS `gfxinfo framestats` saat SurfaceFlinger tidak punya layer (Choreographer sengaja tidak dipakai karena menghasilkan angka palsu)
- [x] Capability probe terpisah (System tab)
- [x] State machine overlay murni
- [x] Quick action tambahan (Boost RAM, Notifs, Bypass CHG, No GOS)
- [ ] A/B render scale terkontrol di dalam match (indikasi sudah ada: `gpu_busy` 79,6% → 68,5% dan 49,1 → 58,9 FPS, tetapi scene berubah selama sampel)
- [ ] Uji layout GSI pada 720p/900p untuk semua layar game, bukan hanya Auto Chess
- [x] Audit proyek komunitas dan intervensi Game Mode resmi (lihat `docs/COMMUNITY-COMPARISON.md`)
- [ ] Frame aware scheduling dengan target FPS per game (kandidat v0.2, sengaja tidak masuk 0.1.0)

---

## 8. Checklist Perbaikan / Fitur Baru

Gunakan checklist ini setiap kali ada perubahan.

### 8.1 Sebelum mulai

- [ ] Baca file ini.
- [ ] Tentukan apakah perubahan menyentuh WebUI, HUD, daemon, atau semua.
- [ ] Pastikan tidak merusak safety/restore.
- [ ] Pastikan tidak menambah data palsu.

### 8.2 Saat implementasi

- [ ] Perubahan WebUI → update dua copy:
  - `module-src/webroot`
  - `hud-app/assets/webroot`
- [ ] Perubahan preset → pastikan 4 preset tetap ada.
- [ ] Perubahan telemetry → tetap pakai `--` / `N/A`.
- [ ] Perubahan overlay → cek handle ON/OFF.
- [ ] Perubahan daemon → build harus tanpa error.

### 8.3 Setelah implementasi

- [ ] `deploy-device.ps1` sukses.
- [ ] Cek snapshot device.
- [ ] Cek preset WebUI ↔ side panel dua arah.
- [ ] Cek BBR tetap ON saat ganti preset.
- [ ] Cek overlay di game.
- [ ] Cek WebUI via CDP / screenshot.
- [ ] Update bagian **Changelog** di bawah.
- [ ] Commit dengan pesan jelas.

---

## 9. Changelog

### `pembersihan-0.1.0` - 2026-11-25

`refactor: hapus fitur gimmick dan rapikan repo`

Perubahan:
- Hapus Boost RAM (`drop_caches`) dari WebUI, side panel, dan daemon (`clean_ram`).
- Hapus tombol Apply boost di Policy dan command `apply_game` di daemon (idempotent, tidak memakai preset terpilih).
- Side panel: bar DND menjadi full width, drawable `ic_quick_ram` dihapus.
- Bersihkan repo root: screenshot sementara dan file referensi HTML dihapus, `cdp-eval.ps1` pindah ke `tools/`.

Testing:
- build: daemon release tanpa warning, APK sukses, parity webroot 3/3.
- device: `clean_ram`/`apply_game` -> unknown command, smoke test PASS, side panel terverifikasi di game tanpa BOOST RAM.
- WebUI: quick dock 3 tombol (bypass/DND/GOS), tidak ada error JS.

### `v0.1.0` - 2026-11-25

- **Identitas baru**: FrameBoost v2 menjadi Secreto Space 0.1.0.
  - modul `secreto_space`, daemon `secretod`, APK `com.secreto.space`
  - `versionName=0.1.0`, `versionCode=1000`
- **Device lock**: `customize.sh` menolak install dan `service.sh` menolak start
  kalau `ro.product.device` bukan `a50`.
- **Migrasi otomatis** dari FrameBoost 2.x: profil, stock snapshot, dan daftar game
  disalin ke `/data/adb/secreto_space/`; modul lama, APK lama, dan file runtime lama dihapus.
- **Path runtime baru**: `/data/local/tmp/secreto.sock`, `secreto-snapshot.json`, `secretod.log`.
- **Sesi game (M1)**:
  - Sesi dimulai saat game masuk foreground dan berakhir saat game ditinggalkan.
  - Background apps ditidurkan dengan `RUN_IN_BACKGROUND ignore` + `am force-stop`, karena
    `am set-inactive` terbukti no-op di GSI ini.
  - Journal `sleeping.list` ditulis sebelum aksi, dipulihkan oleh daemon, `service.sh`,
    dan `uninstall.sh`.
  - Game di library, launcher, IME, app telepon, GMS, dan whitelist tidak pernah ditidurkan.
  - Laporan sesi: durasi, rata-rata FPS, 1% low (p99 seluruh sesi), jank terhadap irama
    frame game, suhu puncak, baterai awal/akhir, jumlah app yang tidur.
  - `End session & restore` dan `RESTORE NOW` mengakhiri sesi, membangunkan app, dan
    mem-pause Secreto untuk game itu sampai game ditutup.
- **Laporan sesi dan riwayat (M2)**:
  - `session_history.json` menyimpan 10 sesi terakhir per game (maksimal 120 total).
  - Halaman detail game menampilkan 10 sesi terakhir dan saran preset kalau data cukup:
    minimal 3 sesi berdurasi 5 menit dan FPS turun lebih dari 8% dari sesi terbaik.
  - Side panel punya strip SESSION / JANK / ASLEEP / THERMAL, diperbarui tiap 200 ms.
  - Jank dihitung terhadap frame time median game, bukan vsync panel, supaya game 30 FPS
    yang stabil tidak dilaporkan 97% jank.
  - Peak temperature tetap tercatat walau game sedang tidak menggambar frame.
  - Bug IPC `window.__secretoIpc[N] is not a function` diperbaiki dengan menangkap nomor
    slot callback, sehingga respons yang datang berdekatan tidak saling menghapus.
- **Skala render (M3, eksperimental)**:
  - `render.rs` menyimpan mode per game dan menerapkan `wm size` 720p/900p hanya selama
    sesi game itu. Journal `wm_size.journal` ditulis sebelum perintah.
  - Reset di akhir sesi, start daemon, `service.sh`, dan `uninstall.sh`.
  - Default Native; UI memberi label `EXPERIMENTAL` dan menjelaskan cakupannya.
- **Produk (M3)**:
  - Empat tab: Games / Session / Policy / System. Network, background apps, preset, dan
    manual boost dilebur ke Policy; thermal guard dan rollback ke System.
  - Capability probe menampilkan ROM, GOS, primitif freeze, `wm size`, cpuset, root, dan
    SELinux lewat perintah `probe`.
  - Guard `ro.product.device=a50` juga ada di binary daemon.
  - Copy Indonesia di UI disapu ke English, termasuk `(referensi)` menjadi `(reference)`.
- **Floating monitor (redesign)**: permukaan terlihat 36 dp dengan window sentuh 44 dp,
  DETAIL 36 × 48 dp, FULL 36 × 54 dp. Sisa 8 dp di sisi dalam transparan tetapi tetap
  bisa disentuh. Border cyan penuh diganti gradient gelap, hairline 1 dp, dan rail status
  2 dp di sisi dalam yang mengikuti warna FPS. COMPACT kini memberi label kecil `FPS`, dan
  suhu/GPU memakai ukuran relatif supaya hierarki teks lebih rapi.

- **Integritas FPS dan recovery (2026-11-25)**:
  - Parser histogram hanya menerima bucket `NNms` dengan `ms > 0` dan `cnt > 0`, jadi baris
    seperti `averageFPS=...` tidak lagi masuk bucket 0 ms dan merusak 1% low.
  - Jalur `gfxinfo` memakai tracker delta per window, bukan angka kumulatif yang dijumlahkan
    berulang.
  - Poll yang terlalu cepat (<0,2 detik) tidak menimpa baseline; bacaan terakhir dipakai
    ulang dengan `age_ms` dan kedaluwarsa setelah 2 detik. Window >3 detik di-rebaseline.
  - Fallback `gfxinfo` hanya dipakai kalau paket benar-benar tidak punya layer
    SurfaceFlinger; game SurfaceView tidak lagi jatuh ke `null` saat window belum siap.
  - Guard single-instance dijalankan sebelum recovery dan tidak menghapus socket yang
    masih menjawab, sehingga daemon kedua tidak bisa membangunkan app atau mereset display
    di tengah sesi.
  - `session::sample` mengabaikan laporan dengan `age_ms > 0` supaya frame yang sama tidak
    dihitung dua kali.
  - 20 unit test Rust, dijalankan di A50. Drill device: recovery `kill -9`, single-instance,
    24 panggilan `fps` beruntun, dan smoke test 17/17 PASS.
- **WebUI Material You redesign (2026-11-25)**:
  - Palet warna diubah dari obsidian-cyan ke Material You dark
    (`#111318` / `#1B1F27` / accent `#A8C7FA`), mengikuti referensi Pixel OS
    Game Space.
  - Radius kartu 24px, border tipis `rgba(255,255,255,0.08)`, button dan badge
    pill-shaped, nav indicator pill dengan accent-soft background.
  - Font stack Inter + JetBrains Mono (dengan fallback system fonts karena
    WebView KSU tidak punya akses internet).
  - Transisi `cubic-bezier(0.2, 0, 0, 1)` (emphasized) untuk semua interaksi.
  - Semua ID binding dan logika `app.js` tetap utuh; tidak ada fitur baru.
  - Verifikasi device: 4 tab (Session, Games, Policy, System) terender benar,
    game detail dengan segmented control dan badge EXPERIMENTAL utuh, tidak
    ada error di logcat. Webroot parity 3/3 MATCH.
  - Game library (tab Games) diubah dari list row horizontal ke grid 2-kolom
    kartu vertikal bergaya cover poster: gradient per-package (golden-angle
    hue spread), inisial game besar di tengah, label source dan preset badge
    di atas, judul + package + tombol Play pill di overlay gradient bawah.
    Tombol Play berukuran 44dp (min tap target), tap card body membuka detail,
    tap Play langsung menjalankan game (diverifikasi: MLBB ter-launch via CDP
    bounding rect + adb input tap).
  - View mode toggle (grid <-> carousel): tombol icon di toolbar Games tab
    mengaktifkan carousel cover flow (kartu tengah besar, kartu samping
    menyinggung dengan scale dan opacity berkurang, dot indicator pill accent).
    Tap card samping untuk slide, tap dot untuk pindah, tap card tengah untuk
    buka detail, tap Play untuk launch. Diverifikasi: Play button di carousel
    menjalankan Auto Chess; toggle grid/carousel bolak-balik normal; tidak ada
    error logcat.
  - Carousel diperbesar (80% width, 312px height) dan mendukung swipe gesture:
    geser kiri/kanan pada stage untuk pindah card, dengan threshold 40px dan
    suppress click setelah swipe supaya tidak membuka detail secara tak
    sengaja. Verifikasi device: swipe left -> Auto Chess, swipe right -> MLBB,
    state CDP mengonfirmasi detail view tetap hidden setelah swipe.
  - Polish UI Pixel OS: carousel hero showcase (title, preset badge, Play pill,
    Tune button) di bawah stage menggantikan tombol Play di dalam card.
    Card carousel jadi pure visual cover. Session card di-redesign dengan
    mini cover ber-hue per game, status dot animasi pulse, badge status
    (STANDBY READY / ACTIVE SESSION), telemetry grid 4-kolom (AVG FPS, 1% LOW,
    JANK, PEAK). "Your games" di Home pakai cover icon ber-gradient per game
    dan tombol Play solid accent. Watermark initial digeser ke atas supaya
    tidak overlap title. Ambient glow backdrop di belakang app container
    sync dengan hue game aktif. Diverifikasi: hero Play launch MLBB, hero Tune
    buka detail view, semua tap target >= 44dp (CDP), tidak ada error logcat.
  - Bug fix: grid dan carousel tidak lagi muncul bersamaan saat pindah dari
    Session ke Games tab. renderGamesList sekarang menyembunyikan grid saat
    mode carousel aktif (sebelumnya grid DOM selalu di-built tapi tidak
    di-hidden). Diverifikasi via CDP: bothVisible=false dalam semua skenario
    (grid, carousel, session->games round-trip).
  - Toolbar Games tab di-rapikan: judul "Game library" dengan sparkle icon
    hijau (indikator auto-detect), search bar full-width dengan icon
    magnifier, tombol refresh/add/toggle jadi icon-only 44dp pill dengan
    stroke-based SVG. Cache buser bump ke v0.1.0-ui2. Search bar dihapus dari
    Games tab karena game list biasanya sedikit (2-5 game) sehingga filter
    tidak diperlukan.
  - Carousel 3D cover flow upgrade: kartu lebih besar (200x268px) dengan
    perspective 1000px, telescoping position (unit pertama full spacing,
    sisanya 55% spacing), rotateY clamped ke ±52deg, translateZ depth
    (max 3 unit x 60px), dan scale 8% per unit. Drag mengikuti jari 1:1
    (transition disabled saat drag), snap-back dengan spring easing
    cubic-bezier(0.22, 1, 0.36, 1) 0.5s. Velocity-based flick: swipe cepat
    (>0.6 px/ms) melompat 2 kartu. DOM card di-build sekali, hanya
    transform yang diupdate supaya CSS transition beranimasi. Hero dan dots
    sync dengan card center. Cache buser bump ke v0.1.0-ui3.
  - Carousel: clone dihapus, kembali ke N card asli. Tidak loop di ujung:
    kiri mentok berhenti, kanan mentok berhenti. Rubber band effect
    (drag 30% strength) saat swipe di ujung supaya terasa responsif tapi
    tidak melompat. Swipe kiri/kanan ter-verifikasi: MLBB <-> Auto Chess,
    mentok di kedua ujung tidak loop.
  - UI optimization pass (2026-11-26):
    - Toast notification system menggantikan semua alert(). Refresh button
      cukup menampilkan toast "Game library refreshed" (tidak lagi mengubah
      teks tombol). Add game flow menggunakan toast untuk feedback.
    - Add game drawer di-redesign: close button (X) di header, select dan
      input lebih bersih, tombol Add full-width. Tidak ada auto-focus yang
      memunculkan keyboard secara tak terduga.
    - Native bridge `getInstalledApps()` via PackageManager: daftar app di
      Add Game drawer sekarang menampilkan nama app asli (e.g. "Discord",
      "Brave", "Gemini") dengan package name di bawahnya, bukan hanya
      package name mentah.
    - Daemon `derive_label()`: nama game manual/auto/config yang tidak ada
      di BUILTIN_GAMES sekarang di-resolve dari segment terakhir package
      (title-cased) sebagai fallback bila tidak ada di builtin database.
    - "Your games" section dihapus dari tab Session. Tab Session sekarang
      fokus ke instrument cluster dan quick tools saja.
    - Default tab saat buka app: Games dengan carousel (sebelumnya Session).
    - CSS home-games yang tidak terpakai dibersihkan.
    - Cache buser bump ke v0.1.0-ui4.
- **Remove from library + add game modal (2026-11-26)**:
  - Game detail view mendapat tombol "Remove from library" yang mengirim
    `remove_game <pkg>` ke daemon. Daemon menghapus game dari manual, auto,
    config, dan builtin (disabled). Setelah hapus, detail view tertutup,
    carousel diperbarui, dan toast "Game removed from library" muncul.
    Verifikasi device: hapus MLBB -> carousel berkurang dari 2 ke 1 card,
    toast muncul, detail view tertutup.
  - Add game drawer diubah menjadi modal bottom sheet popup. Backdrop
    semi-transparan dengan blur, sheet naik dari bawah dengan animasi
    spring. Tap di backdrop menutup sheet. Drag handle di atas sheet.
    Verifikasi device: tap tombol Add -> modal naik dari bawah, tap
    backdrop -> modal tutup, add game via modal -> toast + card baru.
- **State machine overlay murni (2026-11-25)**:
  - State overlay disatukan ke enum `OverlayState { HIDDEN, DOCKED, DRAGGING,
    PANEL_OPEN }` dengan transisi terpusat lewat `enterState()`. Sebelumnya
    state tersebar di boolean `panelOpen` plus flag drag di touch handler.
  - Setiap transisi dicatat di logcat (`SecretoOverlay: DOCKED -> PANEL_OPEN`)
    sehingga lifecycle bisa di-debug tanpa menebak dari UI.
  - Visibilitas overlay dan keputusan posisi bercabang pada satu field, tidak
    lagi pada beberapa boolean yang bisa tidak sinkron.
  - Verifikasi device: transisi `HIDDEN -> DOCKED` saat game dibuka,
    `DOCKED -> DRAGGING -> DOCKED` saat drag, `DOCKED -> PANEL_OPEN -> DOCKED`
    saat tap buka/tutup panel, `DOCKED -> HIDDEN` saat game ditutup. Per-app
    layout tetap bekerja; 17/17 smoke test PASS.
- **Per-app layout panel (2026-11-25)**:
  - Posisi edge, Y, dan mode HUD (OFF / COMPACT / DETAIL / FULL) disimpan per package
    game di `hud_prefs.xml` dengan key `layout_<pkg>_right`, `layout_<pkg>_y`,
    `layout_<pkg>_auto`, `layout_<pkg>_type`, `layout_<pkg>_fps`.
  - Saat game berganti, layout tersimpan dimuat otomatis; game baru tanpa layout
    tersimpan mewarisi layout global sebagai fallback.
  - Pref global (`hud_right`, `hud_y`, `hud_type`, `fps_overlay_enabled`) tetap
    diperbarui sehingga upgrade atau game baru mendapat posisi yang konsisten.
  - Panel di 720p: bila tinggi layar tidak cukup, blok status tetap di scroll
    sehingga semua tombol mode dan preset tetap terjangkau (`setPanelMerged`).
  - `bubbleY` selalu mengikuti posisi bubble terakhir, jadi berganti game tidak
    menimpa posisi game sebelumnya dengan posisi panel yang sedang terbuka.
  - Smoke test `fps live` kini mencoba ulang sekali setelah 2 detik bila snapshot
    pertama masih `null`, sama seperti cek `oom_score_adj`.
  - Verifikasi device: MLBB kiri-Y299-FULL, Auto Chess kanan-Y93-DETAIL. Berganti
    game dua arah, posisi dan mode masing-masing tetap. Panel 720p scroll utuh,
    semua tombol terjangkau. 17/17 smoke test PASS.
- **Audit komunitas dan intervensi resmi (2026-11-25)**:
  - Pembanding: `uperf` (3.408 bintang), `fas-rs` (1.124), `AZenith` (151), `GameSpace` (44),
    `PerfGame` (10), `ROR` (5). Detail di `docs/COMMUNITY-COMPARISON.md`.
  - Game Mode `--downscale 0.667` terbukti bekerja di MLBB (`Scaling:0.667`, transform
    1.4088), tetapi `reset` tidak mengembalikan layer sampai surface game dibuat ulang.
    Karena itu render scale tetap memakai `wm size`.
  - Game Mode `performance` ditolak MLBB (`performance not supported`); hanya `standard`
    dan `custom` yang tersedia.
  - Affinity tidak diubah: `RenderThread` sudah `Cpus_allowed_list: 4-7` dengan mask `f0`.
  - Thermal HAL menurunkan `cpu4` max dari 2.314 GHz ke 1.768-1.898 GHz saat 83-84 °C.
    Preset hanya menulis `scaling_min_freq`, jadi thermal tetap menjadi pengaman.
  - I/O stock sudah `cfq`, readahead 128, dirty 20/10; tidak ada knob baru.
  - Render scale in-match: `gpu_busy` 79,6% → 68,5%, FPS 49,1 → 58,9, tetapi scene berubah
    selama sampel. Dicatat sebagai indikasi, bukan A/B terkontrol. Bubble HUD tetap utuh
    di 720p (`frame=[1444,188][1560,304]`).

- **Artwork asli di game card (2026-11-25)**:
  - Sumber mengikuti cara game launcher: halaman Play Store `details?id=<pkg>` memuat
    ikon 512x512, banner 1024x500, dan warna dominan banner di payload awal.
    Diverifikasi di host dan device: MLBB butuh `gl=ID` (US 404), Free Fire/Discord
    jalan di `ID` maupun `US`.
  - `GameArt.java` mengambil halaman dengan User-Agent browser, memilih entri
    persegi >= 256 px untuk ikon dan entri rasio 1,85-2,25 dengan lebar >= 800 px
    untuk banner, lalu mengunduh `=s160` / `=w512` supaya ringan di A50.
  - Hasil disimpan di `cache/art/<pkg>.cover.jpg`, `.icon.png`, `.json`, `.poke`
    (banner 36-38 KB, ikon 45-55 KB pada uji MLBB dan Auto Chess). Cache berlaku
    7 hari; hasil ikon-only dicoba ulang setelah 6 jam supaya fetch yang gagal sembuh sendiri.
  - Bridge `getGameArt(pkg, callback)` berjalan di thread background dan memanggil
    balik JS lewat `evaluateJavascript`, sama seperti `sendSocketAsync`.
  - UI memasang `.card-art` (banner, gradient scrim) dan `.game-tile-icon` (ikon
    membulat) di grid maupun carousel, memakai warna dominan untuk `--tile-hue`.
    Huruf inisial hanya dipakai sebelum artwork tersedia.
  - Fallback tanpa jaringan memakai `PackageManager.getApplicationIcon`, jadi card
    tetap menampilkan ikon aplikasi asli. Diuji dengan iptables per-uid: 4/4 card
    ikon-only, tanpa gambar rusak, lalu banner kembali setelah aturan dihapus.
  - Verifikasi device lewat CDP: carousel dan grid menampilkan banner dan ikon asli
    MLBB + Auto Chess, halaman detail memakai ikon asli, dan cache bertahan setelah
    app di-force-stop lalu dibuka lagi.

- **Session reports dapat dilipat (2026-11-25)**:
  - Bagian riwayat di halaman detail tidak lagi memanjangkan halaman. Header selalu
    terlihat dengan jumlah sesi dan chevron; daftar 10 sesi plus saran preset hanya
    muncul setelah header ditap.
  - Header memakai target sentuh 44 px, `aria-expanded`/`aria-controls`, fokus
    terlihat, dan chevron berputar saat terbuka. State di-reset ke tertutup setiap
    kali halaman detail dibuka.
  - Verifikasi device: saat tertutup 10 SESSIONS ditampilkan, body `display: none`,
    dan halaman cukup pendek; setelah ditap 10 baris muncul; setelah back dan buka
    lagi, bagian kembali tertutup.

- **Preset Policy tidak lagi menipu saat idle (2026-11-25)**:
  - `apply_preset` di daemon hanya mengembalikan pesan `Profile saved; applies on next launch`
    tanpa benar-benar menyimpan kalau tidak ada game di foreground. UI dulu tetap
    mengubah tombol aktif beberapa detik lalu kembali, tanpa penjelasan.
  - Sekarang keempat tombol preset dan tombol Apply boost dinonaktifkan kalau
    `game_pkg` kosong, dan muncul catatan `Select a game first to change its preset
    or apply a boost.` Tombol tetap 44 px saat aktif.
  - Perubahan hanya WebUI, daemon tidak disentuh. Verifikasi device: idle → 4/4 preset
    dan Apply boost disabled; tap sungguhan lewat CDP tidak mengubah `game_profiles.json`
    dan tidak mengubah label tombol.

### `v2.4.0` - 2026-11-25

```text
fix: honest live FPS, sticky presets and stable thermal hysteresis
```

Perubahan:

- **FPS**: TimeStats di-enable otomatis; layer basi dideteksi (2 poll tanpa frame) lalu `-clear` + `-enable` ulang; fallback `averageFPS` dihapus supaya tidak ada lagi angka 60 palsu; P1 low dihitung dari histogram window berjalan dan di-clamp supaya tidak melebihi FPS.
- **Preset**: keep-alive setiap 5 detik, hanya menulis kalau nilai benar-benar drift. Clock tidak lagi balik ke stock karena reset Samsung. Perubahan preset dari WebUI/panel ikut terbaca tanpa restart game.
- **Thermal**: ambang Warn 87 °C, kembali ke Game di 82 °C, Crit 92 °C. Extras (swappiness, DND, renice, BBR) hanya dijalankan sekali per sesi game, bukan setiap transisi suhu.
- **Apply**: min freq di-clamp ke `scaling_max_freq` yang sedang aktif (thermal cap), dan kegagalan satu write tidak lagi rollback ke stock; keep-alive yang retry.
- **Telemetry**: `read_u32` sekarang mem-parse nilai berakhiran `%` (mis. `gpu_busy` 78%) supaya `busy_pct` tidak lagi 0.
- **HUD watchdog**: kalau proses overlay mati (install APK / LMK), daemon menyalakan ulang foreground service dan menulis `oom_score_adj=-800`. Watchdog juga memeriksa layer overlay di SurfaceFlinger, jadi service yang hidup tanpa window tetap di-restart.
- **Log**: setiap baris daemon diberi timestamp WIB; `service.sh` merotasi log di 512 KB.
- **APK**: `versionName=2.4.0`, `versionCode=240`; warna FPS memakai nilai bulat supaya cocok dengan angka yang tampil.
- **Build**: `build-hud-apk.ps1` mendeteksi JDK Adoptium otomatis dan mengekspor `JAVA_HOME` untuk `d8`/`apksigner`.
- **Test**: `smoke-test.ps1` mengecek 14 titik secara otomatis (termasuk konsistensi DND) dan melaporkan PASS/FAIL.
- **Sinkronisasi panel/app**: `heads_up_blocked` sekarang membaca setting asli (cache 5 detik), tombol Apply Profile memakai preset game yang dipilih dan tidak mengubah clock saat game di background (hanya menyimpan untuk launch berikutnya).
- **Floating monitor dua arah**: app punya segment OFF/FPS/+TEMP/+GPU yang memakai prefs sama dengan side panel; OverlayService mendengarkan perubahan prefs. Selector preset app dibatasi ke `[data-preset]` supaya tidak menghapus state aktif tombol monitor.
- **Smoke test**: tanpa game aktif, pengecekan terkait game ditandai SKIP, bukan FAIL.
- **Responsive**: diuji pada lebar 320/360/411/540/700/820 CSS px, tanpa overflow horizontal dan semua tombol >= 44 px (tombol PLAY 56x44 px, padding 8 px, teks terpusat (gap kiri=kanan 14 px)). Layout berubah dua kolom mulai 700 px. Activity console dikunci portrait; overlay HUD tetap landscape saat game.
- **Versi**: modul naik ke v2.4.0.

Testing (Auto Chess, Galaxy A50, landscape):

```text
Sebelum:
  FPS battle 47.9, jank 20.3%
  GPU Dynamic / 385 MHz, CPU big min 1248 MHz
  monitor stuck di 61

Sesudah:
  clock hold Booster / 683 MHz + CPU big min 1508 MHz selama 60 detik
  battle 50.2 FPS, jank 14.4%
  lobby 58.3 FPS, jank 1%
  monitor live: 60 / 79 C / 683 MHz
```

Post-reboot (verifikasi akhir):

`	ext
modul v2.4.0 aktif, modules_update kosong
daemon v2.4.0 start dengan timestamp
APK versionName=2.4.0 / versionCode=240
overlay otomatis hidup saat game dibuka
preset ESPORTS diterapkan: Booster / 683 MHz, CPU big min 1508 MHz
saat keluar game: governor Interactive / 260 MHz, swappiness 130,
BBR westwood, heads-up kembali aktif (restore_all bekerja)
`

Catatan: thermal HAL Samsung tetap membatasi CPU big ke ~1.77-1.98 GHz saat 80-82 °C, jadi battle berat belum 60 stabil penuh.

---

### `0aa121d` - 2026-11-24

```text
feat: seamless edge monitor, cockpit panel and SurfaceFlinger FPS
```

Perubahan:

- **Floating monitor**: 4 mode dengan ukuran baru (OFF 14×48, FPS 44×44, +TEMP 46×48, +GPU 48×62 dp), semua memenuhi tap target minimal 44 dp.
- **Seamless bezel docking**: `bg_edge_handle.xml` dan `bg_edge_handle_left.xml` memakai layer-list inset negatif `-3dp`; stroke luar tidak terlihat dan sudut dalam rounded 10 dp. Grip bar baru `bg_handle_grip.xml`.
- **Konten simetris**: `view_bubble` jadi vertikal + `gravity=center`, `txt_bubble_fps` center + `maxLines=3`, jadi mode DETAIL dan FULL tidak terpotong.
- **Warna dinamis**: FPS cyan/hijau/amber/merah, suhu amber/merah, GPU abu-abu, memakai `SpannableStringBuilder`.
- **Touch**: touch slop dinamis, press state, ambang tap 320 ms / 22 dp, fling inward 320 ms / 36 dp, haptic saat tap.
- **Docking & clamp**: `dockedX()` dan `clampY()` tanpa margin supaya handle benar-benar menempel dan bisa digeser sampai ujung layar.
- **Side panel cockpit**: header fixed (hero FPS, GPU, CPU, network, battery) + aksi scrollable (Boost RAM, Notifs, Bypass CHG, No GOS) + preset switcher + segmen Floating Monitor.
- **Daemon**: FPS dari SurfaceFlinger `--timestats` dengan tracking per layer dan P1 low dari histogram; deteksi GOS; command `set_heads_up`, `set_bypass_charge`, `gos_disable/restore`; restore `charging_enabled` saat rollback; poll game 250 ms dengan keluar instan di launcher/system.
- **Snapshot**: field baru `gos_disabled`, `has_gos`, `bypass_charging`.
- **Manifest**: `MainActivity` dan `GameLibraryActivity` dikunci portrait agar console tidak melebar saat keluar dari game landscape.
- **WebUI**: rewrite dashboard GameSpace dengan tab Home / Games / Network / Settings, hero card, quick dock, dan multi-target ping.
- **Build**: APK disalin ke payload priv-app; `customize.sh` memasang sebagai priv-app dan whitelist deviceidle.
- **Root**: `MainActivity` meminta akses `su` lebih awal; `WebAppInterface` punya eksekusi shell umum via `su`.

Testing:

- build: `cargo build --release` tanpa warning, APK + ZIP sukses.
- device: Galaxy A50 (`RR8M50C0W8T`), landscape Auto Chess (`com.mobilechess.gp`).
- overlay: OFF / FPS / +TEMP / +GPU tampil simetris, dock kiri dan kanan seamless, tap membuka panel dari kedua sisi, drag + snap berfungsi.
- daemon: pid aktif, socket `list_games` merespons, log transisi state normal.
- modul: update via `ksud module install` + reboot, payload webroot/APK/daemon sinkron.
- parity: `module-src/webroot` dan `hud-app/assets/webroot` identik (3 file).
- bersih-bersih: 104 screenshot test di `/sdcard` dan file test di `/data/local/tmp` dihapus.

---

### `f72310c` - 2026-11-24

```text
feat: polished GameSpace UI, edge FPS handle, preset theming and dead-code cleanup
```

Perubahan:

- Game library grid + halaman detail.
- 4 preset dengan warna aksen.
- Edge FPS handle.
- Side panel responsif.
- `game_preset` ditambahkan ke snapshot.
- Sinkron preset dua arah.
- Dead code dibersihkan di Java, WebUI, dan Rust.
- Build daemon tanpa warning.

---

### Template entri baru

```markdown
### `<hash>` - `<tanggal>`

`feat/fix/refactor: <judul singkat>`

Perubahan:
- ...
- ...

Testing:
- build: ...
- device: ...
- WebUI: ...
```

---

## 10. Kesimpulan

Secreto Space 0.1.0 sudah stabil dan layak dipakai harian. Fase berikutnya sebaiknya difokuskan pada perbaikan arsitektur, bukan penambahan fitur besar yang tidak perlu.
