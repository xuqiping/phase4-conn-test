use super::output::PublishedOutput;
use sha2::{Digest, Sha256};
use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;
use uuid::Uuid;

const TRANSACTION_ROOT_NAME: &str = ".file-keeper-office";
const BACKUP_ROOT_NAME: &str = ".file-keeper-backups";
pub const FAILED_TEMP_RETENTION_MS: i64 = 7 * 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficeRecoveryError {
    OutputDirectoryUnavailable,
    RecoveryScanFailed,
    RecoveryCleanupFailed,
    InvalidRetention,
    ReplaceConfirmationRequired,
    SourceUnavailable,
    PublishedOutputUnavailable,
    SourceChanged,
    BackupUnavailable,
    ReplacementCopyFailed,
    ReplacementValidationFailed,
    ReplacementSwapFailed,
    ReplacementRollbackFailed,
}

impl OfficeRecoveryError {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::OutputDirectoryUnavailable => "OFFICE_OUTPUT_DIRECTORY_UNAVAILABLE",
            Self::RecoveryScanFailed => "OFFICE_RECOVERY_SCAN_FAILED",
            Self::RecoveryCleanupFailed => "OFFICE_RECOVERY_CLEANUP_FAILED",
            Self::InvalidRetention => "OFFICE_RECOVERY_RETENTION_INVALID",
            Self::ReplaceConfirmationRequired => "OFFICE_REPLACE_CONFIRMATION_REQUIRED",
            Self::SourceUnavailable => "OFFICE_REPLACE_SOURCE_UNAVAILABLE",
            Self::PublishedOutputUnavailable => "OFFICE_REPLACE_OUTPUT_UNAVAILABLE",
            Self::SourceChanged => "OFFICE_REPLACE_SOURCE_CHANGED",
            Self::BackupUnavailable => "OFFICE_REPLACE_BACKUP_UNAVAILABLE",
            Self::ReplacementCopyFailed => "OFFICE_REPLACEMENT_COPY_FAILED",
            Self::ReplacementValidationFailed => "OFFICE_REPLACEMENT_VALIDATION_FAILED",
            Self::ReplacementSwapFailed => "OFFICE_REPLACEMENT_SWAP_FAILED",
            Self::ReplacementRollbackFailed => "OFFICE_REPLACEMENT_ROLLBACK_FAILED",
        }
    }
}

impl Display for OfficeRecoveryError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl Error for OfficeRecoveryError {}

#[derive(Clone, PartialEq, Eq)]
pub struct SourceFingerprint {
    size_bytes: u64,
    modified_at_ms: i64,
    sha256: String,
}

impl SourceFingerprint {
    pub fn capture(path: &Path) -> Result<Self, OfficeRecoveryError> {
        let mut file = File::open(path).map_err(|_| OfficeRecoveryError::SourceUnavailable)?;
        let before = file
            .metadata()
            .map_err(|_| OfficeRecoveryError::SourceUnavailable)?;
        if !before.is_file() {
            return Err(OfficeRecoveryError::SourceUnavailable);
        }
        let modified_at_ms = before
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .and_then(|value| i64::try_from(value.as_millis()).ok())
            .ok_or(OfficeRecoveryError::SourceUnavailable)?;
        let sha256 =
            sha256_reader(&mut file).map_err(|_| OfficeRecoveryError::SourceUnavailable)?;
        let after = file
            .metadata()
            .map_err(|_| OfficeRecoveryError::SourceUnavailable)?;
        if before.len() != after.len() || before.modified().ok() != after.modified().ok() {
            return Err(OfficeRecoveryError::SourceChanged);
        }
        Ok(Self {
            size_bytes: before.len(),
            modified_at_ms,
            sha256,
        })
    }
}

impl Debug for SourceFingerprint {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SourceFingerprint")
            .field("size_bytes", &self.size_bytes)
            .field("modified_at_ms", &self.modified_at_ms)
            .field("sha256", &self.sha256)
            .finish()
    }
}

pub struct RecoveryCandidate {
    path: PathBuf,
    pub modified_at_ms: i64,
}

impl RecoveryCandidate {
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Debug for RecoveryCandidate {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RecoveryCandidate")
            .field("path", &"<redacted>")
            .field("modified_at_ms", &self.modified_at_ms)
            .finish()
    }
}

