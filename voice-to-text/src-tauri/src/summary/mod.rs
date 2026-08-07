//! Step 8 (FR-107/109): 云端总结 —— 分段 map-reduce 调云端 LLM（OpenAI 兼容）。
//!
//! 隐私红线（AGENTS.md「例外·网课总结功能」，2026-08-05 用户 review 批准）：
//! - 仅上传转写文字 + 课件 OCR 文本（多模态精修开关开启时才附课件帧图，默认关）；
//! - 音视频原文件永不离开本机；
//! - API Key 只存 Windows 凭据管理器（keyring），不落盘、不进日志、不出现在错误信息；
//! - 配置（base_url/model 等非密信息）存 %APPDATA%/<app>/summary_config.json。

pub mod cloud_api;
pub mod map_reduce;
pub mod prompt;
pub mod render;

use serde::{Deserialize, Serialize};
use std::path::Path;

/// keyring 条目名（Windows 凭据管理器里可见的服务名/条目名）。
pub const KEYRING_SERVICE: &str = "voice-to-text";
pub const KEYRING_USER: &str = "summary_api_key";

const CONFIG_FILE: &str = "summary_config.json";

fn default_max_segment_chars() -> usize {
    2000 // plan: 单段输入 ≤ 2000 字
}
fn default_concurrency() -> usize {
    2 // plan: 默认低并发 2-3，受 provider 限流约束
}
fn default_timeout_secs() -> u64 {
    120
}

/// 云端总结配置（非密，可落盘）。API Key 不在此处。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SummaryConfig {
    /// OpenAI 兼容 Base URL，如 https://api.kimi.com/coding/（尾斜杠有无均可）。
    pub base_url: String,
    /// 文本模型名，如 k3-256k。
    pub model: String,
    /// 视觉模型名（多模态精修用；不开精修可留空）。
    #[serde(default)]
    pub vlm_model: Option<String>,
    /// 单段输入上限（字），超长再切。
    #[serde(default = "default_max_segment_chars")]
    pub max_segment_chars: usize,
    /// map 阶段并发度（受 provider 限流约束）。
    #[serde(default = "default_concurrency")]
    pub concurrency: usize,
    /// 单次请求超时（秒）。
    #[serde(default = "default_timeout_secs")]
    pub timeout_secs: u64,
}

impl Default for SummaryConfig {
    fn default() -> Self {
        Self {
            base_url: "https://api.kimi.com/coding/".into(),
            model: "k3-256k".into(),
            vlm_model: None,
            max_segment_chars: default_max_segment_chars(),
            concurrency: default_concurrency(),
            timeout_secs: default_timeout_secs(),
        }
    }
}

pub fn load_config(config_dir: &Path) -> SummaryConfig {
    std::fs::read_to_string(config_dir.join(CONFIG_FILE))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_config(config_dir: &Path, cfg: &SummaryConfig) -> Result<(), String> {
    std::fs::create_dir_all(config_dir).map_err(|e| format!("create config dir: {e}"))?;
    let json = serde_json::to_string_pretty(cfg).map_err(|e| e.to_string())?;
    std::fs::write(config_dir.join(CONFIG_FILE), json).map_err(|e| format!("write config: {e}"))
}

// ---- API Key（Windows 凭据管理器）----

pub fn set_api_key(key: &str) -> Result<(), String> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|e| e.to_string())?;
    entry
        .set_password(key)
        .map_err(|e| format!("保存凭据失败: {e}"))
}

/// Ok(None) = 未设置。其他错误（凭据管理器不可用等）原样上报。
pub fn get_api_key() -> Result<Option<String>, String> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|e| e.to_string())?;
    match entry.get_password() {
        Ok(k) => Ok(Some(k)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(format!("读取凭据失败: {e}")),
    }
}

pub fn clear_api_key() -> Result<(), String> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|e| e.to_string())?;
    match entry.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(format!("清除凭据失败: {e}")),
    }
}

// ---- 草稿（可撤销：确认前只写草稿 + 版本历史）----

