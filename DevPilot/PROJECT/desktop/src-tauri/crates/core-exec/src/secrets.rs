//! Secrets 注入与脱敏：把项目 secrets 变成进程环境变量，并把输出中的敏感值替换为 ***。
//! 对应 FR-012/AC-014。

use std::collections::HashMap;

/// 一个脱敏后的 secret：名称 + 原始值（仅用于进程注入）+ 若干需要被替换的变体。
#[derive(Debug, Clone)]
pub struct MaskedSecret {
    pub name: String,
    pub value: String,
    /// 所有需要被替换的字符串：原始值 + 小写/大写等常见变体。
    pub needles: Vec<String>,
}

impl MaskedSecret {
    pub fn new(name: impl Into<String>, value: impl Into<String>) -> Self {
        let value = value.into();
        let mut needles = vec![value.clone()];
        let lower = value.to_lowercase();
        if lower != value {
            needles.push(lower);
        }
        let upper = value.to_uppercase();
        if upper != value {
            needles.push(upper);
        }
        Self {
            name: name.into(),
            value,
            needles,
        }
    }
}

/// 把 secrets 注入 env；变量名为 DEVPILOT_SECRET_<NAME>（大写）。
pub fn inject_env(env: &mut HashMap<String, String>, secrets: &[MaskedSecret]) {
    for s in secrets {
        let key = format!("DEVPILOT_SECRET_{}", s.name.to_uppercase());
        env.insert(key, s.value.clone());
    }
}

/// 对一段文本脱敏：将所有 needle 替换为 ***。
pub fn redact(text: &str, secrets: &[MaskedSecret]) -> String {
    let mut out = text.to_string();
    for s in secrets {
        for n in &s.needles {
            if n.is_empty() {
                continue;
            }
            out = out.replace(n, "***");
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn injects_uppercase_env() {
        let mut env = HashMap::new();
        let secrets = vec![MaskedSecret::new("api_key", "secret123")];
        inject_env(&mut env, &secrets);
        assert_eq!(
            env.get("DEVPILOT_SECRET_API_KEY"),
            Some(&"secret123".into())
        );
    }

    #[test]
    fn redacts_value_and_case_variants() {
        let secrets = vec![MaskedSecret::new("token", "AbC123")];
        let text = "header: AbC123 and abc123 and ABC123";
        let out = redact(text, &secrets);
        assert!(!out.contains("AbC123"));
        assert!(!out.contains("abc123"));
        assert!(!out.contains("ABC123"));
        assert!(out.contains("***"));
    }

    #[test]
    fn empty_secret_skipped() {
        let secrets = vec![MaskedSecret::new("x", "")];
        assert_eq!(redact("abc", &secrets), "abc");
    }
}
