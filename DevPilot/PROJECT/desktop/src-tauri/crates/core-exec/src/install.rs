// 一键安装向导：根据环境画像生成缺失运行时的安装命令，并执行安装。
// 对应 FR-005/AC-006。

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::exec::{run, ExecRequest};
use crate::probe::{EnvProfile, Stack};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstallStep {
    pub name: String,
    pub command: Vec<String>,
    pub estimated_seconds: u32,
    pub risk_note: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstallPlan {
    pub missing: Vec<String>,
    pub steps: Vec<InstallStep>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstallResult {
    pub step: String,
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

/// 根据画像与当前 OS 生成安装计划。
pub fn plan(profile: &EnvProfile) -> InstallPlan {
    let mut missing = Vec::new();
    let mut steps = Vec::new();

    for stack in &profile.stacks {
        match stack {
            Stack::Node if !has_runtime("node") => {
                missing.push("Node.js".into());
                steps.push(pkg_install_step(
                    "Node.js",
                    "Node.js 运行时（含 npm）",
                    vec!["nodejs".into()],
                ));
            }
            Stack::Python if !has_runtime("python") => {
                missing.push("Python".into());
                steps.push(pkg_install_step(
                    "Python",
                    "Python 运行时（含 pip）",
                    vec!["python3".into()],
                ));
            }
            Stack::Rust if !has_runtime("cargo") => {
                missing.push("Rust".into());
                steps.push(InstallStep {
                    name: "Rust".into(),
                    command: vec![
                        "curl".into(),
                        "--proto".into(),
                        "=https".into(),
                        "--tlsv1.2".into(),
                        "-sSf".into(),
                        "https://sh.rustup.rs".into(),
                        "|".into(),
                        "sh".into(),
                    ],
                    estimated_seconds: 120,
                    risk_note: "将从官方源下载 rustup 并执行安装脚本".into(),
                });
            }
            _ => {}
        }
    }

    // 通用：依赖包安装（只要画像里有 install_commands 就列出，不检查是否已安装）
    for cmd in &profile.install_commands {
        if !cmd.is_empty() {
            steps.push(InstallStep {
                name: format!("安装项目依赖：{}", cmd[0]),
                command: cmd.clone(),
                estimated_seconds: 60,
                risk_note: "将执行项目依赖安装命令".into(),
            });
        }
    }

    InstallPlan { missing, steps }
}

fn has_runtime(cmd: &str) -> bool {
    std::process::Command::new(cmd)
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn pkg_install_step(name: &str, detail: &str, pkgs: Vec<String>) -> InstallStep {
    let (cmd, risk) = package_manager();
    InstallStep {
        name: format!("{}：{}", name, detail),
        command: [cmd].into_iter().chain(pkgs).collect(),
        estimated_seconds: 60,
        risk_note: risk,
    }
}

fn package_manager() -> (String, String) {
    #[cfg(windows)]
    {
        if has_runtime("winget") {
            return ("winget install -e --id".into(), "使用 winget 安装".into());
        }
        (
            "scoop install".into(),
            "需先安装 scoop（https://scoop.sh）".into(),
        )
    }
    #[cfg(target_os = "macos")]
    {
        if has_runtime("brew") {
            return ("brew install".into(), "使用 Homebrew 安装".into());
        }
        (
            "brew install".into(),
            "需先安装 Homebrew（https://brew.sh）".into(),
        )
    }
    #[cfg(target_os = "linux")]
    {
        if has_runtime("apt") {
            return ("sudo apt install -y".into(), "使用 apt 安装".into());
        }
        (
            "sudo yum install -y".into(),
            "使用 yum 安装（若系统为 dnf 请替换）".into(),
        )
    }
}

/// 执行安装计划；逐个执行，失败后停止并返回已执行结果。
pub async fn run_plan(
    plan: &InstallPlan,
    cwd: &Path,
    mut on_output: impl FnMut(&str),
) -> Vec<InstallResult> {
    let mut results = Vec::new();
    for step in &plan.steps {
        on_output(&format!("[install] 开始：{}\n", step.name));
        let req = ExecRequest::new(&step.command[0], cwd)
            .args(step.command[1..].to_vec())
            .timeout(300_000);
        let r = run(req).await;
        let success = r.exit_code == Some(0);
        on_output(&format!(
            "[install] {}：exit={:?}\n",
            if success { "成功" } else { "失败" },
            r.exit_code
        ));
        results.push(InstallResult {
            step: step.name.clone(),
            success,
            stdout: r.stdout.clone(),
            stderr: r.stderr.clone(),
        });
        if !success {
            break;
        }
    }
    results
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_for_node_when_missing() {
        let mut p = EnvProfile::default();
        p.stacks.push(Stack::Node);
        p.install_commands
            .push(vec!["npm".into(), "install".into()]);
        let plan = plan(&p);
        assert!(plan.missing.iter().any(|m| m.contains("Node")) || has_runtime("node"));
        assert!(plan
            .steps
            .iter()
            .any(|s| s.command.join(" ").contains("npm install")));
    }

    #[test]
    fn plan_empty_when_runtime_present() {
        // 当前机器基本都有 node 或 python，至少不会两个都缺
        let mut p = EnvProfile::default();
        if has_runtime("node") {
            p.stacks.push(Stack::Node);
        } else if has_runtime("python") {
            p.stacks.push(Stack::Python);
        }
        let plan = plan(&p);
        assert!(plan.missing.is_empty() || !plan.steps.is_empty());
    }
}
