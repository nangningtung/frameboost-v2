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
- **Per-Game Preset Tuner**:
  - `BATTERY`: GPU Dynamic 385MHz • CPU4 1.25GHz
  - `BALANCED`: GPU Booster 455MHz • CPU4 1.35GHz
  - `PERFORMANCE`: GPU Booster 572MHz • CPU4 1.51GHz
  - `ESPORTS`: GPU Booster 683MHz • CPU4 1.51GHz
- **Auto-BBR Ping Booster**: Dynamic TCP congestion control switching for online gaming.
- **Game Launch Hygiene**: Kernel pagecache drop & `renice -10` on foreground focus.
- **Console HUD WebUI**: Live telemetry, HTML5 Canvas frametime graph (16.6ms target), game library management.

## Build & Package
```powershell
# 1. Compile native binary
cd frameboostd
cargo build --release --target aarch64-linux-android
Copy-Item target\aarch64-linux-android\release\frameboostd ..\module-src\system\bin\frameboostd -Force

# 2. Package module zip
cd ..
python make-zip.py
```
