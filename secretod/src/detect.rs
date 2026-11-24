use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use crate::profile::GameProfile;
use crate::sysfs::run_cmd;

const CONFIG_SH_PATH: &str = "/data/adb/secreto_space/config.sh";
const GAMES_LIST_PATH: &str = "/data/adb/secreto_space/games.list";
const CUSTOM_GAMES_PATH: &str = "/data/adb/secreto_space/custom_games.list";
const AUTO_GAMES_PATH: &str = "/data/adb/secreto_space/auto_games.list";
const DISABLED_GAMES_PATH: &str = "/data/adb/secreto_space/disabled_games.list";

pub static BUILTIN_GAMES: &[(&str, &str)] = &[
    ("com.mobile.legends", "Mobile Legends: Bang Bang"),
    ("com.mobilechess.gp", "Auto Chess"),
    ("com.tencent.ig", "PUBG Mobile"),
    ("com.pubg.krmobile", "PUBG Mobile (KR)"),
    ("com.pubg.imobile", "Battlegrounds Mobile India"),
    ("com.vng.pubgmobile", "PUBG Mobile (VN)"),
    ("com.dts.freefireth", "Free Fire"),
    ("com.dts.freefiremax", "Free Fire MAX"),
    ("com.activision.callofduty.shooter", "Call of Duty: Mobile"),
    ("com.garena.game.codm", "CODM (Garena)"),
    ("com.tencent.tmgp.sgame", "Honor of Kings (CN)"),
    ("com.levelinfinite.sgameGlobal", "Honor of Kings Global"),
    ("com.riotgames.league.wildrift", "League of Legends: Wild Rift"),
    ("com.miHoYo.Yuanshen", "Genshin Impact"),
    ("com.miHoYo.GenshinImpact", "Genshin Impact (Global)"),
    ("com.miHoYo.hkrpg", "Honkai: Star Rail"),
    ("com.supercell.brawlstars", "Brawl Stars"),
    ("com.supercell.clashroyale", "Clash Royale"),
    ("com.supercell.clashofclans", "Clash of Clans"),
    ("com.minecraftpe", "Minecraft PE"),
    ("com.mojang.minecraftpe", "Minecraft"),
    ("com.roblox.client", "Roblox"),
    ("com.ea.gp.fifamobile", "EA SPORTS FC Mobile"),
    ("jp.konami.pesam", "eFootball"),
    ("com.gameloft.android.ANMP.GloftA9HM", "Asphalt 9 / Legends"),
    ("com.kurogame.wutheringwaves.global", "Wuthering Waves"),
    ("com.carxtech.sr", "CarX Street"),
    ("org.ppsspp.ppsspp", "PPSSPP"),
    ("org.ppsspp.ppssppgold", "PPSSPP Gold"),
    ("xyz.aethersx2.android", "AetherSX2"),
    ("org.dolphinemu.dolphinemu", "Dolphin Emulator"),
];

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct GameEntry {
    pub pkg: String,
    pub name: String,
    pub source: String, // "manual", "auto", "config", "builtin"
    pub preset: String,
    pub auto_bbr: bool,
    pub profile: String,
}

pub struct GameDetector {
    config_games: HashSet<String>,
    manual_games: HashSet<String>,
    auto_games: HashSet<String>,
    disabled_games: HashSet<String>,
}

impl GameDetector {
    pub fn new() -> Self {
        let mut config = HashSet::new();
        let mut manual = HashSet::new();
        let mut auto = HashSet::new();
        let mut disabled = HashSet::new();

        // 1. Load config.sh GAME_PKGS (Layer 1 dari v1)
        Self::load_config_sh(&mut config);

        // 2. Load manual & games.list (Layer 2 dari v1)
        Self::load_list(GAMES_LIST_PATH, &mut auto);
        Self::load_list(CUSTOM_GAMES_PATH, &mut manual);
        Self::load_list(AUTO_GAMES_PATH, &mut auto);

        // 3. Load disabled games
        Self::load_list(DISABLED_GAMES_PATH, &mut disabled);

        Self {
            config_games: config,
            manual_games: manual,
            auto_games: auto,
            disabled_games: disabled,
        }
    }

