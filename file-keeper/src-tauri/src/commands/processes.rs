use crate::platform::ProcessInfo;
use crate::utils::process_matcher;

#[tauri::command]
pub async fn find_file_processes(
    file_path: String,
) -> Result<Vec<ProcessInfo>, String> {
    process_matcher::find_processes_for_file(&file_path)
}

#[tauri::command]
pub async fn close_process(
    pid: u32,
) -> Result<(), String> {
    process_matcher::kill_process(pid)
}

#[tauri::command]
pub async fn close_file_processes(
    file_path: String,
) -> Result<usize, String> {
    let processes = process_matcher::find_processes_for_file(&file_path)?;
    let count = processes.len();

    for process in processes {
        process_matcher::kill_process(process.pid)?;
    }
    Ok(count)
}
