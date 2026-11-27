# Third-Party Notices and Open Source Attributions

FrameBoost v2 adheres to open-source software licenses and community attributions. This document lists the upstream projects, community references, architectures, algorithms, and concepts adapted for FrameBoost on the Samsung Galaxy A50 (Exynos 9610, LineageOS Android 16, KernelSU).

---

## 1. fas-rs (Frame-Aware Scheduling for Android)

- **Repository**: https://github.com/shadow3aaa/fas-rs
- **Author**: shadow3 / shadow3aaa (China)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Concepts & Architecture Referenced**:
  - Concept of Frame-Aware Scheduling (FAS): evaluating runtime governor demand based on real visual frame presentation intervals rather than raw thread runqueue heuristics.
  - Native daemon design in Rust with low memory footprint and zero shell-fork overhead during active game detection.
  - SurfaceFlinger / gfxinfo frame presentation analysis and non-blocking background polling.
- **FrameBoost Adaptations & Safety Changes**:
  - Independent, clean-room Rust implementation tailored to the Exynos 9610 (Cortex-A73/A53 big.LITTLE) and Mali-G72 MP3.
  - Does not use eBPF (not supported on kernel 4.14 legacy A50 tree); instead utilizes throttled sysfs polling and non-intrusive sysfs governors.
  - No GPL code was directly copied; structural concepts and metric intervals were referenced.

---

## 2. Scene 6 / Scene 7 (Android Performance Toolbox)

- **Developer**: YuKongA (酷安 / CoolApk / China)
- **License**: Proprietary / Community Reference
- **Concepts & Architecture Referenced**:
  - Compact multi-cluster telemetry layout (big/little CPU frequency, GPU clock/busy percentage, thermal zones).
  - Multi-tier preset tuning methodology (BATTERY, BALANCED, PERFORMANCE, FAST/ESPORTS).
  - Explicit thermal hysteresis guard to prevent thermal throttling oscillations.
- **FrameBoost Adaptations & Safety Changes**:
  - Configured specifically for Samsung A50 `/sys/kernel/gpu/` and `/sys/devices/system/cpu/` nodes.
  - Implements automatic rollback snapshot before any tuning is applied.

---

## 3. revws/ror (ROR Game Booster & Floating Assistant)

- **Repository**: https://github.com/revws/ror
- **License**: Apache License 2.0
- **Concepts & Architecture Referenced**:
  - Single `WindowManager` window floating overlay architecture (`TYPE_APPLICATION_OVERLAY`) with collapsible state machine (`COLLAPSED`, `EXPANDED`, `DRAGGING`).
  - Haptic feedback integration on state toggles and preset switches.
  - Retractable drawer HUD with in-game quick preset switcher and 44px touch targets.
- **FrameBoost Adaptations & Safety Changes**:
  - Replaced WebView gameplay HUD with 100% native Android Views to prevent RenderThread stutter and memory bloat.
  - Added edge-snapping on release (`animateSnap`) to dock the pill smoothly on screen edges.
  - Decoupled disk snapshot reading to background executor (`ioExecutor`) so main UI thread never blocks on disk I/O.
  - Did NOT adopt dangerous kernel tweaks (e.g. `drop_caches 3` on launch, forced 240Hz, or unverified charge bypass).

---

## 4. MaheshSharan/FrameX-Android

- **Repository**: https://github.com/MaheshSharan/FrameX-Android
- **Author**: Mahesh Sharan
- **License**: MIT License
- **Concepts & Architecture Referenced**:
  - Safe snapshot-and-restore architecture: capturing device hardware baselines prior to session start and guaranteeing complete rollback on game exit or daemon crash.
  - Low-overhead telemetry collection without impacting game rendering pipelines.
  - Real-time battery and power calculation (`V * |I|`).
- **FrameBoost Adaptations & Safety Changes**:
  - Integrated into Rust `frameboostd` daemon via `StockSnapshot::capture()` and `restore_all()`.
  - Battery telemetry strictly restricted to verified standard sysfs paths (`/sys/class/power_supply/battery/`).

---

## 5. rdevz-ph/FPS-Meter-Android

- **Repository**: https://github.com/rdevz-ph/FPS-Meter-Android
- **License**: MIT License
- **Concepts & Architecture Referenced**:
  - Compact floating telemetry pill design inspired by RTSS (RivaTuner Statistics Server) and Samsung Perf Z.
  - Monospace telemetry typography and high-contrast color badges.
  - Touch slop discrimination (preventing accidental drawer toggling while dragging).
- **FrameBoost Adaptations & Safety Changes**:
  - Implemented in `OverlayService.java` and `overlay_layout.xml`.
  - Added value-change caching to avoid unnecessary Android layout recalculations.

---

## 6. yuroyami/PINGY & cloudflare/bbperf

