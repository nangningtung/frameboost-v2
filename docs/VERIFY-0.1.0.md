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

## 6. Rollback

Kalau install 0.1.0 gagal, modul lama sudah dihapus oleh `customize.sh`. ZIP lama
masih ada di riwayat git:

```powershell
git show 0221973:FrameBoost-v2.4.0-A50.zip > FrameBoost-v2.4.0-A50.zip
```

Install ZIP itu lewat KernelSU, lalu reboot. Data di `/data/adb/secreto_space`
tidak dibaca oleh versi lama, jadi profil perlu disalin kembali ke
`/data/adb/a50_frameboost/` bila diperlukan.
