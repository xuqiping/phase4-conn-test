use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficePathError {
    EmptyAllowList,
    DevicePathForbidden,
    PathNotFound,
    PathOutsideAllowList,
    InputMustBeFile,
    OutputMustBeDirectory,
    OutputOverlapsInput,
}

impl OfficePathError {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::EmptyAllowList => "OFFICE_PATH_ALLOW_LIST_EMPTY",
            Self::DevicePathForbidden => "OFFICE_DEVICE_PATH_FORBIDDEN",
            Self::PathNotFound => "OFFICE_PATH_NOT_FOUND",
            Self::PathOutsideAllowList => "OFFICE_PATH_OUTSIDE_ALLOW_LIST",
            Self::InputMustBeFile => "OFFICE_INPUT_MUST_BE_FILE",
            Self::OutputMustBeDirectory => "OFFICE_OUTPUT_MUST_BE_DIRECTORY",
            Self::OutputOverlapsInput => "OFFICE_OUTPUT_OVERLAPS_INPUT",
        }
    }
}

impl Display for OfficePathError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl Error for OfficePathError {}

#[derive(Clone, PartialEq, Eq)]
pub struct NormalizedOfficePath(PathBuf);

impl NormalizedOfficePath {
    pub fn as_path(&self) -> &Path {
        &self.0
    }
}

impl Debug for NormalizedOfficePath {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("NormalizedOfficePath(<redacted>)")
    }
}

pub fn normalize_input_path(
    input_path: &Path,
    allowed_roots: &[PathBuf],
) -> Result<NormalizedOfficePath, OfficePathError> {
    if allowed_roots.is_empty() {
        return Err(OfficePathError::EmptyAllowList);
    }
    reject_device_path(input_path)?;
    let canonical_input =
        fs::canonicalize(input_path).map_err(|_| OfficePathError::PathNotFound)?;
    if !canonical_input.is_file() {
        return Err(OfficePathError::InputMustBeFile);
    }

    let mut allowed = false;
    for root in allowed_roots {
        reject_device_path(root)?;
        let canonical_root = fs::canonicalize(root).map_err(|_| OfficePathError::PathNotFound)?;
        if (canonical_root.is_file() && canonical_input == canonical_root)
            || (canonical_root.is_dir() && canonical_input.starts_with(&canonical_root))
        {
            allowed = true;
            break;
        }
    }
    if !allowed {
        return Err(OfficePathError::PathOutsideAllowList);
    }
    Ok(NormalizedOfficePath(canonical_input))
}

pub fn normalize_output_directory(
    output_directory: &Path,
    inputs: &[NormalizedOfficePath],
) -> Result<NormalizedOfficePath, OfficePathError> {
    reject_device_path(output_directory)?;
    let canonical_output =
        fs::canonicalize(output_directory).map_err(|_| OfficePathError::PathNotFound)?;
    if !canonical_output.is_dir() {
        return Err(OfficePathError::OutputMustBeDirectory);
    }

    for input in inputs {
        let source_directory = input
            .as_path()
            .parent()
            .ok_or(OfficePathError::OutputOverlapsInput)?;
        if canonical_output.starts_with(source_directory) {
            return Err(OfficePathError::OutputOverlapsInput);
        }
    }
    Ok(NormalizedOfficePath(canonical_output))
}

fn reject_device_path(path: &Path) -> Result<(), OfficePathError> {
    let text = path
        .to_string_lossy()
        .replace('/', "\\")
        .to_ascii_lowercase();
    if text.starts_with("\\\\.\\")
        || text.starts_with("\\\\?\\globalroot\\")
        || text.starts_with("\\device\\")
    {
        return Err(OfficePathError::DevicePathForbidden);
    }
    Ok(())
}