- **PINGY Repository**: https://github.com/yuroyami/PINGY (AGPL-3.0)
- **bbperf Repository**: https://github.com/cloudflare/bbperf (MIT)
- **Concepts & Architecture Referenced**:
  - Rolling packet timeline visualization with color-coded latency thresholds (<35ms green, <75ms yellow, >=75ms red).
  - RFC 3550 standard jitter calculation and statistical percentiles (P50 median, P95, P99).
  - Bufferbloat detection: comparing unloaded (idle) network baseline against loaded latency burst to grade network buffer stability (Grades A+, B, C, D).
- **FrameBoost Adaptations & Safety Changes**:
  - Built natively into Rust `frameboostd` (`net.rs`) without external networking crates.
  - Added TCP port 443 connection fallback when mobile carriers drop ICMP echo requests.
  - HTML5 Canvas rolling timeline rendered directly in WebUI.

---

## 7. Pong-Development/packages_apps_GameBar

- **Repository**: https://github.com/Pong-Development/packages_apps_GameBar
- **License**: Apache License 2.0
- **Concepts & Architecture Referenced**:
  - Per-device hardware sysfs path configuration.
  - Zero-fabrication principle: telemetry reflects actual hardware nodes or displays unavailable status rather than guessing or interpolating fake numbers.

---

## 8. kanaodnd/Stellar-Tweaks (Regional: Rust + WebUI + KernelSU module)

- **Repository**: https://github.com/kanaodnd/Stellar-Tweaks
- **License**: Apache License 2.0
- **Concepts & Architecture Referenced**:
  - Modularized Rust daemon architecture with decoupled WebUI (IPC-based).
  - Event-driven game detection via TaskStackListener / PackageManager (replacing shell polling).
  - State cache structure to eliminate redundant sysfs hardware writes.
  - Native system toast notification pipeline via Android Binder IPC.
  - Custom GPU governor configuration per profile in daemon + WebUI.
  - Granular component uninstaller (clean tweaks / clean config / clean all).
- **FrameBoost Adaptations & Safety Changes**:
  - Borrowed event-driven detection concept and state-cache idea for future daemon refinement.
  - Did NOT adopt device spoofing, refresh-rate injection, or ADPF property writes (out of scope/risk).
  - Did NOT adopt license-verification binder (proprietary).

---

## 9. Aibeto/ChiRiScene (Regional: China, eBPF + Rust + FAS)

- **Repository**: https://github.com/Aibeto/ChiRiScene
- **License**: GPL-3.0
- **Concepts & Architecture Referenced**:
  - Frame-Aware Scheduling (FAS) driven by frame intervals (queueBuffer uprobe).
  - CLG load governor based on eBPF real-time load data.
  - Per-app whitelist with auto-switch and restore on exit.
  - WebUI via KernelSU manager (no extra app).
- **FrameBoost Adaptations & Safety Changes**:
  - FAS concept already referenced via fas-rs; ChiRiScene reinforces per-app whitelist pattern.
  - Did NOT adopt eBPF (not supported on A50 kernel 4.14 legacy tree).
  - Did NOT copy GPL code; structural concepts only.

---

## 10. imacte/yumi (Regional: China, eBPF + Rust + PID FAS)

- **Repository**: https://github.com/imacte/yumi
- **License**: GPL-3.0
- **Concepts & Architecture Referenced**:
  - Monitor + Scheduler dual-thread architecture with mpsc event channel.
  - eBPF probes: sched_switch tracepoint (per-core CPU), queueBuffer uprobe (frame intervals).
  - PID-controlled frame-aware scheduling with auto capacity-weight detection.
  - Per-app configuration and CPU-utilization-assisted frequency scaling.
- **FrameBoost Adaptations & Safety Changes**:
  - Dual-thread concept informs our ioExecutor + handler pattern.
  - Did NOT adopt eBPF (kernel limitation on A50).
  - Did NOT copy GPL code; architecture patterns only.

---

## 11. Dreamucxe/GameCore (Global, MIT, "readings not theatre")

- **Repository**: https://github.com/Dreamucxe/GameCore
- **License**: MIT License
- **Concepts & Architecture Referenced**:
  - "Every number is a reading - never a zero/estimate/green checkmark" philosophy (matches our no-fake-telemetry rule).
  - Floating button snap to 4 corners / nearest edge; separate portrait/landscape positions; survives rotation.
  - Thermal warning dot (orange/red) when device hot/critical.
  - Quick sheet on nearest edge; max 6 pinned toggles; narrow to keep game visible.
  - Stats pill configurable: stat selection, order, text size, opacity, corner radius.
  - Per-game profile fields: refresh rate, brightness, display size, orientation, volume, DND, overlays, HUD layout, colour, session tracking.
  - n/a + reason for unavailable readings (never zero).
  - Sampling only while a visible consumer needs it; stops when screen off.
- **FrameBoost Adaptations & Safety Changes**:
  - Adapted edge-snap + thermal dot + n/a convention to OverlayService.
  - Adapted per-game profile field schema (extended games.list).
  - Did NOT adopt Compose/UI rewrite (kept WebView + native Views).
  - Did NOT adopt plugin SDK (out of scope).

