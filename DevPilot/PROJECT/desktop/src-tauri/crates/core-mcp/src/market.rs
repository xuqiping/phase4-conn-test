//! MCP 市场（FR-010 / AC-012）：内置目录点选安装，免改配置。
//! 安装链路：目录/手填 JSON → schema 校验 → 运行时探测（缺了给大白话指引，不代装）→
//! 沙箱命令校验（manager.start 内做）→ 落库 → 起进程探测 → running 即「立即可用」。
//!
//! 在线刷新：MVP 先只内置目录（打包内置）；在线目录合并留到设置页接网络层时再加，
//! 加的时候失败必须降级回内置目录（plan 安全清单「网络边界」条目）。

use std::collections::HashMap;
use std::path::Path;

use core_state::Db;
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// 内置市场目录（构建时打进二进制，离线可用）。
pub const BUILTIN_CATALOG: &str = include_str!("../assets/market_catalog.json");

/// 目录里一个 server 的定义。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MarketEntry {
    pub name: String,
    pub description: String,
    /// npx / uvx —— 决定缺运行时时的安装指引文案。
    pub runtime: String,
    pub command: String,
    pub args: Vec<String>,
    #[serde(default)]
    pub env: Vec<EnvSpec>,
}

/// 需要用户填的环境变量说明。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EnvSpec {
    pub key: String,
    pub description: String,
    pub required: bool,
}

/// 解析市场目录文本（schema 校验，坏目录给大白话原因）。
pub fn parse_catalog(text: &str) -> Result<Vec<MarketEntry>, String> {
    let v: Value = serde_json::from_str(text).map_err(|e| format!("目录不是合法 JSON：{e}"))?;
    let servers = v
        .get("servers")
        .and_then(|s| s.as_array())
        .ok_or("目录缺 servers 列表")?;
    if servers.is_empty() {
        return Err("市场目录是空的".into());
    }
    let mut entries = Vec::new();
    for s in servers {
        let e: MarketEntry =
            serde_json::from_value(s.clone()).map_err(|err| format!("server 条目不合法：{err}"))?;
        if e.name.trim().is_empty() || e.command.trim().is_empty() {
            return Err(format!("「{}」缺 name 或 command", e.name));
        }
        entries.push(e);
    }
    Ok(entries)
}

/// 内置目录（常规入口）。
pub fn builtin_catalog() -> Result<Vec<MarketEntry>, String> {
    parse_catalog(BUILTIN_CATALOG)
}

/// 手填 JSON 配置（手动添加 server）：只收白名单字段，多余字段给提示。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ManualConfig {
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
}

/// 解析手填配置（schema 校验：拒绝非法 JSON、缺字段、带危险字符的 name）。
pub fn parse_manual_config(text: &str) -> Result<ManualConfig, String> {
    let cfg: ManualConfig =
        serde_json::from_str(text).map_err(|e| format!("配置不是合法 JSON：{e}"))?;
    if cfg.name.trim().is_empty() {
        return Err("缺 name（server 的显示名）".into());
    }
    if cfg.name.contains('/') || cfg.name.contains('\\') || cfg.name.contains("..") {
        return Err("name 不能包含路径符号".into());
    }
    if cfg.command.trim().is_empty() {
        return Err("缺 command（启动命令）".into());
    }
    if cfg.args.iter().any(|a| a.contains("..")) {
        return Err("args 里不能有路径穿越（..）".into());
    }
    Ok(cfg)
}

/// 允许真跑 `--version` 探测的运行时白名单——手填任意 command 不在此列：
/// 在沙箱审批门之前执行用户命令等于绕门半圈（P4 审查发现），非白名单直接跳过探测，
/// 交给 manager.start 的审批门 + spawn 去暴露问题。
const PROBE_WHITELIST: [&str; 6] = ["npx", "node", "npm", "uvx", "uv", "python"];

/// 探测运行时是否可用（node/npx、uv/uvx 等）。
/// `probe_cmd` 供测试注入（真探测跑 `<command> --version`）。
pub fn detect_runtime(command: &str, probe_cmd: Option<&str>) -> Result<(), String> {
    if probe_cmd.is_none() && !PROBE_WHITELIST.contains(&command) {
        return Ok(()); // 非白名单命令不预执行，由启动链路兜底
    }
    let probe = probe_cmd
        .map(|c| c.to_string())
        .unwrap_or_else(|| format!("{command} --version"));
    let mut parts = probe.split_whitespace();
    let prog = parts.next().unwrap_or(command);
    let args: Vec<&str> = parts.collect();
    match std::process::Command::new(prog).args(&args).output() {
        Ok(out) if out.status.success() => Ok(()),
        Ok(out) => Err(format!(
            "「{command}」存在但执行报错（退出码 {:?}），请检查安装是否完整",
            out.status.code()
        )),
        Err(_) => Err(runtime_hint(command)),
    }
}