/// 一条总结要点：带时间戳 + 课件帧引用（可解释/可回链）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SummaryPoint {
    pub text: String,
    pub ts_ms: i64,
    /// 课件帧原图相对 session 的路径（可解释性回链）；固定窗降级时为 None。
    pub frame_ref: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SegmentSummary {
    pub segment_id: usize,
    pub start_ms: i64,
    pub end_ms: i64,
    pub points: Vec<SummaryPoint>,
    /// true = 本段是本地兜底产物（LLM 失败/解析失败），非云端生成。
    pub local_fallback: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SummaryDraft {
    pub version: u32,
    pub model: String,
    /// true = 整体或部分走了本地兜底（API 失败降级路径）。
    pub fallback: bool,
    pub segments: Vec<SegmentSummary>,
    /// 全局大纲（reduce 产物；reduce 失败为空数组不阻塞）。
    pub outline: Vec<String>,
}

/// summary_draft.json 磁盘格式：当前草稿 + 历史版本（可撤销）。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SummaryFile {
    pub current: Option<SummaryDraft>,
    #[serde(default)]
    pub history: Vec<SummaryDraft>,
}

const DRAFT_FILE: &str = "summary_draft.json";
/// 历史版本上限（防无限膨胀）。
const HISTORY_CAP: usize = 20;

pub fn load_summary_file(session_dir: &Path) -> SummaryFile {
    std::fs::read_to_string(session_dir.join(DRAFT_FILE))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

/// 写入新草稿：旧 current 压入 history（截断到 HISTORY_CAP）。
pub fn save_new_draft(session_dir: &Path, draft: SummaryDraft) -> Result<(), String> {
    let mut file = load_summary_file(session_dir);
    if let Some(old) = file.current.take() {
        file.history.push(old);
        if file.history.len() > HISTORY_CAP {
            let overflow = file.history.len() - HISTORY_CAP;
            file.history.drain(0..overflow);
        }
    }
    file.current = Some(draft);
    let json = serde_json::to_string_pretty(&file).map_err(|e| e.to_string())?;
    std::fs::write(session_dir.join(DRAFT_FILE), json).map_err(|e| format!("write draft: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_default_is_kimi_endpoint() {
        let c = SummaryConfig::default();
        assert_eq!(c.base_url, "https://api.kimi.com/coding/");
        assert_eq!(c.model, "k3-256k");
        assert_eq!(c.max_segment_chars, 2000);
    }

    #[test]
    fn config_roundtrip_and_tolerant_of_missing_fields() {
        let dir = std::env::temp_dir().join(format!("vtt_sumcfg_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let cfg = SummaryConfig {
            base_url: "https://example.com/".into(),
            model: "m1".into(),
            ..Default::default()
        };
        save_config(&dir, &cfg).unwrap();
        assert_eq!(load_config(&dir), cfg);
        // 旧版配置缺字段 → serde default 补齐，不报错。
        std::fs::write(dir.join(CONFIG_FILE), r#"{"base_url":"http://x","model":"y"}"#).unwrap();
        let loaded = load_config(&dir);
        assert_eq!(loaded.model, "y");
        assert_eq!(loaded.concurrency, 2);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 真实凭据后端冒烟：set → get → clear 走 Windows 凭据管理器。
    /// `cargo test -- --ignored keyring_real`（会动真实凭据库，日常跳过）。
    #[test]
    #[ignore]
    fn keyring_real_roundtrip() {
        set_api_key("test-key-请忽略").unwrap();
        assert_eq!(get_api_key().unwrap().as_deref(), Some("test-key-请忽略"));
        clear_api_key().unwrap();
        assert_eq!(get_api_key().unwrap(), None);
    }

    #[test]
    fn draft_history_push_and_cap() {
        let dir = std::env::temp_dir().join(format!("vtt_sumdraft_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mk = |v: u32| SummaryDraft {
            version: v,
            model: "m".into(),
            fallback: false,
            segments: vec![],
            outline: vec![],
        };
        for v in 1..=25 {
            save_new_draft(&dir, mk(v)).unwrap();
        }
        let f = load_summary_file(&dir);
        assert_eq!(f.current.as_ref().unwrap().version, 25);
        assert_eq!(f.history.len(), HISTORY_CAP);
        assert_eq!(f.history.last().unwrap().version, 24);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
