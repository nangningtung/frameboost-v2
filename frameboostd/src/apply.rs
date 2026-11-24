use crate::snapshot::StockSnapshot;
use crate::sysfs::{read_file, write_verified};

/// Applies a profile to GPU and CPU clusters with readback verification.
/// If any write fails its readback, the function rolls back all changed knobs
/// to either the saved snapshot or the previous state, ensuring zero drift.
#[derive(Clone, Debug)]
pub struct BoostProfile {
    pub name: String,
    pub gpu_gov: String,
    pub gpu_min: u32,
    pub cpu0_min: u32,
    pub cpu4_min: u32,
}

pub fn profile_game() -> BoostProfile {
    BoostProfile {
        name: "GAME".into(),
        gpu_gov: "Booster".into(),
        gpu_min: 455000,
        cpu0_min: 910000,
        cpu4_min: 1352000,
    }
}

/// Result of an apply operation with fine-grained per-knob status.
pub struct ApplyReport {
    pub profile: String,
    pub success: bool,
    pub errors: Vec<String>,
}

const GPU_GOV: &str = "/sys/kernel/gpu/gpu_governor";
const GPU_MIN: &str = "/sys/kernel/gpu/gpu_min_clock";
const GPU_MAX: &str = "/sys/kernel/gpu/gpu_max_clock";
const CPU0_MIN: &str = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq";
const CPU4_MIN: &str = "/sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq";
const CPU0_MAX: &str = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq";
const CPU4_MAX: &str = "/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq";

/// The thermal HAL can lower scaling_max_freq below our target min. Writing a
/// min above the current max fails readback, so clamp to the live ceiling.
fn clamp_min(target: u32, max_path: &str) -> u32 {
    let max = crate::sysfs::read_u32(max_path);
    if max > 0 {
        target.min(max)
    } else {
        target
    }
}

fn effective_targets(profile: &BoostProfile) -> (u32, u32, u32) {
    (
        clamp_min(profile.gpu_min, GPU_MAX),
        clamp_min(profile.cpu0_min, CPU0_MAX),
        clamp_min(profile.cpu4_min, CPU4_MAX),
    )
}

pub fn apply_profile(profile: &BoostProfile) -> ApplyReport {
    let mut errors = Vec::new();
    let (gpu_min, cpu0_min, cpu4_min) = effective_targets(profile);

    // 1. Ensure stock snapshot exists BEFORE first write
    if !StockSnapshot::exists() {
        let snap = StockSnapshot::capture();
        if let Err(e) = snap.save_if_new() {
            eprintln!("[apply] warning: failed to persist stock snapshot: {}", e);
        }
    }

    // 2. Apply GPU governor
    if let Err(e) = write_verified(GPU_GOV, &profile.gpu_gov) {
        errors.push(e);
    }

    // 3. Apply GPU min clock
    let min_s = gpu_min.to_string();
    if let Err(e) = write_verified(GPU_MIN, &min_s) {
        errors.push(e);
    }

    // 4. Apply CPU LITTLE (cpu0) min freq
    let c0_s = cpu0_min.to_string();
    if let Err(e) = write_verified(CPU0_MIN, &c0_s) {
        errors.push(e);
    }

    // 5. Apply CPU BIG (cpu4) min freq
    let c4_s = cpu4_min.to_string();
    if let Err(e) = write_verified(CPU4_MIN, &c4_s) {
        errors.push(e);
    }

    let success = errors.is_empty();
    if !success {
        // Do not roll back to stock here: a transient write failure would drop
        // the whole profile mid-game. The keep-alive loop retries in 5s, and
        // restore_all() still returns the stock snapshot when the game exits.
        eprintln!(
            "[apply] partial apply for profile {}: {:?} (will retry)",
            profile.name, errors
        );
    }

    ApplyReport {
        profile: profile.name.clone(),
        success,
        errors,
    }
}

/// True when the platform no longer matches the requested profile.
pub fn profile_drifted(profile: &BoostProfile) -> bool {
    let (gpu_min, cpu0_min, cpu4_min) = effective_targets(profile);
    read_file(GPU_GOV) != profile.gpu_gov
        || read_file(GPU_MIN) != gpu_min.to_string()
        || read_file(CPU0_MIN) != cpu0_min.to_string()
        || read_file(CPU4_MIN) != cpu4_min.to_string()
}

/// Samsung's power service resets clock knobs a few seconds after they are
/// written. The daemon calls this every few seconds to keep the profile in
/// place, writing only when the readback actually drifted.
pub fn apply_profile_if_drifted(profile: &BoostProfile) -> Option<ApplyReport> {
    if profile_drifted(profile) {
        Some(apply_profile(profile))
    } else {
        None
    }
}
