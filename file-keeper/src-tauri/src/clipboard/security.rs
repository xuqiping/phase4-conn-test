use regex::Regex;
use crate::clipboard::types::ClipboardSourceApp;

const SENSITIVE_APPS: &[&str] = &[
    "1password",
    "bitwarden",
    "keepass",
    "keepassxc",
    "enpass",
    "dashlane",
    "lastpass",
];

pub fn is_sensitive_content(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }

    if contains_private_key(trimmed) {
        return Some("private_key".to_string());
    }
    if contains_token(trimmed) {
        return Some("token".to_string());
    }
    if contains_credit_card(trimmed) {
        return Some("credit_card".to_string());
    }
    if looks_like_high_entropy_secret(trimmed) {
        return Some("high_entropy".to_string());
    }

    None
}

pub fn is_sensitive_source(source: &ClipboardSourceApp, excluded_apps: &[String]) -> Option<String> {
    let process = source.process_name.to_lowercase();
    let title = source.window_title.to_lowercase();

    if excluded_apps.iter().any(|app| process.contains(&app.to_lowercase())) {
        return Some("excluded_app".to_string());
    }

    if SENSITIVE_APPS.iter().any(|app| process.contains(app) || title.contains(app)) {
        return Some("sensitive_app".to_string());
    }

    if title.contains("password") || title.contains("密码") || title.contains("passkey") {
        return Some("sensitive_window".to_string());
    }

    None
}

fn contains_private_key(text: &str) -> bool {
    text.contains("-----BEGIN OPENSSH PRIVATE KEY-----")
        || text.contains("-----BEGIN RSA PRIVATE KEY-----")
        || text.contains("-----BEGIN PRIVATE KEY-----")
}

fn contains_token(text: &str) -> bool {
    let jwt = Regex::new(r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$").unwrap();
    let api_key = Regex::new(r"(?i)(api[_-]?key|access[_-]?token|secret)[=:]\s*[A-Za-z0-9_\-]{16,}").unwrap();
    jwt.is_match(text) || api_key.is_match(text)
}

fn contains_credit_card(text: &str) -> bool {
    let digits: String = text.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 13 || digits.len() > 19 {
        return false;
    }
    luhn_valid(&digits)
}

fn luhn_valid(digits: &str) -> bool {
    let mut sum = 0;
    let mut double = false;

    for ch in digits.chars().rev() {
        let Some(mut digit) = ch.to_digit(10) else { return false };
        if double {
            digit *= 2;
            if digit > 9 {
                digit -= 9;
            }
        }
        sum += digit;
        double = !double;
    }

    sum % 10 == 0
}

fn looks_like_high_entropy_secret(text: &str) -> bool {
    if text.len() < 24 || text.len() > 256 || text.contains(' ') {
        return false;
    }

    let has_lower = text.chars().any(|c| c.is_ascii_lowercase());
    let has_upper = text.chars().any(|c| c.is_ascii_uppercase());
    let has_digit = text.chars().any(|c| c.is_ascii_digit());
    if !(has_lower && has_upper && has_digit) {
        return false;
    }

    shannon_entropy(text) >= 4.0
}

fn shannon_entropy(text: &str) -> f64 {
    let mut counts = std::collections::HashMap::new();
    for ch in text.chars() {
        *counts.entry(ch).or_insert(0usize) += 1;
    }

    let len = text.chars().count() as f64;
    counts.values().fold(0.0, |entropy, count| {
        let p = *count as f64 / len;
        entropy - p * p.log2()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_luhn_valid_card_number() {
        let reason = is_sensitive_content("4111 1111 1111 1111");
        assert_eq!(reason.as_deref(), Some("credit_card"));
    }

    #[test]
    fn ignores_non_card_number() {
        let reason = is_sensitive_content("订单编号 4111-2026-0000");
        assert_eq!(reason, None);
    }

    #[test]
    fn detects_jwt_token() {
        let token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.signature";
        let reason = is_sensitive_content(token);
        assert_eq!(reason.as_deref(), Some("token"));
    }

    #[test]
    fn detects_private_key_marker() {
        let reason = is_sensitive_content("-----BEGIN OPENSSH PRIVATE KEY-----\nabc");
        assert_eq!(reason.as_deref(), Some("private_key"));
    }

    #[test]
    fn detects_high_entropy_secret() {
        let reason = is_sensitive_content("X7qP9mK2vB8nR4sT6wY1zA3cD5eF7gH9");
        assert_eq!(reason.as_deref(), Some("high_entropy"));
    }

    #[test]
    fn detects_password_manager_source() {
        let source = ClipboardSourceApp {
            process_name: "Bitwarden.exe".to_string(),
            window_title: "Bitwarden".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &[]);
        assert_eq!(reason.as_deref(), Some("sensitive_app"));
    }

    #[test]
    fn detects_user_excluded_source() {
        let source = ClipboardSourceApp {
            process_name: "notes.exe".to_string(),
            window_title: "Private Notes".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &["notes.exe".to_string()]);
        assert_eq!(reason.as_deref(), Some("excluded_app"));
    }
}
