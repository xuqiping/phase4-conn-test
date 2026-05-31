use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardKind {
    Text,
    Html,
    Image,
    File,
    Url,
    Color,
    Mixed,
    SecurityEvent,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardPasteFormat {
    Original,
    PlainText,
    Html,
    Markdown,
    ImagePng,
    ImageJpeg,
    FileCopy,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CacheState {
    None,
    Cached,
    ReferenceOnly,
    Cleaned,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FileExtensionMode {
    AllowAll,
    AllowList,
    BlockList,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardFileSaveMode {
    Backup,
    ReferenceOnly,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardSourceApp {
    pub process_name: String,
    pub window_title: String,
    pub pid: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardItemSummary {
    pub id: String,
    pub kind: ClipboardKind,
    pub title: String,
    pub summary: String,
    pub source_app: Option<ClipboardSourceApp>,
    pub created_at: i64,
    pub last_used_at: Option<i64>,
    pub use_count: i64,
    pub is_favorite: bool,
    pub is_pinned: bool,
    pub thumbnail_path: Option<String>,
    pub cache_bytes: i64,
    pub cache_state: CacheState,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardFileEntry {
    pub name: String,
    pub original_path: String,
    pub cached_path: Option<String>,
    pub size_bytes: i64,
    pub modified_at: Option<i64>,
    pub hash: Option<String>,
    pub is_directory: bool,
    pub copy_state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardItemDetail {
    #[serde(flatten)]
    pub summary: ClipboardItemSummary,
    pub text: Option<String>,
    pub html: Option<String>,
    pub sanitized_html: Option<String>,
    pub markdown: Option<String>,
    pub image_path: Option<String>,
    pub image_width: Option<i64>,
    pub image_height: Option<i64>,
    pub image_format: Option<String>,
    pub ocr_text: Option<String>,
    pub files: Option<Vec<ClipboardFileEntry>>,
    pub url: Option<String>,
    pub url_title: Option<String>,
    pub url_description: Option<String>,
    pub url_thumbnail_path: Option<String>,
    pub color_hex: Option<String>,
    pub color_rgb: Option<String>,
    pub security_reason: Option<String>,
    pub available_formats: Vec<ClipboardPasteFormat>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardQuery {
    pub query: Option<String>,
    pub kind: Option<String>,
    pub favorite_only: Option<bool>,
    pub source_app: Option<String>,
    pub start_at: Option<i64>,
    pub end_at: Option<i64>,
    pub limit: i64,
    pub offset: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardTypeLimitMb {
    pub image: i64,
    pub file: i64,
    pub html: i64,
    pub link_preview: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardSettings {
    pub monitor_enabled: bool,
    pub quick_panel_shortcut: String,
    pub auto_paste: bool,
    pub protect_sensitive_content: bool,
    pub enable_ocr: bool,
    pub enable_link_preview: bool,
    pub total_non_text_limit_mb: i64,
    pub item_size_limit_mb: i64,
    pub type_limits_mb: ClipboardTypeLimitMb,
    #[serde(default = "default_file_save_mode")]
    pub file_save_mode: ClipboardFileSaveMode,
    #[serde(default)]
    pub backup_directory: Option<String>,
    pub file_extension_mode: FileExtensionMode,
    pub file_extensions: Vec<String>,
    pub excluded_apps: Vec<String>,
}

fn default_file_save_mode() -> ClipboardFileSaveMode {
    ClipboardFileSaveMode::Backup
}

impl Default for ClipboardSettings {
    fn default() -> Self {
        Self {
            monitor_enabled: true,
            quick_panel_shortcut: "CommandOrControl+Shift+V".to_string(),
            auto_paste: false,
            protect_sensitive_content: true,
            enable_ocr: true,
            enable_link_preview: false,
            total_non_text_limit_mb: 2048,
            item_size_limit_mb: 200,
            type_limits_mb: ClipboardTypeLimitMb {
                image: 1024,
                file: 2048,
                html: 500,
                link_preview: 200,
            },
            file_save_mode: ClipboardFileSaveMode::Backup,
            backup_directory: None,
            file_extension_mode: FileExtensionMode::AllowAll,
            file_extensions: Vec::new(),
            excluded_apps: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardStorageTypeUsage {
    pub kind: String,
    pub bytes: i64,
    pub limit_bytes: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardStorageUsage {
    pub total_bytes: i64,
    pub limit_bytes: i64,
    pub by_type: Vec<ClipboardStorageTypeUsage>,
}
