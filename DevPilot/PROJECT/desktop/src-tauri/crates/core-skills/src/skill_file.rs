//! SKILL.md 解析与渲染（FR-025 / AC-028）。
//! 文件格式 = Claude Skills 规范：开头 `---` YAML frontmatter（name/description/version）
//! + 正文流程。解析失败不 panic，返回大白话原因（登记为 invalid 技能）。

use serde::{Deserialize, Serialize};

/// 解析结果。
#[derive(Debug, Clone, PartialEq)]
pub enum ParsedSkill {
    Valid(SkillMeta),
    /// 文件存在但内容不合法：带大白话原因，注册表标 invalid（不删文件）。
    Invalid(String),
}

/// frontmatter 元数据。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SkillMeta {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_version")]
    pub version: String,
}

fn default_version() -> String {
    "0.1.0".to_string()
}

/// 技能名规则：`[a-z0-9-]`，1~64 字符（与 skills_local 表 CHECK 同口径）。
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.chars().count() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// 找 frontmatter 结束位置：返回「结束 --- 行」在 rest 里的字节偏移。
/// 按行匹配行首 `---`——description/正文里出现 markdown 分隔线（`---`）不会误截
/// （旧实现 find("\n---") 会把正文里的分隔线当成结束标记，P4 审查修正）。
fn frontmatter_end(rest: &str) -> Option<usize> {
    let mut offset = 0usize;
    for line in rest.split_inclusive('\n') {
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.starts_with("---") && trimmed.trim_matches('-').is_empty() {
            return Some(offset);
        }
        offset += line.len();
        // frontmatter 不会太长，超过 64 行还没结束就当没有
        if offset > 8 * 1024 {
            return None;
        }
    }
    None
}

/// 解析 SKILL.md 全文。
pub fn parse_skill_md(text: &str) -> ParsedSkill {
    let rest = match text
        .strip_prefix("---\n")
        .or_else(|| text.strip_prefix("---\r\n"))
    {
        Some(r) => r,
        None => {
            return ParsedSkill::Invalid("文件开头必须是 --- 包起来的说明块（frontmatter）".into());
        }
    };
    let end = match frontmatter_end(rest) {
        Some(i) => i,
        None => return ParsedSkill::Invalid("说明块没有结束：缺少第二个 ---".into()),
    };
    let yaml = &rest[..end];
    let meta: SkillMeta = match serde_yaml::from_str(yaml) {
        Ok(m) => m,
        Err(e) => {
            return ParsedSkill::Invalid(format!("说明块不是合法 YAML：{e}"));
        }
    };
    if meta.name.trim().is_empty() {
        return ParsedSkill::Invalid("缺少 name（技能的斜杠命令名）".into());
    }
    if !valid_name(&meta.name) {
        return ParsedSkill::Invalid(format!(
            "name「{}」不合法：只允许小写字母/数字/连字符，长度 1~64",
            meta.name
        ));
    }
    if meta.description.trim().is_empty() {
        return ParsedSkill::Invalid("缺少 description（技能用途一句话，斜杠列表要展示）".into());
    }
    ParsedSkill::Valid(meta)
}

/// 取正文（frontmatter 之后的内容）——斜杠调用时注入任务输入的部分。
pub fn extract_body(text: &str) -> Option<&str> {
    let rest = text
        .strip_prefix("---\n")
        .or_else(|| text.strip_prefix("---\r\n"))?;
    let end = frontmatter_end(rest)?;
    // 跳过结束 --- 行本身
    let after = &rest[end..];
    let after = after.strip_prefix("---")?;
    Some(after.trim_start_matches(['\r', '\n']))
}

/// 渲染 SKILL.md（生成器复用，保证写出的一定能被 parse 读回）。
pub fn render_skill_md(meta: &SkillMeta, body: &str) -> String {
    format!(
        "---\nname: {}\ndescription: {}\nversion: {}\n---\n\n{}\n",
        meta.name,
        meta.description,
        meta.version,
        body.trim_end()
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD: &str = "---\nname: release-check\ndescription: 上线前检查清单\nversion: 0.1.0\n---\n\n1. 跑测试\n2. 看安全扫描\n";

    #[test]
    fn parses_valid_skill() {
        match parse_skill_md(GOOD) {
            ParsedSkill::Valid(m) => {
                assert_eq!(m.name, "release-check");
                assert_eq!(m.version, "0.1.0");
            }
            ParsedSkill::Invalid(msg) => panic!("合法文件被判无效：{msg}"),
        }
        assert_eq!(extract_body(GOOD), Some("1. 跑测试\n2. 看安全扫描\n"));
    }

    #[test]
    fn invalid_cases_give_plain_reasons() {
        // 缺开头 ---
        assert!(matches!(
            parse_skill_md("name: x"),
            ParsedSkill::Invalid(m) if m.contains("frontmatter")
        ));
        // 坏 YAML
        assert!(matches!(
            parse_skill_md("---\nname: [broken\n---\n正文"),
            ParsedSkill::Invalid(m) if m.contains("YAML")
        ));
        // 名字非法
        assert!(matches!(
            parse_skill_md("---\nname: Bad_Name!\ndescription: x\n---\n正文"),
            ParsedSkill::Invalid(m) if m.contains("不合法")
        ));
        // 缺 description
        assert!(matches!(
            parse_skill_md("---\nname: ok-name\n---\n正文"),
            ParsedSkill::Invalid(m) if m.contains("description")
        ));
        // 没有第二个 ---
        assert!(matches!(
            parse_skill_md("---\nname: ok\ndescription: x\n"),
            ParsedSkill::Invalid(m) if m.contains("结束")
        ));
    }

    #[test]
    fn render_roundtrips_through_parse() {
        let meta = SkillMeta {
            name: "daily-report".into(),
            description: "生成项目日报".into(),
            version: "0.2.0".into(),
        };
        let text = render_skill_md(&meta, "1. 汇总今日任务\n2. 输出 markdown");
        assert_eq!(parse_skill_md(&text), ParsedSkill::Valid(meta));
        assert!(extract_body(&text).unwrap().contains("汇总今日任务"));
    }

    #[test]
    fn version_defaults_when_missing() {
        let text = "---\nname: no-ver\ndescription: 没写版本\n---\n正文";
        assert_eq!(
            parse_skill_md(text),
            ParsedSkill::Valid(SkillMeta {
                name: "no-ver".into(),
                description: "没写版本".into(),
                version: "0.1.0".into(),
            })
        );
    }

    #[test]
    fn body_separator_line_does_not_truncate() {
        // 正文里出现 markdown 分隔线（--- 独立行）不能被当成 frontmatter 结束
        let text =
            "---\nname: sep-skill\ndescription: 带分隔线的正文\n---\n\n上半\n\n---\n\n下半\n";
        assert!(matches!(parse_skill_md(text), ParsedSkill::Valid(_)));
        let body = extract_body(text).unwrap();
        assert!(body.contains("上半"), "{body}");
        assert!(body.contains("下半"), "分隔线后内容不能丢：{body}");
    }

    #[test]
    fn name_rule() {
        assert!(valid_name("a"));
        assert!(valid_name("release-check-2"));
        assert!(!valid_name(""));
        assert!(!valid_name("ABC"));
        assert!(!valid_name("under_score"));
        assert!(!valid_name("中文"));
        assert!(!valid_name(&"x".repeat(65)));
    }
}
