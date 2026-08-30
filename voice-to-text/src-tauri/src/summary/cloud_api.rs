//! OpenAI 兼容 `/v1/chat/completions` 客户端（blocking —— 调用方在 spawn_blocking 里跑，
//! 与 process_ocr 等同模式，避免给 Tauri async runtime 引入 tokio 依赖）。
//!
//! 安全：API Key 只出现在 Authorization 头；错误信息一律脱敏（响应体里若回显 Key 也抹掉）。

use super::SummaryConfig;
use serde::Serialize;
use serde_json::Value;

/// 一条 chat 消息。content 用 Value：纯文本是 String，多模态是分块数组。
#[derive(Debug, Clone, Serialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: Value,
}

impl ChatMessage {
    pub fn system(text: &str) -> Self {
        Self {
            role: "system".into(),
            content: Value::String(text.into()),
        }
    }
    pub fn user(text: String) -> Self {
        Self {
            role: "user".into(),
            content: Value::String(text),
        }
    }
    /// 多模态 user 消息：文字 + 若干 base64 图块（data URI）。
    pub fn user_with_images(text: String, images: Vec<String>) -> Self {
        let mut parts = vec![serde_json::json!({"type": "text", "text": text})];
        for img in images {
            parts.push(serde_json::json!({
                "type": "image_url",
                "image_url": {"url": format!("data:image/jpeg;base64,{img}")}
            }));
        }
        Self {
            role: "user".into(),
            content: Value::Array(parts),
        }
    }
}

/// base_url 尾斜杠归一后拼端点路径。单独成函数便于单测。
pub fn endpoint_url(base_url: &str, path: &str) -> String {
    format!("{}/{}", base_url.trim_end_matches('/'), path.trim_start_matches('/'))
}

/// 脱敏：错误文本里若出现 Key 一律替换（服务端有时会把请求头回显在报错里）。
pub fn sanitize(msg: &str, api_key: &str) -> String {
    if api_key.is_empty() {
        return msg.to_string();
    }
    msg.replace(api_key, "***")
}

/// 同步调一次 chat/completions，返回 assistant content 文本。
/// 重试：429/5xx/网络错误最多再试 2 次（指数退避 1s/3s）；4xx 其他码不重试。
pub fn chat_blocking(
    cfg: &SummaryConfig,
    api_key: &str,
    model: &str,
    messages: &[ChatMessage],
) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(cfg.timeout_secs))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let url = endpoint_url(&cfg.base_url, "/v1/chat/completions");
    // 不传 temperature：Kimi k3 等模型强制 temperature=1，显式传 0.3 会 400；
    // 缺省 = provider 默认，对所有 OpenAI 兼容端点最安全。
    let body = serde_json::json!({
        "model": model,
        "messages": messages,
        "stream": false,
    });

    let mut last_err = String::new();
    for attempt in 0..3u32 {
        if attempt > 0 {
            std::thread::sleep(std::time::Duration::from_millis((1000 * attempt * attempt) as u64));
        }
        let resp = client
            .post(&url)
            .bearer_auth(api_key)
            .json(&body)
            .send();
        match resp {
            Ok(r) => {
                let status = r.status();
                let text = r.text().unwrap_or_default();
                if status.is_success() {
                    return extract_content(&text).map_err(|e| sanitize(&e, api_key));
                }
                let snippet: String = text.chars().take(300).collect();
                last_err = sanitize(
                    &format!("LLM API {}: {}", status.as_u16(), snippet),
                    api_key,
                );
                // 限流/服务端错误可重试；4xx（鉴权/参数）重试无意义。
                if !(status.as_u16() == 429 || status.is_server_error()) {
                    return Err(last_err);
                }
            }
            Err(e) => {
                last_err = sanitize(&format!("LLM 请求失败: {e}"), api_key);
                // 网络/超时错误可重试。
            }
        }
    }
    Err(last_err)
}

/// 解析响应：choices[0].message.content（String 或分块数组都兼容）。
fn extract_content(body: &str) -> Result<String, String> {
    let v: Value = serde_json::from_str(body).map_err(|e| {
        let snippet: String = body.chars().take(200).collect();
        format!("响应非 JSON: {e}; body: {snippet}")
    })?;
    let content = v
        .pointer("/choices/0/message/content")
        .ok_or_else(|| "响应缺 choices[0].message.content".to_string())?;
    match content {
        Value::String(s) => Ok(s.clone()),
        // 部分 provider content 是分块数组：[{"type":"text","text":"..."}]
        Value::Array(parts) => {
            let mut out = String::new();
            for p in parts {
                if let Some(t) = p.get("text").and_then(|t| t.as_str()) {
                    out.push_str(t);
                }
            }
            if out.is_empty() {
                Err("响应 content 分块数组无 text".into())
            } else {
                Ok(out)
            }
        }
        _ => Err("响应 content 类型不支持".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_join_normalizes_slashes() {
        assert_eq!(
            endpoint_url("https://api.kimi.com/coding/", "/v1/chat/completions"),
            "https://api.kimi.com/coding/v1/chat/completions"
        );
        assert_eq!(
            endpoint_url("https://api.kimi.com/coding", "v1/chat/completions"),
            "https://api.kimi.com/coding/v1/chat/completions"
        );
    }

    #[test]
    fn sanitize_strips_key() {
        assert_eq!(
            sanitize("401 invalid key sk-abcd1234 provided", "sk-abcd1234"),
            "401 invalid key *** provided"
        );
        assert_eq!(sanitize("plain error", ""), "plain error");
    }

    #[test]
    fn extract_content_string_and_chunks() {
        let s = extract_content(r#"{"choices":[{"message":{"content":"你好"}}]}"#).unwrap();
        assert_eq!(s, "你好");
        let c = extract_content(
            r#"{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"#,
        )
        .unwrap();
        assert_eq!(c, "ab");
        assert!(extract_content(r#"{"choices":[]}"#).is_err());
        assert!(extract_content("not json").is_err());
    }
}
