use super::{ProcessInfo, ProcessMatcher};
use std::path::Path;
use std::process::Command;
use sysinfo::System;

pub struct MacOSProcessMatcher;

impl ProcessMatcher for MacOSProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
      let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
            .canonicalize()
          .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        // Use lsof to find processes with the file open
        let output = Command::new("lsof")
            .arg(file_path_normalized.to_str().unwrap())
            .output()
         .map_err(|e| format!("Failed to run lsof: {}", e))?;

        if output.status.success() {
            let stdout = String::from_utf8_lossy(&output.stdout);
            for line in stdout.lines().skip(1) {
                // Skip header
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                  if let Ok(pid) = parts[1].parse::<u32>() {
                    if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
                            matching_processes.push(ProcessInfo {
                     pid,
                         name: process.name().to_string(),
                      path: process.exe().to_str().map(|s| s.to_string()),
                   window_title: None,
                           associated_file: Some(file_path.to_string()),
               });
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
