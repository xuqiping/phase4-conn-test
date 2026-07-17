use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::State;

/// 内嵌公钥 PEM（编译期嵌入二进制）。
/// 生产构建时替换 `resources/public_key.pem` 即可，代码无需改动。
const EMBEDDED_PUBLIC_KEY_PEM: &str = include_str!("../../resources/public_key.pem");

const PAYLOAD_SEPARATOR: char = '|';
const TOKEN_SEPARATOR: char = '.';

/// 客户端授权凭据状态。
pub struct SignedEntitlementState {
    token: Mutex<Option<String>>,
}

impl SignedEntitlementState {
    pub fn new() -> Self {
        Self {
            token: Mutex::new(None),
        }
    }

    /// 校验并解析当前凭据，失败返回结构化错误。
    fn verify_current_token(&self) -> Result<SignedEntitlementPayload, EntitlementError> {
        let token = self
            .token
            .lock()
            .map_err(|_| EntitlementError::Internal("token lock poisoned".into()))?
            .clone()
            .ok_or(EntitlementError::Missing)?;

        let public_key = load_embedded_public_key()?;
        verify_signed_entitlement(&public_key, &token)
    }

    /// 入口守卫：校验签名、有效期、模块权限。
    /// 特权 Tauri 命令应在业务逻辑前调用本方法。
    pub fn require_module(&self, module_code: &str) -> Result<(), EntitlementError> {
        let payload = self.verify_current_token()?;

        if !payload.allowed_modules.contains(&module_code.to_string()) {
            return Err(EntitlementError::ModuleNotAllowed(module_code.into()));
        }

        Ok(())
    }

    /// 与旧 `is_module_allowed` 行为对齐的布尔返回值，供兼容层使用。
    pub fn is_module_allowed(&self, module_code: &str) -> bool {
        self.require_module(module_code).is_ok()
    }
}

impl Default for SignedEntitlementState {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum EntitlementError {
    Missing,
    InvalidFormat(String),
    SignatureMismatch,
    Expired,
    ModuleNotAllowed(String),
    Internal(String),
}

impl EntitlementError {
    pub fn user_message(&self) -> String {
        match self {
            EntitlementError::Missing => "缺少授权凭据，请先登录或刷新授权".into(),
            EntitlementError::InvalidFormat(reason) => format!("授权凭据格式无效: {}", reason),
            EntitlementError::SignatureMismatch => "授权凭据签名无效".into(),
            EntitlementError::Expired => "授权凭据已过期，请联网刷新".into(),
            EntitlementError::ModuleNotAllowed(code) => format!("未授权访问 {} 模块", code),
            EntitlementError::Internal(reason) => format!("授权校验内部错误: {}", reason),
        }
    }
}

impl std::fmt::Display for EntitlementError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.user_message())
    }
}

impl std::error::Error for EntitlementError {}

#[derive(Debug, Clone)]
pub struct SignedEntitlementPayload {
    #[allow(dead_code)]
    pub user_id: i64,
    #[allow(dead_code)]
    pub device_id: String,
    pub issued_at_epoch_milli: i64,
    pub not_after_epoch_milli: i64,
    pub allowed_modules: Vec<String>,
}

#[derive(serde::Serialize)]
pub struct EntitlementAccessResult {
    allowed: bool,
    reason: String,
}

/// 从编译期嵌入的 PEM 加载 Ed25519 公钥。
fn load_embedded_public_key() -> Result<VerifyingKey, EntitlementError> {
    static CACHED: Mutex<Option<VerifyingKey>> = Mutex::new(None);

    if let Ok(guard) = CACHED.lock() {
        if let Some(key) = *guard {
            return Ok(key);
        }
    }

    let raw = decode_public_key_pem(EMBEDDED_PUBLIC_KEY_PEM)?;
    let key = VerifyingKey::from_bytes(&raw)
        .map_err(|e| EntitlementError::Internal(format!("invalid Ed25519 public key: {:?}", e)))?;

    if let Ok(mut guard) = CACHED.lock() {
        *guard = Some(key);
    }
    Ok(key)
}

/// 解析 X.509 SubjectPublicKeyInfo PEM，提取 32 字节 Ed25519 公钥。
///
/// Ed25519 SubjectPublicKeyInfo DER 固定为：
/// 30 2a 30 05 06 03 2b 65 70 03 21 00 <32 bytes>
fn decode_public_key_pem(pem: &str) -> Result<[u8; 32], EntitlementError> {
    let base64_body: String = pem
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with("-----"))
        .collect();

    let der = URL_SAFE_NO_PAD
        .decode(base64_body.as_bytes())
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(base64_body.as_bytes()))
        .map_err(|e| EntitlementError::Internal(format!("public key base64 decode failed: {}", e)))?;

    const EXPECTED_PREFIX: &[u8] = &[0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00];
    if der.len() != 44 || !der.starts_with(EXPECTED_PREFIX) {
        return Err(EntitlementError::Internal(
            "public key PEM is not a valid Ed25519 SubjectPublicKeyInfo".into(),
        ));
    }

    let mut raw = [0u8; 32];
    raw.copy_from_slice(&der[12..44]);
    Ok(raw)
}

