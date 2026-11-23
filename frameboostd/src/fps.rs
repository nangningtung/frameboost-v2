use serde::{Deserialize, Serialize};

use crate::sysfs::run_cmd;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct FpsReport {
    pub package: String,
    pub available: bool,
    pub fps: Option<f64>,
    pub frame_time_ms: Option<f64>,
    pub p1_low_fps: Option<f64>,
    pub jank_count: u32,
    pub total_frames: u32,
}

pub fn measure_fps(pkg: &str) -> FpsReport {
    if pkg.is_empty() {
        return FpsReport::default();
    }

    let out = run_cmd("dumpsys", &["gfxinfo", pkg, "framestats"]);
    let mut frame_durations_ns: Vec<u64> = Vec::new();
    let mut in_stats = false;
    let mut col_start = 10; // Default index for FrameStartTime
    let mut col_end = 17;   // Default index for FrameCompleted

    for line in out.lines() {
        if line.starts_with("Flags,") {
            in_stats = true;
            // Map column headers dynamically
            let headers: Vec<&str> = line.split(',').collect();
            for (i, h) in headers.iter().enumerate() {
                if *h == "FrameStartTime" || *h == "IntendedVsync" {
                    col_start = i;
                } else if *h == "FrameCompleted" {
                    col_end = i;
                }
            }
            continue;
        }

        if in_stats {
            if line.is_empty() || line.starts_with("---") {
                continue;
            }
            let vals: Vec<&str> = line.split(',').collect();
            if vals.len() > col_end {
                let start_ns: u64 = vals[col_start].parse().unwrap_or(0);
                let end_ns: u64 = vals[col_end].parse().unwrap_or(0);
                if end_ns > start_ns && start_ns > 0 {
                    let dur = end_ns - start_ns;
                    // Filter outlier anomalies (> 500ms)
                    if dur < 500_000_000 {
                        frame_durations_ns.push(dur);
                    }
                }
            }
        }
    }

    if frame_durations_ns.is_empty() {
        return FpsReport {
            package: pkg.to_string(),
            available: false,
            fps: None,
            frame_time_ms: None,
            p1_low_fps: None,
            jank_count: 0,
            total_frames: 0,
        };
    }

    let total = frame_durations_ns.len() as u32;
    // Calculate janks: frame time > 16.67ms (16_666_666 ns)
    let janks = frame_durations_ns
        .iter()
        .filter(|&&d| d > 16_666_666)
        .count() as u32;

    let sum_ns: u64 = frame_durations_ns.iter().sum();
    let avg_ns = sum_ns as f64 / total as f64;
    let avg_ms = avg_ns / 1_000_000.0;
    let fps = if avg_ms > 0.0 { 1000.0 / avg_ms } else { 0.0 };

    // 1% Low: sort durations ascending, take top 1% worst (longest duration)
    let mut sorted = frame_durations_ns.clone();
    sorted.sort_unstable();
    let p99_idx = (total as f64 * 0.99) as usize;
    let p99_dur_ns = *sorted.get(p99_idx.min(sorted.len() - 1)).unwrap_or(&16_666_666);
    let p1_low_fps = if p99_dur_ns > 0 {
        1_000_000_000.0 / p99_dur_ns as f64
    } else {
        0.0
    };

    FpsReport {
        package: pkg.to_string(),
        available: true,
        fps: Some((fps * 10.0).round() / 10.0),
        frame_time_ms: Some((avg_ms * 10.0).round() / 10.0),
        p1_low_fps: Some((p1_low_fps * 10.0).round() / 10.0),
        jank_count: janks,
        total_frames: total,
    }
}
