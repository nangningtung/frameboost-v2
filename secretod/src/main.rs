use serde::Serialize;
use std::fs;
use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};

mod apply;
mod apps;
mod capture;
mod daemon;
mod detect;
mod fps;
mod net;
mod priority;
mod profile;
mod render;
mod rollback;
mod session;
mod snapshot;
mod sysfs;

use daemon::{spawn_auto_daemon, DaemonState};
use detect::GameDetector;
use fps::measure_fps;
use net::{apply_bbr, measure_bufferbloat, multi_target_ping, restore_stock_cc, run_ping};
use rollback::restore_all;
use snapshot::StockSnapshot;
use sysfs::{log_line, read_file, read_u32, run_cmd};

const SOCK_PATH: &str = "/data/local/tmp/secreto.sock";
const SNAPSHOT_FILE: &str = "/data/local/tmp/secreto-snapshot.json";

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
    pub bypass_charging: bool,
}

static GOS_DISABLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Cached "heads-up blocked" state. Reading the setting on every 250 ms
/// snapshot would spam binder, but reporting `is_game` was a lie: turning DND
/// off from the side panel never changed the snapshot. Cache for 5 s and
/// update it immediately on every toggle.
static HEADS_UP_CACHE: Mutex<Option<(bool, Instant)>> = Mutex::new(None);

pub fn set_heads_up_cache(blocked: bool) {
    if let Ok(mut c) = HEADS_UP_CACHE.lock() {
        *c = Some((blocked, Instant::now()));
    }
}

pub fn refresh_heads_up_cache() {
    let out = run_cmd("settings", &["get", "global", "heads_up_notifications_enabled"]);
    set_heads_up_cache(out.trim() == "0");
}

fn heads_up_blocked_cached() -> bool {
    if let Ok(c) = HEADS_UP_CACHE.lock() {
        if let Some((v, at)) = *c {
            if at.elapsed() < Duration::from_secs(5) {
                return v;
            }
        }
    }
    refresh_heads_up_cache();
    HEADS_UP_CACHE
        .lock()
        .ok()
        .and_then(|c| *c)
        .map(|(v, _)| v)
        .unwrap_or(false)
}

fn has_samsung_gos() -> bool {
    std::process::Command::new("pm")
        .args(["path", "com.samsung.android.game.gos"])
        .output()
        .map(|o| o.status.success() && !o.stdout.is_empty())
        .unwrap_or(false)
}

fn getprop(key: &str) -> String {
    run_cmd("getprop", &[key])
}

#[derive(Serialize)]
struct CapabilityProbe {
    device: String,
    model: String,
    android: String,
    rom: String,
    gsi: bool,
    gos: bool,
    freeze: String,
    wm_size: bool,
    cpuset: bool,
    cpuset_controllers: String,
    root: bool,
    selinux: String,
}

/// What this ROM actually supports, so the console can hide or label features
/// instead of pretending they work everywhere.
fn probe_capabilities() -> CapabilityProbe {
    let flavor = getprop("ro.build.flavor");
    let gsi = flavor.to_ascii_lowercase().contains("gsi");
    let oneui = !getprop("ro.build.version.oneui").is_empty()
        || flavor.to_ascii_lowercase().contains("oneui");
    let rom = if gsi {
        "GSI".to_string()
    } else if oneui {
        "One UI".to_string()
    } else {
        "AOSP".to_string()
    };

    // `cmd appops get` answers "No operations. Default mode: ..." for packages
    // without an explicit op, so the op name is not always in the output. A
    // non-empty answer still proves the command and the op exist.
    let appops_out = run_cmd("cmd", &["appops", "get", "com.android.shell", "RUN_IN_BACKGROUND"]);
    let appops_ok = appops_out.contains("RUN_IN_BACKGROUND")
        || appops_out.contains("Default mode")
        || appops_out.contains("No operations");
    let freeze = if appops_ok {
        "appops + force-stop"
    } else {
        "force-stop only"
    }
    .to_string();

    let cpuset = std::path::Path::new("/dev/cpuset").exists();
    let controllers = read_file("/sys/fs/cgroup/cgroup.controllers");
    let root = run_cmd("id", &["-u"]) == "0";
    let selinux = read_file("/sys/fs/selinux/enforce");

    CapabilityProbe {
        device: getprop("ro.product.device"),
        model: getprop("ro.product.model"),
        android: getprop("ro.build.version.release"),
        rom,
        gsi,
        gos: has_samsung_gos(),
        freeze,
        wm_size: crate::render::supported(),
        cpuset,
        cpuset_controllers: controllers,
        root,
        selinux: match selinux.as_str() {
            "1" => "enforcing".to_string(),
            "0" => "permissive".to_string(),
            _ => "unknown".to_string(),
        },
    }
}