---

## 12. Muimex11 (Regional: Samsung One UI, Shizuku-based)

- **Repository**: https://github.com/dwicao/Muimex11
- **License**: Community Reference
- **Concepts & Architecture Referenced**:
  - Samsung panel: GOS (Game Optimizing Service) disable + restore.
  - RAM Plus off, logging daemons frozen, Samsung bloat frozen.
  - Max Boost: thermal override + background clear + cache trim in one tap.
  - Floating dock tile grid with live on/off state.
  - Thermal override + headroom forecast.
- **FrameBoost Adaptations & Safety Changes**:
  - GOS disable/restore concept adapted to Rust daemon (planned).
  - Did NOT adopt cache cleaner / RAM booster (overhead risk).
  - Did NOT adopt fstrim / dexopt (out of scope).

---

## 13. hotsuper901/dlss5android (MSJ GFX) (Global, MIT)

- **Repository**: https://github.com/hotsuper901/dlss5android
- **License**: MIT License
- **Concepts & Architecture Referenced**:
  - Detection honesty: UNKNOWN_NO_PERMISSION state, never fake "no game".
  - Per-game presets: Battery / Balanced / Competitive, persisted, with live recommendation.
  - Frametime/RAM HUD on TYPE_APPLICATION_OVERLAY, finger-draggable, 500ms refresh, sampled off main thread.
  - Thread safety: every binder/sysfs read on Dispatchers.Default; UI thread only renders.
  - "No CPU meter" deliberate choice (sandboxed app can't read trustworthy device-wide CPU).
- **FrameBoost Adaptations & Safety Changes**:
  - Detection-honesty principle already adopted (daemon offline state).
  - Thread-safety pattern already adopted (ioExecutor).

---

## 14. FreshROMs/android_kernel_samsung_exynos9610_mint (Exynos 9610 Kernel)

- **Repository**: https://github.com/FreshROMs/android_kernel_samsung_exynos9610_mint
- **License**: GPL-2.0
- **Concepts & Architecture Referenced**:
  - Exynos 9610 (Cortex-A73/A53 big.LITTLE) + Mali-G72 MP3 kernel source.
  - EMS (Exynos Mobile Scheduler) optimized for low-latency tasks.
  - Anxiety I/O scheduler as default.
  - Various performance improvements from Linux upstream and Qualcomm.
- **FrameBoost Adaptations & Safety Changes**:
  - Hardware reference for sysfs node paths on A50.
  - Did NOT modify kernel (out of scope; module operates in userspace).

---

## 15. NetizenNemo/Aether_OptExt (Regional: China, Rust + eBPF)

- **Repository**: https://github.com/NetizenNemo/Aether_OptExt
- **License**: GPL-3.0
- **Concepts & Architecture Referenced**:
  - Rust thread CPU affinity optimization (Magisk/KernelSU module).
  - eBPF acceleration via sched_process_exec tracepoint (nanosecond-level PID capture).
  - Fallback to /proc polling when eBPF unavailable.
- **FrameBoost Adaptations & Safety Changes**:
  - eBPF process-exec tracepoint pattern referenced for future daemon enhancement.
  - Did NOT adopt eBPF (kernel limitation on A50).
  - Did NOT copy GPL code; concepts only.

---

## 16. cinitdev/AppOpt (Regional: China, CPU thread core binding)

- **Repository**: https://github.com/cinitdev/AppOpt
- **License**: GPL-3.0
- **Concepts & Architecture Referenced**:
  - CPU thread core binding optimization for Android.
  - eBPF FPS monitoring.
  - Auto-calibration.
- **FrameBoost Adaptations & Safety Changes**:
  - Thread-binding concept referenced for future per-game tuning.
  - Did NOT adopt eBPF (kernel limitation on A50).

## 17. ocornut/imgui (In-process HUD rendering)

- **Repository**: https://github.com/ocornut/imgui
- **License**: MIT
- **Usage**: UI rendering di dalam proses game untuk HUD in-process
  (`inproc-hud/`, modul Zygisk). Dibangun statis ke dalam .so.

## 18. jmpews/Dobby (Inline hooking)

- **Repository**: https://github.com/jmpews/Dobby
- **License**: Apache-2.0
- **Usage**: hook `eglSwapBuffers` untuk menggambar HUD ImGui pada
  frame buffer game (statik: `third_party/dobby/libs/arm64-v8a/libdobby.a`).

## 19. topjohnwu/Magisk — zygisk.hpp (Public API header)

- **Repository**: https://github.com/topjohnwu/Magisk
- **License**: GPL-3.0 (header API publik Zygisk, dipakai tanpa modifikasi,
  sesuai catatan "DO NOT MODIFY" di header)
- **Usage**: antarmuka modul Zygisk untuk injeksi proses game via ReZygisk.
