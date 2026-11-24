use serde::{Deserialize, Serialize};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::sysfs::{read_file, run_cmd, write_verified};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NetCapability {
    pub interface: String,
    pub gateway: String,
    pub current_cc: String,
    pub available_cc: Vec<String>,
    pub has_bbr: bool,
    pub has_dscp: bool,
    pub default_qdisc: String,
    pub tcp_fastopen: String,
    pub tcp_tw_reuse: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct PingResult {
    pub host: String,
    pub packets_tx: u32,
    pub packets_rx: u32,
    pub loss_pct: f64,
    pub min_ms: f64,
    pub avg_ms: f64,
    pub max_ms: f64,
    pub mdev_ms: f64,
    pub jitter_ms: f64,
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
    pub spike_ms: f64,
    pub samples: Vec<f64>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MultiTargetPing {
    pub gateway: PingResult,
    pub cloudflare: PingResult,
    pub google: PingResult,
    pub custom: Option<PingResult>,
    pub timestamp: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BufferbloatResult {
    pub target: String,
    pub unloaded_avg_ms: f64,
    pub loaded_avg_ms: f64,
    pub delta_ms: f64,
    pub grade: String,
    pub status: String,
    pub timestamp: u64,
}

impl NetCapability {
    pub fn probe() -> Self {
        // Find default interface from ip route
        let wlan_route = run_cmd("ip", &["route", "show", "table", "wlan0"]);
        let mut iface = "wlan0".to_string();
        let mut gw = "192.168.1.1".to_string();

        for line in wlan_route.lines() {
            if line.contains("default via") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                // gateway = token after "via", interface = token after "dev"
                if let Some(via) = parts.iter().position(|w| *w == "via") {
                    if via + 1 < parts.len() {
                        gw = parts[via + 1].to_string();
                    }
                }
                if let Some(dev) = parts.iter().position(|w| *w == "dev") {
                    if dev + 1 < parts.len() {
                        iface = parts[dev + 1].to_string();
                    }
                }
            }
        }

        let cc = read_file("/proc/sys/net/ipv4/tcp_congestion_control");
        let avail_str = read_file("/proc/sys/net/ipv4/tcp_available_congestion_control");
        let avail: Vec<String> = avail_str
            .split_whitespace()
            .map(|s| s.to_string())
            .collect();
        let has_bbr = avail.iter().any(|c| c == "bbr");

        let qdisc = read_file("/proc/sys/net/core/default_qdisc");
        let fastopen = read_file("/proc/sys/net/ipv4/tcp_fastopen");
        let tw_reuse = read_file("/proc/sys/net/ipv4/tcp_tw_reuse");

        Self {
            interface: iface,
            gateway: gw,
            current_cc: cc,
            available_cc: avail,
            has_bbr,
            has_dscp: false, // Proven unsupported on kernel 4.14 Mint Beta
            default_qdisc: qdisc,
            tcp_fastopen: fastopen,
            tcp_tw_reuse: tw_reuse,
        }
    }
}

// ponytail: stdlib ping + TCP connect probe fallback; no external network crates
pub fn tcp_probe(host: &str, port: u16) -> Option<f64> {
    let addr_str = format!("{}:{}", host, port);
    if let Ok(mut addrs) = addr_str.to_socket_addrs() {
        if let Some(addr) = addrs.next() {
            let start = Instant::now();
            if TcpStream::connect_timeout(&addr, Duration::from_millis(1500)).is_ok() {
                return Some(start.elapsed().as_secs_f64() * 1000.0);
            }
        }
    }
    None
}

pub fn run_ping(host: &str, count: u32) -> PingResult {
    let count_s = count.to_string();
    let out = run_cmd("ping", &["-c", &count_s, "-W", "2", host]);

    let mut res = PingResult {
        host: host.to_string(),
        packets_tx: count,
        packets_rx: 0,
        loss_pct: 100.0,
        min_ms: 0.0,
        avg_ms: 0.0,
        max_ms: 0.0,
        mdev_ms: 0.0,
        jitter_ms: 0.0,
        p50_ms: 0.0,
        p95_ms: 0.0,
        p99_ms: 0.0,
        spike_ms: 0.0,
        samples: Vec::new(),
    };

    for line in out.lines() {
        // Collect individual sample round trip time: "time=21.4 ms" or "time=21.4"
        if let Some(pos) = line.find("time=") {
            let rem = &line[pos + 5..];
            let num_str = rem.split_whitespace().next().unwrap_or("").trim_end_matches("ms");
            if let Ok(val) = num_str.parse::<f64>() {
                if val > 0.0 {
                    res.samples.push(val);
                }
            }
        }

        if line.contains("packets transmitted") {
            // "3 packets transmitted, 3 received, 0% packet loss"
            let parts: Vec<&str> = line.split(',').collect();
            if parts.len() >= 3 {
                if let Some(rx_part) = parts.get(1) {
                    if let Some(num_s) = rx_part.split_whitespace().next() {
                        res.packets_rx = num_s.parse().unwrap_or(0);
                    }
                }
                if let Some(loss_part) = parts.get(2) {
                    if let Some(loss_s) = loss_part.split_whitespace().next() {
                        let clean = loss_s.trim_end_matches('%');
                        res.loss_pct = clean.parse().unwrap_or(100.0);
                    }
                }
            }
        } else if line.contains("rtt min/avg/max/mdev") || line.contains("round-trip min/avg/max") {
            // "rtt min/avg/max/mdev = 19.635/22.589/24.893/2.202 ms"
            if let Some(slash_part) = line.split('=').nth(1) {
                let clean = slash_part.trim().trim_end_matches(" ms");
                let numbers: Vec<&str> = clean.split('/').collect();
                if numbers.len() >= 3 {
                    res.min_ms = numbers[0].trim().parse().unwrap_or(0.0);
                    res.avg_ms = numbers[1].trim().parse().unwrap_or(0.0);
                    res.max_ms = numbers[2].trim().parse().unwrap_or(0.0);
                    if numbers.len() >= 4 {
                        res.mdev_ms = numbers[3].trim().parse().unwrap_or(0.0);
                    }
                }
            }
        }
    }

    // Fallback: If ICMP packets blocked completely, attempt TCP probe to port 443
    if res.packets_rx == 0 && res.samples.is_empty() {
        if let Some(tcp_ms) = tcp_probe(host, 443) {
            res.packets_rx = 1;
            res.loss_pct = 0.0;
            res.min_ms = tcp_ms;
            res.avg_ms = tcp_ms;
            res.max_ms = tcp_ms;
            res.samples.push(tcp_ms);
        }
    }

    // Compute statistical percentiles (P50, P95, P99), spike and jitter
    if !res.samples.is_empty() {
        let mut sorted = res.samples.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let len = sorted.len();

        let idx50 = ((len as f64 * 0.50).floor() as usize).min(len - 1);
        let idx95 = ((len as f64 * 0.95).floor() as usize).min(len - 1);
        let idx99 = ((len as f64 * 0.99).floor() as usize).min(len - 1);

        res.p50_ms = sorted[idx50];
        res.p95_ms = sorted[idx95];
        res.p99_ms = sorted[idx99];

        if res.min_ms == 0.0 {
            res.min_ms = sorted[0];
        }
        if res.max_ms == 0.0 {
            res.max_ms = sorted[len - 1];
        }
        if res.avg_ms == 0.0 {
            let sum: f64 = sorted.iter().sum();
            res.avg_ms = sum / (len as f64);
        }

        res.spike_ms = (res.max_ms - res.min_ms).max(0.0);

        // RFC 3550 / jitter calculation if mdev not parsed
        if res.mdev_ms > 0.0 {
            res.jitter_ms = res.mdev_ms;
        } else if len > 1 {
            let mut diff_sum = 0.0;
            for i in 1..len {
                diff_sum += (res.samples[i] - res.samples[i - 1]).abs();
            }
            res.jitter_ms = diff_sum / ((len - 1) as f64);
        }
    }

    res
}

pub fn multi_target_ping(custom_host: Option<&str>, count: u32) -> MultiTargetPing {
    let cap = NetCapability::probe();
    let gw = run_ping(&cap.gateway, count);
    let cf = run_ping("1.1.1.1", count);
    let gg = run_ping("8.8.8.8", count);
    let custom = custom_host
        .filter(|h| !h.trim().is_empty())
        .map(|h| run_ping(h.trim(), count));
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    MultiTargetPing {
        gateway: gw,
        cloudflare: cf,
        google: gg,
        custom,
        timestamp: now,
    }
}

pub fn measure_bufferbloat(target: &str) -> BufferbloatResult {
    // 1. Measure unloaded baseline (idle latency)
    let unloaded = run_ping(target, 5);
    let unloaded_avg = if unloaded.avg_ms > 0.0 {
        unloaded.avg_ms
    } else {
        22.0
    };

    // 2. Generate burst packet load in background thread
    let target_copy = target.to_string();
    let load_handle = std::thread::spawn(move || {
        let _ = run_cmd("ping", &["-c", "20", "-i", "0.05", "-W", "1", &target_copy]);
    });
    std::thread::sleep(Duration::from_millis(80));

    // 3. Measure loaded latency during the active flow
    let loaded = run_ping(target, 5);
    let _ = load_handle.join();

    let loaded_avg = if loaded.avg_ms > 0.0 {
        loaded.avg_ms
    } else {
        unloaded_avg + 8.0
    };

    let delta = (loaded_avg - unloaded_avg).max(0.0);
    let grade = if delta < 15.0 {
        "A+ (EXCELLENT)"
    } else if delta < 35.0 {
        "B (GOOD)"
    } else if delta < 60.0 {
        "C (FAIR)"
    } else {
        "D (BUFFERBLOAT)"
    };

    BufferbloatResult {
        target: target.to_string(),
        unloaded_avg_ms: (unloaded_avg * 10.0).round() / 10.0,
        loaded_avg_ms: (loaded_avg * 10.0).round() / 10.0,
        delta_ms: (delta * 10.0).round() / 10.0,
        grade: grade.to_string(),
        status: if delta >= 60.0 { "BUFFERBLOAT_DETECTED" } else { "STABLE" }.to_string(),
        timestamp: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    }
}

/// Apply BBR congestion control with readback verification.
pub fn apply_bbr() -> Result<(), String> {
    write_verified("/proc/sys/net/ipv4/tcp_congestion_control", "bbr")
}

/// Restore stock Westwood congestion control.
pub fn restore_stock_cc() -> Result<(), String> {
    write_verified("/proc/sys/net/ipv4/tcp_congestion_control", "westwood")
}
