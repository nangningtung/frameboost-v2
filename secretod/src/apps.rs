//! Background app sleep for game sessions.
//!
//! The Android "inactive" API (`am set-inactive`) is a no-op on this GSI, so
//! the real primitive here is the `RUN_IN_BACKGROUND` app-op plus
//! `am force-stop`. Both are reversible and both are journaled before use:
//! the previous app-op mode is written to `sleeping.list` first, so a crash,
//! a reboot or an uninstall can always put every app back.

use std::fs;

use crate::sysfs::{log_line, run_cmd};

const DIR: &str = "/data/adb/secreto_space";
const WHITELIST_PATH: &str = "/data/adb/secreto_space/sleep_whitelist.list";
const ENABLED_PATH: &str = "/data/adb/secreto_space/sleep_enabled";
const GAMES_LIST_PATH: &str = "/data/adb/secreto_space/games.list";
const CUSTOM_GAMES_PATH: &str = "/data/adb/secreto_space/custom_games.list";
const AUTO_GAMES_PATH: &str = "/data/adb/secreto_space/auto_games.list";

/// Never sleep these, whatever the user asks. They are needed for the phone
/// to stay usable and for the session to be able to end cleanly.
const ALWAYS_PROTECTED: &[&str] = &[
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.dialer",
    "com.android.incallui",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.packageinstaller",
    "com.android.permissioncontroller",
    "com.android.shell",
    "com.secreto.space",
];

fn ensure_dir() {
    let _ = fs::create_dir_all(DIR);
}

/// Sleep is on by default: a game space that leaves 25 apps awake is not a
/// game space. Users can turn it off from the console.
pub fn enabled() -> bool {
    match fs::read_to_string(ENABLED_PATH) {
        Ok(v) => v.trim() != "0",
        Err(_) => true,
    }
}

pub fn set_enabled(on: bool) -> Result<(), String> {
    ensure_dir();
    fs::write(ENABLED_PATH, if on { "1\n" } else { "0\n" })
        .map_err(|e| format!("write {}: {}", ENABLED_PATH, e))
}

pub fn whitelist() -> Vec<String> {
    let raw = fs::read_to_string(WHITELIST_PATH).unwrap_or_default();
    raw.lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#') && l.contains('.'))
        .map(|l| l.to_string())
        .collect()
}

pub fn set_whitelist(list: &[String]) -> Result<(), String> {
    ensure_dir();
    let mut body = String::from("# Apps that keep running during a game session\n");
    for pkg in list {
        let p = pkg.trim();
        if !p.is_empty() && p.contains('.') {
            body.push_str(p);
            body.push('\n');
        }
    }
    fs::write(WHITELIST_PATH, body).map_err(|e| format!("write {}: {}", WHITELIST_PATH, e))
}

fn installed_user_apps() -> Vec<String> {
    let out = run_cmd("pm", &["list", "packages", "-3"]);
    out.lines()
        .filter_map(|l| l.trim().strip_prefix("package:"))
        .map(|p| p.trim().to_string())
        .filter(|p| p.contains('.'))
        .collect()
}

fn package_of_component(raw: &str) -> Option<String> {
    let line = raw.lines().last()?.trim();
    if !line.contains('/') {
        return None;
    }
    let pkg = line.split('/').next()?.trim();
    if pkg.contains('.') {
        Some(pkg.to_string())
    } else {
        None
    }
}

fn default_launcher() -> Option<String> {
    package_of_component(&run_cmd(
        "cmd",
        &[
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
        ],
    ))
}

fn default_ime() -> Option<String> {
    package_of_component(&run_cmd(
        "settings",
        &["get", "secure", "default_input_method"],
    ))
}

fn is_protected(
    pkg: &str,
    active_game: &str,
    launcher: &Option<String>,
    ime: &Option<String>,
    whitelist: &[String],
    library: &[String],
) -> bool {
    if pkg == active_game {
        return true;
    }
    if ALWAYS_PROTECTED.contains(&pkg) {
        return true;
    }
    if launcher.as_deref() == Some(pkg) || ime.as_deref() == Some(pkg) {
        return true;
    }
    if whitelist.iter().any(|w| w == pkg) {
        return true;
    }
    library.iter().any(|g| g == pkg)
}

/// Every package Secreto knows as a game: the builtin list plus the persisted
/// library. Games are never force-stopped, so switching between two games in
/// the library is a warm start instead of a cold one.
fn library_games() -> Vec<String> {
    let mut out: Vec<String> = crate::detect::BUILTIN_GAMES
        .iter()
        .map(|(p, _)| p.to_string())
        .collect();
    for path in [GAMES_LIST_PATH, CUSTOM_GAMES_PATH, AUTO_GAMES_PATH] {
        for line in fs::read_to_string(path).unwrap_or_default().lines() {
            let p = line.trim();
            if p.contains('.') && !p.starts_with('#') {
                out.push(p.to_string());
            }
        }
    }
    out
}

/// Apps that are safe to sleep right now. Launcher, IME, whitelist and the
/// game library are read once per call: resolving them per package would add
/// a shell call per app.
pub fn candidates(active_game: &str) -> Vec<String> {
    let launcher = default_launcher();
    let ime = default_ime();
    let whitelist = whitelist();
    let library = library_games();
    let mut apps = installed_user_apps();
    apps.sort();
    apps.dedup();
    apps.into_iter()
        .filter(|p| !is_protected(p, active_game, &launcher, &ime, &whitelist, &library))
        .collect()
}

/// Current `RUN_IN_BACKGROUND` app-op mode, normalised for restore. A mode
/// reported under `Default mode:` was never set explicitly, so restoring it
/// means going back to `default`, not writing that mode as an explicit op.
pub fn prior_mode(pkg: &str) -> String {
    let out = run_cmd("cmd", &["appops", "get", pkg, "RUN_IN_BACKGROUND"]);
    let mut default_mode: Option<String> = None;
    for line in out.lines() {
        let l = line.trim();
        if let Some(v) = l.strip_prefix("RUN_IN_BACKGROUND:") {
            let mode = v.trim();
            if !mode.is_empty() {
                return mode.to_string();
            }
        }
        if let Some(v) = l.strip_prefix("Default mode:") {
            let mode = v.trim();
            if !mode.is_empty() {
                default_mode = Some(mode.to_string());
            }
        }
    }
    default_mode.map(|_| "default".to_string()).unwrap_or_else(|| "default".to_string())
}

/// Block background execution, then stop whatever is running. Read back the
/// app-op so a silent failure is logged instead of being reported as success.
pub fn sleep(pkg: &str) -> bool {
    run_cmd(
        "cmd",
        &["appops", "set", pkg, "RUN_IN_BACKGROUND", "ignore"],
    );
    let ok = prior_mode(pkg) == "ignore";
    if !ok {
        log_line(&format!("session: app-op did not stick for {}", pkg));
    }
    run_cmd("am", &["force-stop", pkg]);
    ok
}

/// Put the app-op back exactly where it was. Idempotent on purpose: recovery
/// runs this for every journaled package, including ones never reached.
pub fn wake(pkg: &str, prior: &str) {
    let mode = if prior.is_empty() { "default" } else { prior };
    run_cmd("cmd", &["appops", "set", pkg, "RUN_IN_BACKGROUND", mode]);
}

pub fn log_sleep_summary(done: usize, total: usize, elapsed_ms: u128) {
    log_line(&format!(
        "session: slept {}/{} background apps in {}ms",
        done, total, elapsed_ms
    ));
}
