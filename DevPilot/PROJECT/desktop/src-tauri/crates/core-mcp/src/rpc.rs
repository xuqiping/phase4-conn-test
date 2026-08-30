//! MCP stdio JSON-RPC 编解码（FR-026）。
//! MCP 子进程用「一行一个 JSON」的 JSON-RPC 2.0 通信（LSP 风格但不带 Content-Length 头）。

use serde_json::{json, Value};

/// 造一条请求行（带换行，可直接写 stdin）。
pub fn request(id: i64, method: &str, params: Value) -> String {
    format!(
        "{}\n",
        json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        })
    )
}

/// 造一条通知行（无 id，不需要回应）。
pub fn notification(method: &str, params: Value) -> String {
    format!(
        "{}\n",
        json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        })
    )
}

/// initialize 请求（握手第一步）。
pub fn initialize_request(id: i64, client_name: &str) -> String {
    request(
        id,
        "initialize",
        json!({
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": { "name": client_name, "version": "0.1.0" },
        }),
    )
}

/// initialized 通知（握手第二步，MCP 规范要求）。
pub fn initialized_notification() -> String {
    notification("notifications/initialized", json!({}))
}

/// tools/list 请求。
pub fn tools_list_request(id: i64) -> String {
    request(id, "tools/list", json!({}))
}

/// 解析一行应答：返回 (id, result)；非应答（通知/无效行）返回 None。
pub fn parse_response(line: &str) -> Option<(i64, Value)> {
    let v: Value = serde_json::from_str(line.trim()).ok()?;
    let id = v.get("id")?.as_i64()?;
    // 有 error 字段视为失败：返回 id + null，由调用方判 error。
    if let Some(err) = v.get("error") {
        return Some((id, Value::Null)).map(|_| (id, json!({ "__rpc_error": err })));
    }
    let result = v.get("result").cloned().unwrap_or(Value::Null);
    Some((id, result))
}

/// 解析出来的 result 是否是 RPC 错误（parse_response 打的标）。
pub fn is_rpc_error(result: &Value) -> bool {
    result.get("__rpc_error").is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_and_notification_shapes() {
        let req = initialize_request(1, "devpilot");
        assert!(req.starts_with('{'));
        assert!(req.ends_with('\n'));
        assert!(req.contains("\"method\":\"initialize\""));
        assert!(!notification("x", json!({})).contains("\"id\""));
    }

    #[test]
    fn parse_response_matches_id_and_flags_errors() {
        let ok = parse_response(r#"{"jsonrpc":"2.0","id":7,"result":{"tools":[]}}"#).unwrap();
        assert_eq!(ok.0, 7);
        assert!(!is_rpc_error(&ok.1));
        let err = parse_response(r#"{"jsonrpc":"2.0","id":8,"error":{"code":-32601}}"#).unwrap();
        assert_eq!(err.0, 8);
        assert!(is_rpc_error(&err.1));
        // 通知/垃圾行不是应答
        assert!(parse_response(r#"{"jsonrpc":"2.0","method":"log"}"#).is_none());
        assert!(parse_response("not json").is_none());
    }
}
