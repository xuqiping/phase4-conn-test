//! 测试方案 Markdown 解析器（FR-033 / AC-036）。
//! 把 `workflow_output/docs/测试方案/*.md` 转成结构化验收项草稿。

use regex::Regex;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AcceptanceMethod {
    Auto,
    Manual,
}

/// 一个尚未落库的验收项草稿。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AcceptanceItemDraft {
    pub source_file: String,
    pub tc_id: String,
    pub title: String,
    pub steps: String,
    pub expected: String,
    pub method: AcceptanceMethod,
}

#[derive(Debug, thiserror::Error)]
pub enum ParserError {
    #[error("IO 错误：{0}")]
    Io(#[from] std::io::Error),
    #[error("路径越界：{0}")]
    Escape(String),
}

const TEST_PLAN_DIR: &str = "workflow_output/docs/测试方案";

/// 解析项目下全部测试方案文件。
pub fn parse_project_test_plans(
    project_path: &Path,
) -> Result<Vec<AcceptanceItemDraft>, ParserError> {
    let dir = resolve_subdir(project_path, TEST_PLAN_DIR)?;
    if !dir.exists() {
        return Ok(Vec::new());
    }

    let mut drafts = Vec::new();
    for entry in std::fs::read_dir(&dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) != Some("md") {
            continue;
        }
        let content = std::fs::read_to_string(&path)?;
        let rel = path
            .strip_prefix(project_path)
            .unwrap_or(&path)
            .to_string_lossy()
            .to_string();
        drafts.extend(parse_one_file(&rel, &content));
    }

    // 按文件名 + 出现顺序给稳定排序号（此处仅保证顺序，sort_order 由落库时写入）。
    drafts.sort_by(|a, b| {
        a.source_file
            .cmp(&b.source_file)
            .then_with(|| a.tc_id.cmp(&b.tc_id))
    });
    Ok(drafts)
}

fn resolve_subdir(project_path: &Path, sub: &str) -> Result<PathBuf, ParserError> {
    let target = project_path.join(sub);
    let canonical_project = project_path
        .canonicalize()
        .unwrap_or_else(|_| project_path.to_path_buf());
    let canonical_target = target.canonicalize().unwrap_or_else(|_| target.clone());
    if !canonical_target.starts_with(&canonical_project) {
        return Err(ParserError::Escape(format!(
            "{} 不在项目目录内",
            canonical_target.display()
        )));
    }
    Ok(target)
}

fn parse_one_file(source_file: &str, content: &str) -> Vec<AcceptanceItemDraft> {
    let heading_re = Regex::new(r"(?m)^[ \t]*#{2,4}\s+(.+)$").expect("正则合法");
    let mut drafts = Vec::new();

    for m in heading_re.find_iter(content) {
        let title_line = m.as_str();
        let title = extract_title(title_line);
        let tc_id = extract_tc_id(&title);
        // 只把看起来像用例标题的段落拆成验收项。
        if tc_id.is_empty() && !looks_like_case(&title) {
            continue;
        }
        let body_start = m.end();
        let body_end = heading_re
            .find_iter(content)
            .find(|n| n.start() > body_start)
            .map(|n| n.start())
            .unwrap_or(content.len());
        let body = content[body_start..body_end].trim();
        let (steps, expected) = extract_steps_expected(body);
        let method = detect_method(body);
        drafts.push(AcceptanceItemDraft {
            source_file: source_file.into(),
            tc_id: tc_id.clone(),
            title: if tc_id.is_empty() {
                title.clone()
            } else {
                format!("{} {}", tc_id, title.trim_start_matches(&tc_id).trim())
                    .trim()
                    .to_string()
            },
            steps,
            expected,
            method,
        });
    }

    // 一个标题都没找到时，把整个文件作为一条人工项兜底。
    if drafts.is_empty() && !content.trim().is_empty() {
        let (steps, expected) = extract_steps_expected(content);
        drafts.push(AcceptanceItemDraft {
            source_file: source_file.into(),
            tc_id: "TC-FALLBACK".into(),
            title: "未分类验收项".into(),
            steps,
            expected,
            method: AcceptanceMethod::Manual,
        });
    }

    // 去重：同一 source_file 里相同 tc_id 只保留第一次出现。
    let mut seen = std::collections::HashSet::new();
    drafts
        .into_iter()
        .filter(|d| seen.insert((d.source_file.clone(), d.tc_id.clone())))
        .collect()
}

