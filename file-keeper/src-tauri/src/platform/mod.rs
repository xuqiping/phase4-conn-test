#[cfg(target_os = "windows")]
pub mod windows;

#[cfg(target_os = "windows")]
pub mod windows_file_matcher;
#[cfg(target_os = "macos")]
pub mod macos;

#[cfg(target_os = "linux")]
pub mod linux;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    pub pid: u32,
    pub name: String,
  pub path: Option<String>,
    pub window_title: Option<String>,
    pub associated_file: Option<String>,
}

pub trait ProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String>;
    fn kill_process(pid: u32) -> Result<(), String>;
}
