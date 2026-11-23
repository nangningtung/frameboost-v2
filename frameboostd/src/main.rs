use serde::Serialize;
use std::fs;
use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, RwLock};
use std::thread;
use std::time::{Duration, Instant};

mod apply;
mod daemon;
mod detect;
mod fps;
mod net;
mod profile;
mod rollback;
mod snapshot;
mod sysfs;

use apply::{apply_profile, PROFILE_GAME, PROFILE_IDLE, PROFILE_WARN};
use daemon::{spawn_auto_daemon, DaemonState};
use detect::GameDetector;
use fps::measure_fps;
use net::{apply_bbr, measure_baseline, restore_stock_cc, run_ping, NetCapability};
use rollback::restore_all;
use snapshot::StockSnapshot;
use sysfs::{read_file, read_u32, run_cmd};

const SOCK_PATH: &str = "/data/local/tmp/frameboost.sock";
const SNAPSHOT_FILE: &str = "/data/local/tmp/frameboost-snapshot.json";

#[derive(Serialize, Clone, Default)]
pub struct GpuState {
    pub governor: String,
    pub min_clock: u32,
    pub max_clock: u32,
    pub current_clock: u32,
    pub busy_pct: f64,
}

#[derive(Serialize, Clone, Default)]
pub struct CpuCluster {
    pub gov: String,
    pub min_freq: u32,
    pub max_freq: u32,
    pub cur_freq: u32,
}

#[derive(Serialize, Clone, Default)]
pub struct CpuState {
    pub little: CpuCluster,
    pub big: CpuCluster,
}

#[derive(Serialize, Clone, Default)]
pub struct NetState {
    pub interface: String,
    pub congestion_control: String,
    pub qdisc: String,
    pub tcp_fastopen: String,
    pub tw_reuse: String,
    pub available_cc: String,
}

#[derive(Serialize, Clone, Default)]
pub struct Snapshot {
    pub daemon_state: String,
    pub game_pkg: String,
    pub is_game: bool,
    pub fps: Option<f64>,
    pub p1_low_fps: Option<f64>,
    pub temp_big_mc: i32,
    pub temp_little_mc: i32,
    pub temp_gpu_mc: i32,
    pub gpu: GpuState,
    pub cpu: CpuState,
    pub net: NetState,
    pub daemon_uptime_s: u64,
    pub stock_saved: bool,
}

fn read_gpu() -> GpuState {
    GpuState {
        governor: read_file("/sys/kernel/gpu/gpu_governor"),
        min_clock: read_u32("/sys/kernel/gpu/gpu_min_clock"),
        max_clock: read_u32("/sys/kernel/gpu/gpu_max_clock"),
        current_clock: read_u32("/sys/kernel/gpu/gpu_clock"),
        busy_pct: read_u32("/sys/kernel/gpu/gpu_busy") as f64,
    }
}

fn read_cpu_cluster(cpu: &str) -> CpuCluster {
    let base = format!("/sys/devices/system/cpu/cpu{}/cpufreq", cpu);
    CpuCluster {
        gov: read_file(&format!("{}/scaling_governor", base)),
        min_freq: read_u32(&format!("{}/scaling_min_freq", base)),
        max_freq: read_u32(&format!("{}/scaling_max_freq", base)),
        cur_freq: read_u32(&format!("{}/scaling_cur_freq", base)),
    }
}

fn read_cpu() -> CpuState {
    CpuState {
        little: read_cpu_cluster("0"),
        big: read_cpu_cluster("4"),
    }
}

fn read_net() -> NetState {
    let route = run_cmd("ip", &["route", "show", "default"]);
    let iface = route
        .split_whitespace()
        .find(|w| w.chars().next().map_or(false, |c| c.is_alphanumeric()))
        .unwrap_or("")
        .to_string();
    let cc = read_file("/proc/sys/net/ipv4/tcp_congestion_control");
    let avail = read_file("/proc/sys/net/ipv4/tcp_available_congestion_control");
    let qdisc = run_cmd("tc", &["qdisc", "show", "dev", &iface]);
    let qdisc_name = qdisc.split_whitespace().nth(1).unwrap_or("").to_string();
    let fastopen = read_file("/proc/sys/net/ipv4/tcp_fastopen");
    let tw_reuse = read_file("/proc/sys/net/ipv4/tcp_tw_reuse");

    NetState {
        interface: iface,
        congestion_control: cc,
        qdisc: qdisc_name,
        tcp_fastopen: fastopen,
        tw_reuse,
        available_cc: avail,
    }
}