/// 缺运行时的大白话安装指引（只指路，不代装——装运行时是用户级决策）。
fn runtime_hint(command: &str) -> String {
    match command {
        "npx" | "node" | "npm" => {
            "没找到 Node.js（npx 命令不可用）。去 https://nodejs.org 下载 LTS 版安装，装完重开 DevPilot 再试。".into()
        }
        "uvx" | "uv" => {
            "没找到 uv（uvx 命令不可用）。安装方法：终端执行 `pip install uv`，或去 https://docs.astral.sh/uv 查看对应系统的安装方式。".into()
        }
        _ => format!("没找到命令「{command}」：请先安装它，或检查是否已加入 PATH 环境变量。"),
    }
}

/// 安装结果。
#[derive(Debug, Serialize, PartialEq)]
pub struct InstallOutcome {
    pub id: i64,
    /// installed_and_running / installed_not_started
    pub outcome: String,
    pub message: String,
}

/// 安装参数：目录条目或手填配置统一转成这个再走同一条链路。
#[derive(Debug, Clone)]
pub struct InstallParams {
    pub name: String,
    pub description: String,
    pub command: String,
    pub args: Vec<String>,
    pub env: HashMap<String, String>,
}

/// 从目录条目 + 用户填的 env 组装安装参数（required 项缺了当场拒）。
pub fn params_from_entry(
    entry: &MarketEntry,
    user_env: &HashMap<String, String>,
) -> Result<InstallParams, String> {
    for spec in &entry.env {
        let filled = user_env
            .get(&spec.key)
            .map(|v| !v.trim().is_empty())
            .unwrap_or(false);
        if spec.required && !filled {
            return Err(format!(
                "「{}」需要先填 {}（{}）",
                entry.name, spec.key, spec.description
            ));
        }
    }
    Ok(InstallParams {
        name: entry.name.clone(),
        description: entry.description.clone(),
        command: entry.command.clone(),
        args: entry.args.clone(),
        env: entry
            .env
            .iter()
            .filter_map(|s| user_env.get(&s.key).map(|v| (s.key.clone(), v.clone())))
            .collect(),
    })
}

/// 安装链路（AC-012）：查重 → 运行时探测 → 落库 →（可选）起进程探测。
/// `manager` 传 None 时只落库不启动（导入配置场景）；起失败不回滚记录，留给用户重试/删除。
pub async fn install(
    db: &Db,
    manager: Option<&crate::Manager>,
    p: InstallParams,
    probe_cmd: Option<&str>,
) -> Result<InstallOutcome, String> {
    if let Some(_row) = db
        .read(|c| core_state::mcp_store::by_name(c, p.name.trim()))
        .map_err(|e| format!("查重失败：{e}"))?
    {
        return Err(format!("已安装过「{}」，不用重复安装", p.name));
    }
    detect_runtime(&p.command, probe_cmd)?;
    let env_json = serde_json::to_string(&p.env).map_err(|e| format!("env 序列化失败：{e}"))?;
    let args_json = serde_json::to_string(&p.args).map_err(|e| format!("args 序列化失败：{e}"))?;
    let id = db
        .write(|c| {
            core_state::mcp_store::insert(
                c,
                p.name.trim(),
                &p.description,
                &p.command,
                &args_json,
                &env_json,
            )
        })
        .map_err(|e| format!("写入安装记录失败：{e}"))?;
    let Some(mgr) = manager else {
        return Ok(InstallOutcome {
            id,
            outcome: "installed_not_started".into(),
            message: format!("「{}」已安装（未启动）", p.name),
        });
    };
    match mgr.start(id).await {
        Ok(_) => Ok(InstallOutcome {
            id,
            outcome: "installed_and_running".into(),
            message: format!("「{}」已安装并启动，立即可用", p.name),
        }),
        Err(e) => Ok(InstallOutcome {
            id,
            outcome: "installed_not_started".into(),
            message: format!("「{}」已安装，但启动失败：{e}（可在管理页重试）", p.name),
        }),
    }
}

