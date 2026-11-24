use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

const PROFILES_CONFIG_PATH: &str = "/data/adb/secreto_space/game_profiles.json";

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub enum Preset {
    Battery,
    Balanced,
    Performance,
    Esports,
}

impl Preset {
    pub fn as_str(&self) -> &'static str {
        match self {
            Preset::Battery => "BATTERY",
            Preset::Balanced => "BALANCED",
            Preset::Performance => "PERFORMANCE",
            Preset::Esports => "ESPORTS",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "BATTERY" => Preset::Battery,
            "PERFORMANCE" => Preset::Performance,
            "ESPORTS" => Preset::Esports,
            _ => Preset::Balanced,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CustomGameConfig {
    pub preset: String,
    pub auto_bbr: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct GameProfile {
    pub name: String,
    pub preset: String,
    pub gpu_gov: String,
    pub gpu_min: u32,
    pub cpu0_min: u32,
    pub cpu4_min: u32,
    pub warn_min: u32,
    pub auto_bbr: bool,
}

impl GameProfile {
    pub fn load_custom_configs() -> HashMap<String, CustomGameConfig> {
        if let Ok(data) = fs::read_to_string(PROFILES_CONFIG_PATH) {
            if let Ok(map) = serde_json::from_str::<HashMap<String, CustomGameConfig>>(&data) {
                return map;
            }
        }
        HashMap::new()
    }

    pub fn save_custom_config(pkg: &str, preset: &str, auto_bbr: bool) -> Result<(), String> {
        let mut configs = Self::load_custom_configs();
        configs.insert(
            pkg.to_string(),
            CustomGameConfig {
                preset: preset.to_string(),
                auto_bbr,
            },
        );
        Self::write_configs(&configs)
    }

    /// Change only the preset, keeping whatever auto-BBR choice the game already has.
    pub fn save_preset_only(pkg: &str, preset: &str) -> Result<(), String> {
        let mut configs = Self::load_custom_configs();
        let auto_bbr = configs
            .get(pkg)
            .map(|c| c.auto_bbr)
            .unwrap_or_else(|| Self::default_preset_for(pkg).1);
        configs.insert(
            pkg.to_string(),
            CustomGameConfig {
                preset: preset.to_string(),
                auto_bbr,
            },
        );
        Self::write_configs(&configs)
    }

    fn write_configs(configs: &HashMap<String, CustomGameConfig>) -> Result<(), String> {
        if let Some(parent) = Path::new(PROFILES_CONFIG_PATH).parent() {
            let _ = fs::create_dir_all(parent);
        }

        match serde_json::to_string_pretty(configs) {
            Ok(json) => {
                if let Ok(mut f) = OpenOptions::new()
                    .create(true)
                    .write(true)
                    .truncate(true)
                    .open(PROFILES_CONFIG_PATH)
                {
                    let _ = f.write_all(json.as_bytes());
                    Ok(())
                } else {
                    Err("Failed to open profiles file for write".into())
                }
            }
            Err(e) => Err(format!("Serialization error: {}", e)),
        }
    }

    pub fn default_preset_for(pkg: &str) -> (Preset, bool) {
        match pkg {
            "com.tencent.ig"
            | "com.pubg.krmobile"
            | "com.activision.callofduty.shooter"
            | "com.garena.game.codm" => (Preset::Performance, true),
            "com.miHoYo.Yuanshen" | "com.miHoYo.hkrpg" => (Preset::Esports, false),
            "com.dts.freefireth" | "com.dts.freefiremax" => (Preset::Battery, true),
            "com.mobile.legends"
            | "com.mobilechess.gp"
            | "com.riotgames.league.wildrift"
            | "com.tencent.tmgp.sgame"
            | "com.levelinfinite.sgameGlobal" => (Preset::Balanced, true),
            _ => (Preset::Balanced, false),
        }
    }

    pub fn build(name: &str, preset: Preset, auto_bbr: bool) -> Self {
        let p_str = preset.as_str().to_string();
        match preset {
            Preset::Battery => Self {
                name: name.into(),
                preset: p_str,
                gpu_gov: "Dynamic".into(),
                gpu_min: 385000,
                cpu0_min: 910000,
                cpu4_min: 1248000,
                warn_min: 338000,
                auto_bbr,
            },
            Preset::Balanced => Self {
                name: name.into(),
                preset: p_str,
                gpu_gov: "Booster".into(),
                gpu_min: 455000,
                cpu0_min: 910000,
                cpu4_min: 1352000,
                warn_min: 385000,
                auto_bbr,
            },
            Preset::Performance => Self {
                name: name.into(),
                preset: p_str,
                gpu_gov: "Booster".into(),
                gpu_min: 572000,
                cpu0_min: 1053000,
                cpu4_min: 1508000,
                warn_min: 455000,
                auto_bbr,
            },
            Preset::Esports => Self {
                name: name.into(),
                preset: p_str,
                gpu_gov: "Booster".into(),
                gpu_min: 683000,
                cpu0_min: 1053000,
                cpu4_min: 1508000,
                warn_min: 455000,
                auto_bbr,
            },
        }
    }

    pub fn for_package(pkg: &str) -> Self {
        let custom_map = Self::load_custom_configs();
        if let Some(cfg) = custom_map.get(pkg) {
            let preset = Preset::from_str(&cfg.preset);
            return Self::build(pkg, preset, cfg.auto_bbr);
        }

        let (def_preset, def_bbr) = Self::default_preset_for(pkg);
        Self::build(pkg, def_preset, def_bbr)
    }

    pub fn summary(&self) -> String {
        format!(
            "{} • {} {}M • CPU4 {:.2}G{}",
            self.preset,
            self.gpu_gov,
            self.gpu_min / 1000,
            self.cpu4_min as f64 / 1_000_000.0,
            if self.auto_bbr { " • BBR" } else { "" }
        )
    }
}
