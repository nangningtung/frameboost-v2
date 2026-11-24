# Secreto Space 0.1.0 (Samsung Galaxy A50)

A game space for the Galaxy A50 (SM-A505F/GN, Exynos 9610, Mali-G72 MP3) on GSI
and One UI. KernelSU/Magisk module with a native Rust daemon, a console app and
an in-game overlay.

This module is device locked on purpose. `customize.sh` aborts the install and
`service.sh` refuses to start unless `ro.product.device` is `a50`.

## Features

- **Native Rust daemon (`secretod`)**: low memory footprint, no shell fork during polling.
- **Safety engine**: readback verification, stock snapshot, automatic rollback on exit or reboot.
- **4-layer game detection**:
  - `config.sh` static list (`GAME_PKGS`)
  - persistent lists (`games.list`, `custom_games.list`, `auto_games.list`)
  - Android `GameManagerService` (`dumpsys game`, `cmd game list-modes`)
  - engine process heuristics (`unity|game|render|glthread|cocos|godot|sdl|egl|vulkan`)
- **Installed-only library**: only games still installed by `pm list packages -3` are listed.
- **Per-game presets**:
  - `BATTERY`: GPU Dynamic 385MHz, CPU4 1.25GHz
  - `BALANCED`: GPU Booster 455MHz, CPU4 1.35GHz
  - `PERFORMANCE`: GPU Booster 572MHz, CPU4 1.51GHz
  - `ESPORTS`: GPU Booster 683MHz, CPU4 1.51GHz
- **Auto BBR**: TCP congestion control switched during online sessions.
- **In-game bubble and side panel**: snaps to the nearest edge, opens a cockpit with
  real FPS, GPU/CPU clocks, BBR, battery, quick actions and the preset switcher.
- **Honest telemetry**: FPS comes from SurfaceFlinger `--timestats` per layer.
- **Game sessions**: a session starts when a game comes forward, sleeps the user apps you
  do not need, and reports duration, average FPS, session-wide 1% low, jank against the
  panel vsync, peak temperature and battery. Every slept app is journaled first, so a
  crash, reboot or uninstall restores its original app-op mode.
  Missing data shows `--`, never a fabricated number.
- **Wide thermal hysteresis**: warn 87C, recover 82C, critical 92C.

## Requirements

- Samsung Galaxy A50 (`a50`). Other devices are rejected at install time.
- KernelSU or Magisk with root.
- Android 11 or newer. Tested on a GSI build, API 36.

## Build and deploy

### One command

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1
```

Builds the ARM daemon, builds and signs the APK, packages the ZIP, installs the
APK, replaces the daemon on the device, restarts the watchdog, checks
`list_games`, and relaunches the app.

Deploy without rebuilding:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1 -SkipBuild
```

### Manual steps

```powershell
# 1. Compile the native binary (Android target, plain cargo build fails on Windows)
cd secretod
cargo build --release --target aarch64-linux-android
Copy-Item target\aarch64-linux-android\release\secretod ..\module-src\system\bin\secretod -Force

# 2. Build and sign the APK (also syncs it into module-src for the ZIP)
cd ..
powershell -ExecutionPolicy Bypass -File .\build-hud-apk.ps1

# 3. Package the module ZIP
python make-zip.py
```

Install `SecretoSpace-v0.1.0-A50.zip` through KernelSU or Magisk Manager.
`deploy-device.ps1` is faster for day-to-day updates and needs root ADB (`su`).

## Upgrading from FrameBoost 2.x

`customize.sh` copies `game_profiles.json`, `stock_snapshot.json` and the game
lists from `/data/adb/a50_frameboost/` into `/data/adb/secreto_space/`, removes
the old module directory, uninstalls `com.a50.frameboost.hud`, and deletes the
old runtime files. Reboot once after installing so the new module takes over.

## Notes

- Runtime files: `/data/local/tmp/secreto.sock`, `secreto-snapshot.json`, `secretod.log`.
- User data lives in `/data/adb/secreto_space/` and is never overwritten by updates.
- `disabled_games.list` hides games removed from the UI. Clear the file to show them again.
- `frameboost_v2_gsi_stock_ui_ux.html` is a legacy visual reference, not shipped in the ZIP.
