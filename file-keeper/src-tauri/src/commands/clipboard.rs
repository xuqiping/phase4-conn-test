use crate::clipboard::{
    ClipboardItemSummary, ClipboardQuery, ClipboardService, ClipboardSettings,
    ClipboardStorageTypeUsage, ClipboardStorageUsage,
};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager, State};

#[tauri::command]
pub fn start_clipboard_monitor(
    app: AppHandle,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    let running = service.monitor_flag();
    if running.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return Ok(());
    }

    let service_ptr = service.inner() as *const ClipboardService as usize;
    std::thread::spawn(move || {
        let service = unsafe { &*(service_ptr as *const ClipboardService) };
        let mut last_text = String::new();
        let mut last_files = String::new();
        let mut last_image_hash = String::new();
        while running.load(std::sync::atomic::Ordering::SeqCst) {
            #[cfg(target_os = "windows")]
            {
                if let Ok(Some(files)) = crate::platform::windows::clipboard::read_files() {
                    let signature = files.join("\n");
                    if !signature.is_empty() && signature != last_files {
                        last_files = signature;
                        if let Ok(id) = service.collect_file_snapshot(&files, None) {
                            let _ = app.emit("clipboard://changed", id);
                        }
                    }
                    std::thread::sleep(std::time::Duration::from_millis(500));
                    continue;
                }

                if let Ok(Some(image)) = crate::platform::windows::clipboard::read_image() {
                    let image_hash = hash_bytes(&image);
                    if image_hash != last_image_hash {
                        last_image_hash = image_hash;
                        if let Ok(Some(id)) = service.collect_image_bytes_snapshot(&image) {
                            let _ = app.emit("clipboard://changed", id);
                        }
                    }
                    std::thread::sleep(std::time::Duration::from_millis(500));
                    continue;
                }

                if let Ok(Some(text)) = crate::platform::windows::clipboard::read_text() {
                    if !text.is_empty() && text != last_text {
                        last_text = text.clone();
                        if let Ok(Some(id)) = service.collect_text_snapshot(&text, None) {
                            let _ = app.emit("clipboard://changed", id);
                        }
                    }
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
    });

    Ok(())
}

#[tauri::command]
pub fn stop_clipboard_monitor(
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    service
        .monitor_flag()
        .store(false, std::sync::atomic::Ordering::SeqCst);
    Ok(())
}

#[tauri::command]
pub fn get_clipboard_items(
    query: ClipboardQuery,
    service: State<'_, ClipboardService>,
) -> Result<Vec<ClipboardItemSummary>, String> {
    service.list_items(&query)
}

#[tauri::command]
pub fn search_clipboard_items(
    query: ClipboardQuery,
    service: State<'_, ClipboardService>,
) -> Result<Vec<ClipboardItemSummary>, String> {
    service.list_items(&query)
}

#[tauri::command]
pub fn add_clipboard_text_for_testing(
    text: String,
    source_process: Option<String>,
    service: State<'_, ClipboardService>,
) -> Result<String, String> {
    service.add_text_for_testing(&text, source_process.as_deref())
}

#[tauri::command]
pub fn add_clipboard_file_for_testing(
    path: String,
    size_bytes: i64,
    service: State<'_, ClipboardService>,
) -> Result<String, String> {
    service.add_file_for_testing(&path, size_bytes)
}

#[tauri::command]
pub fn add_clipboard_image_for_testing(
    path: String,
    service: State<'_, ClipboardService>,
) -> Result<String, String> {
    service.add_image_for_testing(&path)
}

#[tauri::command]
pub fn add_clipboard_html_for_testing(
    html: String,
    service: State<'_, ClipboardService>,
) -> Result<String, String> {
    service.add_html_for_testing(&html)
}

#[tauri::command]
pub fn set_clipboard_ocr_text_for_testing(
    id: String,
    text: String,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    service.update_ocr_text(&id, &text)
}

#[tauri::command]
pub fn delete_clipboard_item(
    id: String,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    service.delete_item(&id)
}

#[tauri::command]
pub fn update_clipboard_item_note(
    id: String,
    note: Option<String>,
    service: State<'_, ClipboardService>,
) -> Result<Option<String>, String> {
    service.update_note(&id, note.as_deref())
}

#[tauri::command]
pub fn get_clipboard_settings(
    service: State<'_, ClipboardService>,
) -> Result<ClipboardSettings, String> {
    service.load_settings()
}

#[tauri::command]
pub fn update_clipboard_settings(
    settings: ClipboardSettings,
    service: State<'_, ClipboardService>,
) -> Result<ClipboardSettings, String> {
    service.save_settings(&settings)
}

#[tauri::command]
pub fn get_clipboard_storage_usage(
    service: State<'_, ClipboardService>,
) -> Result<ClipboardStorageUsage, String> {
    let settings = service.load_settings()?;
    let usage = service.storage_usage()?;
    let total_bytes = usage.iter().map(|(_, bytes)| *bytes).sum();
    Ok(ClipboardStorageUsage {
        total_bytes,
        limit_bytes: settings.total_non_text_limit_mb * 1024 * 1024,
        by_type: usage
            .into_iter()
            .map(|(kind, bytes)| ClipboardStorageTypeUsage {
                kind,
                bytes,
                limit_bytes: None,
            })
            .collect(),
    })
}

#[tauri::command]
pub fn get_clipboard_item_detail(
    id: String,
    service: State<'_, ClipboardService>,
) -> Result<crate::clipboard::ClipboardItemDetail, String> {
    let item = service
        .get_item_summary(&id)?
        .ok_or_else(|| "剪贴板记录不存在".to_string())?;
    let text = service.get_text(&id)?;
    let files = service.get_files(&id)?;
    let image_meta = service.get_image_meta(&id)?;
    let (html, markdown, url, url_title, url_description, color_hex, color_rgb) =
        service.get_rich_fields(&id)?;
    let (image_path, image_width, image_height, image_format, ocr_text) = image_meta
        .map(|(path, width, height, format, ocr)| {
            (Some(path), Some(width), Some(height), Some(format), ocr)
        })
        .unwrap_or((None, None, None, None, None));
    Ok(crate::clipboard::ClipboardItemDetail {
        summary: item,
        text,
        html: html.clone(),
        sanitized_html: html,
        markdown,
        image_path,
        image_width,
        image_height,
        image_format,
        ocr_text,
        files,
        url,
        url_title,
        url_description,
        url_thumbnail_path: None,
        color_hex,
        color_rgb,
        security_reason: None,
        available_formats: vec![
            crate::clipboard::ClipboardPasteFormat::Original,
            crate::clipboard::ClipboardPasteFormat::PlainText,
        ],
    })
}

#[tauri::command]
pub fn copy_clipboard_item(
    id: String,
    format: crate::clipboard::ClipboardPasteFormat,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    if matches!(
        format,
        crate::clipboard::ClipboardPasteFormat::Original
            | crate::clipboard::ClipboardPasteFormat::FileCopy
    ) {
        if let Some(paths) = service.get_file_copy_paths(&id)? {
            return write_files_to_system_clipboard(&paths, &service);
        }
    }

    let text = service
        .get_copy_text(&id)?
        .ok_or_else(|| "该记录没有可复制的内容".to_string())?;
    write_text_to_system_clipboard(&text, &service)
}

#[tauri::command]
pub fn copy_clipboard_items(
    ids: Vec<String>,
    format: crate::clipboard::ClipboardPasteFormat,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    if matches!(
        format,
        crate::clipboard::ClipboardPasteFormat::Original
            | crate::clipboard::ClipboardPasteFormat::FileCopy
    ) {
        let mut file_paths = Vec::new();
        for id in &ids {
            if let Some(paths) = service.get_file_copy_paths(id)? {
                file_paths.extend(paths);
            } else {
                file_paths.clear();
                break;
            }
        }
        if !file_paths.is_empty() {
            return write_files_to_system_clipboard(&file_paths, &service);
        }
    }

    let text = service.get_joined_text(&ids)?;
    write_text_to_system_clipboard(&text, &service)
}

fn write_text_to_system_clipboard(text: &str, service: &ClipboardService) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        crate::platform::windows::clipboard::write_text(text)?;
        service.mark_written_text(text)?;
        Ok(())
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = text;
        let _ = service;
        Err("当前平台尚未实现剪贴板写入".to_string())
    }
}

