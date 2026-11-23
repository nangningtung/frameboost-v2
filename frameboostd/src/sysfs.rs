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
    read_file(path).parse().unwrap_or(0)
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
