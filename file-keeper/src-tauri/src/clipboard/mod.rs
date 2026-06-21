pub mod cache;
pub mod link_preview;
pub mod ocr;
pub mod ocr_provider;
pub mod search;
pub mod security;
pub mod storage;
pub mod types;

use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use storage::ClipboardStorage;
use uuid::Uuid;

pub use types::*;

pub struct ClipboardService {
    storage: Mutex<ClipboardStorage>,
    cache_dir: Option<PathBuf>,
    database_path: Option<PathBuf>,
    last_written_hash: Mutex<Option<String>>,
    monitor_running: Arc<AtomicBool>,
    #[cfg(target_os = "windows")]
    last_target_window: Mutex<Option<crate::platform::windows::foreground::ForegroundWindow>>,
}

impl ClipboardService {
    pub fn new(database_path: PathBuf) -> Result<Self, String> {
        let cache_dir = database_path
            .parent()
            .map(|parent| parent.join("clipboard-cache"));
        if let Some(parent) = database_path.parent() {
            std::fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        if let Some(cache_dir) = &cache_dir {
            std::fs::create_dir_all(cache_dir).map_err(|err| err.to_string())?;
        }
        let storage = ClipboardStorage::open(&database_path)?;
        storage.init()?;
        storage.rebuild_search_text_index()?;
        Ok(Self {
            storage: Mutex::new(storage),
            cache_dir,
            database_path: Some(database_path),
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
            cache_dir: None,
            database_path: None,
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
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .list_items(query)
    }

    pub fn get_item_summary(&self, id: &str) -> Result<Option<ClipboardItemSummary>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_item_summary(id)
    }

    pub fn collect_text_snapshot(
        &self,
        text: &str,
        source_process: Option<&str>,
    ) -> Result<Option<String>, String> {
        if self.should_ignore_text(text)? {
            return Ok(None);
        }
        let id = self.add_text_for_testing(text, source_process)?;
        Ok(Some(id))
    }

    pub fn add_text_for_testing(
        &self,
        text: &str,
        source_process: Option<&str>,
    ) -> Result<String, String> {
        if let Some(reason) = security::is_sensitive_content(text) {
            return self
                .storage
                .lock()
                .map_err(|err| err.to_string())?
                .insert_security_event(&reason, source_process);
        }
        if let Some((hex, rgb)) = search::detect_color(text) {
            return self
                .storage
                .lock()
                .map_err(|err| err.to_string())?
                .insert_color_item(&hex, &rgb);
        }
        if let Some(url) = search::normalize_url(text) {
            if url.starts_with("http://") || url.starts_with("https://") {
                return self
                    .storage
                    .lock()
                    .map_err(|err| err.to_string())?
                    .insert_url_item(&url);
            }
        }
        let title = text.chars().take(80).collect::<String>();
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_text_item(text, &title, source_process)
    }

    pub fn add_html_for_testing(&self, html: &str) -> Result<String, String> {
        let markdown = html_to_plain_markdown(html);
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_html_item(html, &markdown)
    }

    pub fn get_rich_fields(
        &self,
        id: &str,
    ) -> Result<
        (
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
        ),
        String,
    > {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_rich_fields(id)
    }

    pub fn add_image_for_testing(&self, image_path: &str) -> Result<String, String> {
        let reader = image::ImageReader::open(image_path).map_err(|err| err.to_string())?;
        let format = reader
            .format()
            .map(|format| format.extensions_str()[0].to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let image = reader.decode().map_err(|err| err.to_string())?;
        let width = image.width() as i64;
        let height = image.height() as i64;
        let bytes = std::fs::metadata(image_path)
            .map_err(|err| err.to_string())?
            .len() as i64;
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_image_item(image_path, width, height, &format, bytes)
    }

    pub fn get_image_meta(
        &self,
        id: &str,
    ) -> Result<Option<(String, i64, i64, String, Option<String>)>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_image_meta(id)
    }

    pub fn update_ocr_text(&self, id: &str, text: &str) -> Result<(), String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .update_ocr_text(id, text)
    }

    pub fn retry_link_preview(&self, id: &str) -> Result<(), String> {
        let settings = self.load_settings()?;
        if !settings.enable_link_preview {
            return Err("URL 联网预览未开启".to_string());
        }
        let url = self
            .storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_url(id)?
            .ok_or_else(|| "该记录不是 URL".to_string())?;
        let preview = link_preview::fetch_preview(&url)?;
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .update_link_preview(id, preview.title.as_deref(), preview.description.as_deref())
    }

    pub fn add_file_for_testing(&self, path: &str, size_bytes: i64) -> Result<String, String> {
        self.collect_file_snapshot(&[path.to_string()], Some(&[size_bytes]))
    }

    pub fn collect_screenshot_bytes_snapshot(&self, png_bytes: &[u8]) -> Result<String, String> {
        self.collect_screenshot_bytes_snapshot_with_ocr_update(png_bytes, |_| {})
    }

    pub fn collect_screenshot_bytes_snapshot_with_ocr_update(
        &self,
        png_bytes: &[u8],
        on_ocr_update: impl Fn(String) + Send + 'static,
    ) -> Result<String, String> {
        let settings = self.load_settings()?;
        let size_bytes = png_bytes.len() as i64;
        if !cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb) {
            return Err("截图超过单记录大小限制".to_string());
        }
        let Some(cache_dir) = &self.cache_dir else {
            return Err("截图记录需要缓存目录".to_string());
        };
        let target_dir = cache_dir.join("images");
        std::fs::create_dir_all(&target_dir).map_err(|err| err.to_string())?;
        let target_path = target_dir.join(format!("screenshot-{}.png", Uuid::new_v4()));
        std::fs::write(&target_path, png_bytes).map_err(|err| err.to_string())?;
        let image = image::load_from_memory(png_bytes).map_err(|err| err.to_string())?;
        let image_path = target_path.to_string_lossy().to_string();
        let id = self
            .storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_image_item(
                &image_path,
                image.width() as i64,
                image.height() as i64,
                "png",
                size_bytes,
            )?;
        if settings.enable_ocr {
            self.spawn_screenshot_ocr(id.clone(), image_path, on_ocr_update);
        }
        Ok(id)
    }

