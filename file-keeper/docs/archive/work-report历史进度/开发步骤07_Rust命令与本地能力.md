# 开发步骤 07：Rust 命令与本地能力

> 本步骤实现桌面端 Rust 层的本地能力扩展，包括读取本地 Git 日志、系统通知提醒、报告导出等，并在敏感命令中加入二次授权校验。

---

## 1. 目标

- 实现读取本地 Git 提交日志的 Rust 命令
- 实现系统本地通知命令
- 实现报告导出为 Markdown 文件命令
- 所有敏感命令必须校验 `work-report` 模块授权
- 完成 Rust 单元测试

---

## 2. 前置依赖

- [`开发步骤06_桌面端前端开发.md`](开发步骤06_桌面端前端开发.md) 已完成
- 桌面端前端框架已搭好

---

## 3. 涉及文件

| 文件 | 路径 |
|---|---|
| `work_report.rs` | `file-keeper/src-tauri/src/commands/work_report.rs` |
| `commands/mod.rs` | `file-keeper/src-tauri/src/commands/mod.rs` |
| `main.rs` | `file-keeper/src-tauri/src/main.rs` |
| 类型定义 | `file-keeper/src-tauri/src/models/work_report.rs`（如需要） |
| 测试 | `file-keeper/src-tauri/src/commands/work_report_tests.rs` |

---

## 4. 详细任务

### 4.1 创建 Rust 命令文件

新增 [`src-tauri/src/commands/work_report.rs`](../../src-tauri/src/commands/work_report.rs)：

```rust
use std::path::PathBuf;
use std::process::Command;
use tauri::State;
use serde::{Deserialize, Serialize};
use crate::state::OfflineTokenState;

#[derive(Debug, Serialize, Deserialize)]
pub struct GitLogEntry {
    pub hash: String,
    pub date: String,
    pub message: String,
    pub author: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ExportReportResult {
    pub path: String,
}

/// 读取本地 Git 日志
/// 敏感操作：访问本地文件系统，必须校验模块授权
#[tauri::command]
pub fn fetch_git_logs(
    offline_token_state: State<OfflineTokenState>,
    repo_path: String,
    since: String,
    until: Option<String>,
) -> Result<Vec<GitLogEntry>, String> {
    if !offline_token_state.is_module_allowed("work-report") {
        return Err("未授权访问工作汇报模块".into());
    }

    let path = PathBuf::from(&repo_path);
    if !path.exists() {
        return Err("仓库路径不存在".into());
    }

    let format = "%H%x1f%ad%x1f%s%x1f%an%x1e";
    let mut args = vec![
        "log",
        "--date=iso",
        "--pretty=format:".to_string() + format,
    ];

    args.push("--since");
    args.push(&since);

    if let Some(u) = &until {
        args.push("--until");
        args.push(u);
    }

    let output = Command::new("git")
        .args(&args)
        .current_dir(&path)
        .output()
        .map_err(|e| format!("执行 git 命令失败: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("git log 失败: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let entries = parse_git_log(&stdout);
    Ok(entries)
}

fn parse_git_log(output: &str) -> Vec<GitLogEntry> {
    output
        .split('')
        .filter(|s| !s.trim().is_empty())
        .map(|entry| {
            let parts: Vec<&str> = entry.split('').collect();
            GitLogEntry {
                hash: parts.get(0).unwrap_or(&"").to_string(),
                date: parts.get(1).unwrap_or(&"").to_string(),
                message: parts.get(2).unwrap_or(&"").to_string(),
                author: parts.get(3).unwrap_or(&"").to_string(),
            }
        })
        .collect()
}

/// 显示本地系统通知
#[tauri::command]
pub fn show_work_report_notification(
    offline_token_state: State<OfflineTokenState>,
    title: String,
    body: String,
) -> Result<(), String> {
    if !offline_token_state.is_module_allowed("work-report") {
        return Err("未授权访问工作汇报模块".into());
    }

    #[cfg(target_os = "macos")]
    {
        use std::process::Command;
        Command::new("osascript")
            .args([
                "-e",
                &format!("display notification \"{}\" with title \"{}\"", body, title),
            ])
            .spawn()
            .map_err(|e| e.to_string())?;
    }

    #[cfg(target_os = "windows")]
    {
        // Windows 通知可使用 notify-rust crate 或 winrt-toast
        // 这里预留接口
    }

    #[cfg(target_os = "linux")]
    {
        use std::process::Command;
        Command::new("notify-send")
            .args([&title, &body])
            .spawn()
            .map_err(|e| e.to_string())?;
    }

    Ok(())
}

/// 导出报告为本地 Markdown 文件
#[tauri::command]
pub fn export_report_markdown(
    offline_token_state: State<OfflineTokenState>,
    title: String,
    content: String,
) -> Result<ExportReportResult, String> {
    if !offline_token_state.is_module_allowed("work-report") {
        return Err("未授权访问工作汇报模块".into());
    }

    let downloads_dir = dirs::download_dir()
        .ok_or("无法获取下载目录")?;

    let filename = sanitize_filename(&format!("{}.md", title)
    );
    let path = downloads_dir.join(filename);

    std::fs::write(&path, content)
        .map_err(|e| format!("写入文件失败: {}", e))?;

    Ok(ExportReportResult {
        path: path.to_string_lossy().to_string(),
    })
}

fn sanitize_filename(name: &str) -> String {
    name.replace(|c: char| !c.is_alphanumeric() && c != '-' && c != '_' && c != '.', "_")
}
```

