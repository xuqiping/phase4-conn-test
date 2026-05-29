pub mod cache;
pub mod link_preview;
pub mod ocr;
pub mod search;
pub mod security;
pub mod storage;
pub mod types;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::sync::atomic::AtomicBool;
use sha2::{Digest, Sha256};
use storage::ClipboardStorage;

pub use types::*;

pub struct ClipboardService {
    storage: Mutex<ClipboardStorage>,
    last_written_hash: Mutex<Option<String>>,
    monitor_running: Arc<AtomicBool>,
    #[cfg(target_os = "windows")]
    last_target_window: Mutex<Option<crate::platform::windows::foreground::ForegroundWindow>>,
}

impl ClipboardService {
    pub fn new(database_path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = database_path.parent() {
            std::fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        let storage = ClipboardStorage::open(&database_path)?;
        storage.init()?;
        Ok(Self {
            storage: Mutex::new(storage),
            last_written_hash: Mutex::new(None),
            monitor_running: Arc::new(AtomicBool::new(false)),
            #[cfg(target_os = "windows")]
            last_target_window: Mutex::new(None),
        })
    }

    pub fn in_memory() -> Result<Self, String> {
        let storage = ClipboardStorage::in_memory()?;
        storage.init()?;
        Ok(Self {
            storage: Mutex::new(storage),
            last_written_hash: Mutex::new(None),
            monitor_running: Arc::new(AtomicBool::new(false)),
            #[cfg(target_os = "windows")]
            last_target_window: Mutex::new(None),
        })
    }

    pub fn monitor_flag(&self) -> Arc<AtomicBool> {
        self.monitor_running.clone()
    }

    pub fn list_items(&self, query: &ClipboardQuery) -> Result<Vec<ClipboardItemSummary>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.list_items(query)
    }

    pub fn collect_text_snapshot(&self, text: &str, source_process: Option<&str>) -> Result<Option<String>, String> {
        if self.should_ignore_text(text)? {
            return Ok(None);
        }
        let id = self.add_text_for_testing(text, source_process)?;
        Ok(Some(id))
    }

    pub fn add_text_for_testing(&self, text: &str, source_process: Option<&str>) -> Result<String, String> {
        if let Some(reason) = security::is_sensitive_content(text) {
            return self.storage.lock().map_err(|err| err.to_string())?.insert_security_event(&reason, source_process);
        }
        if let Some((hex, rgb)) = search::detect_color(text) {
            return self.storage.lock().map_err(|err| err.to_string())?.insert_color_item(&hex, &rgb);
        }
        if let Some(url) = search::normalize_url(text) {
            if url.starts_with("http://") || url.starts_with("https://") {
                return self.storage.lock().map_err(|err| err.to_string())?.insert_url_item(&url);
            }
        }
        let title = text.chars().take(80).collect::<String>();
        self.storage.lock().map_err(|err| err.to_string())?.insert_text_item(text, &title, source_process)
    }

    pub fn add_html_for_testing(&self, html: &str) -> Result<String, String> {
        let markdown = html_to_plain_markdown(html);
        self.storage.lock().map_err(|err| err.to_string())?.insert_html_item(html, &markdown)
    }

    pub fn get_rich_fields(&self, id: &str) -> Result<(Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>), String> {
        self.storage.lock().map_err(|err| err.to_string())?.get_rich_fields(id)
    }

    pub fn add_image_for_testing(&self, image_path: &str) -> Result<String, String> {
        let reader = image::ImageReader::open(image_path).map_err(|err| err.to_string())?;
        let format = reader.format().map(|format| format.extensions_str()[0].to_string()).unwrap_or_else(|| "unknown".to_string());
        let image = reader.decode().map_err(|err| err.to_string())?;
        let width = image.width() as i64;
        let height = image.height() as i64;
        let bytes = std::fs::metadata(image_path).map_err(|err| err.to_string())?.len() as i64;
        self.storage.lock().map_err(|err| err.to_string())?.insert_image_item(image_path, width, height, &format, bytes)
    }

