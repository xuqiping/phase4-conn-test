use super::types::{OfficeTaskId, OutputPolicy, OutputSummary, JS_SAFE_INTEGER_MAX};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use std::fs::{self, File, OpenOptions};
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use sysinfo::Disks;
use uuid::Uuid;

const TRANSACTION_ROOT_NAME: &str = ".file-keeper-office";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutputTransactionError {
    InvalidExpectedCount,
    InvalidOutputId,
    OutputAlreadyAccounted,
    OutputCountExceeded,
    OutputDirectoryUnavailable,
    DiskSpaceUnavailable,
    InsufficientDiskSpace,
    TemporaryDirectoryUnavailable,
    TemporaryFileUnavailable,
    ForeignStagedOutput,
    InvalidFinalName,
    UnsupportedOutputFormat,
    EmptyOutput,
    InvalidOutputSignature,
    OutputValidationFailed,
    TargetAlreadyExists,
    AtomicPublishFailed,
    ChecksumMismatch,
    IncompleteTransaction,
}

impl OutputTransactionError {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidExpectedCount => "OFFICE_OUTPUT_EXPECTED_INVALID",
            Self::InvalidOutputId => "OFFICE_OUTPUT_ID_INVALID",
            Self::OutputAlreadyAccounted => "OFFICE_OUTPUT_ALREADY_ACCOUNTED",
            Self::OutputCountExceeded => "OFFICE_OUTPUT_COUNT_EXCEEDED",
            Self::OutputDirectoryUnavailable => "OFFICE_OUTPUT_DIRECTORY_UNAVAILABLE",
            Self::DiskSpaceUnavailable => "OFFICE_DISK_SPACE_UNAVAILABLE",
            Self::InsufficientDiskSpace => "OFFICE_INSUFFICIENT_DISK_SPACE",
            Self::TemporaryDirectoryUnavailable => "OFFICE_TEMP_DIRECTORY_UNAVAILABLE",
            Self::TemporaryFileUnavailable => "OFFICE_TEMP_FILE_UNAVAILABLE",
            Self::ForeignStagedOutput => "OFFICE_FOREIGN_STAGED_OUTPUT",
            Self::InvalidFinalName => "OFFICE_OUTPUT_NAME_INVALID",
            Self::UnsupportedOutputFormat => "OFFICE_OUTPUT_FORMAT_UNSUPPORTED",
            Self::EmptyOutput => "OFFICE_OUTPUT_EMPTY",
            Self::InvalidOutputSignature => "OFFICE_OUTPUT_SIGNATURE_INVALID",
            Self::OutputValidationFailed => "OFFICE_OUTPUT_VALIDATION_FAILED",
            Self::TargetAlreadyExists => "OFFICE_OUTPUT_TARGET_EXISTS",
            Self::AtomicPublishFailed => "OFFICE_ATOMIC_PUBLISH_FAILED",
            Self::ChecksumMismatch => "OFFICE_OUTPUT_CHECKSUM_MISMATCH",
            Self::IncompleteTransaction => "OFFICE_OUTPUT_TRANSACTION_INCOMPLETE",
        }
    }
}

impl Display for OutputTransactionError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl Error for OutputTransactionError {}

pub struct OutputTransaction {
    task_id: OfficeTaskId,
    transaction_id: Uuid,
    output_policy: OutputPolicy,
    output_directory: PathBuf,
    temporary_directory: PathBuf,
    expected: u64,
    published: u64,
    failed: u64,
    active: HashMap<String, PathBuf>,
    accounted: HashSet<String>,
}

impl Debug for OutputTransaction {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OutputTransaction")
            .field("task_id", &self.task_id)
            .field("transaction_id", &self.transaction_id)
            .field("output_policy", &self.output_policy)
            .field("output_directory", &"<redacted>")
            .field("temporary_directory", &"<redacted>")
            .field("expected", &self.expected)
            .field("published", &self.published)
            .field("failed", &self.failed)
            .field("active_count", &self.active.len())
            .finish()
    }
}

