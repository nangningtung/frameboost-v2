//! Bridge between the daemon and the in-process HUD (Zygisk module).
//!
//! The HUD .so (see inproc-hud/) is injected into game processes and reads
//! `/data/adb/secreto_space/hud_stats.json` a few times per second, so the
//! daemon writes a tiny stats blob here (throttled to 2 s). Toggling draws
//! via the enable flag file; targets come from inproc_targets.list.

use std::fs;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::sysfs::log_line;

const STATS_PATH: &str = "/data/adb/secreto_space/hud_stats.json";
const ENABLE_PATH: &str = "/data/adb/secreto_space/inproc_enable";
const TARGETS_PATH: &str = "/data/adb/secreto_space/inproc_targets.list";

static LAST_WRITE: Mutex<Option<Instant>> = Mutex::new(None);

/// Enable flag ada? Dipakai daemon supaya blob stats tidak ditulis saat
/// eksperimen tidak aktif.
pub fn enabled() -> bool {
    fs::metadata(ENABLE_PATH).is_ok()
}

/// Write the stats blob the in-process HUD reads. Throttled to 2 s so the
/// daemon tick can call this unconditionally.
pub fn write_stats(game: &str, preset: &str, fps: Option<f64>) {
    let due = if let Ok(mut g) = LAST_WRITE.lock() {
        match *g {
            Some(t) if t.elapsed() < Duration::from_secs(2) => false,
            _ => {
                *g = Some(Instant::now());
                true
            }
        }
    } else {
        false
    };
    if !due {
        return;
    }
    let fps_v = fps.unwrap_or(0.0);
    let ms_v = if fps_v > 0.1 { 1000.0 / fps_v } else { 0.0 };
    let json = format!(
        "{{\"game\":\"{}\",\"preset\":\"{}\",\"fps\":{:.1},\"frame_ms\":{:.1}}}",
        game.replace('"', ""),
        preset.replace('"', ""),
        fps_v,
        ms_v
    );
    let _ = fs::write(STATS_PATH, json);
}

/// Enable/disable HUD drawing (create/remove the flag file).
pub fn set_enabled(on: bool) -> String {
    let res = if on {
        fs::write(ENABLE_PATH, "")
    } else {
        fs::remove_file(ENABLE_PATH)
    };
    match res {
        Ok(_) => {
            log_line(&format!(
                "hud_inproc: {}",
                if on { "enabled" } else { "disabled" }
            ));
            "{\"status\":\"ok\"}".to_string()
        }
        Err(_) => "{\"status\":\"error\",\"message\":\"fs error\"}".to_string(),
    }
}

/// Status for IPC/UI: enabled flag + current target list.
pub fn status() -> String {
    let enabled = fs::metadata(ENABLE_PATH).is_ok();
    let targets = fs::read_to_string(TARGETS_PATH)
        .unwrap_or_default()
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .collect::<Vec<_>>();
    format!(
        "{{\"status\":\"ok\",\"enabled\":{},\"targets\":{}}}",
        enabled,
        serde_json::to_string(&targets).unwrap_or_else(|_| "[]".to_string())
    )
}

/// Toggle helper for one-tap UI buttons.
pub fn toggle() -> String {
    let on = fs::metadata(ENABLE_PATH).is_err(); // currently off -> turn on
    set_enabled(on)
}
