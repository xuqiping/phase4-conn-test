use serde::Serialize;
use std::path::Path;
use tauri::Manager;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedArtifactDescriptor {
    kind: &'static str,
    cache_path: String,
    original_path: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FavoritePathDescriptor {
    name: String,
    path: String,
    source_path: String,
    item_type: &'static str,
    managed_artifact: Option<ManagedArtifactDescriptor>,
    shortcut_target_path: Option<String>,
}

fn display_name(path: &Path) -> String {
    path.file_name()
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_else(|| path.to_string_lossy().into_owned())
}

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
pub async fn import_favorite_path(
    app: tauri::AppHandle,
    path: String,
) -> Result<FavoritePathDescriptor, String> {
    let source = Path::new(&path);
    if !source.is_absolute() {
        return Err("收藏路径必须是绝对路径".to_string());
    }
    let metadata = std::fs::metadata(source).map_err(|_| "路径不存在或无法访问".to_string())?;
    if !metadata.is_file() && !metadata.is_dir() {
        return Err("不支持的路径类型".to_string());
    }

    #[cfg(target_os = "windows")]
    if metadata.is_file()
        && source
            .extension()
            .and_then(|value| value.to_str())
            .is_some_and(|value| value.eq_ignore_ascii_case("lnk"))
    {
        use crate::platform::windows::managed_shortcut::{
            import_shortcut_copy, resolve_shortcut_target,
        };
        let target = resolve_shortcut_target(source)?;
        let managed_root = app
            .path()
            .app_data_dir()
            .map_err(|_| "无法定位应用数据目录".to_string())?
            .join("managed-shortcuts");
        let cache_path = import_shortcut_copy(source, &managed_root)?;
        return Ok(FavoritePathDescriptor {
            name: display_name(source),
            path: cache_path.to_string_lossy().into_owned(),
            source_path: path.clone(),
            item_type: "file",
            managed_artifact: Some(ManagedArtifactDescriptor {
                kind: "windows-shortcut-copy",
                cache_path: cache_path.to_string_lossy().into_owned(),
                original_path: path,
            }),
            shortcut_target_path: Some(target.to_string_lossy().into_owned()),
        });
    }

    Ok(FavoritePathDescriptor {
        name: display_name(source),
        path: path.clone(),
        source_path: path,
        item_type: if metadata.is_dir() { "folder" } else { "file" },
        managed_artifact: None,
        shortcut_target_path: None,
    })
}

#[tauri::command]
pub async fn validate_favorite_path(
    path: String,
    shortcut_target_path: Option<String>,
) -> Result<bool, String> {
    if !Path::new(&path).exists() {
        return Ok(false);
    }
    Ok(shortcut_target_path
        .filter(|target| !target.trim().is_empty())
        .map(|target| Path::new(&target).exists())
        .unwrap_or(true))
}

#[tauri::command]
pub async fn delete_managed_shortcut(
    app: tauri::AppHandle,
    cache_path: String,
) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        let managed_root = app
            .path()
            .app_data_dir()
            .map_err(|_| "无法定位应用数据目录".to_string())?
            .join("managed-shortcuts");
        return crate::platform::windows::managed_shortcut::delete_managed_copy(
            Path::new(&cache_path),
            &managed_root,
        );
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = app;
        let _ = cache_path;
        Err("当前平台不支持 Windows 快捷方式托管".to_string())
    }
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