pub fn find_recovery_candidates(
    output_directory: &Path,
) -> Result<Vec<RecoveryCandidate>, OfficeRecoveryError> {
    let output_directory = fs::canonicalize(output_directory)
        .map_err(|_| OfficeRecoveryError::OutputDirectoryUnavailable)?;
    let root = output_directory.join(TRANSACTION_ROOT_NAME);
    let metadata = match fs::symlink_metadata(&root) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(_) => return Err(OfficeRecoveryError::RecoveryScanFailed),
    };
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(OfficeRecoveryError::RecoveryScanFailed);
    }
    let canonical_root =
        fs::canonicalize(&root).map_err(|_| OfficeRecoveryError::RecoveryScanFailed)?;
    if canonical_root.parent() != Some(output_directory.as_path()) {
        return Err(OfficeRecoveryError::RecoveryScanFailed);
    }

    let entries =
        fs::read_dir(&canonical_root).map_err(|_| OfficeRecoveryError::RecoveryScanFailed)?;
    let mut candidates = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|_| OfficeRecoveryError::RecoveryScanFailed)?;
        let metadata = fs::symlink_metadata(entry.path())
            .map_err(|_| OfficeRecoveryError::RecoveryScanFailed)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            continue;
        }
        let path =
            fs::canonicalize(entry.path()).map_err(|_| OfficeRecoveryError::RecoveryScanFailed)?;
        if path.parent() != Some(canonical_root.as_path()) {
            return Err(OfficeRecoveryError::RecoveryScanFailed);
        }
        let modified_at_ms = metadata
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .and_then(|value| i64::try_from(value.as_millis()).ok())
            .ok_or(OfficeRecoveryError::RecoveryScanFailed)?;
        candidates.push(RecoveryCandidate {
            path,
            modified_at_ms,
        });
    }
    Ok(candidates)
}

pub fn cleanup_stale_transactions(
    output_directory: &Path,
    now_ms: i64,
    retention_ms: i64,
) -> Result<u64, OfficeRecoveryError> {
    if now_ms < 0 || retention_ms < 0 {
        return Err(OfficeRecoveryError::InvalidRetention);
    }
    let cutoff = now_ms.saturating_sub(retention_ms);
    let candidates = find_recovery_candidates(output_directory)?;
    let mut removed = 0_u64;
    for candidate in candidates {
        if candidate.modified_at_ms <= cutoff {
            fs::remove_dir_all(candidate.path)
                .map_err(|_| OfficeRecoveryError::RecoveryCleanupFailed)?;
            removed += 1;
        }
    }
    Ok(removed)
}

pub struct ReplaceSourceResult {
    backup_path: PathBuf,
    pub replacement_sha256: String,
}

impl ReplaceSourceResult {
    pub fn backup_path(&self) -> &Path {
        &self.backup_path
    }
}

impl Debug for ReplaceSourceResult {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ReplaceSourceResult")
            .field("backup_path", &"<redacted>")
            .field("replacement_sha256", &self.replacement_sha256)
            .finish()
    }
}

