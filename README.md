# FrameBoost v2 (Samsung Galaxy A50)

High-performance native Rust game optimizer daemon and KernelSU WebUI for Exynos 9610 (Mali-G72 MP3).

## Features
- **Native Rust Daemon (`frameboostd`)**: Light memory footprint, zero shell fork overhead during polling.
- **Safety Engine**: Real-time readback verification, stock state snapshotting, automated rollback on exit or reboot.
- **4-Layer Game Detection**:
  - `config.sh` static list (`GAME_PKGS`)
  - Persistent game lists (`games.list`, `custom_games.list`, `auto_games.list`)
  - Android `GameManagerService` (`dumpsys game` & `cmd game list-modes`)
  - Engine process heuristics (`unity|game|render|glthread|cocos|godot|sdl|egl|vulkan`)
- **Installed-Only Game Library**: the WebUI lists only game profiles whose packages are still installed (`pm list packages -3`). Uninstalled games disappear automatically, and no placeholder entries are shown while the daemon is offline.
- **Per-Game Preset Tuner**:
  - `BATTERY`: GPU Dynamic 385MHz • CPU4 1.25GHz
  - `BALANCED`: GPU Booster 455MHz • CPU4 1.35GHz
  - `PERFORMANCE`: GPU Booster 572MHz • CPU4 1.51GHz
  - `ESPORTS`: GPU Booster 683MHz • CPU4 1.51GHz
- **Auto-BBR Ping Booster**: Dynamic TCP congestion control switching for online gaming.
- **Game Launch Hygiene**: Kernel pagecache drop & `renice -10` on foreground focus.
- **Console HUD WebUI**: Live telemetry, HTML5 Canvas frametime graph (16.6ms target), game library management.
- **In-Game Bubble + Side Panel**: A GameSpace-style floating ball that snaps to the nearest screen edge. Tap (or flick inward) to slide out the edge panel with live FPS/GPU/CPU/BBR/battery, bubble mode (`FPS`, `+TEMP`, `+GPU`), quick game presets, and a bubble-telemetry toggle. Swipe the panel header outward or tap ✕ to collapse it.
- **Multi-Target Ping**: Gateway, Cloudflare, Google and a custom host are probed in one daemon call (`net_multi_ping`); pick a row to drive the gauge and the 8 latency metrics.
- **GSI Stock Dark Theme**: Material dark palette (`#101114` base, `#D0BCFF` primary) shared by the WebUI, the native launcher, and the floating HUD.

## Build & Deploy

### One command (build + install + deploy)
```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1
```
The script builds the ARM daemon, builds and signs the APK, packages the module ZIP, installs the APK, replaces the daemon on the device, restarts the watchdog, verifies `list_games`, and relaunches the app.

Deploy without rebuilding:
```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1 -SkipBuild
```

### Manual steps
```powershell
# 1. Compile native binary (must use the Android target; plain `cargo build` fails on Windows)
cd frameboostd
cargo build --release --target aarch64-linux-android
Copy-Item target\aarch64-linux-android\release\frameboostd ..\module-src\system\bin\frameboostd -Force

# 2. Build + sign APK (also syncs the APK into module-src for the ZIP)
cd ..
powershell -ExecutionPolicy Bypass -File .\build-hud-apk.ps1

# 3. Package module ZIP
python make-zip.py
```

The ZIP (`FrameBoost-v2.4.0-A50.zip`) is installed through KernelSU/Magisk Manager. `deploy-device.ps1` is faster for day-to-day updates and needs root ADB (`su`).

## Notes
- `disabled_games.list` under `/data/adb/a50_frameboost/` hides games removed from the UI. Clear the file (or re-add the package) to show them again.
- `frameboost_v2_gsi_stock_ui_ux.html` is a visual reference only; the runtime WebUI lives in `module-src/webroot`.
