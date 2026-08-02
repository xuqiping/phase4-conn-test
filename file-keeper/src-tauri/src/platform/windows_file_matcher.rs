use super::{ProcessInfo, ProcessMatcher};
use std::path::Path;
use sysinfo::System;

#[cfg(target_os = "windows")]
use windows::Win32::Foundation::{BOOL, HWND, LPARAM};
#[cfg(target_os = "windows")]
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowTextW, GetWindowThreadProcessId,
};

pub struct WindowsProcessMatcher;

impl ProcessMatcher for WindowsProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
        let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
          .canonicalize()
            .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        for (pid, process) in system.processes() {
            let process_path = process.exe();
            let process_name = process.name().to_string();

            // Get window titles for this process
          let window_titles = get_window_titles_for_pid(pid.as_u32());

          // Check if any window title contains the file name or path
            let file_name = file_path_normalized
             .file_name()
              .and_then(|n| n.to_str())
                .unwrap_or("");

            for title in window_titles {
                if title.contains(file_name) || title.contains(file_path) {
                    matching_processes.push(ProcessInfo {
                  pid: pid.as_u32(),
                    name: process_name.clone(),
                      path: process_path.and_then(|p| p.to_str()).map(|s| s.to_string()),
                        window_title: Some(title.clone()),
                associated_file: Some(file_path.to_string()),
                    });
                    break;
          }
            }
        }

        Ok(matching_processes)
    }

    fn kill_process(pid: u32) -> Result<(), String> {
        let mut system = System::new_all();
        system.refresh_all();

        if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
            if process.kill() {
                Ok(())
            } else {
                Err(format!("Failed to kill process {}", pid))
            }
        } else {
          Err(format!("Process {} not found", pid))
        }
    }
}

#[cfg(target_os = "windows")]
fn get_window_titles_for_pid(_target_pid: u32) -> Vec<String> {
    use std::sync::Mutex;

    let titles = Mutex::new(Vec::new());

    unsafe {
        let lparam = LPARAM(&titles as *const _ as isize);
        let _ = EnumWindows(Some(enum_windows_callback), lparam);
    }

    titles.into_inner().unwrap()
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn enum_windows_callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
    let titles = &*(lparam.0 as *const std::sync::Mutex<Vec<String>>);

    let mut pid: u32 = 0;
    GetWindowThreadProcessId(hwnd, Some(&mut pid));

    let mut title = [0u16; 512];
    let len = GetWindowTextW(hwnd, &mut title);

    if len > 0 {
        let title_str = String::from_utf16_lossy(&title[..len as usize]);
        if !title_str.is_empty() {
            titles.lock().unwrap().push(title_str);
        }
    }

    BOOL(1) // Continue enumeration
}
