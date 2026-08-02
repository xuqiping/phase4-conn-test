use std::path::Path;

#[tauri::command]
pub async fn open_file(path: String) -> Result<(), String> {
    let path_obj = Path::new(&path);

    if !path_obj.exists() {
        return Err(format!("文件不存在: {}", path));
    }

    if path_obj.is_dir() {
        // Open folder in file explorer
        opener::open(&path).map_err(|e| format!("打开文件夹失败: {}", e))
    } else if path_obj.is_file() {
        // Open file with default application
        opener::open(&path).map_err(|e| format!("打开文件失败: {}", e))
    } else {
        Err(format!("不支持的路径类型: {}", path))
    }
}

#[tauri::command]
pub async fn validate_path(path: String) -> Result<bool, String> {
    let path_obj = Path::new(&path);
    Ok(path_obj.exists())
}

#[tauri::command]
pub async fn show_in_folder(path: String) -> Result<(), String> {
    let path_obj = Path::new(&path);

    if !path_obj.exists() {
        return Err(format!("路径不存在: {}", path));
    }

    #[cfg(target_os = "windows")]
    {
        use std::process::Command;
        if path_obj.is_file() {
            // Show file in explorer and select it
            Command::new("explorer")
                .args(["/select,", &path])
            .spawn()
             .map_err(|e| format!("打开文件夹失败: {}", e))?;
        } else {
            // Open folder
            Command::new("explorer")
                .arg(&path)
             .spawn()
                .map_err(|e| format!("打开文件夹失败: {}", e))?;
     }
        Ok(())
    }

    #[cfg(not(target_os = "windows"))]
    {
        // For macOS/Linux, open parent folder
        let folder = if path_obj.is_file() {
            path_obj.parent().unwrap_or(path_obj)
        } else {
            path_obj
        };
        opener::open(folder).map_err(|e| format!("打开文件夹失败: {}", e))
    }
}