impl OutputTransaction {
    pub fn start(
        task_id: OfficeTaskId,
        output_policy: OutputPolicy,
        output_directory: &Path,
        estimated_output_bytes: u64,
        expected: u64,
    ) -> Result<Self, OutputTransactionError> {
        if expected == 0
            || expected > JS_SAFE_INTEGER_MAX
            || (output_policy == OutputPolicy::SingleAtomic && expected != 1)
        {
            return Err(OutputTransactionError::InvalidExpectedCount);
        }
        let output_directory = fs::canonicalize(output_directory)
            .map_err(|_| OutputTransactionError::OutputDirectoryUnavailable)?;
        if !output_directory.is_dir() {
            return Err(OutputTransactionError::OutputDirectoryUnavailable);
        }
        ensure_available_space(&output_directory, estimated_output_bytes)?;

        let transaction_id = Uuid::new_v4();
        let transaction_root = output_directory.join(TRANSACTION_ROOT_NAME);
        ensure_transaction_root(&output_directory, &transaction_root)?;
        let temporary_directory = transaction_root.join(format!("{}-{transaction_id}", task_id));
        fs::create_dir(&temporary_directory)
            .map_err(|_| OutputTransactionError::TemporaryDirectoryUnavailable)?;

        Ok(Self {
            task_id,
            transaction_id,
            output_policy,
            output_directory,
            temporary_directory,
            expected,
            published: 0,
            failed: 0,
            active: HashMap::new(),
            accounted: HashSet::new(),
        })
    }

    pub fn stage(&mut self, output_id: &str) -> Result<StagedOutput, OutputTransactionError> {
        validate_output_id(output_id)?;
        if self.accounted.contains(output_id) || self.active.contains_key(output_id) {
            return Err(OutputTransactionError::OutputAlreadyAccounted);
        }
        if (self.accounted.len() as u64) + (self.active.len() as u64) >= self.expected {
            return Err(OutputTransactionError::OutputCountExceeded);
        }

        let path = self.temporary_directory.join(format!("{output_id}.staged"));
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?;
        self.active.insert(output_id.to_string(), path.clone());
        Ok(StagedOutput {
            transaction_id: self.transaction_id,
            output_id: output_id.to_string(),
            path,
        })
    }

    pub fn publish<F>(
        &mut self,
        staged: StagedOutput,
        final_name: &str,
        validate_invariants: F,
    ) -> Result<PublishedOutput, OutputTransactionError>
    where
        F: FnOnce(&Path) -> Result<(), OutputTransactionError>,
    {
        let owned = staged.transaction_id == self.transaction_id
            && self.active.get(&staged.output_id) == Some(&staged.path);
        let result = self.publish_inner(&staged, final_name, validate_invariants);
        if result.is_err() && owned {
            self.fail_staged(&staged.output_id);
        }
        result
    }

    pub fn record_failure(&mut self, output_id: &str) -> Result<(), OutputTransactionError> {
        validate_output_id(output_id)?;
        if self.accounted.contains(output_id) {
            return Err(OutputTransactionError::OutputAlreadyAccounted);
        }
        if self.accounted.len() as u64 >= self.expected {
            return Err(OutputTransactionError::OutputCountExceeded);
        }
        self.fail_staged(output_id);
        Ok(())
    }

    pub fn finalize(self) -> Result<PublicationReceipt, OutputTransactionError> {
        if !self.active.is_empty()
            || self
                .published
                .checked_add(self.failed)
                .filter(|count| *count == self.expected)
                .is_none()
        {
            return Err(OutputTransactionError::IncompleteTransaction);
        }

        if self.failed == 0 {
            let _ = fs::remove_dir(&self.temporary_directory);
            if let Some(root) = self.temporary_directory.parent() {
                let _ = fs::remove_dir(root);
            }
        }
        Ok(PublicationReceipt {
            task_id: self.task_id,
            summary: OutputSummary::new(self.expected, self.published, self.failed),
        })
    }

