use std::path::Path;

#[tauri::command]
pub async fn open_file(path: String) -> Result<(), String> {
    if !Path::new(&path).exists() {
      return Err("文件不存在".to_string());
    }

    opener::open(&path).map_err(|e| format!("打开失败: {}", e))
}

#[tauri::command]
pub async fn validate_path(path: String) -> Result<bool, String> {
    Ok(Path::new(&path).exists())
}
