# Perbandingan dengan proyek komunitas

Tanggal audit: 2026-11-25. Angka bintang diambil dari GitHub API pada tanggal itu,
bukan dari ingatan.

## 1. Proyek yang dicek

| Proyek | Bintang | Fokus | Lisensi | Catatan |
|---|---:|---|---|---|
| `yc9559/uperf` | 3.408 | Userspace performance controller | Apache-2.0 | Update terakhir 2022, dukungan sampai Android 12 |
| `shadow3aaa/fas-rs` | 1.124 | Frame aware scheduling | GPL-3.0 | Aktif, update 2026 |
| `Liliya2727/AZenith` | 151 | Modul all-in-one | Apache-2.0 | Paling dekat dengan cakupan Secreto Space |
| `chaldeaprjkt/packages_apps_GameSpace` | 44 | UI Android Game Mode | Apache-2.0 | Butuh patch SystemUI dan Settings |
| `zuanvfx02/zkmnext-gamespace` | 26 | Game space mandiri | tidak tercantum | Aplikasi terpisah, bukan modul root |
| `adivenxnataly/PerfGame` | 10 | Game Mode interventions | Apache-2.0 | Memakai `cmd game set --downscale/--fps` |
| `IRedDragonICY/GameSpace` | 7 | Fork GameSpace | Apache-2.0 | Fork, bukan engine baru |
| `revws/ror` | 5 | Launcher + kernel manager | Apache-2.0 | Klaim besar, validasi komunitas masih kecil |

`uperf` dan `fas-rs` adalah engine performa, bukan game space. `GameSpace` adalah UI
untuk Android Game Mode API dan justru membutuhkan patch ROM. Karena itu perbandingan
dibagi menjadi dua: engine performa dan produk game space.

## 2. Teknik yang dipakai proyek besar

### uperf

- Membaca input sentuh dari `/dev/input` untuk touch boost.
- Memantau SurfaceFlinger lewat injeksi `SfAnalysis` untuk mengenali render mulai,
  render lambat, dan render selesai.
- Menempelkan thread UI aplikasi yang sedang aktif ke big core.
- Mengatur frekuensi CPU/GPU dan cpuset per scene.
- Menyediakan profil per platform.

Yang relevan untuk A50: touch boost dan pemilihan frekuensi dinamis. Yang tidak
diadopsi: injeksi SurfaceFlinger, karena rapuh terhadap versi Android dan SELinux.
`uperf` juga berhenti di Android 12, sedangkan device ini Android 16.

### fas-rs

- Target FPS per game, misalnya `[30, 60, 120]`.
- `margin_fps`: toleransi drop sebelum scheduler menaikkan performa.
- `core_temp_thresh`: batas suhu sebelum turun mode.
- Penjadwalan berbasis frame, bukan berbasis timer.

Ini gap konseptual terbesar. Secreto Space 0.1.0 memakai preset statis plus keep-alive,
bukan controller per frame. Alasannya ada di bagian 4.

### AZenith

- Dua daemon Rust: profil dan monitor aplikasi.
- Profil per aplikasi, FPS recorder dengan CSV/JSON, downscale resolusi, thermalcore,
  bypass charging, DND, prioritas proses.
- Overlay dan side panel dengan layout per aplikasi.

Cakupannya paling dekat. Secreto Space sudah punya padanannya untuk sesi, journal,
statistik FPS, 1% low, jank, riwayat, saran preset, overlay, DND, brightness, BBR,
bypass charging manual, dan recovery.

### GameSpace dan PerfGame

- `chaldeaprjkt/GameSpace` hanya menampilkan mode game dari Android Game Mode API.
  Butuh patch `frameworks/base` dan `packages/apps/Settings`, jadi bukan modul root.
- `PerfGame` memakai intervention resmi Android 12+:
  `cmd game set --downscale <ratio> --fps <n> <package>`.

## 3. Yang diuji langsung di A50

### 3.1 Game Mode downscale resmi

Perintah:

```text
cmd game set --downscale 0.667 com.mobile.legends
```

Hasil:

- `cmd game list-configs` menampilkan `Scaling:0.667`.
- SurfaceFlinger menampilkan `toDisplayTransform={ scale x=1.4088 y=1.4099 }`,
  artinya buffer game sekitar 766x1660 lalu di-upscale ke 1080x2340.
- `cmd game reset` menghapus konfigurasi, tetapi layer game tetap ter-scale sampai
  game membuat surface baru. Pindah ke background lalu kembali tidak cukup.

