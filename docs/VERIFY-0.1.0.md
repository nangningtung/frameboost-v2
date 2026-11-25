# Verifikasi Secreto Space 0.1.0 di perangkat

Checklist ini dipakai setelah HP tersambung lagi. Semua langkah dijalankan dari
root project, dengan `adb` di PATH.

## 1. Install

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb push "SecretoSpace-v0.1.0-A50.zip" /data/local/tmp/SecretoSpace-v0.1.0-A50.zip
& $adb shell su -c "/data/adb/ksud module install /data/local/tmp/SecretoSpace-v0.1.0-A50.zip"
& $adb shell su -c "rm -f /data/local/tmp/SecretoSpace-v0.1.0-A50.zip"
& $adb reboot
```

Tunggu sampai `getprop sys.boot_completed` bernilai `1`.

## 2. Identitas dan migrasi

| Cek | Perintah | Harapan |
|---|---|---|
| Modul baru | `su -c "head -3 /data/adb/modules/secreto_space/module.prop"` | `id=secreto_space`, `version=v0.1.0` |
| Modul lama hilang | `su -c "ls /data/adb/modules"` | tidak ada `a50_frameboost` |
| Antrean kosong | `su -c "ls /data/adb/modules_update"` | kosong |
| Data termigrasi | `su -c "ls /data/adb/secreto_space"` | `game_profiles.json`, `stock_snapshot.json` |
| Profil utuh | `su -c "grep -A2 mobilechess /data/adb/secreto_space/game_profiles.json"` | preset terakhir tetap ada |
| APK lama hilang | `pm list packages \| grep frameboost` | tidak ada hasil |
| APK baru | `dumpsys package com.secreto.space \| grep versionName` | `versionName=0.1.0` |

## 3. Runtime

| Cek | Perintah | Harapan |
|---|---|---|
| Daemon | `pidof secretod` | ada PID |
| Log | `su -c "head -2 /data/local/tmp/secretod.log"` | `Secreto Space daemon v0.1.0 started` |
| Socket | `su -c "printf 'list_games' \| nc -U /data/local/tmp/secreto.sock"` | JSON daftar game |
| Snapshot | `cat /data/local/tmp/secreto-snapshot.json` | JSON valid |
| File lama | `ls /data/local/tmp \| grep frameboost` | tidak ada hasil |

## 4. Smoke test

```powershell
powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

Harapan: semua cek PASS, kecuali cek terkait game yang SKIP kalau tidak ada game
di foreground.

## 5. Uji fungsional

1. Buka game, pastikan overlay muncul dan FPS bergerak.
2. Ganti preset dari side panel, lalu balik ke app dan lihat kartu game ikut berubah.
3. Keluar game, cek `gpu_governor` dan `gpu_min_clock` kembali stock.
4. Reboot saat game aktif, pastikan setelah boot tidak ada sisa preset yang nyangkut.

## 5a. Sesi dan background apps

| Cek | Perintah | Harapan |
|---|---|---|
| Sesi mulai | buka game, lalu `su -c "printf 'session_status' \| nc -U /data/local/tmp/secreto.sock"` | `active:true`, `pkg` = game |
| Journal | `su -c "cat /data/adb/secreto_space/sleeping.list"` | satu baris `pkg mode` per app |
| App tidur | `su -c "wc -l < /data/adb/secreto_space/sleeping.list"` | belasan sampai puluhan app |
| Game tidak tidur | cek daftar journal | tidak ada game dari library |
| Laporan | keluar game, `su -c "cat /data/adb/secreto_space/session_last.json"` | `reason:"idle"`, `avg_fps`, `p1_low`, `jank_pct` terisi |
| Bangun semua | `su -c "printf 'wake_apps' \| nc -U /data/local/tmp/secreto.sock"` | `woke` > 0, sesi tetap aktif |
| Mode appops pulih | `cmd appops get com.discord RUN_IN_BACKGROUND` | sama seperti sebelum sesi |
| End session | tekan `End session & restore` di console | jam stock, journal hilang, app bangun |
| Tidak ada re-apply | tunggu 15 detik setelah End session | jam tetap stock |

`avg_fps`, `p1_low`, dan `jank_pct` hanya terisi kalau layer game benar-benar
menghasilkan frame. Jank dihitung terhadap irama frame game itu sendiri: frame yang
lebih dari 1,5x frame time median dianggap jank, dengan periode vsync panel sebagai
batas bawah. Jadi game 30 FPS yang stabil tidak lagi dilaporkan 97% jank.

## 5c. Laporan sesi dan riwayat

| Cek | Perintah | Harapan |
|---|---|---|
| Riwayat tersimpan | `su -c "cat /data/adb/secreto_space/session_history.json"` | maksimal 10 entri per game, 120 total |
| Riwayat via socket | `printf 'session_history com.mobilechess.gp' \| nc -U /data/local/tmp/secreto.sock` | `reports` urut terbaru, `suggestion` null atau satu kalimat saran |
| Kartu detail game | buka Games > pilih game | daftar sesi dengan durasi, FPS, 1% low, jank, suhu, jumlah app tidur |
| Saran preset | 3 sesi >= 5 menit dan FPS turun > 8% dari terbaik | `suggestion` menyebut preset yang lebih rendah |

