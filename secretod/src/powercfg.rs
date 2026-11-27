//! Powercfg contract (Scene/uperf-style external UI integration).
//!
//! Uperf and fas-rs both ship a `powercfg.json` + entry script so ecosystem
//! UIs (Scene) can switch performance modes without knowing the engine. This
//! module implements the same contract: the entry script writes a request
//! file, the daemon tick consumes it and maps the mode to a global preset
//! override. Per-game custom configs still win over the global override.

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::profile::GLOBAL_PRESET_PATH;
use crate::sysfs::log_line;

const REQUEST_PATH: &str = "/data/adb/secreto_space/powercfg.request";
pub const STATE_PATH: &str = "/data/adb/modules/secreto_space/cur_powermode.txt";
const ENTRY_PATH: &str = "/data/powercfg.sh";

/// Cached screen state for the standby hint; one `dumpsys display` spawn per
/// 5 s instead of per tick.
static SCREEN_CACHE: Mutex<Option<(bool, Instant)>> = Mutex::new(None);

pub fn setup() {
    // Entry script: any ecosystem UI calls `sh /data/powercfg.sh <mode>`.
    let script = "#!/system/bin/sh\n\
                  # Secreto Space powercfg entry (Scene/uperf contract).\n\
                  MODE=\"$1\"\n\
                  [ -z \"$MODE\" ] && MODE=status\n\
                  mkdir -p /data/adb/secreto_space 2>/dev/null\n\
                  printf '%s\\n' \"$MODE\" > /data/adb/secreto_space/powercfg.request\n";
    if fs::write(ENTRY_PATH, script).is_ok() {
        let _ = fs::set_permissions(ENTRY_PATH, fs::Permissions::from_mode(0o755));
        log_line("powercfg: entry script installed at /data/powercfg.sh");
    }
    update_state("auto");
}

/// Map a powercfg mode to a global preset name. `None` = unknown mode;
/// `Some(None)` = auto (clear the override); `Some(Some(name))` = preset.
fn mode_to_preset(mode: &str) -> Option<Option<&'static str>> {
    match mode.trim().to_ascii_lowercase().as_str() {
        "auto" | "default" => Some(None),
        "balance" | "balanced" => Some(Some("BALANCED")),
        "powersave" | "eco" => Some(Some("ECO")),
        "performance" => Some(Some("PERFORMANCE")),
        "fast" | "esports" => Some(Some("ESPORTS")),
        _ => None,
    }
}

/// External setter used by the IPC handler (`set_global_preset <mode>`).
pub fn set_global(mode: &str) -> String {
    match mode_to_preset(mode) {
        Some(None) => {
            let _ = fs::remove_file(GLOBAL_PRESET_PATH);
            update_state("auto");
            log_line("powercfg: global preset -> auto (per-game defaults)");
            format!(
                "{{\"status\":\"ok\",\"mode\":\"auto\"}}"
            )
        }
        Some(Some(name)) => {
            let _ = fs::write(GLOBAL_PRESET_PATH, name);
            update_state(&name.to_lowercase());
            log_line(&format!("powercfg: global preset -> {}", name));
            format!("{{\"status\":\"ok\",\"mode\":\"{}\",\"preset\":\"{}\"}}", mode.trim(), name)
        }
        None => format!(
            "{{\"status\":\"error\",\"message\":\"unknown mode: {}\"}}",
            mode.trim()
        ),
    }
}

/// Called once per daemon tick: consumes a pending request from the entry
/// script. Returns the resulting preset name (or "auto") when a request was
/// consumed.
pub fn consume() -> Option<String> {
    let req = fs::read_to_string(REQUEST_PATH).ok()?;
    let _ = fs::remove_file(REQUEST_PATH);
    let mode = req.trim().to_string();
    if mode == "status" || mode.is_empty() {
        return None;
    }
    match mode_to_preset(&mode) {
        Some(None) => {
            let _ = fs::remove_file(GLOBAL_PRESET_PATH);
            update_state("auto");
            log_line("powercfg: global preset -> auto (per-game defaults)");
            Some("auto".to_string())
        }
        Some(Some(name)) => {
            let _ = fs::write(GLOBAL_PRESET_PATH, name);
            update_state(&mode.to_lowercase());
            log_line(&format!("powercfg: global preset -> {}", name));
            Some(name.to_string())
        }
        None => {
            log_line(&format!("powercfg: unknown mode requested: {}", mode));
            None
        }
    }
}

pub fn update_state(mode: &str) {
    let _ = fs::write(STATE_PATH, format!("{}\n", mode));
}

/// Screen state, cached 5 s. Used by the daemon standby hint.
pub fn screen_on() -> bool {
    if let Ok(guard) = SCREEN_CACHE.lock() {
        if let Some((on, at)) = guard.as_ref() {
            if at.elapsed() < Duration::from_secs(5) {
                return *on;
            }
        }
    }
    let out = crate::sysfs::run_cmd("dumpsys", &["display"]);
    let on = out.contains("mScreenState=ON");
    if let Ok(mut guard) = SCREEN_CACHE.lock() {
        *guard = Some((on, Instant::now()));
    }
    on
}
