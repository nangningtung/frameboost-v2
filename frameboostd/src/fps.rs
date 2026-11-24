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

struct FpsTracker {
    last_pkg: String,
    layers: HashMap<String, (u64, Instant)>,
    last_active_layer: String,
}

static TRACKER: Mutex<Option<FpsTracker>> = Mutex::new(None);

#[derive(Default, Clone)]
struct LayerDump {
    name: String,
    total_frames: u64,
    avg_fps: f64,
    histogram_p2p: Vec<(u32, u32)>, // (ms, count)
}

fn measure_fps_timestats(pkg: &str) -> Option<FpsReport> {
    let now = Instant::now();
    let mut tracker_guard = TRACKER.lock().unwrap();
    let tracker = tracker_guard.get_or_insert_with(|| FpsTracker {
        last_pkg: String::new(),
        layers: HashMap::new(),
        last_active_layer: String::new(),
    });

    if tracker.last_pkg != pkg {
        run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-clear"]);
        tracker.last_pkg = pkg.to_string();
        tracker.layers.clear();
        tracker.last_active_layer.clear();
    }

    let out = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-dump"]);
    if out.is_empty() {
        return None;
    }

    let mut layers: Vec<LayerDump> = Vec::new();
    let mut current_layer: Option<LayerDump> = None;
    let mut in_histogram = false;

    for line in out.lines() {
        let trimmed = line.trim();
        if let Some(name) = trimmed.strip_prefix("layerName = ") {
            if let Some(l) = current_layer.take() {
                if l.name.contains(pkg) && l.total_frames > 0 {
                    layers.push(l);
                }
            }
            current_layer = Some(LayerDump {
                name: name.to_string(),
                ..Default::default()
            });
            in_histogram = false;
            continue;
        }

        if let Some(ref mut l) = current_layer {
            if let Some(tf_str) = trimmed.strip_prefix("totalFrames = ") {
                l.total_frames = tf_str.parse().unwrap_or(0);
            } else if let Some(fps_str) = trimmed.strip_prefix("averageFPS = ") {
                l.avg_fps = fps_str.parse().unwrap_or(0.0);
            } else if trimmed.starts_with("present2present histogram") {
                in_histogram = true;
            } else if in_histogram {
                if trimmed.contains('=') {
                    for token in trimmed.split_whitespace() {
                        if let Some((k, v)) = token.split_once('=') {
                            let ms = k.trim_end_matches("ms").parse().unwrap_or(0);
                            let cnt = v.parse().unwrap_or(0);
                            if cnt > 0 {
                                l.histogram_p2p.push((ms, cnt));
                            }
                        }
                    }
                } else if trimmed.ends_with(':') {
                    in_histogram = false;
                }
            }
        }
    }

    if let Some(l) = current_layer {
        if l.name.contains(pkg) && l.total_frames > 0 {
            layers.push(l);
        }
    }

    if layers.is_empty() {
        return None;
    }

    // Games render to SurfaceView / Surface layers; splash screens and window containers
    // only draw a handful of static frames. Prefer SurfaceView whenever present.
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

    // Evaluate frame delta for each layer to find which one is actively rendering
    let mut evaluated: Vec<(LayerDump, u64, f64)> = Vec::new(); // (layer, d_frames, fps)
    for l in &candidates {
        if let Some(&(prev_frames, prev_time)) = tracker.layers.get(&l.name) {
            let d_frames = l.total_frames.saturating_sub(prev_frames);
            let d_sec = prev_time.elapsed().as_secs_f64();
            let calc_fps = if d_sec >= 0.2 {
                (d_frames as f64 / d_sec).min(120.0)
            } else {
                l.avg_fps
            };
            evaluated.push((l.clone(), d_frames, calc_fps));
        } else {
            // First time seeing this layer
            evaluated.push((l.clone(), 0, l.avg_fps));
        }
    }

    // Update tracker with latest frame counts
    for l in &candidates {
        tracker.layers.insert(l.name.clone(), (l.total_frames, now));
    }

    // Choose the best layer:
    // 1. Layer with highest d_frames > 0 (actively pushing new frames)
    // 2. Or layer matching last_active_layer
    // 3. Or layer with highest total_frames
    let best_tuple = evaluated
        .iter()
        .filter(|(_, d, _)| *d > 0)
        .max_by_key(|(_, d, _)| *d)
        .or_else(|| {
            evaluated
                .iter()
                .find(|(l, _, _)| l.name == tracker.last_active_layer)
        })
        .or_else(|| evaluated.iter().max_by_key(|(l, _, _)| l.total_frames))?;

    let (best, d_frames, mut fps_val) = best_tuple.clone();
    tracker.last_active_layer = best.name.clone();

    // If d_frames was 0 (first sample) and fps_val is 0.0, fallback to best.avg_fps
    if d_frames == 0 && fps_val == 0.0 && best.avg_fps > 0.0 {
        fps_val = best.avg_fps;
    }

    fps_val = (fps_val * 10.0).round() / 10.0;
    let ft_ms = if fps_val > 0.0 {
        ((1000.0 / fps_val) * 10.0).round() / 10.0
    } else {
        0.0
    };

    let mut total_samples = 0u64;
    for &(_, cnt) in &best.histogram_p2p {
        total_samples += cnt as u64;
    }

    let p1_low = if total_samples > 0 {
        let p99_threshold = (total_samples as f64 * 0.99) as u64;
        let mut accum = 0u64;
        let mut p99_ms = 16u32;
        let mut sorted_hist = best.histogram_p2p.clone();
        sorted_hist.sort_by_key(|&(ms, _)| ms);
        for &(ms, cnt) in &sorted_hist {
            accum += cnt as u64;
            if accum >= p99_threshold {
                p99_ms = ms;
                break;
            }
        }
        if p99_ms > 0 {
            ((1000.0 / p99_ms as f64) * 10.0).round() / 10.0
        } else {
            fps_val
        }
    } else {
        fps_val
    };

    let janks: u32 = best
        .histogram_p2p
        .iter()
        .filter(|&&(ms, _)| ms > 16)
        .map(|&(_, cnt)| cnt)
        .sum();

    Some(FpsReport {
        package: pkg.to_string(),
        available: true,
        fps: Some(fps_val),
        frame_time_ms: Some(ft_ms),
        p1_low_fps: Some(p1_low),
        jank_count: janks,
        total_frames: best.total_frames as u32,
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
        p1_low_fps: Some((p1_low_fps * 10.0).round() / 10.0),
        jank_count: janks,
        total_frames: total,
    }
}

pub fn measure_fps(pkg: &str) -> FpsReport {
    if pkg.is_empty() {
        return FpsReport::default();
    }

    // Try SurfaceFlinger TimeStats first (captures SurfaceView / Unity / Vulkan games)
    if let Some(rep) = measure_fps_timestats(pkg) {
        if rep.fps.is_some() {
            return rep;
        }
    }

    // Fallback to HWUI gfxinfo framestats
    measure_fps_gfxinfo(pkg)
}
