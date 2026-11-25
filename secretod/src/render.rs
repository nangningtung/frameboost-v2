//! Per-game render scale, the biggest FPS lever on a Mali G72 MP3.
//!
//! The panel stays 1080x2340, but a game can be told to render 720p or 900p
//! for the length of its session. The override is journaled before it is
//! applied and reset from the session end, the daemon start, `service.sh` and
//! `uninstall.sh`, so a crash can never leave the phone in a scaled mode.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;

use crate::sysfs::{log_line, run_cmd};

const MAP_PATH: &str = "/data/adb/secreto_space/render_scale.json";
const JOURNAL_PATH: &str = "/data/adb/secreto_space/wm_size.journal";

pub const MODES: [&str; 3] = ["native", "900p", "720p"];

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct RenderMap {
    #[serde(default)]
    pub scales: BTreeMap<String, String>,
}

#[derive(Serialize)]
pub struct RenderStatus {
    pub supported: bool,
    pub native: String,
    pub override_size: String,
    pub applied: bool,
    pub modes: Vec<&'static str>,
    pub scales: BTreeMap<String, String>,
}

fn load_map() -> RenderMap {
    fs::read_to_string(MAP_PATH)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn save_map(map: &RenderMap) -> Result<(), String> {
    if let Some(parent) = std::path::Path::new(MAP_PATH).parent() {
        let _ = fs::create_dir_all(parent);
    }
    let json = serde_json::to_string_pretty(map).map_err(|e| format!("serialize: {}", e))?;
    fs::write(MAP_PATH, json).map_err(|e| format!("write {}: {}", MAP_PATH, e))
}

fn parse_size(line: &str) -> Option<(u32, u32)> {
    let (w, h) = line.trim().split_once('x')?;
    Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
}

/// Physical panel size, independent of any override that is active right now.
pub fn native_size() -> Option<(u32, u32)> {
    let out = run_cmd("wm", &["size"]);
    for line in out.lines() {
        if let Some(rest) = line.trim().strip_prefix("Physical size: ") {
            return parse_size(rest);
        }
    }
    None
}

fn override_size() -> Option<(u32, u32)> {
    let out = run_cmd("wm", &["size"]);
    for line in out.lines() {
        if let Some(rest) = line.trim().strip_prefix("Override size: ") {
            return parse_size(rest);
        }
    }
    None
}

pub fn supported() -> bool {
    native_size().is_some()
}

/// Target size for a mode on a given panel, keeping the aspect ratio. Returns
/// None when the mode is unknown or would not actually reduce the pixel count.
/// Pure so the mapping can be tested without a device.
pub fn scaled_size((w, h): (u32, u32), mode: &str) -> Option<(u32, u32)> {
    let short = match mode {
        "720p" => 720,
        "900p" => 900,
        _ => return None,
    };
    if short >= w {
        return None;
    }
    let long = (h as f64 * short as f64 / w as f64).round() as u32;
    Some((short, long))
}

pub fn target_size(mode: &str) -> Option<(u32, u32)> {
    let native = native_size()?;
    scaled_size(native, mode)
}

/// Scale configured for this package, if any. `native` and an empty value both
/// mean "no override".
pub fn mode_for(pkg: &str) -> Option<String> {
    load_map()
        .scales
        .get(pkg)
        .map(|m| m.to_ascii_lowercase())
        .filter(|m| MODES.contains(&m.as_str()) && m != "native")
}

/// Apply the configured scale for a package. Called once when its session
/// starts, never on the tick loop.
pub fn apply_for(pkg: &str) {
    let Some(mode) = mode_for(pkg) else {
        return;
    };
    let Some((tw, th)) = target_size(&mode) else {
        return;
    };
    let Some((nw, nh)) = native_size() else {
        return;
    };
    if override_size() == Some((tw, th)) {
        return;
    }

    // Journal first: recovery only has to look at the file.
    let body = format!("native={}x{}\napplied={}x{}\npkg={}\n", nw, nh, tw, th, pkg);
    if let Err(e) = fs::write(JOURNAL_PATH, body) {
        log_line(&format!("render: cannot write journal: {}", e));
        return;
    }

    let size = format!("{}x{}", tw, th);
    let out = run_cmd("wm", &["size", &size]);
    if override_size() == Some((tw, th)) {
        log_line(&format!("render: {} at {} ({})", pkg, size, mode));
    } else {
        log_line(&format!("render: wm size {} failed: {}", size, out));
        let _ = fs::remove_file(JOURNAL_PATH);
    }
}

/// Reset the display to its physical size. Idempotent and cheap.
pub fn restore() {
    if fs::metadata(JOURNAL_PATH).is_err() {
        return;
    }
    let _ = run_cmd("wm", &["size", "reset"]);
    let _ = fs::remove_file(JOURNAL_PATH);
    log_line("render: display reset to native size");
}

/// Boot / crash recovery. Returns a log line only when something was undone.
pub fn recover() -> Option<String> {
    if fs::metadata(JOURNAL_PATH).is_err() {
        return None;
    }
    restore();
    Some("render: restored native display size after reboot".to_string())
}

pub fn status() -> RenderStatus {
    let (native, override_size) = match (native_size(), override_size()) {
        (Some((w, h)), Some((ow, oh))) => (format!("{}x{}", w, h), format!("{}x{}", ow, oh)),
        (Some((w, h)), None) => (format!("{}x{}", w, h), String::new()),
        _ => (String::new(), String::new()),
    };
    RenderStatus {
        supported: !native.is_empty(),
        native,
        override_size,
        applied: fs::metadata(JOURNAL_PATH).is_ok(),
        modes: MODES.to_vec(),
        scales: load_map().scales,
    }
}

/// Store the mode for one game. Applies or clears it immediately when that
/// game is the one running right now.
pub fn set(pkg: &str, mode: &str) -> Result<(), String> {
    if pkg.is_empty() || !pkg.contains('.') {
        return Err("invalid package".to_string());
    }
    let mode = mode.trim().to_ascii_lowercase();
    if !MODES.contains(&mode.as_str()) {
        return Err(format!("unknown mode: {}", mode));
    }

    let mut map = load_map();
    if mode == "native" {
        map.scales.remove(pkg);
    } else {
        if target_size(&mode).is_none() {
            return Err(format!("{} is not smaller than this panel", mode));
        }
        map.scales.insert(pkg.to_string(), mode.clone());
    }
    save_map(&map)?;

    if crate::session::active_pkg().as_deref() == Some(pkg) {
        if mode == "native" {
            restore();
        } else {
            apply_for(pkg);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a50_scales_keep_the_panel_aspect() {
        let panel = (1080, 2340);
        assert_eq!(scaled_size(panel, "720p"), Some((720, 1560)));
        assert_eq!(scaled_size(panel, "900p"), Some((900, 1950)));
    }

    #[test]
    fn native_and_unknown_modes_do_nothing() {
        let panel = (1080, 2340);
        assert_eq!(scaled_size(panel, "native"), None);
        assert_eq!(scaled_size(panel, "1080p"), None);
        assert_eq!(scaled_size(panel, ""), None);
    }

    #[test]
    fn a_scale_that_would_not_shrink_is_rejected() {
        assert_eq!(scaled_size((720, 1280), "720p"), None);
        assert_eq!(scaled_size((700, 1200), "900p"), None);
    }
}
