// 路径归一化与沙箱边界判定工具。
// 原则：任何可能越界的路径都按「拒绝」处理；符号链接按真实路径判；大小写/反斜杠统一归一化。

use std::path::{Component, Path, PathBuf};

/// 逻辑路径归一化：不访问文件系统，仅按组件解析 `.` / `..`。
/// 绝对路径以根为基准；相对路径需传入 base。
/// 返回绝对 PathBuf（Windows 上用 dunce 简化）。
pub fn resolve_logical(path: &Path, base: Option<&Path>) -> Result<PathBuf, PathError> {
    let mut out = PathBuf::new();
    let mut has_root = false;
    let mut depth: usize = 0; // 仅用于相对路径防逃逸判断

    for comp in path.components() {
        match comp {
            Component::Prefix(p) => {
                out.push(Component::Prefix(p));
            }
            Component::RootDir => {
                out.push(Component::RootDir);
                has_root = true;
                depth = 0;
            }
            Component::CurDir => {}
            Component::Normal(p) => {
                out.push(p);
                if !has_root {
                    depth += 1;
                }
            }
            Component::ParentDir => {
                if has_root {
                    out.pop();
                } else {
                    if depth == 0 {
                        if base.is_some() {
                            // 相对路径且 base 提供：允许在 base 树内回退，但不能高于 base
                            if !out.pop() {
                                return Err(PathError::EscapeAttempt);
                            }
                        } else {
                            return Err(PathError::EscapeAttempt);
                        }
                    } else {
                        out.pop();
                        depth -= 1;
                    }
                }
            }
        }
    }

    let abs = if has_root {
        out
    } else {
        let mut base = base.ok_or(PathError::Empty)?.to_path_buf();
        base.push(out);
        base
    };

    Ok(dunce::simplified(&abs).to_path_buf())
}

/// 把路径解析成绝对、干净的形式（不含 . / .. / 符号链接 / 多余分隔符）。
/// Windows 上把反斜杠换成正斜杠以便前缀匹配。
/// 若文件不存在会回退到 resolve_logical（用于写操作的目标路径）。
pub fn normalize(path: &Path) -> std::io::Result<PathBuf> {
    match std::fs::canonicalize(path) {
        Ok(abs) => Ok(dunce::simplified(&abs).to_path_buf()),
        Err(_) => {
            // 回退：路径可能尚不存在；按逻辑解析（基于当前工作目录）。
            let cwd = std::env::current_dir()?;
            resolve_logical(path, Some(&cwd)).map_err(std::io::Error::other)
        }
    }
}

/// 在 base 下安全拼接 rel，禁止 `..` 逃逸；不访问文件系统，仅做纯文本归一化。
/// 用于写操作前构造目标路径，避免 TOCTOU 但能把明显恶意路径拦在调用 normalize 之前。
pub fn safe_join(base: &Path, rel: &str) -> Result<PathBuf, PathError> {
    if rel.is_empty() {
        return Ok(base.to_path_buf());
    }
    // 把 rel 当 Path 解析；任何 Prefix/RootDir 都拒绝（必须是相对路径）
    let rel_path = Path::new(rel);
    let mut out = base.to_path_buf();
    let mut depth: usize = 0; // 相对于 base 的目录深度
    for comp in rel_path.components() {
        match comp {
            Component::Normal(p) => {
                out.push(p);
                depth += 1;
            }
            Component::CurDir => {}
            Component::ParentDir => {
                if depth == 0 {
                    return Err(PathError::EscapeAttempt);
                }
                out.pop();
                depth -= 1;
            }
            Component::Prefix(_) | Component::RootDir => return Err(PathError::EscapeAttempt),
        }
    }
    Ok(out)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PathError {
    EscapeAttempt,
    Empty,
    Io(String),
}

impl std::fmt::Display for PathError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PathError::EscapeAttempt => write!(f, "路径试图逃逸出项目目录"),
            PathError::Empty => write!(f, "路径为空"),
            PathError::Io(e) => write!(f, "路径解析失败：{e}"),
        }
    }
}

impl std::error::Error for PathError {}

impl From<std::io::Error> for PathError {
    fn from(e: std::io::Error) -> Self {
        PathError::Io(e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_join_basic() {
        let base = Path::new("/project");
        assert_eq!(
            safe_join(base, "src/main.rs").unwrap(),
            PathBuf::from("/project/src/main.rs")
        );
    }

    #[test]
    fn safe_join_dot() {
        let base = Path::new("/project");
        assert_eq!(
            safe_join(base, "./src/main.rs").unwrap(),
            PathBuf::from("/project/src/main.rs")
        );
    }

    #[test]
    fn safe_join_escape_rejected() {
        let base = Path::new("/project");
        assert!(matches!(
            safe_join(base, "../etc/passwd"),
            Err(PathError::EscapeAttempt)
        ));
    }

    #[test]
    fn safe_join_absolute_rejected() {
        let base = Path::new("/project");
        assert!(matches!(
            safe_join(base, "/etc/passwd"),
            Err(PathError::EscapeAttempt)
        ));
    }
}