/// 离线测试用：市场目录文件也可以从磁盘读（未来在线刷新落盘后复用同一条解析链路）。
pub fn catalog_from_file(path: &Path) -> Result<Vec<MarketEntry>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("读目录文件失败：{e}"))?;
    parse_catalog(&text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_catalog_parses_with_unique_names() {
        let entries = builtin_catalog().expect("内置目录必须合法");
        assert!(
            entries.len() >= 10,
            "内置目录要覆盖常用 server：{}",
            entries.len()
        );
        let mut names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
        names.sort();
        names.dedup();
        assert_eq!(names.len(), entries.len(), "name 不能重复");
        // github 条目要有 required env
        let github = entries.iter().find(|e| e.name == "github").unwrap();
        assert!(github.env.iter().any(|s| s.required));
    }

    #[test]
    fn catalog_schema_errors_are_plain() {
        assert!(parse_catalog("not json").unwrap_err().contains("JSON"));
        assert!(parse_catalog("{}").unwrap_err().contains("servers"));
        assert!(parse_catalog(r#"{"servers":[]}"#)
            .unwrap_err()
            .contains("空"));
        let missing_cmd = r#"{"servers":[{"name":"x","description":"d"}]}"#;
        let msg = parse_catalog(missing_cmd).unwrap_err();
        assert!(
            msg.contains("command") || msg.contains("条目不合法"),
            "缺 command 要被拦：{msg}"
        );
    }

    #[test]
    fn runtime_missing_gives_install_hint() {
        let err =
            detect_runtime("npx", Some("definitely-not-a-real-cmd-xyz --version")).unwrap_err();
        assert!(err.contains("Node.js"), "npx 缺失要指到 nodejs.org：{err}");
        // 有探测命令且成功 → 通过
        assert!(detect_runtime("whatever", Some("cmd /c exit 0")).is_ok());
        // 非白名单命令不预执行（防审批门被绕），直接放行给启动链路兜底
        assert!(detect_runtime("definitely-not-a-real-cmd-xyz", None).is_ok());
    }

    #[test]
    fn manual_config_schema_rejects_bad_input() {
        assert!(parse_manual_config("not json")
            .unwrap_err()
            .contains("JSON"));
        assert!(parse_manual_config(r#"{"command":"x"}"#)
            .unwrap_err()
            .contains("name"));
        assert!(parse_manual_config(r#"{"name":"a/b","command":"x"}"#)
            .unwrap_err()
            .contains("路径"));
        assert!(
            parse_manual_config(r#"{"name":"ok","command":"x","args":["../etc"]}"#)
                .unwrap_err()
                .contains("穿越")
        );
        let ok = parse_manual_config(r#"{"name":"my-server","description":"手填","command":"npx","args":["-y","x"],"env":{"K":"V"}}"#).unwrap();
        assert_eq!(ok.name, "my-server");
    }

    #[test]
    fn params_from_entry_enforces_required_env() {
        let entry = MarketEntry {
            name: "github".into(),
            description: "GitHub".into(),
            runtime: "npx".into(),
            command: "npx".into(),
            args: vec!["-y".into(), "gh".into()],
            env: vec![EnvSpec {
                key: "TOKEN".into(),
                description: "令牌".into(),
                required: true,
            }],
        };
        let empty = HashMap::new();
        let err = params_from_entry(&entry, &empty).unwrap_err();
        assert!(err.contains("TOKEN"));
        let mut filled = HashMap::new();
        filled.insert("TOKEN".to_string(), "t1".to_string());
        let p = params_from_entry(&entry, &filled).unwrap();
        assert_eq!(p.env.get("TOKEN").map(String::as_str), Some("t1"));
    }

    #[tokio::test]
    async fn install_duplicate_rejected_and_runtime_checked_before_insert() {
        let db = Db::open_in_memory().unwrap();
        // 先装一个（不启动）
        let p = InstallParams {
            name: "dup-test".into(),
            description: "d".into(),
            command: "whatever".into(),
            args: vec![],
            env: HashMap::new(),
        };
        let out = install(&db, None, p.clone(), Some("cmd /c exit 0"))
            .await
            .unwrap();
        assert_eq!(out.outcome, "installed_not_started");
        // 重复装 → 拒
        let err = install(&db, None, p, Some("cmd /c exit 0"))
            .await
            .unwrap_err();
        assert!(err.contains("重复"), "{err}");
        // 运行时缺失 → 不落库（探测命令注入不存在的程序）
        let missing = InstallParams {
            name: "no-runtime".into(),
            description: "d".into(),
            command: "npx".into(),
            args: vec![],
            env: HashMap::new(),
        };
        assert!(install(
            &db,
            None,
            missing,
            Some("definitely-not-a-real-cmd-xyz --version")
        )
        .await
        .is_err());
        let rows = db.read(core_state::mcp_store::list).unwrap();
        assert_eq!(rows.len(), 1, "探测失败不落库");
    }
}
