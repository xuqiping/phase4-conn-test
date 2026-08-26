use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::UNIX_EPOCH;
use zeroize::Zeroizing;

const CREDENTIAL_BINDING_VERSION: &str = "file-keeper-office-credential-v1";
const SHA256_HEX_LENGTH: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficeCredentialErrorCode {
    PathInvalid,
    SourceReadFailed,
    FingerprintInvalid,
    PasswordEmpty,
    CredentialNotFound,
    CredentialStale,
    VaultUnavailable,
    VaultAccessDenied,
    VaultWriteFailed,
    VaultReadFailed,
    VaultDeleteFailed,
    VaultEncodingInvalid,
}

impl OfficeCredentialErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::PathInvalid => "OFFICE_CREDENTIAL_PATH_INVALID",
            Self::SourceReadFailed => "OFFICE_CREDENTIAL_SOURCE_READ_FAILED",
            Self::FingerprintInvalid => "OFFICE_CREDENTIAL_FINGERPRINT_INVALID",
            Self::PasswordEmpty => "OFFICE_CREDENTIAL_PASSWORD_EMPTY",
            Self::CredentialNotFound => "OFFICE_CREDENTIAL_NOT_FOUND",
            Self::CredentialStale => "OFFICE_CREDENTIAL_STALE",
            Self::VaultUnavailable => "OFFICE_CREDENTIAL_VAULT_UNAVAILABLE",
            Self::VaultAccessDenied => "OFFICE_CREDENTIAL_VAULT_ACCESS_DENIED",
            Self::VaultWriteFailed => "OFFICE_CREDENTIAL_VAULT_WRITE_FAILED",
            Self::VaultReadFailed => "OFFICE_CREDENTIAL_VAULT_READ_FAILED",
            Self::VaultDeleteFailed => "OFFICE_CREDENTIAL_VAULT_DELETE_FAILED",
            Self::VaultEncodingInvalid => "OFFICE_CREDENTIAL_VAULT_ENCODING_INVALID",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OfficeCredentialError {
    code: OfficeCredentialErrorCode,
}

impl OfficeCredentialError {
    pub const fn new(code: OfficeCredentialErrorCode) -> Self {
        Self { code }
    }

    pub const fn code(self) -> OfficeCredentialErrorCode {
        self.code
    }
}

impl Display for OfficeCredentialError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.code.as_str())
    }
}

impl Error for OfficeCredentialError {}

/// A password that is redacted from Debug output and zeroed when dropped.
pub struct OfficePassword(Zeroizing<String>);

impl OfficePassword {
    pub fn new(password: String) -> Result<Self, OfficeCredentialError> {
        if password.is_empty() {
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::PasswordEmpty,
            ));
        }
        Ok(Self(Zeroizing::new(password)))
    }

    pub fn expose(&self) -> &str {
        self.0.as_str()
    }
}

impl Debug for OfficePassword {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("OfficePassword(<redacted>)")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct OfficeFileFingerprint {
    size_bytes: u64,
    modified_at_millis: u64,
    sha256: String,
}

impl OfficeFileFingerprint {
    fn validate(&self) -> Result<(), OfficeCredentialError> {
        if self.sha256.len() != SHA256_HEX_LENGTH
            || !self
                .sha256
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
        {
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::FingerprintInvalid,
            ));
        }
        Ok(())
    }
}

/// An opaque handle safe to persist in settings or task metadata. It contains no password or path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeCredentialReference {
    binding_id: String,
}

impl OfficeCredentialReference {
    pub fn binding_id(&self) -> &str {
        &self.binding_id
    }

    pub fn parse(binding_id: String) -> Result<Self, OfficeCredentialError> {
        if binding_id.len() != SHA256_HEX_LENGTH
            || !binding_id.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::FingerprintInvalid,
            ));
        }
        Ok(Self {
            binding_id: binding_id.to_ascii_lowercase(),
        })
    }
}

pub trait OfficeCredentialVault: Send + Sync {
    fn set(&self, binding_id: &str, password: &OfficePassword)
        -> Result<(), OfficeCredentialError>;
    fn get(&self, binding_id: &str) -> Result<OfficePassword, OfficeCredentialError>;
    fn delete(&self, binding_id: &str) -> Result<(), OfficeCredentialError>;
}

#[derive(Debug, Default)]
pub struct SystemOfficeCredentialVault;

impl OfficeCredentialVault for SystemOfficeCredentialVault {
    fn set(
        &self,
        binding_id: &str,
        password: &OfficePassword,
    ) -> Result<(), OfficeCredentialError> {
        platform_set_password(binding_id, password.expose())
    }

    fn get(&self, binding_id: &str) -> Result<OfficePassword, OfficeCredentialError> {
        OfficePassword::new(platform_get_password(binding_id)?)
    }

    fn delete(&self, binding_id: &str) -> Result<(), OfficeCredentialError> {
        platform_delete_password(binding_id)
    }
}

pub struct OfficeCredentialService<V = SystemOfficeCredentialVault> {
    vault: V,
    operation_lock: Mutex<()>,
}

impl Default for OfficeCredentialService<SystemOfficeCredentialVault> {
    fn default() -> Self {
        Self {
            vault: SystemOfficeCredentialVault,
            operation_lock: Mutex::new(()),
        }
    }
}

impl<V: OfficeCredentialVault> OfficeCredentialService<V> {
    pub fn new(vault: V) -> Self {
        Self {
            vault,
            operation_lock: Mutex::new(()),
        }
    }

