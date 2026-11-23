use serde::{Deserialize, Serialize};

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
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NetBaseline {
    pub capability: NetCapability,
    pub gateway_ping: PingResult,
    pub internet_ping: PingResult,
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
                if parts.len() >= 5 {
                    gw = parts[2].to_string();
                    iface = parts[4].to_string();
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
    };

    for line in out.lines() {
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
                if numbers.len() == 4 {
                    res.min_ms = numbers[0].trim().parse().unwrap_or(0.0);
                    res.avg_ms = numbers[1].trim().parse().unwrap_or(0.0);
                    res.max_ms = numbers[2].trim().parse().unwrap_or(0.0);
                    res.mdev_ms = numbers[3].trim().parse().unwrap_or(0.0);
                }
            }
        }
    }

    res
}

pub fn measure_baseline() -> NetBaseline {
    let cap = NetCapability::probe();
    let gw_ping = run_ping(&cap.gateway, 3);
    let net_ping = run_ping("8.8.8.8", 3);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    NetBaseline {
        capability: cap,
        gateway_ping: gw_ping,
        internet_ping: net_ping,
        timestamp: now,
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