Kesimpulan: mekanismenya bekerja, tetapi reset-nya tidak langsung. Itu bertentangan
dengan janji "selalu kembali ke native saat sesi selesai". Karena itu Secreto Space
tetap memakai `wm size` yang reset-nya langsung dan berjurnal.

### 3.2 Game Mode performance

```text
cmd game mode performance com.mobile.legends
```

Hasil: `Game mode: performance not supported by com.mobile.legends`.
MLBB hanya mengiklankan `standard` dan `custom`. Tidak ada efek yang bisa diukur.

### 3.3 Affinity thread render

- `RenderThread` MLBB sudah punya `Cpus_allowed_list: 4-7` dan mask `f0`.
- Thread utama game berjalan di `0-1`.
- Artinya game sudah menempatkan thread render di big core tanpa bantuan kita.

Kesimpulan: tidak ada gain yang bisa diklaim dari memindahkan affinity.

### 3.4 Batas frekuensi dan thermal

- Saat dingin (64 °C), `cpu4/scaling_max_freq` = 2.314.000 kHz, sesuai hardware.
- Saat 83-84 °C, thermal HAL menurunkan batas ke 1.768.000-1.898.000 kHz.
- Preset kita hanya menulis `scaling_min_freq`, tidak melawan thermal HAL.

Kesimpulan: tidak perlu memaksa `scaling_max_freq` di PERFORMANCE/ESPORTS. Thermal HAL
tetap menjadi pengaman, dan preset tidak boleh mengalahkannya.

### 3.5 I/O dan VM

- Scheduler blok sudah `cfq`.
- `read_ahead_kb` = 128.
- `dirty_ratio` = 20, `dirty_background_ratio` = 10.
- `swappiness` dan `vfs_cache_pressure` diubah saat sesi lalu dipulihkan dari snapshot.

Kesimpulan: tidak ada knob I/O yang perlu ditambahkan. Nilai stock sudah sama dengan
yang disarankan modul komunitas.

### 3.6 Render scale di lobby MLBB

- Native: 59,3 FPS.
- 720p: 59,0 FPS.
- Panel 60 Hz, jadi keduanya kena vsync cap.
- `gpu_busy` berubah-ubah karena scene lobby ikut berubah, sehingga belum bisa dipakai
  sebagai pembanding beban yang bersih.

Kesimpulan: mekanisme render scale terbukti bekerja dan bubble HUD tetap terlihat penuh
di 720p (`frame=[1444,188][1560,304]`), tetapi gain FPS in-match masih terbuka.

## 4. Keputusan untuk 0.1.0

Diadopsi:

- Preset per game dan keep-alive.
- Journal sebelum aksi, restore saat keluar, crash, reboot, dan uninstall.
- Freeze background app dengan appops + force-stop.
- Thermal hysteresis 87/82/92/80 °C.
- FPS dari SurfaceFlinger, p1 low, jank terhadap irama frame game.
- Riwayat sesi, saran preset, overlay, DND, brightness, BBR.
- Render scale berjurnal dengan `wm size`.
- Single-instance daemon dan recovery.

Tidak diadopsi di 0.1.0:

- Injeksi SurfaceFlinger ala uperf: rapuh, butuh hook native, dan berisiko SELinux.
- Frame aware scheduling ala fas-rs: butuh pembacaan timing frame per frame dan target
  FPS per game. Ini kandidat v0.2, bukan tambahan yang aman untuk 0.1.0.
- Game Mode downscale resmi: reset tidak langsung, seperti dijelaskan di 3.1.
- Game Mode performance: tidak didukung MLBB.
- Perubahan affinity: thread render sudah di big core.
- Paksa frekuensi maksimum: thermal HAL sudah menangani dengan benar.

## 5. Gap yang masih terbuka

1. A/B render scale di dalam match yang benar-benar GPU-bound. Lobby dan menu kena
   vsync cap, jadi belum ada angka in-match yang bersih.
2. Frame aware scheduling dengan target FPS per game, kalau nanti dibutuhkan.
3. Pengukuran beban GPU yang stabil. `gpu_busy` valid saat game live, tetapi scene yang
   berubah membuat rata-rata tidak sebanding.

Tidak ada klaim kemenangan FPS dari audit ini. Yang ada hanya mekanisme yang terbukti,
batas yang terukur, dan teknik komunitas yang sengaja tidak dipakai beserta alasannya.