pub fn replace_source_with_published_output(
    confirmed: bool,
    source: &Path,
    expected_source: &SourceFingerprint,
    published: &PublishedOutput,
) -> Result<ReplaceSourceResult, OfficeRecoveryError> {
    if !confirmed {
        return Err(OfficeRecoveryError::ReplaceConfirmationRequired);
    }
    let source = fs::canonicalize(source).map_err(|_| OfficeRecoveryError::SourceUnavailable)?;
    let published_path = fs::canonicalize(published.path())
        .map_err(|_| OfficeRecoveryError::PublishedOutputUnavailable)?;
    if source == published_path || !source.is_file() || !published_path.is_file() {
        return Err(OfficeRecoveryError::PublishedOutputUnavailable);
    }
    if sha256_file(&published_path).map_err(|_| OfficeRecoveryError::PublishedOutputUnavailable)?
        != published.checksum()
    {
        return Err(OfficeRecoveryError::ReplacementValidationFailed);
    }
    if SourceFingerprint::capture(&source)? != *expected_source {
        return Err(OfficeRecoveryError::SourceChanged);
    }

    let source_parent = source
        .parent()
        .ok_or(OfficeRecoveryError::SourceUnavailable)?;
    let source_name = source
        .file_name()
        .ok_or(OfficeRecoveryError::SourceUnavailable)?;
    let operation_id = Uuid::new_v4();
    let replacement_path =
        source_parent.join(format!(".file-keeper-replacement-{operation_id}.tmp"));
    copy_and_sync(&published_path, &replacement_path)?;
    if sha256_file(&replacement_path)
        .map_err(|_| OfficeRecoveryError::ReplacementValidationFailed)?
        != published.checksum()
    {
        let _ = fs::remove_file(&replacement_path);
        return Err(OfficeRecoveryError::ReplacementValidationFailed);
    }
    if SourceFingerprint::capture(&source)? != *expected_source {
        let _ = fs::remove_file(&replacement_path);
        return Err(OfficeRecoveryError::SourceChanged);
    }

    let backup_root = source_parent.join(BACKUP_ROOT_NAME);
    ensure_local_directory(source_parent, &backup_root)?;
    let backup_directory = backup_root.join(operation_id.to_string());
    fs::create_dir(&backup_directory).map_err(|_| OfficeRecoveryError::BackupUnavailable)?;
    let backup_path = backup_directory.join(source_name);
    if fs::rename(&source, &backup_path).is_err() {
        let _ = fs::remove_file(&replacement_path);
        let _ = fs::remove_dir(&backup_directory);
        return Err(OfficeRecoveryError::BackupUnavailable);
    }

    if fs::rename(&replacement_path, &source).is_err() {
        let _ = fs::remove_file(&replacement_path);
        if fs::rename(&backup_path, &source).is_err() {
            return Err(OfficeRecoveryError::ReplacementRollbackFailed);
        }
        let _ = fs::remove_dir(&backup_directory);
        return Err(OfficeRecoveryError::ReplacementSwapFailed);
    }

    if sha256_file(&source).ok().as_deref() != Some(published.checksum()) {
        let _ = fs::remove_file(&source);
        if fs::rename(&backup_path, &source).is_err() {
            return Err(OfficeRecoveryError::ReplacementRollbackFailed);
        }
        let _ = fs::remove_dir(&backup_directory);
        return Err(OfficeRecoveryError::ReplacementValidationFailed);
    }

    Ok(ReplaceSourceResult {
        backup_path,
        replacement_sha256: published.checksum().to_string(),
    })
}

fn ensure_local_directory(parent: &Path, directory: &Path) -> Result<(), OfficeRecoveryError> {
    match fs::symlink_metadata(directory) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(OfficeRecoveryError::BackupUnavailable);
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(directory).map_err(|_| OfficeRecoveryError::BackupUnavailable)?;
        }
        Err(_) => return Err(OfficeRecoveryError::BackupUnavailable),
    }
    let canonical =
        fs::canonicalize(directory).map_err(|_| OfficeRecoveryError::BackupUnavailable)?;
    if canonical.parent() != Some(parent) {
        return Err(OfficeRecoveryError::BackupUnavailable);
    }
    Ok(())
}

fn copy_and_sync(source: &Path, target: &Path) -> Result<(), OfficeRecoveryError> {
    let result = (|| {
        let mut input =
            File::open(source).map_err(|_| OfficeRecoveryError::ReplacementCopyFailed)?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(target)
            .map_err(|_| OfficeRecoveryError::ReplacementCopyFailed)?;
        let mut buffer = [0_u8; 64 * 1024];
        loop {
            let count = input
                .read(&mut buffer)
                .map_err(|_| OfficeRecoveryError::ReplacementCopyFailed)?;
            if count == 0 {
                break;
            }
            output
                .write_all(&buffer[..count])
                .map_err(|_| OfficeRecoveryError::ReplacementCopyFailed)?;
        }
        output
            .sync_all()
            .map_err(|_| OfficeRecoveryError::ReplacementCopyFailed)
    })();
    if result.is_err() {
        let _ = fs::remove_file(target);
    }
    result
}

fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    sha256_reader(&mut file)
}

fn sha256_reader(reader: &mut impl Read) -> std::io::Result<String> {
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
