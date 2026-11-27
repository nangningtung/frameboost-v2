# Bedah mendalam game space komunitas root (2026-11-27)

Lanjutan dari `docs/COMMUNITY-COMPARISON.md` (2026-11-25). Audit lama adalah
survey tingkat README; dokumen ini bedah **tingkat source dan biner** dari
proyek yang stabil dan dipakai komunitas root, dengan alat reverse-engineering
(REA) untuk artifact yang tidak punya source terbuka.

Metode:
- Source repo terbuka dianalisis langsung dari clone (bukan dari README).
- Biner rilis dianalisis dengan REA CLI 6.10.1 (`inspect-elf-dependencies`,
  inventaris ELF, string forensik). SHA-256 semua artifact dicatat.
- APK proprietary (Samsung GOS) tidak bisa ditarik: tidak ada di ROM device
  (sudah debloat), dan semua mirror publik (apkpure/apkcombo/apkmonk)
  diblokir dari jaringan ini. Bagian GOS hanya berisi bukti lokal + unknown.

---

## 1. Uperf (yc9559) — engine userspace lama yang jadi standar

Release yang dibedah: `dev-22.09.04` (rilis terakhir, 2022). Repo hanya berisi
modul Magisk + config; **source `uperfd` tidak dipublikasikan** (biner stripped).

Biner `bin/uperf` (SHA-256 `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`,
1.461.512 byte, ELF aarch64 PIE, `/system/bin/linker64`, min API 21, NDK r24):

- REA `inspect-elf-dependencies`: `DT_NEEDED = libm.so, libdl.so, libc.so` —
  **tidak ada libbinder/libutils/liblog**. Klaim README "tidak tergantung
  framework Android" terverifikasi di level ELF: komunikasi hanya lewat
  `/proc`, `/sys`, `/dev/input`, cpuset, dan wakelock sysfs.
- Flag keamanan: PIE + BIND_NOW (full RELRO setara).

### 1.1 Skenario/hint (dari README + config repo)

`None, Touch, Pressed, Tap, Swipe, HeavyLoad, SfLag, SfBoost, Standby
(delay 20 s, hidden), SsAnim, WakeUp`.

Poin arsitektur yang relevan:

- **Input dari Linux, bukan framework**: memonitor `/dev/input`, decode protokol
  layar sentuh, membedakan tap vs swipe + kecepatan akhir → prediksi durasi scroll.
- **HeavyLoad berbasis sampling sendiri**: `beban = Σ(efficiency(i) × load_pct(i)/100 × freq_mhz(i)/1000)`.
  Masuk HeavyLoad saat di atas ambang, keluar lebih awal saat turun. Ada
  `requestBurstSlack`: setelah HeavyLoad selesai, butuh beban di bawah `idleLoad`
  selama 1 s + jeda `requestBurstSlack` sebelum HeavyLoad baru diizinkan.
  Tujuannya eksplisit: **game dengan beban terus-menerus tidak boleh memicu
  HeavyLoad** — game harus jalan di frekuensi titik-imbang daya, bukan burst maks.
- **Standby**: hint layar mati ditunda 20 detik, lalu kurangi jumlah core aktif.
- **Affinity**: thread UI app aktif di-pin ke cluster besar.

### 1.2 sfanalysis — hook SurfaceFlinger ala lama

Modul terpisah `sfanalysis-magisk-22.09.04.zip`:

- `system/lib64/libsfanalysis.so` (SHA-256
  `386905b5e6237af09f61628f3a4feb257e17f47722a36b1c9a38eb0688987f31`, 25.952 B,
  NDK clang 14.0.1). REA: `DT_NEEDED` hanya `libdl.so` + `libc.so`.
- String forensik isi `.so`: `/system/bin/surfaceflinger`,
  `/system/lib64/libandroid.so`, `/system/lib64/libandroidfw.so`,
  `/proc/self/maps`, `/proc/%d/comm`, `/proc/%d/stat`, `mprotect`, `prctl`,
  `pthread_cond_signal`, `clock_gettime`. Artinya: shim kecil yang memparse
  `/proc/*/maps` untuk menemukan alamat libandroidfw di proses SF, lalu
  menambal fungsi lewat `mprotect`.
- **Mekanisme injeksi (terverifikasi dari `post-fs-data.sh`)**: bundel
  `patchelf` milik modul menjalankan
  `patchelf --add-needed libsfanalysis.so /system/bin/surfaceflinger --output $MODDIR/...`,
  lalu magic-mount menimpa SF asli, dengan SELinux context dipertahankan
  (`ls -Zl` + `chcon`).
