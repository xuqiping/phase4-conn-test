use crate::types::process::{ProcessCategory, ProcessInfo};
use super::process_mappings::load_process_mappings;
use std::collections::{HashMap, HashSet};
use sysinfo::{System, ProcessRefreshKind};
use serde::{Deserialize, Serialize};
use windows::Win32::Foundation::{BOOL, HWND, LPARAM};
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
    PostMessageW, WM_CLOSE,
};

pub struct ProcessMonitor {
    mappings: HashMap<String, ProcessCategory>,
    system: System,
}

impl ProcessMonitor {
    pub fn new() -> Self {
        Self {
            mappings: load_process_mappings(),
            system: System::new_all(),
      }
    }

    pub fn enumerate_processes(&mut self) -> Result<Vec<ProcessInfo>, String> {
        // Refresh system information
        self.system.refresh_processes_specifics(ProcessRefreshKind::everything());
        let mut processes: Vec<WindowInfo> = Vec::new();
        let processes_ptr = &mut processes as *mut Vec<WindowInfo>;

        unsafe {
            // Enumerate all top-level windows
            EnumWindows(
            Some(enum_windows_callback),
             LPARAM(processes_ptr as isize),
            )
            .map_err(|e| format!("Failed to enumerate windows: {}", e))?;
        }

           // Get process details for each window
        let mut result = Vec::new();
    let mut seen_pids = HashSet::new();

    for proc_info in processes {
            // Skip duplicate PIDs (same process with multiple windows)
       if !seen_pids.insert(proc_info.pid) {
                continue;
            }

            if let Some(process) = self.system.process(sysinfo::Pid::from_u32(proc_info.pid)) {
             let process_name = process.name().to_string().to_lowercase();
           let category = self
               .mappings
              .get(&process_name)
                .copied()
                .unwrap_or(ProcessCategory::Other);

         let memory_mb = process.memory() as f64 / 1024.0 / 1024.0;
                let cpu_usage = process.cpu_usage();

                result.push(ProcessInfo {
                    pid: proc_info.pid,
                name: process.name().to_string(),
               window_title: proc_info.window_title,
                  category,
                    memory_mb,
                  cpu_usage,
             window_handle: proc_info.window_handle,
             });
            }
        }

        Ok(result)
    }

    /// Close a single process by sending WM_CLOSE to its window
    pub fn close_process(&self, window_handle: usize) -> Result<(), String> {
      unsafe {
               let hwnd = HWND(window_handle as *mut core::ffi::c_void);
         PostMessageW(hwnd, WM_CLOSE, windows::Win32::Foundation::WPARAM(0), LPARAM(0))
              .map_err(|e| format!("Failed to close process: {}", e))?;
        }
        Ok(())
    }

    /// Close multiple processes
    pub fn close_processes(&self, window_handles: Vec<usize>) -> Result<CloseResult, String> {
        let mut succeeded = 0;
        let mut failed = 0;

        for handle in window_handles {
          match self.close_process(handle) {
                Ok(_) => succeeded += 1,
                Err(_) => failed += 1,
            }
     }

        Ok(CloseResult { succeeded, failed })
    }
}

// Temporary struct to hold window information during enumeration
#[derive(Debug)]
struct WindowInfo {
    pid: u32,
    window_title: String,
    window_handle: usize,
}

/// Result of closing processes
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CloseResult {
    pub succeeded: usize,
    pub failed: usize,
}

/// Callback function for EnumWindows
unsafe extern "system" fn enum_windows_callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
    let processes = &mut *(lparam.0 as *mut Vec<WindowInfo>);

    // Only process visible windows
    if IsWindowVisible(hwnd).as_bool() {
        // Get window title
        let mut title: [u16; 512] = [0; 512];
        let len = GetWindowTextW(hwnd, &mut title);

        if len > 0 {
            let window_title = String::from_utf16_lossy(&title[..len as usize]);

            // Skip empty titles and system windows
          if !window_title.is_empty() && window_title != "Program Manager" {
                // Get process ID for this window
                let mut pid: u32 = 0;
                GetWindowThreadProcessId(hwnd, Some(&mut pid));

                if pid > 0 {
         processes.push(WindowInfo {
                   pid,
                  window_title,
                   window_handle: hwnd.0 as usize,
                    });
           }
          }
     }
    }

    BOOL::from(true) // Continue enumeration
}