fn take_snapshot(start: Instant, detector: &Arc<RwLock<GameDetector>>, state: u8) -> Snapshot {
    let pkg = GameDetector::get_foreground_pkg();
    let is_g = detector
        .read()
        .unwrap()
        .is_game_fast(&pkg)
        .unwrap_or(false);
    let st_str = DaemonState::from_u8(state).as_str().to_string();

    let fps_rep = if !pkg.is_empty() {
        measure_fps(&pkg)
    } else {
        Default::default()
    };

    Snapshot {
        daemon_state: st_str,
        game_pkg: pkg,
        is_game: is_g,
        fps: fps_rep.fps,
        p1_low_fps: fps_rep.p1_low_fps,
        temp_big_mc: read_u32("/sys/class/thermal/thermal_zone0/temp") as i32,
        temp_little_mc: read_u32("/sys/class/thermal/thermal_zone1/temp") as i32,
        temp_gpu_mc: read_u32("/sys/class/thermal/thermal_zone2/temp") as i32,
        gpu: read_gpu(),
        cpu: read_cpu(),
        net: read_net(),
        daemon_uptime_s: start.elapsed().as_secs(),
        stock_saved: StockSnapshot::exists(),
    }
}

fn handle_client(
    mut stream: UnixStream,
    start: Instant,
    running: Arc<AtomicBool>,
    state_sink: Arc<AtomicU8>,
    detector: Arc<RwLock<GameDetector>>,
) {
    let mut buf = [0u8; 1024];
    let n = stream.read(&mut buf).unwrap_or(0);
    let req = String::from_utf8_lossy(&buf[..n]);
    let req = req.trim();

    let current_st = state_sink.load(Ordering::Relaxed);

    let response = if req == "snapshot" {
        serde_json::to_string(&take_snapshot(start, &detector, current_st)).unwrap_or_default()
    } else if req == "list_games" {
        let list = detector.read().unwrap().list_all_games();
        serde_json::to_string(&list).unwrap_or_default()
    } else if req == "scan_games" {
        let found = {
            let mut det = detector.write().unwrap();
            det.scan_device_games()
        };
        let list = detector.read().unwrap().list_all_games();
        format!(
            r#"{{"status":"ok","found":{},"games":{}}}"#,
            found.len(),
            serde_json::to_string(&list).unwrap_or_default()
        )
    } else if req == "list_installed_apps" {
        let apps = GameDetector::get_installed_user_apps();
        serde_json::to_string(&apps).unwrap_or_default()
    } else if let Some(pkg) = req.strip_prefix("add_game ") {
        let res = {
            let mut det = detector.write().unwrap();
            det.add_manual_game(pkg.trim())
        };
        match res {
            Ok(msg) => {
                let list = detector.read().unwrap().list_all_games();
                format!(
                    r#"{{"status":"ok","message":"{}","games":{}}}"#,
                    msg,
                    serde_json::to_string(&list).unwrap_or_default()
                )
            }
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if let Some(pkg) = req.strip_prefix("remove_game ") {
        let res = {
            let mut det = detector.write().unwrap();
            det.remove_game(pkg.trim())
        };
        match res {
            Ok(msg) => {
                let list = detector.read().unwrap().list_all_games();
                format!(
                    r#"{{"status":"ok","message":"{}","games":{}}}"#,
                    msg,
                    serde_json::to_string(&list).unwrap_or_default()
                )
            }
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if let Some(args) = req.strip_prefix("set_game_profile ") {
        let parts: Vec<&str> = args.split_whitespace().collect();
        if parts.len() >= 3 {
            let pkg = parts[0];
            let preset = parts[1];
            let auto_bbr = parts[2] == "1" || parts[2].eq_ignore_ascii_case("true");
            match crate::profile::GameProfile::save_custom_config(pkg, preset, auto_bbr) {
                Ok(_) => {
                    let list = detector.read().unwrap().list_all_games();
                    format!(
                        r#"{{"status":"ok","message":"Profile updated","games":{}}}"#,
                        serde_json::to_string(&list).unwrap_or_default()
                    )
                }
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            }
        } else {
            r#"{"status":"error","message":"Invalid args. Format: set_game_profile <pkg> <preset> <auto_bbr>"}"#.to_string()
        }
    } else {
        match req {
            "ping" => r#"{"status":"ok"}"#.to_string(),
            "apply_game" => {
                let r = apply_profile(&PROFILE_GAME);
                format!(
                    r#"{{"status":"{}","profile":"{}","errors":{:?}}}"#,
                    if r.success { "ok" } else { "fail" },
                    r.profile,
                    r.errors
                )
            }
            "apply_idle" => {
                let r = apply_profile(&PROFILE_IDLE);
                format!(
                    r#"{{"status":"{}","profile":"{}","errors":{:?}}}"#,
                    if r.success { "ok" } else { "fail" },
                    r.profile,
                    r.errors
                )
            }
            "apply_warn" => {
                let r = apply_profile(&PROFILE_WARN);
                format!(
                    r#"{{"status":"{}","profile":"{}","errors":{:?}}}"#,
                    if r.success { "ok" } else { "fail" },
                    r.profile,
                    r.errors
                )
            }
            "restore_all" => match restore_all() {
                Ok(msg) => format!(r#"{{"status":"ok","message":"{}"}}"#, msg),
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            },
            "get_stock" => {
                if let Some(snap) = StockSnapshot::load() {
                    serde_json::to_string(&snap).unwrap_or_default()
                } else {
                    r#"{"status":"none"}"#.to_string()
                }
            }
            "fps" => {
                let pkg = GameDetector::get_foreground_pkg();
                let rep = measure_fps(&pkg);
                serde_json::to_string(&rep).unwrap_or_default()
            }
            "net_capability" => {
                let cap = NetCapability::probe();
                serde_json::to_string(&cap).unwrap_or_default()
            }
            "net_baseline" => {
                let base = measure_baseline();
                serde_json::to_string(&base).unwrap_or_default()
            }
            "net_ping" => {
                let p = run_ping("8.8.8.8", 3);
                serde_json::to_string(&p).unwrap_or_default()
            }
            "net_apply_bbr" => match apply_bbr() {
                Ok(_) => r#"{"status":"ok","mode":"bbr"}"#.to_string(),
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            },
            "net_restore" => match restore_stock_cc() {
                Ok(_) => r#"{"status":"ok","mode":"westwood"}"#.to_string(),
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            },
            "net_ab_test" => {
                // Measure stock
                let _ = restore_stock_cc();
                let p_stock = run_ping("8.8.8.8", 5);
                // Measure BBR
                let _ = apply_bbr();
                let p_bbr = run_ping("8.8.8.8", 5);
                // Restore stock
                let _ = restore_stock_cc();
                format!(
                    r#"{{"stock":{},"bbr":{}}}"#,
                    serde_json::to_string(&p_stock).unwrap_or_default(),
                    serde_json::to_string(&p_bbr).unwrap_or_default()
                )
            }
            "stop" => {
                running.store(false, Ordering::Relaxed);
                let _ = restore_all();
                r#"{"status":"ok","message":"shutting down"}"#.to_string()
            }
            _ => r#"{"error":"unknown command"}"#.to_string(),
        }
    };

    let _ = stream.write_all(response.as_bytes());
    let _ = stream.write_all(b"\n");
}

fn main() {
    let _ = fs::remove_file(SOCK_PATH);

    // Initial stock capture so baseline is preserved
    let snap = StockSnapshot::capture();
    let _ = snap.save_if_new();

    let listener = match UnixListener::bind(SOCK_PATH) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("Failed to bind socket: {}", e);
            std::process::exit(1);
        }
    };

    let _ = fs::set_permissions(SOCK_PATH, fs::Permissions::from_mode(0o666));

    let start = Instant::now();
    let running = Arc::new(AtomicBool::new(true));
    let state_sink = Arc::new(AtomicU8::new(DaemonState::Idle as u8));
    let detector = Arc::new(RwLock::new(GameDetector::new()));

    eprintln!(
        "FrameBoost daemon v2.0.0 started (pid={})",
        std::process::id()
    );

    // 1. Spawn auto daemon loop (handles game detection + thermal hysteresis)
    let auto_run = running.clone();
    let auto_state = state_sink.clone();
    let auto_det = detector.clone();
    let _auto_thread = spawn_auto_daemon(auto_run, auto_state, auto_det);

    // 2. Spawn telemetry snapshot writer (500ms loop to file for WebUI)
    let snap_run = running.clone();
    let snap_state = state_sink.clone();
    let snap_det = detector.clone();
    thread::spawn(move || {
        while snap_run.load(Ordering::Relaxed) {
            let st = snap_state.load(Ordering::Relaxed);
            let snap = take_snapshot(start, &snap_det, st);
            if let Ok(json) = serde_json::to_string(&snap) {
                let _ = fs::write(SNAPSHOT_FILE, &json);
            }
            thread::sleep(Duration::from_millis(500));
        }
    });

    // 3. Socket command listener loop
    for stream in listener.incoming() {
        match stream {
            Ok(s) => {
                let r = running.clone();
                let st = state_sink.clone();
                let det = detector.clone();
                thread::spawn(move || handle_client(s, start, r, st, det));
            }
            Err(e) => {
                eprintln!("Accept error: {}", e);
            }
        }
    }

    // Clean exit: restore all stock knobs
    eprintln!("FrameBoost daemon stopping, restoring stock...");
    let _ = restore_all();
    let _ = fs::remove_file(SOCK_PATH);
}
