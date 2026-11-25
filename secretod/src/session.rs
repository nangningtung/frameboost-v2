//! Game session engine.
//!
//! A session is the unit the console talks about: it starts when a game comes
//! to the foreground, sleeps the apps the user did not whitelist, and ends with
//! a report plus a guaranteed restore. The journal on disk is written before
//! any app is touched, so a crash or a reboot can always undo the sleep.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use crate::apps;
use crate::fps::FpsReport;
use crate::sysfs::{log_line, read_u32};

const DIR: &str = "/data/adb/secreto_space";
const STATE_PATH: &str = "/data/adb/secreto_space/session.json";
const LIST_PATH: &str = "/data/adb/secreto_space/sleeping.list";
const LAST_PATH: &str = "/data/adb/secreto_space/session_last.json";
const HISTORY_PATH: &str = "/data/adb/secreto_space/session_history.json";

/// Sessions kept per game, and the hard cap across every game.
const HISTORY_PER_GAME: usize = 10;
const HISTORY_MAX: usize = 120;

/// A report needs this much play time before its average FPS is trusted for
/// advice. A 30 second launcher stint says nothing about a preset.
const ADVICE_MIN_SECONDS: u64 = 300;

/// Serialises the sleep batch against wake/recovery. Without it, a session
/// that ends while the batch is still running could be woken and then slept
/// again by the late thread.
static APPLY_LOCK: Mutex<()> = Mutex::new(());

static ACTIVE: Mutex<Option<Active>> = Mutex::new(None);

struct Active {
    pkg: String,
    preset: String,
    started_at: u64,
    fps_sum: f64,
    fps_n: u64,
    /// Frame-time histogram in integer milliseconds, merged from every window
    /// of the session. A session-wide 1% low needs the whole distribution, not
    /// an average of per-window worst frames.
    hist: HashMap<u32, u32>,
    jank: u64,
    frames: u64,
    max_temp_mc: u32,
    battery_start: i32,
    battery_last: i32,
    slept: usize,
    throttle_events: u32,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct SessionInfo {
    pub active: bool,
    pub pkg: String,
    pub preset: String,
    pub started_at: u64,
    pub elapsed_s: u64,
    pub slept_apps: usize,
    pub avg_fps: Option<f64>,
    pub p1_low: Option<f64>,
    pub jank_pct: Option<f64>,
    pub max_temp_c: f64,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SessionReport {
    pub pkg: String,
    pub preset: String,
    pub started_at: u64,
    pub ended_at: u64,
    pub duration_s: u64,
    pub avg_fps: Option<f64>,
    pub p1_low: Option<f64>,
    pub jank_pct: Option<f64>,
    pub max_temp_c: f64,
    pub battery_start: i32,
    pub battery_end: i32,
    pub slept_apps: usize,
    pub reason: String,
    /// Thermal downgrades (WARN or CRIT) that happened during the session.
    #[serde(default)]
    pub throttle_events: u32,
}

#[derive(Serialize)]
struct StoredState<'a> {
    pkg: &'a str,
    preset: &'a str,
    started_at: u64,
    slept: usize,
}

fn epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn battery_pct() -> i32 {
    read_u32("/sys/class/power_supply/battery/capacity") as i32
}

fn round1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}

/// Session 1% low: the 99th percentile frame time of the whole session,
/// converted back to FPS. Averaging per-window worst frames instead turns a
/// single loading hitch into a fake 5 FPS session low.
fn p1_low_from(avg: Option<f64>, hist: &HashMap<u32, u32>) -> Option<f64> {
    let total: u64 = hist.values().map(|&c| c as u64).sum();
    if total == 0 {
        return None;
    }
    let threshold = (total as f64 * 0.99).ceil() as u64;
    let mut keys: Vec<u32> = hist.keys().copied().collect();
    keys.sort_unstable();
    let mut accum = 0u64;
    let mut p99_ms = 0u32;
    for ms in keys {
        accum += hist[&ms] as u64;
        if accum >= threshold {
            p99_ms = ms;
            break;
        }
    }
    if p99_ms == 0 {
        return None;
    }
    let v = 1000.0 / p99_ms as f64;
    Some(round1(avg.map_or(v, |a| v.min(a))))
}

