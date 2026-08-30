//! 工作流 YAML 加载器：外部文件优先，损坏/缺失回退内置默认并告警（plan 坑点表）。

use super::{validator, WorkflowDef};
use std::path::{Path, PathBuf};

/// 内置默认工作流（编译进二进制，永远通过校验——有单测兜底）。
pub const BUILTIN_DEFAULT_YAML: &str = include_str!("../../assets/workflow/default.yaml");

pub struct LoadedWorkflow {
    pub def: WorkflowDef,
    pub source: WorkflowSource,
    /// 降级告警（前端显示大白话）；None = 正常加载
    pub warning: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum WorkflowSource {
    External(PathBuf),
    Builtin,
}

/// 解析 + 校验（供加载器与单测共用）。
pub fn parse(yaml: &str) -> Result<WorkflowDef, String> {
    let def: WorkflowDef = serde_yaml::from_str(yaml).map_err(|e| format!("YAML 解析失败: {e}"))?;
    validator::validate(&def).map_err(|errs| format!("工作流定义非法: {}", errs.join("；")))?;
    Ok(def)
}

/// 加载工作流定义：给了路径先读外部，任何失败都回退内置默认（不崩溃）。
pub fn load(external: Option<&Path>) -> LoadedWorkflow {
    if let Some(p) = external {
        let result = std::fs::read_to_string(p)
            .map_err(|e| e.to_string())
            .and_then(|s| parse(&s));
        match result {
            Ok(def) => {
                return LoadedWorkflow {
                    def,
                    source: WorkflowSource::External(p.to_path_buf()),
                    warning: None,
                };
            }
            Err(e) => {
                return LoadedWorkflow {
                    def: builtin(),
                    source: WorkflowSource::Builtin,
                    warning: Some(format!(
                        "工作流文件 {} 无效（{e}），已回退内置默认版本",
                        p.display()
                    )),
                };
            }
        }
    }
    LoadedWorkflow {
        def: builtin(),
        source: WorkflowSource::Builtin,
        warning: None,
    }
}

fn builtin() -> WorkflowDef {
    parse(BUILTIN_DEFAULT_YAML).expect("内置默认工作流必须永远通过校验")
}