fn check_gos_disabled() -> bool {
    if !has_samsung_gos() {
        return true;
    }

    let out = std::process::Command::new("pm")
        .args(["list", "packages", "-d"])
        .output();
    out.ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).contains("com.samsung.android.game.gos"))
        .unwrap_or(false)
}

#[derive(Serialize, Clone, Default)]
pub struct Snapshot {
    pub daemon_state: String,
    pub game_pkg: String,
    pub game_preset: String,
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
    pub gos_disabled: bool,
    pub has_gos: bool,
    pub daemon_uptime_s: u64,
    pub stock_saved: bool,
    pub session: crate::session::SessionInfo,
    pub sleeping_apps: Vec<String>,
    pub recording: bool,
    pub record_path: String,
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
    let chg_enabled = read_file("/sys/class/power_supply/battery/charging_enabled");
    let bypass_charging = chg_enabled.trim() == "0";

    BatteryState {
        status: if status.is_empty() { "Discharging".to_string() } else { status },
        capacity_pct,
        current_now_ma: current_ma,
        voltage_now_mv: voltage_mv,
        temp_c,
        charge_type,
        power_w,
        bypass_charging,
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
            // Record temperature and battery even when the game is not drawing
            // frames, so a short or paused session still reports a real peak.
            crate::session::sample(
                &rep,
                read_u32("/sys/class/thermal/thermal_zone0/temp"),
                read_u32("/sys/class/power_supply/battery/capacity") as i32,
            );
            *last_fps = Some(rep.clone());
            *last_fps_time = Instant::now();
            rep
        } else {
            // Reuse the last reading between measurements, but report its real
            // age instead of pretending it was sampled this instant.
            let mut rep = last_fps.clone().unwrap_or_default();
            if rep.fps.is_some() {
                rep.age_ms = last_fps_time.elapsed().as_millis() as u64;
            }
            rep
        }
    } else {
        *last_fps = None;
        Default::default()
    };

    let (recording, record_path, _record_elapsed) = crate::capture::status();

    Snapshot {
        daemon_state: st_str,
        game_pkg: active_pkg.to_string(),
        game_preset: if is_g {
            crate::profile::GameProfile::for_package(&active_pkg).preset
        } else {
            String::new()
        },
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
        heads_up_blocked: heads_up_blocked_cached(),
        gos_disabled: GOS_DISABLED.load(Ordering::Relaxed),
        has_gos: has_samsung_gos(),
        daemon_uptime_s: start.elapsed().as_secs(),
        stock_saved: StockSnapshot::exists(),
        session: crate::session::info(),
        sleeping_apps: crate::session::sleeping_apps(),
        recording,
        record_path,
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
        if !has_samsung_gos() {
            GOS_DISABLED.store(true, Ordering::Relaxed);
            r#"{"status":"ok","message":"GOS not installed on this ROM (already bypassed)","disabled":true,"has_gos":false}"#.to_string()
        } else {
            let out = std::process::Command::new("pm")
                .args(["disable-user", "--user", "0", "com.samsung.android.game.gos"])
                .output();
            match out {
                Ok(o) if o.status.success() => {
                    GOS_DISABLED.store(true, Ordering::Relaxed);
                    r#"{"status":"ok","message":"GOS disabled","disabled":true,"has_gos":true}"#.to_string()
                }
                Ok(o) => format!(r#"{{"status":"error","message":"{}"}}"#, String::from_utf8_lossy(&o.stderr).trim()),
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            }
        }
    } else if req == "gos_restore" {
        if !has_samsung_gos() {
            GOS_DISABLED.store(false, Ordering::Relaxed);
            r#"{"status":"ok","message":"GOS not installed on this ROM","disabled":false,"has_gos":false}"#.to_string()
        } else {
            let out = std::process::Command::new("pm")
                .args(["enable", "com.samsung.android.game.gos"])
                .output();
            match out {
                Ok(o) if o.status.success() => {
                    GOS_DISABLED.store(false, Ordering::Relaxed);
                    r#"{"status":"ok","message":"GOS restored","disabled":false,"has_gos":true}"#.to_string()
                }
                Ok(o) => format!(r#"{{"status":"error","message":"{}"}}"#, String::from_utf8_lossy(&o.stderr).trim()),
                Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
            }
        }
    } else if req == "gos_status" {
        let disabled = GOS_DISABLED.load(Ordering::Relaxed);
        let exists = has_samsung_gos();
        format!(r#"{{"status":"ok","disabled":{},"has_gos":{}}}"#, disabled, exists)
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
    } else if let Some(val) = req.strip_prefix("set_heads_up ") {
        let v = if val.trim() == "0" || val.trim().eq_ignore_ascii_case("false") { "0" } else { "1" };
        let out = std::process::Command::new("settings")
            .args(["put", "global", "heads_up_notifications_enabled", v])
            .output();
        match out {
            Ok(_) => {
                set_heads_up_cache(v == "0");
                format!(r#"{{"status":"ok","heads_up":"{}"}}"#, v)
            }
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if let Some(val) = req.strip_prefix("set_bypass_charge ") {
        let v = if val.trim() == "1" || val.trim().eq_ignore_ascii_case("true") { "0" } else { "1" };
        let res = std::fs::write("/sys/class/power_supply/battery/charging_enabled", v);
        match res {
            Ok(_) => format!(r#"{{"status":"ok","bypass":{}}}"#, v == "0"),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if let Some(preset_name) = req.strip_prefix("apply_preset ") {
        let fg = GameDetector::get_foreground_pkg();
        let in_game = {
            let mut det = detector.write().unwrap();
            det.is_game_fast(&fg).unwrap_or_else(|| det.is_game(&fg))
        };
        if !in_game {
            // The console runs in the foreground while the game is paused in
            // the background. Applying clocks there would leave them stuck, so
            // only persist the choice; the daemon applies it on game launch.
            r#"{"status":"idle","message":"Profile saved; applies on next launch"}"#.to_string()
        } else {
            let preset = crate::profile::Preset::from_str(preset_name.trim());
            let prof = crate::profile::GameProfile::build("MANUAL", preset, true);
            let boost = crate::apply::BoostProfile {
                name: prof.preset.clone(),
                gpu_gov: prof.gpu_gov.clone(),
                gpu_min: prof.gpu_min,
                cpu0_min: prof.cpu0_min,
                cpu4_min: prof.cpu4_min,
            };
            let res = crate::apply::apply_profile(&boost);
            format!(r#"{{"status":"{}","preset":"{}"}}"#, if res.success { "ok" } else { "fail" }, prof.preset)
        }
    } else if req == "session_status" {
        let info = crate::session::info();
        let last = crate::session::last_report();
        format!(
            r#"{{"status":"ok","session":{},"last":{},"sleeping":{}}}"#,
            serde_json::to_string(&info).unwrap_or_default(),
            serde_json::to_string(&last).unwrap_or_default(),
            serde_json::to_string(&crate::session::sleeping_apps()).unwrap_or_default()
        )
    } else if req == "session_history" || req.starts_with("session_history ") {
        let arg = req.strip_prefix("session_history").unwrap_or("").trim();
        let reports = if arg.is_empty() {
            crate::session::history_all()
        } else {
            crate::session::history(arg)
        };
        let suggestion = if arg.is_empty() {
            None
        } else {
            crate::session::suggestion(arg)
        };
        format!(
            r#"{{"status":"ok","pkg":"{}","reports":{},"suggestion":{}}}"#,
            arg,
            serde_json::to_string(&reports).unwrap_or_default(),
            serde_json::to_string(&suggestion).unwrap_or_default()
        )
    } else if req == "get_render_scale" {
        format!(
            r#"{{"status":"ok","render":{}}}"#,
            serde_json::to_string(&crate::render::status()).unwrap_or_default()
        )
    } else if let Some(rest) = req.strip_prefix("set_render_scale ") {
        let mut parts = rest.split_whitespace();
        let pkg = parts.next().unwrap_or("");
        let mode = parts.next().unwrap_or("native");
        match crate::render::set(pkg, mode) {
            Ok(_) => format!(
                r#"{{"status":"ok","render":{}}}"#,
                serde_json::to_string(&crate::render::status()).unwrap_or_default()
            ),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if req == "probe" {
        format!(
            r#"{{"status":"ok","probe":{}}}"#,
            serde_json::to_string(&probe_capabilities()).unwrap_or_default()
        )
    } else if req == "screenshot" {
        crate::capture::screenshot()
    } else if let Some(rest) = req.strip_prefix("record_start") {
        let preset = rest.trim();
        crate::capture::start(if preset.is_empty() { "std" } else { preset })
    } else if req == "record_stop" {
        crate::capture::stop("panel")
    } else if req == "record_status" {
        let (active, path, elapsed) = crate::capture::status();
        serde_json::json!({
            "status": "ok",
            "recording": active,
            "path": path,
            "elapsed": elapsed,
        })
        .to_string()
    } else if let Some(rest) = req.strip_prefix("session_start") {
        let arg = rest.trim();
        let pkg = if arg.is_empty() {
            GameDetector::get_foreground_pkg()
        } else {
            arg.to_string()
        };
        if pkg.is_empty() || pkg == "com.secreto.space" {
            r#"{"status":"error","message":"Open a game first"}"#.to_string()
        } else {
            let preset = crate::profile::GameProfile::for_package(&pkg).preset;
            let fresh = crate::session::start(&pkg, &preset);
            if fresh {
                crate::session::sleep_background(&pkg);
            }
            format!(
                r#"{{"status":"ok","message":"{}","pkg":"{}","preset":"{}","sleep_queued":{}}}"#,
                if fresh { "Session started" } else { "Session already active" },
                pkg,
                preset,
                fresh
            )
        }
    } else if req == "session_end" {
        let target = crate::session::active_pkg().unwrap_or_else(GameDetector::get_foreground_pkg);
        crate::daemon::pause_current(&target);
        let report = crate::session::finish("manual");
        let restore = match restore_all() {
            Ok(msg) => msg,
            Err(e) => e,
        };
        format!(
            r#"{{"status":"ok","report":{},"restore":"{}"}}"#,
            serde_json::to_string(&report).unwrap_or_default(),
            restore
        )
    } else if req == "sleep_apps" {
        let game = GameDetector::get_foreground_pkg();
        let list = crate::session::preview_candidates(&game);
        format!(
            r#"{{"status":"ok","candidates":{},"count":{}}}"#,
            serde_json::to_string(&list).unwrap_or_default(),
            list.len()
        )
    } else if req == "wake_apps" {
        let woke = crate::session::wake_now();
        format!(r#"{{"status":"ok","woke":{}}}"#, woke)
    } else if req == "get_sleep_settings" {
        let game = GameDetector::get_foreground_pkg();
        let candidates = crate::session::preview_candidates(&game);
        format!(
            r#"{{"status":"ok","enabled":{},"whitelist":{},"sleeping":{},"candidates":{},"count":{}}}"#,
            crate::apps::enabled(),
            serde_json::to_string(&crate::apps::whitelist()).unwrap_or_default(),
            serde_json::to_string(&crate::session::sleeping_apps()).unwrap_or_default(),
            serde_json::to_string(&candidates).unwrap_or_default(),
            candidates.len()
        )
    } else if let Some(val) = req.strip_prefix("set_sleep_enabled ") {
        let on = val.trim() == "1" || val.trim().eq_ignore_ascii_case("true");
        match crate::apps::set_enabled(on) {
            Ok(_) => format!(r#"{{"status":"ok","enabled":{}}}"#, on),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else if req == "set_sleep_whitelist" || req.starts_with("set_sleep_whitelist ") {
        let val = req.strip_prefix("set_sleep_whitelist").unwrap_or("").trim();
        let list: Vec<String> = val
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| s.contains('.'))
            .collect();
        match crate::apps::set_whitelist(&list) {
            Ok(_) => format!(
                r#"{{"status":"ok","whitelist":{}}}"#,
                serde_json::to_string(&crate::apps::whitelist()).unwrap_or_default()
            ),
            Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
        }
    } else {
        match req {
            "ping" => r#"{"status":"ok"}"#.to_string(),
            "fg" => {
                // Fresh foreground package for the overlay visibility loop.
                // The detector is the same source the session engine uses,
                // so the panel cannot disagree with the daemon.
                let pkg = GameDetector::get_foreground_pkg_cached();
                format!(r#"{{"status":"ok","pkg":"{}"}}"#, pkg)
            }
            "get_heads_up" => {
                let out = std::process::Command::new("settings")
                    .args(["get", "global", "heads_up_notifications_enabled"])
                    .output();
                let val = out.ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_else(|| "1".to_string());
                let enabled = val != "0";
                format!(r#"{{"status":"ok","enabled":{}}}"#, enabled)
            }
            "get_bypass_charge" => {
                let chg = read_file("/sys/class/power_supply/battery/charging_enabled");
                let bypass = chg.trim() == "0";
                format!(r#"{{"status":"ok","bypass":{}}}"#, bypass)
            }
            "restore_all" => {
                // Same contract as the console's End session: wake the apps,
                // close the session and pause the current game, otherwise
                // keep-alive re-applies the preset five seconds later.
                let target =
                    crate::session::active_pkg().unwrap_or_else(GameDetector::get_foreground_pkg);
                crate::daemon::pause_current(&target);
                crate::session::finish("manual_restore");
                match restore_all() {
                    Ok(msg) => format!(r#"{{"status":"ok","message":"{}"}}"#, msg),
                    Err(e) => format!(r#"{{"status":"error","message":"{}"}}"#, e),
                }
            }
            "fps" => {
                let pkg = GameDetector::get_foreground_pkg_cached();
                let rep = measure_fps(&pkg);
                serde_json::to_string(&rep).unwrap_or_default()
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
    // Secreto Space is built for one device. `customize.sh` and `service.sh`
    // already refuse other phones; this is the last line of defence in case the
    // binary is started by hand.
    let device = getprop("ro.product.device");
    if device != "a50" {
        log_line(&format!(
            "device {} is not a50, refusing to start",
            if device.is_empty() { "unknown" } else { device.as_str() }
        ));
        return;
    }

    // Single instance, checked before anything else touches the device. A
    // duplicate used to run recovery first, which woke every slept app and
    // reset the display in the middle of a live session. Never unlink a socket
    // that still answers: that let a second daemon take over the path while the
    // first one kept running the tick loop with no client attached.
    let listener = match UnixListener::bind(SOCK_PATH) {
        Ok(l) => l,
        Err(_) => {
            if UnixStream::connect(SOCK_PATH).is_ok() {
                log_line("another daemon is already listening, exiting");
                return;
            }
            // Stale socket from a crash: nobody answers, so replace it.
            let _ = fs::remove_file(SOCK_PATH);
            match UnixListener::bind(SOCK_PATH) {
                Ok(l) => l,
                Err(e) => {
                    log_line(&format!("Failed to bind socket: {}", e));
                    std::process::exit(1);
                }
            }
        }
    };

    GOS_DISABLED.store(check_gos_disabled(), Ordering::Relaxed);

    // Initial stock capture so baseline is preserved
    let snap = StockSnapshot::capture();
    let _ = snap.save_if_new();

    // Finish anything a crash or a reboot interrupted before serving clients.
    // `recover()` logs its own summary, so it is not logged twice here.
    let _ = crate::session::recover();
    if let Some(msg) = crate::render::recover() {
        log_line(&msg);
    }

    let _ = fs::set_permissions(SOCK_PATH, fs::Permissions::from_mode(0o666));

    let start = Instant::now();
    let running = Arc::new(AtomicBool::new(true));
    let state_sink = Arc::new(AtomicU8::new(DaemonState::Idle as u8));
    let game_sink = Arc::new(RwLock::new(String::new()));
    let detector = Arc::new(RwLock::new(GameDetector::new()));

    log_line(&format!("Secreto Space daemon v0.1.0 started (pid={})", std::process::id()));

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
            thread::sleep(Duration::from_millis(250));
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
                log_line(&format!("Accept error: {}", e));
            }
        }
    }

    // Clean exit: restore all stock knobs
    log_line("Secreto Space daemon stopping, restoring stock...");
    let _ = restore_all();
    let _ = fs::remove_file(SOCK_PATH);
}