- **Pengaman bootloop**: tulis `flag/need_recuser`; jika SF crash saat boot,
  modul memasang file `disable` Magisk sehingga patch tidak dipasang lagi
  di boot berikutnya.
- UNKNOWN: jalur IPC shim → uperfd (tidak ada string socket di `.so`;
  source `surfaceflinger-analysis` di GitHub hanya README).

### 1.3 Config & kontrak integrasi

- 38 file config JSON per-SoC (sdm625…sdm855, exynos e1080…e990, MTK, gs101).
  **Tidak ada Exynos 9610** → installer uperf menolak A50 ("not supported").
  Secreto Space faktanya adalah config A50 yang tidak ada di uperf.
- `powercfg.json` (di modul): kontrak standar untuk UI eksternal —
  `{"module":"uperf,sfanalysis","state":"/sdcard/.../cur_powermode.txt",
  "entry":"/data/powercfg.sh","features":{...}}`. **Scene** (com.omarea.vtools)
  membaca file ini; mode per-app via `/data/powercfg.sh <mode>`.
- Mode global: `auto / balance / powersave / performance / fast` + per-app.

## 2. YAKT v17 (NotZeetaa) — lapisan kernel statis

Modul shell murni (`yakt.sh`, 253 baris), jalan sekali di boot
("not an AI module", "not a performance/gaming module" per README sendiri):

- `printk` dan `printk_devkmsg` off, log/stats scheduler off, SPI CRC off.
- **uclamp per cgroup**: top-app `uclamp.max=max, min=10, boosted=1,
  latency_sensitive=1`; foreground dibatasi `max=50`; background `min=20`;
  sys_bg `max=40`. Ini pola "prioritas top-app" paling sederhana.
- Renice `-20` untuk proses esensial (padanan di Secreto: `priority.rs` renice
  `-10` untuk game pids, dengan guard pid→package).
- schedutil rate-limit dari Pixel 3, tweak mglru.
- Publish benchmark (hackbench 6.632 → 2.205) di README.

Peran ekosistem: **base layer** yang jalan bareng engine dinamis
(uperf/Scene/fas-rs), bukan pengganti engine.

## 3. fas-rs (shadow3aaa) — FAS modern, GPL-3.0, aktif 2026

