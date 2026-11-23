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
use net::{
    apply_bbr, measure_baseline, measure_bufferbloat, multi_target_ping, restore_stock_cc,
    run_ping, NetCapability,
};
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
pub struct BatteryState {
    pub status: String,
    pub capacity_pct: u32,
    pub current_now_ma: i32,
    pub voltage_now_mv: u32,
    pub temp_c: f64,
    pub charge_type: String,
    pub power_w: f64,
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
    pub battery: BatteryState,
    pub vm_swappiness: u32,
    pub heads_up_blocked: bool,
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
    // Android toybox `ip route show default` returns "192.168.1.0/24 dev wlan0 ..."
    // while iproute2 returns "default via X dev wlan0 ...". Extract the token after "dev".
    let route = run_cmd("ip", &["route", "show", "default"]);
    let words: Vec<&str> = route.split_whitespace().collect();
    let mut iface = String::new();
    for i in 0..words.len() {
        if words[i] == "dev" && i + 1 < words.len() {
            iface = words[i + 1].to_string();
            break;
        }
    }
    if iface.is_empty() {
        // Fallback: scan full routing table for the default route
        let full = run_cmd("ip", &["route"]);
        for line in full.lines() {
            if line.contains("default via") {
                let p: Vec<&str> = line.split_whitespace().collect();
                if let Some(pos) = p.iter().position(|w| *w == "dev") {
                    if pos + 1 < p.len() {
                        iface = p[pos + 1].to_string();
                        break;
                    }
                }
            }
        }
    }
    if iface.is_empty() {
        iface = "wlan0".to_string();
    }
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

fn read_battery() -> BatteryState {
    let status = read_file("/sys/class/power_supply/battery/status");
    let capacity_pct = read_u32("/sys/class/power_supply/battery/capacity");
    let mut current_ma = crate::sysfs::read_i32("/sys/class/power_supply/battery/current_now");
    if current_ma > 10000 || current_ma < -10000 {
        current_ma /= 1000;
    }
    let mut voltage_mv = read_u32("/sys/class/power_supply/battery/voltage_now");
    if voltage_mv > 10000 {
        voltage_mv /= 1000;
    }
    let temp_raw = read_u32("/sys/class/power_supply/battery/temp");
    let temp_c = if temp_raw > 100 {
        temp_raw as f64 / 10.0
    } else {
        temp_raw as f64
    };
    let charge_type = read_file("/sys/class/power_supply/battery/charge_type");
    let power_w = (voltage_mv as f64 * (current_ma.abs() as f64)) / 1_000_000.0;
    let power_w = (power_w * 100.0).round() / 100.0;

    BatteryState {
        status: if status.is_empty() { "Discharging".to_string() } else { status },
        capacity_pct,
        current_now_ma: current_ma,
        voltage_now_mv: voltage_mv,
        temp_c,
        charge_type,
        power_w,
    }
}

fn take_snapshot(
    start: Instant,
    _detector: &Arc<RwLock<GameDetector>>,
    state: u8,
    active_pkg: &str,
    last_fps: &mut Option<fps::FpsReport>,
    last_fps_time: &mut Instant,
) -> Snapshot {
    let is_g = !active_pkg.is_empty();
    let st_str = DaemonState::from_u8(state).as_str().to_string();

    let fps_rep = if is_g {
        // ponytail: throttle fps measurement to 1s to prevent RenderThread binder stalls
        if last_fps_time.elapsed() >= Duration::from_millis(1000) {
            let rep = measure_fps(active_pkg);
            *last_fps = Some(rep.clone());
            *last_fps_time = Instant::now();
            rep
        } else {
            last_fps.clone().unwrap_or_default()
        }
    } else {
        *last_fps = None;
        Default::default()
    };

    Snapshot {
        daemon_state: st_str,
        game_pkg: active_pkg.to_string(),
        is_game: is_g,
        fps: fps_rep.fps,
        p1_low_fps: fps_rep.p1_low_fps,
        temp_big_mc: read_u32("/sys/class/thermal/thermal_zone0/temp") as i32,
        temp_little_mc: read_u32("/sys/class/thermal/thermal_zone1/temp") as i32,
        temp_gpu_mc: read_u32("/sys/class/thermal/thermal_zone2/temp") as i32,
        gpu: read_gpu(),
        cpu: read_cpu(),
        net: read_net(),
        battery: read_battery(),
        vm_swappiness: read_u32("/proc/sys/vm/swappiness"),
        heads_up_blocked: is_g,
        daemon_uptime_s: start.elapsed().as_secs(),
        stock_saved: StockSnapshot::exists(),
    }
}

fn handle_client(
    mut stream: UnixStream,
    start: Instant,
    running: Arc<AtomicBool>,
    state_sink: Arc<AtomicU8>,
    game_sink: Arc<RwLock<String>>,
    detector: Arc<RwLock<GameDetector>>,
) {
    let mut buf = [0u8; 1024];
    let n = stream.read(&mut buf).unwrap_or(0);
    let req = String::from_utf8_lossy(&buf[..n]);
    let req = req.trim();

    let current_st = state_sink.load(Ordering::Relaxed);

    let response = if req == "snapshot" {
        let active_pkg = game_sink.read().unwrap().clone();
        let mut last_fps = None;
        let mut last_fps_time = Instant::now();
        serde_json::to_string(&take_snapshot(start, &detector, current_st, &active_pkg, &mut last_fps, &mut last_fps_time)).unwrap_or_default()
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
    } else if req == "gos_disable" {
        let out = std::process::Command::new("pm")
            .args(["disable-user", "--user", "0", "com.samsung.android.game.gos"])
            .output();
        match out {
            Ok(o) if o.status.success() => r#"{"status":"ok","message":"GOS disabled"}"#.to_string(),
            Ok(o) => format!(r#"{{"status":"error","message":"{}"}}"#, String::from_utf8_lossy(&o.stderr).trim()),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if req == "gos_restore" {
        let out = std::process::Command::new("pm")
            .args(["enable", "com.samsung.android.game.gos"])
            .output();
        match out {
            Ok(o) if o.status.success() => r#"{"status":"ok","message":"GOS restored"}"#.to_string(),
            Ok(o) => format!(r#"{{"status":"error","message":"{}"}}"#, String::from_utf8_lossy(&o.stderr).trim()),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if req == "gos_status" {
        let out = std::process::Command::new("pm")
            .args(["list", "packages", "-d"])
            .output();
        let disabled = out.ok().map(|o| String::from_utf8_lossy(&o.stdout).contains("com.samsung.android.game.gos")).unwrap_or(false);
        format!(r#"{{"status":"ok","disabled":{}}}"#, disabled)
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
        if parts.len() >= 2 {
            let pkg = parts[0];
            let preset = parts[1];
            // Third arg is optional: without it the game's existing auto-BBR choice is kept.
            let result = match parts.get(2) {
                Some(flag) => {
                    let auto_bbr = *flag == "1" || flag.eq_ignore_ascii_case("true");
                    crate::profile::GameProfile::save_custom_config(pkg, preset, auto_bbr)
                }
                None => crate::profile::GameProfile::save_preset_only(pkg, preset),
            };
            match result {
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
            r#"{"status":"error","message":"Invalid args. Format: set_game_profile <pkg> <preset> [auto_bbr]"}"#.to_string()
        }
    } else if let Some(args) = req.strip_prefix("net_multi_ping") {
        let parts: Vec<&str> = args.split_whitespace().collect();
        let count: u32 = parts.get(0).and_then(|s| s.parse().ok()).unwrap_or(3);
        let custom_target = parts.get(1).copied();
        let res = multi_target_ping(custom_target, count);
        serde_json::to_string(&res).unwrap_or_default()
    } else if let Some(target) = req.strip_prefix("net_bufferbloat") {
        let t = target.trim();
        let target_host = if t.is_empty() { "1.1.1.1" } else { t };
        let res = measure_bufferbloat(target_host);
        serde_json::to_string(&res).unwrap_or_default()
    } else if let Some(target) = req.strip_prefix("net_ping ") {
        let parts: Vec<&str> = target.split_whitespace().collect();
        let host = parts.get(0).unwrap_or(&"8.8.8.8");
        let count: u32 = parts.get(1).and_then(|s| s.parse().ok()).unwrap_or(5);
        let p = run_ping(host, count);
        serde_json::to_string(&p).unwrap_or_default()
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
                let p_stock = run_ping("1.1.1.1", 8);
                // Measure BBR
                let _ = apply_bbr();
                let p_bbr = run_ping("1.1.1.1", 8);
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
    let game_sink = Arc::new(RwLock::new(String::new()));
    let detector = Arc::new(RwLock::new(GameDetector::new()));

    eprintln!(
        "FrameBoost daemon v2.0.0 started (pid={})",
        std::process::id()
    );

    // 1. Spawn auto daemon loop (handles game detection + thermal hysteresis)
    let auto_run = running.clone();
    let auto_state = state_sink.clone();
    let auto_game = game_sink.clone();
    let auto_det = detector.clone();
    let _auto_thread = spawn_auto_daemon(auto_run, auto_state, auto_game, auto_det);

    // 2. Spawn telemetry snapshot writer (500ms loop to file for WebUI)
    let snap_run = running.clone();
    let snap_state = state_sink.clone();
    let snap_game = game_sink.clone();
    let snap_det = detector.clone();
    thread::spawn(move || {
        let mut last_fps: Option<fps::FpsReport> = None;
        let mut last_fps_time = Instant::now();
        while snap_run.load(Ordering::Relaxed) {
            let st = snap_state.load(Ordering::Relaxed);
            let active_pkg = snap_game.read().unwrap().clone();
            let snap = take_snapshot(start, &snap_det, st, &active_pkg, &mut last_fps, &mut last_fps_time);
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
                let gm = game_sink.clone();
                let det = detector.clone();
                thread::spawn(move || handle_client(s, start, r, st, gm, det));
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
