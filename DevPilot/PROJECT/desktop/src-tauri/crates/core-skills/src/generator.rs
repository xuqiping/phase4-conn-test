//! 技能生成器 + 导入导出（FR-025 / AC-028 后半）。
//! 生成：把任务上下文（prompt + 轮次摘要）渲染成合法 SKILL.md 并落盘注册，立即可斜杠调用。
//! 导入导出：整目录复制，不发明新打包格式——导出产物本身就是一个可再导入的技能目录。

use std::path::Path;

use core_state::Db;

use crate::registry;
use crate::skill_file::{parse_skill_md, render_skill_md, valid_name, ParsedSkill, SkillMeta};

/// 生成入参：name/description 来自用户表单，task_prompt 是任务原文，rounds_summary 是关键步骤摘要。
pub struct GenInput<'a> {
    pub name: &'a str,
    pub description: &'a str,
    pub task_prompt: &'a str,
    pub rounds_summary: &'a str,
}

/// 从对话/任务上下文生成 SKILL.md 全文。
/// 模板正文 = 任务 prompt 精简 + 关键步骤 + 注意事项占位段（用户后续可手改）。
pub fn generate_from_context(input: &GenInput) -> Result<String, String> {
    if !valid_name(input.name) {
        return Err(format!(
            "技能名「{}」不合法：只允许小写字母/数字/连字符，长度 1~64",
            input.name
        ));
    }
    if input.description.trim().is_empty() {
        return Err("请填一句话描述（斜杠列表要展示它）".into());
    }
    let body = format!(
        "# 任务目标\n\n{}\n\n# 关键步骤\n\n{}\n\n# 注意事项\n\n- （待补充：踩过的坑、边界情况）\n",
        input.task_prompt.trim(),
        if input.rounds_summary.trim().is_empty() {
            "- （无记录，可手写关键步骤）"
        } else {
            input.rounds_summary.trim()
        },
    );
    let meta = SkillMeta {
        name: input.name.to_string(),
        description: input.description.trim().to_string(),
        version: "0.1.0".into(),
    };
    Ok(render_skill_md(&meta, &body))
}

/// 保存生成的 SKILL.md：写 <skills_dir>/<name>/SKILL.md → 扫描注册。
/// 同名目录已存在时**拒绝覆盖**（Err），由用户决定改名或先删旧的。
pub fn save_skill(db: &Db, skills_dir: &Path, text: &str) -> Result<String, String> {
    let meta = match parse_skill_md(text) {
        ParsedSkill::Valid(m) => m,
        ParsedSkill::Invalid(msg) => return Err(format!("生成的内容不合法：{msg}")),
    };
    let skill_dir = skills_dir.join(&meta.name);
    if skill_dir.exists() {
        return Err(format!(
            "已有同名技能「{}」：请换个名字，或先删除旧技能",
            meta.name
        ));
    }
    std::fs::create_dir_all(&skill_dir).map_err(|e| format!("建目录失败：{e}"))?;
    std::fs::write(skill_dir.join("SKILL.md"), text).map_err(|e| format!("写文件失败：{e}"))?;
    registry::scan_and_register(db, skills_dir).map_err(|e| format!("注册失败：{e}"))?;
    Ok(format!(
        "技能「{}」已保存，可用 /{} 调用",
        meta.name, meta.name
    ))
}

/// 导出一个技能：把它的目录整份复制到 dest_dir/<name>/。
/// 返回复制后的目标路径。
pub fn export_skill(db: &Db, id: i64, dest_dir: &Path) -> Result<String, String> {
    let row = db
        .read(|c| core_state::skills_local::list(c, false))
        .map_err(|e| format!("读技能列表失败：{e}"))?
        .into_iter()
        .find(|r| r.id == id)
        .ok_or("没有这个技能记录")?;
    let src = Path::new(&row.yaml_path)
        .parent()
        .ok_or("技能路径不合法")?
        .to_path_buf();
    if !src.exists() {
        return Err(format!("技能目录不存在：{}", src.display()));
    }
    std::fs::create_dir_all(dest_dir).map_err(|e| format!("建导出目录失败：{e}"))?;
    let dest = dest_dir.join(&row.name);
    if dest.exists() {
        return Err(format!("导出目录里已有「{}」，先清理再导", row.name));
    }
    copy_dir(&src, &dest)?;
    Ok(dest.to_string_lossy().to_string())
}