fn write_state(a: &Active) {
    let _ = fs::create_dir_all(DIR);
    let body = StoredState {
        pkg: &a.pkg,
        preset: &a.preset,
        started_at: a.started_at,
        slept: a.slept,
    };
    if let Ok(json) = serde_json::to_string(&body) {
        let _ = fs::write(STATE_PATH, json);
    }
}

/// Read the journal and put every app back. Safe to call when there is no
/// journal: it simply does nothing.
fn wake_all() -> usize {
    let _guard = APPLY_LOCK.lock().unwrap();
    let raw = match fs::read_to_string(LIST_PATH) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    let mut n = 0;
    for line in raw.lines() {
        let mut parts = line.split_whitespace();
        let pkg = parts.next().unwrap_or("");
        let prior = parts.next().unwrap_or("default");
        if pkg.contains('.') {
            apps::wake(pkg, prior);
            n += 1;
        }
    }
    let _ = fs::remove_file(LIST_PATH);
    n
}

fn is_active_for(pkg: &str) -> bool {
    ACTIVE
        .lock()
        .unwrap()
        .as_ref()
        .map(|a| a.pkg == pkg)
        .unwrap_or(false)
}

pub fn start(pkg: &str, preset: &str) -> bool {
    if pkg.is_empty() {
        return false;
    }
    let already = {
        let g = ACTIVE.lock().unwrap();
        g.as_ref().map(|a| a.pkg == pkg).unwrap_or(false)
    };
    if already {
        return false;
    }
    finish("switch");

    let active = Active {
        pkg: pkg.to_string(),
        preset: preset.to_string(),
        started_at: epoch(),
        fps_sum: 0.0,
        fps_n: 0,
        hist: HashMap::new(),
        jank: 0,
        frames: 0,
        max_temp_mc: 0,
        battery_start: battery_pct(),
        battery_last: battery_pct(),
        slept: 0,
        throttle_events: 0,
    };
    write_state(&active);
    log_line(&format!("session: start pkg={} preset={}", pkg, preset));
    *ACTIVE.lock().unwrap() = Some(active);
    // Applies only when this game has a non-native scale configured, and is a
    // no-op otherwise. Doing it here covers both the daemon and a manual start.
    crate::render::apply_for(pkg);
    true
}

/// Journal the plan, then sleep the apps on a worker thread. Planning costs
/// one app-op read per package and applying costs a force-stop per package, so
/// none of it may run on the tick loop: that would freeze thermal handling for
/// seconds right when the game needs it most.
pub fn sleep_background(game: &str) {
    if !apps::enabled() {
        return;
    }
    let game = game.to_string();
    std::thread::spawn(move || {
        let candidates = apps::candidates(&game);
        if candidates.is_empty() {
            return;
        }

        let planned: Vec<(String, String)> = candidates
            .into_iter()
            .map(|p| {
                let prior = apps::prior_mode(&p);
                (p, prior)
            })
            .collect();

        // Journal before the first app-op write. Recovery restores every
        // planned package, including ones the loop never reached.
        let mut body = String::new();
        for (pkg, prior) in &planned {
            body.push_str(pkg);
            body.push(' ');
            body.push_str(prior);
            body.push('\n');
        }
        if let Err(e) = fs::write(LIST_PATH, body) {
            log_line(&format!("session: cannot write sleep journal: {}", e));
            return;
        }

        let _guard = APPLY_LOCK.lock().unwrap();
        let started = Instant::now();
        let mut done = 0;
        for (pkg, _) in &planned {
            if !is_active_for(&game) {
                break;
            }
            apps::sleep(pkg);
            done += 1;
        }

        if done > 0 {
            if let Some(a) = ACTIVE.lock().unwrap().as_mut() {
                if a.pkg == game {
                    a.slept = done;
                    write_state(a);
                }
            }
        } else if !is_active_for(&game) {
            // The session ended while planning, so nothing was applied.
            let _ = fs::remove_file(LIST_PATH);
        }
        apps::log_sleep_summary(done, planned.len(), started.elapsed().as_millis());
    });
}

