use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::State;

const OFFLINE_TOKEN_SEPARATOR: char = '|';
const HMAC_ALGORITHM_ERROR: &str = "HMAC algorithm error";

pub struct OfflineTokenState {
    token: Mutex<Option<String>>,
    set_at: Mutex<Option<Instant>>,
    offline_duration_secs: Mutex<i64>,
}

impl OfflineTokenState {
    pub fn new() -> Self {
        Self {
            token: Mutex::new(None),
            set_at: Mutex::new(None),
            offline_duration_secs: Mutex::new(0),
        }
    }

    /// 检查指定模块是否在离线授权列表中且未过期。
    pub fn is_module_allowed(&self, module_code: &str) -> bool {
        let token = match self.token.lock() {
            Ok(guard) => guard.clone(),
            Err(_) => return false,
        };
        let token = match token {
            Some(t) => t,
            None => return false,
        };

        let set_at = match self.set_at.lock() {
            Ok(guard) => *guard,
            Err(_) => return false,
        };
        let set_at = match set_at {
            Some(t) => t,
            None => return false,
        };

        let offline_duration_secs = match self.offline_duration_secs.lock() {
            Ok(guard) => *guard,
            Err(_) => return false,
        };

        if set_at.elapsed() > Duration::from_secs(offline_duration_secs.max(0) as u64) {
            return false;
        }

        let payload = match verify_offline_token_signature(&token) {
            Ok(p) => p,
            Err(_) => return false,
        };

        payload.allowed_modules.contains(&module_code.to_string())
    }
}

#[derive(serde::Serialize)]
pub struct OfflineAccessResult {
    allowed: bool,
    reason: String,
}

#[tauri::command]
pub fn set_offline_token(
    token: String,
    offline_seconds: i64,
    state: State<OfflineTokenState>,
) -> Result<(), String> {
    if offline_seconds <= 0 {
        return Err("offline_seconds must be positive".to_string());
    }
    *state.token.lock().map_err(|e| e.to_string())? = Some(token);
    *state.set_at.lock().map_err(|e| e.to_string())? = Some(Instant::now());
    *state.offline_duration_secs.lock().map_err(|e| e.to_string())? = offline_seconds;
    Ok(())
}

#[tauri::command]
pub fn clear_offline_token(state: State<OfflineTokenState>) -> Result<(), String> {
    *state.token.lock().map_err(|e| e.to_string())? = None;
    *state.set_at.lock().map_err(|e| e.to_string())? = None;
    *state.offline_duration_secs.lock().map_err(|e| e.to_string())? = 0;
    Ok(())
}

#[tauri::command]
pub fn check_offline_access(
    module_code: String,
    state: State<OfflineTokenState>,
) -> Result<OfflineAccessResult, String> {
    if state.is_module_allowed(&module_code) {
        Ok(OfflineAccessResult {
            allowed: true,
            reason: String::new(),
        })
    } else {
        Ok(OfflineAccessResult {
            allowed: false,
            reason: "该模块未在离线授权中或授权已过期".to_string(),
        })
    }
}

struct OfflineTokenPayload {
    #[allow(dead_code)]
    user_id: i64,
    #[allow(dead_code)]
    device_id: String,
    allowed_modules: Vec<String>,
}

fn verify_offline_token_signature(token: &str) -> Result<OfflineTokenPayload, String> {
    let decoded_bytes = base64::decode(token).map_err(|e| format!("base64 decode failed: {}", e))?;
    let decoded = String::from_utf8(decoded_bytes).map_err(|e| format!("invalid utf8: {}", e))?;
    let separator_pos = decoded
        .rfind(OFFLINE_TOKEN_SEPARATOR)
        .ok_or_else(|| "missing signature separator".to_string())?;

    let payload = &decoded[..separator_pos];
    let signature = &decoded[separator_pos + 1..];

    let secret = jwt_secret();
    let expected = hmac_sign(payload, &secret)?;
    if !constant_time_eq(signature.as_bytes(), expected.as_bytes()) {
        return Err("signature mismatch".to_string());
    }

    parse_payload(payload)
}

fn parse_payload(payload: &str) -> Result<OfflineTokenPayload, String> {
    let parts: Vec<&str> = payload.split(OFFLINE_TOKEN_SEPARATOR).collect();
    if parts.len() != 4 {
        return Err("invalid payload format".to_string());
    }
    let user_id = parts[0]
        .parse::<i64>()
        .map_err(|e| format!("invalid user_id: {}", e))?;
    let device_id = parts[1].to_string();
    let _offline_usable_until_epoch_milli = parts[2]
        .parse::<i64>()
        .map_err(|e| format!("invalid offline usable until: {}", e))?;
    let allowed_modules = if parts[3].is_empty() {
        Vec::new()
    } else {
        parts[3].split(',').map(|s| s.to_string()).collect()
    };

    Ok(OfflineTokenPayload {
        user_id,
        device_id,
        allowed_modules,
    })
}

fn hmac_sign(data: &str, secret: &str) -> Result<String, String> {
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes())
        .map_err(|_| HMAC_ALGORITHM_ERROR.to_string())?;
    mac.update(data.as_bytes());
    let result = mac.finalize();
    let bytes = result.into_bytes();
    Ok(base64::encode(&bytes))
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

fn jwt_secret() -> String {
    std::env::var("FILE_KEEPER_JWT_SECRET")
        .unwrap_or_else(|_| "file-keeper-local-dev-jwt-secret-at-least-32-bytes".to_string())
}