    fn spawn_screenshot_ocr(
        &self,
        id: String,
        image_path: String,
        on_ocr_update: impl Fn(String) + Send + 'static,
    ) {
        let Some(database_path) = self.database_path.clone() else {
            return;
        };
        std::thread::spawn(move || {
            let Ok(result) = ocr_provider::recognize_image(&image_path) else {
                return;
            };
            if result.text.trim().is_empty() {
                return;
            }
            let Ok(storage) = ClipboardStorage::open(&database_path) else {
                return;
            };
            if storage.init().is_err() {
                return;
            }
            if storage.update_ocr_text(&id, &result.text).is_ok() {
                on_ocr_update(id);
            }
        });
    }

    pub fn collect_image_bytes_snapshot(&self, png_bytes: &[u8]) -> Result<Option<String>, String> {
        let settings = self.load_settings()?;
        let size_bytes = png_bytes.len() as i64;
        if !cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb) {
            return Ok(None);
        }
        let Some(cache_dir) = self.effective_cache_dir(&settings) else {
            return Ok(None);
        };
        let target_dir = cache_dir.join("images");
        std::fs::create_dir_all(&target_dir).map_err(|err| err.to_string())?;
        let target_path = target_dir.join(format!("{}.png", Uuid::new_v4()));
        std::fs::write(&target_path, png_bytes).map_err(|err| err.to_string())?;
        let image = image::load_from_memory(png_bytes).map_err(|err| err.to_string())?;
        let image_path = target_path.to_string_lossy().to_string();
        let id = self
            .storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_image_item(
                &image_path,
                image.width() as i64,
                image.height() as i64,
                "png",
                size_bytes,
            )?;
        if settings.enable_ocr {
            if let Ok(text) = ocr::recognize_image(&image_path) {
                if !text.trim().is_empty() {
                    let _ = self.update_ocr_text(&id, &text);
                }
            }
        }
        Ok(Some(id))
    }

    pub fn collect_file_snapshot(
        &self,
        paths: &[String],
        size_overrides: Option<&[i64]>,
    ) -> Result<String, String> {
        if paths.is_empty() {
            return Err("没有可记录的文件路径".to_string());
        }
        if paths.len() == 1 && is_image_path(&paths[0]) {
            return self.collect_image_file_snapshot(
                &paths[0],
                size_overrides.and_then(|values| values.first().copied()),
            );
        }

        let settings = self.load_settings()?;
        let mut entries = Vec::new();
        let mut cached_bytes = 0;
        for (index, path) in paths.iter().enumerate() {
            let metadata = std::fs::metadata(path).ok();
            let size_bytes = size_overrides
                .and_then(|values| values.get(index).copied())
                .or_else(|| metadata.as_ref().map(|value| value.len() as i64))
                .unwrap_or(0);
            let is_directory = metadata
                .as_ref()
                .map(|value| value.is_dir())
                .unwrap_or(false);
            let cached = if should_cache_path(path, size_bytes, is_directory, &settings) {
                self.copy_to_cache(path, "files", &settings).ok().flatten()
            } else {
                None
            };
            let copy_state = if cached.is_some() {
                "cached"
            } else {
                "reference_only"
            };
            let name = Path::new(path)
                .file_name()
                .and_then(|value| value.to_str())
                .unwrap_or(path)
                .to_string();
            let cached_path = cached.map(|(cached_path, bytes)| {
                cached_bytes += bytes;
                cached_path
            });
            entries.push(crate::clipboard::types::ClipboardFileEntry {
                name,
                original_path: path.to_string(),
                cached_path,
                size_bytes,
                modified_at: None,
                hash: None,
                is_directory,
                copy_state: copy_state.to_string(),
            });
        }
        let title = if entries.len() == 1 {
            entries[0].name.clone()
        } else {
            format!("{} 个文件", entries.len())
        };
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_file_item(&entries, &title, cached_bytes)
    }

    fn collect_image_file_snapshot(
        &self,
        path: &str,
        size_override: Option<i64>,
    ) -> Result<String, String> {
        let settings = self.load_settings()?;
        let metadata = std::fs::metadata(path).ok();
        let size_bytes = size_override
            .or_else(|| metadata.as_ref().map(|value| value.len() as i64))
            .unwrap_or(0);
        let is_directory = metadata
            .as_ref()
            .map(|value| value.is_dir())
            .unwrap_or(false);
        let cached = if should_cache_path(path, size_bytes, is_directory, &settings) {
            self.copy_to_cache(path, "images", &settings).ok().flatten()
        } else {
            None
        };
        let image_path = cached
            .as_ref()
            .map(|(cached_path, _)| cached_path.clone())
            .unwrap_or_else(|| path.to_string());
        let reader = image::ImageReader::open(&image_path).map_err(|err| err.to_string())?;
        let format = reader
            .format()
            .map(|format| format.extensions_str()[0].to_string())
            .unwrap_or_else(|| image_extension(path));
        let image = reader.decode().map_err(|err| err.to_string())?;
        let cache_bytes = cached.as_ref().map(|(_, bytes)| *bytes).unwrap_or(0);
        let id = self
            .storage
            .lock()
            .map_err(|err| err.to_string())?
            .insert_image_item(
                &image_path,
                image.width() as i64,
                image.height() as i64,
                &format,
                cache_bytes,
            )?;
        if settings.enable_ocr {
            if let Ok(text) = ocr::recognize_image(&image_path) {
                if !text.trim().is_empty() {
                    let _ = self.update_ocr_text(&id, &text);
                }
            }
        }
        Ok(id)
    }

    fn copy_to_cache(
        &self,
        source: &str,
        subdir: &str,
        settings: &ClipboardSettings,
    ) -> Result<Option<(String, i64)>, String> {
        let Some(cache_dir) = self.effective_cache_dir(settings) else {
            return Ok(None);
        };
        let target_dir = cache_dir.join(subdir);
        std::fs::create_dir_all(&target_dir).map_err(|err| err.to_string())?;
        let source_path = Path::new(source);
        let target_path = unique_backup_path(&target_dir, source_path);
        let bytes = std::fs::copy(source_path, &target_path).map_err(|err| err.to_string())?;
        Ok(Some((
            target_path.to_string_lossy().to_string(),
            bytes as i64,
        )))
    }

    fn effective_cache_dir(&self, settings: &ClipboardSettings) -> Option<PathBuf> {
        settings
            .backup_directory
            .as_ref()
            .map(|value| PathBuf::from(value.trim()))
            .filter(|path| !path.as_os_str().is_empty())
            .or_else(|| self.cache_dir.clone())
    }

    pub fn get_files(
        &self,
        id: &str,
    ) -> Result<Option<Vec<crate::clipboard::types::ClipboardFileEntry>>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_files(id)
    }

    pub fn get_file_copy_paths(&self, id: &str) -> Result<Option<Vec<String>>, String> {
        if let Some(files) = self.get_files(id)? {
            let paths = files
                .into_iter()
                .filter_map(|file| {
                    usable_text(file.cached_path).or_else(|| usable_text(Some(file.original_path)))
                })
                .collect::<Vec<_>>();
            if !paths.is_empty() {
                return Ok(Some(paths));
            }
        }
        if let Some((image_path, _width, _height, _format, _ocr_text)) = self.get_image_meta(id)? {
            if let Some(image_path) = usable_text(Some(image_path)) {
                return Ok(Some(vec![image_path]));
            }
        }
        Ok(None)
    }

    pub fn delete_item(&self, id: &str) -> Result<(), String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .delete_item(id)
    }

    pub fn update_note(&self, id: &str, note: Option<&str>) -> Result<Option<String>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .update_note(id, note)
    }

    pub fn rebuild_search_index(&self) -> Result<(), String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .rebuild_search_text_index()
    }

    pub fn storage_usage(&self) -> Result<Vec<(String, i64)>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .storage_usage_by_kind()
    }

    pub fn clear_history(&self, scope: &str) -> Result<(), String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .clear_history(scope)
    }

    pub fn get_text(&self, id: &str) -> Result<Option<String>, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .get_text(id)
    }

    pub fn get_copy_text(&self, id: &str) -> Result<Option<String>, String> {
        if let Some(text) = usable_text(self.get_text(id)?) {
            return Ok(Some(text));
        }

        let (html, markdown, url, _url_title, _url_description, color_hex, color_rgb) =
            self.get_rich_fields(id)?;
        if let Some(url) = usable_text(url) {
            return Ok(Some(url));
        }
        if let Some(color_hex) = usable_text(color_hex) {
            return Ok(Some(color_hex));
        }
        if let Some(color_rgb) = usable_text(color_rgb) {
            return Ok(Some(color_rgb));
        }
        if let Some(markdown) = usable_text(markdown) {
            return Ok(Some(markdown));
        }
        if let Some(html) = usable_text(html) {
            return Ok(Some(html_to_plain_markdown(&html)));
        }

        if let Some(files) = self.get_files(id)? {
            let paths = files
                .into_iter()
                .filter_map(|file| {
                    usable_text(file.cached_path).or_else(|| usable_text(Some(file.original_path)))
                })
                .collect::<Vec<_>>();
            if !paths.is_empty() {
                return Ok(Some(paths.join("\n")));
            }
        }

        if let Some((image_path, _width, _height, _format, ocr_text)) = self.get_image_meta(id)? {
            if let Some(ocr_text) = usable_text(ocr_text) {
                return Ok(Some(ocr_text));
            }
            if let Some(image_path) = usable_text(Some(image_path)) {
                return Ok(Some(image_path));
            }
        }

        Ok(None)
    }

    pub fn get_joined_text(&self, ids: &[String]) -> Result<String, String> {
        let mut texts = Vec::new();
        for id in ids {
            if let Some(text) = self.get_copy_text(id)? {
                texts.push(text);
            }
        }
        if texts.is_empty() {
            return Err("选中的记录没有可复制的文本内容".to_string());
        }
        Ok(texts.join("\n"))
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .load_settings()
    }

    pub fn save_settings(&self, settings: &ClipboardSettings) -> Result<ClipboardSettings, String> {
        let mut normalized = settings.clone();
        normalized.backup_directory =
            normalize_backup_directory(normalized.backup_directory.as_deref())?;
        self.storage
            .lock()
            .map_err(|err| err.to_string())?
            .save_settings(&normalized)?;
        Ok(normalized)
    }

    pub fn mark_written_text(&self, text: &str) -> Result<(), String> {
        let hash = hash_text(text);
        *self
            .last_written_hash
            .lock()
            .map_err(|err| err.to_string())? = Some(hash);
        Ok(())
    }

    pub fn should_ignore_text(&self, text: &str) -> Result<bool, String> {
        let hash = hash_text(text);
        let mut last = self
            .last_written_hash
            .lock()
            .map_err(|err| err.to_string())?;
        if last.as_ref() == Some(&hash) {
            *last = None;
            return Ok(true);
        }
        Ok(false)
    }

    #[cfg(target_os = "windows")]
    pub fn remember_current_foreground_window(&self) -> Result<(), String> {
        let window = crate::platform::windows::foreground::current_foreground_window();
        *self
            .last_target_window
            .lock()
            .map_err(|err| err.to_string())? = window;
        Ok(())
    }

    #[cfg(target_os = "windows")]
    pub fn paste_to_remembered_window(&self) -> Result<(), String> {
        let window = *self
            .last_target_window
            .lock()
            .map_err(|err| err.to_string())?;
        let window = window.ok_or_else(|| "没有可恢复的目标窗口".to_string())?;
        crate::platform::windows::foreground::restore_and_paste(window)
    }
}