Ini kandidat v0.2 di audit lama ("frame aware scheduling butuh timing frame
per frame"). Sekarang diketahui **persis bagaimana** mereka mendapatkannya.

### 3.1 Sumber frame: eBPF uprobe, bukan patch SF

`frame-analyzer` 0.3.4 (crates.io: "Track the frametime of Android apps,
based on ebpf & uprobe"; repo `shadow3aaa/frame-analyzer-ebpf`):

```rust
program.attach(
    Some("_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi"),
    0,
    "/system/lib64/libgui.so",
    Some(pid),
).or_else(|_| program.attach(
    Some("...queueBuffer...SurfaceQueueBufferOutput..."),
    ...
))
```

- Uprobe **per-PID pada proses game itu sendiri**, simbol
  `android::Surface::queueBuffer` di `/system/lib64/libgui.so` (dua mangling
  berbeda untuk API lama/baru), program eBPF 4 KB ring buffer menulis
  `bpf_ktime_get_ns()` tiap queueBuffer.
- Konsekuensi desain: tidak menyentuh biner sistem (beda total dari sfanalysis),
  tidak butuh SELinux surgery, dan otomatis hilang saat proses game mati.
- Ketentuan kernel: butuh CONFIG_BPF / BPF_SYSCALL + perf events (feasibility
  di kernel A50 harus dicek — lihat §6).
- Deps daemon fas-rs: `aya 0.13` (eBPF userspace), `dumpsys-rs` (klien binder
  **pure-Rust** buatan penulis — tidak spawn biner dumpsys), `mlua` (Luajit
  vendored) untuk API ekstensi user, `inotify`, `sys-mount`.

### 3.2 Model kontrol

- Config per-package: `target_fps` array (`[30,60,120,144]`) dicocokkan runtime;
  `margin_fps` (toleransi drop) diskalakan `target_fps/60*base` dengan override
  per target; `core_temp_thresh` dalam milli-°C memicu kontrol termal bawaan
  (bisa `"disabled"`).
- Controller: buffer frametime → `current_fps_long` (rata-rata panjang) dan
  `current_fps_short` (rata-rata N frame terakhir); policy di
  `scheduler/looper/policy/controll.rs` menulis frekuensi dari dua nilai itu.
- API ekstensi versi (v0–v4) + Lua: per-app override tanpa rebuild daemon.
- Ada `webui/` sendiri (KSU WebUI style) — pola yang sama dengan WebUI Secreto.

## 4. AZenith (Liliya2727) — all-in-one terdekat dengan Secreto Space

Apache-2.0, Android 11+, stable. Komposisi:

- **sysdaemon** (Rust, deps `libc` + `signal-hook` saja; `aya` di arm64):
  monitor app **pure-binder** (tanpa fork dumpsys di hot path) + FPS client
  via eBPF uprobe (sumber frame 64-bit only).
- **archdaemon**: profil, bypass charging, FPS recorder CSV/JSON.
- **thermalcore**: daemon termal terpisah — kontroler PID + feed-forward
  prediktif, fusi 50/50 suhu baterai (EWMA α=0.3) dan zona CPU, tulis
  intensitas pendinginan ke DVFSRC OPP (MTK) atau cooling devices aman.
  Filosofi eksplisit: berjalan **di samping vendor thermal HAL**, bukan
  melawannya; gain PID dipersist ke disk sehingga makin lama makin pas.
- **Manager app** Material 3 Expressive: Game Space landscape gamepad-first,
  sidepanel dock, pengaturan per-tweak (FPSGO, GED, WAL, SFL, thermal,
  scheduler), resolusi downscale, target fps, crosshair.

Relevansi untuk Secreto: bentuk produknya nyaris identik (daemon Rust + manager
+ side panel), tapi dua keputusan teknisnya lebih maju — pure-binder monitor dan
FPS eBPF — keduanya tanpa fork di hot path.

## 5. Samsung GOS — status bukti

- ROM device (A50) **tidak punya** paket game Samsung sama sekali
  (`pm list packages` kosong untuk game/gos/booster; GOS sudah dibuang).
- Mirror APK publik semua diblokir/404 dari jaringan audit ini.
- Implementasi Secreto (`main.rs`): `gos_disable` =
  `pm disable-user --user 0 com.samsung.android.game.gos` + flag atomik,
  `gos_restore` = `pm enable`, `gos_status`; ada `has_samsung_gos()` guard.
  Pendekatan yang sama yang umum dipakai komunitas Samsung.
- UNKNOWN (tidak bisa dibuktikan di sesi ini): mekanisme internal GOS
  (limitasi resolusi/fps/thermal per game). Butuh APK dari firmware stock.

## 6. Matriks arsitektur

| Aspek | Secreto 0.1.x | uperf (2022) | fas-rs (aktif) | AZenith | YAKT |
|---|---|---|---|---|---|
| Bahasa daemon | Rust | C++ (source closed) | Rust | Rust ×3 | shell |
| Sumber sinyal beban | dumpsys fg cache (250 ms) | /dev/input + sampling + SF hook | **eBPF uprobe queueBuffer per-pid** | eBPF uprobe + binder monitor | — (statis) |
| Deteksi foreground | daemon cache + dumpsys | cpuset/wakelock watcher | topapp watcher | pure-binder | — |
| Kontrol performa | preset statis + keep-alive + renice | hint dinamis 11 skenario | controller FPS long/short | profil per-app + thermal PID | statis boot |
| Termal | hysteresis 87/82/92/80, hormati HAL | hormati HAL (FAQ eksplisit) | core_temp_thresh milli-°C | PID+FF fusi baterai/SoC | — |
| Injeksi sistem | tidak ada | patchelf SF (legacy) | tidak ada (eBPF) | tidak ada (eBPF) | tidak ada |
| Hot path fork | ada (`pm`, `pidof`, `dumpsys`) | tidak (libc-only) | tidak (dumpsys-rs) | tidak (binder) | tidak |
| Per-app mode | preset per game | per-app via powercfg.sh | per-package target_fps | profil per app | — |
| WebUI | ya | tidak (kontrak JSON) | ya | ya (manager app) | tidak |
| Config A50/9610 | ya (dedicated) | **tidak ada** | universal + per-app | universal | universal |

## 7. Rekomendasi konkret untuk Secreto Space

Prioritas berdasarkan gap terbesar vs bukti terbaik:

1. **Hilangkan fork di hot path** (faclilitas murah, gaya AZenith/fas-rs):
   `pidof`, `dumpsys`, `pm` dijalankan daemon via `std::process::Command`
   tiap event. Alternatif: cache hasil, baca `/proc/<pid>/cmdline` langsung
   (sudah dipakai di `priority.rs` — perluas), dan pertimbangkan klien binder
   Rust ala `dumpsys-rs` untuk fg detection.
2. **Kaji eBPF uprobe queueBuffer untuk v0.2** (fas-rs): satu syscall attach
   per game memberi frametime presisi tanpa menyentuh SF. Prasyarat yang harus
   dicek di kernel topser9/universal9610: `CONFIG_BPF_SYSCALL`,
   `CONFIG_HAVE_PERF_EVENTS`, `CONFIG_DEBUG_INFO_BTF` (BTF opsional untuk aya).
   Kalau kernel tidak dukung, fallback: SF frame events via `dumpsys SurfaceFlinger`
   berbiaya rendah atau buffer-age trace.
3. **Standby + burst discipline ala uperf**: hysteresis termal Secreto sudah ada;
   tambah sisi waktu — hint layar-mati ditunda ±20 s, dan mekanisme slack agar
   beban game kontinu tidak memicu "burst" berulang (segmen HeavyLoad-nya uperf
   adalah referensi implementasi tertulis terbaik).
4. **Kontrak integrasi `powercfg.json`** (uperf): murah dan membuat Secreto bisa
   dipakai UI ekosistem (Scene dsb.); cukup tulis state file + entry script
   saat mode berubah.
5. **Per-app mode global** (auto/balance/powersave/performance/fast ala uperf)
   di atas preset per-game yang sudah ada — dua lapis, bukan pengganti.
6. **Crash guard pola sfanalysis** untuk semua aksi boot-time Secreto:
   flag `need_recuser` + self-disable bila restore gagal — parity check dengan
   `rollback.rs`.
7. **Kontribusi komunitas**: buat `config/e9610.json` untuk uperf dari hasil
   tuning A50 Secreto (gap nyata: uperf tidak punya Exynos 9610) — jalan
   membangun reputasi + memvalidasi nilai tuning di device lain.
8. Yang sengaja **tidak** direkomendasikan: injeksi patchelf ke SF (pola
   2022, rapuh di ROM baru; fas-rs/AZenith sudah membuktikan jalur eBPF lebih
   bersih), dan thermal override (thermal HAL A50 terbukti benar di audit 3.4).

## 8. Bukti & batasan

Artifact dianalisis (semua di `C:\Users\Administrator\Documents\code\gamespace-research\`):

| Artifact | SHA-256 (ringkas) | Metode |
|---|---|---|
| uperf-release/bin/uperf | f1265757…f49d8 | REA inspect-elf-dependencies |
| sfanalysis system/lib64/libsfanalysis.so | 386905b5…87f31 | REA ELF + string forensik |
| uperf repo @ dev-22.09.04 | git clone | source/config analysis |
| YAKT repo @ v17 | git clone | source analysis |
| fas-rs repo (main) | git clone | source analysis |
| frame-analyzer-ebpf (main) | git clone | source analysis |
| AZenith repo (main) | git clone | source analysis |
| SecretoSpace daemon src | repo lokal | comparison baseline |

Tidak bisa dibuktikan di sesi ini: internal `uperfd` (stripped, source closed),
jalur IPC sfanalysis→uperfd (source unpublished), internal Samsung GOS (APK
tidak tersedia: tidak ada di ROM, mirror diblokir), dukungan BPF kernel A50
(belum dicek di device — langkah pertama rekomendasi #2).

Jumlah bintang/status komunitas tidak diulang di sini; angka terakhir
terverifikasi ada di `docs/COMMUNITY-COMPARISON.md` (2026-11-25).

---

# Putaran kedua: pemindaian global ekosistem (2026-11-27)

Pindaian GitHub API (search + profil penulis) memetakan ~40 repo di ekosistem
root, lalu di-clone dan dibedah pilihan yang mewakili teknik berbeda.

## 9. Keluarga "FPS unlock" — spoofing device per-app

Temuan lintas repo: **tier grafis/FPS di dalam game ditentukan whitelist model
device**, dan seluruh keluarga modul ini membuka tier lewat spoofing.

| Proyek | ★ | Teknik | Cakupan efek |
|---|---:|---|---|
| `ahkehra/GameUnlocker` | 132 | `persist.sys.pixelprops.games=false` + kosongkan `ro.product.*.model` saat install (pilih game via volume key) | **Global** — README sendiri mengakui SafetyNet bisa gagal, kamera MIUI rusak |
| `AlirezaParsi/COPG` | 442 | Zygisk companion + `unified_controller` + WebUI; database spoof per-SoC (cpuinfo Kirin 9020/9030, Xiaomi Xring O1/O3, SD 8 Elite; GPU renderer `Adreno (TM) 830/840` + vendor_id) | **Per-app** — tidak merusak sistem lain |
| Modul LSPosed (Pubg-120fps-LpPosed, Spoof-GT-50-Pro, dsb.) | <10 | Spoof model ke OnePlus Pad Pro / Infinix GT 50 Pro agar game membuka tier 120/144 fps | Per-app (via LSPosed) |

Detail COPG yang layak ditiru formatnya:

- `CPU/manifest.json` + file `cpuinfo_<soc>`: **database spoof berbasis data**,
  SoC baru tinggal ditambah file — persis pola config JSON uperf.
- `GPU/manifest.json`: renderer string + vendor_id — game membaca ini lewat
  `GLES/glGetString`, bukan hanya prop.
- WebUI (COPG.json) chmod + chcon `system_file` oleh service.sh.
- CI membangun multi-ABI; `build.sh` bisa jalan **on-device via Termux**.

Kesimpulan: fitur "unlock grafis" yang aman = **spoofing per-app terkontrol**
dengan database, bukan props global ala GameUnlocker.

## 10. MaxManager — pola "one write path" (docs-only)

`nader295/MaxManager` (94★): **source tidak dipublikasikan** (repo hanya docs,
0 file .kt/.rs/.c). Semua klaim di bawah berasal dari `docs/architecture.md`:

- **Satu write path**: semua mutasi hardware lewat `HardwareControlArbiter` —
  journal intent → resolve ownership (safety > per-app > manual > AI) → write →
  **read-back** → rollback bila tidak cocok → **DriftGuard** memeriksa ulang
  drift senyap nanti.
- Gate CI yang **menggagalkan build** bila ada tulisan sysfs di luar arbiter —
  disiplin journal/rollback Secreto sampai level CI.
- 5 daemon native: C daemon utama (app loading, **game preload via vmtouch**,
  PID tracking, bypass charging, inotify), Rust thermal core, Rust chipset
  profiles, Rust utility conf, C preloader.
- JNI seam dengan **wire format berkontrak** + test simbol per ABI.
- `SharedHardwareOwnershipStore` + `ManualControlLocks`: kepemilikan knob
  lintas proses (app vs daemon).

Evidence limit: tidak ada source untuk diverifikasi; nilai di sini adalah pola
arsitektur, bukan implementasi.

## 11. fas-rs `vtools/` — kontrak powercfg.json lintas engine

`fas-rs/module/vtools/gen_json.sh` menghasilkan JSON bergaya `powercfg.json`
(upertf-style: `name/author/version/features/state/entry`) untuk integrasi
**Scene (vtools)**. Artinya kontrak UI eksternal bukan resep uperf saja —
**dua engine besar (uperf dan fas-rs) sama-sama bicara kontrak ini**, dan Scene
adalah UI de-facto di atas keduanya. Modul ekstensi `fas-rs-usage-clamping`
menggunakan API Lua: `API_VERSION`, hook `start_fas/stop_fas/load_fas/
unload_fas`, config `games.toml` dengan `scene_game_list = true` (membaca
game list milik Scene juga).

## 12. AZenith `focused.rs` — pure-binder foreground detection

Source terbuka (GPL-3.0). Teknik konkret, langsung relevan dengan
`detect.rs` Secreto yang masih spawn `dumpsys window`:

- `IProcessObserver` via binder (event perubahan foreground),
- **polling "focused root task" (ActivityTaskManager getFocusedRootTaskInfo)
  tiap 1 s sebagai otoritas** untuk mengoreksi event yang terlewat —
  komentar kode menjelaskan edge case home gesture: launcher dapat foreground
  tanpa app keluar dari state foreground di AMS, event terakhir jadi salah;
  RootTaskInfo adalah sumber kebenaran yang benar,
- `/proc` hanya discan cadangan, maksimal tiap 5 s,
- semua lewat libbinder_ndk (`AParcel`, kode transaksi) — **tanpa fork shell**.

Pola "focused task as authority" yang sudah dipakai Secreto (via dumpsys) bisa
naik kelas ke binder langsung: latensi lebih rendah, tanpa spawn.

## 13. Zygisk in-game overlay (reveny/Zygisk-ImGui-Mod-Menu)

`preAppSpecialize` mencocokkan `nice_name` dengan `TARGET_PACKAGE`
(hard-coded `com.tencent.ig`), lalu meng-inject ImGui + Dobby ke **proses
game itu sendiri** dan menggambar menu/HUD dari dalam proses — tanpa izin
overlay window.

Evaluasi untuk Secreto: overlay Secreto memakai window
`TYPE_APPLICATION_OVERLAY` — lebih aman (tidak menyentuh proses game, tidak
pemicu anticheat, tidak perlu build per game). Teknik ImGui relevan bila suatu
saat butuh HUD yang ter-tie ke frame game (60 fps ikut vsync game), tapi biaya
turnamen/risk anticheat tinggi. **Keputusan: tetap window overlay.**

**Update keputusan (2026-11-27 malam)**: eksperimen `inproc-hud/` berhasil
sebagai riset (injeksi terverifikasi di proses app + MLBB setelah umount
dimatikan; hook `eglSwapBuffers` terpasang; bridge daemon→HUD bekerja),
tapi **di-shelve dari payload produksi**: (1) MLBB ternyata render via
Vulkan sehingga hook GL tidak pernah kena frame — butuh renderer Vulkan
baru yang berat; (2) risiko anticheat untuk akun user; (3) pelajaran
deployment: **file zygisk .so dipetakan zygote sejak boot — jangan pernah
timpa in-place dengan `cp`** (mapping kotor membuat SEMUA app baru crash
`execute non-executable memory` hingga reboot). Ganti selalu dengan
inode baru (`mv`) + reboot. Kode eksperimen tetap di repo (`inproc-hud/`),
payload modul bersih tanpa `zygisk/`.

## 14. Metadata modul generasi baru

AZenith `module.yaml` memakai **schema resmi `KernelSU-Modules/tools`
(`schemaVersion: 1`)**: id/summary/tags/authors/compatibility (minApi,
architectures)/release.assetPattern — superset terstruktur dari `module.prop`.
Ekosistem KSU/APatch/MMRL bergerak ke arah manifest ber-schema. Secreto bisa
mengadopsi `module.yaml` di samping `module.prop` untuk kompatibilitas loader
baru.

## 15. Diperiksa, tidak diadopsi (noise tier)

- `Zy0x/Lynx` (18★), `Llucs/SpeedCool` (54★): modul tweak snapdragon klasik —
  pola sysfs statis yang sudah tercakup YAKT; tidak ada teknik baru.
- `NotZeetaa/DroidConsole`: terminal toolbox interaktif (gpu throttling,
  swappiness, schedutil Pixel 3) — padanan YAKT versi menu; tidak diadopsi.
- `reveny` mod menu: teknik oke untuk riset, tapi distribusi cheat — bukan
  komponen game space.
- `waffleboost`, `OptiPath`, `EliteGaming`, modul 0-5★ lainnya: kualitas tidak
  terverifikasi, tidak dibedah lebih dalam.

## 16. Tambahan bukti & digest (putaran kedua)

| Artifact | Lokasi clone | Status source |
|---|---|---|
| GameUnlocker | gamespace-research/GameUnlocker | terbuka (shell) |
| COPG | gamespace-research/COPG | modul + data terbuka; .so/controller dari CI artifact |
| MaxManager | gamespace-research/MaxManager | **docs-only** (source closed) |
| Zygisk-ImGui-Mod-Menu | gamespace-research/Zygisk-ImGui-Mod-Menu | terbuka (C++) |
| fas-rs-usage-clamping | gamespace-research/fas-rs-usage-clamping | terbuka (shell+lua) |
| DroidConsole | gamespace-research/DroidConsole | terbuka (shell) |

Unknown baru: biner COPG `.so`/`controller` tidak ada di repo (dibangun CI;
mechanisme hook cpuinfo/GLES diinfer dari manifest data, bukan dibedah biner).
Kalau nanti diperlukan kepastian, unduh release COPG dan jalankan REA ELF
(metode sama seperti uperfd).