    /// Parse GAME_PKGS="..." dari /data/adb/secreto_space/config.sh
    fn load_config_sh(set: &mut HashSet<String>) {
        if let Ok(content) = fs::read_to_string(CONFIG_SH_PATH) {
            for line in content.lines() {
                let trimmed = line.trim();
                if trimmed.starts_with("GAME_PKGS=") {
                    let val = trimmed.trim_start_matches("GAME_PKGS=");
                    let clean = val.trim_matches('"').trim_matches('\'');
                    for pkg in clean.split_whitespace() {
                        if !pkg.is_empty() && pkg.contains('.') {
                            set.insert(pkg.to_string());
                        }
                    }
                }
            }
        }
    }

    fn load_list(path: &str, set: &mut HashSet<String>) {
        if let Ok(content) = fs::read_to_string(path) {
            for line in content.lines() {
                let trimmed = line.trim();
                if !trimmed.is_empty() && !trimmed.starts_with('#') {
                    set.insert(trimmed.to_string());
                }
            }
        }
    }

    fn save_set(path: &str, set: &HashSet<String>) {
        if let Some(parent) = Path::new(path).parent() {
            let _ = fs::create_dir_all(parent);
        }
        if let Ok(mut f) = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(path)
        {
            for item in set {
                let _ = writeln!(f, "{}", item);
            }
        }
    }


    fn save_manual(&self) {
        Self::save_set(CUSTOM_GAMES_PATH, &self.manual_games);
        // Sinkronisasi ke games.list agar backward-compatible dengan versi lama
        let mut combined = self.manual_games.clone();
        for g in &self.auto_games {
            combined.insert(g.clone());
        }
        Self::save_set(GAMES_LIST_PATH, &combined);
    }

    fn save_auto(&self) {
        Self::save_set(AUTO_GAMES_PATH, &self.auto_games);
        // Sinkronisasi ke games.list
        let mut combined = self.manual_games.clone();
        for g in &self.auto_games {
            combined.insert(g.clone());
        }
        Self::save_set(GAMES_LIST_PATH, &combined);
    }

    fn save_disabled(&self) {
        Self::save_set(DISABLED_GAMES_PATH, &self.disabled_games);
    }

    /// Extract package name dari line dumpsys format (mCurrentFocus, topResumedActivity, mFocusedApp)
    fn parse_pkg_from_line(line: &str) -> Option<String> {
        for word in line.split_whitespace() {
            if let Some(slash_idx) = word.find('/') {
                let candidate = &word[..slash_idx];
                let clean: String = candidate
                    .chars()
                    .filter(|c| c.is_alphanumeric() || *c == '.' || *c == '_')
                    .collect();
                if clean.contains('.') {
                    return Some(clean);
                }
            }
        }
        None
    }

    /// Deteksi foreground package cepat & ringan (tanpa full dumpsys window):
    /// 1. dumpsys window displays (mCurrentFocus / mFocusedApp)
    /// 2. dumpsys activity top (ACTIVITY / topResumedActivity)
    pub fn get_foreground_pkg() -> String {
        // ponytail: window displays is 3x faster than full window dump and contains focus target
        let win_dump = run_cmd("dumpsys", &["window", "displays"]);
        for line in win_dump.lines() {
            if line.contains("mCurrentFocus") || line.contains("mFocusedApp") {
                if let Some(pkg) = Self::parse_pkg_from_line(line) {
                    return pkg;
                }
            }
        }

        let act_dump = run_cmd("dumpsys", &["activity", "top"]);
        for line in act_dump.lines() {
            if line.contains("ACTIVITY") || line.contains("topResumedActivity") {
                if let Some(pkg) = Self::parse_pkg_from_line(line) {
                    return pkg;
                }
            }
        }

        String::new()
    }