fn write_files_to_system_clipboard(
    paths: &[String],
    service: &ClipboardService,
) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        crate::platform::windows::clipboard::write_files(paths)?;
        service.mark_written_text(&paths.join("\n"))?;
        Ok(())
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = paths;
        let _ = service;
        Err("当前平台尚未实现文件剪贴板写入".to_string())
    }
}

#[tauri::command]
pub fn paste_clipboard_item(
    id: String,
    format: crate::clipboard::ClipboardPasteFormat,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    copy_clipboard_item(id, format, service.clone())?;
    let settings = service.load_settings()?;
    if !settings.auto_paste {
        return Ok(());
    }

    #[cfg(target_os = "windows")]
    {
        service.paste_to_remembered_window()
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(())
    }
}

#[tauri::command]
pub fn remember_clipboard_target_window(
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        service.remember_current_foreground_window()
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = service;
        Ok(())
    }
}

#[tauri::command]
pub fn clear_clipboard_history(
    scope: String,
    service: State<'_, ClipboardService>,
) -> Result<(), String> {
    service.clear_history(&scope)
}

#[tauri::command]
pub fn rebuild_clipboard_index(service: State<'_, ClipboardService>) -> Result<(), String> {
    service.rebuild_search_index()
}

#[tauri::command]
pub fn retry_link_preview(id: String, service: State<'_, ClipboardService>) -> Result<(), String> {
    service.retry_link_preview(&id)
}

fn hash_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("{:x}", hasher.finalize())
}

pub fn clipboard_database_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let app_data = app.path().app_data_dir().map_err(|err| err.to_string())?;
    Ok(app_data.join("clipboard-history.sqlite"))
}

#[cfg(test)]
mod access_mode_tests {
    use super::*;

    #[test]
    fn monitor_commands_do_not_require_entitlement_state() {
        let _: fn(AppHandle, State<'_, ClipboardService>) -> Result<(), String> =
            start_clipboard_monitor;
        let _: fn(State<'_, ClipboardService>) -> Result<(), String> = stop_clipboard_monitor;
    }
}
