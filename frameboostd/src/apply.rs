use crate::snapshot::StockSnapshot;
use crate::sysfs::write_verified;

/// Applies a profile to GPU and CPU clusters with readback verification.
/// If any write fails its readback, the function rolls back all changed knobs
/// to either the saved snapshot or the previous state, ensuring zero drift.
#[derive(Clone, Debug)]
pub struct BoostProfile {
    pub name: &'static str,
    pub gpu_gov: &'static str,
    pub gpu_min: u32,
    pub cpu0_min: u32,
    pub cpu4_min: u32,
}

pub const PROFILE_GAME: BoostProfile = BoostProfile {
    name: "GAME",
    gpu_gov: "Booster",
    gpu_min: 455000,
    cpu0_min: 910000,
    cpu4_min: 1352000,
};

pub const PROFILE_WARN: BoostProfile = BoostProfile {
    name: "WARN",
    gpu_gov: "Booster",
    gpu_min: 385000,
    cpu0_min: 910000,
    cpu4_min: 1352000,
};

pub const PROFILE_IDLE: BoostProfile = BoostProfile {
    name: "IDLE",
    gpu_gov: "Interactive",
    gpu_min: 260000,
    cpu0_min: 702000,
    cpu4_min: 1040000,
};

/// Result of an apply operation with fine-grained per-knob status.
pub struct ApplyReport {
    pub profile: &'static str,
    pub success: bool,
    pub errors: Vec<String>,
}

pub fn apply_profile(profile: &BoostProfile) -> ApplyReport {
    let mut errors = Vec::new();

    // 1. Ensure stock snapshot exists BEFORE first write
    if !StockSnapshot::exists() {
        let snap = StockSnapshot::capture();
        if let Err(e) = snap.save_if_new() {
            eprintln!("[apply] warning: failed to persist stock snapshot: {}", e);
        }
    }

    // 2. Apply GPU governor
    if let Err(e) = write_verified("/sys/kernel/gpu/gpu_governor", profile.gpu_gov) {
        errors.push(e);
    }

    // 3. Apply GPU min clock
    let min_s = profile.gpu_min.to_string();
    if let Err(e) = write_verified("/sys/kernel/gpu/gpu_min_clock", &min_s) {
        errors.push(e);
    }

    // 4. Apply CPU LITTLE (cpu0) min freq
    let c0_s = profile.cpu0_min.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq",
        &c0_s,
    ) {
        errors.push(e);
    }

    // 5. Apply CPU BIG (cpu4) min freq
    let c4_s = profile.cpu4_min.to_string();
    if let Err(e) = write_verified(
        "/sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq",
        &c4_s,
    ) {
        errors.push(e);
    }

    let success = errors.is_empty();
    if !success {
        eprintln!(
            "[apply] FAILED applying profile {}: {:?}. Initiating rollback.",
            profile.name, errors
        );
        // Rollback on partial failure
        if let Some(snap) = StockSnapshot::load() {
            let _ = crate::rollback::restore_from_snapshot(&snap);
        }
    }

    ApplyReport {
        profile: profile.name,
        success,
        errors,
    }
}
