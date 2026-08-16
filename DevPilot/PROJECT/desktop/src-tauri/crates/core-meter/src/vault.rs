//! 登录凭证保险库：refresh token 存 OS 凭据管理器（Windows 凭据管理器 / macOS 钥匙串），
//! 绝不落明文文件（plan 安全清单）。CI/Linux 无凭据后端时返回明确错误而非 panic。

/// 保险库错误（大白话，不给前端透出系统细节）
#[derive(Debug, thiserror::Error)]
pub enum VaultError {
    #[error("系统凭据存储不可用：{0}")]
    Backend(String),
}

pub trait TokenVault {
    fn save(&self, refresh_token: &str) -> Result<(), VaultError>;
    fn load(&self) -> Result<Option<String>, VaultError>;
    fn clear(&self) -> Result<(), VaultError>;
}

/// OS 原生凭据库实现（keyring crate：Windows→凭据管理器，macOS→钥匙串）。
pub struct OsKeyringVault {
    service: String,
    account: String,
}

impl OsKeyringVault {
    pub fn new() -> Self {
        Self {
            service: "DevPilot".into(),
            account: "refresh_token".into(),
        }
    }

    fn entry(&self) -> Result<keyring::Entry, VaultError> {
        keyring::Entry::new(&self.service, &self.account)
            .map_err(|e| VaultError::Backend(e.to_string()))
    }
}

impl Default for OsKeyringVault {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenVault for OsKeyringVault {
    fn save(&self, refresh_token: &str) -> Result<(), VaultError> {
        self.entry()?
            .set_password(refresh_token)
            .map_err(|e| VaultError::Backend(e.to_string()))
    }

    fn load(&self) -> Result<Option<String>, VaultError> {
        match self.entry()?.get_password() {
            Ok(s) => Ok(Some(s)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(VaultError::Backend(e.to_string())),
        }
    }

    fn clear(&self) -> Result<(), VaultError> {
        match self.entry()?.delete_credential() {
            Ok(()) => Ok(()),
            Err(keyring::Error::NoEntry) => Ok(()), // 清不存在的=已干净
            Err(e) => Err(VaultError::Backend(e.to_string())),
        }
    }
}
