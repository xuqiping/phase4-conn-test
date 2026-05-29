use crate::platform::windows::process_monitor::{ProcessMonitor, CloseResult};
use crate::types::process::ProcessInfo;
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
pub fn close_app_process(window_handle: usize) -> Result<(), String> {
    let monitor = PROCESS_MONITOR
        .lock()
      .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

  monitor.close_process(window_handle)
}

#[tauri::command]
pub fn close_app_processes(window_handles: Vec<usize>) -> Result<CloseResult, String> {
    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.close_processes(window_handles)
}

#[tauri::command]
pub fn activate_app_window(window_handle: usize) -> Result<(), String> {
    let monitor = PROCESS_MONITOR
        .lock()
        .map_err(|e| format!("Failed to lock process monitor: {}", e))?;

    monitor.activate_window(window_handle)
}
