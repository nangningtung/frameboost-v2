use serde::{Deserialize, Serialize};
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::sysfs::{read_file, read_u32};

const STOCK_PATH: &str = "/data/adb/a50_frameboost/stock_snapshot.json";

/// Captured stock values before any modification.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StockSnapshot {
    pub gpu_governor: String,
    pub gpu_min_clock: u32,
    pub cpu0_gov: String,
    pub cpu0_min: u32,
    pub cpu4_gov: String,
    pub cpu4_min: u32,
    pub net_cc: String,
    pub net_fastopen: String,
    pub net_tw_reuse: String,
    pub timestamp: u64,
}

impl StockSnapshot {
    /// Read current device values into a snapshot.
    pub fn capture() -> Self {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        Self {
            gpu_governor: read_file("/sys/kernel/gpu/gpu_governor"),
            gpu_min_clock: read_u32("/sys/kernel/gpu/gpu_min_clock"),
            cpu0_gov: read_file("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"),
            cpu0_min: read_u32("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"),
            cpu4_gov: read_file("/sys/devices/system/cpu/cpu4/cpufreq/scaling_governor"),
            cpu4_min: read_u32("/sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq"),
            net_cc: read_file("/proc/sys/net/ipv4/tcp_congestion_control"),
            net_fastopen: read_file("/proc/sys/net/ipv4/tcp_fastopen"),
            net_tw_reuse: read_file("/proc/sys/net/ipv4/tcp_tw_reuse"),
            timestamp: now,
        }
    }

    /// Save to persistent file. Overwrites only if none exists (first capture wins).
    pub fn save_if_new(&self) -> Result<(), String> {
        if fs::metadata(STOCK_PATH).is_ok() {
            // Already have a snapshot — don't overwrite.
            return Ok(());
        }
        // Ensure parent dir exists
        if let Some(parent) = std::path::Path::new(STOCK_PATH).parent() {
            let _ = fs::create_dir_all(parent);
        }
        let json = serde_json::to_string_pretty(self)
            .map_err(|e| format!("serialize: {}", e))?;
        fs::write(STOCK_PATH, &json).map_err(|e| format!("write {}: {}", STOCK_PATH, e))
    }

    /// Force-save (overwrite existing). Used by restore_all to clear stale snapshot.
    pub fn clear() {
        let _ = fs::remove_file(STOCK_PATH);
    }

    /// Load saved snapshot.
    pub fn load() -> Option<Self> {
        let data = fs::read_to_string(STOCK_PATH).ok()?;
        serde_json::from_str(&data).ok()
    }

    /// True if a saved snapshot exists (meaning we have modified device state).
    pub fn exists() -> bool {
        fs::metadata(STOCK_PATH).is_ok()
    }
}
