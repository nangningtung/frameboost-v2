use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};

use crate::apply::{apply_profile, apply_profile_if_drifted, BoostProfile};
use crate::detect::GameDetector;
use crate::net::{apply_bbr, restore_stock_cc};
use crate::profile::GameProfile;
use crate::rollback::restore_all;
use crate::sysfs::{log_line, read_u32, run_cmd};

const POLL_MS: u64 = 250;
const DEBOUNCE_MISSES: u32 = 2; // Instant for launcher/system, 2 x 250ms = 500ms for other non-game apps

const TEMP_WARN_ON: u32 = 87000; // only throttle when really hot; Samsung HAL caps earlier
const TEMP_WARN_OFF: u32 = 82000; // 5C band prevents profile flapping around the threshold
const TEMP_CRIT_ON: u32 = 92000;
const TEMP_CRIT_OFF: u32 = 80000;

/// How often the daemon re-checks the platform against the active profile.
/// Samsung resets the GPU/CPU knobs a few seconds after they are written, so
/// this keep-alive is what makes a preset actually stick.
const KEEPALIVE_SECS: u64 = 5;

/// Set by the console's "end session" action. While this matches the
/// foreground package, Secreto stays out of the way so keep-alive cannot
/// re-apply a preset the user just removed.
static PAUSED_PKG: Mutex<String> = Mutex::new(String::new());

