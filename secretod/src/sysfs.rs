use std::fs;
use std::process::Command;

pub fn read_file(path: &str) -> String {
    fs::read_to_string(path).unwrap_or_default().trim().to_string()
}

pub fn write_file(path: &str, val: &str) -> bool {
    fs::write(path, val).is_ok()
}

/// Write then readback-verify. Returns true only if written value matches.
pub fn write_verified(path: &str, val: &str) -> Result<(), String> {
    if !write_file(path, val) {
        return Err(format!("write failed: {}", path));
    }
    let readback = read_file(path);
    if readback != val {
        Err(format!(
            "readback mismatch: {} expected={} got={}",
            path, val, readback
        ))
    } else {
        Ok(())
    }
}

pub fn read_u32(path: &str) -> u32 {
    // Some Samsung nodes return values like "78%" or "764000\n"; parse the
    // leading digits instead of failing the whole parse.
    let raw = read_file(path);
    let digits: String = raw.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().unwrap_or(0)
}

/// Timestamped daemon log. The device timezone is WIB (UTC+7); using the
/// kernel clock keeps this dependency-free while still readable in logcat.
pub fn log_line(msg: &str) {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let local = secs + 7 * 3600;
    let h = (local / 3600) % 24;
    let m = (local / 60) % 60;
    let s = local % 60;
    eprintln!("[{:02}:{:02}:{:02}] {}", h, m, s, msg);
}

pub fn read_i32(path: &str) -> i32 {
    read_file(path).parse().unwrap_or(0)
}

pub fn run_cmd(cmd: &str, args: &[&str]) -> String {
    Command::new(cmd)
        .args(args)
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default()
}