    fn publish_inner<F>(
        &mut self,
        staged: &StagedOutput,
        final_name: &str,
        validate_invariants: F,
    ) -> Result<PublishedOutput, OutputTransactionError>
    where
        F: FnOnce(&Path) -> Result<(), OutputTransactionError>,
    {
        if staged.transaction_id != self.transaction_id
            || self.active.get(&staged.output_id) != Some(&staged.path)
        {
            return Err(OutputTransactionError::ForeignStagedOutput);
        }
        validate_final_name(final_name)?;
        validate_base_output(&staged.path, final_name)?;
        validate_invariants(&staged.path)
            .map_err(|_| OutputTransactionError::OutputValidationFailed)?;

        let checksum = sha256_file(&staged.path)?;
        sync_file(&staged.path)?;
        let final_path = self.output_directory.join(final_name);
        if final_path.exists() {
            return Err(OutputTransactionError::TargetAlreadyExists);
        }

        fs::hard_link(&staged.path, &final_path).map_err(|_| {
            if final_path.exists() {
                OutputTransactionError::TargetAlreadyExists
            } else {
                OutputTransactionError::AtomicPublishFailed
            }
        })?;
        let published_checksum = match sha256_file(&final_path) {
            Ok(checksum) => checksum,
            Err(error) => {
                let _ = fs::remove_file(&final_path);
                return Err(error);
            }
        };
        if published_checksum != checksum {
            let _ = fs::remove_file(&final_path);
            return Err(OutputTransactionError::ChecksumMismatch);
        }
        if fs::remove_file(&staged.path).is_err() {
            let _ = fs::remove_file(&final_path);
            return Err(OutputTransactionError::AtomicPublishFailed);
        }

        self.active.remove(&staged.output_id);
        self.accounted.insert(staged.output_id.clone());
        self.published += 1;
        Ok(PublishedOutput {
            output_id: staged.output_id.clone(),
            path: final_path,
            checksum,
        })
    }

    fn fail_staged(&mut self, output_id: &str) {
        if self.accounted.contains(output_id) {
            return;
        }
        if let Some(path) = self.active.remove(output_id) {
            let _ = fs::remove_file(path);
        }
        self.accounted.insert(output_id.to_string());
        self.failed += 1;
    }
}

pub struct StagedOutput {
    transaction_id: Uuid,
    output_id: String,
    path: PathBuf,
}

impl StagedOutput {
    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn output_id(&self) -> &str {
        &self.output_id
    }
}

impl Debug for StagedOutput {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("StagedOutput")
            .field("output_id", &self.output_id)
            .field("path", &"<redacted>")
            .finish()
    }
}

pub struct PublishedOutput {
    output_id: String,
    path: PathBuf,
    checksum: String,
}

impl PublishedOutput {
    pub fn output_id(&self) -> &str {
        &self.output_id
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn checksum(&self) -> &str {
        &self.checksum
    }
}

impl Debug for PublishedOutput {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PublishedOutput")
            .field("output_id", &self.output_id)
            .field("path", &"<redacted>")
            .field("checksum", &self.checksum)
            .finish()
    }
}

pub struct PublicationReceipt {
    task_id: OfficeTaskId,
    summary: OutputSummary,
}

impl PublicationReceipt {
    pub(crate) fn into_parts(self) -> (OfficeTaskId, OutputSummary) {
        (self.task_id, self.summary)
    }
}

impl Debug for PublicationReceipt {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PublicationReceipt")
            .field("task_id", &self.task_id)
            .field("summary", &self.summary)
            .finish()
    }
}

pub fn required_disk_space(estimated_output_bytes: u64) -> Result<u64, OutputTransactionError> {
    let temporary_overhead = estimated_output_bytes / 2 + estimated_output_bytes % 2;
    estimated_output_bytes
        .checked_add(temporary_overhead)
        .ok_or(OutputTransactionError::DiskSpaceUnavailable)
}

fn ensure_transaction_root(
    output_directory: &Path,
    transaction_root: &Path,
) -> Result<(), OutputTransactionError> {
    match fs::symlink_metadata(transaction_root) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(OutputTransactionError::TemporaryDirectoryUnavailable);
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(transaction_root)
                .map_err(|_| OutputTransactionError::TemporaryDirectoryUnavailable)?;
        }
        Err(_) => return Err(OutputTransactionError::TemporaryDirectoryUnavailable),
    }
    let canonical_root = fs::canonicalize(transaction_root)
        .map_err(|_| OutputTransactionError::TemporaryDirectoryUnavailable)?;
    if canonical_root.parent() != Some(output_directory) {
        return Err(OutputTransactionError::TemporaryDirectoryUnavailable);
    }
    Ok(())
}

