use super::{ProcessInfo, ProcessMatcher};
use std::fs;
use std::path::Path;
use sysinfo::System;

pub struct LinuxProcessMatcher;

impl ProcessMatcher for LinuxProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
        let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
            .canonicalize()
         .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        for (pid, process) in system.processes() {
         // Check /proc/[pid]/fd/ for open file descriptors
            let fd_path = format!("/proc/{}/fd", pid.as_u32());
          if let Ok(entries) = fs::read_dir(&fd_path) {
         for entry in entries.flatten() {
                 if let Ok(link) = fs::read_link(entry.path()) {
              if link == file_path_normalized {
                       matching_processes.push(ProcessInfo {
              pid: pid.as_u32(),
                 name: process.name().to_string(),
                    path: process.exe().to_str().map(|s| s.to_string()),
                    window_title: None,
                     associated_file: Some(file_path.to_string()),
                });
                       break;
                    }
              }
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
