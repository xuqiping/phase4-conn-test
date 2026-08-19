// 环境探测：扫描项目根，识别技术栈、包管理器、测试命令、运行时。
// 对应 FR-005/AC-005。探测结果进 EnvProfile 并缓存。

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// 识别出的栈。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum Stack {
    Node,
    Python,
    Rust,
    Go,
    Ruby,
    Php,
    Unknown(String),
}

/// 环境画像。
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct EnvProfile {
    pub stacks: Vec<Stack>,
    /// 运行时存在性/版本字符串（不一定能取到版本）。
    pub runtimes: HashMap<String, Option<String>>,
    /// 测试命令候选（按优先级）。
    pub test_commands: Vec<Vec<String>>,
    /// lint 命令候选。
    pub lint_commands: Vec<Vec<String>>,
    /// 安装命令候选（包管理器 install）。
    pub install_commands: Vec<Vec<String>>,
}

/// 探测某项目目录。
pub fn probe(project_dir: &Path) -> EnvProfile {
    let mut profile = EnvProfile::default();

    if project_dir.join("package.json").is_file() {
        profile.stacks.push(Stack::Node);
        profile
            .runtimes
            .insert("node".into(), runtime_version("node", "--version"));
        profile
            .test_commands
            .push(vec!["npm".into(), "test".into()]);
        profile
            .lint_commands
            .push(vec!["npx".into(), "eslint".into(), ".".into()]);
        profile
            .install_commands
            .push(vec!["npm".into(), "install".into()]);
    }

    if project_dir.join("requirements.txt").is_file()
        || project_dir.join("pyproject.toml").is_file()
    {
        profile.stacks.push(Stack::Python);
        profile
            .runtimes
            .insert("python".into(), runtime_version("python", "--version"));
        profile.test_commands.push(vec!["pytest".into()]);
        profile
            .lint_commands
            .push(vec!["flake8".into(), ".".into()]);
        profile.install_commands.push(vec![
            "pip".into(),
            "install".into(),
            "-r".into(),
            "requirements.txt".into(),
        ]);
    }

    if project_dir.join("Cargo.toml").is_file() {
        profile.stacks.push(Stack::Rust);
        profile
            .runtimes
            .insert("cargo".into(), runtime_version("cargo", "--version"));
        profile
            .test_commands
            .push(vec!["cargo".into(), "test".into()]);
        profile
            .lint_commands
            .push(vec!["cargo".into(), "clippy".into()]);
    }

    if project_dir.join("go.mod").is_file() {
        profile.stacks.push(Stack::Go);
        profile
            .runtimes
            .insert("go".into(), runtime_version("go", "version"));
        profile
            .test_commands
            .push(vec!["go".into(), "test".into(), "./...".into()]);
    }

    if project_dir.join("Gemfile").is_file() {
        profile.stacks.push(Stack::Ruby);
        profile
            .runtimes
            .insert("ruby".into(), runtime_version("ruby", "--version"));
        profile
            .test_commands
            .push(vec!["bundle".into(), "exec".into(), "rspec".into()]);
        profile
            .install_commands
            .push(vec!["bundle".into(), "install".into()]);
    }

    if project_dir.join("composer.json").is_file() {
        profile.stacks.push(Stack::Php);
        profile
            .runtimes
            .insert("php".into(), runtime_version("php", "--version"));
        profile
            .test_commands
            .push(vec!["composer".into(), "test".into()]);
        profile
            .install_commands
            .push(vec!["composer".into(), "install".into()]);
    }

    if profile.stacks.is_empty() {
        profile.stacks.push(Stack::Unknown("generic".into()));
    }

    profile
}

fn runtime_version(cmd: &str, arg: &str) -> Option<String> {
    match std::process::Command::new(cmd).arg(arg).output() {
        Ok(out) if out.status.success() => {
            Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
        }
        _ => None,
    }
}

/// 计算项目路径的指纹（缓存 key 第一部分）。
pub fn hash_path(path: &Path) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(path.to_string_lossy().as_bytes());
    format!("{:x}", hasher.finalize())[..16].to_string()
}

/// 计算 lockfile 指纹（缓存 key 第二部分）。
/// 优先 lockfile；没有则 hash package 文件内容；都没有则 hash 目录名+当前时间（不强缓存）。
pub fn hash_lockfile(project_dir: &Path) -> String {
    let candidates: Vec<PathBuf> = vec![
        project_dir.join("package-lock.json"),
        project_dir.join("yarn.lock"),
        project_dir.join("pnpm-lock.yaml"),
        project_dir.join("bun.lockb"),
        project_dir.join("Cargo.lock"),
        project_dir.join("poetry.lock"),
        project_dir.join("uv.lock"),
        project_dir.join("Gemfile.lock"),
        project_dir.join("composer.lock"),
        project_dir.join("go.sum"),
        project_dir.join("requirements.txt"),
    ];

    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    let mut any = false;
    for c in candidates {
        if let Ok(data) = std::fs::read(&c) {
            hasher.update(&data);
            any = true;
        }
    }
    if !any {
        hasher.update(project_dir.to_string_lossy().as_bytes());
    }
    format!("{:x}", hasher.finalize())[..16].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn empty_project_is_generic() {
        let tmp = TempDir::new().unwrap();
        let p = probe(tmp.path());
        assert_eq!(p.stacks, vec![Stack::Unknown("generic".into())]);
    }

    #[test]
    fn node_project_detected() {
        let tmp = TempDir::new().unwrap();
        fs::write(tmp.path().join("package.json"), r#"{"name":"x"}"#).unwrap();
        let p = probe(tmp.path());
        assert!(p.stacks.contains(&Stack::Node));
        assert!(p.test_commands.contains(&vec!["npm".into(), "test".into()]));
    }

    #[test]
    fn python_project_detected() {
        let tmp = TempDir::new().unwrap();
        fs::write(tmp.path().join("requirements.txt"), "requests\n").unwrap();
        let p = probe(tmp.path());
        assert!(p.stacks.contains(&Stack::Python));
    }

    #[test]
    fn mixed_stacks_detected() {
        let tmp = TempDir::new().unwrap();
        fs::write(tmp.path().join("package.json"), "{}").unwrap();
        fs::write(tmp.path().join("Cargo.toml"), "[package]").unwrap();
        let p = probe(tmp.path());
        assert!(p.stacks.contains(&Stack::Node));
        assert!(p.stacks.contains(&Stack::Rust));
    }
}