fn unique_backup_path(target_dir: &Path, source_path: &Path) -> PathBuf {
    let stem = source_path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("clipboard-file");
    let extension = source_path.extension().and_then(|value| value.to_str());
    for index in 0.. {
        let suffix = if index == 0 {
            "备份".to_string()
        } else {
            format!("备份{}", index + 1)
        };
        let file_name = match extension {
            Some(extension) if !extension.is_empty() => {
                format!("{}-{}.{}", stem, suffix, extension)
            }
            _ => format!("{}-{}", stem, suffix),
        };
        let path = target_dir.join(file_name);
        if !path.exists() {
            return path;
        }
    }
    unreachable!()
}

fn usable_text(value: Option<String>) -> Option<String> {
    value
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
}

fn normalize_backup_directory(path: Option<&str>) -> Result<Option<String>, String> {
    let Some(path) = path.map(str::trim).filter(|value| !value.is_empty()) else {
        return Ok(None);
    };
    let path = PathBuf::from(path);
    if !path.is_absolute() {
        return Err("备份目录必须是绝对路径".to_string());
    }
    std::fs::create_dir_all(&path).map_err(|err| format!("备份目录不可用：{}", err))?;
    if !path.is_dir() {
        return Err("备份目录必须是文件夹".to_string());
    }
    Ok(Some(path.to_string_lossy().to_string()))
}

