use crate::platform::windows::process_monitor::{ProcessMonitor, CloseResult};
use crate::types::process::ProcessInfo;
use crate::utils::process_matcher;
use lazy_static::lazy_static;
use std::sync::Mutex;
use std::time::Instant;

lazy_static! {
    static ref PROCESS_MONITOR: Mutex<ProcessMonitor> = Mutex::new(ProcessMonitor::new());
}

#[tauri::command]
pub fn get_running_processes() -> Result<Vec<ProcessInfo>, String> {
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
    window_handle: usize,
) -> Result<(), String> {
    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.close_process(window_handle)
}

#[tauri::command]
pub fn close_app_processes(
    window_handles: Vec<usize>,
) -> Result<CloseResult, String> {
    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.close_processes(window_handles)
}

#[tauri::command]
pub fn kill_app_process(
    pid: u32,
) -> Result<(), String> {
    process_matcher::kill_process(pid)
}

#[tauri::command]
pub fn kill_app_processes(
    pids: Vec<u32>,
) -> Result<CloseResult, String> {
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
    window_handle: usize,
) -> Result<(), String> {
    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.activate_window(window_handle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process_commands_do_not_require_entitlement_state() {
        let _: fn() -> Result<Vec<ProcessInfo>, String> = get_running_processes;
        let _: fn(usize) -> Result<(), String> = close_app_process;
        let _: fn(Vec<usize>) -> Result<CloseResult, String> = close_app_processes;
        let _: fn(u32) -> Result<(), String> = kill_app_process;
        let _: fn(Vec<u32>) -> Result<CloseResult, String> = kill_app_processes;
        let _: fn(usize) -> Result<(), String> = activate_app_window;
    }

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
