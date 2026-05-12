use crate::platform::ProcessInfo;

#[cfg(target_os = "windows")]
use crate::platform::windows::WindowsProcessMatcher as PlatformMatcher;

#[cfg(target_os = "macos")]
use crate::platform::macos::MacOSProcessMatcher as PlatformMatcher;

#[cfg(target_os = "linux")]
use crate::platform::linux::LinuxProcessMatcher as PlatformMatcher;

use crate::platform::ProcessMatcher;

pub fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
    PlatformMatcher::find_processes_for_file(file_path)
}

pub fn kill_process(pid: u32) -> Result<(), String> {
    PlatformMatcher::kill_process(pid)
}
