use super::migrations;
use super::repository::{OfficeRepositoryError, OfficeTaskRepository};
use rusqlite::Connection;
use std::fs;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn open_office_task_repository(
    database_path: &Path,
) -> Result<OfficeTaskRepository, OfficeRepositoryError> {
    backup_before_first_migration(database_path)?;
    let mut connection =
        Connection::open(database_path).map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
    migrations::migrate(&mut connection, unix_timestamp_ms())
        .map_err(|_| OfficeRepositoryError::MigrationFailed)?;
    Ok(OfficeTaskRepository::new(connection))
}

fn backup_before_first_migration(database_path: &Path) -> Result<(), OfficeRepositoryError> {
    if !database_path.is_file() {
        return Ok(());
    }
    let backup_path = database_path.with_extension("pre-migration-v1.bak");
    if backup_path.exists() {
        return Ok(());
    }
    fs::copy(database_path, backup_path)
        .map(|_| ())
        .map_err(|_| OfficeRepositoryError::BackupFailed)
}

pub fn open_in_memory_office_task_repository() -> Result<OfficeTaskRepository, OfficeRepositoryError>
{
    let mut connection =
        Connection::open_in_memory().map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
    migrations::migrate(&mut connection, unix_timestamp_ms())
        .map_err(|_| OfficeRepositoryError::MigrationFailed)?;
    Ok(OfficeTaskRepository::new(connection))
}

pub fn unix_timestamp_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}