/// One FPS sample. Only called when the snapshot loop actually measured new
/// frames, so jank percentage is computed from real frame counts.
pub fn sample(rep: &FpsReport, temp_mc: u32, battery: i32) {
    let mut guard = ACTIVE.lock().unwrap();
    let Some(a) = guard.as_mut() else {
        return;
    };
    if rep.window_frames > 0 {
        a.frames += rep.window_frames as u64;
        a.jank += rep.jank_count as u64;
        for (ms, cnt) in &rep.hist {
            *a.hist.entry(*ms).or_insert(0) += *cnt;
        }
    }
    if let Some(fps) = rep.fps {
        if fps > 0.0 {
            a.fps_sum += fps;
            a.fps_n += 1;
        }
    }
    a.max_temp_mc = a.max_temp_mc.max(temp_mc);
    a.battery_last = battery;
}

/// Called by the daemon whenever it drops a preset because of heat. Counted
/// once per session so the report can say the game was thermally limited.
pub fn note_throttle() {
    if let Some(a) = ACTIVE.lock().unwrap().as_mut() {
        a.throttle_events += 1;
    }
}

/// Package of the running session, if any. Used so "End session" pauses the
/// game that actually had the session rather than whatever is foreground now
/// (the console itself, for example).
pub fn active_pkg() -> Option<String> {
    ACTIVE.lock().unwrap().as_ref().map(|a| a.pkg.clone())
}

pub fn info() -> SessionInfo {
    let guard = ACTIVE.lock().unwrap();
    let Some(a) = guard.as_ref() else {
        return SessionInfo::default();
    };
    let avg = if a.fps_n > 0 {
        Some(round1(a.fps_sum / a.fps_n as f64))
    } else {
        None
    };
    SessionInfo {
        active: true,
        pkg: a.pkg.clone(),
        preset: a.preset.clone(),
        started_at: a.started_at,
        elapsed_s: epoch().saturating_sub(a.started_at),
        slept_apps: a.slept,
        avg_fps: avg,
        p1_low: p1_low_from(avg, &a.hist),
        jank_pct: if a.frames > 0 {
            Some(round1((a.jank as f64 * 100.0 / a.frames as f64).min(100.0)))
        } else {
            None
        },
        max_temp_c: round1(a.max_temp_mc as f64 / 1000.0),
    }
}

pub fn sleeping_apps() -> Vec<String> {
    fs::read_to_string(LIST_PATH)
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.split_whitespace().next())
        .filter(|p| p.contains('.'))
        .map(|p| p.to_string())
        .collect()
}

/// Wake every journaled app but keep the session running. Used by the console
/// when the user wants their apps back without ending the game session.
pub fn wake_now() -> usize {
    let n = wake_all();
    if let Some(a) = ACTIVE.lock().unwrap().as_mut() {
        a.slept = 0;
        write_state(a);
    }
    n
}

pub fn last_report() -> Option<SessionReport> {
    let raw = fs::read_to_string(LAST_PATH).ok()?;
    serde_json::from_str(&raw).ok()
}

/// Every report ever kept, newest first.
pub fn history_all() -> Vec<SessionReport> {
    let raw = match fs::read_to_string(HISTORY_PATH) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    serde_json::from_str(&raw).unwrap_or_default()
}

/// Newest first, at most `HISTORY_PER_GAME` reports for one package.
pub fn history(pkg: &str) -> Vec<SessionReport> {
    history_all()
        .into_iter()
        .filter(|r| r.pkg == pkg)
        .take(HISTORY_PER_GAME)
        .collect()
}

