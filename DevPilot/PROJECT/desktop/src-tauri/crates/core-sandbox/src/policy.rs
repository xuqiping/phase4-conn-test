// 沙箱策略：允许/拒绝路径集合 + 默认拒绝开关。
// 对应 FR-001：默认只允许项目目录内，越界操作暂停弹审批。

use std::path::{Path, PathBuf};

use crate::path::{normalize, safe_join, PathError};

/// 判定结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// 路径在允许范围内，可直接放行。
    Allow(PathBuf),
    /// 路径命中显式拒绝清单（如 ~/.ssh、系统目录），直接拒绝。
    Deny(String),
}

/// 沙箱策略。MVP 先实现目录白名单； Seatbelt/Job Object 为 P05/P08 高危任务预留。
#[derive(Debug, Clone)]
pub struct SandboxPolicy {
    pub allow_paths: Vec<PathBuf>,
    pub deny_paths: Vec<PathBuf>,
}

impl SandboxPolicy {
    pub fn new(allow_paths: Vec<PathBuf>) -> Self {
        Self {
            allow_paths,
            deny_paths: default_deny_paths(),
        }
    }

    /// 检查某条绝对/相对路径是否可放行。
    pub fn check(&self, path: &Path) -> Result<Decision, PathError> {
        let normalized = normalize(path)?;

        // 先判拒绝清单（最长前缀优先）。
        for deny in &self.deny_paths {
            if normalized.starts_with(deny) {
                return Ok(Decision::Deny(format!(
                    "命中敏感目录黑名单：{}",
                    deny.display()
                )));
            }
        }

        // 再判白名单。
        for allow in &self.allow_paths {
            if normalized.starts_with(allow) {
                return Ok(Decision::Allow(normalized));
            }
        }

        Ok(Decision::Deny(format!(
            "路径 {} 不在项目白名单内",
            normalized.display()
        )))
    }

    /// 在首个允许路径下安全拼接相对路径。
    pub fn join(&self, rel: &str) -> Result<PathBuf, PathError> {
        let base = self.allow_paths.first().ok_or(PathError::Empty)?;
        safe_join(base, rel)
    }
}

fn default_deny_paths() -> Vec<PathBuf> {
    let mut v = Vec::new();
    // 敏感/系统目录，任何情况下不允许任务进程直接读写。
    if let Some(home) = dirs::home_dir() {
        #[cfg(unix)]
        {
            v.push(home.join(".ssh"));
            v.push(PathBuf::from("/etc"));
            v.push(PathBuf::from("/usr/bin"));
            v.push(PathBuf::from("/usr/local/bin"));
        }
        #[cfg(windows)]
        {
            v.push(
                home.join("AppData")
                    .join("Roaming")
                    .join("Microsoft")
                    .join("Windows"),
            );
            if let Some(root) = home.ancestors().last() {
                v.push(root.join("Windows"));
                v.push(root.join("Program Files"));
                v.push(root.join("Program Files (x86)"));
            }
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn allow_inside_project() {
        let tmp = TempDir::new().unwrap();
        let policy = SandboxPolicy::new(vec![tmp.path().to_path_buf()]);
        let file = tmp.path().join("src/main.rs");
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(&file, "").unwrap();
        assert!(matches!(policy.check(&file).unwrap(), Decision::Allow(_)));
    }

    #[test]
    fn deny_outside_project() {
        let tmp = TempDir::new().unwrap();
        let policy = SandboxPolicy::new(vec![tmp.path().to_path_buf()]);
        let outside = tmp.path().parent().unwrap().join("outside.txt");
        assert!(matches!(policy.check(&outside).unwrap(), Decision::Deny(_)));
    }

    #[test]
    fn deny_symlink_escape() {
        let tmp = TempDir::new().unwrap();
        let policy = SandboxPolicy::new(vec![tmp.path().to_path_buf()]);
        let real = tmp.path().parent().unwrap().join("secret.txt");
        fs::write(&real, "x").unwrap();
        let link = tmp.path().join("escape.txt");
        #[cfg(windows)]
        std::os::windows::fs::symlink_file(&real, &link).unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(&real, &link).unwrap();
        assert!(matches!(policy.check(&link).unwrap(), Decision::Deny(_)));
    }

    #[test]
    fn join_rejects_escape() {
        let tmp = TempDir::new().unwrap();
        let policy = SandboxPolicy::new(vec![tmp.path().to_path_buf()]);
        assert!(policy.join("../etc/passwd").is_err());
    }
}
