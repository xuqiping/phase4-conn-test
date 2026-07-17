use crate::commands::auth::SignedEntitlementState;
use crate::platform::windows::process_monitor::{ProcessMonitor, CloseResult};
use crate::types::process::ProcessInfo;
use crate::utils::process_matcher;
use lazy_static::lazy_static;
use std::sync::Mutex;
use std::time::Instant;
use tauri::State;

const MODULE_CODE: &str = "processes";

lazy_static! {
    static ref PROCESS_MONITOR: Mutex<ProcessMonitor> = Mutex::new(ProcessMonitor::new());
}

#[tauri::command]
pub fn get_running_processes(
    entitlement_state: State<'_, SignedEntitlementState>,
) -> Result<Vec<ProcessInfo>, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;

    let start = Instant::now();

    let mut monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    let result = monitor.enumerate_processes();

    let elapsed = start.elapsed();
    println!("[PERF] Process enumeration took: {:?}", elapsed);

    result
}

#[tauri::command]
pub fn close_app_process(
    entitlement_state: State<'_, SignedEntitlementState>,
    window_handle: usize,
) -> Result<(), String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;

    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.close_process(window_handle)
}

#[tauri::command]
pub fn close_app_processes(
    entitlement_state: State<'_, SignedEntitlementState>,
    window_handles: Vec<usize>,
) -> Result<CloseResult, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;

    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.close_processes(window_handles)
}

#[tauri::command]
pub fn kill_app_process(
    entitlement_state: State<'_, SignedEntitlementState>,
    pid: u32,
) -> Result<(), String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;
    process_matcher::kill_process(pid)
}

#[tauri::command]
pub fn kill_app_processes(
    entitlement_state: State<'_, SignedEntitlementState>,
    pids: Vec<u32>,
) -> Result<CloseResult, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;
    Ok(kill_processes_by_pid(pids, process_matcher::kill_process))
}

fn kill_processes_by_pid<F>(pids: Vec<u32>, mut kill_process: F) -> CloseResult
where
    F: FnMut(u32) -> Result<(), String>,
{
    let mut succeeded = 0;
    let mut failed = 0;

    for pid in pids {
        match kill_process(pid) {
            Ok(_) => succeeded += 1,
            Err(_) => failed += 1,
        }
    }

    CloseResult { succeeded, failed }
}

#[tauri::command]
pub fn activate_app_window(
    entitlement_state: State<'_, SignedEntitlementState>,
    window_handle: usize,
) -> Result<(), String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;

    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.activate_window(window_handle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_pid_kill_results() {
        let result = kill_processes_by_pid(vec![10, 20, 30], |pid| {
            if pid == 20 {
                Err("denied".to_string())
            } else {
                Ok(())
            }
        });

        assert_eq!(result.succeeded, 2);
        assert_eq!(result.failed, 1);
    }
}