fn append_history(report: &SessionReport) {
    let mut all = history_all();
    all.insert(0, report.clone());

    let mut per_game: HashMap<String, usize> = HashMap::new();
    all.retain(|r| {
        let seen = per_game.entry(r.pkg.clone()).or_insert(0);
        *seen += 1;
        *seen <= HISTORY_PER_GAME
    });
    all.truncate(HISTORY_MAX);

    if let Ok(json) = serde_json::to_string_pretty(&all) {
        let _ = fs::write(HISTORY_PATH, json);
    }
}

fn lower_preset(preset: &str) -> &'static str {
    match preset.to_ascii_uppercase().as_str() {
        "ESPORTS" => "Performance",
        "PERFORMANCE" => "Balanced",
        _ => "Battery",
    }
}

/// Honest advice, only when the data supports it: at least three sessions of
/// five minutes or more, and the newest average at least 8% below the best run
/// of the same game. Anything looser is just noise.
pub fn suggestion(pkg: &str) -> Option<String> {
    let reports: Vec<SessionReport> = history(pkg)
        .into_iter()
        .filter(|r| r.duration_s >= ADVICE_MIN_SECONDS)
        .collect();
    if reports.len() < 3 {
        return None;
    }
    let latest = reports.first()?.avg_fps?;
    let best = reports
        .iter()
        .filter_map(|r| r.avg_fps)
        .fold(0.0_f64, f64::max);
    if best <= 0.0 || latest >= best * 0.92 {
        return None;
    }
    let drop = ((best - latest) / best * 100.0).round() as u32;
    Some(format!(
        "Average FPS is {}% below the best run for this game. Try {} for the next session.",
        drop,
        lower_preset(&reports[0].preset)
    ))
}

pub fn finish(reason: &str) -> Option<SessionReport> {
    let active = ACTIVE.lock().unwrap().take()?;
    let slept = wake_all();
    crate::render::restore();
    let ended_at = epoch();
    let avg = if active.fps_n > 0 {
        Some(round1(active.fps_sum / active.fps_n as f64))
    } else {
        None
    };

    let report = SessionReport {
        pkg: active.pkg,
        preset: active.preset,
        started_at: active.started_at,
        ended_at,
        duration_s: ended_at.saturating_sub(active.started_at),
        avg_fps: avg,
        p1_low: p1_low_from(avg, &active.hist),
        jank_pct: if active.frames > 0 {
            Some(round1((active.jank as f64 * 100.0 / active.frames as f64).min(100.0)))
        } else {
            None
        },
        max_temp_c: round1(active.max_temp_mc as f64 / 1000.0),
        battery_start: active.battery_start,
        battery_end: active.battery_last,
        slept_apps: slept,
        reason: reason.to_string(),
        throttle_events: active.throttle_events,
    };

    if let Ok(json) = serde_json::to_string_pretty(&report) {
        let _ = fs::write(LAST_PATH, json);
    }
    append_history(&report);
    let _ = fs::remove_file(STATE_PATH);
    log_line(&format!(
        "session: end pkg={} duration={}s slept={} reason={}",
        report.pkg, report.duration_s, report.slept_apps, reason
    ));
    Some(report)
}

/// Boot / crash recovery. Only touches the platform when a journal exists, so
/// a normal daemon restart never fights the user's own settings.
pub fn recover() -> Option<String> {
    let had_journal = fs::metadata(STATE_PATH).is_ok() || fs::metadata(LIST_PATH).is_ok();
    if !had_journal {
        return None;
    }
    let woke = wake_all();
    crate::render::restore();
    *ACTIVE.lock().unwrap() = None;
    let _ = fs::remove_file(STATE_PATH);
    let restored = crate::rollback::restore_all().unwrap_or_else(|e| format!("restore failed: {}", e));
    let msg = format!("session: recovered {} apps, {}", woke, restored);
    log_line(&msg);
    Some(msg)
}

/// Used by the console to show how many apps would be slept, without doing it.
pub fn preview_candidates(game: &str) -> Vec<String> {
    apps::candidates(game)
}