    /// Cek apakah package adalah sistem Android (bukan game)
    pub fn is_system_pkg(pkg: &str) -> bool {
        pkg.is_empty()
            || pkg == "android"
            || pkg.starts_with("com.android.")
            || pkg.starts_with("com.google.android.")
            || pkg.contains("launcher")
            || pkg.contains("systemui")
            || pkg.contains("inputmethod")
    }

    /// Get all third-party apps installed on the device (pm list packages -3)
    pub fn get_installed_user_apps() -> Vec<String> {
        let mut out = run_cmd("/system/bin/pm", &["list", "packages", "-3"]);
        if out.is_empty() {
            out = run_cmd("/system/bin/cmd", &["package", "list", "packages", "-3"]);
        }
        let mut apps = Vec::new();
        for line in out.lines() {
            let trimmed = line.trim();
            if let Some(pkg) = trimmed.strip_prefix("package:") {
                let clean = pkg.trim();
                if !clean.is_empty() && clean.contains('.') && !Self::is_system_pkg(clean) {
                    apps.push(clean.to_string());
                }
            }
        }
        apps.sort();
        apps
    }

    /// Add a manual game to custom list
    pub fn add_manual_game(&mut self, pkg: &str) -> Result<String, String> {
        let pkg = pkg.trim();
        if pkg.is_empty() || !pkg.contains('.') {
            return Err("Invalid package name (must contain a dot)".into());
        }
        if !pkg.chars().all(|c| c.is_alphanumeric() || c == '.' || c == '_') {
            return Err("Invalid characters in package name".into());
        }

        // If previously disabled, un-disable it
        if self.disabled_games.remove(pkg) {
            self.save_disabled();
        }

        self.manual_games.insert(pkg.to_string());
        self.save_manual();
        Ok(format!("Added game: {}", pkg))
    }

    /// Remove a game (whether manual, auto, or builtin)
    pub fn remove_game(&mut self, pkg: &str) -> Result<String, String> {
        let pkg = pkg.trim();
        let is_builtin = BUILTIN_GAMES.iter().any(|(p, _)| *p == pkg);
        let mut modified = false;

        if self.manual_games.remove(pkg) {
            self.save_manual();
            modified = true;
        }
        if self.auto_games.remove(pkg) {
            self.save_auto();
            modified = true;
        }
        if self.config_games.remove(pkg) {
            modified = true;
        }
        if is_builtin {
            self.disabled_games.insert(pkg.to_string());
            self.save_disabled();
            modified = true;
        }

        if modified {
            Ok(format!("Removed game: {}", pkg))
        } else {
            Err(format!("Game '{}' not found", pkg))
        }
    }

    pub fn is_registered(&self, pkg: &str) -> bool {
        self.manual_games.contains(pkg)
            || self.auto_games.contains(pkg)
            || self.config_games.contains(pkg)
            || BUILTIN_GAMES.iter().any(|(p, _)| *p == pkg)
    }

    /// Fast in-memory check without shell probes (only needs read lock)
    pub fn is_game_fast(&self, pkg: &str) -> Option<bool> {
        if pkg.is_empty() || self.disabled_games.contains(pkg) {
            return Some(false);
        }
        // Lapisan 1 & 2 dari v1: config + in-memory sets
        if self.manual_games.contains(pkg)
            || self.auto_games.contains(pkg)
            || self.config_games.contains(pkg)
            || BUILTIN_GAMES.iter().any(|(p, _)| *p == pkg)
        {
            return Some(true);
        }
        // Cepat tolak jika ini jelas-jelas sistem/launcher
        if Self::is_system_pkg(pkg) {
            return Some(false);
        }
        None // Belum terdaftar, perlu auto-learn
    }

