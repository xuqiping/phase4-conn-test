//! diff 大白话摘要（FR-013）。
//! 读取 checkpoint 对应 commit 的 git diff，调用 LLM 生成人话摘要。

use crate::task_scheduler::{LlmClient, LlmMessage};
use serde::{Deserialize, Serialize};
use std::path::Path;

const DIFF_SUMMARY_PROMPT: &str = include_str!("prompts/diff_summary.txt");
const DIFF_MAX_LINES: usize = 5000;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DiffSummary {
    #[serde(default)]
    pub what_changed: String,
    #[serde(default)]
    pub why: String,
    #[serde(default)]
    pub impact: String,
    #[serde(default)]
    pub risk: String,
    #[serde(default)]
    pub files: Vec<String>,
    /// diff 是否被截断
    #[serde(default)]
    pub truncated: bool,
}

#[derive(Debug, thiserror::Error)]
pub enum SummarizerError {
    #[error("git diff 失败：{0}")]
    Git(String),
    #[error("LLM 调用失败：{0}")]
    Llm(String),
    #[error("模型返回无法解析：{0}")]
    Parse(String),
}

/// 获取某 commit 的 diff 原文（与父 commit 比较）。
pub fn fetch_diff(project_path: &Path, commit: &str) -> Result<String, SummarizerError> {
    // 先尝试 commit^..commit；失败则用 git show（可能是首提交）。
    let out = std::process::Command::new("git")
        .args(["diff", &format!("{}^..{}", commit, commit)])
        .current_dir(project_path)
        .output()
        .map_err(|e| SummarizerError::Git(e.to_string()))?;

    if out.status.success() {
        return Ok(String::from_utf8_lossy(&out.stdout).to_string());
    }

    let out = std::process::Command::new("git")
        .args(["show", commit])
        .current_dir(project_path)
        .output()
        .map_err(|e| SummarizerError::Git(e.to_string()))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        Err(SummarizerError::Git(
            String::from_utf8_lossy(&out.stderr).to_string(),
        ))
    }
}

/// 截断过长的 diff，保留提示。
pub fn truncate_diff(diff: &str) -> (String, bool) {
    let lines: Vec<&str> = diff.lines().collect();
    if lines.len() <= DIFF_MAX_LINES {
        return (diff.to_string(), false);
    }
    let mut out = lines[..DIFF_MAX_LINES].join("\n");
    out.push_str(&format!(
        "\n\n[diff 过长，仅展示前 {} 行；完整改动请切到代码原文查看]",
        DIFF_MAX_LINES
    ));
    (out, true)
}

/// 生成大白话摘要；返回 (摘要, 原始 diff)。
pub async fn summarize(
    project_path: &Path,
    commit: &str,
    llm: &dyn LlmClient,
) -> Result<(DiffSummary, String), SummarizerError> {
    let raw = fetch_diff(project_path, commit)?;
    let (diff_text, truncated) = truncate_diff(&raw);

    let prompt = DIFF_SUMMARY_PROMPT
        .replace("{{COMMIT}}", commit)
        .replace("{{DIFF}}", &diff_text);

    let response = llm
        .complete(vec![LlmMessage {
            role: "user".into(),
            content: prompt,
        }])
        .await
        .map_err(|e| SummarizerError::Llm(e.to_string()))?;

    let mut summary =
        parse_summary(&response.content).map_err(|e| SummarizerError::Parse(e.to_string()))?;
    summary.truncated = truncated;
    Ok((summary, raw))
}

fn parse_summary(raw: &str) -> Result<DiffSummary, serde_json::Error> {
    let text = raw.trim();
    if let Ok(v) = serde_json::from_str::<DiffSummary>(text) {
        return Ok(v);
    }
    if let Some(start) = text.find("```") {
        let after_fence = &text[start + 3..];
        let code = after_fence.strip_prefix("json").unwrap_or(after_fence);
        if let Some(end) = code.find("```") {
            let inner = code[..end].trim();
            if let Ok(v) = serde_json::from_str::<DiffSummary>(inner) {
                return Ok(v);
            }
        }
    }
    if let Some(start) = text.find('{') {
        if let Some(end) = text.rfind('}') {
            let slice = &text[start..=end];
            if let Ok(v) = serde_json::from_str::<DiffSummary>(slice) {
                return Ok(v);
            }
        }
    }
    serde_json::from_str::<DiffSummary>(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncate_long_diff() {
        let diff: String = (0..6000).map(|i| format!("line {}\n", i)).collect();
        let (out, truncated) = truncate_diff(&diff);
        assert!(truncated);
        assert!(out.lines().count() <= 5002);
    }

    #[test]
    fn keep_short_diff() {
        let diff = "a\nb\nc".to_string();
        let (out, truncated) = truncate_diff(&diff);
        assert!(!truncated);
        assert_eq!(out, diff);
    }

    #[test]
    fn parse_extracts_json_from_markdown() {
        let raw = "```json\n{\"what_changed\":\"x\",\"why\":\"y\",\"impact\":\"z\",\"risk\":\"r\",\"files\":[\"a\"]}\n```";
        let s = parse_summary(raw).unwrap();
        assert_eq!(s.what_changed, "x");
        assert_eq!(s.files, vec!["a"]);
    }
}