/// 导入：src 是一个技能目录（含 SKILL.md）或装了多个技能目录的文件夹。
/// 逐个复制进 skills_dir，返回 (技能名, 结果消息) 清单——坏的跳过并说明原因，不整体失败。
pub fn import_skills(
    db: &Db,
    skills_dir: &Path,
    src: &Path,
) -> Result<Vec<(String, String)>, String> {
    // 判断 src 本身是不是技能目录（直接含 SKILL.md）
    let candidates: Vec<std::path::PathBuf> = if src.join("SKILL.md").exists() {
        vec![src.to_path_buf()]
    } else {
        match std::fs::read_dir(src) {
            Ok(rd) => rd
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir() && p.join("SKILL.md").exists())
                .collect(),
            Err(e) => return Err(format!("读导入目录失败：{e}（选的路径可能不存在）")),
        }
    };
    if candidates.is_empty() {
        return Err("这个文件夹里没找到任何带 SKILL.md 的技能目录".into());
    }
    let mut results = Vec::new();
    for cand in candidates {
        let name = cand
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let dest = skills_dir.join(&name);
        if dest.exists() {
            results.push((name, "跳过：已有同名技能".into()));
            continue;
        }
        match copy_dir(&cand, &dest).and_then(|_| check_importable(&dest)) {
            Ok(()) => results.push((name, "已导入".into())),
            Err(msg) => {
                // 复制进来但不合法：登记为 invalid 交给注册表展示，还是直接撤掉？
                // 口径：撤掉——导入口只收能用的，坏的当场告诉用户。
                std::fs::remove_dir_all(&dest).ok();
                results.push((name, format!("失败：{msg}")));
            }
        }
    }
    registry::scan_and_register(db, skills_dir).map_err(|e| format!("注册失败：{e}"))?;
    Ok(results)
}

/// 导入的目录必须能被解析（哪怕 SKILL.md 有小问题也当场暴露，不留 invalid 残货）。
fn check_importable(dir: &Path) -> Result<(), String> {
    let text = std::fs::read_to_string(dir.join("SKILL.md"))
        .map_err(|e| format!("读 SKILL.md 失败：{e}"))?;
    match parse_skill_md(&text) {
        ParsedSkill::Valid(_) => Ok(()),
        ParsedSkill::Invalid(msg) => Err(msg),
    }
}