    /// Cek engine pattern proses persis v1 is_engine_proc:
    /// ps -A -o NAME | grep $pkg | grep -qiE 'unity|game|render|glthread|cocos|godot|sdl|egl|vulkan'
    fn check_engine_proc(pkg: &str) -> bool {
        let ps = run_cmd("ps", &["-A", "-o", "NAME"]);
        for line in ps.lines() {
            if line.contains(pkg) {
                let lower = line.to_lowercase();
                if lower.contains("unity")
                    || lower.contains("game")
                    || lower.contains("render")
                    || lower.contains("glthread")
                    || lower.contains("cocos")
                    || lower.contains("godot")
                    || lower.contains("sdl")
                    || lower.contains("egl")
                    || lower.contains("vulkan")
                    || lower.contains("unreal")
                    || lower.contains("libil2cpp")
                {
                    return true;
                }
            }
        }

        // Cek juga libraries yang di-load proses via /proc/<pid>/maps jika ada
        let pid_out = run_cmd("pidof", &[pkg]);
        let pid = pid_out.trim();
        if !pid.is_empty() {
            let maps_path = format!("/proc/{}/maps", pid);
            if let Ok(maps) = fs::read_to_string(&maps_path) {
                let lower = maps.to_lowercase();
                if lower.contains("libunity")
                    || lower.contains("libil2cpp")
                    || lower.contains("libgodot")
                    || lower.contains("libcocos")
                    || lower.contains("libunreal")
                {
                    return true;
                }
            }
        }

        false
    }

    /// Auto-learn game saat runtime (Persis 4 lapis versi lama):
    /// 1. config.sh GAME_PKGS
    /// 2. games.list (auto-learned)
    /// 3. dumpsys game (GameManagerService Android)
    /// 4. Engine process pattern (Unity, Cocos, Render, Vulkan, dll)
    /// 5. cmd game list-modes (Android 12-16 GameManager)
    pub fn is_game(&mut self, pkg: &str) -> bool {
        if let Some(res) = self.is_game_fast(pkg) {
            return res;
        }

        // Lapisan 3: terdaftar di dumpsys game
        let game_dump = run_cmd("dumpsys", &["game"]);
        let needle = format!("package {}", pkg);
        let needle_cap = format!("Package {}", pkg);
        if game_dump.contains(&needle) || game_dump.contains(&needle_cap) {
            eprintln!("AUTO-LEARN game (GameManager): {}", pkg);
            self.auto_games.insert(pkg.to_string());
            self.save_auto();
            return true;
        }

        // Lapisan 4: pattern engine Unity/cocos/glthread/render
        if Self::check_engine_proc(pkg) {
            eprintln!("AUTO-LEARN game (Engine proc): {}", pkg);
            self.auto_games.insert(pkg.to_string());
            self.save_auto();
            return true;
        }

        // Lapisan 5: cmd game list-modes (Android 12+ dynamic mode)
        let game_mgr = run_cmd("cmd", &["game", "list-modes", pkg]);
        if game_mgr.contains("available game modes:") || game_mgr.contains("current mode:") {
            eprintln!("AUTO-LEARN game (list-modes): {}", pkg);
            self.auto_games.insert(pkg.to_string());
            self.save_auto();
            return true;
        }

        false
    }

    /// Scan device for games using dumpsys game and cmd game list-modes
    pub fn scan_device_games(&mut self) -> Vec<String> {
        let mut newly_detected = Vec::new();

        // 1. Scan dumpsys game
        let game_dump = run_cmd("dumpsys", &["game"]);
        for line in game_dump.lines() {
            if line.contains("package ") || line.contains("Package ") {
                for word in line.split_whitespace() {
                    let clean: String = word
                        .chars()
                        .filter(|c| c.is_alphanumeric() || *c == '.' || *c == '_')
                        .collect();
                    if clean.contains('.')
                        && !Self::is_system_pkg(&clean)
                        && !self.is_registered(&clean)
                    {
                        newly_detected.push(clean);
                    }
                }
            }
        }

        // Do not probe every installed package with `cmd game list-modes`.
        // That turns scan into one slow shell call per app. Installed-package
        // filtering happens in list_all_games; dumpsys game remains fast path.

        // Register and persist newly detected games
        for pkg in &newly_detected {
            self.auto_games.insert(pkg.clone());
        }
        if !newly_detected.is_empty() {
            self.save_auto();
        }

        newly_detected
    }

