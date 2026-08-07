//! Feature flags（Step 12 运维开关 / FR-101~109 整功能可关停）。
//!
//! 生效优先级：环境变量 > 配置文件 > 默认开。
//! - 环境变量 `VTT_COURSE_SUMMARY=off`：紧急关停，不用找配置文件；
//! - 配置文件 `<app_config_dir>/feature_flags.json`：`{"course_summary": false}`；
//! - 都没有 → 默认开（不因为配置缺失把主功能搞挂）。
//!
//! 运维场景：网课总结出线上问题时，置开关即可隐藏入口 + 拒绝新录制，不必回滚发版。

use serde::{Deserialize, Serialize};
use std::path::Path;

const ENV_KEY: &str = "VTT_COURSE_SUMMARY";
const FILE_NAME: &str = "feature_flags.json";

/// 暴露给前端的开关集合（`get_feature_flags` 返回体）。
/// 加新开关时在此加字段；`#[serde(default)]` 保证旧配置文件缺字段也能解析。
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
pub struct FeatureFlags {
    /// 网课录屏总结入口。false = 前端隐藏 Tab + 后端拒绝 start_capture_session。
    #[serde(default = "default_true")]
    pub course_summary: bool,
}

fn default_true() -> bool {
    true
}

impl FeatureFlags {
    /// 缺省策略：全开。
    pub fn all_on() -> Self {
        Self {
            course_summary: true,
        }
    }
}

/// 环境变量值解析：`0/off/false/disabled/no`（大小写不敏感、去空白）视为关，其余视为开。
pub fn env_flag_enabled(v: &str) -> bool {
    !matches!(
        v.trim().to_ascii_lowercase().as_str(),
        "0" | "off" | "false" | "disabled" | "no"
    )
}

/// 读配置文件中的开关；文件缺失 / 损坏 → 默认全开（配置问题不应拖垮应用）。
pub fn load_flags(cfg_dir: &Path) -> FeatureFlags {
    let raw = match std::fs::read_to_string(cfg_dir.join(FILE_NAME)) {
        Ok(s) => s,
        Err(_) => return FeatureFlags::all_on(),
    };
    serde_json::from_str(&raw).unwrap_or_else(|e| {
        log::warn!("[feature] {FILE_NAME} 解析失败，按默认全开处理: {e}");
        FeatureFlags::all_on()
    })
}

/// 仅由环境变量决定的开关（配置目录不可解析时的兜底路径）。
pub fn env_only_flags() -> FeatureFlags {
    let mut flags = FeatureFlags::all_on();
    if let Ok(v) = std::env::var(ENV_KEY) {
        flags.course_summary = env_flag_enabled(&v);
    }
    flags
}

/// 最终生效值：配置文件打底，环境变量覆盖。
pub fn effective_flags(cfg_dir: &Path) -> FeatureFlags {
    let mut flags = load_flags(cfg_dir);
    if let Ok(v) = std::env::var(ENV_KEY) {
        flags.course_summary = env_flag_enabled(&v);
        log::info!("[feature] {ENV_KEY} 覆盖生效 → course_summary={}", flags.course_summary);
    }
    flags
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_value_parsing() {
        for off in ["0", "off", "OFF", " false ", "Disabled", "no"] {
            assert!(!env_flag_enabled(off), "{off} 应视为关");
        }
        for on in ["1", "on", "true", "yes", "", "随便什么"] {
            assert!(env_flag_enabled(on), "{on} 应视为开");
        }
    }

    #[test]
    fn missing_file_defaults_on() {
        let dir = std::env::temp_dir().join(format!("vtt_ff_missing_{}", std::process::id()));
        assert_eq!(load_flags(&dir), FeatureFlags::all_on());
    }

    #[test]
    fn file_can_disable() {
        let dir = std::env::temp_dir().join(format!("vtt_ff_off_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(FILE_NAME), r#"{"course_summary": false}"#).unwrap();
        assert_eq!(
            load_flags(&dir),
            FeatureFlags {
                course_summary: false
            }
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn broken_file_defaults_on() {
        let dir = std::env::temp_dir().join(format!("vtt_ff_broken_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(FILE_NAME), "not json").unwrap();
        assert_eq!(load_flags(&dir), FeatureFlags::all_on());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn empty_object_defaults_on() {
        // 缺字段时 serde default 补 true —— 旧配置文件不会因为加字段而炸。
        let flags: FeatureFlags = serde_json::from_str("{}").unwrap();
        assert_eq!(flags, FeatureFlags::all_on());
    }
}
