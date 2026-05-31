use rusqlite::{params, Connection};
use uuid::Uuid;
use crate::clipboard::search::normalize_search_text;
use crate::clipboard::types::{CacheState, ClipboardItemSummary, ClipboardKind, ClipboardQuery, ClipboardSettings, ClipboardSourceApp};

pub struct ClipboardStorage {
    connection: Connection,
}

impl ClipboardStorage {
    pub fn in_memory() -> Result<Self, String> {
        Ok(Self { connection: Connection::open_in_memory().map_err(|err| err.to_string())? })
    }

    pub fn open(path: &std::path::Path) -> Result<Self, String> {
        Ok(Self { connection: Connection::open(path).map_err(|err| err.to_string())? })
    }

    pub fn init(&self) -> Result<(), String> {
        self.connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS clipboard_items (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                search_text TEXT NOT NULL,
                source_process TEXT,
                source_title TEXT,
                source_pid INTEGER,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER,
                use_count INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                thumbnail_path TEXT,
                cache_bytes INTEGER NOT NULL DEFAULT 0,
                cache_state TEXT NOT NULL DEFAULT 'none',
                text TEXT,
                security_reason TEXT,
                files_json TEXT,
                image_path TEXT,
                image_width INTEGER,
                image_height INTEGER,
                image_format TEXT,
                ocr_text TEXT,
                html TEXT,
                sanitized_html TEXT,
                markdown TEXT,
                url TEXT,
                url_title TEXT,
                url_description TEXT,
                url_thumbnail_path TEXT,
                color_hex TEXT,
                color_rgb TEXT,
                note TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_clipboard_items_created_at ON clipboard_items(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_clipboard_items_kind ON clipboard_items(kind);
            CREATE TABLE IF NOT EXISTS clipboard_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            "
        ).map_err(|err| err.to_string())?;
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN files_json TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_path TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_width INTEGER", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_height INTEGER", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_format TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN ocr_text TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN html TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN sanitized_html TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN markdown TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_title TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_description TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_thumbnail_path TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN color_hex TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN color_rgb TEXT", []);
        let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN note TEXT", []);
        Ok(())
    }

    pub fn save_settings(&self, settings: &ClipboardSettings) -> Result<(), String> {
        let value = serde_json::to_string(settings).map_err(|err| err.to_string())?;
        self.connection.execute(
            "INSERT INTO clipboard_settings (key, value) VALUES ('settings', ?1)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![value],
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        let result: Result<String, rusqlite::Error> = self.connection.query_row(
            "SELECT value FROM clipboard_settings WHERE key = 'settings'",
            [],
            |row| row.get(0),
        );

        match result {
            Ok(value) => serde_json::from_str(&value).map_err(|err| err.to_string()),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(ClipboardSettings::default()),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn insert_text_item(&self, text: &str, title: &str, source_process: Option<&str>) -> Result<String, String> {
        let now = current_millis();
        if let Some(id) = self.find_latest_text_item(text)? {
            self.touch_item(&id, now)?;
            return Ok(id);
        }

        let id = Uuid::new_v4().to_string();
        let summary = text.chars().take(160).collect::<String>();
        let search_text = normalize_search_text(text);
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, source_process, source_title, source_pid,
                created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, text
            ) VALUES (?1, 'text', ?2, ?3, ?4, ?5, NULL, NULL, ?6, 0, 0, 0, 0, 'none', ?7)",
            params![id, title, summary, search_text, source_process, now, text],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    fn find_latest_text_item(&self, text: &str) -> Result<Option<String>, String> {
        let result: Result<String, rusqlite::Error> = self.connection.query_row(
            "SELECT id FROM clipboard_items WHERE kind = 'text' AND text = ?1 ORDER BY created_at DESC LIMIT 1",
            params![text],
            |row| row.get(0),
        );
        match result {
            Ok(id) => Ok(Some(id)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    fn touch_item(&self, id: &str, now: i64) -> Result<(), String> {
        self.connection.execute(
            "UPDATE clipboard_items SET created_at = ?1, last_used_at = ?1 WHERE id = ?2",
            params![now, id],
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn insert_security_event(&self, reason: &str, source_process: Option<&str>) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, source_process, source_title, source_pid,
                created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, security_reason
            ) VALUES (?1, 'security_event', ?2, ?3, ?4, ?5, NULL, NULL, ?6, 0, 0, 0, 0, 'none', ?7)",
            params![id, format!("已拦截敏感内容：{}", reason), reason, reason, source_process, now, reason],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn list_items(&self, query: &ClipboardQuery) -> Result<Vec<ClipboardItemSummary>, String> {
        let search = query.query.as_ref().map(|value| format!("%{}%", normalize_search_text(value)));
        let kind = query.kind.as_ref().filter(|value| value.as_str() != "all");
        let favorite_only = query.favorite_only.unwrap_or(false);

        let mut statement = self.connection.prepare(
            "SELECT id, kind, title, summary, source_process, source_title, source_pid, created_at,
                    last_used_at, use_count, is_favorite, is_pinned, thumbnail_path, cache_bytes, cache_state, note
             FROM clipboard_items
             WHERE (?1 IS NULL OR search_text LIKE ?1)
               AND (?2 IS NULL OR kind = ?2)
               AND (?3 = 0 OR is_favorite = 1)
               AND (?4 IS NULL OR created_at >= ?4)
               AND (?5 IS NULL OR created_at <= ?5)
             ORDER BY created_at DESC
             LIMIT ?6 OFFSET ?7"
        ).map_err(|err| err.to_string())?;

        let rows = statement.query_map(
            params![search, kind, if favorite_only { 1 } else { 0 }, query.start_at, query.end_at, query.limit, query.offset],
            map_summary_row,
        ).map_err(|err| err.to_string())?;

        rows.collect::<Result<Vec<_>, _>>().map_err(|err| err.to_string())
    }

    pub fn get_item_summary(&self, id: &str) -> Result<Option<ClipboardItemSummary>, String> {
        let result = self.connection.query_row(
            "SELECT id, kind, title, summary, source_process, source_title, source_pid, created_at,
                    last_used_at, use_count, is_favorite, is_pinned, thumbnail_path, cache_bytes, cache_state, note
             FROM clipboard_items
             WHERE id = ?1",
            params![id],
            map_summary_row,
        );
        match result {
            Ok(item) => Ok(Some(item)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn insert_html_item(&self, html: &str, markdown: &str) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        let title = markdown.chars().take(80).collect::<String>();
        self.connection.execute(
            "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, html, sanitized_html, markdown)
             VALUES (?1, 'html', ?2, ?3, ?4, ?5, 0, 0, 0, 0, 'none', ?6, ?6, ?7)",
            params![id, title, title, normalize_search_text(markdown), now, html, markdown],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn insert_url_item(&self, url: &str) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        self.connection.execute(
            "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, url)
             VALUES (?1, 'url', ?2, ?2, ?3, ?4, 0, 0, 0, 0, 'none', ?2)",
            params![id, url, normalize_search_text(url), now],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn insert_color_item(&self, hex: &str, rgb: &str) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        self.connection.execute(
            "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, color_hex, color_rgb)
             VALUES (?1, 'color', ?2, ?3, ?4, ?5, 0, 0, 0, 0, 'none', ?2, ?3)",
            params![id, hex, rgb, normalize_search_text(&format!("{} {}", hex, rgb)), now],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn get_rich_fields(&self, id: &str) -> Result<(Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>), String> {
        let result = self.connection.query_row(
            "SELECT html, markdown, url, url_title, url_description, color_hex, color_rgb FROM clipboard_items WHERE id = ?1",
            params![id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?, row.get(5)?, row.get(6)?)),
        );
        match result {
            Ok(fields) => Ok(fields),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok((None, None, None, None, None, None, None)),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn insert_image_item(&self, image_path: &str, width: i64, height: i64, format: &str, cache_bytes: i64) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        let title = format!("图片 {}×{}", width, height);
        let cache_state = if cache_bytes > 0 { "cached" } else { "reference_only" };
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned,
                thumbnail_path, cache_bytes, cache_state, image_path, image_width, image_height, image_format
            ) VALUES (?1, 'image', ?2, ?3, ?4, ?5, 0, 0, 0, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![id, title, title, title, now, image_path, cache_bytes, cache_state, image_path, width, height, format],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn update_ocr_text(&self, id: &str, ocr_text: &str) -> Result<(), String> {
        let normalized_note = ocr_text.trim().to_string();
        let normalized_note = if normalized_note.is_empty() { None } else { Some(normalized_note) };
        let changed = self.connection.execute(
            "UPDATE clipboard_items SET ocr_text = ?2, note = ?2 WHERE id = ?1",
            params![id, normalized_note],
        ).map_err(|err| err.to_string())?;
        if changed == 0 {
            return Err("剪贴板记录不存在".to_string());
        }
        let search_text = self.build_search_text(id, normalized_note.as_deref())?;
        self.connection.execute(
            "UPDATE clipboard_items SET search_text = ?2 WHERE id = ?1",
            params![id, search_text],
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn update_link_preview(&self, id: &str, title: Option<&str>, description: Option<&str>) -> Result<(), String> {
        self.connection.execute(
            "UPDATE clipboard_items SET url_title = ?2, url_description = ?3 WHERE id = ?1",
            params![id, title, description],
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn get_url(&self, id: &str) -> Result<Option<String>, String> {
        let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
            "SELECT url FROM clipboard_items WHERE id = ?1",
            params![id],
            |row| row.get(0),
        );
        match result {
            Ok(url) => Ok(url),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn get_image_meta(&self, id: &str) -> Result<Option<(String, i64, i64, String, Option<String>)>, String> {
        let result = self.connection.query_row(
            "SELECT image_path, image_width, image_height, image_format, ocr_text FROM clipboard_items WHERE id = ?1",
            params![id],
            |row| {
                let path: Option<String> = row.get(0)?;
                let width: Option<i64> = row.get(1)?;
                let height: Option<i64> = row.get(2)?;
                let format: Option<String> = row.get(3)?;
                let ocr_text: Option<String> = row.get(4)?;
                Ok(path.map(|path| (
                    path,
                    width.unwrap_or_default(),
                    height.unwrap_or_default(),
                    format.unwrap_or_else(|| "unknown".to_string()),
                    ocr_text,
                )))
            },
        );
        match result {
            Ok(meta) => Ok(meta),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn insert_file_item(&self, files: &[crate::clipboard::types::ClipboardFileEntry], title: &str, cache_bytes: i64) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        let files_json = serde_json::to_string(files).map_err(|err| err.to_string())?;
        let summary = files.iter().map(|file| file.name.clone()).collect::<Vec<_>>().join(", ");
        let search_text = normalize_search_text(&summary);
        let cache_state = if cache_bytes > 0 { "cached" } else { "reference_only" };
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned,
                cache_bytes, cache_state, files_json
            ) VALUES (?1, 'file', ?2, ?3, ?4, ?5, 0, 0, 0, ?6, ?7, ?8)",
            params![id, title, summary, search_text, now, cache_bytes, cache_state, files_json],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn get_files(&self, id: &str) -> Result<Option<Vec<crate::clipboard::types::ClipboardFileEntry>>, String> {
        let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
            "SELECT files_json FROM clipboard_items WHERE id = ?1",
            params![id],
            |row| row.get(0),
        );
        match result {
            Ok(Some(value)) => serde_json::from_str(&value).map(Some).map_err(|err| err.to_string()),
            Ok(None) => Ok(None),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn get_text(&self, id: &str) -> Result<Option<String>, String> {
        let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
            "SELECT text FROM clipboard_items WHERE id = ?1",
            params![id],
            |row| row.get(0),
        );
        match result {
            Ok(text) => Ok(text),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn update_note(&self, id: &str, note: Option<&str>) -> Result<Option<String>, String> {
        let normalized_note = note.map(str::trim).filter(|value| !value.is_empty()).map(str::to_string);
        let search_text = self.build_search_text(id, normalized_note.as_deref())?;
        let changed = self.connection.execute(
            "UPDATE clipboard_items SET note = ?2, search_text = ?3 WHERE id = ?1",
            params![id, normalized_note, search_text],
        ).map_err(|err| err.to_string())?;
        if changed == 0 {
            return Err("剪贴板记录不存在".to_string());
        }
        Ok(normalized_note)
    }

    fn build_search_text(&self, id: &str, note: Option<&str>) -> Result<String, String> {
        let result = self.connection.query_row(
            "SELECT title, summary, text, security_reason, files_json, image_path, ocr_text, html, markdown, url, url_title, url_description, color_hex, color_rgb
             FROM clipboard_items
             WHERE id = ?1",
            params![id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<String>>(2)?,
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, Option<String>>(4)?,
                    row.get::<_, Option<String>>(5)?,
                    row.get::<_, Option<String>>(6)?,
                    row.get::<_, Option<String>>(7)?,
                    row.get::<_, Option<String>>(8)?,
                    row.get::<_, Option<String>>(9)?,
                    row.get::<_, Option<String>>(10)?,
                    row.get::<_, Option<String>>(11)?,
                    row.get::<_, Option<String>>(12)?,
                    row.get::<_, Option<String>>(13)?,
                ))
            },
        );
        let (title, summary, text, security_reason, files_json, image_path, ocr_text, html, markdown, url, url_title, url_description, color_hex, color_rgb) = match result {
            Ok(value) => value,
            Err(rusqlite::Error::QueryReturnedNoRows) => return Err("剪贴板记录不存在".to_string()),
            Err(err) => return Err(err.to_string()),
        };

        let mut parts = vec![title, summary];
        parts.extend([text, security_reason, image_path, ocr_text, html, markdown, url, url_title, url_description, color_hex, color_rgb].into_iter().flatten());
        if let Some(files_json) = files_json {
            if let Ok(files) = serde_json::from_str::<Vec<crate::clipboard::types::ClipboardFileEntry>>(&files_json) {
                for file in files {
                    parts.push(file.name);
                    parts.push(file.original_path);
                    if let Some(cached_path) = file.cached_path {
                        parts.push(cached_path);
                    }
                }
            }
        }
        if let Some(note) = note {
            parts.push(note.to_string());
        }

        Ok(normalize_search_text(&parts.join(" ")))
    }

    pub fn storage_usage_by_kind(&self) -> Result<Vec<(String, i64)>, String> {
        let mut statement = self.connection.prepare(
            "SELECT kind, COALESCE(SUM(cache_bytes), 0) FROM clipboard_items GROUP BY kind"
        ).map_err(|err| err.to_string())?;
        let rows = statement.query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)))
            .map_err(|err| err.to_string())?;
        rows.collect::<Result<Vec<_>, _>>().map_err(|err| err.to_string())
    }

    pub fn clear_history(&self, scope: &str) -> Result<(), String> {
        match scope {
            "non_text_cache" => {
                self.connection.execute(
                    "UPDATE clipboard_items SET cache_bytes = 0, cache_state = 'cleaned', thumbnail_path = NULL WHERE kind != 'text'",
                    [],
                ).map_err(|err| err.to_string())?;
            }
            "security_events" => {
                self.connection.execute("DELETE FROM clipboard_items WHERE kind = 'security_event'", [])
                    .map_err(|err| err.to_string())?;
            }
            _ => {
                self.connection.execute("DELETE FROM clipboard_items", [])
                    .map_err(|err| err.to_string())?;
            }
        }
        Ok(())
    }

    pub fn delete_item(&self, id: &str) -> Result<(), String> {
        self.connection.execute("DELETE FROM clipboard_items WHERE id = ?1", params![id])
            .map_err(|err| err.to_string())?;
        Ok(())
    }
}

fn map_summary_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<ClipboardItemSummary> {
    let process_name: Option<String> = row.get(4)?;
    let window_title: Option<String> = row.get(5)?;
    let pid: Option<i64> = row.get(6)?;
    Ok(ClipboardItemSummary {
        id: row.get(0)?,
        kind: parse_kind(row.get::<_, String>(1)?.as_str()),
        title: row.get(2)?,
        summary: row.get(3)?,
        source_app: process_name.map(|process_name| ClipboardSourceApp {
            process_name,
            window_title: window_title.unwrap_or_default(),
            pid: pid.map(|value| value as u32),
        }),
        created_at: row.get(7)?,
        last_used_at: row.get(8)?,
        use_count: row.get(9)?,
        is_favorite: row.get::<_, i64>(10)? == 1,
        is_pinned: row.get::<_, i64>(11)? == 1,
        thumbnail_path: row.get(12)?,
        cache_bytes: row.get(13)?,
        cache_state: parse_cache_state(row.get::<_, String>(14)?.as_str()),
        note: row.get(15)?,
    })
}

fn parse_kind(value: &str) -> ClipboardKind {
    match value {
        "html" => ClipboardKind::Html,
        "image" => ClipboardKind::Image,
        "file" => ClipboardKind::File,
        "url" => ClipboardKind::Url,
        "color" => ClipboardKind::Color,
        "mixed" => ClipboardKind::Mixed,
        "security_event" => ClipboardKind::SecurityEvent,
        _ => ClipboardKind::Text,
    }
}

fn parse_cache_state(value: &str) -> CacheState {
    match value {
        "cached" => CacheState::Cached,
        "reference_only" => CacheState::ReferenceOnly,
        "cleaned" => CacheState::Cleaned,
        _ => CacheState::None,
    }
}

fn current_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query() -> ClipboardQuery {
        ClipboardQuery {
            query: None,
            kind: None,
            favorite_only: None,
            source_app: None,
            start_at: None,
            end_at: None,
            limit: 20,
            offset: 0,
        }
    }

    #[test]
    fn persists_settings() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let mut settings = ClipboardSettings::default();
        settings.auto_paste = true;
        settings.total_non_text_limit_mb = 4096;
        settings.backup_directory = Some("D:/ClipboardBackup".to_string());

        storage.save_settings(&settings).unwrap();
        let loaded = storage.load_settings().unwrap();

        assert!(loaded.auto_paste);
        assert_eq!(loaded.total_non_text_limit_mb, 4096);
        assert_eq!(loaded.backup_directory.as_deref(), Some("D:/ClipboardBackup"));
    }

    #[test]
    fn loads_old_settings_without_backup_directory() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        storage.connection.execute(
            "INSERT INTO clipboard_settings (key, value) VALUES ('settings', ?1)",
            params![r#"{
                "monitorEnabled":true,
                "quickPanelShortcut":"CommandOrControl+Shift+V",
                "autoPaste":false,
                "protectSensitiveContent":true,
                "enableOcr":true,
                "enableLinkPreview":false,
                "totalNonTextLimitMb":2048,
                "itemSizeLimitMb":200,
                "typeLimitsMb":{"image":1024,"file":2048,"html":500,"linkPreview":200},
                "fileSaveMode":"backup",
                "fileExtensionMode":"allow_all",
                "fileExtensions":[],
                "excludedApps":[]
            }"#],
        ).unwrap();

        let loaded = storage.load_settings().unwrap();

        assert_eq!(loaded.backup_directory, None);
    }

    #[test]
    fn inserts_and_lists_text_items_newest_first() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        let first_id = storage.insert_text_item("hello", "hello", Some("notepad.exe")).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let second_id = storage.insert_text_item("world", "world", Some("code.exe")).unwrap();
        let items = storage.list_items(&query()).unwrap();

        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, second_id);
        assert_eq!(items[1].id, first_id);
        assert_eq!(items[0].title, "world");
    }

    #[test]
    fn filters_by_search_text() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        storage.insert_text_item("hello alpha", "hello alpha", Some("notepad.exe")).unwrap();
        storage.insert_text_item("beta", "beta", Some("code.exe")).unwrap();
        let mut query = query();
        query.query = Some("alpha".to_string());

        let items = storage.list_items(&query).unwrap();

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "hello alpha");
    }

    #[test]
    fn duplicate_text_refreshes_existing_item_to_top() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        let first_id = storage.insert_text_item("hello", "hello", None).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let second_id = storage.insert_text_item("world", "world", None).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let duplicate_id = storage.insert_text_item("hello", "hello", None).unwrap();
        let items = storage.list_items(&query()).unwrap();

        assert_eq!(duplicate_id, first_id);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, first_id);
        assert_eq!(items[1].id, second_id);
        assert!(items[0].last_used_at.is_some());
    }

    #[test]
    fn filters_by_created_at_range() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let old_id = storage.insert_text_item("old", "old", None).unwrap();
        let new_id = storage.insert_text_item("new", "new", None).unwrap();
        storage.connection.execute("UPDATE clipboard_items SET created_at = 1000 WHERE id = ?1", params![old_id]).unwrap();
        storage.connection.execute("UPDATE clipboard_items SET created_at = 3000 WHERE id = ?1", params![new_id]).unwrap();
        let mut query = query();
        query.start_at = Some(2000);
        query.end_at = Some(4000);

        let items = storage.list_items(&query).unwrap();

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "new");
    }

    #[test]
    fn updates_note_and_searches_note_text() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let id = storage.insert_text_item("plain content", "plain content", None).unwrap();

        let note = storage.update_note(&id, Some("  项目备注  ")).unwrap();
        let mut search = query();
        search.query = Some("项目备注".to_string());
        let items = storage.list_items(&search).unwrap();

        assert_eq!(note.as_deref(), Some("项目备注"));
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].id, id);
        assert_eq!(items[0].note.as_deref(), Some("项目备注"));

        storage.update_note(&id, Some(" ")).unwrap();
        let items = storage.list_items(&search).unwrap();
        assert!(items.is_empty());
    }

    #[test]
    fn deletes_items() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let id = storage.insert_text_item("hello", "hello", None).unwrap();

        storage.delete_item(&id).unwrap();

        assert!(storage.list_items(&query()).unwrap().is_empty());
    }
}
