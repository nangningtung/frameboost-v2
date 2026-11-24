use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, RwLock};
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
        }
    }

    pub fn tick(&mut self) -> DaemonState {
        let fg = GameDetector::get_foreground_pkg();
        let fast_check = {
            let det = self.detector.read().unwrap();
            det.is_game_fast(&fg)
        };
        let is_game = match fast_check {
            Some(val) => val,
            None => self.detector.write().unwrap().is_game(&fg),
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
            self.apply_state(next_state);
            self.state = next_state;
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
            self.ensure_hud_running();
        }

        // Clean up current_game if fully dropped to idle
        if self.state == DaemonState::Idle && !in_game {
            self.current_game.clear();
            self.active_profile = None;
            self.extras_pkg.clear();
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
                cpu0_min: prof.cpu0_min,
                cpu4_min: prof.cpu4_min,
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
        let pids = run_cmd("pidof", &["com.secreto.space"]);
        if pids.trim().is_empty() {
            self.start_hud_service();
            self.hud_miss = 0;
            return;
        }

        for p in pids.split_whitespace() {
            if !p.is_empty() {
                let _ = fs::write(format!("/proc/{}/oom_score_adj", p), "-800");
            }
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
                    cpu0_min: prof.cpu0_min,
                    cpu4_min: prof.cpu4_min,
                };
                let _ = apply_profile(&boost);

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
                    // 4. Renice game process(es) safely without blocking
                    let pid_out = run_cmd("pidof", &[&self.current_game]);
                    for p in pid_out.split_whitespace() {
                        if !p.is_empty() {
                            let _ = run_cmd("renice", &["-n", "-10", "-p", p]);
                        }
                    }

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
                        cpu0_min: prof.cpu0_min,
                        cpu4_min: prof.cpu4_min,
                    };
                    let _ = apply_profile(&warn);
                }
            }
            DaemonState::Crit => {
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
        while running.load(Ordering::Relaxed) {
            let st = daemon.tick();
            state_sink.store(st as u8, Ordering::Relaxed);
            *game_sink.write().unwrap() = daemon.current_game.clone();
            thread::sleep(Duration::from_millis(POLL_MS));
        }
        // Restore stock when daemon shuts down
        let _ = restore_all();
    })
}