fn extract_title(line: &str) -> String {
    line.trim_start_matches('#').trim().to_string()
}

fn extract_tc_id(title: &str) -> String {
    let re = Regex::new(r"^(TC-\d+)").expect("正则合法");
    re.captures(title)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_string())
        .unwrap_or_default()
}

fn looks_like_case(title: &str) -> bool {
    let re = Regex::new(r"^(用例\s*\d+|\d+\.[\s\S]+|案例\s*\d+)").expect("正则合法");
    re.is_match(title)
}

fn extract_steps_expected(body: &str) -> (String, String) {
    // 先找「预期」分段；前面归为 steps，后面归为 expected。
    let markers = ["**预期**", "**期望**", "### 预期", "#### 预期"];
    if let Some(pos) = markers.iter().filter_map(|m| body.find(m)).min() {
        let steps = body[..pos].trim().to_string();
        let expected = body[pos..].trim().to_string();
        return (steps, expected);
    }

    // 无明确预期段落：整个 body 作为 steps，expected 留空。
    (body.to_string(), String::new())
}

fn detect_method(body: &str) -> AcceptanceMethod {
    let keywords = [
        "自动化",
        "自动",
        "Playwright",
        "自动冒烟",
        "npm test",
        "cargo test",
        "pytest",
    ];
    let lowered = body.to_lowercase();
    if keywords.iter().any(|k| lowered.contains(&k.to_lowercase())) {
        AcceptanceMethod::Auto
    } else {
        AcceptanceMethod::Manual
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn parses_tc_sections_and_detects_auto() {
        const TEST_CONTENT: &str = "# P05 测试方案\n\n\
            ### TC-01 任务按 chunk 顺序执行\n\
            **触发**：点击开始建造。\n\
            **预期**：任务顺序执行。\n\
            **边界**：LLM 路径含 `..` 时拒绝。\n\n\
            ### TC-02 自动冒烟\n\
            **触发**：运行 Playwright。\n\
            **预期**：截图留证。\n";
        let tmp = TempDir::new().unwrap();
        let dir = tmp.path().join(TEST_PLAN_DIR);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("P05_test.md"), TEST_CONTENT).unwrap();

        let drafts = parse_project_test_plans(tmp.path()).unwrap();
        assert_eq!(drafts.len(), 2);
        assert_eq!(drafts[0].tc_id, "TC-01");
        assert_eq!(drafts[0].method, AcceptanceMethod::Manual);
        assert!(drafts[0].steps.contains("点击开始建造"));
        assert!(drafts[0].expected.contains("任务顺序执行"));
        assert_eq!(drafts[1].tc_id, "TC-02");
        assert_eq!(drafts[1].method, AcceptanceMethod::Auto);
    }

    #[test]
    fn fallback_when_no_heading() {
        let tmp = TempDir::new().unwrap();
        let dir = tmp.path().join(TEST_PLAN_DIR);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("vague.md"), "随便写点验收内容。").unwrap();
        let drafts = parse_project_test_plans(tmp.path()).unwrap();
        assert_eq!(drafts.len(), 1);
        assert_eq!(drafts[0].tc_id, "TC-FALLBACK");
        assert_eq!(drafts[0].method, AcceptanceMethod::Manual);
    }

    #[test]
    fn empty_dir_returns_empty() {
        let tmp = TempDir::new().unwrap();
        let dir = tmp.path().join(TEST_PLAN_DIR);
        std::fs::create_dir_all(&dir).unwrap();
        let drafts = parse_project_test_plans(tmp.path()).unwrap();
        assert!(drafts.is_empty());
    }
}
