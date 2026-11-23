# FrameBoost v2 — Project Report

Status: **Selesai untuk fase ini**  
Commit terakhir: `f72310c`  
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

- Edge handle menggantikan bubble besar.
- FPS overlay:
  - ON → handle melebar, angka FPS tampil.
  - OFF → handle kecil, titik status tampil.
- Tap/swipe inward → side panel.
- Side panel:
  - Hero FPS
  - GPU
  - CPU BIG
  - NETWORK
  - BATTERY
  - preset switcher
  - FPS overlay toggle

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

- FPS
- P1 low FPS
- suhu
- GPU clock
- CPU big clock
- congestion control / BBR
- baterai

Aturan:

```text
Tidak ada angka palsu.
Kosong = -- / N/A.
```

### 2.5 Safety

- Snapshot & restore stock config.
- Thermal safety.
- Auto-hide overlay di luar game.
- Stale-daemon guard.
- BBR per game.
- Multi-target ping.
- Quick Boost.
- GOS bypass.

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
- `FrameBoost-v2.3.0-A50.zip`

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
- [ ] Provider FPS fallback SurfaceFlinger / Choreographer
- [ ] Capability probe terpisah
- [ ] State machine overlay murni
- [ ] Quick action tambahan

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
