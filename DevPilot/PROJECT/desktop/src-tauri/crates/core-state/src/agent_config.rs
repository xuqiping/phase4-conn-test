//! 项目约定大白话配置持久化（FR-008 AGENTS.md 自动维护的数据源）。
//!
//! 本模块只负责 `agent_configs` 表的读写与字段 JSON 校验；把字段渲染成 `AGENTS.md`
//! 文本的逻辑在 [`crate::agent_config::render`]（S1 实现）。

use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};

use crate::DbResult;

/// 默认字段列表。零代码用户看到的是这些大白话问题。
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct AgentConfigFields {
    /// 项目一句话定位
    pub positioning: String,
    /// 目标用户画像
    pub target_users: String,
    /// 技术栈偏好
    pub tech_stack: String,
    /// 代码提交规范
    pub commit_style: String,
    /// 安全红线
    pub security_redlines: String,
    /// 文档/注释要求
    pub doc_requirements: String,
    /// 测试红线
    pub testing_redlines: String,
    /// 命名与代码风格
    pub naming_style: String,
}

impl AgentConfigFields {
    /// 首次进入 AGENTS 配置时的引导模板。
    pub fn default_template() -> Self {
        Self {
            positioning: "一个帮助个人开发者把想法快速变成可运行原型的 AI 编程助手".into(),
            target_users: "有产品想法但缺乏完整开发经验的零代码或半代码用户".into(),
            tech_stack: "优先使用 TypeScript + React/Vue + Node.js；Rust 用于性能关键模块".into(),
            commit_style: "中文描述，格式：feat:/fix:/docs:/refactor:/chore: 简述（FR-xxx）".into(),
            security_redlines:
                "禁止把密钥硬编码进代码；禁止暴露数据库明文密码；所有外部请求必须经网关".into(),
            doc_requirements: "关键函数加 Rust doc / JSDoc；复杂决策写 ADR".into(),
            testing_redlines: "每个公共函数至少一个单元测试；提交前必须跑 check_all".into(),
            naming_style: "英文标识符，语义优先，禁止拼音缩写".into(),
        }
    }
}

/// 从数据库读取某项目的配置字段；无记录时返回默认模板。
pub fn load(conn: &Connection, project_id: i64) -> DbResult<AgentConfigFields> {
    let mut stmt = conn.prepare("SELECT fields_json FROM agent_configs WHERE project_id = ?1")?;
    let mut rows = stmt.query([project_id])?;
    if let Some(row) = rows.next()? {
        let json: String = row.get(0)?;
        let fields: AgentConfigFields = serde_json::from_str(&json).unwrap_or_default();
        Ok(fields)
    } else {
        Ok(AgentConfigFields::default_template())
    }
}

/// 保存（或更新）某项目的配置字段。
pub fn save(conn: &mut Connection, project_id: i64, fields: &AgentConfigFields) -> DbResult<()> {
    let json = serde_json::to_string(fields).unwrap_or_else(|_| "{}".into());
    conn.execute(
        "INSERT INTO agent_configs (project_id, fields_json)
         VALUES (?1, ?2)
         ON CONFLICT(project_id) DO UPDATE SET
           fields_json = excluded.fields_json,
           updated_at = datetime('now')",
        params![project_id, json],
    )?;
    Ok(())
}

/// 把字段渲染成项目根 `AGENTS.md` 文本。
///
/// 顶部加「本文件由 DevPilot 自动维护」提示，避免用户手动编辑后被覆盖产生困惑。
pub fn render(fields: &AgentConfigFields) -> String {
    format!(
        "# AGENTS.md · 项目 AI 使用说明书\n\n\
        > ⚠️ 本文件由 DevPilot 自动维护。请通过客户端「项目约定」表单修改，手动编辑会在下次保存时被覆盖。\n\n\
        ## 1. 项目定位\n\n{}\n\n\
        ## 2. 目标用户\n\n{}\n\n\
        ## 3. 技术栈偏好\n\n{}\n\n\
        ## 4. 命名与代码风格\n\n{}\n\n\
        ## 5. 提交规范\n\n{}\n\n\
        ## 6. 安全红线\n\n{}\n\n\
        ## 7. 文档/注释要求\n\n{}\n\n\
        ## 8. 测试红线\n\n{}\n",
        fields.positioning,
        fields.target_users,
        fields.tech_stack,
        fields.naming_style,
        fields.commit_style,
        fields.security_redlines,
        fields.doc_requirements,
        fields.testing_redlines,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Db;

    fn fixture() -> (Db, i64) {
        let db = Db::open_in_memory().unwrap();
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1.20"),
            )?;
            Ok(())
        })
        .unwrap();
        (db, 1)
    }

    #[test]
    fn load_default_when_missing() {
        let (db, pid) = fixture();
        let fields = db.read(|c| load(c, pid)).unwrap();
        assert!(!fields.positioning.is_empty());
    }

    #[test]
    fn save_and_load_roundtrip() {
        let (db, pid) = fixture();
        let mut fields = AgentConfigFields::default_template();
        fields.positioning = "用药提醒小程序".into();
        db.write(|c| save(c, pid, &fields)).unwrap();

        let loaded = db.read(|c| load(c, pid)).unwrap();
        assert_eq!(loaded.positioning, "用药提醒小程序");
    }

    #[test]
    fn render_contains_fields() {
        let mut fields = AgentConfigFields::default_template();
        fields.positioning = "用药提醒小程序".into();
        let md = render(&fields);
        assert!(md.contains("用药提醒小程序"));
        assert!(md.contains("本文件由 DevPilot 自动维护"));
        assert!(md.contains("## 3. 技术栈偏好"));
    }
}