/// 递归复制目录（技能目录通常只有 SKILL.md + 少量资源，量级小）。
fn copy_dir(src: &Path, dest: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dest).map_err(|e| format!("建目录失败：{e}"))?;
    for entry in std::fs::read_dir(src)
        .map_err(|e| format!("读目录失败：{e}"))?
        .flatten()
    {
        let from = entry.path();
        let to = dest.join(entry.file_name());
        if from.is_dir() {
            copy_dir(&from, &to)?;
        } else {
            std::fs::copy(&from, &to).map_err(|e| format!("复制 {} 失败：{e}", from.display()))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_state::Db;

    fn temp_dir(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!(
            "devpilot-gen-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn gen(name: &str, desc: &str) -> String {
        generate_from_context(&GenInput {
            name,
            description: desc,
            task_prompt: "做一个发版检查流程",
            rounds_summary: "- 跑全量测试\n- 过安全扫描",
        })
        .unwrap()
    }

    #[test]
    fn generated_text_is_valid_and_contains_context() {
        let text = gen("release-flow", "发版流程技能");
        assert!(matches!(parse_skill_md(&text), ParsedSkill::Valid(_)));
        let body = crate::skill_file::extract_body(&text).unwrap();
        assert!(body.contains("发版检查流程"), "正文要含任务原文");
        assert!(body.contains("安全扫描"), "正文要含关键步骤");
        assert!(body.contains("注意事项"), "要有注意事项模板段");
    }

    #[test]
    fn bad_name_or_empty_desc_rejected() {
        let err = generate_from_context(&GenInput {
            name: "Bad_Name",
            description: "x",
            task_prompt: "p",
            rounds_summary: "",
        })
        .unwrap_err();
        assert!(err.contains("不合法"));
        let err = generate_from_context(&GenInput {
            name: "ok-name",
            description: "  ",
            task_prompt: "p",
            rounds_summary: "",
        })
        .unwrap_err();
        assert!(err.contains("描述"));
    }

    #[test]
    fn save_registers_immediately_and_conflict_refused() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("save");
        let msg = save_skill(&db, &dir, &gen("save-me", "测试保存")).unwrap();
        assert!(msg.contains("/save-me"), "提示要带斜杠用法：{msg}");
        let rows = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap();
        assert_eq!(rows.len(), 1);
        // 同名再存：Err 而不是覆盖
        let err = save_skill(&db, &dir, &gen("save-me", "重复")).unwrap_err();
        assert!(err.contains("同名"), "冲突要说明：{err}");
        assert_eq!(
            std::fs::read_to_string(dir.join("save-me/SKILL.md")).unwrap(),
            gen("save-me", "测试保存"),
            "原文件未被覆盖"
        );
    }

    #[test]
    fn export_then_import_roundtrip() {
        let db = Db::open_in_memory().unwrap();
        let skills = temp_dir("rt-skills");
        save_skill(&db, &skills, &gen("rt-skill", "往返测试")).unwrap();
        let out = temp_dir("rt-out");
        let id = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap()[0]
            .id;
        let exported = export_skill(&db, id, &out).unwrap();
        assert!(Path::new(&exported).join("SKILL.md").exists());

        // 导入到全新技能目录
        let db2 = Db::open_in_memory().unwrap();
        let dst = temp_dir("rt-dst");
        let results = import_skills(&db2, &dst, &out).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].1.contains("已导入"), "{:?}", results);
        assert!(dst.join("rt-skill/SKILL.md").exists());
    }

    #[test]
    fn import_bad_dir_reports_per_item_and_cleans_up() {
        let db = Db::open_in_memory().unwrap();
        let src = temp_dir("imp-bad");
        // 一个好一个坏
        let good = src.join("good-one");
        std::fs::create_dir_all(&good).unwrap();
        std::fs::write(
            good.join("SKILL.md"),
            "---\nname: good-one\ndescription: 好\n---\nx",
        )
        .unwrap();
        let bad = src.join("bad-one");
        std::fs::create_dir_all(&bad).unwrap();
        std::fs::write(bad.join("SKILL.md"), "---\nname: [oops\n---\nx").unwrap();
        let dst = temp_dir("imp-dst");
        let results = import_skills(&db, &dst, &src).unwrap();
        assert_eq!(results.len(), 2);
        assert!(results
            .iter()
            .any(|(n, r)| n == "good-one" && r.contains("已导入")));
        assert!(results
            .iter()
            .any(|(n, r)| n == "bad-one" && r.contains("失败")));
        assert!(!dst.join("bad-one").exists(), "坏的当场撤掉不留残目录");
        // 空目录给整体 Err
        let empty = temp_dir("imp-empty");
        assert!(import_skills(&db, &dst, &empty).is_err());
    }

    /// AC-028 e2e：保存 → 注册表立即可见 → 斜杠调用能取到展开文本。
    #[test]
    fn ac028_save_then_list_then_invoke_e2e() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("e2e");
        let text = gen("e2e-skill", "端到端验证");
        save_skill(&db, &dir, &text).expect("保存成功");

        // list：enabled + valid，立即可见
        let rows = db
            .read(|c| core_state::skills_local::list(c, true))
            .unwrap();
        assert_eq!(rows.len(), 1, "保存后注册表立即可见");
        assert_eq!(rows[0].status, "valid");

        // invoke：按名字取正文（模拟 commands::invoke_skill 的展开路径）
        let row = db
            .read(|c| core_state::skills_local::by_name(c, "e2e-skill"))
            .unwrap()
            .unwrap();
        assert!(row.enabled);
        let file_text = std::fs::read_to_string(&row.yaml_path).unwrap();
        let body = crate::skill_file::extract_body(&file_text).unwrap();
        assert!(
            body.contains("发版检查流程"),
            "展开文本要含任务原文：{body}"
        );
    }
}
