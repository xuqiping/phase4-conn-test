use crate::commands::auth::SignedEntitlementState;
use crate::platform::ProcessInfo;
use crate::utils::process_matcher;
use tauri::State;

const MODULE_CODE: &str = "processes";

#[tauri::command]
pub async fn find_file_processes(
    entitlement_state: State<'_, SignedEntitlementState>,
    file_path: String,
) -> Result<Vec<ProcessInfo>, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;
    process_matcher::find_processes_for_file(&file_path)
}

#[tauri::command]
pub async fn close_process(
    entitlement_state: State<'_, SignedEntitlementState>,
    pid: u32,
) -> Result<(), String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;
    process_matcher::kill_process(pid)
}

#[tauri::command]
pub async fn close_file_processes(
    entitlement_state: State<'_, SignedEntitlementState>,
    file_path: String,
) -> Result<usize, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;
    let processes = process_matcher::find_processes_for_file(&file_path)?;
    let count = processes.len();

    for process in processes {
        process_matcher::kill_process(process.pid)?;
    }
    Ok(count)
}