    pub fn save_for_file(
        &self,
        path: &Path,
        password: OfficePassword,
    ) -> Result<OfficeCredentialReference, OfficeCredentialError> {
        let _guard = self
            .operation_lock
            .lock()
            .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::VaultUnavailable))?;
        let binding_id = current_binding_id(path)?;
        self.vault.set(&binding_id, &password)?;
        if current_binding_id(path)? != binding_id {
            let _ = self.vault.delete(&binding_id);
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::CredentialStale,
            ));
        }
        Ok(OfficeCredentialReference { binding_id })
    }

    /// Re-fingerprints the file before every read. A stale reference can never unlock a changed file.
    pub fn load_for_file(
        &self,
        path: &Path,
        reference: &OfficeCredentialReference,
    ) -> Result<OfficePassword, OfficeCredentialError> {
        let _guard = self
            .operation_lock
            .lock()
            .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::VaultUnavailable))?;
        let current_id = current_binding_id(path)?;
        if current_id != reference.binding_id {
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::CredentialStale,
            ));
        }
        let password = self.vault.get(&reference.binding_id)?;
        if current_binding_id(path)? != reference.binding_id {
            return Err(OfficeCredentialError::new(
                OfficeCredentialErrorCode::CredentialStale,
            ));
        }
        Ok(password)
    }

    pub fn delete(
        &self,
        reference: OfficeCredentialReference,
    ) -> Result<(), OfficeCredentialError> {
        let _guard = self
            .operation_lock
            .lock()
            .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::VaultUnavailable))?;
        self.vault.delete(&reference.binding_id)
    }
}

fn current_binding_id(path: &Path) -> Result<String, OfficeCredentialError> {
    let canonical_path = normalize_credential_path(path)?;
    let fingerprint = fingerprint_file(&canonical_path)?;
    fingerprint.validate()?;

    let mut hasher = Sha256::new();
    hasher.update(CREDENTIAL_BINDING_VERSION.as_bytes());
    hasher.update([0]);
    hasher.update(path_identity_bytes(&canonical_path));
    hasher.update([0]);
    hasher.update(fingerprint.size_bytes.to_le_bytes());
    hasher.update(fingerprint.modified_at_millis.to_le_bytes());
    hasher.update(fingerprint.sha256.as_bytes());
    Ok(format!("{:x}", hasher.finalize()))
}

fn normalize_credential_path(path: &Path) -> Result<PathBuf, OfficeCredentialError> {
    let canonical = fs::canonicalize(path)
        .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::PathInvalid))?;
    if !canonical.is_file() {
        return Err(OfficeCredentialError::new(
            OfficeCredentialErrorCode::PathInvalid,
        ));
    }
    Ok(canonical)
}

fn fingerprint_file(path: &Path) -> Result<OfficeFileFingerprint, OfficeCredentialError> {
    let mut file = File::open(path)
        .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::SourceReadFailed))?;
    let metadata = file
        .metadata()
        .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::SourceReadFailed))?;
    let modified_at_millis = metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis().min(u64::MAX as u128) as u64)
        .unwrap_or(0);
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| OfficeCredentialError::new(OfficeCredentialErrorCode::SourceReadFailed))?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(OfficeFileFingerprint {
        size_bytes: metadata.len(),
        modified_at_millis,
        sha256: format!("{:x}", hasher.finalize()),
    })
}

#[cfg(windows)]
fn path_identity_bytes(path: &Path) -> Vec<u8> {
    path.to_string_lossy().to_lowercase().into_bytes()
}

#[cfg(not(windows))]
fn path_identity_bytes(path: &Path) -> Vec<u8> {
    use std::os::unix::ffi::OsStrExt;

    path.as_os_str().as_bytes().to_vec()
}

#[cfg(target_os = "windows")]
fn platform_set_password(binding_id: &str, password: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::windows::office_credentials::set_password(binding_id, password)
}

#[cfg(target_os = "windows")]
fn platform_get_password(binding_id: &str) -> Result<String, OfficeCredentialError> {
    crate::platform::windows::office_credentials::get_password(binding_id)
}

#[cfg(target_os = "windows")]
fn platform_delete_password(binding_id: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::windows::office_credentials::delete_password(binding_id)
}

#[cfg(target_os = "macos")]
fn platform_set_password(binding_id: &str, password: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::macos::office_credentials::set_password(binding_id, password)
}

#[cfg(target_os = "macos")]
fn platform_get_password(binding_id: &str) -> Result<String, OfficeCredentialError> {
    crate::platform::macos::office_credentials::get_password(binding_id)
}

#[cfg(target_os = "macos")]
fn platform_delete_password(binding_id: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::macos::office_credentials::delete_password(binding_id)
}

#[cfg(target_os = "linux")]
fn platform_set_password(binding_id: &str, password: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::linux::office_credentials::set_password(binding_id, password)
}

#[cfg(target_os = "linux")]
fn platform_get_password(binding_id: &str) -> Result<String, OfficeCredentialError> {
    crate::platform::linux::office_credentials::get_password(binding_id)
}

#[cfg(target_os = "linux")]
fn platform_delete_password(binding_id: &str) -> Result<(), OfficeCredentialError> {
    crate::platform::linux::office_credentials::delete_password(binding_id)
}

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
fn platform_set_password(_: &str, _: &str) -> Result<(), OfficeCredentialError> {
    Err(OfficeCredentialError::new(
        OfficeCredentialErrorCode::VaultUnavailable,
    ))
}

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
fn platform_get_password(_: &str) -> Result<String, OfficeCredentialError> {
    Err(OfficeCredentialError::new(
        OfficeCredentialErrorCode::VaultUnavailable,
    ))
}

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
fn platform_delete_password(_: &str) -> Result<(), OfficeCredentialError> {
    Err(OfficeCredentialError::new(
        OfficeCredentialErrorCode::VaultUnavailable,
    ))
}
