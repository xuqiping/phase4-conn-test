use std::path::PathBuf;
use std::process::Command;
use tauri::State;
use serde::{Deserialize, Serialize};
use crate::commands::auth::SignedEntitlementState;

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

const MODULE_CODE: &str = "work-report";

/// 读取本地 Git 日志
/// 敏感操作：访问本地文件系统，必须校验模块授权
#[tauri::command]
pub fn fetch_git_logs(
    entitlement_state: State<SignedEntitlementState>,
    repo_path: String,
    since: String,
    until: Option<String>,
) -> Result<Vec<GitLogEntry>, String> {
    entitlement_state.require_module(MODULE_CODE).map_err(|e| e.user_message())?;

    let path = PathBuf::from(&repo_path);
    if !path.exists() {
        return Err("仓库路径不存在".into());
    }

    let format = "%H%x1f%ad%x1f%s%x1f%an%x1e";
    let mut args: Vec<String> = vec![
        "log".to_string(),
        "--date=iso".to_string(),
        "--pretty=format:".to_string() + format,
    ];

    args.push("--since".to_string());
    args.push(since);

    if let Some(u) = until {
        args.push("--until".to_string());
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
        .split('\x1e')
        .filter(|s| !s.trim().is_empty())
        .map(|entry| {
            let parts: Vec<&str> = entry.split('\x1f').collect();
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
    entitlement_state: State<SignedEntitlementState>,
    title: String,
    body: String,
) -> Result<(), String> {
    entitlement_state.require_module(MODULE_CODE).map_err(|e| e.user_message())?;

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
        let _ = (&title, &body);
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
    entitlement_state: State<SignedEntitlementState>,
    title: String,
    content: String,
) -> Result<ExportReportResult, String> {
    entitlement_state.require_module(MODULE_CODE).map_err(|e| e.user_message())?;

    let downloads_dir = dirs::download_dir()
        .ok_or("无法获取下载目录")?;

    let filename = sanitize_filename(&format!("{}.md", title));
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_git_log() {
        let input = "abc123\x1f2024-01-01\x1fFix bug\x1fAlice\x1e";
        let entries = parse_git_log(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].hash, "abc123");
        assert_eq!(entries[0].date, "2024-01-01");
        assert_eq!(entries[0].message, "Fix bug");
        assert_eq!(entries[0].author, "Alice");
    }

    #[test]
    fn test_parse_git_log_multiple_entries() {
        let input = "abc123\x1f2024-01-01\x1fFix bug\x1fAlice\x1edef456\x1f2024-01-02\x1fAdd feature\x1fBob\x1e";
        let entries = parse_git_log(input);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[1].hash, "def456");
        assert_eq!(entries[1].message, "Add feature");
    }

    #[test]
    fn test_sanitize_filename() {
        assert_eq!(sanitize_filename("日报 2024-01-01.md"), "日报_2024-01-01.md");
    }

    #[test]
    fn test_sanitize_filename_removes_special_chars() {
        assert_eq!(sanitize_filename("report/path:test.md"), "report_path_test.md");
    }
}