### 4.2 注册模块

修改 [`src-tauri/src/commands/mod.rs`](../../src-tauri/src/commands/mod.rs)：

```rust
pub mod work_report;
```

### 4.3 注册 invoke handler

修改 [`src-tauri/src/main.rs`](../../src-tauri/src/main.rs)：

```rust
use commands::work_report::{fetch_git_logs, show_work_report_notification, export_report_markdown};

.invoke_handler(tauri::generate_handler![
    // ... 已有命令
    fetch_git_logs,
    show_work_report_notification,
    export_report_markdown,
])
```

### 4.4 前端调用 Rust 命令

在桌面端前端新增 [`src/api/rustWorkReport.ts`](../../src/api/rustWorkReport.ts)：

```ts
import { invoke } from '@tauri-apps/api/tauri'

export interface GitLogEntry {
  hash: string
  date: string
  message: string
  author: string
}

export async function fetchGitLogs(repoPath: string, since: string, until?: string): Promise<GitLogEntry[]> {
  return invoke<GitLogEntry[]>('fetch_git_logs', { repoPath, since, until })
}

export async function showNotification(title: string, body: string): Promise<void> {
  return invoke('show_work_report_notification', { title, body })
}

export async function exportReportMarkdown(title: string, content: string): Promise<{ path: string }> {
  return invoke<{ path: string }>('export_report_markdown', { title, content })
}
```

### 4.5 在 Store 中集成 Git 导入

扩展 `workReportStore`：

```ts
async function importGitLogs(repoPath: string, since: string, until?: string) {
  const logs = await fetchGitLogs(repoPath, since, until)
  for (const log of logs) {
    await saveLog({
      content: `[${log.hash.slice(0, 7)}] ${log.message}`,
      source: 'GIT',
      tags: 'git',
    })
  }
}
```

### 4.6 单元测试

新增 [`src-tauri/src/commands/work_report_tests.rs`](../../src-tauri/src/commands/work_report_tests.rs) 或在 `work_report.rs` 底部加 `#[cfg(test)]`：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_git_log() {
        let input = "abc1232024-01-01Fix bugAlice";
        let entries = parse_git_log(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].hash, "abc123");
        assert_eq!(entries[0].message, "Fix bug");
    }

    #[test]
    fn test_sanitize_filename() {
        assert_eq!(sanitize_filename("日报 2024-01-01.md"), "日报_2024-01-01.md");
    }
}
```

---

## 5. 验收标准

- [ ] `fetch_git_logs` 命令可读取指定 Git 仓库日志
- [ ] `fetch_git_logs` 在未授权时返回错误
- [ ] `show_work_report_notification` 可弹出系统通知
- [ ] `show_work_report_notification` 在未授权时返回错误
- [ ] `export_report_markdown` 可将报告写入下载目录
- [ ] `export_report_markdown` 在未授权时返回错误
- [ ] 前端可调用 Rust 命令导入 Git 日志
- [ ] Rust 单元测试通过

---

## 6. 验证命令

```bash
# Rust 测试
cargo test --manifest-path "file-keeper/src-tauri/Cargo.toml"

# 桌面端构建
npm --prefix "file-keeper" run tauri build
```

---

## 7. 预计工时

**2 天**

---

## 8. 风险与注意事项

| 风险 | 说明 |
|---|---|
| Git 命令跨平台差异 | Windows 上需确保 git 在 PATH 中 |
| 系统通知权限 | macOS/Windows 需要用户授权通知权限 |
| 路径包含特殊字符 | 导出文件名需要 sanitize |
| 离线授权校验 | 参考 `src-tauri/src/commands/auth.rs`，确保校验逻辑一致 |
| 多平台通知实现差异 | MVP 可先实现 macOS，Windows/Linux 后续补齐 |

---

## 9. 下一Step

完成本步骤后，继续执行 [`开发步骤08_测试文档与联调上线.md`](开发步骤08_测试文档与联调上线.md)。

---

*文档版本：v1.0*  
*编写日期：2026-06-21*