## 5d. Skala render (eksperimental)

| Cek | Perintah | Harapan |
|---|---|---|
| Simpan mode | `printf 'set_render_scale com.mobilechess.gp 720p' \| nc ...` | `render.scales` berisi `720p` |
| Terapkan saat sesi | mulai sesi game itu | `wm size` menampilkan `Override size: 720x1560`, `wm_size.journal` ada |
| Reset saat selesai | `session_end` | hanya `Physical size`, journal hilang |
| Reboot di tengah sesi | reboot | `service.sh` menjalankan `wm size reset`, journal hilang |
| Tombol UI | detail game > Render scale | Native / 900p / 720p, pilihan tersimpan, note menyebut ukuran aktif |

Spike 2026-11-25 di Auto Chess: native dan 720p sama-sama 59-60 FPS di menu karena
vsync cap, suhu 71 °C vs 67 °C. Mekanisme terbukti; gain FPS in-match belum terukur.

## 5e. Overlay sesi

| Cek | Harapan |
|---|---|
| Panel saat sesi aktif | SESSION berjalan, JANK %, ASLEEP = jumlah app tidur, THERMAL OK/HOT/LIMIT |
| Panel saat idle | SESSION `--:--`, JANK `--`, ASLEEP `0`, THERMAL `OK` |
| Warna | JANK hijau di bawah 15%, amber 15-29%, merah 30% ke atas |

## 5f. Capability probe

| Cek | Perintah | Harapan |
|---|---|---|
| Probe socket | `printf 'probe' \| nc -U /data/local/tmp/secreto.sock` | `device:a50`, `rom:GSI`, `gos:false`, `freeze:appops + force-stop`, `wm_size:true` |
| Kartu System | buka tab System | nilai probe tampil, badge `READY` |

## 5b. Reboot di tengah sesi

1. Mulai sesi, tunggu sampai journal terisi.
2. `adb reboot`, tunggu `sys.boot_completed=1`.
3. `su -c "ls /data/adb/secreto_space/sleeping.list"` harus gagal (sudah dibersihkan).
4. `su -c "tail -20 /data/local/tmp/secretod.log"` berisi `session: recovered N apps`.
5. `cmd appops get com.discord RUN_IN_BACKGROUND` kembali ke mode semula.

## 6. Rollback

Kalau install 0.1.0 gagal, modul lama sudah dihapus oleh `customize.sh`. ZIP lama
masih ada di riwayat git:

```powershell
git show 0221973:FrameBoost-v2.4.0-A50.zip > FrameBoost-v2.4.0-A50.zip
```

Install ZIP itu lewat KernelSU, lalu reboot. Data di `/data/adb/secreto_space`
tidak dibaca oleh versi lama, jadi profil perlu disalin kembali ke
`/data/adb/a50_frameboost/` bila diperlukan.

## 7. Hasil uji 2026-11-25

| Bagian | Hasil |
|---|---|
| 1 Install | PASS, modul `secreto_space` v0.1.0, modul lama hilang |
| 2 Identitas | PASS, `com.secreto.space` versionName 0.1.0 |
| 3 Runtime | PASS, `secretod` jalan, socket dan snapshot terbaca |
| 4 Reboot | PASS, jam stock setelah reboot |
| 5a Sesi | PASS, 33 app tidur, game library tidak tidur, journal `pkg mode`, wake tanpa mengakhiri sesi |
| 5b Reboot saat sesi | PASS, log `session: recovered 31 apps, all knobs restored to stock`, journal dibersihkan, appops kembali `allow`/`default` |
| Whitelist | PASS, `com.whatsapp` dan `com.discord` di whitelist tidak masuk journal (31 dari 33) |
| Toggle sleep | PASS, `set_sleep_enabled 0` dan `1` tersimpan |
| 5c Riwayat sesi | PASS, 4 sesi Auto Chess tersimpan dan tampil di detail game; saran `null` karena belum ada 3 sesi >= 5 menit |
| 5d Skala render | PASS mekanisme: 720p diterapkan (`720x1560`), reset saat `session_end`, journal bersih; gain FPS in-match belum terukur |
| 5e Overlay | PASS, strip `SESSION 0:47 / JANK 8% / ASLEEP 33 / THERMAL OK` saat Auto Chess berjalan |
| 5f Probe | PASS, `a50`, GSI, GOS absent, `appops + force-stop`, `wm size` didukung, cpuset `memory` |
| Jank | PASS, Auto Chess 30 FPS stabil berubah dari 97,3% menjadi 2,0% setelah definisi memakai median frame time |
| IPC | PASS, tidak ada lagi `__secretoIpc[N] is not a function` setelah nomor slot callback ditangkap |
| Smoke test | PASS penuh 17 cek, termasuk sesi aktif dan journal |