fn ensure_available_space(
    output_directory: &Path,
    estimated_output_bytes: u64,
) -> Result<(), OutputTransactionError> {
    let required = required_disk_space(estimated_output_bytes)?;
    let disks = Disks::new_with_refreshed_list();
    let comparable_output_directory = comparable_disk_path(output_directory);
    let available = disks
        .iter()
        .filter(|disk| {
            comparable_output_directory.starts_with(comparable_disk_path(disk.mount_point()))
        })
        .max_by_key(|disk| disk.mount_point().components().count())
        .map(|disk| disk.available_space())
        .ok_or(OutputTransactionError::DiskSpaceUnavailable)?;
    if available < required {
        return Err(OutputTransactionError::InsufficientDiskSpace);
    }
    Ok(())
}

#[cfg(windows)]
fn comparable_disk_path(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(unc_path) = text.strip_prefix(r"\\?\UNC\") {
        return PathBuf::from(format!(r"\\{unc_path}"));
    }
    if let Some(local_path) = text.strip_prefix(r"\\?\") {
        return PathBuf::from(local_path);
    }
    path.to_path_buf()
}

#[cfg(not(windows))]
fn comparable_disk_path(path: &Path) -> PathBuf {
    path.to_path_buf()
}

fn validate_output_id(output_id: &str) -> Result<(), OutputTransactionError> {
    if output_id.is_empty()
        || output_id.len() > 128
        || !output_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err(OutputTransactionError::InvalidOutputId);
    }
    Ok(())
}

fn validate_final_name(final_name: &str) -> Result<(), OutputTransactionError> {
    let path = Path::new(final_name);
    if final_name.is_empty()
        || final_name.len() > 240
        || path.components().count() != 1
        || !matches!(path.components().next(), Some(Component::Normal(_)))
        || final_name
            .chars()
            .any(|character| matches!(character, '/' | '\\' | ':' | '\0'))
        || is_windows_reserved_name(final_name)
    {
        return Err(OutputTransactionError::InvalidFinalName);
    }
    Ok(())
}

fn is_windows_reserved_name(final_name: &str) -> bool {
    let stem = final_name
        .split('.')
        .next()
        .unwrap_or_default()
        .trim_end_matches([' ', '.'])
        .to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || stem
            .strip_prefix("COM")
            .or_else(|| stem.strip_prefix("LPT"))
            .is_some_and(|number| {
                matches!(number, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9")
            })
}

fn validate_base_output(path: &Path, final_name: &str) -> Result<(), OutputTransactionError> {
    let mut file =
        File::open(path).map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?;
    if file
        .metadata()
        .map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?
        .len()
        == 0
    {
        return Err(OutputTransactionError::EmptyOutput);
    }
    let mut signature = [0_u8; 8];
    let count = file
        .read(&mut signature)
        .map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?;
    let extension = Path::new(final_name)
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase)
        .ok_or(OutputTransactionError::UnsupportedOutputFormat)?;
    let valid = match extension.as_str() {
        "xlsx" | "xlsm" | "docx" | "docm" | "pptx" | "pptm" => {
            count >= 4
                && matches!(
                    &signature[..4],
                    [b'P', b'K', 3, 4] | [b'P', b'K', 5, 6] | [b'P', b'K', 7, 8]
                )
        }
        "xls" | "doc" | "ppt" => {
            count >= 8 && signature == [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]
        }
        "pdf" => count >= 5 && &signature[..5] == b"%PDF-",
        "csv" => true,
        _ => return Err(OutputTransactionError::UnsupportedOutputFormat),
    };
    if !valid {
        return Err(OutputTransactionError::InvalidOutputSignature);
    }
    Ok(())
}

fn sync_file(path: &Path) -> Result<(), OutputTransactionError> {
    OpenOptions::new()
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .map_err(|_| OutputTransactionError::TemporaryFileUnavailable)
}

fn sha256_file(path: &Path) -> Result<String, OutputTransactionError> {
    let mut file =
        File::open(path).map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| OutputTransactionError::TemporaryFileUnavailable)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
