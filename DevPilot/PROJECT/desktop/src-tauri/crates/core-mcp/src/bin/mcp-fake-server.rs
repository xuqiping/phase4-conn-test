//! 测试用假 MCP server（stdio JSON-RPC）。
//! 用法：mcp-fake-server [mode]
//!   ok            —— 正常：应答 initialize / tools/list，长驻（AC-029 崩溃用 kill 模拟）
//!   silent        —— 永不应答（测握手超时）
//!   die-after-init—— 应答 initialize 后立即退出码 1（测异常退出回调）
use std::io::{BufRead, Write};

fn main() {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "ok".into());
    let stdin = std::io::stdin();
    let stdout = std::io::stdout();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        let Some(method) = v.get("method").and_then(|m| m.as_str()) else {
            continue;
        };
        let id = v.get("id").cloned().unwrap_or(serde_json::Value::Null);
        match (mode.as_str(), method) {
            ("silent", _) => continue, // 收到也不应答
            (_, "initialize") => {
                let mut out = stdout.lock();
                let _ = writeln!(
                    out,
                    r#"{{"jsonrpc":"2.0","id":{id},"result":{{"protocolVersion":"2024-11-05","capabilities":{{}},"serverInfo":{{"name":"fake","version":"0.0.1"}}}}}}"#
                );
                let _ = out.flush();
                if mode == "die-after-init" {
                    std::process::exit(1);
                }
            }
            (_, "tools/list") => {
                let mut out = stdout.lock();
                let _ = writeln!(
                    out,
                    r#"{{"jsonrpc":"2.0","id":{id},"result":{{"tools":[{{"name":"echo","description":"回声"}}]}}}}"#
                );
                let _ = out.flush();
            }
            _ => {}
        }
    }
}