pub fn pause_current(pkg: &str) {
    let mut p = PAUSED_PKG.lock().unwrap_or_else(|e| e.into_inner());
    if pkg.is_empty() {
        p.clear();
    } else {
        *p = pkg.to_string();
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum DaemonState {
    Idle = 0,
    Game = 1,
    Warn = 2,
    Crit = 3,
}

impl DaemonState {
    pub fn from_u8(v: u8) -> Self {
        match v {
            1 => DaemonState::Game,
            2 => DaemonState::Warn,
            3 => DaemonState::Crit,
            _ => DaemonState::Idle,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            DaemonState::Idle => "IDLE",
            DaemonState::Game => "GAME",
            DaemonState::Warn => "WARN",
            DaemonState::Crit => "CRIT",
        }
    }
}

pub struct AutoDaemon {
    detector: Arc<RwLock<GameDetector>>,
    state: DaemonState,
    current_game: String,
    miss_count: u32,
    active_profile: Option<GameProfile>,
    last_keepalive: Instant,
    extras_pkg: String,
    hud_miss: u32,
    apply_fail_streak: u32,
    screen_off_since: Option<Instant>,
    standby_active: bool,
    last_hud_poll: Instant,
    sf_check_skip: u32,
}

impl AutoDaemon {
    pub fn new(detector: Arc<RwLock<GameDetector>>) -> Self {
        Self {
            detector,
            state: DaemonState::Idle,
            current_game: String::new(),
            miss_count: 0,
            active_profile: None,
            last_keepalive: Instant::now(),
            extras_pkg: String::new(),
            hud_miss: 0,
            apply_fail_streak: 0,
            screen_off_since: None,
            standby_active: false,
            last_hud_poll: Instant::now(),
            sf_check_skip: 0,
        }
    }

    pub fn tick(&mut self) -> DaemonState {
        // Powercfg contract: consume a pending external mode request and
        // re-apply the profile if the global override changed mid-session.
        if crate::powercfg::consume().is_some() && !self.current_game.is_empty() {
            self.active_profile = Some(GameProfile::for_package(&self.current_game));
            self.apply_state(self.state);
        }

        let fg = GameDetector::get_foreground_pkg();

        // A manual restore pauses Secreto for this game until it leaves the
        // foreground; otherwise keep-alive would undo the restore in 5s.
        {
            let mut paused = PAUSED_PKG.lock().unwrap_or_else(|e| e.into_inner());
            if !paused.is_empty() && *paused != fg {
                paused.clear();
            }
        }
        let paused = {
            let p = PAUSED_PKG.lock().unwrap_or_else(|e| e.into_inner());
            !p.is_empty() && *p == fg
        };

        // A paused game drops to Idle on this very tick. Waiting for the
        // debounce would keep `in_game` true for one tick and start a brand
        // new session right after the user ended the last one.
        if paused {
            self.miss_count = DEBOUNCE_MISSES;
            self.current_game.clear();
        }

        let fast_check = {
            let det = self.detector.read().unwrap_or_else(|e| e.into_inner());
            det.is_game_fast(&fg)
        };
        let is_game = if paused {
            false
        } else {
            match fast_check {
                Some(val) => val,
                None => self.detector.write().unwrap_or_else(|e| e.into_inner()).is_game(&fg),
            }
        };
        let temp_big = read_u32("/sys/class/thermal/thermal_zone0/temp");

        if is_game {
            self.miss_count = 0;
            if self.current_game != fg {
                self.current_game = fg.clone();
                self.active_profile = Some(GameProfile::for_package(&fg));
            }
        } else {
            // Instant exit when user switches to launcher / system UI / home screen
            if GameDetector::is_system_pkg(&fg) || fg.is_empty() {
                self.miss_count = DEBOUNCE_MISSES;
                self.current_game.clear();
            } else {
                self.miss_count += 1;
                if self.miss_count >= DEBOUNCE_MISSES {
                    self.current_game.clear();
                }
            }
        }

        let in_game = is_game || (self.miss_count < DEBOUNCE_MISSES && !self.current_game.is_empty());

        // State machine evaluation
        let next_state = match self.state {
            DaemonState::Idle => {
                if in_game {
                    if temp_big >= TEMP_CRIT_ON {
                        DaemonState::Crit
                    } else if temp_big >= TEMP_WARN_ON {
                        DaemonState::Warn
                    } else {
                        DaemonState::Game
                    }
                } else {
                    DaemonState::Idle
                }
            }
            DaemonState::Game => {
                if !in_game {
                    DaemonState::Idle
                } else if temp_big >= TEMP_CRIT_ON {
                    DaemonState::Crit
                } else if temp_big >= TEMP_WARN_ON {
                    DaemonState::Warn
                } else {
                    DaemonState::Game
                }
            }
            DaemonState::Warn => {
                if !in_game {
                    DaemonState::Idle
                } else if temp_big >= TEMP_CRIT_ON {
                    DaemonState::Crit
                } else if temp_big <= TEMP_WARN_OFF {
                    DaemonState::Game
                } else {
                    DaemonState::Warn // Stay warn between 82°C and 85°C
                }
            }
            DaemonState::Crit => {
                if !in_game {
                    DaemonState::Idle
                } else if temp_big <= TEMP_CRIT_OFF {
                    DaemonState::Game // Full recovery when cooled down to <= 80°C
                } else {
                    DaemonState::Crit
                }
            }
        };

        // Transition actions
        if next_state != self.state {
            log_line(&format!(
                "State transition: {:?} -> {:?} (pkg={}, temp={}mC)",
                self.state, next_state, self.current_game, temp_big
            ));
            if matches!(next_state, DaemonState::Warn | DaemonState::Crit) {
                crate::session::note_throttle();
            }
            self.apply_state(next_state);
            self.state = next_state;
        }

        // Session lifecycle follows the game, not the thermal state, so a game
        // that starts already hot still gets its session and its sleep batch.
        if in_game && !paused && !self.current_game.is_empty() {
            let preset = self
                .active_profile
                .as_ref()
                .map(|p| p.preset.clone())
                .unwrap_or_default();
            if crate::session::start(&self.current_game, &preset) {
                crate::session::sleep_background(&self.current_game);
                crate::priority::boost(&self.current_game);
            }
        }

        // Keep the active profile pinned. Samsung's power service reverts the
        // clock knobs a few seconds after they are written, so without this
        // loop a preset only lasts a few seconds.
        if matches!(self.state, DaemonState::Game | DaemonState::Warn)
            && !self.current_game.is_empty()
            && self.last_keepalive.elapsed() >= Duration::from_secs(KEEPALIVE_SECS)
        {
            self.last_keepalive = Instant::now();
            self.sync_active_profile();
            self.keepalive_apply();
            crate::priority::reapply(&self.current_game);
            self.ensure_hud_running();
        }

        // Clean up current_game if fully dropped to idle
        if self.state == DaemonState::Idle && !in_game {
            self.current_game.clear();
            self.active_profile = None;
            self.extras_pkg.clear();
        }

        // Standby hint (uperf-style): screen off for 20 s during a game ->
        // drop floors to stock; re-apply the profile when the screen returns.
        if in_game && !crate::powercfg::screen_on() {
            let since = *self.screen_off_since.get_or_insert_with(Instant::now);
            if !self.standby_active && since.elapsed() >= Duration::from_secs(20) {
                self.standby_active = true;
                log_line("standby: screen off 20s in-game, restoring stock floors");
                let _ = restore_all();
                crate::priority::restore();
            }
        } else {
            self.screen_off_since = None;
            if self.standby_active {
                self.standby_active = false;
                log_line("standby: screen back on, re-applying profile");
                self.apply_state(self.state);
            }
        }

        // In-process HUD stats blob (hanya saat eksperimen aktif, throttle 2 s).
        if !self.current_game.is_empty()
            && crate::hud::enabled()
            && self.last_hud_poll.elapsed() >= Duration::from_secs(2)
        {
            self.last_hud_poll = Instant::now();
            let preset = self
                .active_profile
                .as_ref()
                .map(|p| p.preset.to_string())
                .unwrap_or_default();
            let info = crate::session::info();
            crate::hud::write_stats(&self.current_game, &preset, info.avg_fps);
        }

        self.state
    }

    /// Pick up preset changes made from the WebUI or side panel without
    /// waiting for the game to be restarted.
    fn sync_active_profile(&mut self) {
        let fresh = GameProfile::for_package(&self.current_game);
        let changed = match &self.active_profile {
            Some(cur) => cur.preset != fresh.preset,
            None => true,
        };
        if changed {
            self.active_profile = Some(fresh);
        }
    }

    fn keepalive_apply(&self) {
        if let Some(prof) = &self.active_profile {
            let target = if self.state == DaemonState::Warn {
                prof.warn_min
            } else {
                prof.gpu_min
            };
            let boost = BoostProfile {
                name: "KEEP".to_string(),
                gpu_gov: prof.gpu_gov.clone(),
                gpu_min: target,
                cpu0_gov: prof.cpu0_gov.clone(),
                cpu4_gov: prof.cpu4_gov.clone(),
                cpu0_min: prof.cpu0_min,
                cpu4_min: prof.cpu4_min,
                cpu0_max: prof.cpu0_max,
                cpu4_max: prof.cpu4_max,
            };
            if let Some(rep) = apply_profile_if_drifted(&boost) {
                if !rep.success {
                    log_line(&format!("keepalive re-apply failed: {:?}", rep.errors));
                }
            }
        }
    }

    /// The HUD process can be killed by an APK install or by the low-memory
    /// killer. While a game is active the daemon restarts its foreground
    /// service, pins its oom_score_adj and verifies that the overlay layer is
    /// actually present (a live service with no window is still a dead HUD).
    fn ensure_hud_running(&mut self) {
        let pids = crate::priority::pids_of("com.secreto.space");
        if pids.is_empty() {
            self.start_hud_service();
            self.hud_miss = 0;
            return;
        }

        for p in &pids {
            let _ = fs::write(format!("/proc/{}/oom_score_adj", p), "-800");
        }

        // `dumpsys SurfaceFlinger --list` is a heavy spawn: only run it every
        // 4th keep-alive (~20 s) instead of every tick; the other ticks just
        // proved the app process is alive via /proc.
        self.sf_check_skip = self.sf_check_skip.saturating_add(1);
        if self.sf_check_skip % 4 != 0 {
            return;
        }

        let layers = run_cmd("dumpsys", &["SurfaceFlinger", "--list"]);
        if layers.contains("com.secreto.space") {
            self.hud_miss = 0;
        } else {
            self.hud_miss += 1;
            if self.hud_miss >= 2 {
                self.hud_miss = 0;
                self.start_hud_service();
            }
        }
    }

    fn start_hud_service(&self) {
        let _ = run_cmd(
            "am",
            &[
                "start-foreground-service",
                "-n",
                "com.secreto.space/.OverlayService",
            ],
        );
        log_line("HUD overlay missing, restarting service");
    }

    fn apply_state(&mut self, st: DaemonState) {
        match st {
            DaemonState::Idle => {
                self.apply_fail_streak = 0;
                crate::session::finish("idle");
                crate::priority::restore();
                crate::capture::stop_if_recording("session_end");
                let _ = restore_all();
                crate::refresh_heads_up_cache();
                self.extras_pkg.clear();
                if let Some(prof) = self.active_profile.clone() {
                    if prof.auto_bbr {
                        let _ = restore_stock_cc();
                    }
                }
            }
            DaemonState::Game => {
                let Some(prof) = self.active_profile.clone() else {
                    return;
                };
                let boost = BoostProfile {
                    name: "GAME".to_string(),
                    gpu_gov: prof.gpu_gov.clone(),
                    gpu_min: prof.gpu_min,
                    cpu0_gov: prof.cpu0_gov.clone(),
                    cpu4_gov: prof.cpu4_gov.clone(),
                    cpu0_min: prof.cpu0_min,
                    cpu4_min: prof.cpu4_min,
                    cpu0_max: prof.cpu0_max,
                    cpu4_max: prof.cpu4_max,
                };
                // Fail-safe: after 3 consecutive apply failures, stop fighting
                // the knob and leave stock values until the session ends.
                // Prevents an endless write/read-fail loop from burning I/O.
                if self.apply_fail_streak >= 3 {
                    // suspended; checked again next state transition
                } else {
                    let rep = apply_profile(&boost);
                    if rep.success {
                        self.apply_fail_streak = 0;
                    } else {
                        self.apply_fail_streak += 1;
                        log_line(&format!(
                            "apply: {:?} (failure {} of 3, then suspend)",
                            rep.errors, self.apply_fail_streak
                        ));
                    }
                }

                // One-shot extras per game session. Running them again on
                // every thermal recovery just adds binder/IO churn.
                if self.extras_pkg != self.current_game {
                    self.extras_pkg = self.current_game.clone();

                    // 1. Anti-stutter RAM & ZRAM: lower swappiness from 130 -> 10 to eliminate kswapd0 thrashing
                    let _ = fs::write("/proc/sys/vm/swappiness", "10");
                    let _ = fs::write("/proc/sys/vm/vfs_cache_pressure", "50");

                    // 2. Gaming DND: block heads-up notifications from popping up over game
                    let _ = run_cmd("settings", &["put", "global", "heads_up_notifications_enabled", "0"]);
                        crate::set_heads_up_cache(true);

                    // 3. Anti-redup: lock brightness to manual mode (prevent palm from covering ambient light sensor)
                    let _ = run_cmd("settings", &["put", "system", "screen_brightness_mode", "0"]);

                    // ponytail: never drop_caches on game launch; causes eMMC I/O stalls and startup freeze
                    // 4. Renice handled by priority::boost at session start and
                    //    priority::reapply at every keep-alive tick — no fork needed here.

                    // 5. Auto-BBR for online titles
                    if prof.auto_bbr {
                        let _ = apply_bbr();
                    }
                }
            }
            DaemonState::Warn => {
                if let Some(prof) = self.active_profile.clone() {
                    let warn = BoostProfile {
                        name: "WARN".to_string(),
                        gpu_gov: prof.gpu_gov.clone(),
                        gpu_min: prof.warn_min,
                        cpu0_gov: prof.cpu0_gov.clone(),
                        cpu4_gov: prof.cpu4_gov.clone(),
                        cpu0_min: prof.cpu0_min,
                        cpu4_min: prof.cpu4_min,
                        cpu0_max: prof.cpu0_max,
                        cpu4_max: prof.cpu4_max,
                    };
                    if self.apply_fail_streak >= 3 {
                        // suspended; leave stock knobs
                    } else {
                        let rep = apply_profile(&warn);
                        if rep.success {
                            self.apply_fail_streak = 0;
                        } else {
                            self.apply_fail_streak += 1;
                            log_line(&format!(
                                "apply warn: {:?} (failure {} of 3, then suspend)",
                                rep.errors, self.apply_fail_streak
                            ));
                        }
                    }
                }
            }
            DaemonState::Crit => {
                crate::capture::stop_if_recording("thermal_critical");
                let _ = restore_all();
                crate::refresh_heads_up_cache();
            }
        }
    }
}

pub fn spawn_auto_daemon(
    running: Arc<AtomicBool>,
    state_sink: Arc<AtomicU8>,
    game_sink: Arc<RwLock<String>>,
    detector: Arc<RwLock<GameDetector>>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut daemon = AutoDaemon::new(detector);
        let mut idle_streak: u32 = 0;
        while running.load(Ordering::Relaxed) {
            // Panic-resilient tick: the daemon loop is a THREAD inside the
            // same process as the IPC handlers — an uncaught panic here would
            // kill the loop while the watchdog keeps seeing a live pid,
            // leaving presets applied until the next reboot. Swallow the
            // panic, log it, and keep ticking (best effort).
            let st = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                daemon.tick()
            })) {
                Ok(st) => st,
                Err(_) => {
                    log_line("panic: tick gagal, dilewati — daemon tetap hidup");
                    DaemonState::Idle
                }
            };
            state_sink.store(st as u8, Ordering::Relaxed);
            *game_sink.write().unwrap_or_else(|e| e.into_inner()) = daemon.current_game.clone();
            // Adaptive polling: 250 ms while a game is active (snappy state
            // machine), a lazy 1 s when idle to cut dumpsys churn ~4x.
            idle_streak = if matches!(st, DaemonState::Idle) {
                idle_streak.saturating_add(1)
            } else {
                0
            };
            let sleep_ms = if idle_streak >= 20 { 1000 } else { POLL_MS };
            thread::sleep(Duration::from_millis(sleep_ms));
        }
        // Restore stock when daemon shuts down
        crate::session::finish("daemon_stop");
        crate::capture::stop_if_recording("daemon_stop");
        let _ = restore_all();
    })
}
