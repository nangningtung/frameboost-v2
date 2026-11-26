use serde_json::json;
use std::fs;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::sysfs::{log_line, run_cmd};

const PIC_DIR: &str = "/sdcard/Pictures/Secreto Space";
const MOV_DIR: &str = "/sdcard/Movies/Secreto Space";
const STATE_DIR: &str = "/data/adb/secreto_space";
const RECORD_PID: &str = "/data/adb/secreto_space/record.pid";
const RECORD_PATH: &str = "/data/adb/secreto_space/record.path";
const RECORD_LOG: &str = "/data/adb/secreto_space/record.log";

fn stamp() -> String {
    // Toybox date gives the local wall clock, which is what a gallery expects.
    let s = run_cmd("date", &["+%Y%m%d_%H%M%S"]);
    if !s.is_empty() {
        return s;
    }
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("{}", secs)
}

fn ensure_dirs() -> Result<(), String> {
    for dir in [PIC_DIR, MOV_DIR, STATE_DIR] {
        fs::create_dir_all(dir).map_err(|e| format!("mkdir {}: {}", dir, e))?;
    }
    Ok(())
}

fn valid_file(path: &str) -> bool {
    fs::metadata(path).map(|m| m.len() > 0).unwrap_or(false)
}

fn media_scan(path: &str) {
    // The media provider still accepts the legacy scan broadcast on this GSI.
    // The URI is percent-encoded because both capture folders contain a space.
    let uri = format!("file://{}", path.replace(' ', "%20"));
    let _ = run_cmd(
        "am",
        &[
            "broadcast",
            "-a",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d",
            &uri,
        ],
    );
}

fn reply(status: &str, message: &str, path: &str) -> String {
    json!({
        "status": status,
        "message": message,
        "path": path,
    })
    .to_string()
}

fn error(message: &str) -> String {
    json!({
        "status": "error",
        "message": message,
    })
    .to_string()
}

pub fn screenshot() -> String {
    if let Err(e) = ensure_dirs() {
        return error(&e);
    }
    let path = format!("{}/Secreto_{}.png", PIC_DIR, stamp());
    let out = Command::new("screencap").args(["-p", &path]).output();
    match out {
        Ok(o) if o.status.success() => {
            if valid_file(&path) {
                media_scan(&path);
                log_line(&format!("screenshot saved: {}", path));
                reply("ok", "Screenshot saved", &path)
            } else {
                error("screencap produced no file")
            }
        }
        Ok(o) => error(&format!(
            "screencap failed: {}",
            String::from_utf8_lossy(&o.stderr).trim()
        )),
        Err(e) => error(&format!("screencap not available: {}", e)),
    }
}

fn read_pid() -> Option<u32> {
    fs::read_to_string(RECORD_PID)
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok())
}

fn pid_alive(pid: u32) -> bool {
    if !Path::new(&format!("/proc/{}", pid)).exists() {
        return false;
    }
    let cmdline = fs::read(format!("/proc/{}/cmdline", pid)).unwrap_or_default();
    String::from_utf8_lossy(&cmdline).contains("screenrecord")
}

/// Returns (recording, path). A stale pid file is cleaned up here so the
/// snapshot never reports a recording that already ended.
pub fn status() -> (bool, String) {
    let active = read_pid().map(pid_alive).unwrap_or(false);
    if !active {
        let _ = fs::remove_file(RECORD_PID);
        let _ = fs::remove_file(RECORD_PATH);
        return (false, String::new());
    }
    let path = fs::read_to_string(RECORD_PATH)
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    (true, path)
}

pub fn start() -> String {
    if let (true, path) = status() {
        return error(&format!("Recording already active: {}", path));
    }
    if let Err(e) = ensure_dirs() {
        return error(&e);
    }
    let path = format!("{}/Secreto_{}.mp4", MOV_DIR, stamp());
    let log = match fs::File::create(RECORD_LOG) {
        Ok(f) => f,
        Err(e) => return error(&format!("record log: {}", e)),
    };
    let log_err = match log.try_clone() {
        Ok(f) => f,
        Err(e) => return error(&format!("record log: {}", e)),
    };
    let child = Command::new("screenrecord")
        .args([
            "--bit-rate",
            "12000000",
            "--time-limit",
            "300",
            &path,
        ])
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(log_err))
        .spawn();
    match child {
        Ok(child) => {
            let pid = child.id();
            if fs::write(RECORD_PID, pid.to_string()).is_err()
                || fs::write(RECORD_PATH, &path).is_err()
            {
                let _ = run_cmd("kill", &["-2", &pid.to_string()]);
                let _ = fs::remove_file(RECORD_PID);
                let _ = fs::remove_file(RECORD_PATH);
                return error("failed to persist recording state");
            }
            log_line(&format!("screenrecord started pid={} path={}", pid, path));
            reply("ok", "Recording started", &path)
        }
        Err(e) => error(&format!("screenrecord not available: {}", e)),
    }
}

pub fn stop(reason: &str) -> String {
    let Some(pid) = read_pid() else {
        let _ = fs::remove_file(RECORD_PATH);
        return reply("ok", "Not recording", "");
    };
    let path = fs::read_to_string(RECORD_PATH)
        .unwrap_or_default()
        .trim()
        .to_string();
    if !pid_alive(pid) {
        let _ = fs::remove_file(RECORD_PID);
        let _ = fs::remove_file(RECORD_PATH);
        if !path.is_empty() && valid_file(&path) {
            media_scan(&path);
            return reply("ok", "Recording saved", &path);
        }
        return reply("ok", "Not recording", "");
    }
    let _ = run_cmd("kill", &["-2", &pid.to_string()]);
    let deadline = Instant::now() + Duration::from_secs(6);
    while Instant::now() < deadline && pid_alive(pid) {
        std::thread::sleep(Duration::from_millis(100));
    }
    if pid_alive(pid) {
        let _ = run_cmd("kill", &["-9", &pid.to_string()]);
        std::thread::sleep(Duration::from_millis(200));
    }
    let _ = fs::remove_file(RECORD_PID);
    let _ = fs::remove_file(RECORD_PATH);
    if path.is_empty() || !valid_file(&path) {
        return error("recording file missing after stop");
    }
    media_scan(&path);
    log_line(&format!("screenrecord stopped reason={} path={}", reason, path));
    reply("ok", "Recording saved", &path)
}

pub fn stop_if_recording(reason: &str) {
    let (active, _) = status();
    if active {
        let _ = stop(reason);
    }
}