    /// List only game profiles whose packages are installed on device.
    pub fn list_all_games(&self) -> Vec<GameEntry> {
        let installed: HashSet<String> = Self::get_installed_user_apps().into_iter().collect();
        let mut map: HashMap<String, GameEntry> = HashMap::new();

        // 1. Builtin games
        for (pkg, name) in BUILTIN_GAMES {
            if installed.contains(*pkg) && !self.disabled_games.contains(*pkg) {
                let prof = GameProfile::for_package(pkg);
                map.insert(
                    pkg.to_string(),
                    GameEntry {
                        pkg: pkg.to_string(),
                        name: name.to_string(),
                        source: "builtin".to_string(),
                        preset: prof.preset.clone(),
                        auto_bbr: prof.auto_bbr,
                        profile: prof.summary(),
                    },
                );
            }
        }

        // 2. Config.sh games (jika belum ada di builtin)
        for pkg in &self.config_games {
            if installed.contains(pkg) && !self.disabled_games.contains(pkg) {
                let prof = GameProfile::for_package(pkg);
                let name = BUILTIN_GAMES
                    .iter()
                    .find(|(p, _)| *p == pkg.as_str())
                    .map(|(_, n)| n.to_string())
                    .unwrap_or_else(|| pkg.clone());
                map.insert(
                    pkg.clone(),
                    GameEntry {
                        pkg: pkg.clone(),
                        name,
                        source: "config".to_string(),
                        preset: prof.preset.clone(),
                        auto_bbr: prof.auto_bbr,
                        profile: prof.summary(),
                    },
                );
            }
        }

        // 3. Auto-detected / auto-learned games
        for pkg in &self.auto_games {
            if installed.contains(pkg) && !self.disabled_games.contains(pkg) {
                let prof = GameProfile::for_package(pkg);
                let name = BUILTIN_GAMES
                    .iter()
                    .find(|(p, _)| *p == pkg.as_str())
                    .map(|(_, n)| n.to_string())
                    .unwrap_or_else(|| pkg.clone());
                map.insert(
                    pkg.clone(),
                    GameEntry {
                        pkg: pkg.clone(),
                        name,
                        source: "auto".to_string(),
                        preset: prof.preset.clone(),
                        auto_bbr: prof.auto_bbr,
                        profile: prof.summary(),
                    },
                );
            }
        }

        // 4. Manual games (user additions always take highest precedence)
        for pkg in &self.manual_games {
            if !installed.contains(pkg) {
                continue;
            }
            let prof = GameProfile::for_package(pkg);
            let name = BUILTIN_GAMES
                .iter()
                .find(|(p, _)| *p == pkg.as_str())
                .map(|(_, n)| n.to_string())
                .unwrap_or_else(|| pkg.clone());
            map.insert(
                pkg.clone(),
                GameEntry {
                    pkg: pkg.clone(),
                    name,
                    source: "manual".to_string(),
                    preset: prof.preset.clone(),
                    auto_bbr: prof.auto_bbr,
                    profile: prof.summary(),
                },
            );
        }

        let mut list: Vec<GameEntry> = map.into_values().collect();
        list.sort_by(|a, b| {
            let order = |s: &str| match s {
                "manual" => 1,
                "config" => 2,
                "auto" => 3,
                _ => 4,
            };
            order(&a.source)
                .cmp(&order(&b.source))
                .then(a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        list
    }
}
