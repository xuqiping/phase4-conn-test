use super::path_policy::{
    normalize_input_path, normalize_output_directory, NormalizedOfficePath, OfficePathError,
};
use super::risk::{assess_file_risks, OfficeFileFormat, OfficeRiskAssessment, OfficeRiskCode};
use sha2::{Digest, Sha256};
use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

pub const FREE_FILE_LIMIT: u64 = 100;
pub const FREE_TOTAL_BYTES_LIMIT: u64 = 1024 * 1024 * 1024;
pub const FREE_SINGLE_FILE_BYTES_LIMIT: u64 = 100 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficeScannerError {
    Path(OfficePathError),
    UnsupportedFormat,
    SourceReadFailed,
    SizeOverflow,
    Risk(OfficeRiskCode),
}

impl OfficeScannerError {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Path(error) => error.as_str(),
            Self::UnsupportedFormat => "OFFICE_UNSUPPORTED_FORMAT",
            Self::SourceReadFailed => "OFFICE_SOURCE_READ_FAILED",
            Self::SizeOverflow => "OFFICE_INPUT_SIZE_OVERFLOW",
            Self::Risk(risk) => match risk {
                OfficeRiskCode::FileLocked => "OFFICE_FILE_LOCKED",
                _ => "OFFICE_RISK_SCAN_FAILED",
            },
        }
    }
}

impl Display for OfficeScannerError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl Error for OfficeScannerError {}

impl From<OfficePathError> for OfficeScannerError {
    fn from(error: OfficePathError) -> Self {
        Self::Path(error)
    }
}

#[derive(Clone)]
pub struct ScannedOfficeInput {
    path: NormalizedOfficePath,
    pub format: OfficeFileFormat,
    pub size_bytes: u64,
    pub modified_at: i64,
    pub sha256: String,
    pub assessment: OfficeRiskAssessment,
}

impl ScannedOfficeInput {
    pub fn path(&self) -> &Path {
        self.path.as_path()
    }
}

impl Debug for ScannedOfficeInput {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ScannedOfficeInput")
            .field("path", &"<redacted>")
            .field("format", &self.format)
            .field("size_bytes", &self.size_bytes)
            .field("modified_at", &self.modified_at)
            .field("sha256", &self.sha256)
            .field("assessment", &self.assessment)
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FreeQuotaReason {
    FileCountExceeded,
    TotalBytesExceeded,
    SingleFileBytesExceeded,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FreeQuotaAssessment {
    pub within_free_quota: bool,
    pub file_count: u64,
    pub total_bytes: u64,
    pub max_file_bytes: u64,
    pub reasons: Vec<FreeQuotaReason>,
}

#[derive(Debug, Clone)]
pub struct OfficePreflightScan {
    pub inputs: Vec<ScannedOfficeInput>,
    pub output_directory: NormalizedOfficePath,
    pub quota: FreeQuotaAssessment,
    pub blocking_risks: Vec<OfficeRiskCode>,
}

pub fn scan_office_inputs(
    input_paths: &[PathBuf],
    allowed_roots: &[PathBuf],
    output_directory: &Path,
) -> Result<OfficePreflightScan, OfficeScannerError> {
    let normalized_inputs = input_paths
        .iter()
        .map(|path| normalize_input_path(path, allowed_roots))
        .collect::<Result<Vec<_>, _>>()?;
    let normalized_output = normalize_output_directory(output_directory, &normalized_inputs)?;

    let mut inputs = Vec::with_capacity(normalized_inputs.len());
    let mut total_bytes = 0_u64;
    let mut max_file_bytes = 0_u64;
    let mut blocking_risks = Vec::new();
    for path in normalized_inputs {
        let format = OfficeFileFormat::from_path(path.as_path())
            .ok_or(OfficeScannerError::UnsupportedFormat)?;
        let metadata =
            fs::metadata(path.as_path()).map_err(|_| OfficeScannerError::SourceReadFailed)?;
        let size_bytes = metadata.len();
        total_bytes = total_bytes
            .checked_add(size_bytes)
            .ok_or(OfficeScannerError::SizeOverflow)?;
        max_file_bytes = max_file_bytes.max(size_bytes);
        let modified_at = metadata
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis().min(i64::MAX as u128) as i64)
            .unwrap_or(0);
        let sha256 = sha256_file(path.as_path())?;
        let assessment =
            assess_file_risks(path.as_path(), format).map_err(OfficeScannerError::Risk)?;
        if assessment.blocked {
            for risk in assessment
                .risks
                .iter()
                .filter(|risk| is_blocking_risk(**risk))
            {
                if !blocking_risks.contains(risk) {
                    blocking_risks.push(*risk);
                }
            }
        }
        inputs.push(ScannedOfficeInput {
            path,
            format,
            size_bytes,
            modified_at,
            sha256,
            assessment,
        });
    }

    let file_count = inputs.len() as u64;
    let mut reasons = Vec::new();
    if file_count > FREE_FILE_LIMIT {
        reasons.push(FreeQuotaReason::FileCountExceeded);
    }
    if total_bytes > FREE_TOTAL_BYTES_LIMIT {
        reasons.push(FreeQuotaReason::TotalBytesExceeded);
    }
    if max_file_bytes > FREE_SINGLE_FILE_BYTES_LIMIT {
        reasons.push(FreeQuotaReason::SingleFileBytesExceeded);
    }
    Ok(OfficePreflightScan {
        inputs,
        output_directory: normalized_output,
        quota: FreeQuotaAssessment {
            within_free_quota: reasons.is_empty(),
            file_count,
            total_bytes,
            max_file_bytes,
            reasons,
        },
        blocking_risks,
    })
}

fn is_blocking_risk(risk: OfficeRiskCode) -> bool {
    matches!(
        risk,
        OfficeRiskCode::FileLocked
            | OfficeRiskCode::InvalidPackage
            | OfficeRiskCode::RelationshipTooLarge
    )
}

fn sha256_file(path: &Path) -> Result<String, OfficeScannerError> {
    let mut file = File::open(path).map_err(|_| OfficeScannerError::SourceReadFailed)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| OfficeScannerError::SourceReadFailed)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
