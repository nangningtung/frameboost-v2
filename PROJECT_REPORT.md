# FrameBoost v2 — Project Report

Status: **Selesai untuk fase ini**  
Commit fitur terakhir: `b8e88ab`  
Device uji: `RR8M50C0W8T` (Samsung Galaxy A50)

---

## 1. Ringkasan

FrameBoost v2 adalah sistem optimasi game untuk Samsung A50 yang terdiri dari:

- **Rust daemon** (`frameboostd`) sebagai pemilik konfigurasi kernel, jaringan, profil, snapshot, dan restore.
- **Android HUD app** (`com.a50.frameboost.hud`) untuk overlay in-game.
- **WebUI** (`module-src/webroot`) untuk kontrol utama.

Prinsip utama: **ringan, cepat, rapi, dan jujur terhadap data**.

---

## 2. Fitur Saat Ini

### 2.1 Game Library

- Hanya game terpasang yang ditampilkan.
- Grid 2 kolom.
- Tap card → halaman detail terpisah.
- Card berisi:
  - inisial/icon
  - nama game
  - package
  - badge preset berwarna
  - tombol PLAY

### 2.2 Halaman Detail Game

- 4 preset:
  - Battery — hijau
  - Balanced — biru
  - Performance — kuning
  - Esports — pink
- BBR ON/OFF.
- Apply Profile dengan notifikasi:
  - `✓ PROFILE APPLIED`
  - `✕ APPLY FAILED`

### 2.3 Overlay In-Game

**Floating monitor (edge handle)** punya 4 mode, semuanya menempel seamless ke bezel:

| Mode | Ukuran | Isi |
|---|---|---|
| OFF | 14 × 48 dp | grip bar cyan (tap target 48 dp) |
| FPS (COMPACT) | 44 × 44 dp | angka FPS |
| + TEMP (DETAIL) | 46 × 48 dp | FPS + suhu |
| + GPU (FULL) | 48 × 62 dp | FPS + suhu + GPU clock |

Detail perilaku:

- Sisi bezel memakai layer-list dengan inset negatif `-3dp`, sehingga stroke luar terpotong tepat di tepi layar (tidak ada garis tepi ganda).
- Sudut sisi dalam rounded 10 dp; konten selalu center (vertikal dan horizontal).
- Warna FPS: cyan ≥55, hijau ≥40, amber ≥25, merah <25.
- Warna suhu: amber ≥78 °C, merah ≥85 °C; GPU clock abu-abu.
- Drag bebas, lepas → snap ke sisi terdekat; posisi X/Y tersimpan.
- Tap vs drag dipisah touch slop dinamis (`max(12dp, getScaledTouchSlop())`), disertai press state (alpha 0.72 + scale 0.95).
- Tap atau fling inward → side panel.

**Side panel** (cockpit GameSpace, lebar 306 dp):

- Header fixed: hero FPS + frametime, GPU, CPU big, network, battery.
- Aksi scrollable: Boost RAM, Notifs (heads-up), Bypass CHG, No GOS.
- Preset switcher 4 tombol (ECO / BAL / PERF / ESPORTS).
- Segmen Floating Monitor: OFF / FPS / + TEMP / + GPU.
- Muncul dari sisi tempat handle menempel; tutup lewat tombol X atau swipe.

### 2.4 Telemetry

Sumber:

```text
/data/local/tmp/frameboost-snapshot.json
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

FPS self-healing: TimeStats di-enable otomatis, layer basi di-clear + enable ulang, dan tidak ada fallback ke rata-rata lama.

### 2.5 Safety

- Snapshot & restore stock config (termasuk `charging_enabled=1` saat rollback).
- Thermal safety dengan hysteresis lebar (Warn 87 °C, kembali 82 °C, Crit 92 °C).
- Keep-alive profil tiap 5 detik untuk menahan reset clock dari layanan Samsung.
- Auto-hide overlay di luar game; deteksi keluar game instan saat pindah ke launcher / system UI (poll 250 ms, debounce 2).
- Stale-daemon guard.
- BBR per game.
- Multi-target ping.
- Quick Boost.
- GOS bypass dengan deteksi paket: ROM tanpa GOS dianggap sudah bypass.
- Bypass charging dan toggle heads-up notifikasi lewat daemon.

---

## 3. Arsitektur

```text
WebUI (module-src/webroot)
        │ Unix socket IPC
        ▼
Rust daemon (frameboostd)
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
| `frameboostd/src/main.rs` | IPC, snapshot, command router |
| `frameboostd/src/profile.rs` | Preset & per-game config |
| `frameboostd/src/net.rs` | Ping, BBR, network |
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

- `hud-app/FrameBoost-GameSpace.apk`
- `module-src/system/bin/frameboostd`
- `module-src/system/priv-app/FrameBoost/FrameBoost-GameSpace.apk`
- `FrameBoost-v2.4.0-A50.zip`

Catatan deploy:

- `deploy-device.ps1` melakukan `adb install -r`, push binary daemon ke modul, lalu restart daemon + watchdog.
- `build-hud-apk.ps1` menyalin APK ke payload ZIP dan ke `system/priv-app/FrameBoost`.
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

- [ ] Per-app layout side panel
- [ ] Fallback FPS Choreographer saat SurfaceFlinger tidak tersedia
- [ ] Capability probe terpisah
- [ ] State machine overlay murni
- [x] Quick action tambahan (Boost RAM, Notifs, Bypass CHG, No GOS)

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

### `v2.4.0` — 2026-11-25

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
- **Responsive**: diuji pada lebar 320/360/411/540/700/820 CSS px, tanpa overflow horizontal dan semua tombol >= 44 px (tombol PLAY 56x44 px dengan padding 8 px). Layout berubah dua kolom mulai 700 px. Activity console dikunci portrait; overlay HUD tetap landscape saat game.
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

### `0aa121d` — 2026-11-24

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

### `f72310c` — 2026-11-24

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
### `<hash>` — `<tanggal>`

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

FrameBoost v2 sudah stabil dan layak dipakai harian. Fase berikutnya sebaiknya difokuskan pada perbaikan arsitektur, bukan penambahan fitur besar yang tidak perlu.
