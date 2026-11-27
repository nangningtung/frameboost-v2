use crate::snapshot::StockSnapshot;
use crate::sysfs::write_verified;

/// Restores all device knobs to saved stock snapshot values.
/// Returns Ok(()) if every knob was successfully reverted,
/// or Err with list of failed knobs.
pub fn restore_from_snapshot(snap: &StockSnapshot) -> Result<(), Vec<String>> {
    let mut errors = Vec::new();

    // 1. Restore GPU governor
    if let Err(e) = write_verified("/sys/kernel/gpu/gpu_governor", &snap.gpu_governor) {
        errors.push(e);
    }

    // 2. Restore GPU min clock
    let min_s = snap.gpu_min_clock.to_string();
    if let Err(e) = write_verified("/sys/kernel/gpu/gpu_min_clock", &min_s) {
        errors.push(e);
    }

    // 3. Restore CPU little (cpu0) min freq
    let c0_s = snap.cpu0_min.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq",
        &c0_s,
    ) {
        errors.push(e);
    }

    // 4. Restore CPU big (cpu4) min freq
    let c4_s = snap.cpu4_min.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq",
        &c4_s,
    ) {
        errors.push(e);
    }

    // 5. Restore CPU max freqs (raise AFTER lowering min so min never
    //    exceeds the ceiling; ESPORTS caps these below stock).
    let c0max = snap.cpu0_max.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq",
        &c0max,
    ) {
        errors.push(e);
    }
    let c4max = snap.cpu4_max.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq",
        &c4max,
    ) {
        errors.push(e);
    }

    // 6. Restore CPU governors (ESPORTS pins them to "performance").
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
        &snap.cpu0_gov,
    ) {
        errors.push(e);
    }
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu4/cpufreq/scaling_governor",
        &snap.cpu4_gov,
    ) {
        errors.push(e);
    }

    // 7. Restore network CC if modified
    if !snap.net_cc.is_empty() {
        let _ = write_verified("/proc/sys/net/ipv4/tcp_congestion_control", &snap.net_cc);
    }

    // 6. Restore VM anti-stutter (swappiness & vfs_cache_pressure)
    if !snap.vm_swappiness.is_empty() {
        let _ = write_verified("/proc/sys/vm/swappiness", &snap.vm_swappiness);
    }
    if !snap.vm_vfs_cache_pressure.is_empty() {
        let _ = write_verified("/proc/sys/vm/vfs_cache_pressure", &snap.vm_vfs_cache_pressure);
    }

    // 7. Restore Gaming DND & Brightness Mode
    if !snap.heads_up_enabled.is_empty() {
        let _ = crate::sysfs::run_cmd("settings", &["put", "global", "heads_up_notifications_enabled", &snap.heads_up_enabled]);
    }
    if !snap.screen_brightness_mode.is_empty() {
        let _ = crate::sysfs::run_cmd("settings", &["put", "system", "screen_brightness_mode", &snap.screen_brightness_mode]);
    }

    // 8. Restore battery charging
    let _ = std::fs::write("/sys/class/power_supply/battery/charging_enabled", "1");

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

/// Fallback hardcoded stock values for A50 (Exynos 9610) if snapshot is missing.
pub fn restore_hardcoded_stock() -> Result<(), Vec<String>> {
    let hardcoded = StockSnapshot {
        gpu_governor: "Interactive".to_string(),
        gpu_min_clock: 260000,
        cpu0_gov: "energy_adaptive".to_string(),
        cpu0_min: 702000,
        cpu0_max: 1742000,
        cpu4_gov: "energy_adaptive".to_string(),
        cpu4_min: 1040000,
        cpu4_max: 2314000,
        net_cc: "westwood".to_string(),
        net_fastopen: "1543".to_string(),
        net_tw_reuse: "0".to_string(),
        vm_swappiness: "130".to_string(),
        vm_vfs_cache_pressure: "100".to_string(),
        heads_up_enabled: "1".to_string(),
        screen_brightness_mode: "0".to_string(),
        timestamp: 0,
    };
    restore_from_snapshot(&hardcoded)
}

/// Primary restore entry point. Tries loaded snapshot first, falls back to hardcoded.
pub fn restore_all() -> Result<String, String> {
    let result = if let Some(snap) = StockSnapshot::load() {
        restore_from_snapshot(&snap)
    } else {
        restore_hardcoded_stock()
    };

    match result {
        Ok(_) => {
            StockSnapshot::clear();
            Ok("all knobs restored to stock".to_string())
        }
        Err(errs) => Err(format!("rollback errors: {}", errs.join("; "))),
    }
}
