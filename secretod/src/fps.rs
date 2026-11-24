use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

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

#[derive(Default, Clone)]
struct LayerDump {
    name: String,
    total_frames: u64,
    histogram_p2p: HashMap<u32, u32>,
}

struct LayerSample {
    frames: u64,
    at: Instant,
    hist: HashMap<u32, u32>,
}

struct FpsTracker {
    last_pkg: String,
    layers: HashMap<String, LayerSample>,
    last_active_layer: String,
    stale_polls: u32,
    timestats_enabled: bool,
}

static TRACKER: Mutex<Option<FpsTracker>> = Mutex::new(None);

/// Layer stats are only collected while TimeStats is enabled. Samsung ships
/// it disabled by default, which is why the counters freeze and the old code
/// kept showing a stale average FPS.
fn enable_timestats() {
    let _ = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-enable"]);
}

/// Clear + enable re-registers the layers that exist right now. Without this,
/// a game that recreated its surface keeps reporting the dead layer forever.
fn reset_timestats() {
    let _ = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-clear"]);
    let _ = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-enable"]);
}

fn parse_histogram(hist: &mut HashMap<u32, u32>, line: &str) {
    for token in line.split_whitespace() {
        if let Some((k, v)) = token.split_once('=') {
            let ms = k.trim_end_matches("ms").parse::<u32>().unwrap_or(0);
            let cnt = v.parse::<u32>().unwrap_or(0);
            if cnt > 0 {
                *hist.entry(ms).or_insert(0) += cnt;
            }
        }
    }
}

fn parse_layers(out: &str, pkg: &str) -> Vec<LayerDump> {
    let mut layers: Vec<LayerDump> = Vec::new();
    let mut current: Option<LayerDump> = None;
    let mut in_histogram = false;

    for line in out.lines() {
        let trimmed = line.trim();
        if let Some(name) = trimmed.strip_prefix("layerName = ") {
            if let Some(l) = current.take() {
                if l.name.contains(pkg) && l.total_frames > 0 {
                    layers.push(l);
                }
            }
            current = Some(LayerDump {
                name: name.to_string(),
                ..Default::default()
            });
            in_histogram = false;
            continue;
        }

        if let Some(ref mut l) = current {
            if let Some(tf_str) = trimmed.strip_prefix("totalFrames = ") {
                l.total_frames = tf_str.parse().unwrap_or(0);
            } else if trimmed.starts_with("present2present histogram") {
                in_histogram = true;
            } else if in_histogram {
                if trimmed.contains('=') {
                    parse_histogram(&mut l.histogram_p2p, trimmed);
                } else if trimmed.ends_with(':') {
                    in_histogram = false;
                }
            }
        }
    }

    if let Some(l) = current {
        if l.name.contains(pkg) && l.total_frames > 0 {
            layers.push(l);
        }
    }

    layers
}

fn diff_histogram(now: &HashMap<u32, u32>, prev: &HashMap<u32, u32>) -> HashMap<u32, u32> {
    let mut out = HashMap::new();
    for (&ms, &cnt) in now {
        let before = prev.get(&ms).copied().unwrap_or(0);
        if cnt > before {
            out.insert(ms, cnt - before);
        }
    }
    out
}

