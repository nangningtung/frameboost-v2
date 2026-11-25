use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::sysfs::run_cmd;

/// Frame interval of the panel, in milliseconds, read once per daemon run.
/// Jank must be judged against the real vsync period: on a 60 Hz panel the
/// histogram buckets 16 and 17 ms are both on time, so a fixed 16 ms cut-off
/// reported half of a healthy 60 FPS game as jank.
fn vsync_ms() -> f64 {
    static VSYNC: OnceLock<f64> = OnceLock::new();
    *VSYNC.get_or_init(|| {
        let out = run_cmd("dumpsys", &["display"]);
        for line in out.lines() {
            let Some(idx) = line.find("renderFrameRate ") else {
                continue;
            };
            let rest = &line[idx + "renderFrameRate ".len()..];
            let num: String = rest
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect();
            if let Ok(hz) = num.parse::<f64>() {
                if (30.0..=240.0).contains(&hz) {
                    return 1000.0 / hz;
                }
            }
        }
        1000.0 / 60.0
    })
}

/// The frame time that half the frames beat, i.e. the cadence the game is
/// actually running at. A locked 30 FPS game takes ~33 ms per frame; judging it
/// against a 60 Hz vsync made every single frame look like jank.
fn typical_frame_for(hist: &HashMap<u32, u32>, fallback_ms: f64) -> f64 {
    let total: u64 = hist.values().map(|&c| c as u64).sum();
    if total == 0 {
        return fallback_ms;
    }
    let half = (total as f64 * 0.5).ceil() as u64;
    let mut keys: Vec<u32> = hist.keys().copied().collect();
    keys.sort_unstable();
    let mut accum = 0u64;
    for ms in keys {
        accum += hist[&ms] as u64;
        if accum >= half {
            return ms as f64;
        }
    }
    fallback_ms
}

/// Jank is a frame that takes 50% longer than the game's own cadence, with the
/// panel period as the floor. Works for 30, 45 and 60 FPS targets alike.
fn jank_cut_for(hist: &HashMap<u32, u32>, vsync: f64) -> f64 {
    (typical_frame_for(hist, vsync) * 1.5).max(vsync)
}

fn jank_cut_ms(hist: &HashMap<u32, u32>) -> f64 {
    jank_cut_for(hist, vsync_ms())
}