mod base64 {
    pub fn decode(input: &str) -> Result<Vec<u8>, String> {
        let mut output = Vec::with_capacity(input.len() * 3 / 4);
        let mut buffer = 0u32;
        let mut bits = 0u32;

        for ch in input.chars() {
            let value = match ch {
                'A'..='Z' => ch as u32 - 'A' as u32,
                'a'..='z' => ch as u32 - 'a' as u32 + 26,
                '0'..='9' => ch as u32 - '0' as u32 + 52,
                '-' => 62,
                '_' => 63,
                '=' => break,
                _ => return Err(format!("invalid base64 character: {}", ch)),
            };
            buffer = (buffer << 6) | value;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                output.push(((buffer >> bits) & 0xFF) as u8);
            }
        }
        Ok(output)
    }

    pub fn encode(input: &[u8]) -> String {
        const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut output = String::with_capacity((input.len() + 2) / 3 * 4);
        let mut buffer = 0u32;
        let mut bits = 0u32;

        for &byte in input {
            buffer = (buffer << 8) | byte as u32;
            bits += 8;
            while bits >= 6 {
                bits -= 6;
                output.push(ALPHABET[((buffer >> bits) & 0x3F) as usize] as char);
            }
        }

        if bits > 0 {
            buffer <<= 6 - bits;
            output.push(ALPHABET[(buffer & 0x3F) as usize] as char);
        }

        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verify_known_backend_token() {
        // Generated by backend AuthorizationService with secret "file-keeper-local-dev-jwt-secret-at-least-32-bytes"
        let token = "Mnx0ZXN0LWRldmljZS1tYWlufDE3ODE1MjkwNjYzNTR8ZmlsZXMsY2xpcGJvYXJkfGk0VjA1aV8xd2NQTFpiS2U3NEFyYnRnTHNUbG1pOGtwcFp4RTRSUnAzRmc";
        std::env::set_var("FILE_KEEPER_JWT_SECRET", "file-keeper-local-dev-jwt-secret-at-least-32-bytes");
        let payload = verify_offline_token_signature(token).expect("token should be valid");
        assert_eq!(payload.user_id, 2);
        assert_eq!(payload.device_id, "test-device-main");
        assert!(payload.allowed_modules.contains(&"files".to_string()));
        assert!(payload.allowed_modules.contains(&"clipboard".to_string()));
    }

    #[test]
    fn reject_tampered_token() {
        let token = "Mnx0ZXN0LWRldmljZS1tYWlufDE3ODE1MjkwNjYzNTR8ZmlsZXMsY2xpcGJvYXJkfGk0VjA1aV8xd2NQTFpiS2U3NEFyYnRnTHNUbG1pOGtwcFp4RTRSUnAzRmc";
        std::env::set_var("FILE_KEEPER_JWT_SECRET", "file-keeper-local-dev-jwt-secret-at-least-32-bytes");
        let mut tampered = token.to_string();
        tampered.pop();
        tampered.push('A');
        assert!(verify_offline_token_signature(&tampered).is_err());
    }

    #[test]
    fn is_module_allowed_returns_true_for_entitled_module() {
        std::env::set_var("FILE_KEEPER_JWT_SECRET", "file-keeper-local-dev-jwt-secret-at-least-32-bytes");
        let secret = jwt_secret();
        let payload = "2|test-device-main|1781529066354|work-report";
        let signature = hmac_sign(payload, &secret).unwrap();
        let token = base64::encode(format!("{}|{}", payload, signature).as_bytes());

        let state = OfflineTokenState::new();
        {
            let mut token_guard = state.token.lock().unwrap();
            *token_guard = Some(token);
            let mut set_at_guard = state.set_at.lock().unwrap();
            *set_at_guard = Some(Instant::now());
            let mut duration_guard = state.offline_duration_secs.lock().unwrap();
            *duration_guard = 3600;
        }

        assert!(state.is_module_allowed("work-report"));
    }

    #[test]
    fn is_module_allowed_returns_false_for_unentitled_module() {
        std::env::set_var("FILE_KEEPER_JWT_SECRET", "file-keeper-local-dev-jwt-secret-at-least-32-bytes");
        let secret = jwt_secret();
        let payload = "2|test-device-main|1781529066354|work-report";
        let signature = hmac_sign(payload, &secret).unwrap();
        let token = base64::encode(format!("{}|{}", payload, signature).as_bytes());

        let state = OfflineTokenState::new();
        {
            let mut token_guard = state.token.lock().unwrap();
            *token_guard = Some(token);
            let mut set_at_guard = state.set_at.lock().unwrap();
            *set_at_guard = Some(Instant::now());
            let mut duration_guard = state.offline_duration_secs.lock().unwrap();
            *duration_guard = 3600;
        }

        assert!(!state.is_module_allowed("files"));
    }

    #[test]
    fn is_module_allowed_returns_false_when_token_expired() {
        std::env::set_var("FILE_KEEPER_JWT_SECRET", "file-keeper-local-dev-jwt-secret-at-least-32-bytes");
        let secret = jwt_secret();
        let payload = "2|test-device-main|1781529066354|work-report";
        let signature = hmac_sign(payload, &secret).unwrap();
        let token = base64::encode(format!("{}|{}", payload, signature).as_bytes());

        let state = OfflineTokenState::new();
        {
            let mut token_guard = state.token.lock().unwrap();
            *token_guard = Some(token);
            let mut set_at_guard = state.set_at.lock().unwrap();
            *set_at_guard = Some(Instant::now());
            let mut duration_guard = state.offline_duration_secs.lock().unwrap();
            *duration_guard = -1;
        }

        assert!(!state.is_module_allowed("work-report"));
    }

    #[test]
    fn is_module_allowed_returns_false_when_no_token() {
        let state = OfflineTokenState::new();
        assert!(!state.is_module_allowed("work-report"));
    }
}
