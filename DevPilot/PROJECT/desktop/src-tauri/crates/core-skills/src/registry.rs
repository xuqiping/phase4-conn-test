//! 技能注册表（FR-025）：扫描 ~/.devpilot/skills/ 同步 skills_local 表。
//! 删除 = 文件移入 skills/.trash/<时间戳>-<name>/（可手工找回），记录软删。

use std::path::{Path, PathBuf};

use core_state::{Db, DbResult};
use rusqlite::Connection;

use crate::skill_file::{parse_skill_md, ParsedSkill};

/// 扫描结果摘要。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScanReport {
    pub valid: usize,
    pub invalid: usize,
}

/// 扫描技能目录并 upsert 注册表（幂等：重复扫描不重复注册）。
/// 目录不存在则创建空目录并返回空报告。
pub fn scan_and_register(db: &Db, skills_dir: &Path) -> DbResult<ScanReport> {
    std::fs::create_dir_all(skills_dir).map_err(core_state::DbError::Io)?;
    let mut report = ScanReport::default();
    let mut seen: Vec<String> = Vec::new();
    let entries = match std::fs::read_dir(skills_dir) {
        Ok(e) => e,
        Err(e) => return Err(core_state::DbError::Io(e)),
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let dir_name = entry.file_name().to_string_lossy().to_string();
        if dir_name == ".trash" {
            continue;
        }
        let skill_md = path.join("SKILL.md");
        let Some(args) = read_skill_dir(&dir_name, &skill_md) else {
            continue;
        };
        seen.push(args.0.clone());
        if args.5 {
            report.valid += 1;
        } else {
            report.invalid += 1;
        }
        let status = if args.5 { "valid" } else { "invalid" };
        db.write(|c| {
            core_state::skills_local::upsert(
                c,
                &core_state::skills_local::SkillUpsert {
                    name: &args.0,
                    display_name: &args.1,
                    description: &args.2,
                    yaml_path: &args.3,
                    version: &args.4,
                    status,
                    status_msg: &args.6,
                },
            )?;
            Ok(())
        })?;
    }
    // 目录里已删除的技能：清掉注册表残留（下次扫描即消失）。
    db.write(|c| remove_stale(c, &seen))?;
    Ok(report)
}

/// 读单个技能目录 → upsert 七元组（name/display/description/path/version/is_valid/status_msg）。
fn read_skill_dir(
    dir_name: &str,
    skill_md: &PathBuf,
) -> Option<(String, String, String, String, String, bool, String)> {
    let text = match std::fs::read_to_string(skill_md) {
        Ok(t) => t,
        Err(_) => {
            return Some((
                dir_name.to_string(),
                dir_name.to_string(),
                String::new(),
                skill_md.to_string_lossy().to_string(),
                "0.0.0".to_string(),
                false,
                "目录里没有 SKILL.md".to_string(),
            ));
        }
    };
    match parse_skill_md(&text) {
        ParsedSkill::Valid(meta) => Some((
            meta.name,
            meta.description.clone(),
            meta.description,
            skill_md.to_string_lossy().to_string(),
            meta.version,
            true,
            String::new(),
        )),
        ParsedSkill::Invalid(msg) => Some((
            dir_name.to_string(),
            dir_name.to_string(),
            String::new(),
            skill_md.to_string_lossy().to_string(),
            "0.0.0".to_string(),
            false,
            msg,
        )),
    }
}

fn remove_stale(conn: &Connection, seen: &[String]) -> DbResult<()> {
    let rows = core_state::skills_local::list(conn, false)?;
    for row in rows {
        if !seen.iter().any(|s| s == &row.name) {
            core_state::skills_local::delete(conn, row.id)?;
        }
    }
    Ok(())
}