/// Frames slower than the cut. Kept separate so the rule can be tested
/// without a real panel.
fn jank_frames(hist: &HashMap<u32, u32>, cut_ms: f64) -> u32 {
    hist.iter()
        .filter(|(ms, _)| **ms as f64 > cut_ms)
        .map(|(_, cnt)| *cnt)
        .sum()
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct FpsReport {
    pub package: String,
    pub available: bool,
    pub fps: Option<f64>,
    pub frame_time_ms: Option<f64>,
    pub p1_low_fps: Option<f64>,
    pub jank_count: u32,
    pub total_frames: u32,
    /// Frames presented in this measurement window. `total_frames` is the
    /// layer's cumulative counter and resets when the layer changes, so the
    /// session report must use this value for jank percentages.
    pub window_frames: u32,
    /// 99th percentile frame time for this window, in milliseconds.
    pub p99_frame_ms: Option<f64>,
    /// Age of a reused reading, in milliseconds. Zero means the numbers were
    /// measured by this very call. Non-zero only happens when a caller asked
    /// again before a new TimeStats window existed.
    pub age_ms: u64,
    /// Frame-time histogram for this window, in integer milliseconds. Kept
    /// out of the JSON payload; the session engine merges it to compute a real
    /// session-wide 1% low instead of averaging per-window worst frames.
    #[serde(skip)]
    pub hist: HashMap<u32, u32>,
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

/// Two dumps closer together than this describe almost the same frames, so the
/// frame delta is not a rate yet.
const MIN_WINDOW_S: f64 = 0.2;

/// A window longer than this is not a rate either: the layer was missing from
/// the dump (activity switch, screen off) or the daemon was blocked. Dividing
/// one frame by a 20 s gap printed `0.0 FPS` for a game that had simply moved
/// to another screen.
const MAX_WINDOW_S: f64 = 3.0;

/// How long a computed reading may be reused when a caller asks again before a
/// new window exists. The snapshot loop polls once a second and the socket
/// `fps` command can land right behind it; returning null there reported a
/// paused game that was actually drawing frames.
const REPORT_TTL: Duration = Duration::from_secs(2);

/// What one TimeStats poll produced. `Pending` is not a failure: it means the
/// package does have SurfaceFlinger layers, there is just no new window yet.
/// Only `NoLayer` may fall through to the HWUI path, because a SurfaceView game
/// has no useful `gfxinfo framestats` and the fallback used to replace a real
/// reading with null.
enum TimestatsPoll {
    Report(FpsReport),
    Pending,
    NoLayer,
}

#[derive(Default)]
struct FpsTracker {
    last_pkg: String,
    layers: HashMap<String, LayerSample>,
    last_active_layer: String,
    stale_polls: u32,
    timestats_enabled: bool,
    last_report: Option<(FpsReport, Instant)>,
}

impl FpsTracker {
    /// The last real reading, if it is still recent enough to stand in for a
    /// poll that arrived too soon. Never invents a number.
    fn cached(&self) -> Option<FpsReport> {
        let (report, at) = self.last_report.as_ref()?;
        let age = at.elapsed();
        if age > REPORT_TTL {
            return None;
        }
        let mut out = report.clone();
        out.age_ms = age.as_millis() as u64;
        Some(out)
    }
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
        let Some((k, v)) = token.split_once('=') else {
            continue;
        };
        // Only real histogram buckets count. Other `key=value` lines in the
        // dump (`averageFPS=...`) used to land in the 0 ms bucket and skew the
        // 1% low.
        let Some(ms_str) = k.strip_suffix("ms") else {
            continue;
        };
        let Ok(ms) = ms_str.parse::<u32>() else {
            continue;
        };
        let Ok(cnt) = v.parse::<u32>() else {
            continue;
        };
        if ms > 0 && cnt > 0 {
            *hist.entry(ms).or_insert(0) += cnt;
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

/// Window for one layer, or `None` when the gap is outside the usable range.
/// `None` with a short gap means "keep the old baseline and ask again later";
/// `None` with a long gap means "re-baseline". Both cases are handled by the
/// caller so neither can fabricate a rate.
fn layer_window(
    prev: &LayerSample,
    dump: &LayerDump,
    d_sec: f64,
) -> Option<(u64, f64, HashMap<u32, u32>)> {
    if !(MIN_WINDOW_S..=MAX_WINDOW_S).contains(&d_sec) {
        return None;
    }
    let d_frames = dump.total_frames.saturating_sub(prev.frames);
    let fps = (d_frames as f64 / d_sec).min(120.0);
    Some((
        d_frames,
        fps,
        diff_histogram(&dump.histogram_p2p, &prev.hist),
    ))
}

fn measure_fps_timestats(pkg: &str) -> TimestatsPoll {
    let mut guard = TRACKER.lock().unwrap();
    let tracker = guard.get_or_insert_with(|| {
        enable_timestats();
        FpsTracker {
            last_pkg: String::new(),
            layers: HashMap::new(),
            last_active_layer: String::new(),
            stale_polls: 0,
            timestats_enabled: true,
            last_report: None,
        }
    });

    if tracker.last_pkg != pkg {
        reset_timestats();
        tracker.last_pkg = pkg.to_string();
        tracker.layers.clear();
        tracker.last_active_layer.clear();
        tracker.stale_polls = 0;
        tracker.timestats_enabled = true;
        tracker.last_report = None;
        // First sample after a reset has no baseline; wait for the next poll.
        return TimestatsPoll::Pending;
    }

    if !tracker.timestats_enabled {
        enable_timestats();
        tracker.timestats_enabled = true;
    }

    let out = run_cmd("dumpsys", &["SurfaceFlinger", "--timestats", "-dump"]);
    if out.is_empty() {
        // A transient binder miss must not demote a SurfaceView game to the
        // HWUI path.
        return match tracker.cached() {
            Some(rep) => TimestatsPoll::Report(rep),
            None => TimestatsPoll::Pending,
        };
    }

    let layers = parse_layers(&out, pkg);
    if layers.is_empty() {
        // If this package has produced layers before, the empty dump is a
        // re-registration gap, not a HWUI game.
        if tracker.last_active_layer.is_empty() && tracker.last_report.is_none() {
            return TimestatsPoll::NoLayer;
        }
        return match tracker.cached() {
            Some(rep) => TimestatsPoll::Report(rep),
            None => TimestatsPoll::Pending,
        };
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
    // Baseline updates are deferred so a poll that arrives too soon can leave
    // the older baseline in place. Overwriting it on every call meant a steady
    // stream of `fps` requests never accumulated a window: each one reset the
    // clock, the cached reading aged out after two seconds, and the daemon
    // reported null while the game was still drawing.
    let mut updates: Vec<(String, LayerSample)> = Vec::new();

    for l in &candidates {
        match tracker.layers.get(&l.name) {
            None => {
                // First sighting: record a baseline only.
                updates.push((
                    l.name.clone(),
                    LayerSample {
                        frames: l.total_frames,
                        at: now,
                        hist: l.histogram_p2p.clone(),
                    },
                ));
            }
            Some(prev) => {
                let d_sec = prev.at.elapsed().as_secs_f64();
                let baseline = LayerSample {
                    frames: l.total_frames,
                    at: now,
                    hist: l.histogram_p2p.clone(),
                };
                let Some((d_frames, fps, dhist)) = layer_window(prev, l, d_sec) else {
                    // Too soon: keep the older baseline so the window grows.
                    // Too late: the gap is not a rate, so start over.
                    if d_sec > MAX_WINDOW_S {
                        updates.push((l.name.clone(), baseline));
                    }
                    continue;
                };
                let better = match &best {
                    Some((_, best_frames, _, _)) => d_frames > *best_frames,
                    None => true,
                };
                if better {
                    best = Some((l.clone(), d_frames, fps, dhist));
                }
                updates.push((l.name.clone(), baseline));
            }
        }
    }

    for (name, sample) in updates {
        tracker.layers.insert(name, sample);
    }

    let Some((best_layer, d_frames, mut fps_val, delta_hist)) = best else {
        // No candidate had a full window yet: either the first poll after a
        // reset, or a caller that arrived right behind the snapshot loop.
        // Reuse the last real reading instead of reporting null.
        return match tracker.cached() {
            Some(rep) => TimestatsPoll::Report(rep),
            None => TimestatsPoll::Pending,
        };
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
            tracker.last_report = None;
            tracker.stale_polls = 0;
        }
        return TimestatsPoll::Pending;
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
    // higher than the measured FPS. A window with only a few frames still has
    // a real worst frame; skipping it left p99 at a fake 16 ms.
    let total_samples: u64 = delta_hist.values().map(|&c| c as u64).sum();
    let mut p99_ms = frame_time_ms.round() as u32;
    if total_samples > 0 {
        let threshold = (total_samples as f64 * 0.99).ceil() as u64;
        let mut keys: Vec<u32> = delta_hist.keys().copied().collect();
        keys.sort_unstable();
        let mut accum = 0u64;
        for ms in keys {
            accum += delta_hist[&ms] as u64;
            if accum >= threshold {
                p99_ms = ms;
                break;
            }
        }
    }
    let p1_low = if p99_ms > 0 {
        (1000.0 / p99_ms as f64).min(fps_val)
    } else {
        fps_val
    };

    let jank_cut = jank_cut_ms(&delta_hist);
    let janks = jank_frames(&delta_hist, jank_cut);

    let report = FpsReport {
        package: pkg.to_string(),
        available: true,
        fps: Some(fps_val),
        frame_time_ms: Some(frame_time_ms),
        p1_low_fps: Some((p1_low * 10.0).round() / 10.0),
        jank_count: janks,
        total_frames: best_layer.total_frames as u32,
        window_frames: d_frames as u32,
        p99_frame_ms: Some(p99_ms as f64),
        age_ms: 0,
        hist: delta_hist,
    };
    tracker.last_report = Some((report.clone(), Instant::now()));
    TimestatsPoll::Report(report)
}

/// `gfxinfo framestats` reports every frame since the last reset, so two polls
/// describe the same frames twice. This tracker keeps the previous totals and
/// turns each poll into a window delta, the same shape the TimeStats path
/// returns. Without it the session report added the same frames again on every
/// poll.
#[derive(Default)]
struct GfxTracker {
    pkg: String,
    total: u32,
    hist: HashMap<u32, u32>,
    at: Option<Instant>,
    last_report: Option<(FpsReport, Instant)>,
}

impl GfxTracker {
    fn cached(&self) -> Option<FpsReport> {
        let (report, at) = self.last_report.as_ref()?;
        let age = at.elapsed();
        if age > REPORT_TTL {
            return None;
        }
        let mut out = report.clone();
        out.age_ms = age.as_millis() as u64;
        Some(out)
    }
}

static GFX_TRACKER: Mutex<Option<GfxTracker>> = Mutex::new(None);

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
            ..Default::default()
        };
    }

    let total = frame_durations_ns.len() as u32;

    let mut hist: HashMap<u32, u32> = HashMap::new();
    for &d in &frame_durations_ns {
        let ms = ((d / 1_000_000).max(1)) as u32;
        *hist.entry(ms).or_insert(0) += 1;
    }

    let sum_ns: u64 = frame_durations_ns.iter().sum();
    let avg_ms = sum_ns as f64 / total as f64;
    let cumulative_fps = if avg_ms > 0.0 { 1000.0 / avg_ms } else { 0.0 };

    let now = Instant::now();
    let mut guard = GFX_TRACKER.lock().unwrap();
    let tr = guard.get_or_insert_with(GfxTracker::default);

    // A new package or a shrinking counter means the stats were reset.
    let reset = tr.pkg != pkg || tr.total == 0 || total < tr.total;
    let window_s = match tr.at {
        Some(at) if !reset => at.elapsed().as_secs_f64(),
        _ => 0.0,
    };

    // A poll that lands right behind the previous one has no usable rate.
    // Reuse the last real reading and keep the older baseline, so the window
    // keeps growing instead of being reset by every call.
    if !reset && window_s < MIN_WINDOW_S {
        return tr.cached().unwrap_or_else(|| pending_report(pkg));
    }

    let (d_frames, d_hist) = if reset {
        (total, hist.clone())
    } else {
        (total - tr.total, diff_histogram(&hist, &tr.hist))
    };

    tr.pkg = pkg.to_string();
    tr.total = total;
    tr.hist = hist;
    tr.at = Some(now);

    if d_frames == 0 {
        // No new frames since the last poll. Report nothing instead of the
        // cumulative average again, which would look like a live reading.
        return pending_report(pkg);
    }

    let fps_val = if window_s >= MIN_WINDOW_S {
        ((d_frames as f64 / window_s).min(120.0) * 10.0).round() / 10.0
    } else {
        // First poll after a reset: the counter covers every frame since the
        // last reset, so the cumulative average is the only rate available.
        (cumulative_fps * 10.0).round() / 10.0
    };
    let frame_time_ms = if fps_val > 0.0 {
        ((1000.0 / fps_val) * 10.0).round() / 10.0
    } else {
        0.0
    };

    let total_samples: u64 = d_hist.values().map(|&c| c as u64).sum();
    let mut p99_ms = frame_time_ms.round() as u32;
    if total_samples > 0 {
        let threshold = (total_samples as f64 * 0.99).ceil() as u64;
        let mut keys: Vec<u32> = d_hist.keys().copied().collect();
        keys.sort_unstable();
        let mut accum = 0u64;
        for ms in keys {
            accum += d_hist[&ms] as u64;
            if accum >= threshold {
                p99_ms = ms;
                break;
            }
        }
    }
    let p1_low = if p99_ms > 0 {
        (1000.0 / p99_ms as f64).min(fps_val)
    } else {
        fps_val
    };

    let report = FpsReport {
        package: pkg.to_string(),
        available: true,
        fps: Some(fps_val),
        frame_time_ms: Some(frame_time_ms),
        p1_low_fps: Some((p1_low * 10.0).round() / 10.0),
        jank_count: jank_frames(&d_hist, jank_cut_ms(&d_hist)),
        total_frames: total,
        window_frames: d_frames,
        p99_frame_ms: Some(p99_ms as f64),
        age_ms: 0,
        hist: d_hist,
    };
    tr.last_report = Some((report.clone(), Instant::now()));
    report
}

/// A poll that found no new frames: the package is known, the rate is not.
fn pending_report(pkg: &str) -> FpsReport {
    FpsReport {
        package: pkg.to_string(),
        available: true,
        ..Default::default()
    }
}

pub fn measure_fps(pkg: &str) -> FpsReport {
    if pkg.is_empty() {
        return FpsReport::default();
    }

    // SurfaceFlinger TimeStats captures SurfaceView / Unity / Vulkan games.
    // Only a package with no layer at all may use the HWUI fallback: a
    // SurfaceView game has no useful `gfxinfo framestats`, and falling through
    // on every empty window replaced a real reading with null.
    match measure_fps_timestats(pkg) {
        TimestatsPoll::Report(rep) => rep,
        TimestatsPoll::Pending => pending_report(pkg),
        TimestatsPoll::NoLayer => measure_fps_gfxinfo(pkg),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hist(pairs: &[(u32, u32)]) -> HashMap<u32, u32> {
        pairs.iter().copied().collect()
    }

    #[test]
    fn histogram_parser_ignores_non_bucket_keys() {
        let mut h = HashMap::new();
        parse_histogram(&mut h, "averageFPS=59.2 16ms=5 17ms=0 0ms=9 33ms=x 24ms=2");
        assert_eq!(h.get(&16), Some(&5));
        assert_eq!(h.get(&24), Some(&2));
        assert!(!h.contains_key(&0), "a 0 ms bucket must never be recorded");
        assert!(!h.contains_key(&17), "zero counts carry no information");
        assert_eq!(h.len(), 2);
    }

    #[test]
    fn layer_parser_keeps_only_live_layers_of_the_package() {
        let dump = r#"
layerName = com.mobile.legends/com.mobile.legends.MainActivity#0
packageName = com.mobile.legends
totalFrames = 500
present2present histogram:
  16ms=300
  17ms=150
  33ms=50
averageFPS = 59.2
layerName = SurfaceView[com.mobile.legends/com.mobile.legends.MainActivity]#1
totalFrames = 900
present2present histogram:
  16ms=800
  33ms=100
layerName = com.other.app/com.other.app.MainActivity#2
totalFrames = 77
present2present histogram:
  16ms=77
layerName = com.mobile.legends/com.mobile.legends.Splash#3
totalFrames = 0
present2present histogram:
  16ms=4
"#;
        let layers = parse_layers(dump, "com.mobile.legends");
        assert_eq!(layers.len(), 2, "foreign and frame-less layers are dropped");
        assert_eq!(layers[0].total_frames, 500);
        assert_eq!(
            layers[0].histogram_p2p,
            hist(&[(16, 300), (17, 150), (33, 50)])
        );
        assert_eq!(layers[1].total_frames, 900);
        assert_eq!(layers[1].histogram_p2p, hist(&[(16, 800), (33, 100)]));
    }

    #[test]
    fn diff_histogram_only_counts_new_frames() {
        let now = hist(&[(16, 5), (33, 1)]);
        let prev = hist(&[(16, 3), (20, 4)]);
        assert_eq!(diff_histogram(&now, &prev), hist(&[(16, 2), (33, 1)]));
    }

    #[test]
    fn a_60hz_game_is_not_jank() {
        // 16 and 17 ms are both on time for a 60 Hz panel.
        let h = hist(&[(16, 60), (17, 40)]);
        let cut = jank_cut_for(&h, 1000.0 / 60.0);
        assert!((cut - 24.0).abs() < 0.01, "cut was {}", cut);
        assert_eq!(jank_frames(&h, cut), 0);
    }

    #[test]
    fn a_locked_30fps_game_is_not_jank() {
        // Judging 33 ms frames against 60 Hz used to mark every frame.
        let h = hist(&[(33, 95), (50, 5)]);
        let cut = jank_cut_for(&h, 1000.0 / 60.0);
        assert!((cut - 49.5).abs() < 0.01, "cut was {}", cut);
        assert_eq!(jank_frames(&h, cut), 5, "only the 50 ms frames are late");
    }

    #[test]
    fn empty_histogram_falls_back_to_vsync() {
        let h = HashMap::new();
        assert!((typical_frame_for(&h, 16.0) - 16.0).abs() < 0.01);
        assert!((jank_cut_for(&h, 16.0) - 24.0).abs() < 0.01);
    }

    #[test]
    fn a_recent_reading_is_reused_and_an_old_one_is_not() {
        // A socket `fps` call that lands right behind the snapshot loop used to
        // return null because the frame window was too short. Reusing the last
        // real reading fixes that, but it must expire.
        let report = FpsReport {
            package: "com.mobile.legends".to_string(),
            available: true,
            fps: Some(59.4),
            ..Default::default()
        };

        let fresh = FpsTracker {
            last_report: Some((report.clone(), Instant::now() - Duration::from_millis(150))),
            ..Default::default()
        };
        let cached = fresh.cached().expect("a 150 ms old reading is still valid");
        assert_eq!(cached.fps, Some(59.4));
        assert!(cached.age_ms >= 150, "age was {}", cached.age_ms);

        let stale = FpsTracker {
            last_report: Some((report, Instant::now() - Duration::from_secs(5))),
            ..Default::default()
        };
        assert!(
            stale.cached().is_none(),
            "a five second old reading must never look live"
        );
    }

    #[test]
    fn a_too_soon_poll_keeps_the_previous_baseline() {
        let dump = LayerDump {
            name: "SurfaceView[com.mobile.legends]#1".to_string(),
            total_frames: 103,
            histogram_p2p: hist(&[(16, 103)]),
        };
        let prev = LayerSample {
            frames: 100,
            at: Instant::now(),
            hist: hist(&[(16, 100)]),
        };

        // 50 ms after the last sample is not a window: the caller must keep
        // the old baseline instead of resetting it, or a stream of `fps`
        // requests would never produce a number.
        assert!(
            layer_window(&prev, &dump, 0.05).is_none(),
            "50 ms must not be treated as a window"
        );

        // A 30 s gap is not a rate either: one frame over that gap printed
        // `0.0 FPS` for a game that had just changed screens.
        assert!(
            layer_window(&prev, &dump, 30.0).is_none(),
            "a 30 s gap must not be divided into a frame rate"
        );

        // 300 ms with three new frames is a real window.
        let (frames, fps, dhist) =
            layer_window(&prev, &dump, 0.3).expect("300 ms is a window");
        assert_eq!(frames, 3);
        assert!((fps - 10.0).abs() < 0.01, "fps was {}", fps);
        assert_eq!(dhist, hist(&[(16, 3)]));
    }

    #[test]
    fn a_pending_poll_reports_no_rate_but_stays_available() {
        let rep = pending_report("com.mobile.legends");
        assert!(rep.available, "the package is known");
        assert_eq!(rep.fps, None, "no window means no number");
        assert_eq!(rep.age_ms, 0);
    }
}