/// 验证 signed entitlement token。
fn verify_signed_entitlement(
    public_key: &VerifyingKey,
    token: &str,
) -> Result<SignedEntitlementPayload, EntitlementError> {
    let dot_index = token
        .find(TOKEN_SEPARATOR)
        .ok_or_else(|| EntitlementError::InvalidFormat("missing payload/signature separator".into()))?;

    let payload_b64 = &token[..dot_index];
    let signature_b64 = &token[dot_index + 1..];

    let payload_bytes = URL_SAFE_NO_PAD
        .decode(payload_b64.as_bytes())
        .map_err(|e| EntitlementError::InvalidFormat(format!("payload base64url decode failed: {}", e)))?;
    let payload = String::from_utf8(payload_bytes)
        .map_err(|e| EntitlementError::InvalidFormat(format!("payload is not valid UTF-8: {}", e)))?;

    let signature_bytes: [u8; 64] = URL_SAFE_NO_PAD
        .decode(signature_b64.as_bytes())
        .map_err(|e| EntitlementError::InvalidFormat(format!("signature base64url decode failed: {}", e)))?
        .try_into()
        .map_err(|_| EntitlementError::InvalidFormat("signature length is not 64 bytes".into()))?;
    let signature = Signature::from_bytes(&signature_bytes);

    public_key
        .verify(payload.as_bytes(), &signature)
        .map_err(|_| EntitlementError::SignatureMismatch)?;

    let parsed = parse_payload(&payload)?;

    let now_millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| EntitlementError::Internal(format!("system time error: {}", e)))?
        .as_millis() as i64;

    if now_millis > parsed.not_after_epoch_milli {
        return Err(EntitlementError::Expired);
    }

    Ok(parsed)
}

fn parse_payload(payload: &str) -> Result<SignedEntitlementPayload, EntitlementError> {
    let parts: Vec<&str> = payload.split(PAYLOAD_SEPARATOR).collect();
    if parts.len() != 5 {
        return Err(EntitlementError::InvalidFormat(format!(
            "expected 5 payload fields, got {}",
            parts.len()
        )));
    }

    let user_id = parts[0]
        .parse::<i64>()
        .map_err(|e| EntitlementError::InvalidFormat(format!("invalid user_id: {}", e)))?;
    let device_id = parts[1].to_string();
    let issued_at_epoch_milli = parts[2]
        .parse::<i64>()
        .map_err(|e| EntitlementError::InvalidFormat(format!("invalid issued_at: {}", e)))?;
    let not_after_epoch_milli = parts[3]
        .parse::<i64>()
        .map_err(|e| EntitlementError::InvalidFormat(format!("invalid not_after: {}", e)))?;

    if not_after_epoch_milli < issued_at_epoch_milli {
        return Err(EntitlementError::InvalidFormat(
            "not_after must not be before issued_at".into(),
        ));
    }

    let allowed_modules = if parts[4].is_empty() {
        Vec::new()
    } else {
        parts[4].split(',').map(|s| s.to_string()).collect()
    };

    Ok(SignedEntitlementPayload {
        user_id,
        device_id,
        issued_at_epoch_milli,
        not_after_epoch_milli,
        allowed_modules,
    })
}

#[tauri::command]
pub fn set_signed_entitlement(
    token: String,
    state: State<SignedEntitlementState>,
) -> Result<(), String> {
    *state.token.lock().map_err(|e| e.to_string())? = Some(token);
    Ok(())
}

#[tauri::command]
pub fn clear_signed_entitlement(state: State<SignedEntitlementState>) -> Result<(), String> {
    *state.token.lock().map_err(|e| e.to_string())? = None;
    Ok(())
}

