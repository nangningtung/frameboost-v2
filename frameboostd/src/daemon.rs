use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, RwLock};
use std::thread;
use std::time::Duration;

use crate::apply::{apply_profile, BoostProfile};
use crate::detect::GameDetector;
use crate::net::{apply_bbr, restore_stock_cc};
use crate::profile::GameProfile;
use crate::rollback::restore_all;
use crate::sysfs::{read_u32, run_cmd};

const POLL_MS: u64 = 500;
const DEBOUNCE_MISSES: u32 = 3; // 3 x 500ms = 1.5s before declaring game exit

const TEMP_WARN_ON: u32 = 85000;
const TEMP_WARN_OFF: u32 = 82000;
const TEMP_CRIT_ON: u32 = 90000;
const TEMP_CRIT_OFF: u32 = 80000;

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
}

impl AutoDaemon {
    pub fn new(detector: Arc<RwLock<GameDetector>>) -> Self {
        Self {
            detector,
            state: DaemonState::Idle,
            current_game: String::new(),
            miss_count: 0,
            active_profile: None,
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
            self.miss_count += 1;
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
            eprintln!(
                "[daemon] State transition: {:?} -> {:?} (pkg={}, temp={}mC)",
                self.state, next_state, self.current_game, temp_big
            );
            self.apply_state(next_state);
            self.state = next_state;
        }

        // Clean up current_game if fully dropped to idle
        if self.state == DaemonState::Idle && !in_game {
            self.current_game.clear();
            self.active_profile = None;
        }

        self.state
    }

    fn apply_state(&self, st: DaemonState) {
        match st {
            DaemonState::Idle => {
                let _ = restore_all();
                if let Some(prof) = &self.active_profile {
                    if prof.auto_bbr {
                        let _ = restore_stock_cc();
                    }
                }
            }
            DaemonState::Game => {
                if let Some(prof) = &self.active_profile {
                    let boost = BoostProfile {
                        name: "GAME",
                        gpu_gov: Box::leak(prof.gpu_gov.clone().into_boxed_str()),
                        gpu_min: prof.gpu_min,
                        cpu0_min: prof.cpu0_min,
                        cpu4_min: prof.cpu4_min,
                    };
                    let _ = apply_profile(&boost);

                    // 1. Anti-stutter RAM & ZRAM: lower swappiness from 130 -> 10 to eliminate kswapd0 thrashing
                    let _ = fs::write("/proc/sys/vm/swappiness", "10");
                    let _ = fs::write("/proc/sys/vm/vfs_cache_pressure", "50");

                    // 2. Gaming DND: block heads-up notifications from popping up over game
                    let _ = run_cmd("settings", &["put", "global", "heads_up_notifications_enabled", "0"]);

                    // 3. Anti-redup: lock brightness to manual mode (prevent palm from covering ambient light sensor)
                    let _ = run_cmd("settings", &["put", "system", "screen_brightness_mode", "0"]);

                    // 4. Game Launch Hygiene: drop pagecache & renice process
                    let _ = fs::write("/proc/sys/vm/drop_caches", "3");
                    let pid_out = run_cmd("pidof", &[&self.current_game]);
                    let pid = pid_out.trim();
                    if !pid.is_empty() {
                        let _ = run_cmd("renice", &["-n", "-10", "-p", pid]);
                    }

                    // 5. Auto-BBR for online titles
                    if prof.auto_bbr {
                        let _ = apply_bbr();
                    }
                }
            }
            DaemonState::Warn => {
                if let Some(prof) = &self.active_profile {
                    let warn = BoostProfile {
                        name: "WARN",
                        gpu_gov: Box::leak(prof.gpu_gov.clone().into_boxed_str()),
                        gpu_min: prof.warn_min,
                        cpu0_min: prof.cpu0_min,
                        cpu4_min: prof.cpu4_min,
                    };
                    let _ = apply_profile(&warn);
                }
            }
            DaemonState::Crit => {
                let _ = restore_all();
            }
        }
    }
}

pub fn spawn_auto_daemon(
    running: Arc<AtomicBool>,
    state_sink: Arc<AtomicU8>,
    detector: Arc<RwLock<GameDetector>>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut daemon = AutoDaemon::new(detector);
        while running.load(Ordering::Relaxed) {
            let st = daemon.tick();
            state_sink.store(st as u8, Ordering::Relaxed);
            thread::sleep(Duration::from_millis(POLL_MS));
        }
        // Restore stock when daemon shuts down
        let _ = restore_all();
    })
}