fn should_cache_path(
    path: &str,
    size_bytes: i64,
    is_directory: bool,
    settings: &ClipboardSettings,
) -> bool {
    matches!(settings.file_save_mode, ClipboardFileSaveMode::Backup)
        && !is_directory
        && cache::extension_allowed(
            path,
            &settings.file_extension_mode,
            &settings.file_extensions,
        )
        && cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb)
}

fn is_image_path(path: &str) -> bool {
    matches!(
        Path::new(path)
            .extension()
            .and_then(|value| value.to_str())
            .map(str::to_ascii_lowercase)
            .as_deref(),
        Some("png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp")
    )
}

fn image_extension(path: &str) -> String {
    Path::new(path)
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_else(|| "unknown".to_string())
}

fn html_to_plain_markdown(html: &str) -> String {
    html.replace("<br>", "\n")
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
    use std::sync::{Mutex, OnceLock};

    fn ocr_test_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }

    fn temp_service() -> (ClipboardService, PathBuf) {
        let root = std::env::temp_dir().join(format!(
            "file-keeper-clipboard-test-{}",
            uuid::Uuid::new_v4()
        ));
        let service = ClipboardService::new(root.join("clipboard.sqlite")).unwrap();
        (service, root)
    }

    #[test]
    fn service_startup_rebuilds_existing_clipboard_search_index() {
        let root = std::env::temp_dir().join(format!(
            "file-keeper-clipboard-test-{}",
            uuid::Uuid::new_v4()
        ));
        let database_path = root.join("clipboard.sqlite");
        std::fs::create_dir_all(&root).unwrap();
        let id = {
            let storage = ClipboardStorage::open(&database_path).unwrap();
            storage.init().unwrap();
            storage
                .insert_image_item("C:/Users/me/screenshot.png", 120, 80, "png", 4096)
                .unwrap()
        };
        let connection = rusqlite::Connection::open(&database_path).unwrap();
        connection.execute(
            "UPDATE clipboard_items SET ocr_text = ?2, note = ?2, search_text = ?3 WHERE id = ?1",
            rusqlite::params![id, "启动 OCR 备注", "图片 120×80"],
        ).unwrap();
        drop(connection);

        let service = ClipboardService::new(database_path).unwrap();
        let mut search = ClipboardQuery {
            query: Some("启动 OCR".to_string()),
            kind: None,
            favorite_only: None,
            source_app: None,
            start_at: None,
            end_at: None,
            limit: 20,
            offset: 0,
        };
        let items = service.list_items(&search).unwrap();

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].note.as_deref(), Some("启动 OCR 备注"));
        search.query = Some("不存在".to_string());
        assert!(service.list_items(&search).unwrap().is_empty());
    }

    fn write_test_png(path: &Path) {
        let image = image::RgbaImage::from_pixel(2, 3, image::Rgba([34, 197, 94, 255]));
        image.save(path).unwrap();
    }

    fn test_png_bytes() -> Vec<u8> {
        let mut bytes = Vec::new();
        let image = image::RgbaImage::from_pixel(2, 3, image::Rgba([34, 197, 94, 255]));
        image
            .write_to(
                &mut std::io::Cursor::new(&mut bytes),
                image::ImageFormat::Png,
            )
            .unwrap();
        bytes
    }

    #[test]
    fn sensitive_text_becomes_security_event() {
        let service = ClipboardService::in_memory().unwrap();
        service
            .add_text_for_testing("4111 1111 1111 1111", Some("browser.exe"))
            .unwrap();
        let items = service
            .list_items(&ClipboardQuery {
                query: None,
                kind: None,
                favorite_only: None,
                source_app: None,
                start_at: None,
                end_at: None,
                limit: 20,
                offset: 0,
            })
            .unwrap();

        assert_eq!(items[0].kind, ClipboardKind::SecurityEvent);
    }

    #[test]
    fn written_text_hash_can_be_ignored_once() {
        let service = ClipboardService::in_memory().unwrap();
        service.mark_written_text("hello").unwrap();

        assert!(service.should_ignore_text("hello").unwrap());
        assert!(!service.should_ignore_text("world").unwrap());
    }

    #[test]
    fn joins_selected_text_items_in_requested_order() {
        let service = ClipboardService::in_memory().unwrap();
        let first = service.add_text_for_testing("first", None).unwrap();
        let second = service.add_text_for_testing("second", None).unwrap();

        let text = service.get_joined_text(&[second, first]).unwrap();

        assert_eq!(text, "second\nfirst");
    }

    #[test]
    fn resolves_copy_text_for_url_color_html_file_and_image_items() {
        let service = ClipboardService::in_memory().unwrap();
        let url = service
            .add_text_for_testing("https://example.com/path", None)
            .unwrap();
        let color = service.add_text_for_testing("#22c55e", None).unwrap();
        let html = service
            .add_html_for_testing("<p><strong>Hello</strong><br>World</p>")
            .unwrap();
        let file = service
            .add_file_for_testing("C:/Users/me/report.pdf", 1024)
            .unwrap();
        let image = service
            .storage
            .lock()
            .unwrap()
            .insert_image_item("C:/Users/me/image.png", 120, 80, "png", 4096)
            .unwrap();

        service.update_ocr_text(&image, "image text").unwrap();

        assert_eq!(
            service.get_copy_text(&url).unwrap().as_deref(),
            Some("https://example.com/path")
        );
        assert_eq!(
            service.get_copy_text(&color).unwrap().as_deref(),
            Some("#22C55E")
        );
        assert_eq!(
            service.get_copy_text(&html).unwrap().as_deref(),
            Some("**Hello**\nWorld")
        );
        assert_eq!(
            service.get_copy_text(&file).unwrap().as_deref(),
            Some("C:/Users/me/report.pdf")
        );
        assert_eq!(
            service.get_copy_text(&image).unwrap().as_deref(),
            Some("image text")
        );
    }

    #[test]
    fn copied_files_are_backed_up_when_cache_dir_exists() {
        let (service, root) = temp_service();
        let source = root.join("report.txt");
        std::fs::write(&source, "backup me").unwrap();

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let files = service.get_files(&id).unwrap().unwrap();
        let cached_path = files[0].cached_path.as_ref().unwrap();

        assert_ne!(cached_path, &source.to_string_lossy().to_string());
        assert!(cached_path.ends_with("report-备份.txt"));
        assert_eq!(std::fs::read_to_string(cached_path).unwrap(), "backup me");
        assert_eq!(files[0].copy_state, "cached");
        assert_eq!(
            service.get_item_summary(&id).unwrap().unwrap().cache_state,
            CacheState::Cached
        );
    }

    #[test]
    fn custom_backup_directory_is_used_for_files() {
        let (service, root) = temp_service();
        let custom_dir = root.join("custom-backup");
        let mut settings = service.load_settings().unwrap();
        settings.backup_directory = Some(custom_dir.to_string_lossy().to_string());
        service.save_settings(&settings).unwrap();
        let source = root.join("report.txt");
        std::fs::write(&source, "custom backup").unwrap();

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let files = service.get_files(&id).unwrap().unwrap();
        let cached_path = PathBuf::from(files[0].cached_path.as_ref().unwrap());

        assert!(cached_path.starts_with(custom_dir.join("files")));
        assert_eq!(
            std::fs::read_to_string(cached_path).unwrap(),
            "custom backup"
        );
    }

    #[test]
    fn reference_only_mode_does_not_backup_files() {
        let (service, root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.file_save_mode = ClipboardFileSaveMode::ReferenceOnly;
        service.save_settings(&settings).unwrap();
        let source = root.join("report.txt");
        std::fs::write(&source, "reference only").unwrap();

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let files = service.get_files(&id).unwrap().unwrap();
        let item = service.get_item_summary(&id).unwrap().unwrap();

        assert!(files[0].cached_path.is_none());
        assert_eq!(files[0].copy_state, "reference_only");
        assert_eq!(item.cache_state, CacheState::ReferenceOnly);
    }

    #[test]
    fn reference_only_mode_records_image_original_path() {
        let (service, root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.file_save_mode = ClipboardFileSaveMode::ReferenceOnly;
        service.save_settings(&settings).unwrap();
        let source = root.join("sample.png");
        write_test_png(&source);

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let item = service.get_item_summary(&id).unwrap().unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert_eq!(item.kind, ClipboardKind::Image);
        assert_eq!(image_meta.0, source.to_string_lossy());
        assert_eq!(item.cache_state, CacheState::ReferenceOnly);
    }

    #[test]
    fn custom_backup_directory_is_used_for_image_files() {
        let (service, root) = temp_service();
        let custom_dir = root.join("custom-image-backup");
        let mut settings = service.load_settings().unwrap();
        settings.backup_directory = Some(custom_dir.to_string_lossy().to_string());
        service.save_settings(&settings).unwrap();
        let source = root.join("sample.png");
        write_test_png(&source);

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert!(PathBuf::from(&image_meta.0).starts_with(custom_dir.join("images")));
    }

    #[test]
    fn copied_image_file_is_recorded_as_image_and_backed_up() {
        let (service, root) = temp_service();
        let source = root.join("sample.png");
        write_test_png(&source);

        let id = service
            .collect_file_snapshot(&[source.to_string_lossy().to_string()], None)
            .unwrap();
        let item = service.get_item_summary(&id).unwrap().unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert_eq!(item.kind, ClipboardKind::Image);
        assert_ne!(image_meta.0, source.to_string_lossy());
        assert_eq!(image_meta.1, 2);
        assert_eq!(image_meta.2, 3);
        assert!(std::path::Path::new(&image_meta.0).exists());
    }

    #[test]
    fn image_bytes_are_saved_to_custom_backup_directory() {
        let (service, root) = temp_service();
        let custom_dir = root.join("custom-byte-backup");
        let mut settings = service.load_settings().unwrap();
        settings.backup_directory = Some(custom_dir.to_string_lossy().to_string());
        settings.enable_ocr = false;
        service.save_settings(&settings).unwrap();

        let id = service
            .collect_image_bytes_snapshot(&test_png_bytes())
            .unwrap()
            .unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert!(PathBuf::from(&image_meta.0).starts_with(custom_dir.join("images")));
        assert_eq!(image_meta.1, 2);
        assert_eq!(image_meta.2, 3);
    }

    #[test]
    fn reference_only_mode_still_saves_image_bytes() {
        let (service, root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.file_save_mode = ClipboardFileSaveMode::ReferenceOnly;
        service.save_settings(&settings).unwrap();

        let id = service
            .collect_image_bytes_snapshot(&test_png_bytes())
            .unwrap()
            .unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert!(
            PathBuf::from(&image_meta.0).starts_with(root.join("clipboard-cache").join("images"))
        );
        assert_eq!(image_meta.1, 2);
        assert_eq!(image_meta.2, 3);
    }

    #[test]
    fn invalid_backup_directory_is_rejected() {
        let (service, root) = temp_service();
        let file_path = root.join("not-a-directory");
        std::fs::write(&file_path, "file").unwrap();
        let mut settings = service.load_settings().unwrap();
        settings.backup_directory = Some(file_path.to_string_lossy().to_string());

        let result = service.save_settings(&settings);

        assert!(result.is_err());
    }

    #[test]
    fn ocr_text_is_saved_as_image_note() {
        let service = ClipboardService::in_memory().unwrap();
        let image = service
            .storage
            .lock()
            .unwrap()
            .insert_image_item("C:/Users/me/image.png", 120, 80, "png", 4096)
            .unwrap();

        service
            .update_ocr_text(&image, "  识别出来的文字  ")
            .unwrap();
        let item = service.get_item_summary(&image).unwrap().unwrap();

        assert_eq!(item.note.as_deref(), Some("识别出来的文字"));
    }

    #[test]
    fn updates_note_through_service() {
        let service = ClipboardService::in_memory().unwrap();
        let id = service.add_text_for_testing("hello", None).unwrap();

        let note = service.update_note(&id, Some("  重要  ")).unwrap();
        let item = service.get_item_summary(&id).unwrap().unwrap();

        assert_eq!(note.as_deref(), Some("重要"));
        assert_eq!(item.note.as_deref(), Some("重要"));
    }

    #[test]
    fn screenshot_bytes_are_saved_as_cached_image_record() {
        let (service, root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.enable_ocr = false;
        service.save_settings(&settings).unwrap();

        let id = service
            .collect_screenshot_bytes_snapshot(&test_png_bytes())
            .unwrap();
        let item = service.get_item_summary(&id).unwrap().unwrap();
        let image_meta = service.get_image_meta(&id).unwrap().unwrap();

        assert_eq!(item.kind, ClipboardKind::Image);
        assert_eq!(item.cache_state, CacheState::Cached);
        assert!(
            PathBuf::from(&image_meta.0).starts_with(root.join("clipboard-cache").join("images"))
        );
        assert!(std::path::Path::new(&image_meta.0).exists());
        assert_eq!(image_meta.1, 2);
        assert_eq!(image_meta.2, 3);
    }

    #[test]
    fn screenshot_snapshot_does_not_wait_for_ocr() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;

        let _guard = ocr_test_lock();
        let (service, _root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.enable_ocr = true;
        service.save_settings(&settings).unwrap();
        let (ocr_started_tx, ocr_started_rx) = mpsc::channel();
        let (release_ocr_tx, release_ocr_rx) = mpsc::channel();
        let release_ocr_rx = Arc::new(std::sync::Mutex::new(release_ocr_rx));
        crate::clipboard::ocr_provider::set_recognizer_for_testing(move |_image_path| {
            let _ = ocr_started_tx.send(());
            let _ = release_ocr_rx.lock().unwrap().recv();
            Ok(crate::clipboard::ocr_provider::OcrResult {
                text: "delayed text".to_string(),
                engine: "test".to_string(),
                elapsed_ms: 0,
                blocks: Vec::new(),
            })
        });

        let (return_tx, return_rx) = mpsc::channel();
        std::thread::spawn(move || {
            let id = service
                .collect_screenshot_bytes_snapshot(&test_png_bytes())
                .unwrap();
            let _ = return_tx.send(id);
        });
        let ocr_started = ocr_started_rx
            .recv_timeout(Duration::from_millis(50))
            .is_ok();
        let id = return_rx.recv_timeout(Duration::from_millis(50)).unwrap();
        let _ = release_ocr_tx.send(());
        crate::clipboard::ocr_provider::clear_recognizer_for_testing();

        assert!(ocr_started, "测试必须确认慢 OCR 已经开始，才有意义");
        assert!(!id.is_empty());
    }

    #[test]
    fn screenshot_ocr_update_notifies_when_note_is_saved() {
        use std::sync::mpsc;
        use std::time::Duration;

        let _guard = ocr_test_lock();
        let (service, _root) = temp_service();
        let mut settings = service.load_settings().unwrap();
        settings.enable_ocr = true;
        service.save_settings(&settings).unwrap();
        crate::clipboard::ocr_provider::set_recognizer_for_testing(|_image_path| {
            Ok(crate::clipboard::ocr_provider::OcrResult {
                text: "OCR text".to_string(),
                engine: "test".to_string(),
                elapsed_ms: 0,
                blocks: Vec::new(),
            })
        });
        let (updated_tx, updated_rx) = mpsc::channel();

        let id = service
            .collect_screenshot_bytes_snapshot_with_ocr_update(
                &test_png_bytes(),
                move |updated_id| {
                    let _ = updated_tx.send(updated_id);
                },
            )
            .unwrap();
        let updated_id = updated_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        crate::clipboard::ocr_provider::clear_recognizer_for_testing();
        let item = service.get_item_summary(&id).unwrap().unwrap();

        assert_eq!(updated_id, id);
        assert_eq!(item.note.as_deref(), Some("OCR text"));
    }

    #[test]
    fn screenshot_ocr_text_is_saved_as_note() {
        let service = ClipboardService::in_memory().unwrap();
        let image = service
            .storage
            .lock()
            .unwrap()
            .insert_image_item("C:/Users/me/screenshot.png", 120, 80, "png", 4096)
            .unwrap();

        service
            .update_ocr_text(&image, "  screenshot text  ")
            .unwrap();
        let item = service.get_item_summary(&image).unwrap().unwrap();

        assert_eq!(item.note.as_deref(), Some("screenshot text"));
    }
}