fn measure_fps_timestats(pkg: &str) -> Option<FpsReport> {
    let mut guard = TRACKER.lock().unwrap();
    let tracker = guard.get_or_insert_with(|| {
        enable_timestats();
        FpsTracker {
            last_pkg: String::new(),
            layers: HashMap::new(),
            last_active_layer: String::new(),
            stale_polls: 0,
            timestats_enabled: true,
        }
    });

    if tracker.last_pkg != pkg {
        reset_timestats();
        tracker.last_pkg = pkg.to_string();
        tracker.layers.clear();
        tracker.last_active_layer.clear();
        tracker.stale_polls = 0;
        tracker.timestats_enabled = true;
        // First sample after a reset has no baseline; wait for the next poll.
        return None;
    }

    if !tracker.timestats_enabled {
        enable_timestats();
        tracker.timestats_enabled = true;
    }

    let out = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-dump"]);
    if out.is_empty() {
        return None;
    }

    let layers = parse_layers(&out, pkg);
    if layers.is_empty() {
        return None;
    }

    // Games render to SurfaceView / Surface layers; splash screens and window
    // containers only draw a handful of static frames.
    let surface_views: Vec<LayerDump> = layers
        .iter()
        .filter(|l| l.name.contains("SurfaceView") || l.name.contains("Surface"))
        .cloned()
        .collect();
    let candidates = if !surface_views.is_empty() {
        surface_views
    } else {
        layers
    };

    let now = Instant::now();
    let mut best: Option<(LayerDump, u64, f64, HashMap<u32, u32>)> = None;

    for l in &candidates {
        if let Some(prev) = tracker.layers.get(&l.name) {
            let d_frames = l.total_frames.saturating_sub(prev.frames);
            let d_sec = prev.at.elapsed().as_secs_f64();
            if d_sec >= 0.2 {
                let fps = (d_frames as f64 / d_sec).min(120.0);
                let dhist = diff_histogram(&l.histogram_p2p, &prev.hist);
                let better = match &best {
                    Some((_, best_frames, _, _)) => d_frames > *best_frames,
                    None => true,
                };
                if better {
                    best = Some((l.clone(), d_frames, fps, dhist));
                }
            }
        }
    }

    // Store the latest sample for the next poll.
    for l in &candidates {
        tracker.layers.insert(
            l.name.clone(),
            LayerSample {
                frames: l.total_frames,
                at: now,
                hist: l.histogram_p2p.clone(),
            },
        );
    }

    let Some((best_layer, d_frames, mut fps_val, delta_hist)) = best else {
        tracker.stale_polls += 1;
        if tracker.stale_polls >= 2 {
            reset_timestats();
            tracker.layers.clear();
            tracker.last_active_layer.clear();
            tracker.stale_polls = 0;
        }
        return None;
    };

    // No new frames means the scene is static or the counters froze. Never
    // fall back to a cumulative average: that is what produced a fake
    // constant "60 FPS" reading.
    if d_frames == 0 {
        tracker.stale_polls += 1;
        if tracker.stale_polls >= 2 {
            reset_timestats();
            tracker.layers.clear();
            tracker.last_active_layer.clear();
            tracker.stale_polls = 0;
        }
        return None;
    }

    tracker.stale_polls = 0;
    tracker.last_active_layer = best_layer.name.clone();

    fps_val = (fps_val * 10.0).round() / 10.0;
    let frame_time_ms = if fps_val > 0.0 {
        ((1000.0 / fps_val) * 10.0).round() / 10.0
    } else {
        0.0
    };

    // P1 low from the frames of this window only, clamped so it can never be
    // higher than the measured FPS.
    let total_samples: u64 = delta_hist.values().map(|&c| c as u64).sum();
    let p1_low = if total_samples >= 5 {
        let threshold = (total_samples as f64 * 0.99).ceil() as u64;
        let mut keys: Vec<u32> = delta_hist.keys().copied().collect();
        keys.sort_unstable();
        let mut accum = 0u64;
        let mut p99_ms = 16u32;
        for ms in keys {
            accum += delta_hist[&ms] as u64;
            if accum >= threshold {
                p99_ms = ms;
                break;
            }
        }
        if p99_ms > 0 {
            (1000.0 / p99_ms as f64).min(fps_val)
        } else {
            fps_val
        }
    } else {
        fps_val
    };

    let janks: u32 = delta_hist
        .iter()
        .filter(|(ms, _)| **ms > 16)
        .map(|(_, cnt)| *cnt)
        .sum();

    Some(FpsReport {
        package: pkg.to_string(),
        available: true,
        fps: Some(fps_val),
        frame_time_ms: Some(frame_time_ms),
        p1_low_fps: Some((p1_low * 10.0).round() / 10.0),
        jank_count: janks,
        total_frames: best_layer.total_frames as u32,
    })
}

fn measure_fps_gfxinfo(pkg: &str) -> FpsReport {
    let out = run_cmd("dumpsys", &["gfxinfo", pkg, "framestats"]);
    let mut frame_durations_ns: Vec<u64> = Vec::new();
    let mut in_stats = false;
    let mut col_start = 10;
    let mut col_end = 17;

    for line in out.lines() {
        if line.starts_with("Flags,") {
            in_stats = true;
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
    let janks = frame_durations_ns
        .iter()
        .filter(|&&d| d > 16_666_666)
        .count() as u32;

    let sum_ns: u64 = frame_durations_ns.iter().sum();
    let avg_ns = sum_ns as f64 / total as f64;
    let avg_ms = avg_ns / 1_000_000.0;
    let fps = if avg_ms > 0.0 { 1000.0 / avg_ms } else { 0.0 };

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
        p1_low_fps: Some(((p1_low_fps.min(fps)) * 10.0).round() / 10.0),
        jank_count: janks,
        total_frames: total,
    }
}

pub fn measure_fps(pkg: &str) -> FpsReport {
    if pkg.is_empty() {
        return FpsReport::default();
    }

    // SurfaceFlinger TimeStats captures SurfaceView / Unity / Vulkan games.
    if let Some(rep) = measure_fps_timestats(pkg) {
        return rep;
    }

    // Fallback for HWUI (View-based) games.
    measure_fps_gfxinfo(pkg)
}