    pub fn get_image_meta(&self, id: &str) -> Result<Option<(String, i64, i64, String, Option<String>)>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.get_image_meta(id)
    }

    pub fn update_ocr_text(&self, id: &str, text: &str) -> Result<(), String> {
        self.storage.lock().map_err(|err| err.to_string())?.update_ocr_text(id, text)
    }

    pub fn retry_link_preview(&self, id: &str) -> Result<(), String> {
        let settings = self.load_settings()?;
        if !settings.enable_link_preview {
            return Err("URL 联网预览未开启".to_string());
        }
        let url = self.storage.lock().map_err(|err| err.to_string())?.get_url(id)?
            .ok_or_else(|| "该记录不是 URL".to_string())?;
        let preview = link_preview::fetch_preview(&url)?;
        self.storage.lock().map_err(|err| err.to_string())?.update_link_preview(id, preview.title.as_deref(), preview.description.as_deref())
    }

    pub fn add_file_for_testing(&self, path: &str, size_bytes: i64) -> Result<String, String> {
        let settings = self.load_settings()?;
        let copy_state = if cache::extension_allowed(path, &settings.file_extension_mode, &settings.file_extensions)
            && cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb)
        {
            "cached"
        } else {
            "reference_only"
        };
        let name = std::path::Path::new(path)
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or(path)
            .to_string();
        let entry = crate::clipboard::types::ClipboardFileEntry {
            name: name.clone(),
            original_path: path.to_string(),
            cached_path: if copy_state == "cached" { Some(path.to_string()) } else { None },
            size_bytes,
            modified_at: None,
            hash: None,
            is_directory: false,
            copy_state: copy_state.to_string(),
        };
        self.storage.lock().map_err(|err| err.to_string())?.insert_file_item(&[entry], &name, if copy_state == "cached" { size_bytes } else { 0 })
    }

    pub fn get_files(&self, id: &str) -> Result<Option<Vec<crate::clipboard::types::ClipboardFileEntry>>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.get_files(id)
    }

    pub fn delete_item(&self, id: &str) -> Result<(), String> {
        self.storage.lock().map_err(|err| err.to_string())?.delete_item(id)
    }

    pub fn storage_usage(&self) -> Result<Vec<(String, i64)>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.storage_usage_by_kind()
    }

    pub fn clear_history(&self, scope: &str) -> Result<(), String> {
        self.storage.lock().map_err(|err| err.to_string())?.clear_history(scope)
    }

    pub fn get_text(&self, id: &str) -> Result<Option<String>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.get_text(id)
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        self.storage.lock().map_err(|err| err.to_string())?.load_settings()
    }

    pub fn save_settings(&self, settings: &ClipboardSettings) -> Result<ClipboardSettings, String> {
        self.storage.lock().map_err(|err| err.to_string())?.save_settings(settings)?;
        Ok(settings.clone())
    }

    pub fn mark_written_text(&self, text: &str) -> Result<(), String> {
        let hash = hash_text(text);
        *self.last_written_hash.lock().map_err(|err| err.to_string())? = Some(hash);
        Ok(())
    }

    pub fn should_ignore_text(&self, text: &str) -> Result<bool, String> {
        let hash = hash_text(text);
        let mut last = self.last_written_hash.lock().map_err(|err| err.to_string())?;
        if last.as_ref() == Some(&hash) {
            *last = None;
            return Ok(true);
        }
        Ok(false)
    }

    #[cfg(target_os = "windows")]
    pub fn remember_current_foreground_window(&self) -> Result<(), String> {
        let window = crate::platform::windows::foreground::current_foreground_window();
        *self.last_target_window.lock().map_err(|err| err.to_string())? = window;
        Ok(())
    }

    #[cfg(target_os = "windows")]
    pub fn paste_to_remembered_window(&self) -> Result<(), String> {
        let window = *self.last_target_window.lock().map_err(|err| err.to_string())?;
        let window = window.ok_or_else(|| "没有可恢复的目标窗口".to_string())?;
        crate::platform::windows::foreground::restore_and_paste(window)
    }
}

fn html_to_plain_markdown(html: &str) -> String {
    html
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("</p>", "\n")
        .replace("<p>", "")
        .replace("<strong>", "**")
        .replace("</strong>", "**")
        .replace("<b>", "**")
        .replace("</b>", "**")
        .replace("<em>", "*")
        .replace("</em>", "*")
}

fn hash_text(text: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    format!("{:x}", hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sensitive_text_becomes_security_event() {
        let service = ClipboardService::in_memory().unwrap();
        service.add_text_for_testing("4111 1111 1111 1111", Some("browser.exe")).unwrap();
        let items = service.list_items(&ClipboardQuery {
            query: None,
            kind: None,
            favorite_only: None,
            source_app: None,
            limit: 20,
            offset: 0,
        }).unwrap();

        assert_eq!(items[0].kind, ClipboardKind::SecurityEvent);
    }

    #[test]
    fn written_text_hash_can_be_ignored_once() {
        let service = ClipboardService::in_memory().unwrap();
        service.mark_written_text("hello").unwrap();

        assert!(service.should_ignore_text("hello").unwrap());
        assert!(!service.should_ignore_text("world").unwrap());
    }
}
