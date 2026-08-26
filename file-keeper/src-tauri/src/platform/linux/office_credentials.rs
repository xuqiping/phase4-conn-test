use crate::office::credentials::{OfficeCredentialError, OfficeCredentialErrorCode};

const SERVICE_NAME: &str = "com.file-keeper.office-password.v1";

pub fn set_password(binding_id: &str, password: &str) -> Result<(), OfficeCredentialError> {
    entry(binding_id, OfficeCredentialErrorCode::VaultWriteFailed)?
        .set_password(password)
        .map_err(|error| map_error(error, OfficeCredentialErrorCode::VaultWriteFailed))
}

pub fn get_password(binding_id: &str) -> Result<String, OfficeCredentialError> {
    entry(binding_id, OfficeCredentialErrorCode::VaultReadFailed)?
        .get_password()
        .map_err(|error| map_error(error, OfficeCredentialErrorCode::VaultReadFailed))
}

pub fn delete_password(binding_id: &str) -> Result<(), OfficeCredentialError> {
    entry(binding_id, OfficeCredentialErrorCode::VaultDeleteFailed)?
        .delete_password()
        .map_err(|error| map_error(error, OfficeCredentialErrorCode::VaultDeleteFailed))
}

fn entry(
    binding_id: &str,
    fallback: OfficeCredentialErrorCode,
) -> Result<keyring::Entry, OfficeCredentialError> {
    keyring::Entry::new(SERVICE_NAME, &format!("office:{binding_id}"))
        .map_err(|error| map_error(error, fallback))
}

fn map_error(error: keyring::Error, fallback: OfficeCredentialErrorCode) -> OfficeCredentialError {
    let code = match error {
        keyring::Error::NoEntry => OfficeCredentialErrorCode::CredentialNotFound,
        keyring::Error::BadEncoding(_) => OfficeCredentialErrorCode::VaultEncodingInvalid,
        keyring::Error::PlatformFailure(_) => OfficeCredentialErrorCode::VaultUnavailable,
        _ => fallback,
    };
    OfficeCredentialError::new(code)
}