/// 删除技能：文件移入 .trash/，记录软删。返回移动后的路径；id 不存在返回 None。
pub fn delete_skill(db: &Db, id: i64, skills_dir: &Path) -> DbResult<Option<PathBuf>> {
    let Some(row) = db
        .read(|c| core_state::skills_local::list(c, false))?
        .into_iter()
        .find(|r| r.id == id)
    else {
        return Ok(None);
    };
    let skill_dir = Path::new(&row.yaml_path)
        .parent()
        .unwrap_or(skills_dir)
        .to_path_buf();
    let trash = skills_dir.join(".trash");
    std::fs::create_dir_all(&trash).map_err(core_state::DbError::Io)?;
    let dest = trash.join(format!(
        "{}-{}",
        chrono_now_compact(),
        skill_dir.file_name().unwrap_or_default().to_string_lossy()
    ));
    std::fs::rename(&skill_dir, &dest).map_err(core_state::DbError::Io)?;
    db.write(|c| core_state::skills_local::delete(c, id))?;
    Ok(Some(dest))
}

/// 紧凑时间戳（仅用于 .trash 目录名，测试可注入——这里取系统时间即可）。
fn chrono_now_compact() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("{now}")
}

/// 技能目录定位：~/.devpilot/skills。
pub fn default_skills_dir() -> PathBuf {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .unwrap_or_default();
    PathBuf::from(home).join(".devpilot").join("skills")
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_state::Db;

    /// 临时技能目录（每个测试独立，避免互相污染）。
    fn temp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "devpilot-skills-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn write_skill(dir: &Path, name: &str, text: &str) {
        let sd = dir.join(name);
        std::fs::create_dir_all(&sd).unwrap();
        std::fs::write(sd.join("SKILL.md"), text).unwrap();
    }

    #[test]
    fn scan_registers_valid_and_invalid() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("scan");
        write_skill(
            &dir,
            "release-check",
            "---\nname: release-check\ndescription: 发版检查\n---\n跑测试",
        );
        write_skill(&dir, "broken", "---\nname: [oops\n---\nx");
        let report = scan_and_register(&db, &dir).unwrap();
        assert_eq!((report.valid, report.invalid), (1, 1));
        let rows = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap();
        assert_eq!(rows.len(), 2);
        let broken = rows.iter().find(|r| r.name == "broken").unwrap();
        assert_eq!(broken.status, "invalid");
        // 幂等：重复扫描不重复注册
        scan_and_register(&db, &dir).unwrap();
        let rows = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap();
        assert_eq!(rows.len(), 2);
    }

    #[test]
    fn scan_removes_stale_after_file_deleted() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("stale");
        write_skill(&dir, "gone", "---\nname: gone\ndescription: 会删\n---\nx");
        scan_and_register(&db, &dir).unwrap();
        std::fs::remove_dir_all(dir.join("gone")).unwrap();
        scan_and_register(&db, &dir).unwrap();
        let rows = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap();
        assert!(rows.is_empty(), "文件没了注册表也不残留");
    }

    #[test]
    fn missing_skill_md_marked_invalid_not_skipped() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("nomd");
        std::fs::create_dir_all(dir.join("empty")).unwrap();
        let report = scan_and_register(&db, &dir).unwrap();
        assert_eq!(report.invalid, 1);
        let rows = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap();
        assert_eq!(rows[0].status_msg, "目录里没有 SKILL.md");
    }

    #[test]
    fn delete_moves_to_trash_and_clears_record() {
        let db = Db::open_in_memory().unwrap();
        let dir = temp_dir("del");
        write_skill(
            &dir,
            "tmp-skill",
            "---\nname: tmp-skill\ndescription: 临时\n---\nx",
        );
        scan_and_register(&db, &dir).unwrap();
        let id = db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap()[0]
            .id;
        let dest = delete_skill(&db, id, &dir).unwrap().expect("存在才能删");
        assert!(dest.to_string_lossy().contains(".trash"));
        assert!(dest.join("SKILL.md").exists(), "文件进回收站可找回");
        assert!(db
            .read(|c| core_state::skills_local::list(c, false))
            .unwrap()
            .is_empty());
    }
}