#[tauri::command]
pub fn check_signed_entitlement_access(
    module_code: String,
    state: State<SignedEntitlementState>,
) -> Result<EntitlementAccessResult, String> {
    match state.require_module(&module_code) {
        Ok(()) => Ok(EntitlementAccessResult {
            allowed: true,
            reason: String::new(),
        }),
        Err(e) => Ok(EntitlementAccessResult {
            allowed: false,
            reason: e.user_message(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    // 与 resources/public_key.pem 配对的测试私钥（仅用于测试）。
    const TEST_PRIVATE_KEY_BYTES: [u8; 32] = [
        0x42, 0x3d, 0xb1, 0xb9, 0xaa, 0xac, 0x0e, 0x25,
        0xd2, 0xad, 0x28, 0x8c, 0x22, 0x74, 0x7b, 0xe9,
        0x4e, 0xfc, 0x9f, 0x19, 0xbe, 0xd9, 0x19, 0xa5,
        0x55, 0xd6, 0x2f, 0x97, 0xb8, 0x15, 0x33, 0xea,
    ];

    fn test_signing_key() -> SigningKey {
        SigningKey::from_bytes(&TEST_PRIVATE_KEY_BYTES)
    }

    fn build_token(
        user_id: i64,
        device_id: &str,
        issued_at_milli: i64,
        not_after_milli: i64,
        modules: &[&str],
    ) -> String {
        let payload = format!(
            "{}|{}|{}|{}|{}",
            user_id,
            device_id,
            issued_at_milli,
            not_after_milli,
            modules.join(",")
        );
        let signature = test_signing_key().sign(payload.as_bytes());
        format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(payload.as_bytes()),
            URL_SAFE_NO_PAD.encode(signature.to_bytes())
        )
    }

    #[test]
    fn decode_embedded_public_key_succeeds() {
        let key = load_embedded_public_key().expect("embedded public key should load");
        assert_eq!(key.to_bytes(), test_signing_key().verifying_key().to_bytes());
    }

    #[test]
    fn verify_valid_token_succeeds() {
        let token = build_token(2, "test-device", 1_700_000_000_000, 1_800_000_000_000, &["clipboard"]);
        let public_key = load_embedded_public_key().unwrap();
        let payload = verify_signed_entitlement(&public_key, &token).expect("valid token should verify");
        assert_eq!(payload.user_id, 2);
        assert_eq!(payload.device_id, "test-device");
        assert!(payload.allowed_modules.contains(&"clipboard".to_string()));
    }

    #[test]
    fn require_module_allows_entitled_module() {
        let token = build_token(2, "test-device", 1_700_000_000_000, 1_800_000_000_000, &["work-report"]);
        let state = SignedEntitlementState::new();
        state.token.lock().unwrap().replace(token);
        assert!(state.is_module_allowed("work-report"));
        assert!(state.require_module("work-report").is_ok());
    }

    #[test]
    fn require_module_rejects_unentitled_module() {
        let token = build_token(2, "test-device", 1_700_000_000_000, 1_800_000_000_000, &["work-report"]);
        let state = SignedEntitlementState::new();
        state.token.lock().unwrap().replace(token);
        assert!(!state.is_module_allowed("clipboard"));
        assert!(matches!(
            state.require_module("clipboard"),
            Err(EntitlementError::ModuleNotAllowed(_))
        ));
    }

    #[test]
    fn require_module_rejects_expired_token() {
        let token = build_token(2, "test-device", 1_000_000_000_000, 1_000_000_001_000, &["work-report"]);
        let state = SignedEntitlementState::new();
        state.token.lock().unwrap().replace(token);
        assert!(matches!(
            state.require_module("work-report"),
            Err(EntitlementError::Expired)
        ));
    }

    #[test]
    fn require_module_rejects_tampered_payload() {
        let mut token = build_token(2, "test-device", 1_700_000_000_000, 1_800_000_000_000, &["work-report"]);
        // Tamper with the payload portion: decode, modify module, re-encode while keeping the
        // original signature, so the signature no longer matches the payload.
        let dot_index = token.find('.').unwrap();
        let signature_b64 = token.split_off(dot_index + 1);
        token.pop(); // remove trailing '.'
        let payload_bytes = URL_SAFE_NO_PAD.decode(token.as_bytes()).unwrap();
        let payload = String::from_utf8(payload_bytes).unwrap();
        let tampered = payload.replace("work-report", "clipboard");
        let tampered_b64 = URL_SAFE_NO_PAD.encode(tampered.as_bytes());
        let tampered_token = format!("{}.{}", tampered_b64, signature_b64);

        let state = SignedEntitlementState::new();
        state.token.lock().unwrap().replace(tampered_token);
        assert!(matches!(
            state.require_module("clipboard"),
            Err(EntitlementError::SignatureMismatch)
        ));
    }

    #[test]
    fn require_module_rejects_forged_signature() {
        // A different private key, so the signature will not match the embedded public key.
        const OTHER_PRIVATE_KEY_BYTES: [u8; 32] = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        ];
        let other = SigningKey::from_bytes(&OTHER_PRIVATE_KEY_BYTES);
        let payload = "2|test-device|1700000000000|1800000000000|work-report";
        let signature = other.sign(payload.as_bytes());
        let token = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(payload.as_bytes()),
            URL_SAFE_NO_PAD.encode(signature.to_bytes())
        );
        let state = SignedEntitlementState::new();
        state.token.lock().unwrap().replace(token);
        assert!(matches!(
            state.require_module("work-report"),
            Err(EntitlementError::SignatureMismatch)
        ));
    }

    #[test]
    fn require_module_rejects_missing_token() {
        let state = SignedEntitlementState::new();
        assert!(matches!(
            state.require_module("work-report"),
            Err(EntitlementError::Missing)
        ));
    }

    #[test]
    fn verify_malformed_token_rejected() {
        let public_key = load_embedded_public_key().unwrap();
        assert!(verify_signed_entitlement(&public_key, "not-a-token").is_err());
    }
}
